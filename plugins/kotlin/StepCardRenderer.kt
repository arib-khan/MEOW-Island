package com.smartisland.app

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface

/** Everything the "Step goals" card shows. Real values only: the caller decides which parts are available. */
class StepCardData(
    val steps: Long, val goal: Int,
    val hasSteps: Boolean,      // a real reading exists for today
    val goalOn: Boolean,        // "Daily step goal" enabled: show goal, % and progress
    val heatOn: Boolean,        // "Contribution heatmap" enabled: show the day grid
    val levels: IntArray,       // CELLS entries, oldest first, last = today; DayRec.level values (0 = no data)
    val label: String,          // small caption above the number
    val hint: String,           // shown instead of the progress bar when there is nothing to measure
    val subtitle: String,       // line under the title
    val happy: Boolean          // happy cat (goal reached) or angry cat (keep moving)
)

/**
 * Draws the whole "Step goals" card into one bitmap. All coordinates are in a 1098 x 1432 design space and scaled to the
 * requested width. Pure android.graphics (no Context), so it can also run in the JVM test harness in tests/shim.
 */
object StepCardRenderer {
    const val RW = 1098f
    const val RH = 1432f
    const val CELLS = 54          // 9 columns x 6 rows, oldest first, bottom-right = today

    fun pct(steps: Long, goal: Int): Int = if (goal <= 0) 0 else minOf(100L, steps * 100 / goal).toInt()

    private fun cellColor(level: Int): Int = when (level) {
        4 -> 0xFFC98DFB.toInt(); 3 -> 0xFF8F58E8.toInt(); 2 -> 0xFF4A3685.toInt(); 1 -> 0xFF2C2638.toInt(); else -> 0xFF27222F.toInt()
    }

    fun render(widthPx: Int, d: StepCardData, cat: Bitmap?, paw: Bitmap?, shoe: Bitmap?): Bitmap {
        val k = widthPx / RW
        val bmp = Bitmap.createBitmap(widthPx, maxOf(1, (RH * k).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.isFilterBitmap = true
        fun u(v: Float) = v * k
        fun rr(l: Float, t: Float, r: Float, b: Float) = RectF(u(l), u(t), u(r), u(b))
        fun fill(rect: RectF, radius: Float, color: Int) { p.style = Paint.Style.FILL; p.color = color; c.drawRoundRect(rect, u(radius), u(radius), p) }
        fun line(rect: RectF, radius: Float, width: Float, color: Int) {
            p.style = Paint.Style.STROKE; p.strokeWidth = u(width); p.color = color; c.drawRoundRect(rect, u(radius), u(radius), p)
        }
        fun glow(rect: RectF, radius: Float, color: Int, blur: Float, strokeW: Float) {
            p.style = if (strokeW > 0f) Paint.Style.STROKE else Paint.Style.FILL
            p.strokeWidth = u(strokeW); p.color = color
            p.maskFilter = BlurMaskFilter(maxOf(1f, u(blur)), BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(rect, u(radius), u(radius), p)
            p.maskFilter = null
        }
        fun tint(bm: Bitmap?, dst: RectF, color: Int) {
            if (bm == null) return
            p.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            c.drawBitmap(bm, null, dst, p)
            p.colorFilter = null
        }

        // ---- card: outer glow, dark gradient fill, purple gradient border ----
        val card = rr(45f, 70f, 1055f, 1370f)
        glow(card, 150f, 0x778B5CF6, 22f, 12f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, card.top, 0f, card.bottom, 0xFF150F26.toInt(), 0xFF0B0813.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(card, u(150f), u(150f), p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = u(4f)
        p.shader = LinearGradient(card.left, card.top, card.right, card.bottom,
            intArrayOf(0xFF8A5CFF.toInt(), 0xFF4E2AB8.toInt(), 0xFF9B6BFF.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(card, u(150f), u(150f), p)
        p.shader = null

        // ---- title + subtitle ----
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        // fit to the free space left of the cat, whatever typeface the phone uses
        tp.textSize = u(80f)
        val titleW = tp.measureText("Step goals") / k
        val titleSize = if (titleW > 372f) 80f * 372f / titleW else 80f
        tp.textSize = u(titleSize); tp.color = 0xFFFFFFFF.toInt()
        c.drawText("Step ", u(112f), u(218f), tp)
        val gx = u(112f) + tp.measureText("Step ")
        tp.shader = LinearGradient(gx, 0f, gx + tp.measureText("goals"), 0f, 0xFFC9A0FF.toInt(), 0xFFF28CF0.toInt(), Shader.TileMode.CLAMP)
        c.drawText("goals", gx, u(218f), tp)
        tp.shader = null
        tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        tp.textSize = u(40f); tp.color = 0xFF7F72A6.toInt()
        val subW = tp.measureText(d.subtitle) / k
        val subSize = if (subW > 300f) 40f * 300f / subW else 40f
        tp.textSize = u(subSize)
        c.drawText(d.subtitle, u(112f), u(286f), tp)
        val subEnd = 112f + minOf(subW, 300f)
        tint(paw, rr(subEnd + 18f, 252f, subEnd + 58f, 292f), 0xFF7F72A6.toInt())

        // ---- grid panel (the cat rests on its top edge) ----
        val panel = rr(75f, 322f, 1040f, 990f)
        fill(panel, 70f, 0xFF0F0A1B.toInt())
        line(panel, 70f, 2f, 0xFF2A1E4A.toInt())
        val last = CELLS - 1
        if (d.heatOn) {
            for (i in 0 until CELLS) {
                val lv = if (i < d.levels.size) d.levels[i] else 0
                val rect = rr(112f + (i % 9) * 96.5f, 383f + (i / 9) * 97.2f, 192f + (i % 9) * 96.5f, 463f + (i / 9) * 97.2f)
                if (lv >= 3 && i != last) glow(rect, 20f, (cellColor(lv) and 0x00FFFFFF) or 0x66000000, 12f, 0f)
                if (i == last) {
                    fill(rect, 20f, if (lv >= 3) cellColor(lv) else 0xFF15101F.toInt())
                    glow(rect, 22f, 0xAAFFFFFF.toInt(), 8f, 7f)
                    line(rect, 22f, 6f, 0xFFFFFFFF.toInt())
                    tint(paw, RectF(rect.centerX() - u(26f), rect.centerY() - u(26f), rect.centerX() + u(26f), rect.centerY() + u(26f)), 0xFFFFFFFF.toInt())
                } else fill(rect, 20f, cellColor(lv))
            }
        } else {
            for (i in 0 until CELLS) fill(rr(112f + (i % 9) * 96.5f, 383f + (i / 9) * 97.2f, 192f + (i % 9) * 96.5f, 463f + (i / 9) * 97.2f), 20f, 0xFF1C1726.toInt())
            tp.textAlign = Paint.Align.CENTER; tp.textSize = u(40f); tp.color = 0xFFB9ADD6.toInt()
            c.drawText("Turn on Contribution heatmap", u(557f), u(640f), tp)
            c.drawText("in Meow Island \u2192 Health", u(557f), u(696f), tp)
            tp.textAlign = Paint.Align.LEFT
        }

        // ---- cat (drawn after the panel so its paws overlap the panel edge) ----
        if (cat != null) c.drawBitmap(cat, null, rr(505f, 12f, 1020f, 362f), p)

        // ---- bottom panel ----
        val bottom = rr(75f, 995f, 1025f, 1312f)
        fill(bottom, 80f, 0xFF110C1F.toInt())
        line(bottom, 80f, 3f, 0xFF3B2C6E.toInt())

        // progress ring
        val pc = if (d.goalOn && d.hasSteps) pct(d.steps, d.goal) else 0
        val cx = u(236f); val cy = u(1153f); val rad = u(105f)
        val oval = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
        p.style = Paint.Style.STROKE; p.strokeWidth = u(28f); p.color = 0xFF2A2438.toInt()
        c.drawArc(oval, 0f, 360f, false, p)
        if (pc > 0) {
            val sweep = 360f * pc / 100f
            p.strokeCap = Paint.Cap.ROUND
            p.color = 0x886D3CF2.toInt()
            p.maskFilter = BlurMaskFilter(maxOf(1f, u(14f)), BlurMaskFilter.Blur.NORMAL)
            c.drawArc(oval, -90f, sweep, false, p)
            p.maskFilter = null
            val sg = SweepGradient(cx, cy, intArrayOf(0xFF6D3CF2.toInt(), 0xFFB05CFF.toInt(), 0xFFE27DF5.toInt(), 0xFF6D3CF2.toInt()), floatArrayOf(0f, 0.4f, 0.8f, 1f))
            val m = Matrix(); m.postRotate(-90f, cx, cy); sg.setLocalMatrix(m)
            p.shader = sg
            c.drawArc(oval, -90f, sweep, false, p)
            p.shader = null
            p.strokeCap = Paint.Cap.BUTT
        }
        tint(shoe, RectF(cx - u(46f), cy - u(46f), cx + u(46f), cy + u(46f)), 0xFFB58CFF.toInt())

        // texts
        tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        tp.textSize = u(36f); tp.color = 0xFF8A7FA8.toInt()
        c.drawText(d.label, u(398f), u(1078f), tp)
        val big = if (d.hasSteps) java.text.NumberFormat.getIntegerInstance().format(d.steps) else "\u2014"
        val goalTxt = if (d.goalOn && d.hasSteps) "/ " + java.text.NumberFormat.getIntegerInstance().format(d.goal) else ""
        val avail = 440f   // design units between the number's left edge and the % pill
        var f = 1f
        tp.typeface = Typeface.create("sans-serif", Typeface.BOLD); tp.textSize = u(84f)
        val w1 = tp.measureText(big) / k
        tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL); tp.textSize = u(42f)
        val w2 = if (goalTxt.isEmpty()) 0f else tp.measureText(goalTxt) / k
        if (w1 + 24f + w2 > avail) f = avail / (w1 + 24f + w2)
        tp.typeface = Typeface.create("sans-serif", Typeface.BOLD); tp.textSize = u(84f * f); tp.color = 0xFFFFFFFF.toInt()
        c.drawText(big, u(398f), u(1172f), tp)
        if (goalTxt.isNotEmpty()) {
            tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL); tp.textSize = u(42f * f); tp.color = 0xFF6F6590.toInt()
            c.drawText(goalTxt, u(398f + w1 * f + 24f), u(1170f), tp)
        }
        if (d.goalOn && d.hasSteps) {
            val pill = rr(845f, 1098f, 990f, 1175f)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, pill.top, 0f, pill.bottom, 0xFF5B33B0.toInt(), 0xFF3F2380.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(pill, u(38f), u(38f), p); p.shader = null
            tp.typeface = Typeface.create("sans-serif", Typeface.BOLD); tp.textSize = u(38f); tp.color = 0xFFFFFFFF.toInt(); tp.textAlign = Paint.Align.CENTER
            c.drawText("$pc%", pill.centerX(), pill.centerY() + u(13f), tp)
            tp.textAlign = Paint.Align.LEFT
            // linear progress
            val track = rr(398f, 1215f, 962f, 1252f)
            fill(track, 18f, 0xFF25202F.toInt())
            val fw = 564f * pc / 100f
            if (fw > 0f) {
                val fr = rr(398f, 1215f, 398f + maxOf(fw, 30f), 1252f)
                p.style = Paint.Style.FILL
                p.shader = LinearGradient(fr.left, 0f, fr.right, 0f, 0xFF7B45F5.toInt(), 0xFFE88BF0.toInt(), Shader.TileMode.CLAMP)
                c.drawRoundRect(fr, u(18f), u(18f), p); p.shader = null
            }
        } else {
            tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL); tp.textSize = u(32f); tp.color = 0xFF8A7FA8.toInt()
            c.drawText(d.hint, u(398f), u(1238f), tp)
        }
        return bmp
    }
}
