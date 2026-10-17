package com.smartisland.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.text.NumberFormat
import java.util.Calendar
import kotlin.math.sqrt

/** Home-screen widgets. The clocks tick by themselves (TextClock); the heatmap is drawn from the local step ledger. */
class MeowClockWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) { for (id in ids) m.updateAppWidget(id, MeowWidgets.clock(ctx)) }
}

class MeowRoundClockWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) { for (id in ids) m.updateAppWidget(id, MeowWidgets.round(ctx)) }
}

class MeowHeatWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) { for (id in ids) m.updateAppWidget(id, MeowWidgets.heat(ctx, m, id)) }
    override fun onAppWidgetOptionsChanged(ctx: Context, m: AppWidgetManager, id: Int, newOptions: Bundle) {
        m.updateAppWidget(id, MeowWidgets.heat(ctx, m, id))   // redraw for the new size
    }
}

object MeowWidgets {
    private fun rid(ctx: Context, type: String, name: String) = ctx.resources.getIdentifier(name, type, ctx.packageName)

    private fun open(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 7, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Only shown if the user turned the step counter on; adding a widget never enables tracking. */
    private fun stepsLine(ctx: Context): String {
        if (!HealthEngine.cfg(ctx).optBoolean("stepCounter")) return ""
        val s = HealthEngine.todaySteps(ctx) ?: return ""
        return NumberFormat.getIntegerInstance().format(s) + " steps"
    }

    private fun setExtra(ctx: Context, v: RemoteViews, text: String) {
        val id = rid(ctx, "id", "meow_extra")
        v.setTextViewText(id, text)
        v.setViewVisibility(id, if (text.isEmpty()) View.GONE else View.VISIBLE)
    }

    fun clock(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, rid(ctx, "layout", "meow_widget_clock"))
        v.setOnClickPendingIntent(rid(ctx, "id", "meow_root"), open(ctx))
        setExtra(ctx, v, stepsLine(ctx))
        return v
    }

    fun round(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, rid(ctx, "layout", "meow_widget_round"))
        v.setOnClickPendingIntent(rid(ctx, "id", "meow_root"), open(ctx))
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))   // sticky read, nothing is registered
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        setExtra(ctx, v, if (level >= 0 && scale > 0) "\uD83D\uDD0B " + (level * 100 / scale) + "%" else "")
        return v
    }

    fun heat(ctx: Context, m: AppWidgetManager, id: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, rid(ctx, "layout", "meow_widget_heat"))
        v.setOnClickPendingIntent(rid(ctx, "id", "meow_root"), open(ctx))
        val grid = rid(ctx, "id", "meow_grid"); val msg = rid(ctx, "id", "meow_msg")
        if (!HealthEngine.cfg(ctx).optBoolean("heatmap")) {
            v.setViewVisibility(grid, View.GONE); v.setViewVisibility(msg, View.VISIBLE)
            v.setTextViewText(msg, "Turn on Contribution heatmap in Meow Island \u2192 Health to fill this widget.")
        } else {
            val o = m.getAppWidgetOptions(id)
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180).coerceAtLeast(80)
            val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140).coerceAtLeast(60)
            v.setViewVisibility(msg, View.GONE); v.setViewVisibility(grid, View.VISIBLE)
            v.setImageViewBitmap(grid, renderHeat(ctx, (wDp - 24).toFloat(), (hDp - 24 - 18).toFloat()))
        }
        return v
    }

    /** Draws the grid into a bitmap kept under ~240k pixels (RemoteViews bitmaps cross a size-limited binder call). */
    private fun renderHeat(ctx: Context, wDp: Float, hDp: Float): Bitmap {
        var s = ctx.resources.displayMetrics.density
        val area = wDp * s * hDp * s
        if (area > 240000f) s *= sqrt(240000f / area)
        val bw = maxOf(1, (wDp * s).toInt()); val bh = maxOf(1, (hDp * s).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val gap = 3f * s
        val cell = ((bh - 6 * gap) / 7f).coerceIn(5f * s, 22f * s)
        val cols = ((bw + gap) / (cell + gap)).toInt().coerceIn(4, 20)
        val g = HealthEngine.gridLevels(ctx, cols)
        val ox = (bw - (cols * (cell + gap) - gap)) / 2f
        val oy = (bh - (7 * (cell + gap) - gap)) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val todayRow = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
        for (w in 0 until cols) for (r in 0 until 7) {
            val lv = g[w][r]
            if (lv < 0) continue
            p.style = Paint.Style.FILL
            p.color = when (lv) { 4 -> 0xFFB79CFF.toInt(); 3 -> 0xFF6C55B0.toInt(); 2 -> 0xFF3A2F5C.toInt(); 1 -> 0xFF2A2238.toInt(); else -> 0x22FFFFFF }
            val l = ox + w * (cell + gap); val t = oy + r * (cell + gap)
            val rect = RectF(l, t, l + cell, t + cell)
            c.drawRoundRect(rect, cell * 0.25f, cell * 0.25f, p)
            if (w == cols - 1 && r == todayRow) {           // ring marks today
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * s; p.color = 0xFFE4D8FF.toInt()
                c.drawRoundRect(rect, cell * 0.25f, cell * 0.25f, p)
            }
        }
        return bmp
    }

    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        val m = AppWidgetManager.getInstance(app)
        for (id in m.getAppWidgetIds(ComponentName(app, MeowClockWidget::class.java))) m.updateAppWidget(id, clock(app))
        for (id in m.getAppWidgetIds(ComponentName(app, MeowRoundClockWidget::class.java))) m.updateAppWidget(id, round(app))
        for (id in m.getAppWidgetIds(ComponentName(app, MeowHeatWidget::class.java))) m.updateAppWidget(id, heat(app, m, id))
    }
}
