package com.smartisland.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What the user picked for one custom screen: the HTML file and the files it uses (images, fonts, style sheets, scripts), either picked one by one
 * or inside a .zip. Held in memory while the user previews it, written to the app's private storage as bundle.zip when saved. Never uploaded anywhere.
 */
class ScreenBundle(var html: String, var htmlName: String, val assets: LinkedHashMap<String, ByteArray>) {
    val warnings = ArrayList<String>()
    fun assetBytes(): Long = assets.values.sumOf { it.size.toLong() }
    fun toAssets() = Assets(assets)

    companion object {
        const val MAX_HTML = 1024 * 1024
        const val MAX_ONE = 8 * 1024 * 1024
        const val MAX_TOTAL = 24 * 1024 * 1024
        const val MAX_COUNT = 300
        const val MAIN = "index.html"           // name of the main file inside bundle.zip

        /** Turns a zip entry name into a safe relative path, or null (directories, hidden/system entries, anything that tries to leave the folder). */
        fun cleanPath(raw: String): String? {
            val p = raw.replace('\\', '/').trimStart('/')
            if (p.isEmpty() || p.endsWith("/")) return null
            val parts = p.split('/')
            if (parts.any { it == ".." || it == "." || it.isEmpty() }) return null
            if (parts[0] == "__MACOSX" || parts.last().startsWith(".")) return null
            return p
        }

        /** Builds a bundle from picked files (display name + bytes). A zip is expanded; the main HTML is index.html when there is one, else the shallowest .html. */
        fun fromPicked(files: List<Pair<String, ByteArray>>, into: ScreenBundle? = null): ScreenBundle {
            val all = LinkedHashMap<String, ByteArray>()
            into?.let { b -> all.putAll(b.assets); all[b.htmlName] = b.html.toByteArray(Charsets.UTF_8) }
            val warnings = ArrayList<String>(); var zipped = false
            for ((name, bytes) in files) {
                if (name.lowercase().endsWith(".zip")) { zipped = true; for ((p, b) in expandZip(bytes, warnings)) all[p] = b }
                else { val p = cleanPath(name.substringAfterLast('/')) ?: continue; all[p] = bytes }
            }
            // a zip made from a folder usually has one top-level folder around everything: take it off so paths match the HTML's
            if (zipped) {
                val tops = all.keys.map { it.substringBefore('/', "") }.toSet()
                if (tops.size == 1 && tops.first().isNotEmpty() && all.keys.all { it.contains('/') }) { val pre = tops.first() + "/"; val m = LinkedHashMap<String, ByteArray>(); for ((k, v) in all) m[k.removePrefix(pre)] = v; all.clear(); all.putAll(m) }
            }
            val htmls = all.keys.filter { val e = it.substringAfterLast('.', "").lowercase(); e == "html" || e == "htm" }
            val main = htmls.firstOrNull { it.equals(MAIN, true) } ?: htmls.minByOrNull { it.count { c -> c == '/' } * 1000 + it.length } ?: ""
            val html = if (main.isEmpty()) "" else String(all.getValue(main), Charsets.UTF_8).removePrefix("\uFEFF")
            val assets = LinkedHashMap<String, ByteArray>()
            for ((k, v) in all) { if (k == main) continue; val e = k.substringAfterLast('.', "").lowercase(); if (e in Assets.ALLOWED_EXT) assets[k] = v else warnings.add("Skipped $k (not an image, font, style sheet or script)") }
            val b = ScreenBundle(html, if (main.isEmpty()) "index.html" else main.substringAfterLast('/'), assets); b.warnings.addAll(warnings)
            if (html.length > MAX_HTML) throw IOException("The HTML file is larger than ${MAX_HTML / 1024} KB. Put big images in separate files or a .zip.")
            if (assets.size > MAX_COUNT) throw IOException("That is more than $MAX_COUNT files.")
            if (b.assetBytes() > MAX_TOTAL) throw IOException("The files add up to more than ${MAX_TOTAL / 1048576} MB. Use smaller images (the display is only 240 x 320).")
            return b
        }

        private fun expandZip(bytes: ByteArray, warnings: MutableList<String>): List<Pair<String, ByteArray>> {
            val out = ArrayList<Pair<String, ByteArray>>(); var total = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e: ZipEntry? = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val p = cleanPath(e.name)
                        if (p == null) warnings.add("Skipped " + e.name.take(60))
                        else {
                            val buf = ByteArrayOutputStream(); val tmp = ByteArray(16384); var n: Int; var size = 0L
                            while (z.read(tmp).also { n = it } > 0) { size += n; total += n; if (size > MAX_ONE || total > MAX_TOTAL * 2) throw IOException("The zip is too large (limit ${MAX_TOTAL / 1048576} MB unpacked)."); buf.write(tmp, 0, n) }
                            out.add(p to buf.toByteArray()); if (out.size > MAX_COUNT * 2) throw IOException("The zip contains too many files.")
                        }
                    }
                    e = z.nextEntry
                }
            }
            return out
        }

        fun readZip(f: File): ScreenBundle {
            val files = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(f.inputStream().buffered()).use { z -> var e = z.nextEntry; while (e != null) { if (!e.isDirectory) files.add(e.name to z.readBytes()); e = z.nextEntry } }
            val html = files.firstOrNull { it.first == MAIN }?.let { String(it.second, Charsets.UTF_8) } ?: ""
            val assets = LinkedHashMap<String, ByteArray>(); for ((k, v) in files) if (k != MAIN) assets[k] = v
            return ScreenBundle(html, MAIN, assets)
        }
    }

    fun writeZip(f: File) {
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry(MAIN)); z.write(html.toByteArray(Charsets.UTF_8)); z.closeEntry()
            for ((k, v) in assets) { z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() }
        }
    }
}

/**
 * The library of custom screens (private storage, never uploaded) and their transfer to the display.
 *   filesDir/screens/index.json            the list (id, name, slot on the display, size, CRC, frames, settings, ...)
 *   filesDir/screens/<id>/bundle.zip       the user's HTML and the files it uses (so a screen can be rendered again with other settings)
 *   filesDir/screens/<id>/device.bin       the encoded file the display plays (MANI, see firmware/MeowIsland/mani.h)
 * Every screen owns one of the display's [DeviceProtocol.SLOTS] slots. [ImageTransfers] runs one transfer at a time on top of the existing BLE link
 * ([DeviceLink.send]); screens are sent one after the other.
 */
object CustomScreen {
    const val MAX_SCREENS = DeviceProtocol.SLOTS
    private const val DEVICE_MAX = 786432
    private val lock = Any()
    private fun root(ctx: Context) = File(ctx.filesDir, "screens").also { it.mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("meowextras", Context.MODE_PRIVATE)

    class Entry(val id: String, var name: String, var slot: Int, var crc: Long, var size: Int, var frames: Int, var animated: Boolean, var loop: Boolean,
                var durationMs: Int, var settings: JSONObject, var updatedAt: Long) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("slot", slot).put("crc", crc.toString()).put("size", size).put("frames", frames)
            .put("animated", animated).put("loop", loop).put("durationMs", durationMs).put("settings", settings).put("updatedAt", updatedAt)
        companion object {
            fun from(j: JSONObject) = Entry(j.getString("id"), j.optString("name", "Custom screen"), j.optInt("slot"), j.optString("crc", "0").toLongOrNull() ?: 0L, j.optInt("size"),
                j.optInt("frames", 1), j.optBoolean("animated"), j.optBoolean("loop"), j.optInt("durationMs"), j.optJSONObject("settings") ?: JSONObject(), j.optLong("updatedAt"))
        }
    }

    // ------------------------------------------------------------------ index
    private fun readIndex(ctx: Context): Pair<MutableList<Entry>, MutableSet<Int>> {
        migrateLegacy(ctx)
        val f = File(root(ctx), "index.json"); val list = ArrayList<Entry>(); val pend = HashSet<Int>()
        try {
            if (f.isFile) {
                val j = JSONObject(f.readText(Charsets.UTF_8)); val a = j.optJSONArray("screens") ?: JSONArray()
                for (i in 0 until a.length()) { val e = Entry.from(a.getJSONObject(i)); if (File(File(root(ctx), e.id), "device.bin").isFile) list.add(e) }
                val p = j.optJSONArray("pendingDelete") ?: JSONArray(); for (i in 0 until p.length()) pend.add(p.getInt(i))
            }
        } catch (e: Exception) { }
        return list to pend
    }
    private fun writeIndex(ctx: Context, list: List<Entry>, pend: Set<Int>) {
        val j = JSONObject().put("v", 1).put("screens", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }).put("pendingDelete", JSONArray(pend.toList()))
        val t = File(root(ctx), "index.json.tmp"); t.writeText(j.toString(), Charsets.UTF_8)
        val f = File(root(ctx), "index.json"); f.delete(); if (!t.renameTo(f)) throw IOException("Could not save the screen list.")
    }
    fun list(ctx: Context): List<Entry> = synchronized(lock) { readIndex(ctx).first.sortedBy { it.slot } }
    fun find(ctx: Context, id: String): Entry? = list(ctx).firstOrNull { it.id == id }
    fun deviceBytes(ctx: Context, id: String): ByteArray? = try { File(File(root(ctx), id), "device.bin").takeIf { it.isFile }?.readBytes() } catch (e: Exception) { null }
    fun loadBundle(ctx: Context, id: String): ScreenBundle? = try { File(File(root(ctx), id), "bundle.zip").takeIf { it.isFile }?.let { ScreenBundle.readZip(it) } } catch (e: Exception) { null }

    /** The single design of the earlier version becomes the first screen of the library (slot 0, which is where the display already keeps it). */
    private fun migrateLegacy(ctx: Context) {
        val idx = File(root(ctx), "index.json"); val img = File(ctx.filesDir, "custom_screen.mimg"); val html = File(ctx.filesDir, "custom_screen.html")
        if (idx.isFile || !img.isFile) return
        try {
            val data = img.readBytes(); val id = "legacy"; val dir = File(root(ctx), id).also { it.mkdirs() }
            File(dir, "device.bin").writeBytes(data)
            ScreenBundle(if (html.isFile) html.readText(Charsets.UTF_8) else "", ScreenBundle.MAIN, LinkedHashMap()).writeZip(File(dir, "bundle.zip"))
            val e = Entry(id, prefs(ctx).getString("customName", "")?.removeSuffix(".html")?.ifBlank { null } ?: "My screen", 0, DeviceProtocol.crc32(data), data.size, 1, false, false, 0,
                JSONObject().put("scripts", false).put("online", false).put("animate", false), prefs(ctx).getLong("customSavedAt", System.currentTimeMillis()))
            writeIndex(ctx, listOf(e), emptySet())
            img.delete(); html.delete(); prefs(ctx).edit().remove("customCrc").remove("customName").remove("customSavedAt").apply()
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ picked files waiting for a decision
    val pending = ConcurrentHashMap<String, ScreenBundle>()
    fun newToken(): String = java.lang.Long.toString(System.nanoTime(), 36) + java.lang.Integer.toString((Math.random() * 46656).toInt(), 36)

    /** Stores a rendered screen. id == null creates a new one from the pending bundle [token]; otherwise the screen is replaced (and its files too when a bundle is given). */
    fun save(ctx: Context, id: String?, token: String?, name: String, settings: JSONObject, device: ByteArray, frames: Int, animated: Boolean, loop: Boolean, durationMs: Int): Entry {
        if (device.size < 19 || device.size > DEVICE_MAX || device[0] != 'M'.code.toByte() || (device[1] != 'A'.code.toByte() && device[1] != 'I'.code.toByte())) throw IOException("That is not a valid display file.")
        synchronized(lock) {
            val (list, pend) = readIndex(ctx)
            val bundle = token?.let { pending[it] }
            var e = id?.let { want -> list.firstOrNull { it.id == want } }
            if (e == null) {
                if (bundle == null) throw IOException("Choose the files again; nothing is waiting to be saved.")
                if (list.size >= MAX_SCREENS) throw IOException("The display holds $MAX_SCREENS screens. Remove one first.")
                val slot = (0 until MAX_SCREENS).first { s -> list.none { it.slot == s } }
                e = Entry(newToken(), name.take(40), slot, 0, 0, 1, false, false, 0, settings, 0); list.add(e)
            }
            val dir = File(root(ctx), e.id).also { it.mkdirs() }
            val tb = File(dir, "device.bin.tmp"); tb.writeBytes(device); val fb = File(dir, "device.bin"); fb.delete(); if (!tb.renameTo(fb)) throw IOException("Could not save the screen.")
            if (bundle != null) { val tz = File(dir, "bundle.zip.tmp"); bundle.writeZip(tz); val fz = File(dir, "bundle.zip"); fz.delete(); if (!tz.renameTo(fz)) throw IOException("Could not save the screen.") }
            e.name = name.take(40).ifBlank { e.name }; e.crc = DeviceProtocol.crc32(device); e.size = device.size; e.frames = frames; e.animated = animated; e.loop = loop; e.durationMs = durationMs
            e.settings = settings; e.updatedAt = System.currentTimeMillis()
            pend.remove(e.slot)
            writeIndex(ctx, list, pend)
            if (token != null) pending.remove(token)
            confirmed.remove(e.id)
            return e
        }
    }

    fun rename(ctx: Context, id: String, name: String) {
        synchronized(lock) { val (list, pend) = readIndex(ctx); val e = list.firstOrNull { it.id == id } ?: return; e.name = name.trim().take(40).ifBlank { e.name }; writeIndex(ctx, list, pend) }
    }

    /** Removes the screen from the phone and, when connected, from the display (otherwise as soon as the display is connected again). The other screens are not touched. */
    fun remove(ctx: Context, id: String) {
        var slot = -1
        synchronized(lock) {
            val (list, pend) = readIndex(ctx); val e = list.firstOrNull { it.id == id } ?: return
            slot = e.slot; list.remove(e); File(root(ctx), id).deleteRecursively(); pend.add(slot); writeIndex(ctx, list, pend)
        }
        synchronized(queue) { queue.remove(id) }
        if (tId == id) ImageTransfers.cancelKind(DeviceProtocol.KIND_SCREEN)
        confirmed.remove(id)
        processPendingDeletes(ctx)
        emit()
    }

    /** Deletes screens on the display that this app deleted while the display was away. */
    fun processPendingDeletes(ctx: Context) {
        val st = DeviceLink.lastStatus()
        if (!DeviceLink.isReady() || st?.hasSlots != true) return
        synchronized(lock) {
            val (list, pend) = readIndex(ctx); if (pend.isEmpty()) return
            for (s in pend.toList()) { if (list.any { it.slot == s }) { pend.remove(s); continue }; DeviceLink.send(DeviceProtocol.deleteSlot(s)); pend.remove(s) }
            writeIndex(ctx, list, pend)
        }
    }

    /** Slots that hold a screen on the display although the library has none there (for example after reinstalling the app). Left alone unless the user asks. */
    fun deviceOnlySlots(ctx: Context): List<Int> {
        val st = DeviceLink.lastStatus() ?: return emptyList(); if (!st.hasSlots) return emptyList()
        val own = list(ctx).map { it.slot }.toSet()
        return (0 until DeviceProtocol.SLOTS).filter { (st.slotMask shr it) and 1 == 1 && it !in own }
    }
    fun clearDeviceOnly(ctx: Context) { for (s in deviceOnlySlots(ctx)) DeviceLink.send(DeviceProtocol.deleteSlot(s)) }

    // ------------------------------------------------------------------ transfers
    @Volatile var tState = "idle"; private set               // idle | sending | done | error
    @Volatile var tId = ""; private set
    @Volatile var tSent = 0; private set
    @Volatile var tTotal = 0; private set
    @Volatile var tMessage = ""; private set
    private val queue = java.util.ArrayDeque<String>()
    private val confirmed = ConcurrentHashMap<String, Long>()    // screens the display acknowledged with this exact CRC during this connection
    @Volatile private var autoTried = false

    fun onConnected() { autoTried = false; confirmed.clear() }
    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean("customAuto", true)
    fun setAuto(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("customAuto", on).apply() }

    private fun libraryDigest(list: List<Entry>) = DeviceProtocol.slotDigest(list.associate { it.slot to it.crc })

    /** Queues screens for the display; returns an error message, or null when the transfer(s) started. */
    fun send(ctx: Context, ids: List<String>): String? {
        if (!DeviceLink.isReady()) return "The display is not connected right now."
        if (DeviceLink.lastStatus()?.hasSlots != true) return "The display firmware is too old for animated or several custom screens. Flash firmware 1.3 or later from the firmware folder (the app and the other screens keep working)."
        val known = list(ctx).map { it.id }.toSet()
        synchronized(queue) { for (id in ids) if (id in known && !queue.contains(id) && id != tId) queue.add(id) }
        pump(ctx)
        return null
    }
    fun sendAll(ctx: Context): String? = send(ctx, list(ctx).map { it.id })

    private fun pump(ctx: Context) {
        if (tState == "sending" || ImageTransfers.busy()) return
        val id = synchronized(queue) { queue.poll() } ?: return
        startOne(ctx, id)
    }

    private fun startOne(ctx: Context, id: String) {
        val e = find(ctx, id); val data = deviceBytes(ctx, id)
        if (e == null || data == null) { pump(ctx); return }
        val st = DeviceLink.lastStatus()
        if (st != null && st.hasSlots && st.fsTotalKb > 0 && data.size > st.fsFreeKb * 1024L - 16384L && st.slotMask shr e.slot and 1 == 0) {
            fail(id, "The display has about ${Math.max(0, st.fsFreeKb - 16)} KB free but \"${e.name}\" needs ${data.size / 1024} KB. Delete another screen, simplify this one, or flash the firmware with the \"Default 4MB with spiffs\" partition scheme.")
            return
        }
        tId = id; tState = "sending"; tSent = 0; tTotal = data.size; tMessage = ""; emit()
        val ok = ImageTransfers.start(DeviceProtocol.KIND_SCREEN, data, 240, 320,
            { ok, _, msg ->
                tState = if (ok) "done" else "error"; tMessage = msg; if (ok) { tSent = tTotal; confirmed[id] = e.crc }
                emit()
                if (ok) pump(ctx) else synchronized(queue) { queue.clear() }
            },
            { sent, total -> tSent = sent; tTotal = total; emit() }, e.slot)
        if (!ok) fail(id, "Could not start the transfer.")
    }
    private fun fail(id: String, msg: String) { tId = id; tState = "error"; tMessage = msg; tSent = 0; tTotal = 0; synchronized(queue) { queue.clear() }; emit() }

    /** After (re)connecting: bring the display in line with the library. Once per connection, so a failing display cannot loop. The display answers a screen it already has instantly, without writing flash. */
    fun autoSendIfNeeded(ctx: Context, s: DeviceProtocol.Status) {
        if (!s.hasSlots) return
        processPendingDeletes(ctx)
        if (autoTried || !autoEnabled(ctx) || ImageTransfers.busy() || tState == "sending") { pump(ctx); return }
        val l = list(ctx); if (l.isEmpty()) return
        if (s.slotDigest == libraryDigest(l) && s.slotDigest != 0L) return
        autoTried = true
        send(ctx, l.map { it.id })
    }

    fun cancel() { synchronized(queue) { queue.clear() }; ImageTransfers.cancelKind(DeviceProtocol.KIND_SCREEN); tState = "idle"; tMessage = "Cancelled."; emit() }

    /** Shows this screen on the display now. */
    fun show(ctx: Context, id: String): String? {
        val e = find(ctx, id) ?: return "That screen no longer exists."
        if (!DeviceLink.isReady()) return "The display is not connected right now."
        if (DeviceLink.lastStatus()?.hasSlots != true) return "The display firmware is too old for several custom screens. Flash firmware 1.3 or later."
        DeviceLink.send(DeviceProtocol.screenSlot(DeviceProtocol.SCREEN_CUSTOM, e.slot)); return null
    }

    fun info(ctx: Context): JSONObject {
        val l = list(ctx); val st = DeviceLink.lastStatus()
        val inSync = st?.hasSlots == true && st.slotDigest != 0L && st.slotDigest == libraryDigest(l)
        val arr = JSONArray()
        for (e in l) {
            val j = e.toJson()
            val onDev = st?.hasSlots == true && ((st.slotMask shr e.slot) and 1 == 1) && (inSync || confirmed[e.id] == e.crc)
            j.put("onDevice", onDev).put("sending", tState == "sending" && tId == e.id).put("queued", synchronized(queue) { queue.contains(e.id) })
            j.put("shown", st?.hasSlots == true && st.screen == DeviceProtocol.SCREEN_CUSTOM && st.activeSlot == e.slot)
            arr.put(j)
        }
        return JSONObject().put("screens", arr).put("auto", autoEnabled(ctx)).put("connected", DeviceLink.isReady()).put("max", MAX_SCREENS)
            .put("device", JSONObject().put("hasSlots", st?.hasSlots == true).put("mask", st?.slotMask ?: 0).put("active", st?.activeSlot ?: 0).put("freeKb", st?.fsFreeKb ?: 0).put("totalKb", st?.fsTotalKb ?: 0)
                .put("fw", if (st != null) "${st.fwMajor}.${st.fwMinor}" else "").put("deviceOnly", JSONArray(deviceOnlySlots(ctx))).put("inSync", inSync))
            .put("transfer", JSONObject().put("state", tState).put("id", tId).put("sent", tSent).put("total", tTotal).put("message", tMessage).put("queued", synchronized(queue) { queue.size }))
    }
    fun emit() { IslandModule.emit("customTransfer", mapOf("state" to tState, "id" to tId, "sent" to tSent, "total" to tTotal, "message" to tMessage)) }
}

/** One image transfer at a time over [DeviceLink.send]; the status notifications from the device drive it. */
object ImageTransfers {
    private var active: ImageSender? = null
    private var activeKind = -1
    private var nextId = 1

    fun busy() = active != null
    fun activeKind() = if (active != null) activeKind else -1

    fun start(kind: Int, data: ByteArray, w: Int, h: Int, finish: (Boolean, Int, String) -> Unit, progress: (Int, Int) -> Unit, slot: Int = 0): Boolean {
        if (!DeviceLink.isReady()) return false
        active?.abort()                                              // a newer transfer replaces an older one (album art is only started while nothing else is sending)
        val seen = DeviceLink.lastStatus()?.xferId ?: 0
        do { nextId = if (nextId >= 255) 1 else nextId + 1 } while (nextId == seen)
        lateinit var s: ImageSender
        s = ImageSender(nextId, kind, data, w, h, { DeviceLink.send(it) },
            { ok, reason, msg -> if (active === s) { active = null; activeKind = -1 }; finish(ok, reason, msg) }, progress, slot)
        active = s; activeKind = kind
        s.start(System.currentTimeMillis())
        return true
    }

    fun onStatus(st: DeviceProtocol.Status) { active?.onStatus(st, System.currentTimeMillis()) }
    fun tick() { active?.tick(System.currentTimeMillis()) }
    fun cancelKind(kind: Int) { if (activeKind == kind) active?.abort() }
    fun onDisconnected() { active?.linkLost() }
}
