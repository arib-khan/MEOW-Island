package com.smartisland.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Optional, opt-in accessibility service that (a) counts Instagram Reels / YouTube Shorts and (b)
 * can shield those feeds. Privacy model: it only ever INSPECTS the Instagram and YouTube windows
 * (and only looks up a handful of view ids); for every other app it merely notices that you left.
 * Nothing it sees is sent anywhere or stored except the daily counters and a short diagnostic log
 * of event names. It is a separate service from [MeowAccessibilityService], which stays inert.
 *
 * The cheap path matters: with nothing enabled it returns on the first line of every event.
 */
class ShortsGuardService : AccessibilityService() {
    companion object {
        @Volatile var inst: ShortsGuardService? = null
        @Volatile var lastError = ""
        fun enabledInSettings(ctx: Context): Boolean {
            val list =
                    Settings.Secure.getString(
                            ctx.contentResolver,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    )
                            ?: return false
            val me = ComponentName(ctx, ShortsGuardService::class.java)
            return list.split(':').any {
                it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
            }
        }
        private const val EVAL_MIN_GAP = 250L
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var cfg: ShortsCfg = ShortsCfg.parse(org.json.JSONObject())
    private val counters = HashMap<String, ShortsCounter>()

    private var onFeed = false
    private var curApp: String? = null
    private var misses = 0
    private var noSig = 0
    private var scrollSeen = 0L
    private var lastEval = 0L
    private var evalPending = false
    private var snoozeUntil = 0L
    private var escapeTries = 0
    private var escapeChain = false
    private var milestoneUntil = 0L
    private var lastShown = -1
    private var evals = 0
    private var matches = 0
    private var signatureWarned = false
    private val swipe =
            SwipeCounter(
                    quietMs = 800
            ) // long enough for the page-key path (below) to count first, so a swipe is never
    // counted twice
    private var ytFirst = false
    private var ytFirstAt = 0L
    private var ytMode =
            0 // how Shorts are told apart: 1 = list position, 2 = the Short's own text, 0 = nothing
    // usable
    private val ytRows = HashSet<Int>()
    private var ytScrollSeen = false
    private var lastScrollEventAt = 0L
    private var lastRecordAt = 0L
    private var feedEnterAt = 0L

    private var pill: LinearLayout? = null
    private var pillEmoji: TextView? = null
    private var pillCount: TextView? = null
    private var pillText: TextView? = null
    private var shield: View? = null
    private var screenOff: BroadcastReceiver? = null

    private val evalRun = Runnable {
        evalPending = false
        evaluate()
    }
    private val escapeRun = Runnable { if (onFeed && shield != null) escapeStep(true) }
    private val settleRun = Runnable { settleSwipe() }
    private val milestoneRun = Runnable { updatePillText() }

    // ------------------------------------------------------------------ lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        reloadConfig()
        screenOff =
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        leaveFeed("screen off")
                    }
                }
        try {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (e: Exception) {
            screenOff = null
        }
        WellbeingStore.log(this, "guard service connected")
    }

    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        h.removeCallbacksAndMessages(null)
        removeShieldView()
        removePillView()
        try {
            screenOff?.let { unregisterReceiver(it) }
        } catch (e: Exception) {}
        screenOff = null
        onFeed = false
        curApp = null
        if (inst === this) inst = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::wm.isInitialized) return
        if (shield != null) {
            val a = curApp
            removeShieldView()
            if (a != null) showShield(a)
        }
        if (pill != null) {
            removePillView(false)
            curApp?.let { showPill(it) }
        }
    }

    /** Called after the app saved new settings. */
    fun reloadConfig() {
        cfg = WellbeingStore.shorts(this)
        if (!cfg.anyOn) {
            leaveFeed("all features off")
            return
        }
        removePillView(false) // appearance may have changed: rebuilt on next evaluation
        lastShown = -1
        scheduleEval(0)
    }

    fun status(): Map<String, Any> =
            mapOf(
                    "connected" to true,
                    "onFeed" to onFeed,
                    "app" to (curApp ?: ""),
                    "shield" to (shield != null),
                    "counter" to (pill != null),
                    "evals" to evals,
                    "matches" to matches,
                    "lastError" to lastError,
                    "snoozedMs" to maxOf(0L, snoozeUntil - System.currentTimeMillis())
            )

    // ------------------------------------------------------------------ events
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!cfg.anyOn) return
        val pkg = e.packageName?.toString() ?: return
        val app = ShortsApp.forPackage(pkg)
        if (app == null) {
            if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            if (pkg == packageName
            ) { // our own overlays fire events too: only an Activity means "left"
                if (e.className?.toString()?.contains("Activity") == true) leaveFeed("meow app")
                return
            }
            if (pkg == "com.android.systemui" || isIme(pkg))
                    return // shade, keyboard, volume bars: still on the same screen
            leaveFeed("other app")
            return
        }
        if (!cfg.counts(app) && !cfg.blocks(app)) return
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrollSeen = System.currentTimeMillis()
            if (app == ShortsApp.YT) onYoutubeScroll(e)
        }
        scheduleEval(0)
    }

    private fun isIme(pkg: String): Boolean =
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .enabledInputMethodList.any { it.packageName == pkg }
            } catch (e: Exception) {
                false
            }

    /**
     * Coalesces bursts of content-changed events (a playing video produces many) into at most one
     * tree lookup per [EVAL_MIN_GAP].
     */
    private fun scheduleEval(delay: Long) {
        if (evalPending) return
        val due = maxOf(System.currentTimeMillis() + delay, lastEval + EVAL_MIN_GAP)
        evalPending = true
        h.postDelayed(evalRun, maxOf(0L, due - System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------ detection
    private fun evaluate() {
        lastEval = System.currentTimeMillis()
        if (!cfg.anyOn) {
            leaveFeed("off")
            return
        }
        val root =
                try {
                    rootInActiveWindow
                } catch (e: Exception) {
                    null
                } ?: return
        try {
            val pkg = root.packageName?.toString() ?: return
            val app = ShortsApp.forPackage(pkg) ?: return
            if (!cfg.counts(app) && !cfg.blocks(app)) {
                leaveFeed("app off")
                return
            }
            evals++
            var det = ShortsDetector.detect(pkg, realHeight() / 2) { id -> nodes(root, pkg, id) }
            if (det.onFeed && det.app == ShortsApp.YT) {
                // YouTube's text-based fingerprint is not per-Short (it describes the player's
                // state), so identify the page itself.
                val pk =
                        try {
                            youtubePageKey(root, pkg, realHeight() / 2)
                        } catch (x: Exception) {
                            null
                        }
                val mode = pk?.mode ?: 0
                if (mode != ytMode) {
                    ytMode = mode
                    WellbeingStore.log(
                            this,
                            "yt page key: " +
                                    (if (mode == 1) "list position"
                                    else if (mode == 2) "page text" else "none")
                    )
                }
                if (pk != null) {
                    if (pk.row >= 0) ytRows.add(pk.row)
                    det = Detection(det.app, true, pk.key, det.matched)
                }
            }
            if (det.onFeed) {
                matches++
                onDetection(det)
            } else if (onFeed &&
                            curApp == ShortsApp.YT &&
                            System.currentTimeMillis() - lastScrollEventAt < 4000
            ) {
                misses = 0
                scheduleEval(
                        1000
                ) // the Shorts pager was scrolling a moment ago: keep the feed "on"
            } else if (onFeed) {
                misses++
                if (misses >= 2) leaveFeed("feed gone")
                else scheduleEval(250) // hysteresis: a swipe can momentarily hide the pager
            }
        } catch (e: Exception) {
            lastError = (e.message ?: e.javaClass.simpleName).take(120)
            WellbeingStore.log(this, "eval error: $lastError")
        } finally {
            rec(root)
        }
    }

    private class PageKey(val mode: Int, val key: String, val row: Int)

    /**
     * Identity of the Short that is on screen: (1) its position in YouTube's own vertical list
     * (accessibility collection info of the page, which changes by exactly one per swipe and does
     * not depend on what the player is doing), else (2) a hash of the longer texts inside that one
     * page (title, channel, counters; short labels and player-state words are ignored).
     */
    private fun youtubePageKey(root: AccessibilityNodeInfo, pkg: String, centerY: Int): PageKey? {
        var page: AccessibilityNodeInfo? = null
        for (id in listOf("reel_player_page_container", "reel_recycler")) {
            val found = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") ?: continue
            for (n in found) {
                if (!n.isVisibleToUser) continue
                if (id == "reel_recycler"
                ) { // the list itself: take its child that covers the middle of the screen
                    for (i in 0 until n.childCount) {
                        val c = n.getChild(i) ?: continue
                        val r = Rect()
                        c.getBoundsInScreen(r)
                        if (c.isVisibleToUser && r.top <= centerY && centerY <= r.bottom) {
                            page = c
                            break
                        }
                    }
                } else {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.top <= centerY && centerY <= r.bottom) page = n
                }
                if (page != null) break
            }
            if (page != null) break
        }
        val p = page ?: return null
        var x: AccessibilityNodeInfo? = p
        var depth = 0
        while (x != null && depth < 4) {
            val ci = x.collectionItemInfo
            if (ci != null && ci.rowIndex >= 0) return PageKey(1, "r" + ci.rowIndex, ci.rowIndex)
            x = x.parent
            depth++
        }
        val texts = java.util.TreeSet<String>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(p)
        var seen = 0
        val dynamic =
                Regex("(?i)(\\d{1,2}:\\d{2}|seconds?|pause|play|mute|buffer|loading|progress)")
        while (queue.isNotEmpty() && seen < 150) {
            val n = queue.poll() ?: break
            seen++
            for (t in listOf(n.text?.toString(), n.contentDescription?.toString())) if (t != null &&
                            t.length >= 8 &&
                            !dynamic.containsMatchIn(t)
            )
                    texts.add(t.take(120))
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return if (texts.isEmpty()) null
        else PageKey(2, "t" + ShortsDetector.stableHash(texts.joinToString("|")), -1)
    }

    private fun nodes(root: AccessibilityNodeInfo, pkg: String, id: String): List<NodeInfo> {
        val out = ArrayList<NodeInfo>()
        val found = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") ?: return out
        for (n in found) {
            val r = Rect()
            n.getBoundsInScreen(r)
            out.add(
                    NodeInfo(
                            id,
                            n.text?.toString()?.take(200),
                            n.contentDescription?.toString()?.take(200),
                            n.isVisibleToUser,
                            r.top,
                            r.bottom
                    )
            )
            rec(n)
        }
        return out
    }

    private fun onDetection(det: Detection) {
        val app = det.app ?: return
        val now = System.currentTimeMillis()
        misses = 0
        if (!onFeed || curApp != app) {
            if (onFeed) leaveFeed("switch")
            onFeed = true
            curApp = app
            signatureWarned = false
            feedEnterAt = now
            if (counters[app] == null) counters[app] = ShortsCounter()
            WellbeingStore.log(this, "enter $app via ${det.matched}")
            IslandModule.emit("shortsState", mapOf("onFeed" to true, "app" to app))
        }
        if (ShortsPolicy.shouldBlock(app, cfg.blockIg, cfg.blockYt, cfg.strict, snoozeUntil, now)) {
            removePillView()
            showShield(app)
            if (cfg.autoLeave && !escapeChain) {
                escapeChain = true
                h.postDelayed(escapeRun, 700)
            }
            return
        }
        if (shield != null) {
            removeShieldView()
            escapeChain = false
            h.removeCallbacks(escapeRun)
        }
        if (!cfg.counts(app)) {
            removePillView()
            return
        }
        val ctr = counters[app] ?: return
        var counted = ctr.observe(det.sig, now)
        if (app == ShortsApp.YT) {
            swipe.noteSig(det.sig, now)
            if (counted && ytFirst) {
                if (ytMode != 1 && now - ytFirstAt < 2500)
                        counted =
                                false // loading / play-state changes right after opening are not a
                // new Short
                else if (ytMode == 0)
                        counted =
                                false // nothing identifies a page: later Shorts come from the swipe
                // path
                else if (ytMode == 2 && ytScrollSeen && now - lastScrollEventAt > 2500)
                        counted = false // text alone must be backed by a scroll
            }
            if (counted)
                    WellbeingStore.log(
                            this,
                            "yt counted: key=" +
                                    (if (ytMode == 1) "position"
                                    else if (ytMode == 2) "text" else "none") +
                                    " positionsSeen=" +
                                    ytRows.size
                    )
        }
        var approx = false
        if (det.sig == null) {
            noSig++
            if (noSig == 3 && !signatureWarned) {
                signatureWarned = true
                WellbeingStore.log(
                        this,
                        "$app: feed matched but no item text exposed -> approximate scroll counting"
                )
            }
            if (noSig >= 3 && scrollSeen > 0 && now - scrollSeen < 1500 && ctr.observeScroll(now)) {
                counted = true
                approx = true
                scrollSeen = 0
            }
        } else noSig = 0
        if (counted) record(app, approx)
        showPill(app)
        if (ctr.pending) scheduleEval(ctr.settleDelayMs + 40)
    }

    private fun leaveFeed(reason: String) {
        h.removeCallbacks(escapeRun)
        h.removeCallbacks(settleRun)
        swipe.exit()
        ytFirst = false
        ytMode = 0
        ytRows.clear()
        ytScrollSeen = false
        if (onFeed) {
            WellbeingStore.log(this, "leave $curApp ($reason)")
            counters[curApp ?: ""]?.exit()
            IslandModule.emit("shortsState", mapOf("onFeed" to false, "app" to ""))
        }
        onFeed = false
        curApp = null
        misses = 0
        noSig = 0
        escapeTries = 0
        escapeChain = false
        lastShown = -1
        removeShieldView()
        removePillView()
    }

    // ------------------------------------------------------------------ YouTube Shorts: count
    // swipes
    /**
     * Scroll events of the Shorts pager (not of comments, descriptions or other lists, whose view
     * ids do not mention reel/shorts).
     */
    private fun onYoutubeScroll(e: AccessibilityEvent) {
        if (!cfg.counts(ShortsApp.YT)) return
        val src =
                try {
                    e.source
                } catch (x: Exception) {
                    null
                }
        var id: String? = null
        if (src != null) {
            id = src.viewIdResourceName
            rec(src)
        }
        if (id != null && !(id.contains("reel") || id.contains("shorts")))
                return // comments, description, other lists
        val now = System.currentTimeMillis()
        lastScrollEventAt = now
        ytScrollSeen = true
        if (!onFeed || curApp != ShortsApp.YT) {
            // The Shorts pager itself is scrolling, so we ARE on the feed even if the tree lookup
            // missed it a moment ago.
            if (id == null ||
                            ShortsPolicy.shouldBlock(
                                    ShortsApp.YT,
                                    cfg.blockIg,
                                    cfg.blockYt,
                                    cfg.strict,
                                    snoozeUntil,
                                    now
                            )
            )
                    return
            onDetection(Detection(ShortsApp.YT, true, null, "scroll-event"))
            if (!onFeed) return
        }
        if (!swipe.burstOpen)
                WellbeingStore.log(
                        this,
                        "yt scroll src=" +
                                (id ?: "?").substringAfter(":id/") +
                                " items=" +
                                e.itemCount +
                                " from=" +
                                e.fromIndex +
                                " y=" +
                                e.scrollY
                )
        swipe.onScroll(now, if (e.itemCount > 0 && e.fromIndex >= 0) e.fromIndex else -1)
        h.removeCallbacks(settleRun)
        h.postDelayed(settleRun, 830)
    }

    private fun settleSwipe() {
        if (!onFeed || curApp != ShortsApp.YT) return
        val now = System.currentTimeMillis()
        if (now - lastScrollEventAt < 800) {
            h.postDelayed(settleRun, 820 - (now - lastScrollEventAt))
            return
        }
        if (ShortsPolicy.shouldBlock(
                        ShortsApp.YT,
                        cfg.blockIg,
                        cfg.blockYt,
                        cfg.strict,
                        snoozeUntil,
                        now
                )
        )
                return
        evaluate() // refresh the item signature first so "moved / came back" evidence is current
        if (!onFeed || curApp != ShortsApp.YT) return
        val r = swipe.settle(System.currentTimeMillis())
        val trusted =
                ytMode == 1 &&
                        ytRows.size >=
                                2 // list positions are changing: they count swipes exactly, this
        // path is only a safety net
        val already =
                lastRecordAt >= swipe.burstStartAt - 100 // another path already counted this swipe
        if (r != SwipeCounter.NONE && (trusted || already || now - feedEnterAt < 1500)) {
            WellbeingStore.log(
                    this,
                    "yt swipe ignored: " +
                            (if (trusted) "positions trusted"
                            else if (already) "already counted" else "just opened")
            )
        } else if (r != SwipeCounter.NONE) {
            WellbeingStore.log(this, "yt swipe " + swipe.note)
            record(ShortsApp.YT, r == SwipeCounter.APPROX)
            showPill(ShortsApp.YT)
        } else WellbeingStore.log(this, "yt swipe " + swipe.note)
    }

    // ------------------------------------------------------------------ counting
    private fun record(app: String, approx: Boolean) {
        if (app == ShortsApp.YT) {
            if (!ytFirst) ytFirstAt = System.currentTimeMillis()
            ytFirst = true
        }
        lastRecordAt = System.currentTimeMillis()
        val before = WellbeingStore.today(this)
        val after = WellbeingStore.addCount(this, app, approx)
        val prevTotal = before[0] + before[1]
        val total = after[0] + after[1]
        val idx = if (app == ShortsApp.IG) 0 else 1
        val prevShown = if (cfg.modeTotal) prevTotal else before[idx]
        val shown = if (cfg.modeTotal) total else after[idx]
        Milestones.crossed(prevShown, shown, cfg.tiers)?.let {
            if (cfg.textMode == "milestone") {
                milestoneUntil = System.currentTimeMillis() + 3500
                h.removeCallbacks(milestoneRun)
                h.postDelayed(milestoneRun, 3600)
            }
        }
        IslandModule.emit("shortsCount", mapOf("ig" to after[0], "yt" to after[1]))
        when (ShortsPolicy.goalNudge(prevTotal, total, cfg.goal, cfg.remindEvery)) {
            1 -> nudge("😺 Daily goal reached", "$total Reels/Shorts today")
            2 -> nudge("😿 $total today", "${total - cfg.goal} over your goal")
        }
    }

    private fun nudge(title: String, sub: String) {
        val now = System.currentTimeMillis()
        IslandBus.post(
                Act(
                        "shorts_goal",
                        55,
                        title,
                        sub,
                        expireAt = now + 6000,
                        expand = true,
                        pulse = now
                )
        )
    }

    private fun shownCount(app: String): Int {
        val t = WellbeingStore.today(this)
        return if (cfg.modeTotal) t[0] + t[1] else if (app == ShortsApp.IG) t[0] else t[1]
    }

    // ------------------------------------------------------------------ floating counter pill
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun landscape() =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun showPill(app: String) {
        if (cfg.hideLandscape && landscape()) {
            removePillView()
            return
        }
        ensurePill()
        val n = shownCount(app)
        val bump = lastShown >= 0 && n != lastShown
        lastShown = n
        updatePillText(n)
        if (bump)
                pill?.let { v ->
                    v.animate().cancel()
                    v.animate()
                            .scaleX(1.14f)
                            .scaleY(1.14f)
                            .setDuration(110)
                            .withEndAction {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                            }
                            .start()
                }
    }

    private fun updatePillText(n: Int = lastShown) {
        if (n < 0) return
        val tier = Milestones.pick(n, cfg.tiers)
        pillEmoji?.text = tier?.emoji ?: "🐱"
        pillCount?.text = n.toString()
        val wantText =
                tier != null &&
                        tier.text.isNotEmpty() &&
                        (cfg.textMode == "always" ||
                                (cfg.textMode == "milestone" &&
                                        System.currentTimeMillis() < milestoneUntil))
        pillText?.visibility = if (wantText) View.VISIBLE else View.GONE
        pillText?.text = tier?.text ?: ""
    }

    private fun ensurePill() {
        if (pill != null) return
        val s = cfg.scale
        val box =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10f * s), dp(5f * s), dp(12f * s), dp(5f * s))
                    background =
                            GradientDrawable().apply {
                                cornerRadius = dp(40f).toFloat()
                                setColor(cfg.bg)
                                setStroke(1, 0x33FFFFFF)
                            }
                    alpha = 0f
                    translationY = -dp(14f).toFloat()
                    scaleX = 0.85f
                    scaleY = 0.85f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
        val em =
                TextView(this).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * s)
                    setPadding(0, 0, dp(6f * s), 0)
                }
        val ct =
                TextView(this).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * s)
                    setTextColor(cfg.fg)
                    typeface = Typeface.DEFAULT_BOLD
                }
        val tx =
                TextView(this).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * s)
                    setTextColor(cfg.fg)
                    alpha = 0.85f
                    setPadding(dp(8f * s), 0, 0, 0)
                    visibility = View.GONE
                    maxLines = 1
                }
        box.addView(em)
        box.addView(ct)
        box.addView(tx)
        val ins = insets()
        val lp =
                WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = ins[0] + dp(cfg.yDp.toFloat())
        lp.x = ((cfg.fx - 0.5f) * (resources.displayMetrics.widthPixels - dp(24f))).toInt()
        try {
            wm.addView(box, lp)
            pill = box
            pillEmoji = em
            pillCount = ct
            pillText = tx
            box.animate()
                    .alpha(cfg.opacity)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
        } catch (e: Exception) {
            lastError = "counter: " + (e.message ?: e.javaClass.simpleName)
            WellbeingStore.log(this, lastError)
        }
    }

    private fun removePillView(animate: Boolean = true) {
        val v = pill ?: return
        pill = null
        pillEmoji = null
        pillCount = null
        pillText = null
        val kill = {
            try {
                wm.removeViewImmediate(v)
            } catch (e: Exception) {}
        }
        if (animate)
                v.animate()
                        .alpha(0f)
                        .translationY(-dp(10f).toFloat())
                        .setDuration(160)
                        .withEndAction { kill() }
                        .start()
        else kill()
    }

    // ------------------------------------------------------------------ blocking shield
    private fun showShield(app: String) {
        if (shield != null) return
        val strict = cfg.strict
        val name = if (app == ShortsApp.IG) "Instagram Reels" else "YouTube Shorts"
        val root =
                FrameLayout(this).apply {
                    setBackgroundColor(0xFA0E0B16.toInt())
                    isClickable = true
                    alpha = 0f
                }
        val col =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(28f), dp(20f), dp(28f), dp(20f))
                }
        val resId = resources.getIdentifier("meow_cat_angry", "drawable", packageName)
        if (resId != 0)
                col.addView(
                        ImageView(this).apply {
                            setImageResource(resId)
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                        LinearLayout.LayoutParams(dp(150f), dp(150f))
                )
        else
                col.addView(
                        TextView(this).apply {
                            text = "😾"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
                            gravity = Gravity.CENTER
                        }
                )
        col.addView(
                TextView(this).apply {
                    text = "$name ${if (app == ShortsApp.IG) "are" else "is"} blocked"
                    setTextColor(0xFFF4F0FA.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14f), 0, dp(6f))
                }
        )
        col.addView(
                TextView(this).apply {
                    text =
                            if (strict)
                                    "Strict mode is on, so this feed stays closed. Everything else in the app still works."
                            else
                                    "You asked Meow Island to keep this feed closed. Everything else in the app still works."
                    setTextColor(0xFFA79DBA.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(22f))
                }
        )
        col.addView(
                button("Back to normal content", 0xFFB79CFF.toInt(), 0xFF1A1128.toInt()) {
                    escapeChain = true
                    escapeStep(true)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f))
        )
        if (!strict)
                col.addView(
                        button("Pause for ${cfg.pauseMin} min", 0x00000000, 0xFFB79CFF.toInt()) {
                            snoozeUntil = System.currentTimeMillis() + cfg.pauseMin * 60_000L
                            WellbeingStore.log(this, "shield paused ${cfg.pauseMin} min")
                            removeShieldView()
                            escapeChain = false
                            h.removeCallbacks(escapeRun)
                            scheduleEval(0)
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f))
                                .also { it.topMargin = dp(10f) }
                )
        root.addView(
                col,
                FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                )
        )

        val ins = insets()
        // Covers the app's content area only: the status bar and navigation bar stay visible and
        // usable.
        val lp =
                WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        maxOf(dp(200f), realHeight() - ins[0] - ins[1]),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.y = ins[0]
        try {
            wm.addView(root, lp)
            shield = root
            root.animate().alpha(1f).setDuration(120).start()
            WellbeingStore.log(this, "shield shown ($app)")
            WellbeingStore.addBlockedAttempt(this)
        } catch (e: Exception) {
            lastError = "shield: " + (e.message ?: e.javaClass.simpleName)
            WellbeingStore.log(this, lastError)
        }
    }

    private fun button(label: String, fill: Int, textColor: Int, onClick: () -> Unit) =
            Button(this).apply {
                text = label
                isAllCaps = false
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                minHeight = dp(48f)
                background =
                        GradientDrawable().apply {
                            cornerRadius = dp(16f).toFloat()
                            setColor(fill)
                            if (fill == 0) setStroke(dp(1f), 0xFFB79CFF.toInt())
                        }
                setOnClickListener { onClick() }
            }

    private fun removeShieldView() {
        val v = shield ?: return
        shield = null
        try {
            wm.removeViewImmediate(v)
        } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------ getting out of the feed
    /**
     * Strongest supported escalation, one step per call: (1) tap the app's own Home tab, (2) Back,
     * (3) the system Home screen. The shield stays up for as long as the feed is still detected,
     * whatever these steps achieve.
     */
    private fun escapeStep(chain: Boolean) {
        h.removeCallbacks(escapeRun)
        val app = curApp ?: return
        escapeTries++
        val ok =
                when (escapeTries) {
                    1 -> clickHome(app)
                    2 -> performGlobalAction(GLOBAL_ACTION_BACK)
                    else -> performGlobalAction(GLOBAL_ACTION_HOME)
                }
        WellbeingStore.log(this, "escape step $escapeTries ok=$ok")
        scheduleEval(500)
        if (chain && escapeTries < 4) h.postDelayed(escapeRun, 1200)
    }

    private fun clickHome(app: String): Boolean {
        val root =
                try {
                    rootInActiveWindow
                } catch (e: Exception) {
                    null
                } ?: return false
        try {
            val pkg = ShortsApp.pkgOf(app)
            val bottomBand = realHeight() * 0.8
            val cands = ArrayList<AccessibilityNodeInfo>()
            if (app == ShortsApp.IG)
                    root.findAccessibilityNodeInfosByViewId("$pkg:id/feed_tab")?.let {
                        cands.addAll(it)
                    }
            root.findAccessibilityNodeInfosByText("Home")?.forEach { n ->
                val r = Rect()
                n.getBoundsInScreen(r)
                val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "")
                if (r.top >= bottomBand && label.trim().equals("Home", true)) cands.add(n)
            }
            for (n in cands) {
                var x: AccessibilityNodeInfo? = n
                var depth = 0
                while (x != null && depth < 4) {
                    if (x.isClickable &&
                                    x.isVisibleToUser &&
                                    x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    )
                            return true
                    x = x.parent
                    depth++
                }
            }
            return false
        } finally {
            rec(root)
        }
    }

    // ------------------------------------------------------------------ geometry helpers
    @Suppress("DEPRECATION")
    private fun realHeight(): Int {
        if (Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds.height()
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m.heightPixels
    }

    /** [top, bottom] system-bar heights in px. */
    private fun insets(): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val i =
                    wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                            WindowInsets.Type.systemBars()
                    )
            return intArrayOf(i.top, i.bottom)
        }
        fun dim(n: String): Int {
            val id = resources.getIdentifier(n, "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        return intArrayOf(dim("status_bar_height"), dim("navigation_bar_height"))
    }

    @Suppress("DEPRECATION")
    private fun rec(n: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < 33)
                try {
                    n?.recycle()
                } catch (e: Exception) {}
    }
}
