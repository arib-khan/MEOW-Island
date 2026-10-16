package com.smartisland.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import kotlin.math.abs

/** Round "vinyl/CD" view: circular artwork, grooves, label ring and centre hole. */
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
        } else { p.color = 0xFF2A2A2A.toInt(); c.drawCircle(cx, cy, r, p) }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x44000000
        c.drawCircle(cx, cy, r * 0.82f, p); c.drawCircle(cx, cy, r * 0.64f, p); c.drawCircle(cx, cy, r * 0.46f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFF111111.toInt(); c.drawCircle(cx, cy, r * 0.27f, p)
        p.color = 0xFF000000.toInt(); c.drawCircle(cx, cy, r * 0.11f, p)
    }
}

/**
 * Screen Time Guardian, executed natively on the service's own clock so it keeps working while the
 * React Native layer is suspended in the background. Feedback only: it never blocks an app.
 */
class Guardian {
    private var lastRun = 0L
    private var lastScan = 0L
    private var fg: String? = null
    private var angryPkg: String? = null

    fun reset() { angryPkg = null; IslandBus.remove("guard") }

    private fun scan(ctx: Context, now: Long) {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val from = if (lastScan == 0L) now - 3L * 3600000L else lastScan - 2000L
        val ev = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                1 -> fg = e.packageName
                2 -> if (e.packageName == fg) fg = null
                16, 17 -> fg = null
            }
        }
        lastScan = now
    }

    fun tick(ctx: Context, cfg: JSONObject) {
        val now = System.currentTimeMillis()
        if (now - lastRun < 3000L) return
        lastRun = now
        if (!cfg.optBoolean("guardEnabled", true) || !UsageUtil.hasAccess(ctx)) {
            if (angryPkg != null) reset()
            return
        }
        scan(ctx, now)
        val cur = fg
        val limits = cfg.optJSONArray("limits")
        var overPkg: String? = null
        var overName = ""
        if (cur != null && limits != null) {
            for (i in 0 until limits.length()) {
                val l = limits.optJSONObject(i) ?: continue
                if (l.optString("pkg") == cur && l.optBoolean("enabled", true)) {
                    val used = UsageUtil.todayAll(ctx)[cur] ?: 0L
                    if (used > l.optInt("limitMin", 30) * 60000L) { overPkg = cur; overName = l.optString("name", cur) }
                }
            }
        }
        val prev = angryPkg
        if (overPkg != null) {
            angryPkg = overPkg
            IslandBus.post(Act("guard", 80, overName, cfg.optString("warnText", "Daily limit reached"),
                emoji = cfg.optString("angry", "\uD83D\uDE21"), expand = true))
        } else if (prev != null) {
            angryPkg = null
            IslandBus.remove("guard")
            // Happy emoji once per transition, only when the user actually left the monitored app.
            if (cur != prev) IslandBus.post(Act("happy", 80, "", "", emoji = cfg.optString("happy", "\uD83D\uDE0A"),
                expireAt = now + cfg.optInt("happyMs", 3000)))
        }
    }
}

@SuppressLint("NewApi")
class IslandService : Service() {
    companion object {
        @Volatile var inst: IslandService? = null
        @Volatile var lastError: String = ""
        private const val CH = "island_fgs"
    }

    private val main = Handler(Looper.getMainLooper())
    private val guardian = Guardian()
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private lateinit var cd: CDView
    private lateinit var ico: ImageView
    private lateinit var emo: TextView
    private lateinit var col: LinearLayout
    private lateinit var tT: TextView
    private lateinit var tS: TextView
    private var spin: ObjectAnimator? = null
    private var sizeAnim: ValueAnimator? = null
    private var expanded = false
    private var autoCollapseAt = 0L
    private var shownSig = "?"

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
            nm.createNotificationChannel(NotificationChannel(CH, "Smart Island service", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, IslandService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Smart Island is running")
            .setContentText("Tap to open settings")
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun statusBarH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /** Vertically centre the pill in the status-bar band, never above the screen edge. */
    private fun topOffset(h: Int) = maxOf(dp(4), (statusBarH() - h) / 2 + dp(2))

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView() {
        val c = Cfg.read(this)
        val minW = dp(c.optInt("w", 120)); val minH = dp(c.optInt("h", 34))
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER
        r.setPadding(dp(10), dp(4), dp(10), dp(4))
        r.clipToOutline = true; r.outlineProvider = ViewOutlineProvider.BACKGROUND
        cd = CDView(this); cd.layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)); cd.visibility = View.GONE
        ico = ImageView(this); ico.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)); ico.visibility = View.GONE
        emo = TextView(this); emo.textSize = 20f; emo.visibility = View.GONE
        col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(dp(8), 0, dp(4), 0); col.visibility = View.GONE
        tT = TextView(this); tT.textSize = 13f; tT.maxLines = 1; tT.ellipsize = TextUtils.TruncateAt.END
        tS = TextView(this); tS.textSize = 11f; tS.maxLines = 2; tS.ellipsize = TextUtils.TruncateAt.END; tS.visibility = View.GONE
        col.addView(tT); col.addView(tS)
        r.addView(cd); r.addView(ico); r.addView(emo); r.addView(col)
        root = r
        applyStyle()

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val l = WindowManager.LayoutParams(minW, minH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        l.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        l.y = topOffset(minH)
        if (Build.VERSION.SDK_INT >= 28) l.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp = l

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { setExpanded(!expanded, 0L); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) > abs(vx)) setExpanded(vy > 0, 0L)       // swipe down = expand, up = collapse
                else IslandBus.top()?.let { IslandBus.remove(it.id) } // swipe sideways = dismiss
                return true
            }
        })
        r.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
        wm!!.addView(r, l)
        render(true)
    }

    private fun applyStyle() {
        val c = Cfg.read(this)
        val r = root ?: return
        val bg = GradientDrawable()
        bg.setColor(c.optInt("bg", Color.BLACK))
        bg.cornerRadius = dp(c.optInt("r", 22)).toFloat()
        r.background = bg
        val fg = c.optInt("fg", Color.WHITE)
        tT.setTextColor(fg)
        tS.setTextColor((fg and 0x00FFFFFF) or 0xB3000000.toInt())
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
        tS.visibility = if (expanded && tS.text.isNotEmpty()) View.VISIBLE else View.GONE
        col.visibility = if (tT.visibility == View.VISIBLE || tS.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        tT.maxWidth = dp(if (expanded) 260 else 150)
    }

    private fun bind(a: Act?) {
        if (a == null) {
            cd.visibility = View.GONE; ico.visibility = View.GONE; emo.visibility = View.GONE
            tT.text = ""; tT.visibility = View.GONE; tS.text = ""; setSpin(false)
            updateSubVisibility(); return
        }
        val music = a.id == "music"
        cd.visibility = if (music) View.VISIBLE else View.GONE
        if (music) cd.art = a.art
        setSpin(music && a.spinning)
        ico.visibility = if (!music && a.icon != null) View.VISIBLE else View.GONE
        if (a.icon != null) ico.setImageBitmap(a.icon)
        emo.visibility = if (a.emoji.isNotEmpty()) View.VISIBLE else View.GONE
        emo.text = a.emoji
        tT.text = a.title; tT.visibility = if (a.title.isEmpty()) View.GONE else View.VISIBLE
        tS.text = a.sub
        updateSubVisibility()
    }

    private fun render(force: Boolean) {
        if (root == null) return
        val a = IslandBus.top()
        val sig = if (a == null) "idle" else a.id + "|" + a.title + "|" + a.sub + "|" + a.spinning + "|" + a.emoji + "|" + (a.art != null)
        if (sig == shownSig && !force) return
        val changed = sig != shownSig
        shownSig = sig
        bind(a)
        if (a == null) { expanded = false; autoCollapseAt = 0L }
        else if (changed && a.expand) { expanded = true; autoCollapseAt = System.currentTimeMillis() + Cfg.read(this).optInt("expandMs", 5000) }
        else if (changed) { expanded = false; autoCollapseAt = 0L }
        updateSubVisibility()
        layoutPill(true)
    }

    private fun setExpanded(v: Boolean, autoMs: Long) {
        expanded = v
        autoCollapseAt = if (v && autoMs > 0L) System.currentTimeMillis() + autoMs else 0L
        updateSubVisibility()
        layoutPill(true)
    }

    private fun layoutPill(animate: Boolean) {
        val r = root ?: return; val l = lp ?: return; val w = wm ?: return
        val c = Cfg.read(this)
        val minW = dp(c.optInt("w", 120)); val minH = dp(c.optInt("h", 34))
        val maxW = resources.displayMetrics.widthPixels - dp(24)
        r.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val tw = minOf(maxOf(minW, r.measuredWidth), maxW)
        val th = maxOf(minH, r.measuredHeight)
        val fromW = l.width; val fromH = l.height
        sizeAnim?.cancel()
        l.y = topOffset(th)
        if (!animate || (fromW == tw && fromH == th)) {
            l.width = tw; l.height = th
            try { w.updateViewLayout(r, l) } catch (e: Exception) { }
            return
        }
        val ms = c.optInt("animMs", 280).toLong()
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = ms
        va.addUpdateListener { v ->
            val f = v.animatedValue as Float
            l.width = (fromW + (tw - fromW) * f).toInt()
            l.height = (fromH + (th - fromH) * f).toInt()
            try { w.updateViewLayout(r, l) } catch (e: Exception) { }
        }
        sizeAnim = va
        va.start()
    }

    fun refresh() { main.post { render(false) } }
    fun applyConfig() { main.post { applyStyle(); render(true) } }
    fun isShowing(): Boolean = root?.isAttachedToWindow == true

    private val tick = object : Runnable {
        override fun run() {
            try {
                guardian.tick(this@IslandService, Cfg.read(this@IslandService))
                if (autoCollapseAt > 0L && System.currentTimeMillis() > autoCollapseAt) setExpanded(false, 0L)
                render(false)
            } catch (e: Exception) { lastError = e.message ?: "tick failed" }
            main.postDelayed(this, 1000L)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        sizeAnim?.cancel(); spin?.cancel()
        try { root?.let { wm?.removeView(it) } } catch (e: Exception) { }
        val was = inst === this
        root = null
        if (was) {
            inst = null
            IslandModule.emit("islandState", mapOf("running" to false, "error" to lastError))
        }
        super.onDestroy()
    }
}
