import com.smartisland.app.DeviceProtocol as P
import com.smartisland.app.ImageSender
import com.smartisland.app.Lrc
import java.io.ByteArrayOutputStream
import java.util.Random

// Called from DeviceProtocolTest.kt's main(): LRC parsing and the image sender against a simulated lossy device.

private fun u32(f: ByteArray, at: Int): Long = (f[at].toLong() and 0xFF) or ((f[at + 1].toLong() and 0xFF) shl 8) or ((f[at + 2].toLong() and 0xFF) shl 16) or ((f[at + 3].toLong() and 0xFF) shl 24)
private fun u16(f: ByteArray, at: Int): Int = (f[at].toInt() and 0xFF) or ((f[at + 1].toInt() and 0xFF) shl 8)

/** Mirrors firmware/MeowIsland/image_rx.h closely enough to exercise the sender: in-order chunks only, CRC check at END, same ids/states/reasons. */
private class SimDevice(var storedCrc: Long = 0L) {
    var id = 0; var kind = 0; var state = P.XFER_IDLE; var next = 0; var reason = 0; var total = 0; var want = 0L
    var buf = ByteArrayOutputStream()
    fun status() = P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, state, id, next, reason, storedCrc)
    fun onFrame(f: ByteArray) {
        val type = f[1].toInt() and 0xFF; val p = f.copyOfRange(3, f.size)
        when (type) {
            P.MSG_IMG_BEGIN -> {
                val xid = p[0].toInt() and 0xFF
                if (state == P.XFER_RECEIVING && xid == id) return                 // a repeated BEGIN keeps the progress
                id = xid; kind = p[1].toInt(); total = u32(p, 2).toInt(); want = u32(p, 6); next = 0; reason = 0; buf = ByteArrayOutputStream()
                state = if (kind == P.KIND_CUSTOM && storedCrc != 0L && storedCrc == want) P.XFER_DONE else P.XFER_RECEIVING
            }
            P.MSG_IMG_DATA -> if (state == P.XFER_RECEIVING && (p[0].toInt() and 0xFF) == id && u16(p, 1) == next) { buf.write(p, 3, p.size - 3); next++ }
            P.MSG_IMG_END -> if (state == P.XFER_RECEIVING && (p[0].toInt() and 0xFF) == id) {
                val got = buf.toByteArray()
                if (got.size != total) { state = P.XFER_FAILED; reason = 6 }
                else if (P.crc32(got) != want) { state = P.XFER_FAILED; reason = 5 }
                else { state = P.XFER_DONE; if (kind == P.KIND_CUSTOM) storedCrc = want }
            }
            P.MSG_IMG_CTRL -> if (p[0].toInt() == P.CTRL_ABORT && state == P.XFER_RECEIVING) state = P.XFER_IDLE
        }
    }
}

private class Outcome(var ok: Boolean? = null, var reason: Int = -1, var message: String = "", var lastProgress: Int = 0)

private fun runTransfer(data: ByteArray, kind: Int, dev: SimDevice, drop: Double, dup: Double, seed: Long, wantStatusPush: Boolean = true): Outcome {
    val rnd = Random(seed); val res = Outcome(); var now = 1000L
    val wire = ArrayList<ByteArray>()
    val s = ImageSender(9, kind, data, 240, 320, { wire.add(it) }, { ok, reason, msg -> res.ok = ok; res.reason = reason; res.message = msg }, { d, _ -> res.lastProgress = d })
    s.start(now)
    var steps = 0
    while (!s.done && steps++ < 20000) {
        now += 250
        val batch = ArrayList(wire); wire.clear()
        for (f in batch) { if (rnd.nextDouble() < drop) continue; dev.onFrame(f); if (rnd.nextDouble() < dup) dev.onFrame(f) }
        if (wantStatusPush) s.onStatus(dev.status(), now)
        s.tick(now)
    }
    return res
}

fun extrasTests() {
    // ---- LRC ----
    val lrc = "[ar:Someone]\n[ti:Song]\n[00:12.50]First line\n[00:20.00]Second line\n[01:05.5]Third\n[00:30.00][00:40.00]Repeat\n[02:00.00]\n"
    val l = Lrc.parse(lrc)
    check(l.size == 6 && l[0].text == "First line" && l[0].ms == 12_500L && l[4].ms == 65_500L && l[5].text == "", "lrc: tags skipped, multiple timestamps expanded, sorted")
    check(Lrc.indexAt(l, 0) == -1 && Lrc.indexAt(l, 12_500) == 0 && Lrc.indexAt(l, 19_999) == 0 && Lrc.indexAt(l, 20_000) == 1 && Lrc.indexAt(l, 9_999_999) == 5, "lrc: current line follows the position")
    check(Lrc.neighbour(l, 1, -1) == "First line" && Lrc.neighbour(l, 4, 1) == "" && Lrc.neighbour(l, 0, -1) == "", "lrc: neighbours skip blank gaps and stop at the ends")
    check(Lrc.parse("just\nplain\nlyrics").isEmpty() && Lrc.parse("[00:01.00]only one").isEmpty() && Lrc.parse(null).isEmpty() && Lrc.parse("").isEmpty(), "lrc: text without timestamps is NOT treated as synchronised")
    check(Lrc.parse("[00:01.5]a\n[00:02.50]b")[0].ms == 1500L && Lrc.parse("[00:01]a\n[00:02:25]b")[1].ms == 2250L, "lrc: fraction lengths")

    // ---- image sender over hostile links ----
    val data = ByteArray(5000) { (it * 31 + 7).toByte() }
    val crc = P.crc32(data)
    run { val dev = SimDevice(); val r = runTransfer(data, P.KIND_CUSTOM, dev, 0.0, 0.0, 1); check(r.ok == true && dev.storedCrc == crc && dev.buf.toByteArray().contentEquals(data), "sender: clean link delivers the exact bytes") }
    for (seed in 1L..20L) {
        val dev = SimDevice(); val r = runTransfer(data, P.KIND_CUSTOM, dev, 0.25, 0.2, seed)
        check(r.ok == true && dev.buf.toByteArray().contentEquals(data), "sender: 25% loss and 20% duplicates still end with the exact bytes (seed $seed)")
    }
    run { val dev = SimDevice(storedCrc = crc); val r = runTransfer(data, P.KIND_CUSTOM, dev, 0.0, 0.0, 3); check(r.ok == true && dev.buf.size() == 0, "sender: a design the device already has is acknowledged without sending data") }
    run { val dev = SimDevice(); val bad = data.copyOf(); bad[10] = 0; val s2 = runTransfer(bad, P.KIND_CUSTOM, dev, 0.0, 0.0, 4); check(s2.ok == true, "sender: sanity (different content is fine)") }
    run { val dev = SimDevice(); val r = runTransfer(data, P.KIND_CUSTOM, dev, 1.0, 0.0, 5); check(r.ok == false && r.reason == ImageSender.R_STALLED, "sender: a device that never answers ends as 'stopped responding', not an endless loop") }
    run {   // a device that reports a failure (e.g. CRC)
        var failed: Int? = null
        val s = ImageSender(4, P.KIND_CUSTOM, data, 240, 320, { }, { ok, reason, _ -> if (!ok) failed = reason })
        s.start(1000)
        s.onStatus(P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, P.XFER_FAILED, 4, 0, 3, 0L), 1500)
        check(failed == 3 && s.done, "sender: device failure reason is passed on")
        s.onStatus(P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, P.XFER_DONE, 4, 0, 0, crc), 1600)
        check(failed == 3, "sender: nothing happens after the transfer ended")
    }
    run {   // a status of an older transfer is ignored; DONE with a different stored crc is not a success for the custom screen
        var res: Boolean? = null
        val s = ImageSender(4, P.KIND_CUSTOM, data, 240, 320, { }, { ok, _, _ -> res = ok })
        s.start(0); s.onStatus(P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, P.XFER_DONE, 3, 0, 0, crc), 10)
        check(res == null, "sender: a stale DONE of another transfer id is ignored")
        s.onStatus(P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, P.XFER_DONE, 4, 0, 0, crc + 1), 20)
        check(res == false, "sender: DONE with a different stored checksum is not reported as success")
    }
    run { var lost = false; val s = ImageSender(2, P.KIND_ART, ByteArray(100), 80, 80, { }, { ok, _, msg -> lost = !ok && msg.contains("connection")}); s.start(0); s.linkLost(); check(lost && s.done, "sender: link loss ends the transfer with an explanation") }
    run { val dev = SimDevice(); val r = runTransfer(ByteArray(80 * 80 * 2) { it.toByte() }, P.KIND_ART, dev, 0.2, 0.1, 8); check(r.ok == true && dev.storedCrc == 0L, "sender: album art transfers and does not touch the stored custom design") }
}
