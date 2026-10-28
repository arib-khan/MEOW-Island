package com.smartisland.app

import android.content.Intent
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/** JS bridge for the Meow Island TFT device. All the real work is in [DeviceLink]; this only forwards calls. */
class DeviceModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    override fun getName() = "DeviceModule"

    @ReactMethod fun deviceStart() { DeviceLink.ensureStarted(ctx) }
    @ReactMethod fun deviceHasPermission(p: Promise) { p.resolve(DeviceLink.hasPermission(ctx)) }
    @ReactMethod fun deviceGetState(p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(com.facebook.react.bridge.Arguments.makeNativeMap(DeviceLink.state())) }
    @ReactMethod fun deviceScanStart(p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(DeviceLink.startScan()) }
    @ReactMethod fun deviceScanStop() { DeviceLink.stopScan() }
    @ReactMethod fun deviceScanResults(p: Promise) { p.resolve(DeviceLink.scanResultsJson()) }
    @ReactMethod fun devicePair(address: String, p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(DeviceLink.pair(address)) }
    @ReactMethod fun deviceDisconnect() { DeviceLink.disconnect() }
    @ReactMethod fun deviceReconnect() { DeviceLink.ensureStarted(ctx); DeviceLink.reconnect() }
    /** Resolves true when the device was reachable and told to forget this phone too. */
    @ReactMethod fun deviceUnpair(clearWifi: Boolean, p: Promise) { p.resolve(DeviceLink.unpair(clearWifi)) }
    @ReactMethod fun deviceSetScreen(id: Int, p: Promise) { p.resolve(DeviceLink.setScreen(id)) }
    @ReactMethod fun deviceSyncNow(p: Promise) { DeviceLink.syncAll(true); p.resolve(true) }
    @ReactMethod fun deviceSetWifi(ssid: String, pass: String, p: Promise) {
        if (!DeviceLink.state().let { it["ready"] == true }) { p.reject("NOT_CONNECTED", "The device is not connected right now."); return }
        if (DeviceLink.setWifi(ssid, pass)) p.resolve(true) else p.reject("INVALID_WIFI", "The Wi-Fi name must be 1 to 32 characters and the password empty (open network) or 8 to 63 characters.")
    }
    @ReactMethod fun deviceClearWifi(p: Promise) { p.resolve(DeviceLink.clearWifi()) }
    @ReactMethod fun deviceOpenBluetoothSettings() { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
