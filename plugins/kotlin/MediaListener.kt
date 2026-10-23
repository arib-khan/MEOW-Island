package com.smartisland.app

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * One service, three jobs (all need notification-listener access):
 *  1. active MediaSessions -> real track info, artwork, playback state, transport controls
 *  2. other apps' notifications -> island preview (privacy-gated by config)
 *  3. this app's own reminder notifications -> island, even while the JS layer is asleep
 */
@SuppressLint("NewApi")
class MediaListener : NotificationListenerService() {
    companion object {
        @Volatile var controller: MediaController? = null
        @Volatile var snapshot: Map<String, Any> = mapOf("active" to false)
        @Volatile var inst: MediaListener? = null
    }

    private var msm: MediaSessionManager? = null
    private var registered = false
    private val seen = HashMap<String, String>()

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { pushMedia() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { pushMedia() }
        override fun onSessionDestroyed() { repick() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private fun cn() = ComponentName(this, MediaListener::class.java)

    private fun repick() {
        try { pick(msm?.getActiveSessions(cn())) } catch (e: SecurityException) { }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        inst = this
        try {
            val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm = m
            m.addOnActiveSessionsChangedListener(sessionsListener, cn())
            registered = true
            pick(m.getActiveSessions(cn()))
        } catch (e: SecurityException) {
            IslandModule.emit("error", mapOf("where" to "media", "message" to (e.message ?: "no access")))
        }
    }

    override fun onListenerDisconnected() { cleanup(); super.onListenerDisconnected() }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun cleanup() {
        controller?.unregisterCallback(cb); controller = null
        if (registered) { try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { } }
        registered = false
        IslandBus.remove("music")
        snapshot = mapOf("active" to false)
        IslandModule.emit("media", snapshot)
        MeowMusic.refresh(this)
        if (inst === this) inst = null
    }

    private fun pick(list: List<MediaController>?) {
        controller?.unregisterCallback(cb)
        controller = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        controller?.registerCallback(cb)
        pushMedia()
    }

    fun refresh() { pushMedia() }

    private fun pushMedia() {
        val c = controller
        val cfg = Cfg.read(this)
        if (c == null || !cfg.optBoolean("musicEnabled", true)) { publishNone(); return }
        val m = c.metadata
        val st = c.playbackState
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: (m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
        val state = st?.state ?: PlaybackState.STATE_NONE
        val playing = state == PlaybackState.STATE_PLAYING
        if (title.isEmpty() || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED) { publishNone(); return }
        val bmp: Bitmap? = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val small = bmp?.let { Bitmap.createScaledBitmap(it, 96, 96, true) }
        val actions = st?.actions ?: 0L
        val canNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        val canPrev = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
        var pos = st?.position ?: 0L
        if (playing && st != null && st.lastPositionUpdateTime > 0L)
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        val dur = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        IslandBus.post(Act("music", 50, title, artist, art = small, spinning = playing, playing = playing,
            canPrev = canPrev, canNext = canNext, posMs = pos, durMs = dur, posAt = System.currentTimeMillis()))
        val snap = HashMap<String, Any>()
        snap["active"] = true; snap["title"] = title; snap["artist"] = artist; snap["playing"] = playing
        snap["position"] = pos.toDouble()
        snap["duration"] = dur.toDouble()
        snap["posAt"] = System.currentTimeMillis().toDouble()      // when `position` was true, so the widget can keep counting
        snap["canNext"] = canNext; snap["canPrev"] = canPrev
        snap["art"] = if (small != null) Img.b64(small, false) else ""
        snap["app"] = c.packageName ?: ""
        snapshot = snap
        IslandModule.emit("media", snap)
        MeowMusic.refresh(this)
    }

    private fun publishNone() {
        IslandBus.remove("music")
        snapshot = mapOf("active" to false)
        IslandModule.emit("media", snapshot)
        MeowMusic.refresh(this)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun appIcon(pkg: String): Bitmap? = try {
        Img.fromDrawable(packageManager.getApplicationIcon(pkg), 64)
    } catch (e: Exception) { null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val own = sbn.packageName == packageName
        if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (!own && ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || n.category == Notification.CATEGORY_TRANSPORT)) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val sig = title + "\u0001" + text
        if (seen[sbn.key] == sig) return          // updates (progress ticks, re-posts) are not new events
        seen[sbn.key] = sig
        if (seen.size > 200) seen.clear()
        val cfg = Cfg.read(this)
        if (own) {
            val body = if (text.isNotEmpty()) text else title
            IslandBus.post(Act("reminder", 90, "\u23F0 " + body, "Reminder", expireAt = System.currentTimeMillis() + 15000L, expand = true, pulse = System.currentTimeMillis()))
            return
        }
        if (!cfg.optBoolean("notifEnabled", true)) return
        val app = appLabel(sbn.packageName)
        val preview = cfg.optBoolean("notifPreview", true)
        val t = if (preview && title.isNotEmpty()) title else app
        val sub = if (preview) (app + (if (text.isNotEmpty()) " \u00B7 $text" else "")) else "New notification"
        IslandBus.post(Act("notif", 60, t, sub, icon = appIcon(sbn.packageName),
            expireAt = System.currentTimeMillis() + cfg.optInt("expandMs", 5000) + 1500L, expand = true, pulse = System.currentTimeMillis()))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { seen.remove(sbn.key) }
}
