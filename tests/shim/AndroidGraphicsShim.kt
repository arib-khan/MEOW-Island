// Minimal Java2D stand-in for the android.graphics classes used by StepCardRenderer, so the REAL renderer code can run on a
// desktop JVM and its output can be inspected. Visual details (fonts, blur) are approximations of Android's.
package android.graphics

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.PaintContext
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.Raster

class Rect
class RectF(@JvmField var left: Float, @JvmField var top: Float, @JvmField var right: Float, @JvmField var bottom: Float) {
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
}

class Bitmap(val img: BufferedImage) {
    enum class Config { ARGB_8888 }
    val width: Int get() = img.width
    val height: Int get() = img.height
    companion object { @JvmStatic fun createBitmap(w: Int, h: Int, cfg: Config): Bitmap = Bitmap(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)) }
}

class Matrix { var rot = 0f; fun postRotate(deg: Float, px: Float, py: Float): Boolean { rot += deg; return true } }

open class Shader {
    enum class TileMode { CLAMP, REPEAT, MIRROR }
    var local: Matrix? = null
    fun setLocalMatrix(m: Matrix) { local = m }
    open fun awt(): java.awt.Paint = Color.BLACK
}

class LinearGradient(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val colors: IntArray, val pos: FloatArray?, val tile: TileMode) : Shader() {
    constructor(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int, tile: TileMode) : this(x0, y0, x1, y1, intArrayOf(c0, c1), null, tile)
    override fun awt(): java.awt.Paint {
        if (x0 == x1 && y0 == y1) return Color(colors[0], true)
        val fr = pos ?: FloatArray(colors.size) { it / (colors.size - 1f) }
        return LinearGradientPaint(Point2D.Float(x0, y0), Point2D.Float(x1, y1), fr, Array(colors.size) { Color(colors[it], true) }, MultipleGradientPaint.CycleMethod.NO_CYCLE)
    }
}

class SweepGradient(val cx: Float, val cy: Float, val colors: IntArray, val pos: FloatArray?) : Shader() {
    private fun colorAt(t: Double): Int {
        val fr = pos ?: FloatArray(colors.size) { it / (colors.size - 1f) }
        for (i in 1 until colors.size) if (t <= fr[i]) {
            val a = colors[i - 1]; val b = colors[i]; val f = ((t - fr[i - 1]) / (fr[i] - fr[i - 1])).coerceIn(0.0, 1.0)
            fun ch(s: Int) = (((a ushr s) and 255) * (1 - f) + ((b ushr s) and 255) * f).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return colors.last()
    }
    override fun awt(): java.awt.Paint {
        val rot = local?.rot ?: 0f
        return object : java.awt.Paint {
            override fun getTransparency() = java.awt.Transparency.TRANSLUCENT
            override fun createContext(cm: ColorModel?, db: Rectangle?, ub: Rectangle2D?, xf: AffineTransform?, h: RenderingHints?): PaintContext {
                val inv = try { xf?.createInverse() } catch (e: Exception) { null }
                return object : PaintContext {
                    override fun dispose() {}
                    override fun getColorModel(): ColorModel = ColorModel.getRGBdefault()
                    override fun getRaster(x: Int, y: Int, w: Int, h: Int): Raster {
                        val r = ColorModel.getRGBdefault().createCompatibleWritableRaster(w, h)
                        val px = IntArray(4); val pt = Point2D.Double()
                        for (j in 0 until h) for (i in 0 until w) {
                            pt.setLocation(x + i + 0.5, y + j + 0.5); inv?.transform(pt, pt)
                            var a = Math.toDegrees(Math.atan2(pt.y - cy, pt.x - cx)) - rot   // clockwise from 3 o'clock, shader rotated by 'rot'
                            a = ((a % 360.0) + 360.0) % 360.0
                            val col = colorAt(a / 360.0)
                            px[0] = (col shr 16) and 255; px[1] = (col shr 8) and 255; px[2] = col and 255; px[3] = (col ushr 24) and 255
                            r.setPixel(i, j, px)
                        }
                        return r
                    }
                }
            }
        }
    }
}

open class MaskFilter
class BlurMaskFilter(val radius: Float, val style: Blur) : MaskFilter() { enum class Blur { NORMAL } }
open class ColorFilter
class PorterDuff { enum class Mode { SRC_IN } }
class PorterDuffColorFilter(val color: Int, val mode: PorterDuff.Mode) : ColorFilter()
class Typeface(val bold: Boolean) {
    companion object { const val NORMAL = 0; const val BOLD = 1; @JvmStatic fun create(family: String, style: Int) = Typeface(style == BOLD) }
}

class Paint(flags: Int = 0) {
    companion object { const val ANTI_ALIAS_FLAG = 1; const val FILTER_BITMAP_FLAG = 2 }
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Align { LEFT, CENTER, RIGHT }
    var color = 0xFF000000.toInt()
    var style = Style.FILL
    var strokeWidth = 0f
    var shader: Shader? = null
    var maskFilter: MaskFilter? = null
    var colorFilter: ColorFilter? = null
    var typeface: Typeface? = null
    var textSize = 12f
    var strokeCap = Cap.BUTT
    var textAlign = Align.LEFT
    var isAntiAlias = true
    var isFilterBitmap = false
    fun font(): Font = Font(System.getProperty("shim.font", "Poppins"), if (typeface?.bold == true) Font.BOLD else Font.PLAIN, 12).deriveFont(textSize)
    fun measureText(s: String): Float = font().getStringBounds(s, FontRenderContext(null, true, true)).width.toFloat()
}

class Canvas(val bmp: Bitmap) {
    private fun g(img: BufferedImage): Graphics2D {
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        return g
    }
    private fun setup(g: Graphics2D, p: Paint) {
        g.paint = p.shader?.awt() ?: Color(p.color, true)
        val cap = when (p.strokeCap) { Paint.Cap.ROUND -> BasicStroke.CAP_ROUND; Paint.Cap.SQUARE -> BasicStroke.CAP_SQUARE; else -> BasicStroke.CAP_BUTT }
        g.stroke = BasicStroke(maxOf(0.0001f, p.strokeWidth), cap, BasicStroke.JOIN_ROUND)
    }
    private fun blurRegion(img: BufferedImage, r: Rectangle, radius: Float) {
        val sigma = 0.57735 * radius + 0.5; val br = maxOf(1, (sigma * 1.5).toInt())
        val x0 = maxOf(0, r.x); val y0 = maxOf(0, r.y); val x1 = minOf(img.width, r.x + r.width); val y1 = minOf(img.height, r.y + r.height)
        val w = x1 - x0; val h = y1 - y0; if (w <= 0 || h <= 0) return
        val px = img.getRGB(x0, y0, w, h, null, 0, w)
        val ch = Array(4) { s -> FloatArray(w * h) { ((px[it] ushr (s * 8)) and 255).toFloat() } }
        for (s in 0 until 4) repeat(3) {
            for (pass in 0..1) {
                val src = ch[s]; val dst = FloatArray(w * h)
                val len = if (pass == 0) w else h; val lines = if (pass == 0) h else w
                for (l in 0 until lines) {
                    var sum = 0f
                    fun at(i: Int) = if (i < 0 || i >= len) 0f else src[if (pass == 0) l * w + i else i * w + l]
                    for (i in -br..br) sum += at(i)
                    for (i in 0 until len) { dst[if (pass == 0) l * w + i else i * w + l] = sum / (2 * br + 1); sum += at(i + br + 1) - at(i - br) }
                }
                ch[s] = dst
            }
        }
        for (i in px.indices) px[i] = (ch[3][i].toInt().coerceIn(0, 255) shl 24) or (ch[2][i].toInt().coerceIn(0, 255) shl 16) or (ch[1][i].toInt().coerceIn(0, 255) shl 8) or ch[0][i].toInt().coerceIn(0, 255)
        img.setRGB(x0, y0, w, h, px, 0, w)
    }
    private fun draw(p: Paint, bounds: Rectangle, op: (Graphics2D) -> Unit) {
        val bl = p.maskFilter as? BlurMaskFilter
        if (bl == null) { val g = g(bmp.img); setup(g, p); op(g); g.dispose(); return }
        val m = (bl.radius * 4 + p.strokeWidth).toInt() + 4
        val region = Rectangle(bounds.x - m, bounds.y - m, bounds.width + 2 * m, bounds.height + 2 * m)
        val tmp = BufferedImage(bmp.width, bmp.height, BufferedImage.TYPE_INT_ARGB)
        val gg = g(tmp); setup(gg, p); op(gg); gg.dispose()
        blurRegion(tmp, region, bl.radius)
        val g2 = g(bmp.img); g2.drawImage(tmp, 0, 0, null); g2.dispose()
    }
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) {
        val s = RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), rx * 2, ry * 2)
        draw(p, s.bounds) { g -> if (p.style == Paint.Style.STROKE) g.draw(s) else g.fill(s) }
    }
    fun drawArc(o: RectF, start: Float, sweep: Float, useCenter: Boolean, p: Paint) {
        val s = Arc2D.Float(o.left, o.top, o.width(), o.height(), -start, -sweep, Arc2D.OPEN)
        draw(p, s.bounds) { g -> g.draw(s) }
    }
    fun drawText(t: String, x: Float, y: Float, p: Paint) {
        val w = if (p.textAlign == Paint.Align.CENTER) p.measureText(t) / 2f else 0f
        draw(p, Rectangle(x.toInt(), (y - p.textSize).toInt(), p.measureText(t).toInt() + 1, (p.textSize * 1.3f).toInt())) { g -> g.font = p.font(); g.drawString(t, x - w, y) }
    }
    private fun tinted(src: BufferedImage, color: Int): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        for (yy in 0 until src.height) for (xx in 0 until src.width) {
            val a = (src.getRGB(xx, yy) ushr 24) * ((color ushr 24) / 255f)
            out.setRGB(xx, yy, (a.toInt() shl 24) or (color and 0x00FFFFFF))
        }
        return out
    }
    fun drawBitmap(b: Bitmap, src: Rect?, dst: RectF, p: Paint?) {
        val img = (p?.colorFilter as? PorterDuffColorFilter)?.let { tinted(b.img, it.color) } ?: b.img
        val g = g(bmp.img); g.drawImage(img, dst.left.toInt(), dst.top.toInt(), dst.width().toInt(), dst.height().toInt(), null); g.dispose()
    }
}
