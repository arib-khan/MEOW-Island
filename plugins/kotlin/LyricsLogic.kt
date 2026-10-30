package com.smartisland.app

/**
 * Lyrics, without any Android or network code (unit-tested on a desktop): parsing synchronised LRC text and picking the line for a playback position.
 * Only text that carries real timestamps counts as synchronised. Plain text is reported as UNSYNCED and is never displayed as if it were in time.
 */
object Lrc {
    class Line(val ms: Long, val text: String)

    private val TS = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val META = Regex("""^\[[A-Za-z]{2,}:.*]$""")           // [ar:..] [ti:..] [by:..] and friends: not lyrics

    /** Lines sorted by time. Empty when the text has fewer than two timestamped lines (= not synchronised). */
    fun parse(text: String?): List<Line> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<Line>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || META.matches(line)) continue
            val stamps = TS.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val body = line.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out.add(Line(min * 60_000L + sec * 1000L + ms, body))
            }
        }
        out.sortBy { it.ms }
        return if (out.size >= 2) out else emptyList()
    }

    /** Index of the line that is current at [posMs] (the last one whose time has come), or -1 before the first line. */
    fun indexAt(lines: List<Line>, posMs: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (lines[mid].ms <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        return ans
    }

    /** Non-blank text of a neighbouring line (instrumental gaps are skipped), or "". */
    fun neighbour(lines: List<Line>, idx: Int, dir: Int): String {
        var i = idx + dir
        while (i >= 0 && i < lines.size) { if (lines[i].text.isNotBlank()) return lines[i].text; i += dir }
        return ""
    }
}

/** What a lyrics source answered for one track. */
class LyricsResult(val state: Int, val lines: List<Lrc.Line>)

/**
 * A lyrics source. Nothing is built in that scrapes Spotify or any other protected service: Android's media session does not carry lyrics, and
 * Spotify has no public lyrics API. Implementations must be legitimate, documented sources (see [LrcLibProvider], which is OFF unless the user opts in).
 * [fetch] blocks: call it from a background thread.
 */
interface LyricsProvider {
    fun fetch(title: String, artist: String, durationSec: Int): LyricsResult
}
