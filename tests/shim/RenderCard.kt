import android.graphics.Bitmap
import com.smartisland.app.StepCardData
import com.smartisland.app.StepCardRenderer
import java.io.File
import javax.imageio.ImageIO

fun load(path: String): Bitmap? = try { Bitmap(ImageIO.read(File(path))) } catch (e: Exception) { null }

var checks = 0
fun px(b: Bitmap, x: Double, y: Double): IntArray {
    val k = b.width / 1098.0; val c = b.img.getRGB((x * k).toInt(), (y * k).toInt())
    return intArrayOf((c shr 16) and 255, (c shr 8) and 255, c and 255)
}
fun isTrack(c: IntArray) = Math.abs(c[0] - 42) < 14 && Math.abs(c[1] - 36) < 14 && Math.abs(c[2] - 56) < 16
fun isPurple(c: IntArray) = c[2] > 170 && c[0] > 90 && c[1] < 150
fun ok(cond: Boolean, msg: String) { if (!cond) throw AssertionError("RENDER CHECK FAILED: $msg"); checks++ }

fun main(args: Array<String>) {
    val dir = args[0]; val out = args[1]
    val angry = load("$dir/meow_cat_angry.png"); val happy = load("$dir/meow_cat_happy.png")
    val paw = load("$dir/meow_paw.png"); val shoe = load("$dir/meow_shoe.png")
    val mock = IntArray(54); mapOf(12 to 3, 19 to 3, 21 to 2, 25 to 4, 32 to 4, 33 to 3, 38 to 2, 46 to 4).forEach { (i, l) -> mock[i] = l }
    fun save(name: String, w: Int, d: StepCardData, cat: Bitmap?) {
        val b = StepCardRenderer.render(w, d, cat, paw, shoe); ImageIO.write(b.img, "png", File("$out/$name.png")); println("$name ${b.width}x${b.height}")
    }
    // 1) the design from the mock-up: 7,240 / 10,000, 72 %
    save("card_mock", 1098, StepCardData(7240, 10000, true, true, true, mock, "Today's Steps", "", "Keep moving", false), angry)
    // 2) goal reached: happy cat
    val full = mock.copyOf(); full[53] = 4
    save("card_goal", 1098, StepCardData(10450, 10000, true, true, true, full, "Today's Steps", "", "Goal reached!", true), happy)
    // 3) nothing enabled yet: honest prompts, no numbers
    save("card_off", 1098, StepCardData(0, 10000, false, false, false, IntArray(54), "Step counter is off", "Turn it on in Meow Island \u2192 Health", "Every step counts", false), angry)
    // 4) step counter on but goal off: steps only
    save("card_nogoal", 1098, StepCardData(4210, 10000, true, false, true, mock, "Today's Steps", "", "Every step counts", false), angry)
    // 5) very large numbers must not collide with the % pill
    save("card_big", 1098, StepCardData(100000, 100000, true, true, true, mock, "Today's Steps", "", "Goal reached!", true), happy)
    // ---- pixel checks on the ring, grid and honest states (ring centre 236,1153 r=105; 10:30 o'clock is the unfilled gap at 72 %) ----
    val cx = 236.0; val cy = 1153.0
    fun ring(b: Bitmap, deg: Double) = px(b, cx + 105 * Math.sin(Math.toRadians(deg)), cy - 105 * Math.cos(Math.toRadians(deg)))
    val m = StepCardRenderer.render(1098, StepCardData(7240, 10000, true, true, true, mock, "Today's Steps", "", "Keep moving", false), angry, paw, shoe)
    ok(isPurple(ring(m, 90.0)), "72% ring is filled at 3 o'clock")
    ok(isPurple(ring(m, 180.0)), "72% ring is filled at 6 o'clock")
    ok(isTrack(ring(m, 315.0)), "72% ring leaves 10:30 o'clock unfilled")
    val g = StepCardRenderer.render(1098, StepCardData(10450, 10000, true, true, true, full, "Today's Steps", "", "Goal reached!", true), happy, paw, shoe)
    ok(isPurple(ring(g, 315.0)), "100% ring is filled all the way round")
    val o = StepCardRenderer.render(1098, StepCardData(0, 10000, false, false, false, IntArray(54), "Step counter is off", "hint", "Every step counts", false), angry, paw, shoe)
    ok(isTrack(ring(o, 90.0)) && isTrack(ring(o, 315.0)), "no progress is drawn when tracking is off")
    val lit = px(m, 827.5, 617.4)                                          // cell index 25 = level 4
    ok(Math.abs(lit[0] - 201) < 25 && Math.abs(lit[1] - 141) < 25 && lit[2] > 220, "goal-reached day is the bright purple")
    val dark = px(m, 827.5 - 96.5, 617.4)                                  // neighbour without data
    ok(dark[2] < 70, "a day with no data stays dark")
    val offCell = px(o, 827.5, 617.4)
    ok(offCell[2] < 70, "heatmap off: no lit cells")
    println("RENDER CHECKS PASSED: $checks")
    // 6) small widget size
    save("card_small", 420, StepCardData(7240, 10000, true, true, true, mock, "Today's Steps", "", "Keep moving", false), angry)
}
