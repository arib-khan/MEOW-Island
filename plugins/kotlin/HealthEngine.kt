package com.smartisland.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Calendar
import java.util.TimeZone

/**
 * Optional Health & Wellness engine. Everything is OFF until the user enables it in the app, stays on the device
 * (SharedPreferences), and stops as soon as the corresponding switches are turned off.
 *
 * Step counting is event driven: the hardware step sensor is only listened to while the screen is on and the island
 * service runs; otherwise a coarse (~30 min, battery-friendly) alarm takes one cumulative reading. Because the
 * counter is cumulative since boot, steps taken while nothing was listening are still counted at the next reading.
 */
@SuppressLint("NewApi")
object HealthEngine {
    private const val PREF = "health"
    const val ACTION_SYNC = "com.smartisland.app.HEALTH_SYNC"
    private var ledger: StepLedger? = null
    private var lastSave = 0L
    private var lastEmit = 0L
    private var lastWidget = 0L
    private var live: SensorEventListener? = null
    private val walk = WalkDetector()
    private val fmt: NumberFormat = NumberFormat.getIntegerInstance()

    fun cfg(ctx: Context): JSONObject = try {
        JSONObject(ctx.applicationContext.getSharedPreferences("health_cfg", 0).getString("cfg", "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    fun writeCfg(ctx: Context, json: String) { ctx.applicationContext.getSharedPreferences("health_cfg", 0).edit().putString("cfg", json).apply() }

    fun needed(c: JSONObject) = c.optBoolean("stepCounter") || c.optBoolean("walkingIsland") || c.optBoolean("goalEnabled") || c.optBoolean("heatmap")
    private fun goal(c: JSONObject) = c.optInt("goal", 8000).coerceIn(500, 100000)

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission("android.permission.ACTIVITY_RECOGNITION") == PackageManager.PERMISSION_GRANTED

    private fun sensorOf(ctx: Context, type: Int): Sensor? = (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(type)

    // ---------- persistence ----------
    @Synchronized private fun load(ctx: Context): StepLedger {
        ledger?.let { return it }
        val l = StepLedger()
        try {
            val o = JSONObject(ctx.applicationContext.getSharedPreferences(PREF, 0).getString("ledger", "{}") ?: "{}")
            l.lastRaw = o.optLong("lastRaw", -1L)
            val d = o.optJSONObject("days")
            if (d != null) for (k in d.keys()) {
                val j = d.getJSONObject(k)
                l.days[k] = DayRec(j.optLong("s"), j.optInt("g", 8000), j.optBoolean("p"), j.optLong("f"), j.optLong("l"))
            }
        } catch (e: Exception) { }
        ledger = l
        return l
    }

    @Synchronized fun save(ctx: Context, force: Boolean) {
        val l = ledger ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 5000L) return
        lastSave = now
        l.prune(now, TimeZone.getDefault())
        val o = JSONObject(); o.put("lastRaw", l.lastRaw)
        val d = JSONObject()
        for ((k, r) in l.days) {
            val j = JSONObject(); j.put("s", r.steps); j.put("g", r.goal); j.put("p", r.partial); j.put("f", r.firstAt); j.put("l", r.lastAt)
            d.put(k, j)
        }
        o.put("days", d)
        ctx.applicationContext.getSharedPreferences(PREF, 0).edit().putString("ledger", o.toString()).apply()
        if (force || now - lastWidget > 600000L) {        // keep the home-screen heatmap fresh without redrawing on every step
            lastWidget = now
            try { MeowWidgets.updateAll(ctx.applicationContext) } catch (e: Exception) { }
        }
    }

    @Synchronized fun todaySteps(ctx: Context): Long? = load(ctx).today(System.currentTimeMillis(), TimeZone.getDefault())?.steps

    @Synchronized fun gridLevels(ctx: Context, weeks: Int): Array<IntArray> =
        HeatGrid.build(load(ctx).days, System.currentTimeMillis(), TimeZone.getDefault(), weeks)

    // ---------- readings ----------
    @Synchronized fun onSensor(ctx: Context, type: Int, value: Float) {
        val c = cfg(ctx)
        if (!needed(c)) return
        val now = System.currentTimeMillis(); val tz = TimeZone.getDefault(); val l = load(ctx); val g = goal(c)
        val delta: Long = if (type == Sensor.TYPE_STEP_COUNTER) l.onCounter(value.toLong(), now, tz, g) else { l.onDetected(1, now, tz, g); 1L }
        // large deltas are catch-up readings (screen was off), not live walking
        if (delta in 1..25) walk.onSteps(delta.toInt(), now)
        save(ctx, false)
        if (now - lastEmit > 1000L) {
            lastEmit = now
            val r = l.today(now, tz)
            IslandModule.emit("health", mapOf("steps" to (r?.steps ?: 0L).toDouble(), "goal" to (r?.goal ?: g).toDouble()))
        }
        refreshIsland(ctx, c)
    }

    private fun celebrated(ctx: Context, key: String) = ctx.applicationContext.getSharedPreferences(PREF, 0).getBoolean("cel|$key", false)
    private fun markCelebrated(ctx: Context, key: String) {
        val sp = ctx.applicationContext.getSharedPreferences(PREF, 0); val ed = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("cel|") && k != "cel|$key") ed.remove(k)
        ed.putBoolean("cel|$key", true).apply()
    }

    /** Called on every step event and once per service tick: decides what, if anything, the island shows. */
    @Synchronized fun refreshIsland(ctx: Context, c: JSONObject = cfg(ctx)) {
        val now = System.currentTimeMillis(); val tz = TimeZone.getDefault()
        val walkOn = c.optBoolean("walkingIsland"); val goalOn = c.optBoolean("goalEnabled")
        if (!needed(c) || (!walkOn && !goalOn)) { IslandBus.remove("walk"); IslandBus.remove("goal"); return }
        val l = load(ctx); val rec = l.today(now, tz)
        val steps = rec?.steps ?: 0L; val g = rec?.goal ?: goal(c)
        val key = StepLedger.dayKey(now, tz)
        val heat = if (c.optBoolean("heatmapOnIsland") && c.optBoolean("heatmap")) l.recentLevels(now, tz, 7) else null
        if (goalOn && rec != null && steps >= g && !celebrated(ctx, key)) {
            markCelebrated(ctx, key)
            IslandBus.post(Act("goal", 85, fmt.format(g) + " steps!", "Daily goal reached!\n" + fmt.format(steps) + " / " + fmt.format(g) + " steps today",
                emoji = "\uD83D\uDE3A", prog = 1f, celebrate = true, heat = heat, expand = true, pulse = now, expireAt = now + 9000L))
        }
        if (walkOn && walk.tick(now)) {
            val pct = if (g > 0) minOf(100L, steps * 100 / g) else 0L
            val title = if (goalOn) fmt.format(steps) + " / " + fmt.format(g) else fmt.format(steps) + " steps"
            val sub = if (goalOn) "Today: " + fmt.format(steps) + " / " + fmt.format(g) + " steps\n" + fmt.format(maxOf(0L, g - steps)) + " left \u00B7 " + pct + "%"
                else "Today: " + fmt.format(steps) + " steps"
            IslandBus.post(Act("walk", 55, title, sub, emoji = "\uD83D\uDC3E", prog = if (goalOn && g > 0) minOf(1f, steps.toFloat() / g) else -1f, heat = heat))
        } else IslandBus.remove("walk")
    }

    // ---------- live listener (hosted by the island service) ----------
    @Synchronized fun ensureLive(ctx: Context, interactive: Boolean) {
        val c = cfg(ctx)
        val should = interactive && needed(c) && hasPermission(ctx) && (sensorOf(ctx, Sensor.TYPE_STEP_COUNTER) != null || sensorOf(ctx, Sensor.TYPE_STEP_DETECTOR) != null)
        if (should && live == null) startLive(ctx)
        else if (!should && live != null) stopLive(ctx)
    }

    private fun startLive(ctx: Context) {
        val sm = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val s = counter ?: sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        val app = ctx.applicationContext
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { onSensor(app, e.sensor.type, e.values[0]) }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
        }
        live = l
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    @Synchronized fun stopLive(ctx: Context) {
        val l = live ?: return
        (ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(l)
        live = null
        save(ctx, true)
    }

    // ---------- coarse background sync (no foreground service needed) ----------
    private fun syncIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 200, Intent(ctx, HealthReceiver::class.java).setAction(ACTION_SYNC), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun armSync(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val c = cfg(ctx)
        if (!needed(c) || !hasPermission(ctx) || sensorOf(ctx, Sensor.TYPE_STEP_COUNTER) == null) { am.cancel(syncIntent(ctx)); return }
        val now = System.currentTimeMillis()
        val mid = Calendar.getInstance(); mid.add(Calendar.DAY_OF_YEAR, 1)
        mid.set(Calendar.HOUR_OF_DAY, 0); mid.set(Calendar.MINUTE, 0); mid.set(Calendar.SECOND, 20); mid.set(Calendar.MILLISECOND, 0)
        // inexact + allow-while-idle: no exact-alarm permission; Doze may delay it, which only delays the catch-up reading
        am.setAndAllowWhileIdle(AlarmManager.RTC, minOf(now + 30 * 60000L, mid.timeInMillis), syncIntent(ctx))
    }

    /** One cumulative reading, then [done]. Used by the alarm; safe if the sensor/permission is missing. */
    fun syncOnce(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (s == null || !needed(cfg(app)) || !hasPermission(app)) { done(); return }
        val h = Handler(Looper.getMainLooper())
        var finished = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (finished) return
                finished = true; sm.unregisterListener(this)
                onSensor(app, Sensor.TYPE_STEP_COUNTER, e.values[0]); save(app, true); done()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
        }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, h)
        h.postDelayed({ if (!finished) { finished = true; sm.unregisterListener(l); done() } }, 8000L)
    }

    /** Re-applies user settings: walking thresholds, goal for today, alarms, listener, island state. */
    @Synchronized fun configure(ctx: Context) {
        val c = cfg(ctx)
        val sens = c.optString("walkSensitivity", "normal")
        walk.minSteps = if (sens == "low") 10 else if (sens == "high") 4 else 6
        walk.idleMs = c.optInt("walkReturnSec", 8).coerceIn(3, 60) * 1000L
        if (!needed(c)) {
            stopLive(ctx); walk.reset(); IslandBus.remove("walk"); IslandBus.remove("goal")
        } else {
            val now = System.currentTimeMillis()
            load(ctx).setGoal(goal(c), now, TimeZone.getDefault()); save(ctx, true)
            refreshIsland(ctx, c)
        }
        armSync(ctx)
        try { MeowWidgets.updateAll(ctx.applicationContext) } catch (e: Exception) { }
    }

    @Synchronized fun clear(ctx: Context) {
        stopLive(ctx); walk.reset(); ledger = StepLedger()
        ctx.applicationContext.getSharedPreferences(PREF, 0).edit().clear().apply()
        ctx.applicationContext.getSharedPreferences("health_water", 0).edit().clear().apply()
        IslandBus.remove("walk"); IslandBus.remove("goal")
        try { MeowWidgets.updateAll(ctx.applicationContext) } catch (e: Exception) { }
    }

    @Synchronized fun snapshot(ctx: Context): String {
        val now = System.currentTimeMillis(); val tz = TimeZone.getDefault(); val c = cfg(ctx); val l = load(ctx)
        val rec = l.today(now, tz)
        val o = JSONObject()
        o.put("day", StepLedger.dayKey(now, tz)); o.put("steps", rec?.steps ?: 0L); o.put("goal", rec?.goal ?: goal(c)); o.put("partial", rec?.partial ?: false)
        o.put("hasToday", rec != null)
        val d = JSONObject()
        for ((k, r) in l.days) { val j = JSONObject(); j.put("s", r.steps); j.put("g", r.goal); j.put("p", r.partial); d.put(k, j) }
        o.put("days", d)
        val st = JSONObject()
        st.put("counter", sensorOf(ctx, Sensor.TYPE_STEP_COUNTER) != null); st.put("detector", sensorOf(ctx, Sensor.TYPE_STEP_DETECTOR) != null)
        st.put("permission", hasPermission(ctx)); st.put("sdk", Build.VERSION.SDK_INT); st.put("live", live != null)
        o.put("status", st)
        val w = JSONObject()
        val sp = ctx.applicationContext.getSharedPreferences("health_water", 0)
        w.put("glasses", sp.getInt("glasses|" + StepLedger.dayKey(now, tz), 0)); w.put("nextAt", sp.getLong("nextAt", 0L)); w.put("snoozeAt", sp.getLong("snoozeAt", 0L))
        o.put("water", w)
        return o.toString()
    }
}

class HealthReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != HealthEngine.ACTION_SYNC) return
        val pr = goAsync()
        var finished = false
        HealthEngine.syncOnce(ctx) { if (!finished) { finished = true; pr.finish() } }
        HealthEngine.armSync(ctx)
    }
}

/** Water reminders: AlarmManager (inexact, no polling, no exact-alarm permission), notifications and/or the island. */
@SuppressLint("NewApi")
object Water {
    const val FIRE = "com.smartisland.app.WATER_FIRE"
    const val DONE = "com.smartisland.app.WATER_DONE"
    const val SNOOZE = "com.smartisland.app.WATER_SNOOZE"
    const val DISMISS = "com.smartisland.app.WATER_DISMISS"
    private const val CH = "water"
    private const val NID = 4242

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("health_water", 0)

    fun cfgOf(c: JSONObject): WaterCfg {
        val w = c.optJSONObject("water") ?: JSONObject()
        val d = BooleanArray(7) { true }
        val arr: JSONArray? = w.optJSONArray("days")
        if (arr != null) for (i in 0 until minOf(7, arr.length())) d[i] = arr.optBoolean(i, true)
        return WaterCfg(w.optBoolean("enabled", false), w.optInt("intervalMin", 60).coerceIn(5, 1440),
            w.optInt("startMin", 540).coerceIn(0, 1439), w.optInt("endMin", 1260).coerceIn(0, 1439), d,
            w.optBoolean("quietEnabled", false), w.optInt("quietStartMin", 780).coerceIn(0, 1439), w.optInt("quietEndMin", 840).coerceIn(0, 1439),
            w.optLong("pausedUntil", 0L))
    }

    private fun pi(ctx: Context, action: String, slot: Long, req: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, req, Intent(ctx, WaterReceiver::class.java).setAction(action).putExtra("slot", slot),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** (Re)computes the single next alarm. Same PendingIntent every time, so restarts can never create duplicates. */
    fun arm(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val w = cfgOf(HealthEngine.cfg(app))
        if (!w.enabled) { am.cancel(pi(app, FIRE, 0L, 100)); sp(app).edit().remove("nextAt").remove("snoozeAt").apply(); return }
        val now = System.currentTimeMillis()
        var next = WaterSchedule.next(now, w, TimeZone.getDefault())
        val snooze = sp(app).getLong("snoozeAt", 0L)
        if (snooze > now) next = if (next == null) snooze else minOf(next, snooze)
        if (next == null) { am.cancel(pi(app, FIRE, 0L, 100)); sp(app).edit().remove("nextAt").apply(); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(app, FIRE, next, 100))
        sp(app).edit().putLong("nextAt", next).apply()
    }

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CH, "Water reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun fire(ctx: Context, slot: Long, force: Boolean) {
        val app = ctx.applicationContext
        val c = HealthEngine.cfg(app); val w = cfgOf(c)
        val s = sp(app); val now = System.currentTimeMillis()
        val snoozed = slot != 0L && slot == s.getLong("snoozeAt", 0L)
        if (!force) {
            if (slot != 0L && slot <= s.getLong("lastSlot", 0L)) { arm(app); return }    // duplicate / stale delivery
            s.edit().putLong("lastSlot", slot).apply()
            if (snoozed) s.edit().remove("snoozeAt").apply()
            if (!w.enabled || (!snoozed && !WaterSchedule.allowedNow(now, w, TimeZone.getDefault()))) { arm(app); return }
        }
        val wc = c.optJSONObject("water") ?: JSONObject()
        if (wc.optBoolean("toNotification", true)) notify(app)
        if (wc.optBoolean("toIsland", true)) {
            IslandBus.post(Act("water", 78, "\uD83D\uDCA7 Time to drink water!", "Stay hydrated \u2014 tap Done when you have.", water = true, expand = true,
                pulse = now, expireAt = now + 120000L))
        }
        arm(app)
    }

    private fun notify(ctx: Context) {
        try {
            channel(ctx)
            val icon = ctx.resources.getIdentifier("ic_meow", "drawable", ctx.packageName).let { if (it != 0) it else android.R.drawable.ic_dialog_info }
            val open = PendingIntent.getActivity(ctx, 0, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(icon)
                .setContentTitle("\uD83D\uDCA7 Time to drink water!").setContentText("Take a moment to hydrate.")
                .setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, "Done", pi(ctx, DONE, 0L, 101)).addAction(0, "Snooze", pi(ctx, SNOOZE, 0L, 102)).addAction(0, "Dismiss", pi(ctx, DISMISS, 0L, 103))
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NID, n)
        } catch (e: Exception) { }   // missing POST_NOTIFICATIONS on Android 13+ simply means no notification
    }

    private fun clearUi(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NID) } catch (e: Exception) { }
        IslandBus.remove("water")
    }

    fun done(ctx: Context) {
        val app = ctx.applicationContext; val s = sp(app)
        val k = "glasses|" + StepLedger.dayKey(System.currentTimeMillis(), TimeZone.getDefault())
        s.edit().putInt(k, s.getInt(k, 0) + 1).apply()
        clearUi(app)
    }

    fun snooze(ctx: Context) {
        val app = ctx.applicationContext
        val min = (HealthEngine.cfg(app).optJSONObject("water")?.optInt("snoozeMin", 10) ?: 10).coerceIn(1, 120)
        sp(app).edit().putLong("snoozeAt", System.currentTimeMillis() + min * 60000L).apply()
        clearUi(app); arm(app)
    }

    fun dismiss(ctx: Context) { clearUi(ctx.applicationContext) }
}

class WaterReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            Water.FIRE -> Water.fire(ctx, i.getLongExtra("slot", 0L), false)
            Water.DONE -> Water.done(ctx)
            Water.SNOOZE -> Water.snooze(ctx)
            Water.DISMISS -> Water.dismiss(ctx)
        }
    }
}
