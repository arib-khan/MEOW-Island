package com.smartisland.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Optional, low-power background step counting (only runs when a step feature AND "Count steps in the background" are on, and the
 * Physical-activity permission is granted). It does not poll: it keeps one batched hardware step listener alive (see
 * HealthEngine.ensureLive) and reacts to screen-off (save), screen-on and phone-unlocked (fresh reading + widget refresh), date
 * and time-zone changes. If the system kills it, the cumulative hardware counter plus the ~30 min alarm sync still catch up.
 */
@SuppressLint("NewApi")
class StepService : Service() {
    companion object {
        @Volatile var alive = false
        private const val CH = "steps_fgs"
        private const val NID = 3
    }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            try {
                val interactive = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> { HealthEngine.save(ctx, true); HealthEngine.ensureLive(ctx, false) }
                    Intent.ACTION_SCREEN_ON -> HealthEngine.ensureLive(ctx, true)
                    // Phone unlocked: take a fresh reading and redraw the widgets (HealthEngine.save(force) refreshes them)
                    Intent.ACTION_USER_PRESENT -> { HealthEngine.ensureLive(ctx, true); HealthEngine.syncOnce(ctx) { } }
                    else -> { HealthEngine.syncOnce(ctx) { }; HealthEngine.ensureLive(ctx, interactive) }   // date / time zone / clock changed
                }
            } catch (e: Exception) { }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Background step counting", NotificationManager.IMPORTANCE_MIN))
            val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE)
            val icon = resources.getIdentifier("ic_meow", "drawable", packageName).let { if (it != 0) it else android.R.drawable.ic_dialog_info }
            val n = NotificationCompat.Builder(this, CH).setSmallIcon(icon).setContentTitle("Counting your steps")
                .setContentText("Low-power step counting is on. Turn it off in Health.").setOngoing(true).setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_MIN).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) else startForeground(NID, n)
            val f = IntentFilter()
            for (a in arrayOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)) f.addAction(a)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, f)
            alive = true
            HealthEngine.ensureLive(this, (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
            HealthEngine.syncOnce(this) { }
        } catch (e: Exception) { alive = false; stopSelf() }   // e.g. permission missing: fall back to the alarm sync
    }

    override fun onDestroy() {
        alive = false
        try { unregisterReceiver(rx) } catch (e: Exception) { }
        try { HealthEngine.save(this, true); HealthEngine.ensureLive(this, false) } catch (e: Exception) { }
        super.onDestroy()
    }
}
