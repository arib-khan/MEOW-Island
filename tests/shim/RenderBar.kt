import android.graphics.Bitmap
import com.smartisland.app.MusicBarRenderer
import java.io.File
import javax.imageio.ImageIO

fun rgb(b: Bitmap, x: Int, y: Int): Int = b.img.getRGB(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
fun ch(c: Int, s: Int) = (c shr s) and 255
fun main(args: Array<String>) {
    val out = args[0]; val dens = 3f; val w = 900
    var n = 0
    fun ok(c: Boolean, m: String) { if (!c) throw AssertionError("BAR RENDER CHECK FAILED: $m"); n++ }
    for ((name, f) in listOf("0" to 0f, "32" to 0.32f, "100" to 1f)) {
        val b = MusicBarRenderer.render(w, dens, f); ImageIO.write(b.img, "png", File("$out/bar_$name.png"))
        ok(b.width == w && b.height == 42, "size $name")
        val cy = b.height / 2; val tx = MusicBarRenderer.thumbX(f, w, b.height / 2f).toInt()
        val thumb = rgb(b, tx, cy); ok(ch(thumb, 16) > 240 && ch(thumb, 8) > 240 && ch(thumb, 0) > 240, "white thumb centre at $name")
        ok(ch(rgb(b, tx, 1), 24) > 0, "thumb ring reaches the top edge at $name")
        if (f in 0.01f..0.99f) {
            val fill = rgb(b, tx / 2, cy); ok(ch(fill, 0) > 200 && ch(fill, 24) > 200, "fill is bright violet left of the thumb")
            val track = rgb(b, (tx + w) / 2, cy); ok(ch(track, 16) < 70 && ch(track, 0) < 90 && ch(track, 24) > 200, "dark track right of the thumb")
        }
        ok(MusicBarRenderer.thumbX(f, w, 21f) in 21f..(w - 21f), "thumb stays inside the bar at $name")
    }
    println("bar render checks: $n passed")
}
