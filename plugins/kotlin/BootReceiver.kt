package com.smartisland.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/** After reboot: re-arm optional health alarms (only if the user enabled them) and restore the island if it was left running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        try { HealthEngine.configure(ctx); Water.arm(ctx) } catch (e: Exception) { }
        if (!ctx.getSharedPreferences("island", 0).getBoolean("autostart", false)) return
        if (!Settings.canDrawOverlays(ctx)) return
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, IslandService::class.java)) } catch (e: Exception) { }
    }
}
