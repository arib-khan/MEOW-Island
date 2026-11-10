package com.smartisland.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The photo gallery: pictures picked on this phone, compressed heavily to 240 x 320 JPEG files and sent to the display, which shows them full screen one
 * after the other. Everything stays in the app's private storage; nothing is uploaded anywhere.
 *   filesDir/gallery/index.json          settings, the photos in play order, slots waiting to be deleted on the display
 *   filesDir/gallery/<id>_m.jpg          "master": the picture upright, at most 1280 px on the long side (kept so a change of fit / quality can re-compress it)
 *   filesDir/gallery/<id>_<crc>.jpg      what the display gets: exactly 240 x 320, baseline JPEG, a few KB to a few tens of KB
 * Every photo owns one of the display's [DeviceProtocol.PHOTO_SLOTS] slots; the order on the phone is sent as a list of slots, so reordering and removing
 * never re-sends a picture.
 */
object Gallery {
    const val MAX_PHOTOS = DeviceProtocol.PHOTO_SLOTS
    const val W = 240; const val H = 320
    private const val MASTER_LONG = 1280
    private val lock = Any()
    private fun root(ctx: Context) = File(ctx.filesDir, "gallery").also { it.mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("meowextras", Context.MODE_PRIVATE)

    class Photo(val id: String, var slot: Int, var crc: Long, var size: Int, var addedAt: Long)
    class Settings(var intervalSec: Int = 10, var fit: String = "fill", var quality: String = "balanced") {
        fun toJson() = JSONObject().put("intervalSec", intervalSec).put("fit", fit).put("quality", quality)
        /** The size the compressed photo should stay under; the best quality that fits is used. */
        fun targetBytes() = when (quality) { "small" -> 12 * 1024; "best" -> 32 * 1024; else -> 20 * 1024 }
    }
    private class Index(val settings: Settings, val photos: MutableList<Photo>, val pendingDelete: MutableSet<Int>)

    // ------------------------------------------------------------------ index
    private fun photoFile(ctx: Context, p: Photo) = File(root(ctx), "${p.id}_${java.lang.Long.toHexString(p.crc)}.jpg")
    private fun masterFile(ctx: Context, id: String) = File(root(ctx), "${id}_m.jpg")

    private fun readIndex(ctx: Context): Index {
        cleanupLegacy(ctx)
        val f = File(root(ctx), "index.json"); val st = Settings(); val list = ArrayList<Photo>(); val pend = HashSet<Int>()
        try {
            if (f.isFile) {
                val j = JSONObject(f.readText(Charsets.UTF_8)); j.optJSONObject("settings")?.let { s ->
                    st.intervalSec = s.optInt("intervalSec", 10).coerceIn(DeviceProtocol.GAL_MIN_SEC, DeviceProtocol.GAL_MAX_SEC)
                    st.fit = if (s.optString("fit") == "fit") "fit" else "fill"; st.quality = s.optString("quality").let { q -> if (q == "small" || q == "best") q else "balanced" }
                }
                val a = j.optJSONArray("photos") ?: JSONArray()
                for (i in 0 until a.length()) { val o = a.getJSONObject(i); val p = Photo(o.getString("id"), o.getInt("slot"), o.optString("crc", "0").toLongOrNull() ?: 0L, o.optInt("size"), o.optLong("addedAt")); if (photoFile(ctx, p).isFile) list.add(p) }
                val pd = j.optJSONArray("pendingDelete") ?: JSONArray(); for (i in 0 until pd.length()) pend.add(pd.getInt(i))
            }
        } catch (e: Exception) { }
        return Index(st, list, pend)
    }
    private fun writeIndex(ctx: Context, ix: Index) {
        val j = JSONObject().put("v", 1).put("settings", ix.settings.toJson())
            .put("photos", JSONArray().also { a -> ix.photos.forEach { a.put(JSONObject().put("id", it.id).put("slot", it.slot).put("crc", it.crc.toString()).put("size", it.size).put("addedAt", it.addedAt)) } })
            .put("pendingDelete", JSONArray(ix.pendingDelete.toList()))
        val t = File(root(ctx), "index.json.tmp"); t.writeText(j.toString(), Charsets.UTF_8)
        val f = File(root(ctx), "index.json"); f.delete(); if (!t.renameTo(f)) throw IOException("Could not save the gallery.")
    }

    /** The custom-screen feature of earlier versions is gone: its stored designs and files are removed. */
    private fun cleanupLegacy(ctx: Context) {
        val flag = prefs(ctx).getBoolean("legacyCustomRemoved", false); if (flag) return
        try { File(ctx.filesDir, "screens").deleteRecursively(); File(ctx.filesDir, "custom_screen.mimg").delete(); File(ctx.filesDir, "custom_screen.html").delete() } catch (e: Exception) { }
        prefs(ctx).edit().remove("customCrc").remove("customName").remove("customSavedAt").remove("customAuto").putBoolean("legacyCustomRemoved", true).apply()
    }

    // ------------------------------------------------------------------ picture processing
    private fun orientation(ctx: Context, uri: Uri): Int = try {
        ctx.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1
    } catch (e: Exception) { 1 }
    private fun orientMatrix(o: Int): Matrix? {
        val m = Matrix()
        when (o) { 2 -> m.postScale(-1f, 1f); 3 -> m.postRotate(180f); 4 -> m.postScale(1f, -1f); 5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f); 7 -> { m.postRotate(-90f); m.postScale(-1f, 1f) }; 8 -> m.postRotate(-90f); else -> return null }
        return m
    }
    /** Halving steps are a good box filter; a single bilinear jump from 1280 px to 240 px would alias badly. */
    private fun downscale(src: Bitmap, dw: Int, dh: Int): Bitmap {
        var cur = src
        while (cur.width >= dw * 2 && cur.height >= dh * 2) { val n = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true); if (cur !== src) cur.recycle(); cur = n }
        if (cur.width != dw || cur.height != dh) { val n = Bitmap.createScaledBitmap(cur, dw, dh, true); if (cur !== src) cur.recycle(); cur = n }
        return cur
    }

    /** Reads a picture from the picker, makes it upright and at most [MASTER_LONG] px on the long side. Returns null when it cannot be decoded. */
    private fun loadMaster(ctx: Context, uri: Uri): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val first = cr.openInputStream(uri) ?: return null
        first.use { BitmapFactory.decodeStream(it, null, bounds) }                    // returns null by design: only the size is read
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1; while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MASTER_LONG) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        orientMatrix(orientation(ctx, uri))?.let { m -> val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true); if (r !== bmp) { bmp.recycle(); bmp = r } }
        val longSide = Math.max(bmp.width, bmp.height)
        if (longSide > MASTER_LONG) { val k = MASTER_LONG.toFloat() / longSide; val r = downscale(bmp, Math.max(1, Math.round(bmp.width * k)), Math.max(1, Math.round(bmp.height * k))); if (r !== bmp) { bmp.recycle(); bmp = r } }
        return bmp
    }
    private fun jpegBytes(b: Bitmap, q: Int): ByteArray { val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, q, o); return o.toByteArray() }

    /** The 240 x 320 picture the display shows: "fill" crops the centre to cover the screen, "fit" keeps the whole picture with black bars. */
    private fun frame(master: Bitmap, fit: String): Bitmap {
        val scale = if (fit == "fit") Math.min(W.toFloat() / master.width, H.toFloat() / master.height) else Math.max(W.toFloat() / master.width, H.toFloat() / master.height)
        val sw = Math.max(1, Math.round(master.width * scale)); val sh = Math.max(1, Math.round(master.height * scale))
        val scaled = downscale(master, sw, sh)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(out); c.drawColor(Color.BLACK)
        val left = (W - sw) / 2; val top = (H - sh) / 2
        val dl = Math.max(0, left); val dt = Math.max(0, top); val dr = Math.min(W, left + sw); val db = Math.min(H, top + sh)
        val sl = Math.max(0, -left); val stp = Math.max(0, -top)
        c.drawBitmap(scaled, Rect(sl, stp, sl + (dr - dl), stp + (db - dt)), Rect(dl, dt, dr, db), Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled !== master) scaled.recycle()
        return out
    }
    /** Heavy compression: the highest JPEG quality whose file stays under the size target (never below quality 20), always under the display's limit. */
    internal fun compressForDisplay(master: Bitmap, st: Settings): ByteArray {
        val f = frame(master, st.fit); try {
            val target = st.targetBytes(); var q = 80; var best = jpegBytes(f, q)
            while (best.size > target && q > 20) { q -= 5; best = jpegBytes(f, q) }
            if (best.size > DeviceProtocol.PHOTO_MAX_FILE) best = jpegBytes(f, 15)
            if (best.size > DeviceProtocol.PHOTO_MAX_FILE) throw IOException("This picture cannot be compressed small enough.")
            return best
        } finally { f.recycle() }
    }

    private fun newId() = java.lang.Long.toString(System.nanoTime(), 36) + java.lang.Integer.toString((Math.random() * 1679616).toInt(), 36)
    private fun displayName(ctx: Context, uri: Uri): String {
        var name = "photo"
        try { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) ?: name } } } catch (e: Exception) { }
        return name
    }

    /** Adds the picked pictures (in the order given) until the display's 24 slots are used. Blocks: call from a background thread. */
    fun addFromUris(ctx: Context, uris: List<Uri>, progress: (done: Int, total: Int) -> Unit): JSONObject {
        var added = 0; val skipped = JSONArray()
        for ((i, uri) in uris.withIndex()) {
            progress(i, uris.size)
            val name = displayName(ctx, uri)
            try {
                val room = synchronized(lock) { MAX_PHOTOS - readIndex(ctx).photos.size }
                if (room <= 0) { skipped.put(JSONObject().put("name", name).put("reason", "The display holds $MAX_PHOTOS photos. Remove one first.")); continue }
                val st = synchronized(lock) { readIndex(ctx).settings }
                val master = loadMaster(ctx, uri)
                if (master == null) { skipped.put(JSONObject().put("name", name).put("reason", "Could not read this picture.")); continue }
                val (masterJpg, dev) = try { Pair(jpegBytes(master, 90), compressForDisplay(master, st)) } finally { master.recycle() }
                synchronized(lock) {
                    val ix = readIndex(ctx)
                    if (ix.photos.size >= MAX_PHOTOS) { skipped.put(JSONObject().put("name", name).put("reason", "The display holds $MAX_PHOTOS photos. Remove one first.")); return@synchronized }
                    val slot = (0 until MAX_PHOTOS).first { s -> ix.photos.none { it.slot == s } }
                    val p = Photo(newId(), slot, DeviceProtocol.crc32(dev), dev.size, System.currentTimeMillis())
                    masterFile(ctx, p.id).writeBytes(masterJpg); photoFile(ctx, p).writeBytes(dev)
                    ix.photos.add(p); ix.pendingDelete.remove(slot); writeIndex(ctx, ix); added++
                }
            } catch (e: OutOfMemoryError) { skipped.put(JSONObject().put("name", name).put("reason", "Not enough memory for this picture.")) }
            catch (e: Exception) { skipped.put(JSONObject().put("name", name).put("reason", e.message ?: "Could not process this picture.")) }
        }
        progress(uris.size, uris.size)
        return JSONObject().put("added", added).put("skipped", skipped)
    }

    // ------------------------------------------------------------------ editing
    fun remove(ctx: Context, id: String) {
        synchronized(lock) {
            val ix = readIndex(ctx); val p = ix.photos.firstOrNull { it.id == id } ?: return
            ix.photos.remove(p); ix.pendingDelete.add(p.slot); writeIndex(ctx, ix)
            photoFile(ctx, p).delete(); masterFile(ctx, id).delete()
            synchronized(queue) { queue.remove(id) }; confirmed.remove(id)
        }
        if (tId == id) ImageTransfers.cancelKind(DeviceProtocol.KIND_PHOTO)
        processPendingDeletes(ctx); pushCfg(ctx); emit()
    }
    /** Moves a photo [delta] places in the play order (negative = earlier). */
    fun move(ctx: Context, id: String, delta: Int) {
        synchronized(lock) {
            val ix = readIndex(ctx); val i = ix.photos.indexOfFirst { it.id == id }; if (i < 0) return
            val j = (i + delta).coerceIn(0, ix.photos.size - 1); if (i == j) return
            val p = ix.photos.removeAt(i); ix.photos.add(j, p); writeIndex(ctx, ix)
        }
        pushCfg(ctx)
    }
    fun setInterval(ctx: Context, sec: Int) {
        synchronized(lock) { val ix = readIndex(ctx); ix.settings.intervalSec = sec.coerceIn(DeviceProtocol.GAL_MIN_SEC, DeviceProtocol.GAL_MAX_SEC); writeIndex(ctx, ix) }
        pushCfg(ctx)
    }
    /** Fit / quality change: every photo is compressed again from its master (blocks: background thread). Returns the number of photos whose file changed. */
    fun setLook(ctx: Context, fit: String, quality: String): Int {
        val fitV = if (fit == "fit") "fit" else "fill"; val qV = if (quality == "small" || quality == "best") quality else "balanced"
        val (st, ids) = synchronized(lock) {
            val ix = readIndex(ctx)
            if (ix.settings.fit == fitV && ix.settings.quality == qV) return 0
            ix.settings.fit = fitV; ix.settings.quality = qV; writeIndex(ctx, ix)
            Pair(ix.settings, ix.photos.map { it.id })
        }
        var changed = 0
        for (id in ids) {
            try {
                val mf = masterFile(ctx, id); if (!mf.isFile) continue
                val master = BitmapFactory.decodeFile(mf.path) ?: continue
                val dev = try { compressForDisplay(master, st) } finally { master.recycle() }
                synchronized(lock) {
                    val ix = readIndex(ctx); val p = ix.photos.firstOrNull { it.id == id } ?: return@synchronized
                    val old = photoFile(ctx, p); val crc = DeviceProtocol.crc32(dev)
                    if (crc != p.crc) { p.crc = crc; p.size = dev.size; photoFile(ctx, p).writeBytes(dev); if (old.path != photoFile(ctx, p).path) old.delete(); writeIndex(ctx, ix); confirmed.remove(id); changed++ }
                }
            } catch (e: Exception) { }
        }
        return changed
    }
    fun photoBytes(ctx: Context, id: String): ByteArray? = synchronized(lock) { readIndex(ctx).photos.firstOrNull { it.id == id }?.let { try { photoFile(ctx, it).readBytes() } catch (e: Exception) { null } } }

    // ------------------------------------------------------------------ display sync
    @Volatile var tState = "idle"; private set               // idle | sending | done | error
    @Volatile var tId = ""; private set
    @Volatile var tSent = 0; private set
    @Volatile var tTotal = 0; private set
    @Volatile var tMessage = ""; private set
    private val queue = java.util.ArrayDeque<String>()
    private val confirmed = ConcurrentHashMap<String, Long>()    // photos the display acknowledged with this exact CRC during this connection
    @Volatile private var autoTried = false
    @Volatile private var cfgSent = false

    fun onConnected() { autoTried = false; cfgSent = false; confirmed.clear() }
    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean("galleryAuto", true)
    fun setAuto(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("galleryAuto", on).apply() }

    private fun digestOf(l: List<Photo>) = DeviceProtocol.photoDigest(l.associate { it.slot to it.crc })

    /** Slideshow timing and order. Idempotent: the display ignores it when nothing changed. */
    fun pushCfg(ctx: Context) {
        if (!DeviceLink.isReady() || DeviceLink.lastStatus()?.hasGallery != true) return
        val ix = synchronized(lock) { readIndex(ctx) }
        DeviceLink.send(DeviceProtocol.galleryCfg(ix.settings.intervalSec, ix.photos.map { it.slot })); cfgSent = true
    }

    /** Queues photos for the display; returns an error message, or null when the transfer(s) started. */
    fun send(ctx: Context, ids: List<String>): String? {
        if (!DeviceLink.isReady()) return "The display is not connected right now."
        if (DeviceLink.lastStatus()?.hasGallery != true) return "The display firmware has no photo gallery yet. Flash the new firmware (firmware 1.3 or later); everything else keeps working."
        val known = synchronized(lock) { readIndex(ctx).photos.map { it.id } }.toSet()
        synchronized(queue) { for (id in ids) if (id in known && !queue.contains(id) && id != tId) queue.add(id) }
        pump(ctx); return null
    }
    fun sendAll(ctx: Context): String? = send(ctx, synchronized(lock) { readIndex(ctx).photos.map { it.id } })

    private fun pump(ctx: Context) {
        if (tState == "sending" || ImageTransfers.busy()) return
        val id = synchronized(queue) { queue.poll() }
        if (id == null) { if (tState == "done") pushCfg(ctx); return }
        startOne(ctx, id)
    }
    private fun startOne(ctx: Context, id: String) {
        val p = synchronized(lock) { readIndex(ctx).photos.firstOrNull { it.id == id } }
        val data = p?.let { photoBytes(ctx, id) }
        if (p == null || data == null) { pump(ctx); return }
        val st = DeviceLink.lastStatus()
        if (st != null && st.hasGallery && st.fsTotalKb > 0 && data.size > st.fsFreeKb * 1024L - 16384L && (st.photoMask shr p.slot) and 1L == 0L) {
            fail(id, "The display has about ${Math.max(0, st.fsFreeKb - 16)} KB free but this photo needs ${(data.size + 1023) / 1024} KB. Remove a photo, use the Smaller setting, or flash the display with a partition scheme that has more storage.")
            return
        }
        tId = id; tState = "sending"; tSent = 0; tTotal = data.size; tMessage = ""; emit()
        val ok = ImageTransfers.start(DeviceProtocol.KIND_PHOTO, data, W, H,
            { ok, _, msg ->
                tState = if (ok) "done" else "error"; tMessage = msg; if (ok) { tSent = tTotal; confirmed[id] = p.crc }
                emit()
                if (ok) pump(ctx) else synchronized(queue) { queue.clear() }
            },
            { sent, total -> tSent = sent; tTotal = total; emit() }, p.slot)
        if (!ok) fail(id, "Could not start the transfer.")
    }
    private fun fail(id: String, msg: String) { tId = id; tState = "error"; tMessage = msg; tSent = 0; tTotal = 0; synchronized(queue) { queue.clear() }; emit() }

    /** After (re)connecting: send the slideshow setting and bring the display's photos in line with the library. Once per connection, so a failing display cannot loop. */
    fun autoSyncIfNeeded(ctx: Context, s: DeviceProtocol.Status) {
        if (!s.hasGallery) return
        processPendingDeletes(ctx)
        if (!cfgSent) pushCfg(ctx)
        if (autoTried || !autoEnabled(ctx) || ImageTransfers.busy() || tState == "sending") { pump(ctx); return }
        val l = synchronized(lock) { readIndex(ctx).photos }; if (l.isEmpty()) return
        if (s.galDigest == digestOf(l) && s.galDigest != 0L) return
        autoTried = true
        send(ctx, l.map { it.id })
    }
    /** Deletes photos on the display that were removed here while the display was away. */
    fun processPendingDeletes(ctx: Context) {
        val st = DeviceLink.lastStatus()
        if (!DeviceLink.isReady() || st?.hasGallery != true) return
        synchronized(lock) {
            val ix = readIndex(ctx); if (ix.pendingDelete.isEmpty()) return
            for (s in ix.pendingDelete.toList()) { if (ix.photos.none { it.slot == s }) DeviceLink.send(DeviceProtocol.deletePhoto(s)); ix.pendingDelete.remove(s) }
            writeIndex(ctx, ix)
        }
    }
    /** Slots that hold a photo on the display although this phone has none there (for example after reinstalling the app). Left alone unless the user asks. */
    fun deviceOnlySlots(ctx: Context): List<Int> {
        val st = DeviceLink.lastStatus() ?: return emptyList(); if (!st.hasGallery) return emptyList()
        val own = synchronized(lock) { readIndex(ctx).photos.map { it.slot } }.toSet()
        return (0 until MAX_PHOTOS).filter { (st.photoMask shr it) and 1L == 1L && it !in own }
    }
    fun clearDeviceOnly(ctx: Context) { for (s in deviceOnlySlots(ctx)) DeviceLink.send(DeviceProtocol.deletePhoto(s)) }

    fun cancel() { synchronized(queue) { queue.clear() }; ImageTransfers.cancelKind(DeviceProtocol.KIND_PHOTO); tState = "idle"; tMessage = "Cancelled."; emit() }

    /** Shows the slideshow on the display now. */
    fun show(): String? {
        if (!DeviceLink.isReady()) return "The display is not connected right now."
        DeviceLink.send(DeviceProtocol.screen(DeviceProtocol.SCREEN_GALLERY)); return null
    }

    fun info(ctx: Context): JSONObject {
        val ix = synchronized(lock) { readIndex(ctx) }; val st = DeviceLink.lastStatus()
        val inSync = st?.hasGallery == true && st.galDigest != 0L && st.galDigest == digestOf(ix.photos)
        val arr = JSONArray(); var total = 0L
        for (p in ix.photos) {
            total += p.size
            val onDev = st?.hasGallery == true && ((st.photoMask shr p.slot) and 1L == 1L) && (inSync || confirmed[p.id] == p.crc)
            arr.put(JSONObject().put("id", p.id).put("slot", p.slot).put("size", p.size).put("uri", "file://" + photoFile(ctx, p).path)
                .put("onDevice", onDev).put("sending", tState == "sending" && tId == p.id).put("queued", synchronized(queue) { queue.contains(p.id) })
                .put("shown", st?.hasGallery == true && st.screen == DeviceProtocol.SCREEN_GALLERY && st.shownSlot == p.slot))
        }
        return JSONObject().put("photos", arr).put("settings", ix.settings.toJson()).put("auto", autoEnabled(ctx)).put("connected", DeviceLink.isReady()).put("max", MAX_PHOTOS).put("totalBytes", total)
            .put("device", JSONObject().put("hasGallery", st?.hasGallery == true).put("count", java.lang.Long.bitCount(st?.photoMask ?: 0L)).put("freeKb", st?.fsFreeKb ?: 0).put("totalKb", st?.fsTotalKb ?: 0)
                .put("intervalSec", st?.intervalSec ?: 0).put("fw", if (st != null) "${st.fwMajor}.${st.fwMinor}" else "").put("deviceOnly", JSONArray(deviceOnlySlots(ctx))).put("inSync", inSync))
            .put("transfer", JSONObject().put("state", tState).put("id", tId).put("sent", tSent).put("total", tTotal).put("message", tMessage).put("queued", synchronized(queue) { queue.size }))
    }
    fun emit() { IslandModule.emit("galleryTransfer", mapOf("state" to tState, "id" to tId, "sent" to tSent, "total" to tTotal, "message" to tMessage)) }
}
