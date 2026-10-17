package com.smartisland.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Deliberately INERT. It subscribes to no events, cannot read window content (canRetrieveWindowContent=false), performs no gestures
 * and stores nothing. Its only purpose is that Android allows a window of type TYPE_ACCESSIBILITY_OVERLAY, the one layer that sits
 * above the status bar and navigation bar, to be created from this service's context. Fully optional: without it the island
 * uses the standard overlay layer.
 */
class MeowAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var inst: MeowAccessibilityService? = null

        /** True when the user has switched the service on in Android settings (it may still be connecting). */
        fun enabledInSettings(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, MeowAccessibilityService::class.java)
            return list.split(':').any { it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true) }
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); inst = this; IslandService.inst?.refresh() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }
    override fun onUnbind(intent: Intent?): Boolean { if (inst === this) inst = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (inst === this) inst = null; super.onDestroy() }
}
