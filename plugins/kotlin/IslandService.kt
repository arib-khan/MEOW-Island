package com.smartisland.app
import android.app.*; import android.content.*; import android.graphics.*; import android.graphics.drawable.GradientDrawable
import android.os.*; import android.view.*; import android.widget.*
import androidx.core.app.NotificationCompat

/** Foreground service that owns the overlay pill. State is pushed from JS via IslandModule.update(). */
class IslandService : Service() {
  private lateinit var wm: WindowManager; private var root: LinearLayout? = null
  private lateinit var title: TextView; private lateinit var sub: TextView; private lateinit var art: ImageView
  private var spin: ObjectAnimatorHolder? = null
  companion object { var inst: IslandService? = null }

  override fun onBind(i: Intent?) = null
  override fun onCreate() {
    super.onCreate(); inst = this
    val ch = "island"; val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(ch,"Smart Island",NotificationManager.IMPORTANCE_MIN))
    startForeground(1, NotificationCompat.Builder(this,ch).setContentTitle("Smart Island running").setSmallIcon(android.R.drawable.ic_dialog_info).build())
    if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
    wm = getSystemService(WINDOW_SERVICE) as WindowManager
    build()
  }
  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
  private fun build() {
    val p = getSharedPreferences("island", MODE_PRIVATE)
    val w = p.getInt("w", 120); val h = p.getInt("h", 32); val r = p.getInt("r", 20)
    val bg = GradientDrawable().apply { setColor(p.getInt("bg", Color.BLACK)); cornerRadius = dp(r).toFloat() }
    art = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)); visibility = View.GONE; clipToOutline = true
      outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setOval(0,0,v.width,v.height) } } }
    title = TextView(this).apply { setTextColor(p.getInt("fg", Color.WHITE)); textSize = 13f; maxLines = 1; setPadding(dp(8),0,dp(8),0) }
    sub = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 11f; maxLines = 1; visibility = View.GONE }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(sub) }
    root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg
      setPadding(dp(8), dp(4), dp(8), dp(4)); addView(art); addView(col)
      setOnClickListener { expanded = !expanded; sub.visibility = if (expanded) View.VISIBLE else View.GONE }
      minimumWidth = dp(w); minimumHeight = dp(h) }
    val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
    lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.y = dp(8)
    wm.addView(root, lp)
  }
  private var expanded = false
  fun render(t: String, s: String, artBytes: ByteArray?, playing: Boolean, durMs: Long) {
    Handler(Looper.getMainLooper()).post {
      title.text = t; sub.text = s
      if (artBytes != null) { art.setImageBitmap(BitmapFactory.decodeByteArray(artBytes,0,artBytes.size)); art.visibility = View.VISIBLE } else art.visibility = View.GONE
      val a = art.animation
      if (playing && artBytes != null) { if (spin == null) spin = ObjectAnimatorHolder(art); spin!!.start(durMs) } else spin?.pause()
    }
  }
  override fun onDestroy() { root?.let { wm.removeView(it) }; inst = null; super.onDestroy() }
}
/** Pausable infinite rotation (CD effect). */
class ObjectAnimatorHolder(v: View) {
  private val a = android.animation.ObjectAnimator.ofFloat(v, "rotation", 0f, 360f).apply { repeatCount = -1; interpolator = android.view.animation.LinearInterpolator() }
  fun start(ms: Long) { a.duration = ms; if (!a.isStarted) a.start() else if (a.isPaused) a.resume() }
  fun pause() { if (a.isRunning) a.pause() }
}
