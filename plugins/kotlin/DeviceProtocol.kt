package com.smartisland.app

import java.text.Normalizer

/**
 * Phone side of the Meow Island link protocol. Pure Kotlin (no android.* imports) so it is unit-tested on a desktop
 * (tests/kotlin/run_device.sh) and cross-checked against the firmware's protocol.h with tests/device_vectors.txt.
 * Frame = 0xA5 | type | len | payload. All integers little-endian. Keep this file and firmware/MeowIsland/protocol.h in sync.
 */
object DeviceProtocol {
    const val PROTO = 1
    const val MAGIC = 0xA5
    const val SERVICE_UUID = "4d656f77-1000-4a3b-9c2d-0e7f6a5b4c3d"
    const val RX_UUID = "4d656f77-1001-4a3b-9c2d-0e7f6a5b4c3d"       // phone -> device, write (encrypted + authenticated link required)
    const val STATUS_UUID = "4d656f77-1002-4a3b-9c2d-0e7f6a5b4c3d"   // device -> phone, read + notify (same protection)
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val MSG_HELLO = 0x01; const val MSG_SCREEN = 0x02; const val MSG_STEPS = 0x03; const val MSG_MUSIC = 0x04
    const val MSG_WIFI_SET = 0x05; const val MSG_WIFI_CLEAR = 0x06; const val MSG_UNPAIR = 0x07; const val MSG_TIME = 0x08

    const val SCREEN_CLOCK = 0; const val SCREEN_STEPS = 1; const val SCREEN_MUSIC = 2
    const val STEP_CELLS = 54
    const val MAX_TITLE = 48; const val MAX_ARTIST = 40
    const val STATUS_LEN = 10

    fun frame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 200) { "payload too large" }
        return byteArrayOf(MAGIC.toByte(), type.toByte(), payload.size.toByte()) + payload
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun hello() = frame(MSG_HELLO, byteArrayOf(PROTO.toByte()))
    fun screen(id: Int) = frame(MSG_SCREEN, byteArrayOf(id.coerceIn(0, 2).toByte()))
    fun time(epochSec: Long, tzMinutes: Int) = frame(MSG_TIME, le32(epochSec) + le16(tzMinutes.coerceIn(-840, 840)))
    fun wifiClear() = frame(MSG_WIFI_CLEAR)
    fun unpair(clearWifi: Boolean) = frame(MSG_UNPAIR, byteArrayOf(if (clearWifi) 1 else 0))

    /** SSID 1..32 bytes, password empty (open network) or 8..63 bytes, exactly what the firmware accepts. Returns null when invalid. */
    fun wifiSet(ssid: String, pass: String): ByteArray? {
        val s = ssid.toByteArray(Charsets.UTF_8); val p = pass.toByteArray(Charsets.UTF_8)
        if (s.isEmpty() || s.size > 32) return null
        if (p.isNotEmpty() && p.size < 8 || p.size > 63) return null
        return frame(MSG_WIFI_SET, byteArrayOf(s.size.toByte()) + s + byteArrayOf(p.size.toByte()) + p)
    }

    /** levels: STEP_CELLS values 0..4 (oldest first, last = today), packed two per byte, low nibble first. */
    fun packLevels(levels: IntArray): ByteArray {
        val out = ByteArray(27)
        for (i in 0 until STEP_CELLS) {
            val v = (if (i < levels.size) levels[i] else 0).coerceIn(0, 4)
            out[i / 2] = (out[i / 2].toInt() or (if (i % 2 == 0) v else v shl 4)).toByte()
        }
        return out
    }

    class Steps(val steps: Long, val goal: Long, val hasSteps: Boolean, val goalOn: Boolean, val heatOn: Boolean, val happy: Boolean,
                val label: Int, val hint: Int, val subtitle: Int, val levels: IntArray)

    fun steps(s: Steps): ByteArray {
        val flags = (if (s.hasSteps) 1 else 0) or (if (s.goalOn) 2 else 0) or (if (s.heatOn) 4 else 0) or (if (s.happy) 8 else 0)
        return frame(MSG_STEPS, le32(s.steps.coerceIn(0, 4_000_000_000L)) + le32(s.goal.coerceIn(0, 10_000_000L)) +
            byteArrayOf(flags.toByte(), s.label.toByte(), s.hint.toByte(), s.subtitle.toByte()) + packLevels(s.levels))
    }

    /**
     * The TFT fonts only have printable ASCII, so text is reduced to that: accents are stripped (é -> e), typographic quotes/dashes
     * become plain ones, everything else (Devanagari, CJK, emoji...) is dropped. Returns "" when nothing readable is left.
     */
    fun ascii(text: String?, maxBytes: Int): String {
        if (text.isNullOrBlank()) return ""
        var t = text.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2013', '-').replace('\u2014', '-').replace("\u2026", "...").replace('\u00A0', ' ')
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val sb = StringBuilder(); var lastSpace = true
        for (ch in t) {
            val c = if (ch.code in 0x20..0x7E) ch else ' '
            if (c == ' ') { if (lastSpace) continue; lastSpace = true } else lastSpace = false
            if (sb.length >= maxBytes) break
            sb.append(c)
        }
        return sb.toString().trimEnd()
    }

    fun music(session: Boolean, playing: Boolean, posSec: Int, durSec: Int, title: String?, artist: String?): ByteArray {
        val t = ascii(title, MAX_TITLE).toByteArray(Charsets.US_ASCII); val a = ascii(artist, MAX_ARTIST).toByteArray(Charsets.US_ASCII)
        val flags = (if (session) 1 else 0) or (if (playing) 2 else 0)
        return frame(MSG_MUSIC, byteArrayOf(flags.toByte()) + le16(posSec.coerceIn(0, 65535)) + le16(durSec.coerceIn(0, 65535)) +
            byteArrayOf(t.size.toByte()) + t + byteArrayOf(a.size.toByte()) + a)
    }

    /** Splits a byte stream into BLE writes of at most [size] bytes (the device reassembles frames from the stream). */
    fun chunks(data: ByteArray, size: Int): List<ByteArray> {
        val n = size.coerceAtLeast(20); val out = ArrayList<ByteArray>()
        var i = 0
        while (i < data.size) { out.add(data.copyOfRange(i, minOf(data.size, i + n))); i += n }
        return out
    }

    class Status(val proto: Int, val fwMajor: Int, val fwMinor: Int, val screen: Int, val wifiState: Int, val wifiReason: Int,
                 val timeValid: Boolean, val pairingOpen: Boolean, val hasWifi: Boolean, val stepsFresh: Boolean, val musicFresh: Boolean,
                 val bonds: Int, val heapKb: Int)

    fun parseStatus(b: ByteArray?): Status? {
        if (b == null || b.size < STATUS_LEN) return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (u(0) != PROTO) return null
        val f = u(6)
        return Status(u(0), u(1), u(2), u(3).coerceIn(0, 2), u(4).coerceIn(0, 3), u(5), f and 1 != 0, f and 2 != 0, f and 4 != 0, f and 8 != 0, f and 16 != 0, u(7), u(8))
    }

    fun wifiReasonText(code: Int) = when (code) { 1 -> "That Wi-Fi network was not found"; 2 -> "The Wi-Fi password was not accepted"; 3 -> "The Wi-Fi network did not answer in time"; 4 -> "The Wi-Fi connection failed"; else -> "" }
}
