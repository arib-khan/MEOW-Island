package com.smartisland.app

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Parsed Reels/Shorts settings. The JSON shape is shared with src/core/shorts.ts; every field is validated on read. */
data class ShortsCfg(
    val counterOn: Boolean, val counterIg: Boolean, val counterYt: Boolean, val modeTotal: Boolean,
    val fx: Float, val yDp: Int, val scale: Float, val opacity: Float, val bg: Int, val fg: Int, val textMode: String, val hideLandscape: Boolean,
    val tiers: List<Tier>,
    val blockIg: Boolean, val blockYt: Boolean, val strict: Boolean, val autoLeave: Boolean, val pauseMin: Int,
    val goal: Int, val remindEvery: Int
) {
    val anyOn get() = (counterOn && (counterIg || counterYt)) || blockIg || blockYt
    fun counts(app: String) = counterOn && (if (app == ShortsApp.IG) counterIg else counterYt)
    fun blocks(app: String) = if (app == ShortsApp.IG) blockIg else blockYt

    companion object {
        private fun col(o: JSONObject, k: String, d: Int) = try { Color.parseColor(o.optString(k, "")) } catch (e: Exception) { d }
        fun parse(root: JSONObject): ShortsCfg {
            val c = root.optJSONObject("counter") ?: JSONObject()
            val b = root.optJSONObject("block") ?: JSONObject()
            val g = root.optJSONObject("goal") ?: JSONObject()
            val tiers = ArrayList<Tier>()
            c.optJSONArray("tiers")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { t -> if (t.optInt("min", 0) >= 1) tiers.add(Tier(t.optInt("min"), t.optString("emoji", "🐱").take(8), t.optString("text", "").take(40))) } }
            return ShortsCfg(
                c.optBoolean("enabled", true), c.optBoolean("ig", true), c.optBoolean("yt", true), c.optString("mode", "app") == "total",
                c.optDouble("fx", 0.5).toFloat().coerceIn(0f, 1f), c.optInt("y", 16).coerceIn(0, 400), c.optDouble("scale", 1.0).toFloat().coerceIn(0.7f, 1.6f),
                c.optDouble("opacity", 0.92).toFloat().coerceIn(0.3f, 1f), col(c, "bg", Color.BLACK), col(c, "fg", Color.WHITE),
                c.optString("text", "milestone"), c.optBoolean("hideLandscape", true),
                if (tiers.isEmpty()) Milestones.DEFAULT else tiers.sortedBy { it.min },
                b.optBoolean("ig", false), b.optBoolean("yt", false), b.optBoolean("strict", false), b.optBoolean("autoLeave", true), b.optInt("pauseMin", 5).coerceIn(1, 60),
                g.optInt("daily", 0).coerceIn(0, 10_000), g.optInt("remindEvery", 10).coerceIn(0, 1000)
            )
        }
    }
}

/**
 * Everything is stored locally in SharedPreferences. Native is the single source of truth (not JS), so blocking and the counter
 * keep working after a reboot without the React Native app ever starting.
 */
object WellbeingStore {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("wellbeing", 0)

    // ---- config (JSON text, validated on read) ----
    fun shortsJson(ctx: Context): JSONObject = try { JSONObject(p(ctx).getString("shorts", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    fun shorts(ctx: Context): ShortsCfg = ShortsCfg.parse(shortsJson(ctx))
    fun adultJson(ctx: Context): JSONObject = try { JSONObject(p(ctx).getString("adult", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    fun adultEnabled(ctx: Context) = adultJson(ctx).optBoolean("enabled", false)

    /** True when [new] weakens protection compared with [old]: a PIN (if set) is then required. */
    fun shortsLoosens(old: JSONObject, new: JSONObject): Boolean {
        val o = old.optJSONObject("block") ?: JSONObject(); val n = new.optJSONObject("block") ?: JSONObject()
        return (o.optBoolean("ig", false) && !n.optBoolean("ig", false)) || (o.optBoolean("yt", false) && !n.optBoolean("yt", false)) ||
            (o.optBoolean("strict", false) && !n.optBoolean("strict", false)) || (o.optBoolean("autoLeave", true) && !n.optBoolean("autoLeave", true))
    }
    fun adultLoosens(old: JSONObject, new: JSONObject): Boolean {
        if (old.optBoolean("enabled", false) && !new.optBoolean("enabled", false)) return true
        val oa = old.optJSONArray("allow")?.length() ?: 0; val na = new.optJSONArray("allow")?.length() ?: 0
        return na > oa                                  // adding allow-list entries opens holes
    }
    fun writeShorts(ctx: Context, json: JSONObject) { p(ctx).edit().putString("shorts", json.toString()).apply() }
    fun writeAdult(ctx: Context, json: JSONObject) { p(ctx).edit().putString("adult", json.toString()).apply() }

    // ---- daily counts: {"2026-10-22": {"ig": 3, "yt": 5, "igA": 0, "ytA": 1}} ("A" = counted approximately) ----
    @Synchronized fun addCount(ctx: Context, app: String, approx: Boolean, now: Long = System.currentTimeMillis()): IntArray {
        val all = days(ctx); val k = ShortsPolicy.dayKey(now)
        val d = all.optJSONObject(k) ?: JSONObject().also { all.put(k, it) }
        d.put(app, d.optInt(app, 0) + 1)
        if (approx) d.put(app + "A", d.optInt(app + "A", 0) + 1)
        prune(all, now)
        p(ctx).edit().putString("days", all.toString()).apply()
        return intArrayOf(d.optInt(ShortsApp.IG, 0), d.optInt(ShortsApp.YT, 0))
    }
    @Synchronized fun days(ctx: Context): JSONObject = try { JSONObject(p(ctx).getString("days", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    @Synchronized fun today(ctx: Context): IntArray { val d = days(ctx).optJSONObject(ShortsPolicy.dayKey(System.currentTimeMillis())); return intArrayOf(d?.optInt("ig", 0) ?: 0, d?.optInt("yt", 0) ?: 0) }
    @Synchronized fun clearDays(ctx: Context) { p(ctx).edit().remove("days").apply() }
    private fun prune(all: JSONObject, now: Long) {            // keep ~13 months, tiny storage
        if (all.length() <= 400) return
        val keys = ArrayList<String>(); all.keys().forEach { keys.add(it) }; keys.sort()
        for (k in keys.take(keys.size - 400)) all.remove(k)
    }

    // ---- adult filter statistics (counts only, never domains) ----
    @Synchronized fun addBlocked(ctx: Context, n: Int, now: Long = System.currentTimeMillis()) {
        if (n <= 0) return
        val o = try { JSONObject(p(ctx).getString("adultStats", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val k = ShortsPolicy.dayKey(now)
        o.put(k, o.optInt(k, 0) + n); o.put("total", o.optLong("total", 0) + n)
        val keys = ArrayList<String>(); o.keys().forEach { if (it != "total") keys.add(it) }; keys.sort()
        if (keys.size > 120) for (x in keys.take(keys.size - 120)) o.remove(x)
        p(ctx).edit().putString("adultStats", o.toString()).apply()
    }
    @Synchronized fun adultStats(ctx: Context): JSONObject = try { JSONObject(p(ctx).getString("adultStats", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    fun listMeta(ctx: Context): JSONObject = try { JSONObject(p(ctx).getString("listMeta", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    fun writeListMeta(ctx: Context, m: JSONObject) { p(ctx).edit().putString("listMeta", m.toString()).apply() }

    /** How many times the shield appeared today (a count only). */
    @Synchronized fun addBlockedAttempt(ctx: Context) {
        val o = try { JSONObject(p(ctx).getString("shieldStats", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val k = ShortsPolicy.dayKey(System.currentTimeMillis())
        o.put(k, o.optInt(k, 0) + 1)
        val keys = ArrayList<String>(); o.keys().forEach { keys.add(it) }; keys.sort()
        if (keys.size > 120) for (x in keys.take(keys.size - 120)) o.remove(x)
        p(ctx).edit().putString("shieldStats", o.toString()).apply()
    }
    @Synchronized fun shieldToday(ctx: Context): Int = try { JSONObject(p(ctx).getString("shieldStats", "{}") ?: "{}").optInt(ShortsPolicy.dayKey(System.currentTimeMillis()), 0) } catch (e: Exception) { 0 }

    // ---- small diagnostic ring buffer (no usernames, captions or URLs: only event names and hashes) ----
    @Synchronized fun log(ctx: Context, line: String) {
        val a = try { JSONArray(p(ctx).getString("log", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        val out = JSONArray(); val from = maxOf(0, a.length() - 39)
        for (i in from until a.length()) out.put(a.get(i))
        out.put(java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + line.take(160))
        p(ctx).edit().putString("log", out.toString()).apply()
    }
    fun logText(ctx: Context): String = try { val a = JSONArray(p(ctx).getString("log", "[]")); (0 until a.length()).joinToString("\n") { a.getString(it) } } catch (e: Exception) { "" }
    fun clearLog(ctx: Context) { p(ctx).edit().remove("log").apply() }

    // ---- optional PIN: a speed bump against impulsive changes, NOT a security boundary (see docs) ----
    fun hasPin(ctx: Context) = p(ctx).contains("pinHash")
    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p(ctx).edit().putString("pinSalt", salt).putString("pinHash", hash(pin, salt)).putInt("pinFails", 0).putLong("pinLockUntil", 0).apply()
    }
    fun clearPin(ctx: Context) { p(ctx).edit().remove("pinHash").remove("pinSalt").apply() }
    /** 0 = ok, 1 = wrong, 2 = temporarily locked after repeated failures. */
    @Synchronized fun checkPin(ctx: Context, pin: String?): Int {
        val sp = p(ctx); val now = System.currentTimeMillis()
        if (now < sp.getLong("pinLockUntil", 0)) return 2
        val ok = pin != null && MessageDigest.isEqual(hash(pin, sp.getString("pinSalt", "") ?: "").toByteArray(), (sp.getString("pinHash", "") ?: "").toByteArray())
        if (ok) { sp.edit().putInt("pinFails", 0).apply(); return 0 }
        val f = sp.getInt("pinFails", 0) + 1
        sp.edit().putInt("pinFails", if (f >= 5) 0 else f).putLong("pinLockUntil", if (f >= 5) now + 60_000 else 0).apply()
        return if (f >= 5) 2 else 1
    }
}
