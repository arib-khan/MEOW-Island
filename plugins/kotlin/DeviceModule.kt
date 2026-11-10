package com.smartisland.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import com.facebook.react.bridge.ActivityEventListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/** JS bridge for the Meow Island TFT device. All the real work is in [DeviceLink]; this only forwards calls. */
class DeviceModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    override fun getName() = "DeviceModule"

    private val main = Handler(Looper.getMainLooper())
    private val PICK_REQ = 7711
    private var pickPromise: Promise? = null
    private val listener = object : ActivityEventListener {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != PICK_REQ) return
            val p = pickPromise ?: return; pickPromise = null
            val uris = ArrayList<android.net.Uri>()
            data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris.add(it) } }
            if (uris.isEmpty()) data?.data?.let { uris.add(it) }
            if (resultCode != Activity.RESULT_OK || uris.isEmpty()) { p.resolve(null); return }
            Thread { readPicked(uris, p) }.start()
        }
        override fun onNewIntent(intent: Intent) { }
    }
    init { ctx.addActivityEventListener(listener) }

    @ReactMethod fun deviceStart() { DeviceLink.ensureStarted(ctx) }
    @ReactMethod fun deviceHasPermission(p: Promise) { p.resolve(DeviceLink.hasPermission(ctx)) }
    @ReactMethod fun deviceGetState(p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(com.facebook.react.bridge.Arguments.makeNativeMap(DeviceLink.state())) }
    @ReactMethod fun deviceScanStart(p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(DeviceLink.startScan()) }
    @ReactMethod fun deviceScanStop() { DeviceLink.stopScan() }
    @ReactMethod fun deviceScanResults(p: Promise) { p.resolve(DeviceLink.scanResultsJson()) }
    @ReactMethod fun devicePair(address: String, p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(DeviceLink.pair(address)) }
    @ReactMethod fun deviceDisconnect() { DeviceLink.disconnect() }
    @ReactMethod fun deviceCancelPair() { main.post { DeviceLink.cancelPair() } }
    @ReactMethod fun deviceRepair(p: Promise) { main.post { p.resolve(DeviceLink.repair()) } }
    @ReactMethod fun deviceReconnect() { DeviceLink.ensureStarted(ctx); DeviceLink.reconnect() }
    /** Resolves true when the device was reachable and told to forget this phone too. */
    @ReactMethod fun deviceUnpair(clearWifi: Boolean, p: Promise) { p.resolve(DeviceLink.unpair(clearWifi)) }
    @ReactMethod fun deviceSetScreen(id: Int, p: Promise) { p.resolve(DeviceLink.setScreen(id)) }
    @ReactMethod fun deviceSyncNow(p: Promise) { DeviceLink.syncAll(true); p.resolve(true) }
    @ReactMethod fun deviceSetWifi(ssid: String, pass: String, p: Promise) {
        if (!DeviceLink.state().let { it["ready"] == true }) { p.reject("NOT_CONNECTED", "The device is not connected right now."); return }
        if (DeviceLink.setWifi(ssid, pass)) p.resolve(true) else p.reject("INVALID_WIFI", "The Wi-Fi name must be 1 to 32 characters and the password empty (open network) or 8 to 63 characters.")
    }
    @ReactMethod fun deviceClearWifi(p: Promise) { p.resolve(DeviceLink.clearWifi()) }
    @ReactMethod fun deviceOpenBluetoothSettings() { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // ------------------------------------------------------------------ screen time on the display
    @ReactMethod fun deviceScreenTimeGet(p: Promise) { try { p.resolve(DeviceExtras.screenTimeJson(DeviceExtras.readScreenTime(ctx))) } catch (e: Exception) { p.reject("SCREEN_TIME", e.message ?: "failed") } }
    @ReactMethod fun deviceScreenTimeSetGoal(minutes: Int, p: Promise) { val v = DeviceExtras.setGoalMin(ctx, minutes); DeviceLink.screenTimeChanged(); p.resolve(v) }

    // ------------------------------------------------------------------ notifications on the display (opt-in; nothing is stored on disk or sent anywhere but to the paired display)
    @ReactMethod fun deviceNotifsGet(p: Promise) {
        p.resolve(JSONObject().put("enabled", DeviceExtras.notifsEnabled(ctx)).put("access", DeviceExtras.hasNotifAccess(ctx))
            .put("count", DeviceExtras.NotifStore.snapshot(System.currentTimeMillis()).size).put("preview", Cfg.read(ctx).optBoolean("notifPreview", true)).toString())
    }
    @ReactMethod fun deviceNotifsSetEnabled(on: Boolean, p: Promise) { DeviceExtras.setNotifsEnabled(ctx, on); DeviceLink.notifsChanged(); p.resolve(on) }
    /** Clears the list on the display only. Notifications on the phone are not touched. */
    @ReactMethod fun deviceNotifsClear(p: Promise) { DeviceExtras.NotifStore.clear(); DeviceLink.notifsChanged(); p.resolve(true) }

    // ------------------------------------------------------------------ lyrics (Spotify) source
    @ReactMethod fun deviceLyricsGet(p: Promise) { p.resolve(JSONObject().put("provider", DeviceExtras.lyricsProvider(ctx)).put("state", DeviceExtras.Lyrics.state).put("detail", DeviceExtras.Lyrics.detail).toString()) }
    @ReactMethod fun deviceLyricsSetProvider(name: String, p: Promise) { DeviceExtras.setLyricsProvider(ctx, name); DeviceLink.requestSync(); p.resolve(DeviceExtras.lyricsProvider(ctx)) }

    // ------------------------------------------------------------------ captions (Live Caption text for YouTube, Brave and other players)
    @ReactMethod fun deviceCaptionsGet(p: Promise) {
        p.resolve(JSONObject().put("enabled", DeviceExtras.captionsEnabled(ctx)).put("service", MeowCaptionService.enabledInSettings(ctx))
            .put("liveCaption", DeviceExtras.liveCaptionSystemState(ctx)).put("lines", DeviceExtras.Captions.recent(System.currentTimeMillis()).size).toString())
    }
    @ReactMethod fun deviceCaptionsSetEnabled(on: Boolean, p: Promise) { DeviceExtras.setCaptionsEnabled(ctx, on); DeviceLink.requestSync(); p.resolve(on) }
    @ReactMethod fun deviceCaptionsOpenAccessibility() { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // ------------------------------------------------------------------ photo gallery (up to 24 photos, compressed and sent to the display)
    /** Opens Android's picker for one or more pictures. They are made upright, compressed to 240 x 320 JPEG and added to the gallery (this can take a few seconds
     *  per picture; progress arrives as "galleryProgress" events). Resolves null when cancelled, otherwise JSON {added, skipped:[{name,reason}], library}. */
    @ReactMethod fun galleryPick(p: Promise) {
        val act = currentActivity
        if (act == null) { p.reject("NO_ACTIVITY", "Open the app on screen first."); return }
        if (pickPromise != null) { p.reject("BUSY", "A picker is already open."); return }
        pickPromise = p
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            act.startActivityForResult(i, PICK_REQ)
        } catch (e: Exception) { pickPromise = null; p.reject("PICKER", "Could not open the picture picker: " + (e.message ?: "")) }
    }
    private fun readPicked(uris: List<android.net.Uri>, p: Promise) {
        try {
            val r = Gallery.addFromUris(ctx, uris) { done, total -> IslandModule.emit("galleryProgress", mapOf("done" to done, "total" to total)) }
            r.put("library", Gallery.info(ctx)); p.resolve(r.toString())
        } catch (e: Throwable) { p.reject("READ_FAILED", "Could not add those pictures: " + (e.message ?: e.javaClass.simpleName)) }
    }
    @ReactMethod fun galleryList(p: Promise) { try { p.resolve(Gallery.info(ctx).toString()) } catch (e: Exception) { p.reject("READ_FAILED", e.message ?: "failed") } }
    @ReactMethod fun galleryRemove(id: String, p: Promise) { main.post { Gallery.remove(ctx, id); p.resolve(Gallery.info(ctx).toString()) } }
    /** Moves a photo earlier (delta < 0) or later (delta > 0) in the slideshow. */
    @ReactMethod fun galleryMove(id: String, delta: Int, p: Promise) { main.post { Gallery.move(ctx, id, delta); p.resolve(Gallery.info(ctx).toString()) } }
    /** settingsJson: {intervalSec, fit: "fill"|"fit", quality: "small"|"balanced"|"best"}. A change of fit or quality compresses every photo again (background). */
    @ReactMethod fun gallerySetSettings(settingsJson: String, p: Promise) {
        Thread {
            try {
                val j = JSONObject(settingsJson)
                if (j.has("fit") || j.has("quality")) {
                    val cur = JSONObject(Gallery.info(ctx).toString()).getJSONObject("settings")
                    val changed = Gallery.setLook(ctx, j.optString("fit", cur.optString("fit")), j.optString("quality", cur.optString("quality")))
                    if (changed > 0 && DeviceLink.isReady()) main.post { Gallery.sendAll(ctx) }
                }
                if (j.has("intervalSec")) Gallery.setInterval(ctx, j.optInt("intervalSec", 10))
                p.resolve(Gallery.info(ctx).toString())
            } catch (e: Throwable) { p.reject("SETTINGS_FAILED", "Could not change the settings: " + (e.message ?: e.javaClass.simpleName)) }
        }.start()
    }
    /** Sends the photos the display is missing (all of them when [all] is true). */
    @ReactMethod fun gallerySend(all: Boolean, p: Promise) { main.post { val err = Gallery.sendAll(ctx); if (err == null) p.resolve(true) else p.reject("SEND_FAILED", err) } }
    @ReactMethod fun galleryCancel() { main.post { Gallery.cancel() } }
    @ReactMethod fun galleryShow(p: Promise) { main.post { val err = Gallery.show(); if (err == null) p.resolve(true) else p.reject("SHOW_FAILED", err) } }
    @ReactMethod fun gallerySetAuto(on: Boolean, p: Promise) { Gallery.setAuto(ctx, on); p.resolve(on) }
    /** Removes photos that are on the display but not in this app's gallery. */
    @ReactMethod fun galleryClearDeviceOnly(p: Promise) { main.post { Gallery.clearDeviceOnly(ctx); p.resolve(true) } }
}
