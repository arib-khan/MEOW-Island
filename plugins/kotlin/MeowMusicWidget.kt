package com.smartisland.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.BitmapShader
import android.graphics.Shader
import android.media.AudioManager
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationManagerCompat

/** Dancing Cat music widget. All state comes from the existing MediaListener (media sessions); this adds no service and no audio access. */
class MeowMusicWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        val v = MeowMusic.build(ctx, MeowMusic.state(ctx.applicationContext))
        for (id in ids) m.updateAppWidget(id, v)
    }
    override fun onDisabled(ctx: Context) { MeowMusic.forget() }   // last widget removed: nothing left to keep in sync
}

/** Receives the widget's button taps (not exported: only this app's PendingIntents can reach it). */
class MeowMusicActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) { try { MeowMusic.act(ctx.applicationContext, i.action ?: return) } catch (e: Exception) { } }
}

object MeowMusic {
    const val PREV = "com.smartisland.app.MUSIC_PREV"
    const val PLAY = "com.smartisland.app.MUSIC_PLAY"
    const val NEXT = "com.smartisland.app.MUSIC_NEXT"
    @Volatile private var lastSig = ""

    private fun rid(ctx: Context, type: String, name: String) = ctx.resources.getIdentifier(name, type, ctx.packageName)
    fun forget() { lastSig = "" }

    class MusicState(val view: MusicView, val playing: Boolean, val canPrev: Boolean, val canNext: Boolean, val art: String?)

    private fun hasAccess(ctx: Context) = try { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) } catch (e: Exception) { false }

    fun state(ctx: Context): MusicState {
        val s = MediaListener.snapshot
        val active = s["active"] == true
        val playing = s["playing"] == true
        val art = (s["art"] as? String)?.takeIf { it.isNotEmpty() }
        val dancing = Cfg.read(ctx).optBoolean("dancingCat", true)
        val v = MusicWidgetLogic.view(hasAccess(ctx), active, playing, s["title"] as? String, s["artist"] as? String, art != null, dancing, art?.hashCode() ?: 0)
        return MusicState(v, playing, s["canPrev"] == true, s["canNext"] == true, art)
    }

    private fun circle(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = BitmapShader(Bitmap.createScaledBitmap(src, size, size, true), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p)
        return out
    }

    private fun btn(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, code, Intent(ctx, MeowMusicActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Two layouts on purpose: the "play" layout holds the frame animators, the "idle" layout has none, so nothing animates or wakes when music is not playing. */
    fun build(ctx: Context, s: MusicState): RemoteViews {
        val vw = s.view
        val v = RemoteViews(ctx.packageName, rid(ctx, "layout", if (vw.animate) "meow_widget_music_play" else "meow_widget_music_idle"))
        v.setTextViewText(rid(ctx, "id", "meow_title"), vw.title)
        v.setTextViewText(rid(ctx, "id", "meow_artist"), vw.artist)
        val play = rid(ctx, "id", "meow_play")
        v.setImageViewResource(play, rid(ctx, "drawable", if (s.playing) "ic_meow_pause" else "ic_meow_play"))
        v.setOnClickPendingIntent(rid(ctx, "id", "meow_prev"), btn(ctx, PREV, 11))
        v.setOnClickPendingIntent(play, btn(ctx, PLAY, 12))
        v.setOnClickPendingIntent(rid(ctx, "id", "meow_next"), btn(ctx, NEXT, 13))
        val withSession = vw.mode == MusicMode.PLAYING || vw.mode == MusicMode.PAUSED
        v.setInt(rid(ctx, "id", "meow_prev"), "setImageAlpha", if (withSession && !s.canPrev) 90 else 255)    // unsupported controls look disabled
        v.setInt(rid(ctx, "id", "meow_next"), "setImageAlpha", if (withSession && !s.canNext) 90 else 255)
        v.setOnClickPendingIntent(rid(ctx, "id", "meow_root"),
            PendingIntent.getActivity(ctx, 14, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val art = rid(ctx, "id", "meow_art")
        var shown = false
        if (vw.showArt && s.art != null) {
            try {
                val raw = Base64.decode(s.art, Base64.DEFAULT)
                val bm = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bm != null) { v.setImageViewBitmap(art, circle(bm, 96)); shown = true }
            } catch (e: Exception) { }
        }
        v.setViewVisibility(art, if (shown) View.VISIBLE else View.GONE)   // missing artwork simply hides the slot
        return v
    }

    /** Called by MediaListener on track / playback changes and by the settings bridge. Does nothing if no widget is on the home screen. */
    fun refresh(ctx: Context, force: Boolean = false) {
        try {
            val app = ctx.applicationContext
            val m = AppWidgetManager.getInstance(app)
            val ids = m.getAppWidgetIds(ComponentName(app, MeowMusicWidget::class.java))
            if (ids.isEmpty()) { lastSig = ""; return }
            val st = state(app)
            if (!force && st.view.sig == lastSig) return          // identical picture: no redraw, no battery
            lastSig = st.view.sig
            val v = build(app, st)
            for (id in ids) m.updateAppWidget(id, v)
        } catch (e: Exception) { }
    }

    private fun key(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Buttons drive the REAL media session. Without notification access there is no controller, so a media key is sent to whatever player is active. */
    fun act(ctx: Context, action: String) {
        val t = MediaListener.controller?.transportControls
        val playing = MediaListener.snapshot["playing"] == true
        when (action) {
            PREV -> if (t != null) t.skipToPrevious() else key(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            NEXT -> if (t != null) t.skipToNext() else key(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
            PLAY -> if (t != null) { if (playing) t.pause() else t.play() } else key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        // the widget redraws when the player reports its new state (MediaListener -> refresh); no timers needed here
    }
}
