import { NativeModules, NativeEventEmitter, AppState } from 'react-native';
import { useCallback, useEffect, useState } from 'react';

const N: any = NativeModules.IslandModule;
export const available = !!N;
/** False when the installed APK was built from older native code (e.g. prebuild --clean failed). */
export const nativeCurrent = !!N && (N.nativeVersion ?? 0) >= 8;
export const events = N ? new NativeEventEmitter(N) : (null as unknown as NativeEventEmitter);

const W: any = NativeModules.WellbeingModule;
export const wellbeingAvailable = !!W;
export type ShortsStatus = { serviceEnabled: boolean; serviceConnected: boolean; onFeed: boolean; app: string; shield: boolean; evals: number; matches: number; lastError: string; blockingConfigured: boolean; blockingActive: boolean; hasPin: boolean; shieldToday: number };
export type AdultStatus = { enabled: boolean; running: boolean; permission: boolean; error: string; privateDns: string; otherVpn: boolean; blockedToday: number; blockedTotal: number; listUpdatedAt: number; listCount: number; listSource: string };
/** Typed bridge to WellbeingModule.kt. Setters that can weaken protection reject with code PIN_REQUIRED / PIN_WRONG / PIN_LOCKED when a PIN is set. */
export const wb = {
  hasShortsGuard: (): Promise<boolean> => W.hasShortsGuard(),
  requestShortsGuard: () => W.requestShortsGuard(),
  getShortsConfig: (): Promise<string> => W.getShortsConfig(),
  setShortsConfig: (json: string, pin?: string): Promise<string> => W.setShortsConfig(json, pin ?? null),
  getShortsStatus: (): Promise<ShortsStatus> => W.getShortsStatus(),
  getShortsStats: (): Promise<string> => W.getShortsStats(),
  clearShortsStats: (): Promise<boolean> => W.clearShortsStats(),
  getDiagnosticLog: (): Promise<string> => W.getDiagnosticLog(),
  clearDiagnosticLog: (): Promise<boolean> => W.clearDiagnosticLog(),
  hasPin: (): Promise<boolean> => W.hasPin(),
  setPin: (pin: string, oldPin?: string): Promise<boolean> => W.setPin(pin, oldPin ?? null),
  clearPin: (pin?: string): Promise<boolean> => W.clearPin(pin ?? null),
  hasVpnPermission: (): Promise<boolean> => W.hasVpnPermission(),
  requestVpnPermission: (): Promise<boolean> => W.requestVpnPermission(),
  openVpnSettings: () => W.openVpnSettings(),
  getAdultConfig: (): Promise<string> => W.getAdultConfig(),
  setAdultConfig: (json: string, pin?: string): Promise<string> => W.setAdultConfig(json, pin ?? null),
  getAdultStatus: (): Promise<AdultStatus> => W.getAdultStatus(),
  updateAdultBlocklist: (url: string): Promise<number> => W.updateAdultBlocklist(url),
};
export const wbEvents = W ? new NativeEventEmitter(N) : (null as unknown as NativeEventEmitter);   // events are emitted through IslandModule

const Dv: any = NativeModules.DeviceModule;
export const deviceAvailable = !!Dv;
/** Typed bridge to DeviceModule.kt (the Meow Island ESP32 display). The Wi-Fi password is passed straight through and never stored by the app. */
export const dev = {
  start: () => { try { Dv?.deviceStart(); } catch { /* optional feature */ } },
  hasPermission: (): Promise<boolean> => Dv.deviceHasPermission(),
  getState: (): Promise<any> => Dv.deviceGetState(),
  scanStart: (): Promise<boolean> => Dv.deviceScanStart(),
  scanStop: () => Dv.deviceScanStop(),
  scanResults: (): Promise<string> => Dv.deviceScanResults(),
  pair: (address: string): Promise<boolean> => Dv.devicePair(address),
  disconnect: () => Dv.deviceDisconnect(),
  /** Stops a pairing that is in progress and returns the link to a clean state. */
  cancelPair: () => Dv.deviceCancelPair(),
  /** Forgets the phone-side pairing and pairs afresh (the display must be in pairing mode). */
  repair: (): Promise<boolean> => Dv.deviceRepair(),
  reconnect: () => Dv.deviceReconnect(),
  unpair: (clearWifi: boolean): Promise<boolean> => Dv.deviceUnpair(clearWifi),
  setScreen: (id: number): Promise<boolean> => Dv.deviceSetScreen(id),
  syncNow: (): Promise<boolean> => Dv.deviceSyncNow(),
  setWifi: (ssid: string, pass: string): Promise<boolean> => Dv.deviceSetWifi(ssid, pass),
  clearWifi: (): Promise<boolean> => Dv.deviceClearWifi(),
  openBluetoothSettings: () => Dv.deviceOpenBluetoothSettings(),
};
export const devEvents = Dv ? new NativeEventEmitter(N) : (null as unknown as NativeEventEmitter);   // emitted through IslandModule

// ---- screen time / notifications / lyrics / custom HTML screen (DeviceModule.kt). Everything is JSON strings across the bridge. ----
export const deviceExtrasAvailable = !!(Dv && Dv.screensPick && Dv.deviceScreenTimeGet && Dv.deviceNotifsGet);
export type ScreenEntry = {
  id: string; name: string; slot: number; crc: string; size: number; frames: number; animated: boolean; loop: boolean; durationMs: number; updatedAt: number;
  settings: { scripts?: boolean; online?: boolean; animate?: boolean; fps?: number; durationMs?: number; loop?: string };
  onDevice: boolean; sending: boolean; queued: boolean; shown: boolean;
};
export type Library = {
  screens: ScreenEntry[]; auto: boolean; connected: boolean; max: number;
  device: { hasSlots: boolean; mask: number; active: number; freeKb: number; totalKb: number; fw: string; deviceOnly: number[]; inSync: boolean };
  transfer: { state: 'idle' | 'sending' | 'done' | 'error'; id: string; sent: number; total: number; message: string; queued: number };
};
export type PickedBundle = { token: string; htmlName: string; html: string; assets: { path: string; size: number }[]; warnings: string[] };
export type RenderedFrames = { loop: boolean; notes: string[]; frames: { x: number; y: number; w: number; h: number; delay: number; px: string }[] };
const parse = (s: any): any => { try { return JSON.parse(String(s)); } catch { return null; } };
export const devx = {
  screenTimeGet: async (): Promise<any> => parse(await Dv.deviceScreenTimeGet()),
  screenTimeSetGoal: (min: number): Promise<number> => Dv.deviceScreenTimeSetGoal(min),
  notifsGet: async (): Promise<{ enabled: boolean; access: boolean; count: number; preview: boolean } | null> => parse(await Dv.deviceNotifsGet()),
  notifsSetEnabled: (on: boolean): Promise<boolean> => Dv.deviceNotifsSetEnabled(on),
  notifsClear: (): Promise<boolean> => Dv.deviceNotifsClear(),
  lyricsGet: async (): Promise<{ provider: 'off' | 'lrclib'; state: number; detail: string } | null> => parse(await Dv.deviceLyricsGet()),
  captionsGet: async (): Promise<{ enabled: boolean; service: boolean; liveCaption: number; lines: number } | null> => parse(await Dv.deviceCaptionsGet()),
  captionsSetEnabled: (on: boolean): Promise<boolean> => Dv.deviceCaptionsSetEnabled(on),
  captionsOpenAccessibility: () => Dv.deviceCaptionsOpenAccessibility(),
  lyricsSetProvider: (name: 'off' | 'lrclib'): Promise<string> => Dv.deviceLyricsSetProvider(name),
  /** Several files at once (an .html with its images, or a .zip). Resolves null when cancelled. Pass a token to add files to an earlier selection. */
  screensPick: async (addToToken?: string): Promise<PickedBundle | null> => { const r = await Dv.screensPick(addToToken ?? null); return r ? parse(r) : null; },
  screensDiscard: (token: string) => { try { Dv.screensDiscard(token); } catch { /* ignore */ } },
  /** source = 'pending:<token>' or 'saved:<id>' */
  screensGetHtml: (source: string): Promise<string> => Dv.screensGetHtml(source),
  screensRender: async (source: string, preparedHtml: string, harness: string, settingsJson: string): Promise<RenderedFrames> => parse(await Dv.screensRender(source, preparedHtml, harness, settingsJson)),
  screensSave: async (id: string, token: string, name: string, settingsJson: string, deviceB64: string, frames: number, animated: boolean, loop: boolean, durationMs: number): Promise<{ saved: ScreenEntry; library: Library } | null> =>
    parse(await Dv.screensSave(id, token, name, settingsJson, deviceB64, frames, animated, loop, durationMs)),
  screensList: async (): Promise<Library | null> => parse(await Dv.screensList()),
  screensGetDevice: (id: string): Promise<string> => Dv.screensGetDevice(id),
  screensRename: async (id: string, name: string): Promise<Library | null> => parse(await Dv.screensRename(id, name)),
  screensRemove: async (id: string): Promise<Library | null> => parse(await Dv.screensRemove(id)),
  /** ids = [] sends every screen. */
  screensSend: (ids: string[]): Promise<boolean> => Dv.screensSend(JSON.stringify(ids)),
  screensCancel: () => Dv.screensCancel(),
  screensShow: (id: string): Promise<boolean> => Dv.screensShow(id),
  screensSetAuto: (on: boolean): Promise<boolean> => Dv.screensSetAuto(on),
  screensClearDeviceOnly: (): Promise<boolean> => Dv.screensClearDeviceOnly(),
};

export type AppInfo = { pkg: string; name: string; icon: string };
export type TimerInfo = { id: string; label: string; endAt: number; notifId: string };
export type Media = { active: boolean; title?: string; artist?: string; playing?: boolean; position?: number; duration?: number; canNext?: boolean; canPrev?: boolean; art?: string };

export const native = {
  hasOverlay: (): Promise<boolean> => N.hasOverlay(),
  requestOverlay: () => N.requestOverlay(),
  requestNotificationAccess: () => N.requestNotificationAccess(),
  requestUsageAccess: () => N.requestUsageAccess(),
  requestExactAlarm: () => N.requestExactAlarm(),
  openNotificationSettings: () => N.openNotificationSettings(),
  openBatterySettings: () => N.openBatterySettings(),
  hasAccessibility: (): Promise<boolean> => N.hasAccessibility(),
  requestAccessibility: () => N.requestAccessibility(),
  getLayer: (): Promise<string> => N.getLayer(),
  startIsland: (): Promise<boolean> => N.startIsland(),
  stopIsland: (): Promise<boolean> => N.stopIsland(),
  isRunning: (): Promise<boolean> => N.isRunning(),
  lastError: (): Promise<string> => N.lastError(),
  setConfig: (json: string) => N.setConfig(json),
  postActivity: (id: string, priority: number, title: string, sub: string, ttlMs: number) => N.postActivity(id, priority, title, sub, ttlMs),
  startTimer: (label: string, endAtMs: number, notifId: string): Promise<string> => N.startTimer(label, endAtMs, notifId),
  cancelTimer: (id: string): Promise<boolean> => N.cancelTimer(id),
  getTimers: (): Promise<TimerInfo[]> => N.getTimers(),
  setHealthConfig: (json: string) => N.setHealthConfig(json),
  getHealthSnapshot: (): Promise<string> => N.getHealthSnapshot(),
  pinWidget: (kind: 'clock' | 'round' | 'heat' | 'card' | 'music'): Promise<boolean> => N.pinWidget(kind),
  syncSteps: (): Promise<boolean> => N.syncSteps(),
  clearHealthData: (): Promise<boolean> => N.clearHealthData(),
  waterAction: (a: 'done' | 'snooze' | 'dismiss' | 'test') => N.waterAction(a),
  getMedia: (): Promise<Media> => N.getMedia(),
  mediaControl: (a: 'play' | 'pause' | 'next' | 'prev') => N.mediaControl(a),
  getInstalledApps: (): Promise<AppInfo[]> => N.getInstalledApps(),
  getUsageToday: (): Promise<Record<string, number>> => N.getUsageToday(),
};

export type Perms = { a11y: boolean; overlay: boolean; notif: boolean; listener: boolean; usage: boolean; exact: boolean; battery: boolean };
const NONE: Perms = { a11y: false, overlay: false, notif: false, listener: false, usage: false, exact: false, battery: false };

/** Live permission status; re-checked every time the app returns to the foreground. */
export function usePerms() {
  const [p, setP] = useState<Perms>(NONE);
  const [loading, setLoading] = useState(true);
  const refresh = useCallback(async () => {
    try {
      const [a11y, overlay, notif, listener, usage, exact, battery] = await Promise.all([
        N.hasAccessibility(), N.hasOverlay(), N.hasNotificationPermission(), N.hasNotificationAccess(), N.hasUsageAccess(), N.canScheduleExactAlarms(), N.isIgnoringBattery(),
      ]);
      setP({ a11y, overlay, notif, listener, usage, exact, battery });
    } catch { /* leave previous values */ }
    setLoading(false);
  }, []);
  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') refresh(); });
    return () => sub.remove();
  }, [refresh]);
  return { ...p, loading, refresh };
}
