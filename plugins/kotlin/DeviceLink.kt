package com.smartisland.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Phone side of the Meow Island TFT device: scanning, pairing (Android's own passkey dialog), connecting, automatic reconnecting and
 * pushing the step card + music data. Everything runs natively so it keeps working while the React Native app is in the background
 * (the process is kept alive by the existing island service / notification listener); the JS screen only displays state and sends commands.
 * Wi-Fi passwords pass through here once, straight into a frame: they are never stored or logged.
 */
@Suppress("DEPRECATION")
object DeviceLink {
    const val S_UNPAIRED = "unpaired"; const val S_PAIRING = "pairing"; const val S_CONNECTING = "connecting"; const val S_CONNECTED = "connected"
    const val S_RECONNECTING = "reconnecting"; const val S_DISCONNECTED = "disconnected"; const val S_BT_OFF = "bluetooth_off"; const val S_NO_PERMISSION = "no_permission"

    private val SERVICE = UUID.fromString(DeviceProtocol.SERVICE_UUID)
    private val RX = UUID.fromString(DeviceProtocol.RX_UUID)
    private val STATUS = UUID.fromString(DeviceProtocol.STATUS_UUID)
    private val CCCD = UUID.fromString(DeviceProtocol.CCCD_UUID)
    private val RECONNECT_DELAYS = longArrayOf(3000, 6000, 12000, 30000)

    class Found(val address: String, val name: String, val rssi: Int, val pairingOpen: Boolean, val bonded: Boolean)

    private val h = Handler(Looper.getMainLooper())
    @Volatile private var appCtx: Context? = null
    private var receiversOn = false

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var statusChr: BluetoothGattCharacteristic? = null
    private var state = S_UNPAIRED
    private var ready = false
    private var error = ""
    private var bondProblem = false
    private var status: DeviceProtocol.Status? = null
    private var mtu = 23
    private var pendingPair: String? = null
    private var attempts = 0
    private var encFailures = 0
    private var writeFailures = 0

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private var scanning = false
    private val found = LinkedHashMap<String, Found>()

    // what was last sent, so only changes cross the air
    private var lastStepsKey = ""; private var lastStepsAt = 0L
    private var lastMusicKey = ""; private var lastMusicAt = 0L; private var lastMusicPos = 0; private var lastMusicPlaying = false
    private var lastTimeAt = 0L
    private var tickCount = 0

    // ------------------------------------------------------------------ persistence (the device address and a display name only)
    private fun prefs() = appCtx!!.getSharedPreferences("meowdevice", Context.MODE_PRIVATE)
    fun isPaired(): Boolean = appCtx != null && prefs().getBoolean("paired", false) && prefs().getString("address", null) != null
    private fun wantConnected() = prefs().getBoolean("enabled", true)

    // ------------------------------------------------------------------ environment
    fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun adapter(): BluetoothAdapter? = (appCtx?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Safe to call from any entry point (app start, island service, notification listener, boot): starts what is needed, once. */
    fun ensureStarted(ctx: Context) {
        appCtx = ctx.applicationContext
        h.post {
            registerReceivers()
            if (isPaired() && wantConnected() && gatt == null && !ready && pendingPair == null) connectSaved(false)
            else if (!isPaired() && state != S_PAIRING) setState(S_UNPAIRED)
        }
    }

    private fun registerReceivers() {
        val ctx = appCtx ?: return
        if (receiversOn) return
        receiversOn = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                        h.post { onBondChanged(dev?.address, st, prev) }
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        h.post { onAdapterChanged(st) }
                    }
                }
            }
        }
        val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(r, f)
    }

    private fun onAdapterChanged(st: Int) {
        if (st == BluetoothAdapter.STATE_OFF || st == BluetoothAdapter.STATE_TURNING_OFF) { closeGatt(); stopScan(); if (isPaired()) setState(S_BT_OFF) }
        else if (st == BluetoothAdapter.STATE_ON && isPaired() && wantConnected()) connectSaved(false)
    }

    private fun onBondChanged(address: String?, st: Int, prev: Int) {
        if (address == null || address != pendingPair) return
        if (st == BluetoothDevice.BOND_BONDED) { prefs().edit().putBoolean("paired", true).apply(); connectSaved(false) }
        else if (st == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
            pendingPair = null; error = "Pairing did not finish. The code may have been wrong, or it timed out. Try Add Device again."
            setState(S_UNPAIRED)
        }
    }

    // ------------------------------------------------------------------ state
    private fun setState(s: String) { state = s; emitState() }
    private fun snapshot(): Map<String, Any> {
        val ctx = appCtx; val st = status
        return mapOf(
            "state" to state, "paired" to isPaired(), "ready" to ready, "scanning" to scanning,
            "name" to (if (ctx != null) prefs().getString("name", "") ?: "" else ""), "address" to (if (ctx != null) prefs().getString("address", "") ?: "" else ""),
            "ssid" to (if (ctx != null) prefs().getString("ssid", "") ?: "" else ""), "error" to error, "bondProblem" to bondProblem,
            "bluetoothOn" to (adapter()?.isEnabled == true), "permission" to (ctx != null && hasPermission(ctx)),
            "fw" to (if (st != null) "${st.fwMajor}.${st.fwMinor}" else ""), "screen" to (st?.screen ?: 0), "wifiState" to (st?.wifiState ?: 0),
            "wifiReason" to DeviceProtocol.wifiReasonText(st?.wifiReason ?: 0), "timeValid" to (st?.timeValid ?: false), "hasWifi" to (st?.hasWifi ?: false),
            "pairingOpen" to (st?.pairingOpen ?: false), "stepsFresh" to (st?.stepsFresh ?: false), "musicFresh" to (st?.musicFresh ?: false)
        )
    }
    fun state(): Map<String, Any> = snapshot()
    private fun emitState() { IslandModule.emit("deviceState", snapshot()) }

    // ------------------------------------------------------------------ scanning
    fun scanResultsJson(): String {
        val a = JSONArray()
        for (f in found.values.sortedByDescending { it.rssi }) a.put(JSONObject().put("address", f.address).put("name", f.name).put("rssi", f.rssi).put("pairingOpen", f.pairingOpen).put("bonded", f.bonded))
        return a.toString()
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val rec = r.scanRecord
            val mfg = rec?.getManufacturerSpecificData(0xFFFF)
            val open = mfg != null && mfg.size >= 3 && mfg[0] == 'M'.code.toByte() && mfg[1] == 'I'.code.toByte() && (mfg[2].toInt() and 1) != 0
            val name = rec?.deviceName ?: try { r.device.name } catch (e: SecurityException) { null } ?: "Meow Island"
            val bonded = try { r.device.bondState == BluetoothDevice.BOND_BONDED } catch (e: SecurityException) { false }
            h.post { found[r.device.address] = Found(r.device.address, name, r.rssi, open, bonded); IslandModule.emit("deviceScan", mapOf("json" to scanResultsJson())) }
        }
        override fun onScanFailed(errorCode: Int) { h.post { scanning = false; error = "Bluetooth scan failed (code $errorCode)."; emitState() } }
    }
    private val stopScanRun = Runnable { stopScan() }

    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        val ctx = appCtx ?: return false
        if (!hasPermission(ctx)) { error = "Allow Nearby devices (Bluetooth) for Meow Island first."; setState(S_NO_PERMISSION); return false }
        val a = adapter()
        if (a == null || !a.isEnabled) { error = "Turn Bluetooth on first."; setState(S_BT_OFF); return false }
        stopScan(); found.clear(); error = ""; bondProblem = false
        return try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            a.bluetoothLeScanner.startScan(listOf(filter), settings, scanCb)
            scanning = true; h.removeCallbacks(stopScanRun); h.postDelayed(stopScanRun, 25_000); emitState(); true
        } catch (e: Exception) { error = "Could not start scanning: " + (e.message ?: e.javaClass.simpleName); emitState(); false }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        h.removeCallbacks(stopScanRun)
        if (!scanning) return
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
        emitState()
    }

    // ------------------------------------------------------------------ pairing and connecting
    /** Starts Android's bonding with the chosen device. Android shows its own dialog: the user types the 6-digit code shown on the TFT. */
    @SuppressLint("MissingPermission")
    fun pair(address: String): Boolean {
        val ctx = appCtx ?: return false
        if (!hasPermission(ctx)) { setState(S_NO_PERMISSION); return false }
        val a = adapter()
        if (a == null || !a.isEnabled) { setState(S_BT_OFF); return false }
        val picked = found[address]
        stopScan(); error = ""; bondProblem = false; encFailures = 0
        if (isPaired() && prefs().getString("address", null) != address) { closeGatt() }     // replacing the old device
        return try {
            val dev = a.getRemoteDevice(address)
            pendingPair = address
            prefs().edit().putString("address", address).putString("name", picked?.name ?: dev.name ?: "Meow Island").putBoolean("paired", false).putBoolean("enabled", true).remove("ssid").apply()
            setState(S_PAIRING)
            if (dev.bondState == BluetoothDevice.BOND_BONDED) { prefs().edit().putBoolean("paired", true).apply(); connectSaved(false) }   // already bonded
            else if (!dev.createBond()) { pendingPair = null; error = "Android could not start pairing."; setState(S_UNPAIRED); return false }
            true
        } catch (e: Exception) { pendingPair = null; error = "Pairing failed: " + (e.message ?: e.javaClass.simpleName); setState(S_UNPAIRED); false }
    }

    @SuppressLint("MissingPermission")
    private fun connectSaved(auto: Boolean) {
        val ctx = appCtx ?: return
        val addr = prefs().getString("address", null) ?: return
        if (!hasPermission(ctx)) { setState(S_NO_PERMISSION); return }
        val a = adapter()
        if (a == null || !a.isEnabled) { setState(S_BT_OFF); return }
        closeGatt()
        try {
            val dev = a.getRemoteDevice(addr)
            setState(if (attempts > 0 || state == S_RECONNECTING || (isPaired() && pendingPair == null && state != S_PAIRING)) S_RECONNECTING else S_CONNECTING)
            gatt = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(ctx, auto, cb, BluetoothDevice.TRANSPORT_LE) else dev.connectGatt(ctx, auto, cb)
        } catch (e: Exception) { error = "Could not connect: " + (e.message ?: e.javaClass.simpleName); setState(S_DISCONNECTED); scheduleReconnect() }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        h.removeCallbacks(tickRun); h.removeCallbacks(reconnectRun)
        ready = false; writing = false; writeQueue.clear(); rx = null; statusChr = null
        try { gatt?.disconnect() } catch (e: Exception) { }
        try { gatt?.close() } catch (e: Exception) { }
        gatt = null
    }

    private val reconnectRun = Runnable { if (isPaired() && wantConnected() && gatt == null) connectSaved(attempts > RECONNECT_DELAYS.size) }
    private fun scheduleReconnect() {
        h.removeCallbacks(reconnectRun)
        if (!isPaired() || !wantConnected() || state == S_BT_OFF) return
        val i = attempts; attempts++
        if (i < RECONNECT_DELAYS.size) h.postDelayed(reconnectRun, RECONNECT_DELAYS[i])
        else h.post(reconnectRun)                       // from here on Android reconnects by itself when the device comes into range (autoConnect)
        if (state != S_RECONNECTING) setState(S_RECONNECTING)
    }

    /** Pause: keep the pairing but stop connecting (the device keeps showing its clock). */
    fun disconnect() { prefs().edit().putBoolean("enabled", false).apply(); attempts = 0; closeGatt(); setState(if (isPaired()) S_DISCONNECTED else S_UNPAIRED) }
    fun reconnect() { prefs().edit().putBoolean("enabled", true).apply(); attempts = 0; error = ""; bondProblem = false; if (isPaired()) connectSaved(false) }

    /** Forgets the device on the phone, and tells the device to forget this phone (and optionally its Wi-Fi) when it is connected. */
    fun unpair(clearWifi: Boolean): Boolean {
        val wasReady = ready
        if (wasReady) { send(DeviceProtocol.unpair(clearWifi)); h.postDelayed({ finishUnpair() }, 1500) } else finishUnpair()
        return wasReady
    }
    private fun finishUnpair() {
        closeGatt(); stopScan(); pendingPair = null; status = null; error = ""; bondProblem = false; attempts = 0
        prefs().edit().clear().apply()
        setState(S_UNPAIRED)
    }

    // ------------------------------------------------------------------ GATT
    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            h.post { onConn(g, st, newState) }
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) { h.post { mtu = m; discover(g) } }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) { h.post { onServices(g, st) } }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { h.post { onCccd(g, st) } }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { h.post { onWritten(st) } }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { val v = c.value; h.post { onStatusBytes(v) } }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { h.post { onStatusBytes(value) } }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { val v = c.value; if (st == BluetoothGatt.GATT_SUCCESS) h.post { onStatusBytes(v) } }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) { if (st == BluetoothGatt.GATT_SUCCESS) h.post { onStatusBytes(value) } }
    }

    @SuppressLint("MissingPermission")
    private fun onConn(g: BluetoothGatt, st: Int, newState: Int) {
        if (g !== gatt) { try { g.close() } catch (e: Exception) { }; return }     // a stale callback from a connection we already dropped
        if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
            h.postDelayed({ if (g === gatt) { try { if (!g.requestMtu(247)) discover(g) } catch (e: Exception) { discover(g) } } }, 400)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            val wasReady = ready
            closeGatt()
            // Dropping right after connecting, repeatedly, with a bond on record usually means one side lost the pairing keys.
            if (!wasReady && st != 0 && (st == 5 || st == 15 || st == 22 || st == 19 || st == 133 || st == 8)) encFailures++ else if (wasReady) encFailures = 0
            if (encFailures >= 4 && st != 8) markBondProblem()
            if (wantConnected()) scheduleReconnect() else setState(S_DISCONNECTED)
        }
    }

    private fun markBondProblem() {
        bondProblem = true
        error = "This phone and the device no longer agree on their pairing. Remove the device in Android's Bluetooth settings, then use Add Device again."
        emitState()
    }

    @SuppressLint("MissingPermission")
    private fun discover(g: BluetoothGatt) { if (g === gatt) try { g.discoverServices() } catch (e: Exception) { } }

    @SuppressLint("MissingPermission")
    private fun onServices(g: BluetoothGatt, st: Int) {
        if (g !== gatt) return
        val svc = g.getService(SERVICE)
        rx = svc?.getCharacteristic(RX); statusChr = svc?.getCharacteristic(STATUS)
        if (st != BluetoothGatt.GATT_SUCCESS || rx == null || statusChr == null) {
            error = "This device does not look like a Meow Island display."; closeGatt(); setState(S_DISCONNECTED); return
        }
        try {
            g.setCharacteristicNotification(statusChr, true)
            val d = statusChr!!.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        } catch (e: Exception) { error = "Could not subscribe to the device: " + (e.message ?: ""); closeGatt(); setState(S_DISCONNECTED); scheduleReconnect() }
    }

    @SuppressLint("MissingPermission")
    private fun onCccd(g: BluetoothGatt, st: Int) {
        if (g !== gatt) return
        if (st == BluetoothGatt.GATT_SUCCESS) { becomeReady(g) }
        else if (st == 5 || st == 15 || st == 8) { encFailures++; if (encFailures >= 2) markBondProblem(); closeGatt(); scheduleReconnect() }   // insufficient authentication / encryption
        else { closeGatt(); scheduleReconnect() }
    }

    @SuppressLint("MissingPermission")
    private fun becomeReady(g: BluetoothGatt) {
        ready = true; attempts = 0; encFailures = 0; writeFailures = 0; bondProblem = false; error = ""; pendingPair = null
        prefs().edit().putBoolean("paired", true).apply()
        lastStepsKey = ""; lastMusicKey = ""; lastTimeAt = 0
        setState(S_CONNECTED)
        try { g.readCharacteristic(statusChr) } catch (e: Exception) { }
        send(DeviceProtocol.hello())
        syncAll(true)
        h.removeCallbacks(tickRun); h.postDelayed(tickRun, 1000)
    }

    private fun onStatusBytes(b: ByteArray?) {
        val s = DeviceProtocol.parseStatus(b) ?: return
        val old = status; status = s
        if (old == null || old.screen != s.screen || old.wifiState != s.wifiState || old.wifiReason != s.wifiReason || old.timeValid != s.timeValid || old.hasWifi != s.hasWifi ||
            old.pairingOpen != s.pairingOpen || old.stepsFresh != s.stepsFresh || old.musicFresh != s.musicFresh) emitState()
    }

    // ------------------------------------------------------------------ writing (one at a time, in order)
    private fun send(frame: ByteArray) {
        if (!ready) return
        for (c in DeviceProtocol.chunks(frame, mtu - 3)) writeQueue.addLast(c)
        pump()
    }
    /** For frequently refreshed state: skipped when earlier writes are still waiting (the next tick sends the newest data). */
    private fun sendState(frame: ByteArray) { if (writeQueue.size <= 12) send(frame) }

    @SuppressLint("MissingPermission")
    private fun pump() {
        val g = gatt ?: return; val c = rx ?: return
        if (writing || writeQueue.isEmpty() || !ready) return
        val data = writeQueue.removeFirst()
        writing = true
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = data; g.writeCharacteristic(c) }
        } catch (e: Exception) { false }
        if (!ok) {
            writing = false; writeQueue.addFirst(data); writeFailures++
            if (writeFailures > 8) { closeGatt(); scheduleReconnect() } else h.postDelayed({ pump() }, 250)
        }
    }
    private fun onWritten(st: Int) {
        writing = false
        if (st == BluetoothGatt.GATT_SUCCESS) writeFailures = 0
        else if (++writeFailures > 6) { closeGatt(); scheduleReconnect(); return }
        pump()
    }

    // ------------------------------------------------------------------ data
    private val tickRun = object : Runnable {
        override fun run() { if (!ready) return; try { syncAll(false) } catch (e: Exception) { }; h.postDelayed(this, 1000) }
    }

    private fun stepsFrom(d: StepCardData): DeviceProtocol.Steps {
        val hint = when { d.hint.contains("Allow Physical") -> 2; d.hint.contains("Waiting") -> 3; d.hint.contains("Turn it on") -> 1; else -> 0 }
        val sub = when (d.subtitle) { "Goal reached!" -> 2; "Keep moving" -> 1; else -> 0 }
        return DeviceProtocol.Steps(d.steps, d.goal.toLong(), d.hasSteps, d.goalOn, d.heatOn, d.happy, if (d.label.contains("off")) 1 else 0, hint, sub, d.levels)
    }

    /** Sends whatever changed since last time. force = send everything now (just connected, or "Sync now"). */
    fun syncAll(force: Boolean) {
        val ctx = appCtx ?: return
        if (!ready) return
        val now = System.currentTimeMillis()
        tickCount++
        if (force || now - lastTimeAt > 30 * 60_000L) {
            lastTimeAt = now
            val tz = java.util.TimeZone.getDefault().getOffset(now) / 60_000
            send(DeviceProtocol.time(now / 1000, tz))
        }
        // steps: the same data the home-screen "Step goals" widget shows; looked at every 5 s, sent only when it changed
        if (force || tickCount % 5 == 0) {
            val d = try { MeowWidgets.stepCardData(ctx) } catch (e: Exception) { null }
            if (d != null) {
                val s = stepsFrom(d)
                val key = "${s.steps}|${s.goal}|${s.hasSteps}|${s.goalOn}|${s.heatOn}|${s.happy}|${s.label}|${s.hint}|${s.subtitle}|${s.levels.joinToString("")}"
                if (force || key != lastStepsKey || now - lastStepsAt > 120_000L) { lastStepsKey = key; lastStepsAt = now; sendState(DeviceProtocol.steps(s)) }
            }
        }
        // music: the same state the Dancing Cat widget shows
        val ms = try { MeowMusic.state(ctx) } catch (e: Exception) { null }
        if (ms != null) {
            val session = ms.view.mode == MusicMode.PLAYING || ms.view.mode == MusicMode.PAUSED
            val playing = session && ms.playing
            val pos = if (session && ms.pos >= 0) (MusicProgress.now(ms.pos, ms.posAt, ms.playing, now, ms.dur) / 1000).toInt() else 0
            val dur = if (session && ms.dur > 0) (ms.dur / 1000).toInt() else 0
            val key = "$session|$playing|${ms.view.title}|${ms.view.artist}|$dur"
            val expected = lastMusicPos + (if (lastMusicPlaying) ((now - lastMusicAt) / 1000).toInt() else 0)
            val seeked = key == lastMusicKey && Math.abs(pos - expected) > 3
            if (force || key != lastMusicKey || seeked || (playing && now - lastMusicAt >= 10_000L)) {
                lastMusicKey = key; lastMusicAt = now; lastMusicPos = pos; lastMusicPlaying = playing
                sendState(DeviceProtocol.music(session, playing, pos, dur, ms.view.title, ms.view.artist))
            }
        }
    }

    // ------------------------------------------------------------------ commands from the app
    fun setScreen(id: Int): Boolean { if (!ready) return false; send(DeviceProtocol.screen(id)); return true }

    /** Sends the Wi-Fi details to the device over the encrypted link. Returns false if not connected or the values are not valid. The password is not kept. */
    fun setWifi(ssid: String, pass: String): Boolean {
        if (!ready) return false
        val f = DeviceProtocol.wifiSet(ssid, pass) ?: return false
        send(f)
        prefs().edit().putString("ssid", ssid).apply()           // the name only, for display; never the password
        emitState(); return true
    }
    fun clearWifi(): Boolean { if (!ready) return false; send(DeviceProtocol.wifiClear()); prefs().edit().remove("ssid").apply(); emitState(); return true }
}
