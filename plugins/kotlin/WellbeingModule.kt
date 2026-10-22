package com.smartisland.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import org.json.JSONObject

/**
 * JS bridge for the digital-wellbeing features (Reels/Shorts counter + blocker, adult-site DNS filter).
 * Native owns the settings; every change that would weaken protection must carry the PIN when one is set.
 */
class WellbeingModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    private val VPN_REQ = 7311
    private var vpnPromise: Promise? = null

    private val listener = object : ActivityEventListener {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != VPN_REQ) return
            vpnPromise?.resolve(resultCode == Activity.RESULT_OK); vpnPromise = null
        }
        override fun onNewIntent(intent: Intent) { }
    }
    init { ctx.addActivityEventListener(listener) }
    override fun getName() = "WellbeingModule"
    override fun getConstants(): Map<String, Any>? = mapOf("wellbeingVersion" to 1)

    private fun open(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i) }
    private fun pinGate(p: Promise, pin: String?): Boolean {
        if (!WellbeingStore.hasPin(ctx)) return true
        when (WellbeingStore.checkPin(ctx, pin)) {
            0 -> return true
            2 -> p.reject("PIN_LOCKED", "Too many wrong PINs. Try again in a minute.")
            else -> p.reject(if (pin.isNullOrEmpty()) "PIN_REQUIRED" else "PIN_WRONG", if (pin.isNullOrEmpty()) "Enter your PIN to change this." else "That PIN is not right.")
        }
        return false
    }

    // ---------------------------------------------------------------- Reels / Shorts
    @ReactMethod fun hasShortsGuard(p: Promise) { p.resolve(ShortsGuardService.enabledInSettings(ctx)) }
    @ReactMethod fun requestShortsGuard() { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    @ReactMethod fun getShortsConfig(p: Promise) { p.resolve(WellbeingStore.shortsJson(ctx).toString()) }

    @ReactMethod fun setShortsConfig(json: String, pin: String?, p: Promise) {
        try {
            val next = JSONObject(json); val old = WellbeingStore.shortsJson(ctx)
            if (WellbeingStore.shortsLoosens(old, next) && !pinGate(p, pin)) return
            WellbeingStore.writeShorts(ctx, next)
            ShortsGuardService.inst?.reloadConfig()
            p.resolve(next.toString())
        } catch (e: Exception) { p.reject("CONFIG_ERROR", e.message ?: "Invalid settings", e) }
    }

    @ReactMethod fun getShortsStatus(p: Promise) {
        try {
            val svc = ShortsGuardService.inst
            val m = Arguments.createMap()
            val enabled = ShortsGuardService.enabledInSettings(ctx)
            val cfg = WellbeingStore.shorts(ctx)
            m.putBoolean("serviceEnabled", enabled)
            m.putBoolean("serviceConnected", svc != null)
            m.putBoolean("onFeed", svc?.status()?.get("onFeed") == true)
            m.putString("app", (svc?.status()?.get("app") as? String) ?: "")
            m.putBoolean("shield", svc?.status()?.get("shield") == true)
            m.putInt("evals", (svc?.status()?.get("evals") as? Int) ?: 0)
            m.putInt("matches", (svc?.status()?.get("matches") as? Int) ?: 0)
            m.putString("lastError", ShortsGuardService.lastError)
            m.putBoolean("blockingConfigured", cfg.blockIg || cfg.blockYt)
            m.putBoolean("blockingActive", (cfg.blockIg || cfg.blockYt) && svc != null)
            m.putBoolean("hasPin", WellbeingStore.hasPin(ctx))
            m.putInt("shieldToday", WellbeingStore.shieldToday(ctx))
            p.resolve(m)
        } catch (e: Exception) { p.reject("STATUS_ERROR", e.message ?: "unavailable", e) }
    }

    @ReactMethod fun getShortsStats(p: Promise) { p.resolve(WellbeingStore.days(ctx).toString()) }
    @ReactMethod fun clearShortsStats(p: Promise) { WellbeingStore.clearDays(ctx); IslandModule.emit("shortsCount", mapOf("ig" to 0, "yt" to 0)); p.resolve(true) }
    @ReactMethod fun getDiagnosticLog(p: Promise) { p.resolve(WellbeingStore.logText(ctx)) }
    @ReactMethod fun clearDiagnosticLog(p: Promise) { WellbeingStore.clearLog(ctx); p.resolve(true) }

    // ---------------------------------------------------------------- PIN
    @ReactMethod fun hasPin(p: Promise) { p.resolve(WellbeingStore.hasPin(ctx)) }
    @ReactMethod fun setPin(newPin: String, oldPin: String?, p: Promise) {
        if (!Regex("^\\d{4,8}$").matches(newPin)) { p.reject("PIN_FORMAT", "Use 4 to 8 digits."); return }
        if (!pinGate(p, oldPin)) return
        WellbeingStore.setPin(ctx, newPin); p.resolve(true)
    }
    @ReactMethod fun clearPin(pin: String?, p: Promise) { if (!pinGate(p, pin)) return; WellbeingStore.clearPin(ctx); p.resolve(true) }

    // ---------------------------------------------------------------- Adult website filter
    @ReactMethod fun hasVpnPermission(p: Promise) { p.resolve(try { VpnService.prepare(ctx) == null } catch (e: Exception) { false }) }

    /** Shows Android's own VPN consent dialog. Resolves true only if the user accepts. */
    @ReactMethod fun requestVpnPermission(p: Promise) {
        try {
            val i = VpnService.prepare(ctx)
            if (i == null) { p.resolve(true); return }
            val act = currentActivity
            if (act == null) { p.reject("NO_ACTIVITY", "Open Meow Island first"); return }
            vpnPromise?.resolve(false); vpnPromise = p
            act.startActivityForResult(i, VPN_REQ)
        } catch (e: Exception) { p.reject("VPN_ERROR", e.message ?: "Could not ask for VPN permission", e) }
    }
    @ReactMethod fun openVpnSettings() { open(Intent(Settings.ACTION_VPN_SETTINGS)) }

    @ReactMethod fun getAdultConfig(p: Promise) { p.resolve(WellbeingStore.adultJson(ctx).toString()) }

    /** Saves the settings and starts / stops the filter to match `enabled`. Enabling needs the VPN permission to be granted first. */
    @ReactMethod fun setAdultConfig(json: String, pin: String?, p: Promise) {
        try {
            val next = JSONObject(json); val old = WellbeingStore.adultJson(ctx)
            if (WellbeingStore.adultLoosens(old, next) && !pinGate(p, pin)) return
            val want = next.optBoolean("enabled", false)
            if (want && VpnService.prepare(ctx) != null) { p.reject("NO_VPN_PERMISSION", "VPN permission has not been granted yet"); return }
            WellbeingStore.writeAdult(ctx, next)
            if (want) {
                if (AdultFilterService.running) AdultFilterService.applyUser(ctx) else AdultFilterService.start(ctx)
            } else AdultFilterService.stop(ctx)
            p.resolve(next.toString())
        } catch (e: Exception) { p.reject("CONFIG_ERROR", e.message ?: "Invalid settings", e) }
    }

    @ReactMethod fun getAdultStatus(p: Promise) {
        try {
            val m = Arguments.createMap()
            val cfg = WellbeingStore.adultJson(ctx)
            val meta = WellbeingStore.listMeta(ctx)
            val stats = WellbeingStore.adultStats(ctx)
            m.putBoolean("enabled", cfg.optBoolean("enabled", false))
            m.putBoolean("running", AdultFilterService.running)
            m.putBoolean("permission", try { VpnService.prepare(ctx) == null } catch (e: Exception) { false })
            m.putString("error", AdultFilterService.lastError)
            m.putString("privateDns", AdultFilterService.privateDnsMode(ctx))
            m.putBoolean("otherVpn", AdultFilterService.otherVpnActive(ctx))
            m.putInt("blockedToday", stats.optInt(ShortsPolicy.dayKey(System.currentTimeMillis()), 0) + 0)
            m.putDouble("blockedTotal", stats.optLong("total", 0).toDouble())
            m.putDouble("listUpdatedAt", meta.optLong("updatedAt", 0).toDouble())
            m.putInt("listCount", if (AdultFilterService.listLoaded) AdultFilterService.list.listSize else meta.optInt("count", 0))
            m.putString("listSource", if (meta.has("source")) meta.optString("source") else "bundled")
            p.resolve(m)
        } catch (e: Exception) { p.reject("STATUS_ERROR", e.message ?: "unavailable", e) }
    }

    @ReactMethod fun updateAdultBlocklist(url: String, p: Promise) {
        Thread {
            try { p.resolve(AdultFilterService.update(ctx, url)) } catch (e: Exception) { p.reject("UPDATE_FAILED", e.message ?: e.javaClass.simpleName, e) }
        }.start()
    }
}
