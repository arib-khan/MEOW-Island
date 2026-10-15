package com.smartisland.app
import android.app.AppOpsManager; import android.content.*; import android.net.Uri; import android.os.*; import android.provider.Settings
import android.app.usage.UsageStatsManager
import com.facebook.react.bridge.*; import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.ReactPackage; import com.facebook.react.uimanager.ViewManager

class IslandModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
  companion object { var rc: ReactApplicationContext? = null
    fun emit(n: String, d: Map<String, Any>) { rc?.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)?.emit(n, Arguments.makeNativeMap(d)) } }
  init { rc = ctx }
  override fun getName() = "IslandModule"
  @ReactMethod fun addListener(n: String) {} @ReactMethod fun removeListeners(c: Int) {}
  @ReactMethod fun hasOverlay(p: Promise) = p.resolve(Settings.canDrawOverlays(ctx))
  @ReactMethod fun requestOverlay() = ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  @ReactMethod fun hasNotificationAccess(p: Promise) = p.resolve(Settings.Secure.getString(ctx.contentResolver,"enabled_notification_listeners")?.contains(ctx.packageName) == true)
  @ReactMethod fun requestNotificationAccess() = ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  @ReactMethod fun hasUsageAccess(p: Promise) { val a = ctx.getSystemService(AppOpsManager::class.java)
    p.resolve(a.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED) }
  @ReactMethod fun requestUsageAccess() = ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  @ReactMethod fun startIsland() { ctx.startForegroundService(Intent(ctx, IslandService::class.java)) }
  @ReactMethod fun stopIsland() { ctx.stopService(Intent(ctx, IslandService::class.java)) }
  /** JS pushes the highest-priority activity text; media is rendered natively by MediaListener. */
  @ReactMethod fun show(title: String, sub: String) { IslandService.inst?.render(title, sub, null, false, 6000) }
  @ReactMethod fun savePrefs(w: Int, h: Int, r: Int, bg: Int, fg: Int) { ctx.getSharedPreferences("island", 0).edit().putInt("w",w).putInt("h",h).putInt("r",r).putInt("bg",bg).putInt("fg",fg).apply() }
  @ReactMethod fun mediaControl(a: String) { val t = MediaListener.controller?.transportControls ?: return
    when (a) { "play" -> t.play(); "pause" -> t.pause(); "next" -> t.skipToNext(); "prev" -> t.skipToPrevious() } }
  /** Foreground package from usage events (last 10s). Polled by JS; approximate, not instant. */
  @ReactMethod fun foregroundPackage(p: Promise) {
    val u = ctx.getSystemService(UsageStatsManager::class.java); val now = System.currentTimeMillis()
    val ev = u.queryEvents(now - 10_000, now); val e = android.app.usage.UsageEvents.Event(); var pkg: String? = null
    while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName }
    p.resolve(pkg) }
  @ReactMethod fun usageTodayMs(pkg: String, p: Promise) {
    val u = ctx.getSystemService(UsageStatsManager::class.java); val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0); set(java.util.Calendar.SECOND,0) }
    p.resolve(u.queryAndAggregateUsageStats(c.timeInMillis, System.currentTimeMillis())[pkg]?.totalTimeInForeground?.toDouble() ?: 0.0) }
}
class IslandPackage : ReactPackage {
  override fun createNativeModules(c: ReactApplicationContext) = listOf<NativeModule>(IslandModule(c))
  override fun createViewManagers(c: ReactApplicationContext) = emptyList<ViewManager<*, *>>()
}
