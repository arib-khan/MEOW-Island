package com.smartisland.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional lyrics source: LRCLIB (https://lrclib.net), a free community lyrics database with a documented public API and time-synchronised lyrics.
 * Disabled by default. When the user turns it on, the song title, artist name and length of the playing track are sent to lrclib.net over HTTPS
 * (nothing else: no account, no notification content, no device identifiers). Offline or on any error the display just says so.
 */
class LrcLibProvider : LyricsProvider {
    override fun fetch(title: String, artist: String, durationSec: Int): LyricsResult {
        if (title.isBlank()) return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, emptyList())
        var conn: HttpURLConnection? = null
        try {
            val q = StringBuilder("https://lrclib.net/api/get?track_name=").append(URLEncoder.encode(title, "UTF-8"))
                .append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
            if (durationSec > 0) q.append("&duration=").append(durationSec)
            conn = URL(q.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "MeowIsland/1.0 (Android lyrics display)")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 404) return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, emptyList())
            if (code != 200) return LyricsResult(DeviceProtocol.LY_OFFLINE, emptyList())
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val j = JSONObject(body)
            val synced = if (j.isNull("syncedLyrics")) "" else j.optString("syncedLyrics", "")
            val lines = Lrc.parse(synced)
            if (lines.isNotEmpty()) return LyricsResult(DeviceProtocol.LY_AVAILABLE, lines)
            val plain = if (j.isNull("plainLyrics")) "" else j.optString("plainLyrics", "")
            return LyricsResult(if (plain.isNotBlank()) DeviceProtocol.LY_UNSYNCED else DeviceProtocol.LY_UNAVAILABLE, emptyList())
        } catch (e: IOException) {
            return LyricsResult(DeviceProtocol.LY_OFFLINE, emptyList())
        } catch (e: Exception) {
            return LyricsResult(DeviceProtocol.LY_OFFLINE, emptyList())
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }
}
