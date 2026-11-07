package com.smartisland.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The files that come with a custom screen: images, fonts, style sheets, scripts, keyed by their path relative to the HTML file. */
class Assets(val files: Map<String, ByteArray>) {
    private val lower: Map<String, String> = files.keys.associateBy { it.lowercase(Locale.ROOT) }
    private val byBase: Map<String, List<String>> = files.keys.groupBy { it.substringAfterLast('/').lowercase(Locale.ROOT) }

    /** Exact path first, then ignoring case (zips made on Windows), then by file name alone when that is unambiguous (a flat pick of html + images). */
    fun find(rawPath: String): Pair<String, ByteArray>? {
        val p = Uri.decode(rawPath).substringBefore('?').substringBefore('#').trimStart('/')
        if (p.isEmpty()) return null
        files[p]?.let { return p to it }
        lower[p.lowercase(Locale.ROOT)]?.let { k -> return k to files.getValue(k) }
        val c = byBase[p.substringAfterLast('/').lowercase(Locale.ROOT)]
        if (c != null && c.size == 1) return c[0] to files.getValue(c[0])
        return null
    }

    companion object {
        fun mimeFor(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                "svg" -> "image/svg+xml"; "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
                "woff2" -> "font/woff2"; "woff" -> "font/woff"; "ttf" -> "font/ttf"; "otf" -> "font/otf"; "webp" -> "image/webp"; "avif" -> "image/avif"
                "html", "htm" -> "text/html"; "txt" -> "text/plain"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }
        val ALLOWED_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "avif", "woff", "woff2", "ttf", "otf", "css", "js", "mjs", "json", "txt", "html", "htm")
    }
}

/**
 * Renders a user's HTML/CSS/JavaScript into frames for the display, locally, in a throw-away WebView that is as locked down as the platform allows:
 *   - no JavaScript bridge (addJavascriptInterface is never called), so the page cannot reach any app code;
 *   - the page's own scripts run only when the design allows it (otherwise a Content-Security-Policy stops them); no storage, no navigation, no file access;
 *   - the WebView itself never touches the network (blockNetworkLoads). Every request is answered by Session.intercept: files that came with the design
 *     are served from memory, and, only when the design allows "online resources", https images / fonts / style sheets / scripts are fetched by THIS code
 *     (size, count and time limits, content-type allow-list) and handed to the page. Anything else gets an empty 404;
 *   - a virtual clock (src/core/animscreen.ts HARNESS_JS) replaces time inside the page, so CSS animations, transitions, Web Animations, timers and
 *     requestAnimationFrame advance exactly one step per captured frame;
 *   - a timeout; the WebView is destroyed afterwards on every path.
 * The WebView is laid out at 240x320 CSS pixels (physical size = CSS size x screen density) and each capture is box-filtered down to exactly 240x320,
 * so one CSS pixel is one display pixel. It needs the app in the foreground (an activity to host the view).
 */
object CustomRender {
    const val W = 240; const val H = 320
    const val MAX_FRAMES = 120
    private const val TIMEOUT_MS = 45_000L
    private const val READY_POLLS = 40                      // x 100 ms: how long to wait for images and fonts
    private const val REMOTE_MAX_ONE = 6 * 1024 * 1024
    private const val REMOTE_MAX_TOTAL = 24 * 1024 * 1024
    private const val REMOTE_MAX_COUNT = 64
    private const val REMOTE_TIMEOUT_MS = 6000
    private const val RAW_BUDGET = 12 * 1024 * 1024         // changed-pixel bytes we are willing to hand to the app for one animation

    class Options(val scripts: Boolean, val online: Boolean, val animate: Boolean, val fps: Int, val durationMs: Int, val loopMode: String)
    /** One captured frame: frame 0 covers the whole screen, later ones only the rectangle that changed since the previous frame. */
    class Frame(val x: Int, val y: Int, val w: Int, val h: Int, var delayMs: Int, val rgb565: ByteArray)
    class Result(val frames: List<Frame>?, val loop: Boolean, val code: String, val message: String, val notes: List<String>)

    private val ui = Handler(Looper.getMainLooper())

    /** Calls [done] exactly once, on the main thread. [preparedHtml] already contains the clock (animscreen.ts prepareHtml); [harness] is installed from outside when scripts are off. */
    fun render(activity: Activity?, assets: Assets, preparedHtml: String, harness: String, o: Options, done: (Result) -> Unit) {
        if (activity == null || activity.isFinishing) { done(Result(null, false, "NO_ACTIVITY", "Open the Meow Island app on screen to render a design.", emptyList())); return }
        ui.post { Session(activity, assets, preparedHtml, harness, o, done).start() }
    }

    private class Session(val activity: Activity, val assets: Assets, val html: String, val harness: String, val o: Options, val done: (Result) -> Unit) {
        var finished = false
        var started = false
        var wv: WebView? = null
        val root = activity.window.decorView as? ViewGroup
        val notes = CopyOnWriteArrayList<String>()
        val remote = ConcurrentHashMap<String, Pair<String, ByteArray>>()
        val remoteBytes = AtomicLong(0); val remoteCount = AtomicInteger(0)
        val token = Any()
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        val pw = Math.round(W * density); val ph = Math.round(H * density)
        val frames = ArrayList<Frame>(); var rawBytes = 0L
        var prev: ShortArray? = null
        var big: Bitmap? = null; var bigPx: IntArray? = null
        var n = 1; var interval = 100; var firstStepMs = 0L; var loop = false; var idx = 0

        fun note(s: String) { if (notes.size < 8 && !notes.contains(s)) notes.add(s) }
        fun cleanup() {
            try { wv?.let { v -> root?.removeView(v); v.stopLoading(); v.destroy() } } catch (e: Exception) { }
            wv = null
            try { big?.recycle() } catch (e: Exception) { }
            big = null; bigPx = null
        }
        fun finish(r: Result) { if (finished) return; finished = true; ui.removeCallbacksAndMessages(token); cleanup(); done(r) }
        fun fail(code: String, msg: String) = finish(Result(null, false, code, msg, notes.toList()))
        fun later(ms: Long, r: Runnable) { ui.postAtTime(r, token, SystemClock.uptimeMillis() + ms) }

        @SuppressLint("SetJavaScriptEnabled")
        fun start() {
            try {
                val v = WebView(activity); wv = v
                v.layoutParams = FrameLayout.LayoutParams(pw, ph)
                v.translationX = -(pw + 64).toFloat()                       // attached (so it really renders) but off screen
                v.isVerticalScrollBarEnabled = false; v.isHorizontalScrollBarEnabled = false
                v.overScrollMode = View.OVER_SCROLL_NEVER
                v.setBackgroundColor(Color.WHITE)
                val s = v.settings
                s.javaScriptEnabled = true                                  // the harness needs it; the page's own scripts are stopped by a CSP when the design has scripts off
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
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = intercept(request)
                    override fun onPageFinished(view: WebView, url: String?) { if (url == null || url == "about:blank") return; onLoaded() }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { fail("RENDERER_GONE", "The renderer crashed on this design. Try a simpler file."); return true }
                }
                if (root == null) { fail("NO_ACTIVITY", "No window to render in."); return }
                root.addView(v)
                v.loadDataWithBaseURL("https://meow.local/", html, "text/html", "utf-8", null)
                later(TIMEOUT_MS, Runnable { fail("TIMEOUT", "The design took too long to render. Remove heavy scripts or very large images, or lower the frame rate or duration.") })
            } catch (e: Exception) { fail("RENDER_FAILED", "Could not render this file: " + (e.message ?: e.javaClass.simpleName)) }
            catch (e: OutOfMemoryError) { fail("RENDER_FAILED", "Not enough memory to render this design.") }
        }

        // ---------------------------------------------------------------- requests (called on WebView background threads)
        private fun blocked(): WebResourceResponse = WebResourceResponse("text/plain", "utf-8", 404, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        private fun served(mime: String, bytes: ByteArray): WebResourceResponse =
            WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("svg")) "utf-8" else null, 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), ByteArrayInputStream(bytes))

        fun intercept(r: WebResourceRequest): WebResourceResponse? {
            val u = r.url
            if (u.scheme == "data" || u.toString() == "about:blank") return null
            if (!r.method.equals("GET", ignoreCase = true)) return blocked()
            if (u.scheme == "https" && u.host == "meow.local") {
                val path = u.encodedPath ?: "/"
                if (path == "/" || path.isEmpty()) return null
                val hit = assets.find(path)
                if (hit != null) return served(Assets.mimeFor(hit.first), hit.second)
                if (!path.endsWith("favicon.ico")) note("Not found in your files: " + Uri.decode(path.trimStart('/')) + " (choose it together with the HTML file, or put everything in one .zip)")
                return blocked()
            }
            if (u.scheme == "https" && o.online) return fetchRemote(u.toString())
            if (u.scheme == "https") note("Online resources are off for this screen, so " + (u.host ?: "a web address") + " was not loaded")
            else if (u.scheme == "http") note("Only https addresses can be loaded (" + (u.host ?: "http") + " is plain http)")
            return blocked()
        }

        private fun fetchRemote(url: String): WebResourceResponse {
            remote[url]?.let { return served(it.first, it.second) }
            if (remoteCount.incrementAndGet() > REMOTE_MAX_COUNT) { note("Too many online files; some were skipped"); return blocked() }
            val host = try { URL(url).host } catch (e: Exception) { "?" }
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = REMOTE_TIMEOUT_MS; c.readTimeout = REMOTE_TIMEOUT_MS; c.instanceFollowRedirects = true; c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", "MeowIsland/1.0"); c.setRequestProperty("Accept", "image/*,font/*,text/css,*/*;q=0.5")
                val code = c.responseCode
                if (code !in 200..299) { note("$host answered $code for " + url.substringAfter(host).take(40)); c.disconnect(); return blocked() }
                if (c.url.protocol != "https") { c.disconnect(); return blocked() }
                val ctype = (c.contentType ?: "").substringBefore(';').trim().lowercase(Locale.ROOT)
                val okType = ctype.startsWith("image/") || ctype.startsWith("font/") || ctype.startsWith("application/font") || ctype.startsWith("application/x-font") || ctype == "text/css" ||
                    (o.scripts && (ctype.contains("javascript") || ctype == "application/json")) || ctype == "application/octet-stream"
                if (!okType) { note("$host sent " + ctype.ifEmpty { "an unknown type" } + ", which is not an image, font or style sheet"); c.disconnect(); return blocked() }
                val out = ByteArrayOutputStream(); val buf = ByteArray(16384); var total = 0
                c.inputStream.use { ins -> while (true) { val k = ins.read(buf); if (k < 0) break; total += k; if (total > REMOTE_MAX_ONE) break; out.write(buf, 0, k) } }
                c.disconnect()
                if (total > REMOTE_MAX_ONE) { note("A file from $host is larger than ${REMOTE_MAX_ONE / 1048576} MB and was skipped"); return blocked() }
                if (remoteBytes.addAndGet(total.toLong()) > REMOTE_MAX_TOTAL) { note("Online files are larger than ${REMOTE_MAX_TOTAL / 1048576} MB in total; some were skipped"); return blocked() }
                val mime = if (ctype == "application/octet-stream") Assets.mimeFor(url.substringBefore('?')) else ctype
                val bytes = out.toByteArray(); remote[url] = mime to bytes
                return served(mime, bytes)
            } catch (e: Exception) { note("Could not load " + url.substringAfter("//").take(60) + ": " + (e.message ?: e.javaClass.simpleName).take(60)); return blocked() }
        }

        // ---------------------------------------------------------------- JavaScript helpers
        private fun js(code: String, cb: (String) -> Unit) {
            val v = wv ?: return
            try { v.evaluateJavascript(code) { r -> if (!finished) cb(r ?: "null") } } catch (e: Exception) { fail("RENDER_FAILED", "Could not control the renderer: " + (e.message ?: "")) }
        }
        private fun jsonString(raw: String): String? = try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null }

        // ---------------------------------------------------------------- flow: loaded -> ready -> plan -> capture frames -> done
        fun onLoaded() {
            if (finished || started) return
            started = true
            if (o.scripts) pollReady(0) else js(harness) { pollReady(0) }
        }
        fun pollReady(count: Int) {
            js("(window.__meow&&window.__meow.ready())?1:0") { r ->
                if (r.trim() == "1" || count >= READY_POLLS) {
                    if (r.trim() != "1") note("Some images or fonts were still loading after ${READY_POLLS / 10} seconds")
                    plan()
                } else later(100, Runnable { pollReady(count + 1) })
            }
        }
        fun plan() {
            js("window.__meow.step(0);JSON.stringify(window.__meow.measure())") { raw ->
                val m = try { JSONObject(jsonString(raw) ?: "{}") } catch (e: Exception) { JSONObject() }
                val anims = m.optInt("anims"); val raf = m.optInt("raf"); val timers = m.optInt("timers"); val intervals = m.optInt("intervals")
                val fin = m.optDouble("finiteEnd", 0.0); val inf = m.optDouble("infiniteCycle", 0.0)
                val dynamic = raf > 0 || timers > 0
                if (!o.animate || (anims == 0 && !dynamic)) {
                    // a still: with "animate" off, show the state the animations end in
                    n = 1; loop = false; interval = 0; firstStepMs = if (o.animate) 0L else Math.min(fin, 20000.0).toLong()
                } else {
                    var dur = if (o.durationMs > 0) o.durationMs else if (Math.max(fin, inf) > 0) Math.max(fin, inf).toInt() else 3000
                    dur = dur.coerceIn(200, 20000)
                    val fps = o.fps.coerceIn(1, 15); interval = Math.round(1000f / fps)
                    n = Math.round(dur / interval.toFloat()).coerceIn(2, MAX_FRAMES)
                    loop = when (o.loopMode) { "on" -> true; "off" -> false; else -> inf > 0 || raf > 0 || intervals > 0 }
                    firstStepMs = 0L
                }
                idx = 0; nextFrame()
            }
        }
        fun nextFrame() {
            if (finished) return
            if (idx >= n) { complete(); return }
            val dt = if (idx == 0) firstStepMs else interval.toLong()
            js("window.__meow.step($dt);0") {
                val v = wv
                if (v != null) {
                    try { v.postVisualStateCallback(idx.toLong(), object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) { if (!finished) capture() } }) }
                    catch (e: Exception) { later(60, Runnable { capture() }) }          // very old WebView: just wait a moment
                }
            }
        }
        fun capture() {
            val v = wv ?: return
            try {
                var b = big
                if (b == null) { b = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888); big = b; bigPx = IntArray(pw * ph) }
                v.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, pw, ph)
                val c = Canvas(b); c.drawColor(Color.WHITE); v.draw(c)
                val cur = boxDownscale(b, bigPx!!)
                val p = prev
                if (p == null) {
                    frames.add(Frame(0, 0, W, H, if (n == 1) 100 else interval, toBytes(cur, 0, 0, W, H))); rawBytes += W * H * 2L
                } else {
                    var minX = W; var minY = H; var maxX = -1; var maxY = -1
                    for (y in 0 until H) { val row = y * W; for (x in 0 until W) if (cur[row + x] != p[row + x]) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y } }
                    if (maxX < 0) frames.last().delayMs += interval                                  // nothing changed: the previous frame just stays longer
                    else {
                        val w = maxX - minX + 1; val h = maxY - minY + 1
                        frames.add(Frame(minX, minY, w, h, interval, toBytes(cur, minX, minY, w, h))); rawBytes += w * h * 2L
                        if (rawBytes > RAW_BUDGET) { fail("TOO_HEAVY", "This animation changes too much on screen to record. Lower the frame rate or the duration, or animate a smaller area."); return }
                    }
                }
                prev = cur
                idx++
                later(0, Runnable { nextFrame() })
            } catch (e: Exception) { fail("RENDER_FAILED", "Could not capture the rendered design: " + (e.message ?: e.javaClass.simpleName)) }
            catch (e: OutOfMemoryError) { fail("RENDER_FAILED", "Not enough memory to capture this design.") }
        }
        fun complete() { finish(Result(frames.toList(), loop && frames.size > 1, "", "", notes.toList())) }

        /** pw x ph ARGB -> 240 x 320 RGB565 values, averaging every source pixel that falls into a display pixel. */
        fun boxDownscale(bmp: Bitmap, px: IntArray): ShortArray {
            bmp.getPixels(px, 0, pw, 0, 0, pw, ph)
            val out = ShortArray(W * H)
            for (y in 0 until H) {
                val y0 = y * ph / H; val y1 = Math.max(y0 + 1, (y + 1) * ph / H)
                for (x in 0 until W) {
                    val x0 = x * pw / W; val x1 = Math.max(x0 + 1, (x + 1) * pw / W)
                    var r = 0; var g = 0; var bl = 0; var cnt = 0
                    for (yy in y0 until y1) { val row = yy * pw; for (xx in x0 until x1) { val c = px[row + xx]; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF; cnt++ } }
                    val rr = r / cnt; val gg = g / cnt; val bb = bl / cnt
                    out[y * W + x] = (((rr shr 3) shl 11) or ((gg shr 2) shl 5) or (bb shr 3)).toShort()
                }
            }
            return out
        }
        /** The pixels of a rectangle as RGB565, two bytes per pixel, little-endian, row by row (the device format; see mimg.h). */
        fun toBytes(px: ShortArray, x: Int, y: Int, w: Int, h: Int): ByteArray {
            val out = ByteArray(w * h * 2); var o = 0
            for (j in 0 until h) { val row = (y + j) * W + x; for (i in 0 until w) { val v = px[row + i].toInt(); out[o++] = v.toByte(); out[o++] = (v shr 8).toByte() } }
            return out
        }
    }
}
