package com.smartisland.app

import java.util.Calendar
import java.util.TimeZone

/** Pure (Android-free) health logic so it can be unit-tested on the JVM. */

class DayRec(var steps: Long, var goal: Int, var partial: Boolean, var firstAt: Long, var lastAt: Long) {
    /** 0 no data, 1 unknown (partial, below goal), 2 below 50%, 3 50-99%, 4 goal reached. */
    fun level(): Int {
        if (goal > 0 && steps >= goal) return 4
        if (partial) return 1
        if (goal <= 0) return 2
        val p = steps * 100 / goal
        return if (p >= 50) 3 else 2
    }
}

/**
 * Daily step ledger. The Android step counter is a cumulative-since-boot value, so we keep the last raw reading
 * as a baseline and only ever add the difference. A reading lower than the baseline means the phone rebooted.
 * Days are keyed by the device's LOCAL calendar date. Each day keeps the goal that was active that day.
 */
class StepLedger(var lastRaw: Long = -1L, val days: MutableMap<String, DayRec> = LinkedHashMap()) {
    companion object {
        fun dayKey(ms: Long, tz: TimeZone): String {
            val c = Calendar.getInstance(tz)
            c.timeInMillis = ms
            return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }
    }

    private fun rec(key: String, now: Long, goal: Int, partial: Boolean): DayRec =
        days.getOrPut(key) { DayRec(0L, goal, partial, now, now) }

    /** Feed a cumulative reading. Returns the steps added (never negative). */
    fun onCounter(raw: Long, now: Long, tz: TimeZone, goal: Int): Long {
        val key = dayKey(now, tz)
        val first = lastRaw < 0L
        val reset = !first && raw < lastRaw
        val delta = when {
            first -> 0L                 // steps taken before tracking started are unknown: never invented
            reset -> raw                // counter restarted at boot: everything it reports happened after our last reading
            else -> raw - lastRaw
        }
        val r = rec(key, now, goal, first)   // the first day we ever track is partial (we missed the earlier steps)
        if (reset) r.partial = true          // steps between the last reading and the reboot cannot be recovered
        r.steps += delta
        r.lastAt = now
        lastRaw = raw
        return delta
    }

    /** Step-detector fallback: one event per step, no cumulative baseline, so days are always flagged partial. */
    fun onDetected(n: Int, now: Long, tz: TimeZone, goal: Int) {
        val r = rec(dayKey(now, tz), now, goal, true)
        r.steps += maxOf(0, n); r.lastAt = now
    }

    /** Goal edits change TODAY only; finished days keep the goal they ended with. */
    fun setGoal(goal: Int, now: Long, tz: TimeZone) { days[dayKey(now, tz)]?.goal = goal }

    fun today(now: Long, tz: TimeZone): DayRec? = days[dayKey(now, tz)]

    fun prune(now: Long, tz: TimeZone, keepDays: Int = 400) {
        val cutoff = dayKey(now - keepDays * 86400000L, tz)
        val it = days.keys.iterator()
        while (it.hasNext()) if (it.next() < cutoff) it.remove()
    }

    /** Levels for the last [n] days ending today (oldest first); missing days are level 0, never invented. */
    fun recentLevels(now: Long, tz: TimeZone, n: Int): IntArray {
        val out = IntArray(n)
        for (i in 0 until n) {
            val c = Calendar.getInstance(tz)
            c.timeInMillis = now
            c.add(Calendar.DAY_OF_YEAR, -(n - 1 - i))
            out[i] = days[dayKey(c.timeInMillis, tz)]?.level() ?: 0
        }
        return out
    }
}

/** Hysteresis walking detector: enter after [minSteps] steps inside [windowMs]; leave after [idleMs] without steps. */
class WalkDetector(var minSteps: Int = 6, var windowMs: Long = 10000L, var idleMs: Long = 8000L) {
    private val times = ArrayList<Long>()
    private var lastStepAt = 0L
    var walking = false
        private set

    private fun prune(now: Long) { while (times.isNotEmpty() && now - times[0] > windowMs) times.removeAt(0) }

    fun onSteps(n: Int, now: Long) {
        for (i in 0 until n) times.add(now)
        lastStepAt = now
        prune(now)
        if (!walking && times.size >= minSteps) walking = true
    }

    fun tick(now: Long): Boolean {
        prune(now)
        if (walking && now - lastStepAt >= idleMs) { walking = false; times.clear() }
        return walking
    }

    fun reset() { walking = false; times.clear(); lastStepAt = 0L }
}

class WaterCfg(
    val enabled: Boolean, val intervalMin: Int, val startMin: Int, val endMin: Int, val days: BooleanArray,
    val quietEnabled: Boolean, val quietStartMin: Int, val quietEndMin: Int, val pausedUntil: Long
)

object WaterSchedule {
    /** Monday = 0 ... Sunday = 6 (java.util.Calendar uses Sunday = 1). */
    fun dayIdx(c: Calendar): Int = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7

    private fun inQuiet(m: Int, cfg: WaterCfg): Boolean {
        if (!cfg.quietEnabled) return false
        val s = cfg.quietStartMin; val e = cfg.quietEndMin
        return if (s <= e) m >= s && m < e else (m >= s || m < e)   // a quiet window may cross midnight
    }

    private fun endOf(cfg: WaterCfg) = if (cfg.endMin >= cfg.startMin) cfg.endMin else 1439

    /** Would a reminder be acceptable at [now]? Used to drop alarms that Doze delayed past the end of the window. */
    fun allowedNow(now: Long, cfg: WaterCfg, tz: TimeZone): Boolean {
        if (!cfg.enabled || now < cfg.pausedUntil) return false
        val c = Calendar.getInstance(tz); c.timeInMillis = now
        if (!cfg.days[dayIdx(c)]) return false
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return m >= cfg.startMin && m <= endOf(cfg) + 15 && !inQuiet(m, cfg)
    }

    /** Next reminder strictly after [after]: slots are start, start+interval, ... up to end, on active days, minus quiet/paused. */
    fun next(after: Long, cfg: WaterCfg, tz: TimeZone): Long? {
        if (!cfg.enabled || cfg.intervalMin < 1) return null
        val end = endOf(cfg)
        for (d in 0..8) {
            val day = Calendar.getInstance(tz)
            day.timeInMillis = after
            day.add(Calendar.DAY_OF_YEAR, d)
            if (!cfg.days[dayIdx(day)]) continue
            var m = cfg.startMin
            while (m <= end) {
                val c = Calendar.getInstance(tz)
                c.timeInMillis = day.timeInMillis
                c.set(Calendar.HOUR_OF_DAY, m / 60); c.set(Calendar.MINUTE, m % 60)
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                val t = c.timeInMillis
                if (t > after && t >= cfg.pausedUntil && !inQuiet(m, cfg)) return t
                m += cfg.intervalMin
            }
        }
        return null
    }
}

/** Contribution grid shared by the home-screen widget: columns = weeks (oldest first), rows = Monday..Sunday. */
object HeatGrid {
    /** -1 = a day that has not happened yet; 0..4 = DayRec.level (0 = no data, never filled in). */
    fun build(days: Map<String, DayRec>, now: Long, tz: TimeZone, weeks: Int): Array<IntArray> {
        val today = Calendar.getInstance(tz)
        today.timeInMillis = now
        val idx = WaterSchedule.dayIdx(today)
        return Array(weeks) { w ->
            IntArray(7) { r ->
                val back = (weeks - 1 - w) * 7 + (idx - r)
                if (back < 0) -1 else {
                    val c = Calendar.getInstance(tz)
                    c.timeInMillis = now
                    c.add(Calendar.DAY_OF_YEAR, -back)     // calendar arithmetic, so DST cannot skip or repeat a day
                    days[StepLedger.dayKey(c.timeInMillis, tz)]?.level() ?: 0
                }
            }
        }
    }
}
