package com.smartisland.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local, on-device DNS filter built on [VpnService]. Design (same idea as DNS66 / personalDNSfilter):
 *  - the tunnel owns ONLY two virtual DNS addresses (one IPv4, one IPv6) and the system is told to use them as its DNS servers;
 *  - only DNS packets to those addresses ever enter the tunnel, all other traffic keeps using the normal network, so there is no
 *    traffic proxying, no battery cost from forwarding data and no IPv4/IPv6 leak of ordinary connections;
 *  - a query for a blocked domain (or any subdomain) is answered with NXDOMAIN locally; everything else is forwarded unchanged to
 *    the network's own DNS server (or the optional Cloudflare family resolver) using a socket that bypasses the tunnel.
 * No browsing history is kept: only a per-day count of blocked lookups. The blocklist is a plain file that can be refreshed.
 */
class AdultFilterService : VpnService() {
    companion object {
        const val V4_DNS = "10.111.222.1"; const val V4_ADDR = "10.111.222.2"
        const val V6_DNS = "fd6e:6f77:6d65:6f77::1"; const val V6_ADDR = "fd6e:6f77:6d65:6f77::2"
        private const val CH = "meow_filter"; private const val NOTIF_ID = 3
        @Volatile var running = false
        @Volatile var lastError = ""
        @Volatile var inst: AdultFilterService? = null
        val list = DomainBlocklist()
        @Volatile var listLoaded = false
        private val blockedSession = AtomicInteger(0)
        fun blockedThisSession() = blockedSession.get()

        fun listFile(ctx: Context) = File(ctx.filesDir, "adult_blocklist.txt")

        /** Loads the downloaded list if present, otherwise the list bundled in the APK. Safe to call from any thread. */
        @Synchronized fun loadList(ctx: Context): Int {
            val f = listFile(ctx)
            val reader: BufferedReader = if (f.exists() && f.length() > 1000) f.bufferedReader() else BufferedReader(InputStreamReader(ctx.assets.open("adult_seed.txt")))
            val n = reader.use { list.load(it) }
            applyUser(ctx); listLoaded = true
            return n
        }
        fun applyUser(ctx: Context) {
            val a = WellbeingStore.adultJson(ctx)
            fun arr(k: String): List<String> = a.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
            list.setUser(arr("allow"), arr("block"))
        }

        /** Downloads a hosts/domain list over HTTPS, validates it and swaps it in atomically. Returns the number of domains. */
        fun update(ctx: Context, url: String): Int {
            require(url.startsWith("https://")) { "Only https:// sources are allowed" }
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val tmp = File(ctx.filesDir, "adult_blocklist.tmp")
            var bytes = 0L
            c.inputStream.use { i -> tmp.outputStream().use { o -> val buf = ByteArray(16384); while (true) { val r = i.read(buf); if (r < 0) break; bytes += r; if (bytes > 40_000_000) throw IllegalStateException("List is larger than 40 MB"); o.write(buf, 0, r) } } }
            val probe = DomainBlocklist()
            val n = tmp.bufferedReader().use { probe.load(it) }
            if (n < 1000) { tmp.delete(); throw IllegalStateException("List looks invalid ($n domains)") }
            if (!tmp.renameTo(listFile(ctx))) { listFile(ctx).delete(); tmp.renameTo(listFile(ctx)) }
            val m = org.json.JSONObject().put("updatedAt", System.currentTimeMillis()).put("count", n).put("source", url)
            WellbeingStore.writeListMeta(ctx, m)
            return loadList(ctx)
        }

        fun privateDnsMode(ctx: Context): String = try { Settings.Global.getString(ctx.contentResolver, "private_dns_mode") ?: "off" } catch (e: Exception) { "unknown" }

        /** Another app currently holds a VPN (ours is not the one running). */
        fun otherVpnActive(ctx: Context): Boolean = try {
            val cm: ConnectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;
            val other = cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            other && !running
        } catch (e: Exception) { false }

        fun start(ctx: Context) { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, AdultFilterService::class.java)) }
        fun stop(ctx: Context) { try { ctx.startService(Intent(ctx, AdultFilterService::class.java).setAction("STOP")) } catch (e: Exception) { } }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool = Executors.newFixedThreadPool(4)
    private var flusher: ScheduledExecutorService? = null
    @Volatile private var stopping = false
    private val pending = AtomicInteger(0)
    @Volatile private var lastIslandPost = 0L
    @Volatile private var upstreamCache: List<InetAddress> = emptyList()
    @Volatile private var upstreamAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { shutdown("stopped"); return START_NOT_STICKY }
        startForegroundNotif()
        if (!WellbeingStore.adultEnabled(this)) { shutdown("disabled"); return START_NOT_STICKY }
        if (reader?.isAlive == true) return START_STICKY
        stopping = false; lastError = ""
        inst = this
        reader = Thread({ runFilter() }, "meow-dns").also { it.start() }
        return START_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Adult website filter", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE)
        val icon = resources.getIdentifier("ic_meow", "drawable", packageName).let { if (it != 0) it else android.R.drawable.ic_dialog_info }
        val n: Notification = NotificationCompat.Builder(this, CH).setSmallIcon(icon).setContentTitle("Adult website filter is on")
            .setContentText("Blocking known adult domains on this device").setOngoing(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
    }

    // ------------------------------------------------------------------ main loop
    private fun runFilter() {
        try {
            if (VpnService.prepare(this) != null) { fail("VPN permission was not granted (or was revoked). Open Meow Island and switch the filter on again."); return }
            if (!listLoaded) loadList(this) else applyUser(this)
            val b = Builder().setSession("Meow Island adult-site filter").setMtu(1500)
                .addAddress(V4_ADDR, 32).addDnsServer(V4_DNS).addRoute(V4_DNS, 32)
            try { b.addAddress(V6_ADDR, 128).addDnsServer(V6_DNS).addRoute(V6_DNS, 128) } catch (e: Exception) { /* IPv6 unavailable on this build: IPv4 DNS only */ }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            b.setBlocking(true)
            val fd = b.establish() ?: run { fail("Android refused to start the VPN (another always-on VPN may hold it)."); return }
            tun = fd
            running = true
            flusher = Executors.newSingleThreadScheduledExecutor().also { it.scheduleWithFixedDelay({ flushStats() }, 10, 10, TimeUnit.SECONDS) }
            IslandModule.emit("adultState", mapOf("running" to true, "error" to ""))
            val inp = FileInputStream(fd.fileDescriptor); val out = FileOutputStream(fd.fileDescriptor)
            val buf = ByteArray(32767)
            while (!stopping) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val pkt = buf.copyOf(n)
                try { pool.execute { handle(pkt, out) } } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            if (!stopping) fail("Filter stopped: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            if (!stopping) shutdown("loop ended")
        }
    }

    private fun handle(pkt: ByteArray, out: FileOutputStream) {
        try {
            val q = DnsPacket.parse(pkt) ?: return
            if (q.dstPort != 53 || !DnsPacket.isStandardQuery(q.dns)) return
            val name = DnsPacket.questionName(q.dns) ?: return
            val answer: ByteArray
            if (name == DomainBlocklist.DOH_CANARY) {
                answer = DnsPacket.nxdomain(q.dns)                              // keeps Firefox on the system resolver; not a "blocked site"
            } else if (list.isBlocked(name)) {
                answer = DnsPacket.nxdomain(q.dns)
                blockedSession.incrementAndGet(); pending.incrementAndGet()
                maybeNotify()
            } else {
                answer = forward(q.dns) ?: return                              // upstream unreachable: stay silent, the client retries / times out
            }
            val reply = DnsPacket.reply(q, if (answer.size + 48 > 1500) DnsPacket.truncated(answer) else answer)
            synchronized(out) { out.write(reply) }
        } catch (e: Exception) { /* a single bad packet must never stop the filter */ }
    }

    private fun forward(query: ByteArray): ByteArray? {
        for (server in upstream()) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(); protect(s); s.soTimeout = 3500
                s.send(DatagramPacket(query, query.size, server, 53))
                val buf = ByteArray(4096); val r = DatagramPacket(buf, buf.size)
                s.receive(r)
                if (r.length >= 12 && buf[0] == query[0] && buf[1] == query[1]) return buf.copyOf(r.length)   // id must match
            } catch (e: Exception) { /* try the next server */ } finally { s?.close() }
        }
        return null
    }

    /** Upstream resolvers, refreshed every 30 s: the network's own DNS servers by default, so the user's DNS choice is preserved. */
    private fun upstream(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (now - upstreamAt < 30_000 && upstreamCache.isNotEmpty()) return upstreamCache
        val a = WellbeingStore.adultJson(this)
        val res = ArrayList<InetAddress>()
        try {
            when (a.optString("upstream", "system")) {
                "cloudflare" -> { res.add(InetAddress.getByName("1.1.1.3")); res.add(InetAddress.getByName("1.0.0.3")) }
                "custom" -> a.optString("customDns", "").trim().takeIf { Regex("^[0-9a-fA-F:.]+$").matches(it) }?.let { res.add(InetAddress.getByName(it)) }
            }
            if (res.isEmpty()) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                    cm.getLinkProperties(n)?.dnsServers?.forEach { d -> val h = d.hostAddress ?: ""; if (h != V4_DNS && !h.startsWith("fd6e:6f77") && res.none { it == d }) res.add(d) }
                }
            }
        } catch (e: Exception) { }
        if (res.isEmpty()) { try { res.add(InetAddress.getByName("1.1.1.1")); res.add(InetAddress.getByName("9.9.9.9")) } catch (e: Exception) { } }
        upstreamCache = res; upstreamAt = now
        return res
    }

    private fun maybeNotify() {
        if (!WellbeingStore.adultJson(this).optBoolean("notify", true)) return
        val now = System.currentTimeMillis()
        if (now - lastIslandPost < 20_000) return
        lastIslandPost = now
        IslandBus.post(Act("adult_block", 35, "🚫 Blocked", "Adult website", expireAt = now + 2500, expand = true, pulse = now))
    }

    private fun flushStats() { val n = pending.getAndSet(0); if (n > 0) WellbeingStore.addBlocked(this, n) }

    private fun fail(msg: String) {
        lastError = msg
        WellbeingStore.log(this, "adult filter: $msg")
        IslandModule.emit("adultState", mapOf("running" to false, "error" to msg))
        shutdown(msg)
    }

    private fun shutdown(reason: String) {
        if (stopping && !running) { stopSelf(); return }
        stopping = true; running = false
        try { flushStats() } catch (e: Exception) { }
        try { flusher?.shutdownNow() } catch (e: Exception) { }
        try { tun?.close() } catch (e: Exception) { }
        tun = null
        IslandModule.emit("adultState", mapOf("running" to false, "error" to lastError))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The user switched the VPN off in Android settings, or another VPN app took over. */
    override fun onRevoke() {
        lastError = "The VPN was turned off in Android settings or replaced by another VPN app."
        WellbeingStore.log(this, "adult filter revoked")
        shutdown("revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopping = true; running = false
        try { tun?.close() } catch (e: Exception) { }
        try { pool.shutdownNow() } catch (e: Exception) { }
        if (inst === this) inst = null
        super.onDestroy()
    }
}
