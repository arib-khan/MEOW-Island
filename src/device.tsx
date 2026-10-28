import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, PermissionsAndroid, Platform } from 'react-native';
import { dev, deviceAvailable, devEvents } from './native';
import { DeviceState, EMPTY_DEVICE, Found, mergeDeviceState, parseFound } from './core/device';

/** Live state of the Meow Island display. Native owns everything (pairing, reconnecting, syncing); this hook only mirrors it and sends commands. */
export function useDevice() {
  const [state, setState] = useState<DeviceState>(EMPTY_DEVICE);
  const [found, setFound] = useState<Found[]>([]);
  const [ready, setReady] = useState(false);
  const alive = useRef(true);

  const refresh = useCallback(async () => {
    if (!deviceAvailable) { setReady(true); return; }
    try { const st = mergeDeviceState(await dev.getState()); if (alive.current) setState(st); } catch { /* keep what is shown */ }
    if (alive.current) setReady(true);
  }, []);

  useEffect(() => {
    alive.current = true;
    refresh();
    const sub = AppState.addEventListener('change', (a) => { if (a === 'active') refresh(); });
    const subs = devEvents ? [
      devEvents.addListener('deviceState', (e: any) => { setState(mergeDeviceState(e)); setReady(true); }),
      devEvents.addListener('deviceScan', (e: any) => setFound(parseFound(e?.json))),
    ] : [];
    return () => { alive.current = false; sub.remove(); subs.forEach((x) => x.remove()); try { dev.scanStop(); } catch { /* not scanning */ } };
  }, [refresh]);

  /** Asks Android for the Bluetooth permission this phone needs (Nearby devices on Android 12+, Location on older versions). */
  const askPermission = useCallback(async (): Promise<boolean> => {
    if (Platform.OS !== 'android') return false;
    try {
      if (Platform.Version >= 31) {
        const r = await PermissionsAndroid.requestMultiple([PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN, PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT]);
        return Object.values(r).every((v) => v === PermissionsAndroid.RESULTS.GRANTED);
      }
      return (await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION)) === PermissionsAndroid.RESULTS.GRANTED;
    } catch { return false; }
  }, []);

  const scan = useCallback(async () => {
    if (!(await dev.hasPermission()) && !(await askPermission())) { await refresh(); return false; }
    setFound([]);
    const ok = await dev.scanStart();
    await refresh();
    return ok;
  }, [askPermission, refresh]);

  return { state, found, ready, refresh, scan, askPermission };
}
