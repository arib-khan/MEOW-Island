import { NativeModules, NativeEventEmitter, AppState } from 'react-native';
import { useCallback, useEffect, useState } from 'react';

const N: any = NativeModules.IslandModule;
export const available = !!N;
/** False when the installed APK was built from older native code (e.g. prebuild --clean failed). */
export const nativeCurrent = !!N && (N.nativeVersion ?? 0) >= 5;
export const events = N ? new NativeEventEmitter(N) : (null as unknown as NativeEventEmitter);

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
  pinWidget: (kind: 'clock' | 'round' | 'heat'): Promise<boolean> => N.pinWidget(kind),
  syncSteps: (): Promise<boolean> => N.syncSteps(),
  clearHealthData: (): Promise<boolean> => N.clearHealthData(),
  waterAction: (a: 'done' | 'snooze' | 'dismiss' | 'test') => N.waterAction(a),
  getMedia: (): Promise<Media> => N.getMedia(),
  mediaControl: (a: 'play' | 'pause' | 'next' | 'prev') => N.mediaControl(a),
  getInstalledApps: (): Promise<AppInfo[]> => N.getInstalledApps(),
  getUsageToday: (): Promise<Record<string, number>> => N.getUsageToday(),
};

export type Perms = { overlay: boolean; notif: boolean; listener: boolean; usage: boolean; exact: boolean; battery: boolean };
const NONE: Perms = { overlay: false, notif: false, listener: false, usage: false, exact: false, battery: false };

/** Live permission status; re-checked every time the app returns to the foreground. */
export function usePerms() {
  const [p, setP] = useState<Perms>(NONE);
  const [loading, setLoading] = useState(true);
  const refresh = useCallback(async () => {
    try {
      const [overlay, notif, listener, usage, exact, battery] = await Promise.all([
        N.hasOverlay(), N.hasNotificationPermission(), N.hasNotificationAccess(), N.hasUsageAccess(), N.canScheduleExactAlarms(), N.isIgnoringBattery(),
      ]);
      setP({ overlay, notif, listener, usage, exact, battery });
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
