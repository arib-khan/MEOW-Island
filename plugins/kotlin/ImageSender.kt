package com.smartisland.app

/**
 * Sender side of the image transfer (no Android code, so it is unit-tested on a desktop with a simulated lossy link: tests/kotlin/ImageSenderTest.kt).
 * The device only accepts chunks in order and reports in its status which chunk it expects next, so the sender keeps a small window of unacknowledged
 * chunks in flight, advances on every status update and, when nothing happens for a while, rewinds to what the device really has. A transfer ends
 * when the device says DONE (with the expected checksum for the custom screen) or FAILED, after too many stalls, or when the link is lost.
 */
class ImageSender(
    val id: Int, val kind: Int, private val data: ByteArray, private val w: Int, private val h: Int,
    private val send: (ByteArray) -> Unit,
    private val finish: (ok: Boolean, reason: Int, message: String) -> Unit,
    private val progress: (done: Int, total: Int) -> Unit = { _, _ -> }
) {
    companion object { const val WINDOW = 8; const val STALL_MS = 2500L; const val MAX_STALLS = 6; const val R_STALLED = 6; const val R_BAD_IMAGE = 7 }

    val crc: Long = DeviceProtocol.crc32(data)
    private val chunks = (data.size + DeviceProtocol.IMG_CHUNK - 1) / DeviceProtocol.IMG_CHUNK
    private var acked = 0
    private var nextToSend = 0
    private var endSent = false
    private var confirmed = false           // the device has answered our BEGIN
    private var lastProgress = 0L
    private var stalls = 0
    var done = false; private set

    private fun begin() = DeviceProtocol.imgBegin(id, kind, data.size, crc, DeviceProtocol.IMG_CHUNK, w, h)

    fun start(now: Long) { lastProgress = now; send(begin()) }

    fun onStatus(s: DeviceProtocol.Status, now: Long) {
        if (done || !s.hasExt || s.xferId != id) return
        when (s.xferState) {
            DeviceProtocol.XFER_FAILED -> end(false, s.xferReason, DeviceProtocol.xferReasonText(s.xferReason))
            DeviceProtocol.XFER_DONE ->
                if (kind == DeviceProtocol.KIND_CUSTOM && s.customCrc != crc) end(false, R_BAD_IMAGE, DeviceProtocol.xferReasonText(R_BAD_IMAGE))
                else { progress(data.size, data.size); end(true, 0, "") }
            DeviceProtocol.XFER_RECEIVING -> {
                confirmed = true
                if (s.xferNext > acked) { acked = minOf(s.xferNext, chunks); lastProgress = now; stalls = 0; progress(minOf(acked * DeviceProtocol.IMG_CHUNK, data.size), data.size) }
                pump()
            }
        }
    }

    private fun pump() {
        if (!confirmed || done) return
        if (nextToSend < acked) nextToSend = acked
        while (nextToSend < chunks && nextToSend - acked < WINDOW) {
            val off = nextToSend * DeviceProtocol.IMG_CHUNK
            send(DeviceProtocol.imgData(id, nextToSend, data, off, minOf(DeviceProtocol.IMG_CHUNK, data.size - off)))
            nextToSend++
        }
        if (acked >= chunks && !endSent) { send(DeviceProtocol.imgEnd(id)); endSent = true }
    }

    /** Call about twice a second while the transfer is active. */
    fun tick(now: Long) {
        if (done || now - lastProgress < STALL_MS) return
        lastProgress = now; stalls++
        if (stalls > MAX_STALLS) { end(false, R_STALLED, DeviceProtocol.xferReasonText(R_STALLED)); return }
        if (!confirmed) { send(begin()); return }
        nextToSend = acked; endSent = false; pump()
    }

    fun abort() { if (done) return; send(DeviceProtocol.imgCtrl(DeviceProtocol.CTRL_ABORT)); end(false, 0, "Cancelled.") }
    /** The link dropped: the device throws away the half-received data itself. */
    fun linkLost() { end(false, 0, "The connection to the device was lost during the transfer. The design that was on the display is still there.") }
    private fun end(ok: Boolean, reason: Int, msg: String) { if (done) return; done = true; finish(ok, reason, msg) }
}
