package com.smartisland.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream

/**
 * Renders a user's HTML into a 240x320 bitmap, locally, in a throw-away WebView that is as locked down as the platform allows:
 *   - no JavaScript bridge (addJavascriptInterface is never called), so the page cannot reach any app code;
 *   - JavaScript is OFF unless the user explicitly allows it for this design; even then: no storage, no network, no navigation;
 *   - no file or content-provider access; the page is loaded from a string under a fake https origin whose requests are answered with an empty 404;
 *   - network loads are blocked outright, so remote images / fonts / scripts do not render (this is intended, and the app says so);
 *   - every navigation attempt is refused;
 *   - an 8 second timeout; the WebView is destroyed afterwards on every path.
 * The WebView is laid out at 240x320 CSS pixels (physical size = CSS size x screen density) and the capture is scaled to exactly 240x320, so one CSS
 * pixel is one display pixel. It needs the app in the foreground (an activity to host the view); the saved image is what is re-sent later.
 */
object CustomRender {
    const val W = 240; const val H = 320
    private const val TIMEOUT_MS = 8000L

    class Result(val rgb565: ByteArray?, val code: String, val message: String)

    private val ui = Handler(Looper.getMainLooper())

    /** Calls [done] exactly once, on the main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun render(activity: Activity?, html: String, allowScripts: Boolean, done: (Result) -> Unit) {
        if (activity == null || activity.isFinishing) { done(Result(null, "NO_ACTIVITY", "Open the Meow Island app on screen to render a design.")); return }
        ui.post {
            var finished = false
            var wv: WebView? = null
            val root = activity.window.decorView as? ViewGroup
            fun cleanup() { try { wv?.let { v -> root?.removeView(v); v.stopLoading(); v.destroy() } } catch (e: Exception) { }; wv = null }
            fun finish(r: Result) { if (finished) return; finished = true; ui.removeCallbacksAndMessages(null); cleanup(); done(r) }
            try {
                val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
                val pw = Math.round(W * density); val ph = Math.round(H * density)
                val v = WebView(activity)
                wv = v
                v.layoutParams = FrameLayout.LayoutParams(pw, ph)
                v.translationX = -(pw + 64).toFloat()                       // attached (so it really renders) but off screen
                v.isVerticalScrollBarEnabled = false; v.isHorizontalScrollBarEnabled = false
                v.overScrollMode = View.OVER_SCROLL_NEVER
                v.setBackgroundColor(Color.WHITE)
                val s = v.settings
                s.javaScriptEnabled = allowScripts
                s.javaScriptCanOpenWindowsAutomatically = false
                s.allowFileAccess = false; s.allowContentAccess = false
                s.domStorageEnabled = false
                s.setSupportMultipleWindows(false)
                s.blockNetworkLoads = true
                s.setSupportZoom(false); s.builtInZoomControls = false
                s.loadsImagesAutomatically = true
                s.cacheMode = WebSettings.LOAD_NO_CACHE
                s.mediaPlaybackRequiresUserGesture = true
                s.textZoom = 100
                s.useWideViewPort = false; s.loadWithOverviewMode = false
                v.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true   // no navigation, ever
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url.toString()
                        if (u.startsWith("data:") || u == "about:blank") return null
                        return WebResourceResponse("text/plain", "utf-8", 404, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun onPageFinished(view: WebView, url: String?) {
                        // give layout, inline images and (optional) scripts a moment, then capture
                        ui.postDelayed({ if (!finished) capture(v, pw, ph, ::finish) }, if (allowScripts) 1200L else 400L)
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { /* a blocked remote resource is expected, not fatal */ }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        finish(Result(null, "RENDERER_GONE", "The renderer crashed on this design. Try a simpler file.")); return true
                    }
                }
                root?.addView(v) ?: run { finish(Result(null, "NO_ACTIVITY", "No window to render in.")); return@post }
                v.loadDataWithBaseURL("https://meow.local/", html, "text/html", "utf-8", null)
                ui.postDelayed({ finish(Result(null, "TIMEOUT", "The design took too long to render (8 s). Remove heavy scripts or very large images.")) }, TIMEOUT_MS)
            } catch (e: Exception) {
                finish(Result(null, "RENDER_FAILED", "Could not render this file: " + (e.message ?: e.javaClass.simpleName)))
            } catch (e: OutOfMemoryError) {
                finish(Result(null, "RENDER_FAILED", "Not enough memory to render this design."))
            }
        }
    }

    private fun capture(v: WebView, pw: Int, ph: Int, finish: (Result) -> Unit) {
        try {
            v.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, pw, ph)
            val big = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val c = Canvas(big); c.drawColor(Color.WHITE); v.draw(c)
            val bmp = if (pw == W && ph == H) big else Bitmap.createScaledBitmap(big, W, H, true)
            if (bmp !== big) big.recycle()
            finish(Result(toRgb565(bmp), "", ""))
            bmp.recycle()
        } catch (e: Exception) {
            finish(Result(null, "RENDER_FAILED", "Could not capture the rendered design: " + (e.message ?: e.javaClass.simpleName)))
        } catch (e: OutOfMemoryError) {
            finish(Result(null, "RENDER_FAILED", "Not enough memory to capture this design."))
        }
    }

    /** ARGB bitmap -> RGB565, two bytes per pixel, little-endian, row by row (the device format; see mimg.h). */
    fun toRgb565(bmp: Bitmap): ByteArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 2)
        for (i in px.indices) {
            val c = px[i]
            val v = (((c shr 16) and 0xFF) shr 3 shl 11) or (((c shr 8) and 0xFF) shr 2 shl 5) or ((c and 0xFF) shr 3)
            out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }
}
