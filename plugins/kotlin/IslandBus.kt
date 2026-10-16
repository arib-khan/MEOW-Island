package com.smartisland.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Calendar

/** One thing the island can show. Highest priority wins; the rest keep their state. */
data class Act(
    val id: String, val priority: Int, val title: String, val sub: String = "",
    val emoji: String = "", val icon: Bitmap? = null, val art: Bitmap? = null,
    val spinning: Boolean = false, val expireAt: Long = 0L, val expand: Boolean = false,
    /** Changes whenever a NEW event happens; a changed pulse re-expands the island. */
    val pulse: Long = 0L,
    val playing: Boolean = false, val canPrev: Boolean = false, val canNext: Boolean = false,
    val posMs: Long = 0L, val durMs: Long = 0L, val posAt: Long = 0L
)

/** Process-wide activity registry. Lives natively so it keeps working while JS is suspended. */
object IslandBus {
    private val acts = LinkedHashMap<String, Act>()

    @Synchronized fun post(a: Act) { acts[a.id] = a; IslandService.inst?.refresh() }
    @Synchronized fun remove(id: String) { if (acts.remove(id) != null) IslandService.inst?.refresh() }
    @Synchronized fun top(): Act? {
        val now = System.currentTimeMillis()
        val iter = acts.values.iterator()
        while (iter.hasNext()) {
            val a = iter.next()
            if (a.expireAt > 0L && a.expireAt <= now) iter.remove()
        }
        return acts.values.maxByOrNull { it.priority }
    }
}

object Cfg {
    fun read(ctx: Context): JSONObject = try {
        JSONObject(ctx.getSharedPreferences("island", 0).getString("config", "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    fun write(ctx: Context, json: String) {
        ctx.getSharedPreferences("island", 0).edit().putString("config", json).apply()
    }
}

object Img {
    fun fromDrawable(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    fun b64(b: Bitmap, png: Boolean): String {
        val o = ByteArrayOutputStream()
        b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }
}

object UsageUtil {
    fun hasAccess(ctx: Context): Boolean {
        val a = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            ctx.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED
        } else mode == AppOpsManager.MODE_ALLOWED
    }

    /** Local-midnight boundary, recomputed on every call so time-zone/DST changes are respected. */
    fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * Foreground time per package since local midnight, from raw usage events.
     * Event types: 1 = resumed, 2 = paused, 16 = screen off, 17 = keyguard shown.
     * Each interval is counted once; a new foreground package closes any other open interval.
     */
    fun todayAll(ctx: Context): HashMap<String, Long> {
        val total = HashMap<String, Long>()
        val open = HashMap<String, Long>()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(startOfToday(), now)
        val e = UsageEvents.Event()
        fun close(p: String, at: Long) {
            val s = open.remove(p)
            if (s != null && at > s) total[p] = (total[p] ?: 0L) + (at - s)
        }
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val p = e.packageName ?: continue
            when (e.eventType) {
                1 -> {
                    for (k in ArrayList(open.keys)) if (k != p) close(k, e.timeStamp)
                    if (!open.containsKey(p)) open[p] = e.timeStamp
                }
                2 -> close(p, e.timeStamp)
                16, 17 -> for (k in ArrayList(open.keys)) close(k, e.timeStamp)
            }
        }
        for (k in ArrayList(open.keys)) close(k, now)
        return total
    }
}

object Fmt {
    fun clock(ms: Long): String {
        val t = maxOf(0L, ms / 1000L)
        val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }
    fun min(ms: Long): String = (maxOf(0L, ms) / 60000L).toString() + " min"
}

/** Foreground-app tracker built from usage events (needs Usage Access). Throttled to save battery. */
object Fg {
    @Volatile var pkg: String? = null
    @Volatile var available = false
    private var lastScan = 0L

    fun update(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastScan < 1500L) return
        available = UsageUtil.hasAccess(ctx)
        if (!available) { pkg = null; lastScan = now; return }
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val from = if (lastScan == 0L) now - 3L * 3600000L else lastScan - 2000L
        val ev = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                1 -> pkg = e.packageName
                2 -> if (e.packageName == pkg) pkg = null
                16, 17 -> pkg = null
            }
        }
        lastScan = now
    }
}

/** Countdown timers keyed by absolute end timestamps, persisted natively (survive restarts). */
object Timers {
    fun all(ctx: Context): JSONArray = try {
        JSONArray(ctx.getSharedPreferences("island", 0).getString("timers", "[]") ?: "[]")
    } catch (e: Exception) { JSONArray() }

    fun save(ctx: Context, arr: JSONArray) { ctx.getSharedPreferences("island", 0).edit().putString("timers", arr.toString()).apply() }

    fun add(ctx: Context, id: String, label: String, endAt: Long, notifId: String) {
        val arr = all(ctx)
        val t = JSONObject(); t.put("id", id); t.put("label", label); t.put("endAt", endAt); t.put("notifId", notifId)
        arr.put(t); save(ctx, arr)
    }

    fun remove(ctx: Context, id: String) {
        val arr = all(ctx); val keep = JSONArray()
        for (i in 0 until arr.length()) { val t = arr.optJSONObject(i) ?: continue; if (t.optString("id") != id) keep.put(t) }
        save(ctx, keep)
    }
}
