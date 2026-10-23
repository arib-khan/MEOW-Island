package com.smartisland.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Draws the Dancing Cat widget's progress bar (rounded track, violet-to-pink fill, round white thumb) into one bitmap, because
 * RemoteViews cannot draw a thumb. Uses only drawRoundRect / LinearGradient so the same code renders on the desktop shim
 * (tests/shim) for pixel checks. The bitmap is made at the widget's real pixel width so nothing is stretched.
 */
object MusicBarRenderer {
    const val HEIGHT_DP = 14f
    private const val TRACK = 0xFF2E3040.toInt()
    private const val FILL_A = 0xFF7C4DFF.toInt()
    private const val FILL_B = 0xFFD070F2.toInt()
    private const val RING = 0xFF9B6BFF.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Thumb centre for [fraction] 0..1 inside a bar [w] px wide whose thumb radius is [r] px (the thumb never leaves the bar). */
    fun thumbX(fraction: Float, w: Int, r: Float): Float = r + (w - 2 * r) * fraction.coerceIn(0f, 1f)

    fun render(widthPx: Int, density: Float, fraction: Float): Bitmap {
        val w = widthPx.coerceAtLeast(40); val h = Math.round(HEIGHT_DP * density).coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = h / 2f                                   // thumb radius = half the bar height
        val trackH = 5f * density; val cy = h / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = TRACK
        c.drawRoundRect(RectF(r, cy - trackH / 2, w - r, cy + trackH / 2), trackH / 2, trackH / 2, p)
        val tx = thumbX(fraction, w, r)
        if (tx > r + 0.5f) {
            val f = Paint(Paint.ANTI_ALIAS_FLAG)
            f.shader = LinearGradient(r, 0f, w - r, 0f, intArrayOf(FILL_A, FILL_B), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(r, cy - trackH / 2, tx, cy + trackH / 2), trackH / 2, trackH / 2, f)
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG); ring.color = RING
        c.drawRoundRect(RectF(tx - r, cy - r, tx + r, cy + r), r, r, ring)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG); dot.color = WHITE
        val ri = r - 2f * density
        c.drawRoundRect(RectF(tx - ri, cy - ri, tx + ri, cy + ri), ri, ri, dot)
        return bmp
    }
}
