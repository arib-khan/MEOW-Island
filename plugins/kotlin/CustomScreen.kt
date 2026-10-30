package com.smartisland.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The saved custom design (an already-encoded MIMG file plus the user's HTML source, both in the app's private storage and never uploaded anywhere)
 * and the transfers of images to the device. [ImageTransfers] runs one transfer at a time on top of the existing BLE link ([DeviceLink.send]).
 */
object CustomScreen {
    const val MAX_HTML = 256 * 1024
    private const val IMG = "custom_screen.mimg"; private const val HTML = "custom_screen.html"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("meowextras", Context.MODE_PRIVATE)

    // transfer state shown in the app
    @Volatile var tState = "idle"; private set               // idle | sending | done | error
    @Volatile var tSent = 0; private set
    @Volatile var tTotal = 0; private set
    @Volatile var tMessage = ""; private set
    @Volatile private var autoTried = false

    fun onConnected() { autoTried = false }
    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean("customAuto", true)
    fun setAuto(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("customAuto", on).apply() }

    fun savedImage(ctx: Context): ByteArray? = try { File(ctx.filesDir, IMG).takeIf { it.isFile }?.readBytes() } catch (e: Exception) { null }
    fun savedHtml(ctx: Context): String? = try { File(ctx.filesDir, HTML).takeIf { it.isFile }?.readText(Charsets.UTF_8) } catch (e: Exception) { null }
    fun savedCrc(ctx: Context): Long = prefs(ctx).getString("customCrc", "0")?.toLongOrNull() ?: 0L

    /** Writes to temporary files first so a crash can never leave half a design behind. */
    fun save(ctx: Context, image: ByteArray, html: String, name: String): Long {
        val crc = DeviceProtocol.crc32(image)
        val ti = File(ctx.filesDir, "$IMG.tmp"); val th = File(ctx.filesDir, "$HTML.tmp")
        ti.writeBytes(image); th.writeText(html, Charsets.UTF_8)
        File(ctx.filesDir, IMG).delete(); File(ctx.filesDir, HTML).delete()
        if (!ti.renameTo(File(ctx.filesDir, IMG)) || !th.renameTo(File(ctx.filesDir, HTML))) throw java.io.IOException("Could not save the design.")
        prefs(ctx).edit().putString("customCrc", crc.toString()).putString("customName", name.take(80)).putLong("customSavedAt", System.currentTimeMillis()).apply()
        return crc
    }

    /** Removes the design from the phone and, when connected, from the device. The device's other screens are not touched. */
    fun remove(ctx: Context) {
        ImageTransfers.cancelKind(DeviceProtocol.KIND_CUSTOM)
        File(ctx.filesDir, IMG).delete(); File(ctx.filesDir, HTML).delete()
        prefs(ctx).edit().remove("customCrc").remove("customName").remove("customSavedAt").apply()
        if (DeviceLink.isReady()) DeviceLink.send(DeviceProtocol.imgCtrl(DeviceProtocol.CTRL_DELETE_CUSTOM))
        tState = "idle"; tSent = 0; tTotal = 0; tMessage = ""
        emit()
    }

    fun info(ctx: Context): JSONObject {
        val img = File(ctx.filesDir, IMG)
        val st = DeviceLink.lastStatus()
        return JSONObject()
            .put("hasSaved", img.isFile).put("name", prefs(ctx).getString("customName", "") ?: "").put("size", if (img.isFile) img.length() else 0L)
            .put("crc", savedCrc(ctx).toString()).put("auto", autoEnabled(ctx)).put("connected", DeviceLink.isReady())
            .put("deviceHasExt", st?.hasExt == true).put("deviceCrc", (st?.customCrc ?: 0L).toString())
            .put("state", tState).put("sent", tSent).put("total", tTotal).put("message", tMessage)
    }

    /** Starts sending the saved design. Returns an error message, or null when the transfer started. */
    fun send(ctx: Context): String? {
        val img = savedImage(ctx) ?: return "There is no saved design to send."
        if (!DeviceLink.isReady()) return "The device is not connected right now."
        if (DeviceLink.lastStatus()?.hasExt == false) return "The device firmware is too old for custom screens. Update it from the firmware folder."
        if (ImageTransfers.activeKind() == DeviceProtocol.KIND_CUSTOM) return "A transfer is already running."
        tState = "sending"; tSent = 0; tTotal = img.size; tMessage = ""; emit()
        val ok = ImageTransfers.start(DeviceProtocol.KIND_CUSTOM, img, 240, 320,
            { ok, _, msg -> tState = if (ok) "done" else "error"; tMessage = msg; if (ok) tSent = tTotal; emit() },
            { sent, total -> tSent = sent; tTotal = total; emit() })
        if (!ok) { tState = "error"; tMessage = "Could not start the transfer."; emit(); return tMessage }
        return null
    }

    /** After (re)connecting: put the saved design back on the device if it does not have this exact one. Once per connection, so a failing device cannot loop. */
    fun autoSendIfNeeded(ctx: Context, s: DeviceProtocol.Status) {
        if (!s.hasExt || autoTried || !autoEnabled(ctx) || ImageTransfers.busy()) return
        val crc = savedCrc(ctx); if (crc == 0L || savedImage(ctx) == null) return
        if (s.customCrc == crc) return
        autoTried = true
        send(ctx)
    }

    fun cancel() { ImageTransfers.cancelKind(DeviceProtocol.KIND_CUSTOM); tState = "idle"; tMessage = "Cancelled."; emit() }
    fun emit() { IslandModule.emit("customTransfer", mapOf("state" to tState, "sent" to tSent, "total" to tTotal, "message" to tMessage)) }
}

/** One image transfer at a time over [DeviceLink.send]; the status notifications from the device drive it. */
object ImageTransfers {
    private var active: ImageSender? = null
    private var activeKind = -1
    private var nextId = 1

    fun busy() = active != null
    fun activeKind() = if (active != null) activeKind else -1

    fun start(kind: Int, data: ByteArray, w: Int, h: Int, finish: (Boolean, Int, String) -> Unit, progress: (Int, Int) -> Unit): Boolean {
        if (!DeviceLink.isReady()) return false
        active?.abort()                                              // a newer transfer replaces an older one (album art never blocks the custom screen)
        val seen = DeviceLink.lastStatus()?.xferId ?: 0
        do { nextId = if (nextId >= 255) 1 else nextId + 1 } while (nextId == seen)
        lateinit var s: ImageSender
        s = ImageSender(nextId, kind, data, w, h, { DeviceLink.send(it) },
            { ok, reason, msg -> if (active === s) { active = null; activeKind = -1 }; finish(ok, reason, msg) }, progress)
        active = s; activeKind = kind
        s.start(System.currentTimeMillis())
        return true
    }

    fun onStatus(st: DeviceProtocol.Status) { active?.onStatus(st, System.currentTimeMillis()) }
    fun tick() { active?.tick(System.currentTimeMillis()) }
    fun cancelKind(kind: Int) { if (activeKind == kind) active?.abort() }
    fun onDisconnected() { active?.linkLost() }
}
