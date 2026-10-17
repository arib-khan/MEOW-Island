import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { getSetting, setSetting } from './core/db';
import { events, native } from './native';
import { DayRec, HEALTH_DEFAULTS, HealthCfg, WaterSettings, mergeHealth } from './core/health';

export type Snapshot = {
  day: string; steps: number; goal: number; partial: boolean; hasToday: boolean; days: Record<string, DayRec>;
  status: { counter: boolean; detector: boolean; permission: boolean; sdk: number; live: boolean };
  water: { glasses: number; nextAt: number; snoozeAt: number };
};
type Ctx = {
  cfg: HealthCfg; snap: Snapshot | null; refresh: () => Promise<void>;
  update: (p: Partial<HealthCfg>) => void; updateWater: (p: Partial<WaterSettings>) => void; resetWater: () => void; clearData: () => Promise<void>;
};
const C = createContext<Ctx>({ cfg: HEALTH_DEFAULTS, snap: null, refresh: async () => {}, update: () => {}, updateWater: () => {}, resetWater: () => {}, clearData: async () => {} });
export const useHealth = () => useContext(C);

/** All health settings and readings stay on the device: SQLite settings + native SharedPreferences. Nothing is sent anywhere. */
export function HealthProvider({ children }: { children: React.ReactNode }) {
  const [cfg, setCfg] = useState<HealthCfg>(HEALTH_DEFAULTS);
  const [snap, setSnap] = useState<Snapshot | null>(null);
  const ref = useRef<HealthCfg>(HEALTH_DEFAULTS);
  const lastRefresh = useRef(0);

  const refresh = useCallback(async () => {
    lastRefresh.current = Date.now();
    try { setSnap(JSON.parse(await native.getHealthSnapshot())); } catch { /* keep the last snapshot */ }
  }, []);

  const syncAndRefresh = useCallback(async () => { try { await native.syncSteps(); } catch { /* ignore */ } await refresh(); }, [refresh]);

  useEffect(() => {
    (async () => {
      let merged = HEALTH_DEFAULTS;
      try { merged = mergeHealth(await getSetting<unknown>('health', {})); } catch { /* defaults = everything off */ }
      ref.current = merged; setCfg(merged);
      try { native.setHealthConfig(JSON.stringify(merged)); } catch { /* ignore */ }
      syncAndRefresh();
    })();
    const sub = events.addListener('health', () => { if (Date.now() - lastRefresh.current > 1500) refresh(); });
    const app = AppState.addEventListener('change', (st) => { if (st === 'active') syncAndRefresh(); });
    const id = setInterval(refresh, 20000);
    return () => { sub.remove(); app.remove(); clearInterval(id); };
  }, [refresh, syncAndRefresh]);

  const commit = useCallback((n: HealthCfg) => {
    ref.current = n; setCfg(n); setSetting('health', n);
    try { native.setHealthConfig(JSON.stringify(n)); } catch { /* ignore */ }
    setTimeout(refresh, 400);
  }, [refresh]);
  const update = useCallback((p: Partial<HealthCfg>) => commit(mergeHealth({ ...ref.current, ...p })), [commit]);
  const updateWater = useCallback((p: Partial<WaterSettings>) => commit(mergeHealth({ ...ref.current, water: { ...ref.current.water, ...p } })), [commit]);
  const resetWater = useCallback(() => commit({ ...ref.current, water: HEALTH_DEFAULTS.water }), [commit]);
  const clearData = useCallback(async () => { try { await native.clearHealthData(); } finally { refresh(); } }, [refresh]);
  return <C.Provider value={{ cfg, snap, refresh, update, updateWater, resetWater, clearData }}>{children}</C.Provider>;
}
