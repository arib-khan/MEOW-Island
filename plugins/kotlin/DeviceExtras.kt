package com.smartisland.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Data sources for the new device screens: phone screen time, the notification queue, lyrics state, the media source name and album-art bytes.
 * Everything here reuses what the app already has (UsageUtil, MediaListener, Cfg). Settings live in their own preference file ("meowextras") because
 * "Forget device" wipes the device preferences, and these are the user's own choices.
 */
object DeviceExtras {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("meowextras", Context.MODE_PRIVATE)

    // ================================================================== screen time (phone usage today; NOT the device's display-on time)
    const val DEFAULT_GOAL_MIN = 240

    class ScreenTime(val access: Boolean, val ok: Boolean, val totalMin: Int, val goalMin: Int, val apps: List<DeviceProtocol.AppUse>, val day: String, val error: String)

    fun normalizeGoal(m: Int): Int = if (m <= 0) 0 else (Math.round(m / 15.0) * 15).toInt().coerceIn(15, 960)
    fun goalMin(ctx: Context): Int = prefs(ctx).getInt("stGoalMin", DEFAULT_GOAL_MIN)
    fun setGoalMin(ctx: Context, min: Int): Int { val v = normalizeGoal(min); prefs(ctx).edit().putInt("stGoalMin", v).apply(); return v }
    fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())          // the PHONE's local date: the summary resets when it changes

    // The home screen / system UI are "foreground" for Android but are not screen time in Digital Wellbeing either.
    private val IGNORED = setOf("com.android.systemui", "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher", "com.miui.home", "com.android.launcher",
        "com.android.launcher3", "com.oppo.launcher", "com.huawei.android.launcher", "com.nothing.launcher", "com.mi.android.globallauncher", "com.transsion.hilauncher")
    private fun homePackage(ctx: Context): String? = try {
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (e: Exception) { null }

    private val labels = ConcurrentHashMap<String, String>()
    private fun lastSegment(pkg: String) = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    fun appName(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        val l = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { "" }
        if (DeviceProtocol.ascii(l, 14).isNotEmpty()) l else lastSegment(pkg)       // the display only has ASCII: fall back to the package name when the label has none
    }

    /** Reads today's usage through the existing [UsageUtil]. Never throws: denied permission, no data and errors are explicit states. */
    fun readScreenTime(ctx: Context): ScreenTime {
        val goal = goalMin(ctx); val day = dayKey()
        if (!UsageUtil.hasAccess(ctx)) return ScreenTime(false, false, 0, goal, emptyList(), day, "")
        return try {
            val home = homePackage(ctx)
            var totalMs = 0L
            val apps = ArrayList<DeviceProtocol.AppUse>()
            for ((pkg, ms) in UsageUtil.todayAll(ctx)) {
                if (pkg in IGNORED || pkg == home || pkg == "android" || ms <= 0L) continue
                totalMs += ms
                if (ms >= 60_000L) apps.add(DeviceProtocol.AppUse(appName(ctx, pkg), (ms / 60_000L).toInt()))
            }
            apps.sortByDescending { it.min }
            ScreenTime(true, true, (totalMs / 60_000L).toInt().coerceIn(0, 1440), goal, apps.take(10), day, "")
        } catch (e: Exception) {
            ScreenTime(true, false, 0, goal, emptyList(), day, "Could not read usage data from Android.")
        }
    }
    fun screenTimeFrame(s: ScreenTime): ByteArray = DeviceProtocol.screenTime(s.ok, !s.access, s.access && !s.ok, s.totalMin, s.goalMin, s.apps)
    fun screenTimeKey(s: ScreenTime) = "${s.access}|${s.ok}|${s.totalMin}|${s.goalMin}|${s.day}|" + s.apps.take(DeviceProtocol.ST_MAX_APPS).joinToString(",") { it.name + ":" + it.min }
    fun screenTimeJson(s: ScreenTime): String {
        val a = JSONArray(); for (x in s.apps) a.put(JSONObject().put("name", x.name).put("min", x.min))
        return JSONObject().put("access", s.access).put("ok", s.ok).put("totalMin", s.totalMin).put("goalMin", s.goalMin).put("apps", a).put("day", s.day).put("error", s.error).toString()
    }

    // ================================================================== notifications
    class NotifItem(val key: String, val postedAt: Long, val app: String, val title: String, val text: String)

    /**
     * Bounded, in-memory only (never written to disk, never sent anywhere but to the paired display over Bluetooth): the newest [MAX] entries,
     * each dropped after [TTL_MS] or when the phone's own notification is dismissed. The same app+title+text within [DEDUP_MS] is not added twice.
     * Nothing here can dismiss or alter a notification on the phone.
     */
    object NotifStore {
        const val MAX = 20; const val TTL_MS = 6L * 3600_000L; const val DEDUP_MS = 60_000L
        private val items = ArrayList<NotifItem>()     // newest first

        @Synchronized fun add(n: NotifItem): Boolean {
            items.removeAll { it.key == n.key }                                                                 // an update of the same notification replaces it
            if (items.any { it.app == n.app && it.title == n.title && it.text == n.text && n.postedAt - it.postedAt < DEDUP_MS }) return false
            items.add(0, n)
            while (items.size > MAX) items.removeAt(items.size - 1)
            return true
        }
        @Synchronized fun remove(key: String): Boolean = items.removeAll { it.key == key }
        @Synchronized fun clear() { items.clear() }
        @Synchronized fun snapshot(now: Long): List<NotifItem> { items.removeAll { now - it.postedAt > TTL_MS }; return ArrayList(items) }
    }

    fun notifsEnabled(ctx: Context) = prefs(ctx).getBoolean("notifsOn", false)           // opt-in
    fun setNotifsEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("notifsOn", on).apply(); if (!on) NotifStore.clear() }
    fun hasNotifAccess(ctx: Context) = try { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) } catch (e: Exception) { false }

    /** Called by the notification listener for every notification that already passed its filters (not ongoing, not a group summary, not our own). */
    fun onNotificationPosted(ctx: Context, key: String, app: String, title: String, text: String, secret: Boolean) {
        if (!notifsEnabled(ctx) || secret) return                     // notifications marked private by the app are never shown on the display
        val showText = Cfg.read(ctx).optBoolean("notifPreview", true)  // the existing "show previews" privacy switch also governs the display
        val added = NotifStore.add(NotifItem(key, System.currentTimeMillis(), app, if (showText) title else "", if (showText) text else ""))
        if (added) DeviceLink.notifsChanged()
    }
    fun onNotificationRemoved(key: String) { if (NotifStore.remove(key)) DeviceLink.notifsChanged() }

    fun notifFrame(ctx: Context, now: Long): ByteArray {
        val on = notifsEnabled(ctx)
        val list = if (on) NotifStore.snapshot(now).map { DeviceProtocol.Notif(((now - it.postedAt) / 60_000L), it.app, it.title, it.text) } else emptyList()
        return DeviceProtocol.notifs(on, on && !hasNotifAccess(ctx), list)
    }
    /** Identity of what the display should show, WITHOUT the ages (they advance on the device by themselves): unchanged content is not sent again. */
    fun notifKey(ctx: Context, now: Long): String {
        val on = notifsEnabled(ctx)
        return "$on|${on && !hasNotifAccess(ctx)}|" + (if (on) NotifStore.snapshot(now).take(DeviceProtocol.NT_MAX).joinToString("~") { it.key + "\u0001" + it.title + "\u0001" + it.text } else "")
    }

    // ================================================================== media source + album art
    const val SPOTIFY = "com.spotify.music"
    fun isSpotify(pkg: String) = pkg == SPOTIFY || pkg == "com.spotify.lite"
    fun sourceLabel(ctx: Context, pkg: String): String = when {
        isSpotify(pkg) -> "SPOTIFY"
        pkg == "com.google.android.youtube" -> "YOUTUBE"
        pkg == "com.google.android.apps.youtube.music" -> "YT MUSIC"
        pkg.startsWith("com.brave.browser") -> "BRAVE"
        pkg == "com.android.chrome" -> "CHROME"
        pkg.isEmpty() -> ""
        else -> DeviceProtocol.ascii(appName(ctx, pkg), 14).uppercase().ifEmpty { "MUSIC" }
    }

    const val ART = 80
    /** The 96x96 artwork the media listener already decoded -> 80x80 RGB565 little-endian (12 800 bytes), the format the display expects. */
    fun artBytes(src: Bitmap): ByteArray {
        val s = Bitmap.createScaledBitmap(src, ART, ART, true)
        val px = IntArray(ART * ART); s.getPixels(px, 0, ART, 0, 0, ART, ART)
        if (s !== src) s.recycle()
        val out = ByteArray(ART * ART * 2)
        for (i in px.indices) {
            val c = px[i]; val a = Color.alpha(c)
            var r = Color.red(c); var g = Color.green(c); var b = Color.blue(c)
            if (a < 255) { r = r * a / 255; g = g * a / 255; b = b * a / 255 }           // transparent artwork is shown over black
            val v = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
            out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    // ================================================================== lyrics
    fun lyricsProvider(ctx: Context): String = prefs(ctx).getString("lyricsProvider", "off") ?: "off"
    fun setLyricsProvider(ctx: Context, name: String) { prefs(ctx).edit().putString("lyricsProvider", if (name == "lrclib") "lrclib" else "off").apply(); Lyrics.reset() }

    /**
     * Lyrics for the track Spotify is playing, from an opt-in provider only. State machine per track:
     *   not Spotify -> NA (the display then says "Captions unavailable"); provider off -> PROVIDER_OFF; fetching -> LOADING;
     *   synchronised lines -> AVAILABLE; text without timestamps -> UNSYNCED (never shown as if it were in time); none -> UNAVAILABLE; network error -> OFFLINE.
     */
    object Lyrics {
        private val exec = Executors.newSingleThreadExecutor()
        @Volatile private var key = ""
        @Volatile var state = DeviceProtocol.LY_NA; private set
        @Volatile private var lines: List<Lrc.Line> = emptyList()
        @Volatile private var offlineAt = 0L
        private val cache = object : LinkedHashMap<String, LyricsResult>(8, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, LyricsResult>?) = size > 8 }

        fun reset() { key = ""; state = DeviceProtocol.LY_NA; lines = emptyList() }

        fun update(ctx: Context, spotify: Boolean, title: String, artist: String, durSec: Int) {
            if (!spotify || title.isBlank()) { reset(); return }
            val provider = lyricsProvider(ctx)
            val k = "$provider|$title|$artist|$durSec"
            val retryOffline = state == DeviceProtocol.LY_OFFLINE && System.currentTimeMillis() - offlineAt > 60_000L
            if (k == key && !retryOffline) return
            key = k; lines = emptyList()
            if (provider != "lrclib") { state = DeviceProtocol.LY_PROVIDER_OFF; return }
            val hit = synchronized(cache) { cache[k] }
            if (hit != null) { state = hit.state; lines = hit.lines; return }
            state = DeviceProtocol.LY_LOADING
            val appCtx = ctx.applicationContext
            exec.execute {
                val r = try { LrcLibProvider().fetch(title, artist, durSec) } catch (e: Exception) { LyricsResult(DeviceProtocol.LY_OFFLINE, emptyList()) }
                if (key == k) {
                    state = r.state; lines = r.lines
                    if (r.state == DeviceProtocol.LY_OFFLINE) offlineAt = System.currentTimeMillis() else synchronized(cache) { cache[k] = r }
                    DeviceLink.requestSync()
                }
            }
        }

        class Current(val idx: Int, val prev: String, val cur: String, val next: String)
        /** Only meaningful when [state] == AVAILABLE. Before the first timestamp the current line is empty and the first line is "next". */
        fun current(posMs: Long): Current {
            val l = lines; val i = Lrc.indexAt(l, posMs)
            return if (i < 0) Current(-1, "", "", l.firstOrNull { it.text.isNotBlank() }?.text ?: "")
            else Current(i, Lrc.neighbour(l, i, -1), l[i].text, Lrc.neighbour(l, i, 1))
        }
    }
}
