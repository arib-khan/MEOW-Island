package com.smartisland.app

/** Pure (Android-free) decisions for the Dancing Cat music widget, so they can be unit-tested on the JVM. */
enum class MusicMode { PLAYING, PAUSED, IDLE, NO_ACCESS }

class MusicView(val mode: MusicMode, val title: String, val artist: String, val animate: Boolean, val showArt: Boolean, val sig: String)

object MusicWidgetLogic {
    fun mode(hasAccess: Boolean, active: Boolean, playing: Boolean): MusicMode = when {
        !hasAccess -> MusicMode.NO_ACCESS     // without notification access Android does not tell us what is playing
        !active -> MusicMode.IDLE
        playing -> MusicMode.PLAYING
        else -> MusicMode.PAUSED
    }

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The cat dances ONLY while a media session is actively playing and Dancing Cat Mode is on. Missing metadata gets a plain
     * fallback instead of blank text. [artKey] identifies the artwork so a changed cover redraws the widget.
     */
    fun view(hasAccess: Boolean, active: Boolean, playing: Boolean, title: String?, artist: String?, hasArt: Boolean, dancingOn: Boolean, artKey: Int = 0): MusicView {
        val m = mode(hasAccess, active, playing)
        val t = when (m) { MusicMode.NO_ACCESS -> "Allow notification access"; MusicMode.IDLE -> "Nothing playing"; else -> clean(title) ?: "Unknown track" }
        val a = when (m) { MusicMode.NO_ACCESS -> "to show what\u2019s playing"; MusicMode.IDLE -> "Play music in any app"; else -> clean(artist) ?: "Unknown artist" }
        val animate = m == MusicMode.PLAYING && dancingOn
        val art = hasArt && (m == MusicMode.PLAYING || m == MusicMode.PAUSED)
        return MusicView(m, t, a, animate, art, "$m|$t|$a|$animate|$art|$artKey")
    }
}

/** Pure time helpers for the progress bar and the elapsed / total labels. */
object MusicProgress {
    /** m:ss, or h:mm:ss from one hour. Negative or absurd values never produce garbage. */
    fun fmt(ms: Long): String {
        val t = (ms.coerceIn(0L, 99L * 3600_000L)) / 1000
        val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    /** Where the track is right now: the reported position plus the time passed since it was reported, only while playing. */
    fun now(posMs: Long, reportedAt: Long, playing: Boolean, nowMs: Long, durMs: Long): Long {
        var p = posMs
        if (playing && reportedAt > 0 && nowMs > reportedAt) p += nowMs - reportedAt
        return if (durMs > 0) p.coerceIn(0L, durMs) else maxOf(0L, p)
    }

    fun fraction(posMs: Long, durMs: Long): Float = if (durMs <= 0) 0f else (posMs.toFloat() / durMs).coerceIn(0f, 1f)

    /** The bar and times are shown only when the player reports both a real position and a real length (live streams have neither). */
    fun hasProgress(withSession: Boolean, posMs: Long, durMs: Long): Boolean = withSession && posMs >= 0 && durMs > 0
}
