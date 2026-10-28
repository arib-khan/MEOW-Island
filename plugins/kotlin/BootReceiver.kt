package com.smartisland.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.core.content.ContextCompat

/** After reboot: re-arm optional health alarms (only if the user enabled them) and restore the island if it was left running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Adult-website filter: restart only if the user enabled it AND Android's VPN consent is still granted (never prompts here).
        try { if (WellbeingStore.adultEnabled(ctx) && VpnService.prepare(ctx) == null) AdultFilterService.start(ctx) } catch (e: Exception) { }
        try { DeviceLink.ensureStarted(ctx) } catch (e: Exception) { }      // reconnect to a paired Meow Island display, if any
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        try { HealthEngine.configure(ctx); Water.arm(ctx) } catch (e: Exception) { }
        if (!ctx.getSharedPreferences("island", 0).getBoolean("autostart", false)) return
        val topOk = Cfg.read(ctx).optString("layer", "topmost") == "topmost" && MeowAccessibilityService.enabledInSettings(ctx)
        if (!Settings.canDrawOverlays(ctx) && !topOk) return
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, IslandService::class.java)) } catch (e: Exception) { }
    }
}
