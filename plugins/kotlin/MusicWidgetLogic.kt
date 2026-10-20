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
