package com.smartisland.app
import android.content.ComponentName; import android.media.MediaMetadata; import android.media.session.*
import android.service.notification.NotificationListenerService
import java.io.ByteArrayOutputStream; import android.graphics.Bitmap

/** NotificationListenerService grants access to MediaSessions => real song/artist/art/playback state + controls. */
class MediaListener : NotificationListenerService() {
  companion object { var controller: MediaController? = null; var lastNotif: Triple<String,String,String>? = null }
  private var msm: MediaSessionManager? = null
  private val cb = object : MediaController.Callback() {
    override fun onMetadataChanged(m: MediaMetadata?) = push()
    override fun onPlaybackStateChanged(s: PlaybackState?) = push()
  }
  private val sl = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
  override fun onListenerConnected() {
    msm = getSystemService(MediaSessionManager::class.java)
    val cn = ComponentName(this, MediaListener::class.java)
    msm?.addOnActiveSessionsChangedListener(sl, cn); pick(msm?.getActiveSessions(cn))
  }
  private fun pick(l: List<MediaController>?) {
    controller?.unregisterCallback(cb)
    controller = l?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l?.firstOrNull()
    controller?.registerCallback(cb); push()
  }
  private fun push() {
    val c = controller ?: return; val m = c.metadata; val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
    val bmp: Bitmap? = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
    val bytes = bmp?.let { ByteArrayOutputStream().also { o -> Bitmap.createScaledBitmap(it,96,96,true).compress(Bitmap.CompressFormat.JPEG,80,o) }.toByteArray() }
    IslandService.inst?.render(m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "", m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "", bytes, playing, 6000)
    IslandModule.emit("media", mapOf("title" to (m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""), "artist" to (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""),
      "playing" to playing, "position" to (c.playbackState?.position ?: 0L), "duration" to (m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)))
  }
  override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
    val e = sbn.notification.extras
    IslandModule.emit("notification", mapOf("pkg" to sbn.packageName, "title" to (e.getCharSequence("android.title")?.toString() ?: ""), "text" to (e.getCharSequence("android.text")?.toString() ?: "")))
  }
}
