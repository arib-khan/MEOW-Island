package com.smartisland.app

import android.annotation.SuppressLint
import android.app.AlarmManager
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

    /** Bumped whenever the JS<->native contract changes, so a stale native build is detected. */
    @ReactMethod fun getNativeVersion(p: Promise) { p.resolve(2) }

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
    @ReactMethod fun startIsland(p: Promise) {
        if (!Settings.canDrawOverlays(ctx)) { p.reject("NO_OVERLAY", "Overlay permission is not granted"); return }
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
    }
    @ReactMethod fun postActivity(id: String, priority: Int, title: String, sub: String, ttlMs: Double) {
        IslandBus.post(Act(id, priority, title, sub, expireAt = if (ttlMs > 0) System.currentTimeMillis() + ttlMs.toLong() else 0L, expand = true))
    }
    @ReactMethod fun removeActivity(id: String) { IslandBus.remove(id) }

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
