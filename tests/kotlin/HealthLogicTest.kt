import com.smartisland.app.*
import java.util.Calendar
import java.util.TimeZone

var passed = 0
fun check(cond: Boolean, msg: String) { if (!cond) throw AssertionError("FAIL: $msg"); passed++ }
fun t(tz: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long { val c = Calendar.getInstance(tz); c.clear(); c.set(y, mo - 1, d, h, mi, 0); return c.timeInMillis }
fun hm(ms: Long, tz: TimeZone): String { val c = Calendar.getInstance(tz); c.timeInMillis = ms; return String.format("%02d-%02d %02d:%02d", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }
fun cfg(days: BooleanArray = BooleanArray(7) { true }, interval: Int = 60, start: Int = 9 * 60, end: Int = 21 * 60, quiet: Boolean = false, qs: Int = 780, qe: Int = 840, paused: Long = 0L, enabled: Boolean = true) =
    WaterCfg(enabled, interval, start, end, days, quiet, qs, qe, paused)

fun main() {
    val tz = TimeZone.getTimeZone("Asia/Kolkata")

    // ---- step aggregation ----
    val l = StepLedger()
    val t0 = t(tz, 2026, 10, 16, 9, 0)
    check(l.onCounter(1000, t0, tz, 8000) == 0L, "first reading adds nothing (no invented steps)")
    check(l.today(t0, tz)!!.steps == 0L && l.today(t0, tz)!!.partial, "first day is flagged partial")
    check(l.onCounter(1500, t0 + 60000, tz, 8000) == 500L, "delta added")
    check(l.onCounter(1500, t0 + 120000, tz, 8000) == 0L, "same cumulative value adds nothing (no double count)")
    check(l.today(t0, tz)!!.steps == 500L, "total is 500")
    check(l.onCounter(300, t0 + 180000, tz, 8000) == 300L, "reboot: counter restarted, count raw")
    check(l.today(t0, tz)!!.steps == 800L && l.today(t0, tz)!!.partial, "reboot adds steps and flags day partial")
    check(l.days.values.all { it.steps >= 0 }, "never negative")

    // ---- midnight / calendar day ----
    val l2 = StepLedger()
    val a = t(tz, 2026, 10, 16, 23, 50); val b = t(tz, 2026, 10, 17, 0, 10)
    l2.onCounter(100, a - 3600000, tz, 8000)
    l2.onCounter(5100, a, tz, 8000)
    l2.onCounter(5200, b, tz, 8000)
    check(l2.days["2026-10-16"]!!.steps == 5000L, "day 16 total preserved after midnight")
    check(l2.days["2026-10-17"]!!.steps == 100L, "new day starts from baseline, not from cumulative total")
    check(!l2.days["2026-10-17"]!!.partial, "continuing day is not partial")

    // ---- goals & completion are per day ----
    l2.setGoal(10000, b, tz)
    check(l2.days["2026-10-16"]!!.goal == 8000, "past day keeps its original goal")
    check(l2.days["2026-10-17"]!!.goal == 10000, "today's goal can change")
    check(l2.days["2026-10-16"]!!.level() == 1, "first tracked day is partial, so 5000/8000 is unknown, not missed")
    check(DayRec(5000, 8000, false, 0, 0).level() == 3, "fully tracked 5000/8000 is 62% -> level 3")
    val full = DayRec(8000, 8000, false, 0, 0); check(full.level() == 4, "goal reached = level 4")
    val fullPartial = DayRec(9000, 8000, true, 0, 0); check(fullPartial.level() == 4, "goal reached beats partial")
    val unk = DayRec(100, 8000, true, 0, 0); check(unk.level() == 1, "partial below goal = unknown, not a missed day")
    val missed = DayRec(100, 8000, false, 0, 0); check(missed.level() == 2, "tracked below 50% = missed (level 2)")
    check(l2.recentLevels(b, tz, 3).toList() == listOf(0, 1, 2), "recent levels: untracked day is 0, never fabricated")

    // ---- detector fallback + prune ----
    val l3 = StepLedger(); l3.onDetected(5, a, tz, 8000); l3.onDetected(3, a + 1000, tz, 8000)
    check(l3.today(a, tz)!!.steps == 8L && l3.today(a, tz)!!.partial, "detector counts events and is flagged partial")
    val old = StepLedger(); old.days["2020-01-01"] = DayRec(1, 1, false, 0, 0); old.days["2026-10-16"] = DayRec(1, 1, false, 0, 0)
    old.prune(a, tz); check(old.days.keys.toList() == listOf("2026-10-16"), "prune drops very old days only")

    // DST: a US zone across the spring-forward day still yields distinct consecutive keys
    val ny = TimeZone.getTimeZone("America/New_York")
    val ls = StepLedger(); ls.onCounter(0, t(ny, 2026, 3, 7, 12, 0), ny, 8000)
    ls.onCounter(10, t(ny, 2026, 3, 8, 12, 0), ny, 8000); ls.onCounter(20, t(ny, 2026, 3, 9, 12, 0), ny, 8000)
    check(ls.days.keys.toList() == listOf("2026-03-07", "2026-03-08", "2026-03-09"), "DST day is a single calendar day")

    // ---- walking detection hysteresis ----
    val w = WalkDetector(minSteps = 6, windowMs = 10000, idleMs = 8000)
    w.onSteps(2, 0); check(!w.tick(1000), "2 steps is not walking")
    w.onSteps(4, 2000); check(w.tick(2000), "6 steps in window = walking")
    check(w.tick(9000), "stays walking during the idle grace period (no flicker)")
    w.onSteps(1, 9500); check(w.tick(17000), "a new step restarts the idle timer")
    check(!w.tick(17600), "leaves walking after idleMs without steps")
    w.onSteps(3, 30000); check(!w.tick(30000), "old steps do not count towards a new walk")

    // ---- water schedule ----
    val c1 = cfg()
    var n = WaterSchedule.next(t(tz, 2026, 10, 16, 8, 0), c1, tz)!!; check(hm(n, tz) == "10-16 09:00", "first slot at start time, got ${hm(n, tz)}")
    n = WaterSchedule.next(t(tz, 2026, 10, 16, 9, 0), c1, tz)!!; check(hm(n, tz) == "10-16 10:00", "strictly after: got ${hm(n, tz)}")
    n = WaterSchedule.next(t(tz, 2026, 10, 16, 21, 0), c1, tz)!!; check(hm(n, tz) == "10-17 09:00", "after the end rolls to next day: got ${hm(n, tz)}")
    n = WaterSchedule.next(t(tz, 2026, 10, 16, 8, 0), cfg(interval = 120), tz)!!; check(hm(n, tz) == "10-16 09:00", "2h interval")
    n = WaterSchedule.next(t(tz, 2026, 10, 16, 9, 30), cfg(interval = 120), tz)!!; check(hm(n, tz) == "10-16 11:00", "2h interval next")
    n = WaterSchedule.next(t(tz, 2026, 10, 16, 12, 30), cfg(quiet = true), tz)!!; check(hm(n, tz) == "10-16 14:00", "quiet 13:00-14:00 skipped: got ${hm(n, tz)}")
    val weekdaysOnly = BooleanArray(7) { it < 5 }   // Oct 16 2026 is a Friday
    n = WaterSchedule.next(t(tz, 2026, 10, 16, 22, 0), cfg(days = weekdaysOnly), tz)!!; check(hm(n, tz) == "10-19 09:00", "skips Sat/Sun: got ${hm(n, tz)}")
    n = WaterSchedule.next(t(tz, 2026, 10, 16, 8, 0), cfg(paused = t(tz, 2026, 10, 16, 12, 0)), tz)!!; check(hm(n, tz) == "10-16 12:00", "pause defers to the pause end: got ${hm(n, tz)}")
    check(WaterSchedule.next(0, cfg(enabled = false), tz) == null, "disabled -> nothing scheduled")
    check(WaterSchedule.next(0, cfg(days = BooleanArray(7)), tz) == null, "no active days -> nothing scheduled")
    val night = cfg(quiet = true, qs = 22 * 60, qe = 7 * 60, start = 0, end = 1439)
    n = WaterSchedule.next(t(tz, 2026, 10, 16, 21, 30), night, tz)!!; check(hm(n, tz) == "10-17 07:00", "quiet window across midnight: got ${hm(n, tz)}")
    check(WaterSchedule.allowedNow(t(tz, 2026, 10, 16, 10, 0), c1, tz), "allowed inside window")
    check(!WaterSchedule.allowedNow(t(tz, 2026, 10, 16, 23, 0), c1, tz), "a delayed alarm after the window is dropped")
    // DST day schedule is monotonic and never in the past
    val dst = cfg(interval = 60, start = 60, end = 300)
    var cur = t(ny, 2026, 3, 8, 0, 0); var count = 0
    while (count < 6) { val x = WaterSchedule.next(cur, dst, ny)!!; check(x > cur, "monotonic across DST"); cur = x; count++ }

    widgetTests()
    println("ALL $passed CHECKS PASSED")
}

fun widgetTests() {
    val tz = TimeZone.getTimeZone("Asia/Kolkata")
    fun at(y: Int, mo: Int, d: Int): Long { val c = Calendar.getInstance(tz); c.clear(); c.set(y, mo - 1, d, 12, 0, 0); return c.timeInMillis }
    val today = at(2026, 10, 16)   // Friday
    val days = mutableMapOf("2026-10-16" to DayRec(8000, 8000, false, 0, 0), "2026-10-15" to DayRec(100, 8000, false, 0, 0))
    val g = HeatGrid.build(days, today, tz, 5)
    check(g.size == 5 && g.all { it.size == 7 }, "grid is weeks x 7")
    check(g[4][4] == 4, "today (Friday, last column) shows goal reached")
    check(g[4][3] == 2, "Thursday is a tracked miss")
    check(g[4][5] == -1 && g[4][6] == -1, "Saturday/Sunday are future days")
    check(g[0][0] == 0 && g[2][2] == 0, "untracked days are 0, never invented")
    val ny = TimeZone.getTimeZone("America/New_York")
    fun atNy(y: Int, mo: Int, d: Int): Long { val c = Calendar.getInstance(ny); c.clear(); c.set(y, mo - 1, d, 12, 0, 0); return c.timeInMillis }
    val dstDays = mutableMapOf("2026-03-08" to DayRec(9000, 8000, false, 0, 0))
    val gd = HeatGrid.build(dstDays, atNy(2026, 3, 9), ny, 3)   // Monday after spring-forward
    check(gd[1][6] == 4, "DST Sunday (Mar 8) sits in the last row of the previous week column")
    check(gd[2][0] == 0 && gd[2][1] == -1, "Monday Mar 9 is today (no data) and the rest of that week is future")
}
