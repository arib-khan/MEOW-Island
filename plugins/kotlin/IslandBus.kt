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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Calendar

/** One thing the island can show. Highest priority wins; the rest keep their state. */
data class Act(
    val id: String, val priority: Int, val title: String, val sub: String = "",
    val emoji: String = "", val icon: Bitmap? = null, val art: Bitmap? = null,
    val spinning: Boolean = false, val expireAt: Long = 0L, val expand: Boolean = false
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
