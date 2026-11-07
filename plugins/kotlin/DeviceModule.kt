package com.smartisland.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import com.facebook.react.bridge.ActivityEventListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/** JS bridge for the Meow Island TFT device. All the real work is in [DeviceLink]; this only forwards calls. */
class DeviceModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    override fun getName() = "DeviceModule"

    private val main = Handler(Looper.getMainLooper())
    private val PICK_REQ = 7711
    private var pickPromise: Promise? = null
    private var pickInto: String? = null
    private val listener = object : ActivityEventListener {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != PICK_REQ) return
            val p = pickPromise ?: return; pickPromise = null
            val uris = ArrayList<android.net.Uri>()
            data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris.add(it) } }
            if (uris.isEmpty()) data?.data?.let { uris.add(it) }
            if (resultCode != Activity.RESULT_OK || uris.isEmpty()) { p.resolve(null); return }
            val into = pickInto; pickInto = null
            Thread { readPicked(uris, into, p) }.start()
        }
        override fun onNewIntent(intent: Intent) { }
    }
    init { ctx.addActivityEventListener(listener) }

    @ReactMethod fun deviceStart() { DeviceLink.ensureStarted(ctx) }
    @ReactMethod fun deviceHasPermission(p: Promise) { p.resolve(DeviceLink.hasPermission(ctx)) }
    @ReactMethod fun deviceGetState(p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(com.facebook.react.bridge.Arguments.makeNativeMap(DeviceLink.state())) }
    @ReactMethod fun deviceScanStart(p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(DeviceLink.startScan()) }
    @ReactMethod fun deviceScanStop() { DeviceLink.stopScan() }
    @ReactMethod fun deviceScanResults(p: Promise) { p.resolve(DeviceLink.scanResultsJson()) }
    @ReactMethod fun devicePair(address: String, p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(DeviceLink.pair(address)) }
    @ReactMethod fun deviceDisconnect() { DeviceLink.disconnect() }
    @ReactMethod fun deviceCancelPair() { main.post { DeviceLink.cancelPair() } }
    @ReactMethod fun deviceRepair(p: Promise) { main.post { p.resolve(DeviceLink.repair()) } }
    @ReactMethod fun deviceReconnect() { DeviceLink.ensureStarted(ctx); DeviceLink.reconnect() }
    /** Resolves true when the device was reachable and told to forget this phone too. */
    @ReactMethod fun deviceUnpair(clearWifi: Boolean, p: Promise) { p.resolve(DeviceLink.unpair(clearWifi)) }
    @ReactMethod fun deviceSetScreen(id: Int, p: Promise) { p.resolve(DeviceLink.setScreen(id)) }
    @ReactMethod fun deviceSyncNow(p: Promise) { DeviceLink.syncAll(true); p.resolve(true) }
    @ReactMethod fun deviceSetWifi(ssid: String, pass: String, p: Promise) {
        if (!DeviceLink.state().let { it["ready"] == true }) { p.reject("NOT_CONNECTED", "The device is not connected right now."); return }
        if (DeviceLink.setWifi(ssid, pass)) p.resolve(true) else p.reject("INVALID_WIFI", "The Wi-Fi name must be 1 to 32 characters and the password empty (open network) or 8 to 63 characters.")
    }
    @ReactMethod fun deviceClearWifi(p: Promise) { p.resolve(DeviceLink.clearWifi()) }
    @ReactMethod fun deviceOpenBluetoothSettings() { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // ------------------------------------------------------------------ screen time on the display
    @ReactMethod fun deviceScreenTimeGet(p: Promise) { try { p.resolve(DeviceExtras.screenTimeJson(DeviceExtras.readScreenTime(ctx))) } catch (e: Exception) { p.reject("SCREEN_TIME", e.message ?: "failed") } }
    @ReactMethod fun deviceScreenTimeSetGoal(minutes: Int, p: Promise) { val v = DeviceExtras.setGoalMin(ctx, minutes); DeviceLink.screenTimeChanged(); p.resolve(v) }

    // ------------------------------------------------------------------ notifications on the display (opt-in; nothing is stored on disk or sent anywhere but to the paired display)
    @ReactMethod fun deviceNotifsGet(p: Promise) {
        p.resolve(JSONObject().put("enabled", DeviceExtras.notifsEnabled(ctx)).put("access", DeviceExtras.hasNotifAccess(ctx))
            .put("count", DeviceExtras.NotifStore.snapshot(System.currentTimeMillis()).size).put("preview", Cfg.read(ctx).optBoolean("notifPreview", true)).toString())
    }
    @ReactMethod fun deviceNotifsSetEnabled(on: Boolean, p: Promise) { DeviceExtras.setNotifsEnabled(ctx, on); DeviceLink.notifsChanged(); p.resolve(on) }
    /** Clears the list on the display only. Notifications on the phone are not touched. */
    @ReactMethod fun deviceNotifsClear(p: Promise) { DeviceExtras.NotifStore.clear(); DeviceLink.notifsChanged(); p.resolve(true) }

    // ------------------------------------------------------------------ lyrics (Spotify) source
    @ReactMethod fun deviceLyricsGet(p: Promise) { p.resolve(JSONObject().put("provider", DeviceExtras.lyricsProvider(ctx)).put("state", DeviceExtras.Lyrics.state).put("detail", DeviceExtras.Lyrics.detail).toString()) }
    @ReactMethod fun deviceLyricsSetProvider(name: String, p: Promise) { DeviceExtras.setLyricsProvider(ctx, name); DeviceLink.requestSync(); p.resolve(DeviceExtras.lyricsProvider(ctx)) }

    // ------------------------------------------------------------------ captions (Live Caption text for YouTube, Brave and other players)
    @ReactMethod fun deviceCaptionsGet(p: Promise) {
        p.resolve(JSONObject().put("enabled", DeviceExtras.captionsEnabled(ctx)).put("service", MeowCaptionService.enabledInSettings(ctx))
            .put("liveCaption", DeviceExtras.liveCaptionSystemState(ctx)).put("lines", DeviceExtras.Captions.recent(System.currentTimeMillis()).size).toString())
    }
    @ReactMethod fun deviceCaptionsSetEnabled(on: Boolean, p: Promise) { DeviceExtras.setCaptionsEnabled(ctx, on); DeviceLink.requestSync(); p.resolve(on) }
    @ReactMethod fun deviceCaptionsOpenAccessibility() { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // ------------------------------------------------------------------ custom HTML screens (library of up to 8, still or animated)
    /** Opens Android's document picker for one or more files: an .html file with its images / fonts / scripts, or a .zip. [addToToken] adds files to a selection made earlier.
     *  Resolves null when cancelled, otherwise JSON {token, htmlName, html, assets:[{path,size}], warnings:[]}. Nothing is uploaded anywhere. */
    @ReactMethod fun screensPick(addToToken: String?, p: Promise) {
        val act = currentActivity
        if (act == null) { p.reject("NO_ACTIVITY", "Open the app on screen first."); return }
        if (pickPromise != null) { p.reject("BUSY", "A file picker is already open."); return }
        pickPromise = p; pickInto = addToToken
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            act.startActivityForResult(i, PICK_REQ)
        } catch (e: Exception) { pickPromise = null; pickInto = null; p.reject("PICKER", "Could not open the file picker: " + (e.message ?: "")) }
    }
    private fun readPicked(uris: List<android.net.Uri>, intoToken: String?, p: Promise) {
        try {
            val cr = ctx.contentResolver; val files = ArrayList<Pair<String, ByteArray>>(); var total = 0L
            for (uri in uris) {
                var name = "file"
                cr.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ni >= 0) name = c.getString(ni) ?: name } }
                val buf = ByteArrayOutputStream()
                cr.openInputStream(uri)?.use { ins -> val b = ByteArray(16384); while (true) { val n = ins.read(b); if (n < 0) break; total += n; if (total > ScreenBundle.MAX_TOTAL * 2) throw java.io.IOException("Those files are larger than ${ScreenBundle.MAX_TOTAL * 2 / 1048576} MB."); buf.write(b, 0, n) } }
                files.add(name to buf.toByteArray())
            }
            val into = intoToken?.let { CustomScreen.pending[it] }
            val bundle = ScreenBundle.fromPicked(files, into)
            val token = if (into != null && intoToken != null) intoToken else CustomScreen.newToken()
            CustomScreen.pending[token] = bundle
            val assets = org.json.JSONArray(); for ((k, v) in bundle.assets) assets.put(JSONObject().put("path", k).put("size", v.size))
            p.resolve(JSONObject().put("token", token).put("htmlName", bundle.htmlName).put("html", bundle.html).put("assets", assets).put("warnings", org.json.JSONArray(bundle.warnings)).toString())
        } catch (e: Exception) { p.reject("READ_FAILED", "Could not read those files: " + (e.message ?: e.javaClass.simpleName)) }
    }
    @ReactMethod fun screensDiscard(token: String) { CustomScreen.pending.remove(token) }

    private fun sourceBundle(source: String): ScreenBundle? =
        if (source.startsWith("pending:")) CustomScreen.pending[source.removePrefix("pending:")] else if (source.startsWith("saved:")) CustomScreen.loadBundle(ctx, source.removePrefix("saved:")) else null
    /** The HTML text of a pending selection ("pending:<token>") or a saved screen ("saved:<id>"). */
    @ReactMethod fun screensGetHtml(source: String, p: Promise) { val b = sourceBundle(source); if (b == null) p.reject("NOT_FOUND", "That screen's files are no longer on the phone.") else p.resolve(b.html) }

    /** Renders and records the screen. [preparedHtml] and [harness] come from src/core/animscreen.ts. Resolves JSON {loop, notes:[], frames:[{x,y,w,h,delay,px(base64 RGB565)}]}; frame 0 is the whole screen, the others only the changed rectangle. */
    @ReactMethod fun screensRender(source: String, preparedHtml: String, harness: String, settingsJson: String, p: Promise) {
        val b = sourceBundle(source)
        if (b == null) { p.reject("NOT_FOUND", "That screen's files are no longer on the phone. Choose them again."); return }
        if (preparedHtml.length > ScreenBundle.MAX_HTML * 2) { p.reject("TOO_LARGE", "That file is too large."); return }
        val s = try { JSONObject(settingsJson) } catch (e: Exception) { JSONObject() }
        val o = CustomRender.Options(s.optBoolean("scripts", true), s.optBoolean("online", true), s.optBoolean("animate", true), s.optInt("fps", 10), s.optInt("durationMs", 0), s.optString("loop", "auto"))
        CustomRender.render(currentActivity, b.toAssets(), preparedHtml, harness, o) { r ->
            val fr = r.frames
            if (fr == null) p.reject(r.code, r.message)
            else Thread {
                try {
                    val a = org.json.JSONArray()
                    for (f in fr) a.put(JSONObject().put("x", f.x).put("y", f.y).put("w", f.w).put("h", f.h).put("delay", f.delayMs).put("px", Base64.encodeToString(f.rgb565, Base64.NO_WRAP)))
                    p.resolve(JSONObject().put("loop", r.loop).put("notes", org.json.JSONArray(r.notes)).put("frames", a).toString())
                } catch (e: Throwable) { p.reject("RENDER_FAILED", "Could not hand the recording to the app: " + (e.message ?: e.javaClass.simpleName)) }
            }.start()
        }
    }
    /** Saves a rendered screen into the library (id empty = new screen from the pending selection [token]). Resolves the library JSON. */
    @ReactMethod fun screensSave(id: String, token: String, name: String, settingsJson: String, deviceB64: String, frames: Int, animated: Boolean, loop: Boolean, durationMs: Int, p: Promise) {
        try {
            val dev = Base64.decode(deviceB64, Base64.DEFAULT)
            val e = CustomScreen.save(ctx, id.ifEmpty { null }, token.ifEmpty { null }, name, JSONObject(settingsJson), dev, frames, animated, loop, durationMs)
            p.resolve(JSONObject().put("saved", e.toJson()).put("library", CustomScreen.info(ctx)).toString())
        } catch (e: Exception) { p.reject("SAVE_FAILED", "Could not save the screen: " + (e.message ?: e.javaClass.simpleName)) }
    }
    @ReactMethod fun screensList(p: Promise) { try { p.resolve(CustomScreen.info(ctx).toString()) } catch (e: Exception) { p.reject("READ_FAILED", e.message ?: "failed") } }
    /** Base64 of the file the display plays, for the preview. */
    @ReactMethod fun screensGetDevice(id: String, p: Promise) { val d = CustomScreen.deviceBytes(ctx, id); if (d == null) p.reject("NOT_FOUND", "That screen no longer exists.") else p.resolve(Base64.encodeToString(d, Base64.NO_WRAP)) }
    @ReactMethod fun screensRename(id: String, name: String, p: Promise) { CustomScreen.rename(ctx, id, name); p.resolve(CustomScreen.info(ctx).toString()) }
    /** Removes the screen from the phone and from the display. The display's other screens are not touched. */
    @ReactMethod fun screensRemove(id: String, p: Promise) { main.post { CustomScreen.remove(ctx, id); p.resolve(CustomScreen.info(ctx).toString()) } }
    /** [idsJson]: JSON array of screen ids to send, or "[]" for all of them. */
    @ReactMethod fun screensSend(idsJson: String, p: Promise) {
        main.post {
            val ids = try { val a = org.json.JSONArray(idsJson); (0 until a.length()).map { a.getString(it) } } catch (e: Exception) { emptyList<String>() }
            val err = if (ids.isEmpty()) CustomScreen.sendAll(ctx) else CustomScreen.send(ctx, ids)
            if (err == null) p.resolve(true) else p.reject("SEND_FAILED", err)
        }
    }
    @ReactMethod fun screensCancel() { main.post { CustomScreen.cancel() } }
    @ReactMethod fun screensShow(id: String, p: Promise) { main.post { val err = CustomScreen.show(ctx, id); if (err == null) p.resolve(true) else p.reject("SHOW_FAILED", err) } }
    @ReactMethod fun screensSetAuto(on: Boolean, p: Promise) { CustomScreen.setAuto(ctx, on); p.resolve(on) }
    /** Removes screens that are on the display but not in this app's library. */
    @ReactMethod fun screensClearDeviceOnly(p: Promise) { main.post { CustomScreen.clearDeviceOnly(ctx); p.resolve(true) } }
}
