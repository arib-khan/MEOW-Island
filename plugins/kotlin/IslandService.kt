package com.smartisland.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/** Round vinyl/CD view: circular artwork, grooves, label ring and centre hole. */
class CDView(ctx: Context) : View(ctx) {
    var art: Bitmap? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = minOf(w, h) / 2f; val cx = w / 2f; val cy = h / 2f
        val b = art
        p.style = Paint.Style.FILL
        if (b != null) {
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix(); m.setScale(w / b.width, h / b.height); sh.setLocalMatrix(m)
            p.shader = sh; c.drawCircle(cx, cy, r, p); p.shader = null
        } else { p.color = 0xFF2A2A3A.toInt(); c.drawCircle(cx, cy, r, p) }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x44000000
        c.drawCircle(cx, cy, r * 0.82f, p); c.drawCircle(cx, cy, r * 0.64f, p); c.drawCircle(cx, cy, r * 0.46f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFF111111.toInt(); c.drawCircle(cx, cy, r * 0.27f, p)
        p.color = 0xFF000000.toInt(); c.drawCircle(cx, cy, r * 0.11f, p)
    }
}

/** Two tiny cat ears sitting on top of the pill, drawn in the pill colour. */
class EarsView(ctx: Context) : View(ctx) {
    var color: Int = Color.BLACK
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = color; p.style = Paint.Style.FILL
        val ew = minOf(w * 0.14f, h * 2.6f)
        for (cx in floatArrayOf(w * 0.2f, w * 0.8f)) {
            val path = Path()
            path.moveTo(cx - ew / 2f, h + 1f); path.lineTo(cx, 0f); path.lineTo(cx + ew / 2f, h + 1f); path.close()
            c.drawPath(path, p)
        }
    }
}

class BarView(ctx: Context) : View(ctx) {
    var frac: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var fill: Int = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        p.color = 0x33FFFFFF; c.drawRoundRect(0f, 0f, w, h, r, r, p)
        p.color = fill; c.drawRoundRect(0f, 0f, w * frac, h, r, r, p)
    }
}

/** Media button drawn with paths so it stays crisp and independent of fonts. kind: 0 prev, 1 play/pause, 2 next. */
class CtlView(ctx: Context, private val kind: Int) : View(ctx) {
    var playing: Boolean = false
        set(v) { field = v; invalidate() }
    var tint: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun tri(c: Canvas, x0: Float, x1: Float, cy: Float, s: Float) {
        val path = Path(); path.moveTo(x0, cy - s); path.lineTo(x1, cy); path.lineTo(x0, cy + s); path.close(); c.drawPath(path, p)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val s = minOf(width, height) * 0.28f
        p.color = tint; p.style = Paint.Style.FILL
        when (kind) {
            1 -> if (playing) {
                c.drawRect(cx - s * 0.85f, cy - s, cx - s * 0.2f, cy + s, p)
                c.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.85f, cy + s, p)
            } else tri(c, cx - s * 0.7f, cx + s, cy, s)
            0 -> { c.drawRect(cx - s, cy - s, cx - s * 0.6f, cy + s, p); tri(c, cx + s, cx - s * 0.5f, cy, s) }
            else -> { tri(c, cx - s, cx + s * 0.5f, cy, s); c.drawRect(cx + s * 0.6f, cy - s, cx + s, cy + s, p) }
        }
    }
}

/**
 * Screen Time Guardian, executed natively so it keeps working while the React Native layer is suspended.
 * Feedback only: it never blocks an app. While the user is in an over-limit app it reminds every N minutes
 * (default 5) with usage / limit / excess, once per interval, persisted per app per local day.
 */
class Guardian {
    private var lastRun = 0L
    private var angryPkg: String? = null
    private var pulse = 0L
    private var headline = ""
    private var dayKey = ""

    fun reset() { angryPkg = null; pulse = 0L; IslandBus.remove("guard") }

    private fun today(): String {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR).toString() + "-" + c.get(java.util.Calendar.DAY_OF_YEAR)
    }

    fun tick(ctx: Context, cfg: JSONObject) {
        val now = System.currentTimeMillis()
        if (now - lastRun < 3000L) return
        lastRun = now
        if (!cfg.optBoolean("guardEnabled", true) || !Fg.available) { if (angryPkg != null) reset(); return }
        val sp = ctx.getSharedPreferences("island_guard", 0)
        val key = today()
        if (key != dayKey) {          // local-day rollover: forget yesterday's reminder timestamps
            dayKey = key
            val ed = sp.edit()
            for (k in sp.all.keys) if (!k.endsWith("|$key")) ed.remove(k)
            ed.apply()
        }
        val cur = Fg.pkg
        val limits = cfg.optJSONArray("limits")
        var overPkg: String? = null; var overName = ""; var limMin = 0
        if (cur != null && limits != null) {
            for (i in 0 until limits.length()) {
                val l = limits.optJSONObject(i) ?: continue
                if (l.optString("pkg") == cur && l.optBoolean("enabled", true)) { overPkg = cur; overName = l.optString("name", cur); limMin = l.optInt("limitMin", 30) }
            }
        }
        var usedMs = 0L
        if (overPkg != null) usedMs = UsageUtil.todayAll(ctx)[overPkg] ?: 0L
        val limMs = limMin * 60000L
        val over = overPkg != null && usedMs > limMs
        val prev = angryPkg
        if (over) {
            val pk = overPkg ?: return
            val rk = "r|$pk|$key"
            val last = sp.getLong(rk, 0L)
            val interval = maxOf(1, cfg.optInt("remindMin", 5)) * 60000L
            if (prev != pk || last == 0L || now - last >= interval) {
                if (last == 0L || now - last >= interval) {      // a reminder is due (never twice for one interval)
                    headline = if (last == 0L) "Daily limit exceeded!" else "Still over your screen-time limit!"
                    pulse = now
                    sp.edit().putLong(rk, now).apply()
                } else if (headline.isEmpty()) headline = "Still over your screen-time limit!"
            }
            angryPkg = pk
            val sub = headline + "\nUsed today: " + Fmt.min(usedMs) + "\nDaily limit: " + limMin + " min\nOver limit by: " + Fmt.min(usedMs - limMs)
            IslandBus.post(Act("guard", 80, overName, sub, emoji = cfg.optString("angry", "\uD83D\uDE3E"), expand = true, pulse = pulse))
        } else if (prev != null) {
            angryPkg = null; pulse = 0L; headline = ""
            IslandBus.remove("guard")
            // Happy emoji once per transition, only when the user actually left the monitored app.
            if (cur != prev) IslandBus.post(Act("happy", 80, "", "", emoji = cfg.optString("happy", "\uD83D\uDE3A"),
                expand = false, expireAt = now + cfg.optInt("happyMs", 3000)))
        }
    }
}

@SuppressLint("NewApi")
class IslandService : Service() {
    companion object {
        @Volatile var inst: IslandService? = null
        @Volatile var lastError: String = ""
        private const val CH = "island_fgs"
        private val VIDEO_APPS = setOf("com.google.android.youtube", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient",
            "in.startv.hotstar", "com.disney.disneyplus", "tv.twitch.android.app", "org.videolan.vlc", "com.mxtech.videoplayer.ad")
    }

    private class Geo(val w: Int, val h: Int, val top: Int, val bottom: Int)

    private val main = Handler(Looper.getMainLooper())
    private val guardian = Guardian()
    private var wm: WindowManager? = null
    private var outer: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private lateinit var ears: EarsView
    private lateinit var pill: LinearLayout
    private lateinit var cd: CDView
    private lateinit var ico: ImageView
    private lateinit var emo: TextView
    private lateinit var col: LinearLayout
    private lateinit var tT: TextView
    private lateinit var tS: TextView
    private lateinit var bar: BarView
    private lateinit var ctl: LinearLayout
    private lateinit var cPrev: CtlView
    private lateinit var cPlay: CtlView
    private lateinit var cNext: CtlView
    private var curAct: Act? = null
    private var spin: ObjectAnimator? = null
    private var sizeAnim: ValueAnimator? = null
    private var expanded = false
    private var autoCollapseAt = 0L
    private var shownSig = "?"
    private var lastId = ""
    private var lastPulse = 0L
    private var hidden = false
    private var fsCount = 0
    private var dragging = false
    private var dragOn = true

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            getSharedPreferences("island", 0).edit().putBoolean("autostart", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startAsForeground()  // must happen first, Android kills the app if it is late
            if (inst != null) { stopSelf(); return }  // never two overlays
            if (!Settings.canDrawOverlays(this)) { fail("Overlay permission is not granted"); return }
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            buildView()
            inst = this
            lastError = ""
            IslandModule.emit("islandState", mapOf("running" to true, "error" to ""))
            main.post(tick)
        } catch (e: Exception) { fail(e.message ?: e.javaClass.simpleName) }
    }

    private fun fail(msg: String) {
        lastError = msg
        IslandModule.emit("islandState", mapOf("running" to false, "error" to msg))
        stopSelf()
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Meow Island service", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, IslandService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val icon = resources.getIdentifier("ic_meow", "drawable", packageName).let { if (it != 0) it else android.R.drawable.ic_dialog_info }
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(icon)
            .setContentTitle("Meow Island is running")
            .setContentText("Tap to open settings")
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- geometry ----------
    private fun statusBarH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun navBarH(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(48)
    }

    private fun cutoutTop(): Int {
        if (Build.VERSION.SDK_INT < 28) return 0
        return outer?.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
    }

    private fun earsH(): Int = if (::ears.isInitialized && ears.visibility == View.VISIBLE) dp(6) else 0
    private fun baseH(c: JSONObject): Int = dp(c.optInt("h", 34)) + earsH()

    /** Real screen size plus top/bottom keep-out bands (status bar, cutout, navigation bar) for the "usable area" mode. */
    @Suppress("DEPRECATION")
    private fun geo(c: JSONObject): Geo {
        var sw = resources.displayMetrics.widthPixels
        var sh = resources.displayMetrics.heightPixels
        val w = wm
        if (w != null) {
            if (Build.VERSION.SDK_INT >= 30) { val b = w.currentWindowMetrics.bounds; sw = b.width(); sh = b.height() }
            else { val dm = DisplayMetrics(); w.defaultDisplay.getRealMetrics(dm); sw = dm.widthPixels; sh = dm.heightPixels }
        }
        if (c.optString("posArea", "usable") == "screen") return Geo(sw, sh, 0, 0)
        val top = maxOf(statusBarH(), cutoutTop())
        val mode = try { Settings.Secure.getInt(contentResolver, "navigation_mode", 0) } catch (e: Exception) { 0 }
        val nav = if (mode == 2) dp(16) else navBarH()   // 2 = gesture navigation: only a small safety band
        return Geo(sw, sh, top, nav)
    }

    /** fx/fy are fractions (0..1) of the allowed range, so the position survives rotation and size changes. */
    private fun place(tw: Int, th: Int, c: JSONObject): IntArray {
        val g = geo(c)
        val margin = dp(8)
        val fx = c.optDouble("fx", 0.5).coerceIn(0.0, 1.0)
        val fy = c.optDouble("fy", 0.0).coerceIn(0.0, 1.0)
        val cx = margin + fx * (g.w - 2 * margin)
        val x = (cx - tw / 2.0).toInt().coerceIn(0, maxOf(0, g.w - tw))
        val usable = maxOf(0, g.h - g.top - g.bottom - baseH(c))
        val y = (g.top + fy * usable).toInt().coerceIn(g.top, maxOf(g.top, g.h - g.bottom - th))
        return intArrayOf(x, y)
    }

    private fun moveTo(x: Int, y: Int) {
        val l = lp ?: return; val o = outer ?: return; val w = wm ?: return
        val g = geo(Cfg.read(this))
        l.x = x.coerceIn(0, maxOf(0, g.w - l.width))
        l.y = y.coerceIn(g.top, maxOf(g.top, g.h - g.bottom - l.height))
        try { w.updateViewLayout(o, l) } catch (e: Exception) { }
    }

    private fun savePosition() {
        val l = lp ?: return
        val c = Cfg.read(this); val g = geo(c)
        val margin = dp(8)
        val span = maxOf(1, g.w - 2 * margin)
        val fx = ((l.x + l.width / 2.0 - margin) / span).coerceIn(0.0, 1.0)
        val usable = maxOf(1, g.h - g.top - g.bottom - baseH(c))
        val fy = ((l.y - g.top).toDouble() / usable).coerceIn(0.0, 1.0)
        c.put("fx", fx); c.put("fy", fy)
        Cfg.write(this, c.toString())
        IslandModule.emit("position", mapOf("fx" to fx, "fy" to fy))
    }

    // ---------- view ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun buildView() {
        val c = Cfg.read(this)
        val minW = dp(c.optInt("w", 120)); val minH = dp(c.optInt("h", 34))
        val o = LinearLayout(this); o.orientation = LinearLayout.VERTICAL
        ears = EarsView(this); ears.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
        pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL; pill.gravity = Gravity.CENTER
        pill.setPadding(dp(10), dp(4), dp(10), dp(4))
        pill.clipToOutline = true; pill.outlineProvider = ViewOutlineProvider.BACKGROUND
        pill.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        cd = CDView(this); cd.layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)); cd.visibility = View.GONE
        ico = ImageView(this); ico.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)); ico.visibility = View.GONE
        emo = TextView(this); emo.textSize = 20f; emo.visibility = View.GONE; emo.setPadding(0, 0, dp(4), 0)
        col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(dp(8), 0, dp(4), 0); col.visibility = View.GONE
        tT = TextView(this); tT.textSize = 13f; tT.maxLines = 1; tT.ellipsize = TextUtils.TruncateAt.END
        tS = TextView(this); tS.textSize = 11f; tS.maxLines = 4; tS.ellipsize = TextUtils.TruncateAt.END; tS.visibility = View.GONE
        bar = BarView(this)
        bar.layoutParams = LinearLayout.LayoutParams(dp(200), dp(4)).also { it.topMargin = dp(8) }
        bar.visibility = View.GONE
        ctl = LinearLayout(this); ctl.orientation = LinearLayout.HORIZONTAL; ctl.gravity = Gravity.CENTER
        ctl.layoutParams = LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT)
        ctl.visibility = View.GONE
        cPrev = CtlView(this, 0); cPlay = CtlView(this, 1); cNext = CtlView(this, 2)
        for (v in arrayOf(cPrev, cPlay, cNext)) { v.layoutParams = LinearLayout.LayoutParams(dp(56), dp(40)); ctl.addView(v) }
        cPrev.setOnClickListener { MediaListener.controller?.transportControls?.skipToPrevious() }
        cNext.setOnClickListener { MediaListener.controller?.transportControls?.skipToNext() }
        cPlay.setOnClickListener {
            val t = MediaListener.controller?.transportControls
            if (curAct?.playing == true) t?.pause() else t?.play()
        }
        col.addView(tT); col.addView(tS); col.addView(bar); col.addView(ctl)
        pill.addView(cd); pill.addView(ico); pill.addView(emo); pill.addView(col)
        o.addView(ears); o.addView(pill)
        outer = o
        applyStyle()

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val l = WindowManager.LayoutParams(minW, minH + earsH(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        l.gravity = Gravity.TOP or Gravity.START          // x/y are plain pixels from the top-left of the screen
        if (Build.VERSION.SDK_INT >= 28) l.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) l.fitInsetsTypes = 0   // we position ourselves; do not let the system inset the window
        lp = l
        val p0 = place(l.width, l.height, c); l.x = p0[0]; l.y = p0[1]

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { setExpanded(!expanded, 0L); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) > abs(vx)) setExpanded(vy > 0, 0L)        // swipe down = expand, up = collapse
                else IslandBus.top()?.let { IslandBus.remove(it.id) } // swipe sideways = dismiss
                return true
            }
        })
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        // Only events NOT consumed by the media buttons reach this listener, so buttons never start a drag.
        o.setOnTouchListener { _, ev ->
            var handled = false
            val cur = lp
            if (cur != null) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = cur.x; startY = cur.y; dragging = false }
                    MotionEvent.ACTION_MOVE -> if (dragOn && (dragging || hypot(ev.rawX - downX, ev.rawY - downY) > slop)) {
                        dragging = true; sizeAnim?.cancel()
                        moveTo(startX + (ev.rawX - downX).toInt(), startY + (ev.rawY - downY).toInt()); handled = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; savePosition(); handled = true }
                }
            }
            if (handled) true else gd.onTouchEvent(ev)
        }
        wm!!.addView(o, l)
        render(true)
    }

    private fun applyStyle() {
        val c = Cfg.read(this)
        if (outer == null) return
        val bgc = c.optInt("bg", Color.BLACK)
        val bg = GradientDrawable()
        bg.setColor(bgc)
        bg.cornerRadius = dp(c.optInt("r", 22)).toFloat()
        pill.background = bg
        val fg = c.optInt("fg", Color.WHITE)
        tT.setTextColor(fg)
        tS.setTextColor((fg and 0x00FFFFFF) or 0xB3000000.toInt())
        bar.fill = fg
        for (v in arrayOf(cPrev, cPlay, cNext)) v.tint = fg
        ears.color = bgc
        ears.visibility = if (c.optBoolean("ears", true)) View.VISIBLE else View.GONE
        dragOn = c.optBoolean("dragEnabled", true)
    }

    private fun setSpin(on: Boolean) {
        val s = spin ?: ObjectAnimator.ofFloat(cd, View.ROTATION, 0f, 360f).also {
            it.duration = 6000L; it.repeatCount = ValueAnimator.INFINITE
            it.interpolator = android.view.animation.LinearInterpolator(); spin = it
        }
        if (on) { if (!s.isStarted) s.start() else if (s.isPaused) s.resume() }
        else if (s.isRunning) s.pause()
    }

    private fun updateSubVisibility() {
        val a = curAct
        val music = a?.id == "music"
        val big = expanded && music
        tS.visibility = if (expanded && tS.text.isNotEmpty()) View.VISIBLE else View.GONE
        bar.visibility = if (big && (a?.durMs ?: 0L) > 0L) View.VISIBLE else View.GONE
        ctl.visibility = if (big) View.VISIBLE else View.GONE
        col.visibility = if (tT.visibility == View.VISIBLE || tS.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        val mw = dp(if (big) 200 else if (expanded) 260 else 150)
        tT.maxWidth = mw; tS.maxWidth = mw
        val size = dp(if (big) 46 else 26)
        val clp = cd.layoutParams
        if (clp.width != size) { clp.width = size; clp.height = size; cd.layoutParams = clp }
    }

    private fun bind(a: Act?) {
        curAct = a
        if (a == null) {
            cd.visibility = View.GONE; ico.visibility = View.GONE; emo.visibility = View.GONE
            tT.text = ""; tT.visibility = View.GONE; tS.text = ""; setSpin(false)
            updateSubVisibility(); return
        }
        val music = a.id == "music"
        cd.visibility = if (music) View.VISIBLE else View.GONE
        if (music) cd.art = a.art
        setSpin(music && a.spinning)
        cPlay.playing = a.playing
        cPrev.isEnabled = a.canPrev; cPrev.alpha = if (a.canPrev) 1f else 0.3f
        cNext.isEnabled = a.canNext; cNext.alpha = if (a.canNext) 1f else 0.3f
        ico.visibility = if (!music && a.icon != null) View.VISIBLE else View.GONE
        if (a.icon != null) ico.setImageBitmap(a.icon)
        emo.visibility = if (a.emoji.isNotEmpty()) View.VISIBLE else View.GONE
        emo.text = a.emoji
        tT.text = a.title; tT.visibility = if (a.title.isEmpty()) View.GONE else View.VISIBLE
        tS.text = a.sub
        updateSubVisibility()
    }

    private fun render(force: Boolean) {
        if (outer == null) return
        val a = IslandBus.top()
        val sig = if (a == null) "idle" else a.id + "|" + a.title + "|" + a.sub + "|" + a.spinning + "|" + a.emoji + "|" +
            (a.art != null) + "|" + a.playing + "|" + a.canPrev + "|" + a.canNext + "|" + a.pulse
        if (sig == shownSig && !force) return
        shownSig = sig
        bind(a)
        val id = a?.id ?: ""
        val pulse = a?.pulse ?: 0L
        if (a == null) { expanded = false; autoCollapseAt = 0L }
        else if ((id != lastId || pulse != lastPulse) && a.expand) {
            expanded = true; autoCollapseAt = System.currentTimeMillis() + Cfg.read(this).optInt("expandMs", 5000)
        } else if (id != lastId) { expanded = false; autoCollapseAt = 0L }
        lastId = id; lastPulse = pulse
        updateSubVisibility()
        layoutPill(!force)
        updateProgress()
    }

    private fun setExpanded(v: Boolean, autoMs: Long) {
        expanded = v
        autoCollapseAt = if (v && autoMs > 0L) System.currentTimeMillis() + autoMs else 0L
        updateSubVisibility()
        layoutPill(true)
        updateProgress()
    }

    /** Measures the pill, then animates size AND position so the island stays anchored and on screen. */
    private fun layoutPill(animate: Boolean) {
        val o = outer ?: return; val l = lp ?: return; val w = wm ?: return
        if (dragging) return
        val c = Cfg.read(this)
        val g = geo(c)
        val minW = dp(c.optInt("w", 120)); val minH = dp(c.optInt("h", 34))
        val maxW = g.w - dp(16)
        pill.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val tw = minOf(maxOf(minW, pill.measuredWidth), maxW)
        val th = maxOf(minH, pill.measuredHeight) + earsH()
        val to = place(tw, th, c)
        val fw = l.width; val fh = l.height; val fx = l.x; val fy = l.y
        sizeAnim?.cancel()
        if (!animate || (fw == tw && fh == th && fx == to[0] && fy == to[1])) {
            l.width = tw; l.height = th; l.x = to[0]; l.y = to[1]
            try { w.updateViewLayout(o, l) } catch (e: Exception) { }
            return
        }
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = c.optInt("animMs", 280).toLong()   // honours the system "animator duration scale" automatically
        va.addUpdateListener { v ->
            val f = v.animatedValue as Float
            l.width = (fw + (tw - fw) * f).toInt(); l.height = (fh + (th - fh) * f).toInt()
            l.x = (fx + (to[0] - fx) * f).toInt(); l.y = (fy + (to[1] - fy) * f).toInt()
            try { w.updateViewLayout(o, l) } catch (e: Exception) { }
        }
        sizeAnim = va
        va.start()
    }

    private fun updateProgress() {
        val a = curAct ?: return
        if (a.id != "music" || a.durMs <= 0L || !expanded) return
        val pos = a.posMs + (if (a.playing) System.currentTimeMillis() - a.posAt else 0L)
        bar.frac = pos.toFloat() / a.durMs.toFloat()
    }

    // ---------- full-screen video hiding ----------
    @Suppress("DEPRECATION")
    private fun barsHidden(): Boolean {
        val v = outer ?: return false
        val ins = v.rootWindowInsets ?: return false
        return if (Build.VERSION.SDK_INT >= 30) !ins.isVisible(WindowInsets.Type.statusBars())
        else (v.windowSystemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
    }

    /**
     * Heuristic, not a universal signal (Android has none). Requires Usage Access so we know the foreground app is the
     * app owning the media session, the session to be playing/paused/buffering, and then either landscape orientation
     * or (for known video apps) hidden system bars.
     */
    private fun fullscreenLikely(): Boolean {
        if (!Fg.available) return false
        val mc = MediaListener.controller ?: return false
        val st = mc.playbackState?.state ?: return false
        if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_PAUSED && st != PlaybackState.STATE_BUFFERING) return false
        val fg = Fg.pkg ?: return false
        if (fg != mc.packageName) return false
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return landscape || (VIDEO_APPS.contains(fg) && barsHidden())
    }

    private fun setHidden(h: Boolean) {
        val o = outer ?: return; val l = lp ?: return; val w = wm ?: return
        hidden = h
        o.visibility = if (h) View.INVISIBLE else View.VISIBLE
        l.flags = if (h) l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { w.updateViewLayout(o, l) } catch (e: Exception) { }
    }

    private fun checkFullscreen(c: JSONObject) {
        val enabled = c.optBoolean("hideFullscreen", true)
        if (!enabled) { if (hidden) setHidden(false); fsCount = 0; return }
        if (fullscreenLikely()) { fsCount = if (fsCount < 0) 1 else fsCount + 1 } else { fsCount = if (fsCount > 0) -1 else fsCount - 1 }
        // two consecutive agreeing checks (~2 s) are required to flip: no flicker while scrolling
        if (!hidden && fsCount >= 2) setHidden(true)
        else if (hidden && fsCount <= -2) setHidden(false)
    }

    // ---------- timers ----------
    private fun timersTick() {
        val raw = getSharedPreferences("island", 0).getString("timers", "[]") ?: "[]"
        if (raw == "[]") { IslandBus.remove("timer"); return }
        val arr = Timers.all(this)
        val now = System.currentTimeMillis()
        val keep = JSONArray()
        var next: JSONObject? = null
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val end = t.optLong("endAt")
            if (end <= now) {
                if (now - end < 120000L) IslandBus.post(Act("timerdone", 75, "\u23F1 Timer done", t.optString("label"), expand = true, pulse = end, expireAt = now + 15000L))
            } else {
                keep.put(t)
                val n = next
                if (n == null || end < n.optLong("endAt")) next = t
            }
        }
        if (keep.length() != arr.length()) Timers.save(this, keep)
        val n = next
        if (n != null) IslandBus.post(Act("timer", 70, "\u23F1 " + Fmt.clock(n.optLong("endAt") - now), n.optString("label")))
        else IslandBus.remove("timer")
    }

    fun refresh() { main.post { render(false) } }
    fun applyConfig() { main.post { applyStyle(); render(true) } }
    fun isShowing(): Boolean = outer?.isAttachedToWindow == true

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post { layoutPill(false) }   // fractions re-map to the new screen size/orientation
    }

    private val tick = object : Runnable {
        override fun run() {
            var interactive = true
            try {
                interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                if (interactive) {                       // no polling while the screen is off: saves battery
                    val c = Cfg.read(this@IslandService)
                    Fg.update(this@IslandService)
                    guardian.tick(this@IslandService, c)
                    timersTick()
                    checkFullscreen(c)
                    if (autoCollapseAt > 0L && System.currentTimeMillis() > autoCollapseAt) setExpanded(false, 0L)
                    render(false)
                    updateProgress()
                }
            } catch (e: Exception) { lastError = e.message ?: "tick failed" }
            main.postDelayed(this, if (interactive) 1000L else 5000L)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        sizeAnim?.cancel(); spin?.cancel()
        try { outer?.let { wm?.removeView(it) } } catch (e: Exception) { }
        val was = inst === this
        outer = null
        if (was) {
            inst = null
            IslandModule.emit("islandState", mapOf("running" to false, "error" to lastError))
        }
        super.onDestroy()
    }
}
