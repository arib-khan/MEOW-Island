package com.smartisland.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.ViewManager

@SuppressLint("NewApi")
class IslandModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    companion object {
        @Volatile var rc: ReactApplicationContext? = null
        fun emit(name: String, data: Map<String, Any>) {
            try {
                val c = rc ?: return
                if (!c.hasActiveReactInstance()) return
                c.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, Arguments.makeNativeMap(data))
            } catch (e: Exception) { }
        }
    }

    init { rc = ctx }
    override fun getName() = "IslandModule"

    /** Read synchronously from JS (NativeModules.IslandModule.nativeVersion) to detect a stale native build. */
    override fun getConstants(): Map<String, Any>? = mapOf("nativeVersion" to 7)

    @ReactMethod fun addListener(eventName: String) { }
    @ReactMethod fun removeListeners(count: Int) { }

    private fun open(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i) }
    private fun pkgUri() = Uri.parse("package:" + ctx.packageName)

    // ---- permissions: status ----
    @ReactMethod fun hasOverlay(p: Promise) { p.resolve(Settings.canDrawOverlays(ctx)) }
    @ReactMethod fun hasNotificationPermission(p: Promise) { p.resolve(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    @ReactMethod fun hasNotificationAccess(p: Promise) { p.resolve(NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) }
    @ReactMethod fun hasUsageAccess(p: Promise) { p.resolve(UsageUtil.hasAccess(ctx)) }
    @ReactMethod fun canScheduleExactAlarms(p: Promise) {
        if (Build.VERSION.SDK_INT >= 31) p.resolve((ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms())
        else p.resolve(true)
    }
    @ReactMethod fun isIgnoringBattery(p: Promise) {
        p.resolve((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName))
    }

    // ---- permissions: open the right settings page ----
    @ReactMethod fun requestOverlay() { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri())) }
    @ReactMethod fun requestNotificationAccess() { open(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
    @ReactMethod fun requestUsageAccess() { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    @ReactMethod fun requestExactAlarm() { if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri())) }
    @ReactMethod fun openNotificationSettings() {
        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
    @ReactMethod fun openBatterySettings() { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

    // ---- island lifecycle ----
    @ReactMethod fun hasAccessibility(p: Promise) { p.resolve(MeowAccessibilityService.enabledInSettings(ctx)) }
    @ReactMethod fun requestAccessibility() { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    @ReactMethod fun getLayer(p: Promise) { p.resolve(IslandService.inst?.layerName() ?: "none") }

    @ReactMethod fun startIsland(p: Promise) {
        val topOk = Cfg.read(ctx).optString("layer", "topmost") == "topmost" && MeowAccessibilityService.enabledInSettings(ctx)
        if (!Settings.canDrawOverlays(ctx) && !topOk) { p.reject("NO_OVERLAY", "Overlay permission is not granted"); return }
        try {
            ctx.getSharedPreferences("island", 0).edit().putBoolean("autostart", true).apply()
            IslandService.lastError = ""
            ContextCompat.startForegroundService(ctx, Intent(ctx, IslandService::class.java))
            p.resolve(true)
        } catch (e: Exception) { p.reject("START_FAILED", e.message ?: e.javaClass.simpleName, e) }
    }
    @ReactMethod fun stopIsland(p: Promise) {
        ctx.getSharedPreferences("island", 0).edit().putBoolean("autostart", false).apply()
        ctx.stopService(Intent(ctx, IslandService::class.java))
        p.resolve(true)
    }
    @ReactMethod fun isRunning(p: Promise) { p.resolve(IslandService.inst?.isShowing() == true) }
    @ReactMethod fun lastError(p: Promise) { p.resolve(IslandService.lastError) }

    // ---- config & activities ----
    @ReactMethod fun setConfig(json: String) {
        Cfg.write(ctx, json)
        IslandService.inst?.applyConfig()
        MediaListener.inst?.refresh()
        MeowMusic.refresh(ctx, true)          // Dancing Cat Mode on/off takes effect immediately
    }
    @ReactMethod fun postActivity(id: String, priority: Int, title: String, sub: String, ttlMs: Double) {
        IslandBus.post(Act(id, priority, title, sub, expireAt = if (ttlMs > 0) System.currentTimeMillis() + ttlMs.toLong() else 0L, expand = true, pulse = System.currentTimeMillis()))
    }
    @ReactMethod fun removeActivity(id: String) { IslandBus.remove(id) }

    /** Asks the launcher to pin a widget (Android 8+). Returns false when the launcher cannot, so the UI can explain the manual way. */
    @ReactMethod fun pinWidget(kind: String, p: Promise) {
        try {
            val cls: Class<*> = when (kind) { "clock" -> MeowClockWidget::class.java; "round" -> MeowRoundClockWidget::class.java; "card" -> MeowStepCardWidget::class.java; "music" -> MeowMusicWidget::class.java; else -> MeowHeatWidget::class.java }
            val m = AppWidgetManager.getInstance(ctx)
            if (Build.VERSION.SDK_INT >= 26 && m.isRequestPinAppWidgetSupported) { m.requestPinAppWidget(ComponentName(ctx, cls), null, null); p.resolve(true) }
            else p.resolve(false)
        } catch (e: Exception) { p.reject("WIDGET_ERROR", e.message ?: "Could not pin widget", e) }
    }

    // ---- optional Health & Wellness (all local, all opt-in) ----
    @ReactMethod fun setHealthConfig(json: String) {
        HealthEngine.writeCfg(ctx, json)
        HealthEngine.configure(ctx)
        Water.arm(ctx)
    }
    @ReactMethod fun getHealthSnapshot(p: Promise) {
        try { p.resolve(HealthEngine.snapshot(ctx)) } catch (e: Exception) { p.reject("HEALTH_ERROR", e.message ?: "unavailable", e) }
    }
    /** One fresh cumulative reading (no-op if disabled, no permission or no sensor). */
    @ReactMethod fun syncSteps(p: Promise) { HealthEngine.syncOnce(ctx) { p.resolve(true) } }
    @ReactMethod fun clearHealthData(p: Promise) { HealthEngine.clear(ctx); p.resolve(true) }
    @ReactMethod fun waterAction(action: String) {
        when (action) { "done" -> Water.done(ctx); "snooze" -> Water.snooze(ctx); "dismiss" -> Water.dismiss(ctx); "test" -> Water.fire(ctx, 0L, true) }
    }

    // ---- timers (absolute end timestamps, persisted natively) ----
    @ReactMethod fun startTimer(label: String, endAtMs: Double, notifId: String, p: Promise) {
        val id = System.currentTimeMillis().toString()
        Timers.add(ctx, id, label, endAtMs.toLong(), notifId)
        p.resolve(id)
    }
    @ReactMethod fun cancelTimer(id: String, p: Promise) { Timers.remove(ctx, id); IslandBus.remove("timer"); p.resolve(true) }
    @ReactMethod fun getTimers(p: Promise) {
        val arr = Arguments.createArray()
        val all = Timers.all(ctx)
        for (i in 0 until all.length()) {
            val t = all.optJSONObject(i) ?: continue
            if (t.optLong("endAt") <= System.currentTimeMillis()) continue
            val m = Arguments.createMap()
            m.putString("id", t.optString("id")); m.putString("label", t.optString("label"))
            m.putDouble("endAt", t.optLong("endAt").toDouble()); m.putString("notifId", t.optString("notifId"))
            arr.pushMap(m)
        }
        p.resolve(arr)
    }

    // ---- music ----
    @ReactMethod fun getMedia(p: Promise) { p.resolve(Arguments.makeNativeMap(MediaListener.snapshot)) }
    @ReactMethod fun mediaControl(action: String) {
        val t = MediaListener.controller?.transportControls ?: return
        when (action) {
            "play" -> t.play(); "pause" -> t.pause(); "next" -> t.skipToNext(); "prev" -> t.skipToPrevious()
        }
    }

    // ---- screen time ----
    /** Launchable apps only, via a MAIN/LAUNCHER <queries> intent: no QUERY_ALL_PACKAGES needed. */
    @ReactMethod fun getInstalledApps(p: Promise) {
        Thread {
            try {
                val pm = ctx.packageManager
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val seen = HashSet<String>()
                val arr = Arguments.createArray()
                for (ri in pm.queryIntentActivities(i, 0).sortedBy { it.loadLabel(pm).toString().lowercase() }) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == ctx.packageName || !seen.add(pkg)) continue
                    val m = Arguments.createMap()
                    m.putString("pkg", pkg)
                    m.putString("name", ri.loadLabel(pm).toString())
                    m.putString("icon", Img.b64(Img.fromDrawable(ri.loadIcon(pm), 72), true))
                    arr.pushMap(m)
                }
                p.resolve(arr)
            } catch (e: Exception) { p.reject("APPS_ERROR", e.message ?: "Could not list apps", e) }
        }.start()
    }
    @ReactMethod fun getUsageToday(p: Promise) {
        try {
            val m = Arguments.createMap()
            for ((k, v) in UsageUtil.todayAll(ctx)) m.putDouble(k, v.toDouble())
            p.resolve(m)
        } catch (e: Exception) { p.reject("USAGE_ERROR", e.message ?: "Usage unavailable", e) }
    }
}

class IslandPackage : ReactPackage {
    override fun createNativeModules(c: ReactApplicationContext): List<NativeModule> = listOf<NativeModule>(IslandModule(c))
    override fun createViewManagers(c: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
