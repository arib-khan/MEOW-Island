package com.smartisland.app

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure logic (no android.* imports) for the Reels / Shorts counter and blocker, so it can be unit-tested on a desktop JVM
 * (tests/kotlin/run.sh). The AccessibilityService only converts Android nodes to [NodeInfo] and feeds them in.
 */
object ShortsApp {
    const val IG = "ig"
    const val YT = "yt"
    const val IG_PKG = "com.instagram.android"
    const val YT_PKG = "com.google.android.youtube"
    fun forPackage(p: String?): String? = when (p) { IG_PKG -> IG; YT_PKG -> YT; else -> null }
    fun pkgOf(app: String): String = if (app == IG) IG_PKG else YT_PKG
}

/** The few attributes the detector needs from one accessibility node. No user content is ever stored; text is only hashed. */
data class NodeInfo(val id: String?, val text: String?, val desc: String?, val visible: Boolean, val top: Int, val bottom: Int)

data class Detection(val app: String?, val onFeed: Boolean, val sig: String?, val matched: String?)

/**
 * View ids the target apps currently use for their full-screen short-video pagers. These are the ONLY app-specific facts the
 * feature relies on. Instagram and YouTube may rename them in any release: when that happens detection simply reports "not on
 * feed" (nothing is counted or blocked) and the in-app diagnostics show no match. Update the lists here.
 * NOTE: Instagram calls Stories "reel_viewer_*" internally, so those ids are deliberately NOT used.
 */
object ShortsRules {
    val IG_FEED_IDS = listOf("clips_viewer_view_pager")
    val IG_SIG_IDS = listOf("clips_author_username", "clips_caption_component")
    val YT_FEED_IDS = listOf("reel_recycler", "reel_player_page_container")
    val YT_SIG_IDS = listOf("reel_channel_bar", "reel_player_page_container")
    fun feedIds(app: String) = if (app == ShortsApp.IG) IG_FEED_IDS else YT_FEED_IDS
    fun sigIds(app: String) = if (app == ShortsApp.IG) IG_SIG_IDS else YT_SIG_IDS
}

object ShortsDetector {
    /**
     * @param find returns the nodes whose view id (without the "pkg:id/" prefix) equals the given id.
     * @param centerY vertical centre of the screen: the pager keeps the neighbouring page loaded, so the node covering the
     *                centre is the one the user is actually looking at.
     */
    fun detect(pkg: String?, centerY: Int, find: (String) -> List<NodeInfo>): Detection {
        val app = ShortsApp.forPackage(pkg) ?: return Detection(null, false, null, null)
        var matched: String? = null
        for (id in ShortsRules.feedIds(app)) { if (find(id).any { it.visible }) { matched = id; break } }
        if (matched == null) return Detection(app, false, null, null)
        return Detection(app, true, signature(ShortsRules.sigIds(app), centerY, find), matched)
    }

    /** Hash of the text of the on-screen item, or null when the app exposes nothing usable (then the fallback counter is used). */
    fun signature(ids: List<String>, centerY: Int, find: (String) -> List<NodeInfo>): String? {
        val sb = StringBuilder()
        for (id in ids) {
            val cands = find(id).filter { it.visible }
            if (cands.isEmpty()) continue
            val best = cands.firstOrNull { it.top <= centerY && centerY <= it.bottom }
                ?: cands.minByOrNull { minOf(Math.abs(it.top - centerY), Math.abs(it.bottom - centerY)) }!!
            val t = ((best.text ?: "") + "|" + (best.desc ?: "")).trim()
            if (t.length > 1) sb.append(id).append('=').append(t).append(';')
        }
        return if (sb.isEmpty()) null else stableHash(sb.toString())
    }

    /** FNV-1a 64-bit. Only the hash is kept in memory (never a username or caption). */
    fun stableHash(s: String): String {
        var h = -0x340d631b7bdddcdbL
        for (ch in s) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
        return java.lang.Long.toHexString(h)
    }
}

/**
 * Turns noisy per-event observations into "a new short was watched".
 *  - an item counts only after it has been the visible item for [settleMs] (swipe animations expose mixed trees);
 *  - items seen in the last [memoryMs] (swiping back, app switch and return) are never counted twice;
 *  - leaving the feed forgets the current item but keeps the recent memory.
 */
class ShortsCounter(private val settleMs: Long = 300, private val memoryMs: Long = 90_000, private val memorySize: Int = 8, private val scrollDebounceMs: Long = 900) {
    private class Seen(val sig: String, var at: Long)
    private val recent = ArrayList<Seen>()
    private var candidate: String? = null
    private var candidateSince = 0L
    private var counted: String? = null
    private var lastScrollAt = 0L
    val settleDelayMs: Long get() = settleMs
    val pending: Boolean get() = candidate != null && candidate != counted

    fun exit() { candidate = null; counted = null }

    /** @return true exactly once for each newly watched item. */
    fun observe(sig: String?, now: Long): Boolean {
        if (sig == null) return false
        if (sig != candidate) { candidate = sig; candidateSince = now; return false }
        if (sig == counted || now - candidateSince < settleMs) return false
        counted = sig
        recent.removeAll { now - it.at > memoryMs }
        val dup = recent.firstOrNull { it.sig == sig }
        if (dup != null) { dup.at = now; return false }
        recent.add(Seen(sig, now))
        while (recent.size > memorySize) recent.removeAt(0)
        return true
    }

    /** Approximate fallback for when no item signature is available: one count per debounced scroll. */
    fun observeScroll(now: Long): Boolean {
        if (now - lastScrollAt < scrollDebounceMs) return false
        lastScrollAt = now
        return true
    }
}

data class Tier(val min: Int, val emoji: String, val text: String)

object Milestones {
    val DEFAULT = listOf(Tier(1, "😺", "Enjoying yourself"), Tier(10, "😼", "Getting hooked"), Tier(25, "😿", "Maybe take a break"), Tier(50, "🙀", "That's a lot!"), Tier(100, "😾", "Time to log off?"))
    fun pick(count: Int, tiers: List<Tier>): Tier? = tiers.filter { it.min <= count }.maxByOrNull { it.min }
    /** The highest tier whose threshold was passed by going from [prev] to [now], or null. */
    fun crossed(prev: Int, now: Int, tiers: List<Tier>): Tier? = tiers.filter { it.min in (prev + 1)..now }.maxByOrNull { it.min }
}

object ShortsPolicy {
    /** Should the shield be shown? Snooze is only honoured when strict mode is off. */
    fun shouldBlock(app: String?, blockIg: Boolean, blockYt: Boolean, strict: Boolean, snoozeUntil: Long, now: Long): Boolean {
        if (app == null) return false
        val on = if (app == ShortsApp.IG) blockIg else blockYt
        if (!on) return false
        return strict || now >= snoozeUntil
    }
    fun dayKey(ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz); c.timeInMillis = ms
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
    /** 1 = the goal was just reached, 2 = a repeat reminder every [every] items beyond it (0 = only at the goal), 0 = nothing. */
    fun goalNudge(prevTotal: Int, total: Int, goal: Int, every: Int): Int {
        if (goal <= 0 || total <= prevTotal) return 0
        if (prevTotal < goal && total >= goal) return 1
        if (every > 0 && total > goal && (total - goal) % every == 0) return 2
        return 0
    }
}
