import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { getSetting, setSetting } from './core/db';
import { native } from './native';
import { CONFIG_VERSION, migrateConfig } from './core/migrate';

export type LimitItem = { pkg: string; name: string; limitMin: number; enabled: boolean };
export type Config = {
  w: number; h: number; r: number; bg: string; fg: string; expandMs: number; animMs: number;
  notifEnabled: boolean; notifPreview: boolean; musicEnabled: boolean; guardEnabled: boolean;
  angry: string; happy: string; warnText: string; happyMs: number; limits: LimitItem[];
  fx: number; fy: number; posArea: 'statusbar' | 'usable' | 'screen'; hideFullscreen: boolean; ears: boolean; remindMin: number; cfgVersion: number;
};
export const DEFAULTS: Config = {
  w: 120, h: 34, r: 22, bg: '#000000', fg: '#FFFFFF', expandMs: 5000, animMs: 280,
  notifEnabled: true, notifPreview: true, musicEnabled: true, guardEnabled: true,
  angry: '\u{1F63E}', happy: '\u{1F63A}', warnText: 'Daily limit reached', happyMs: 3000, limits: [],
  fx: 0.5, fy: 0, posArea: 'statusbar', hideFullscreen: true, ears: true, remindMin: 5, cfgVersion: CONFIG_VERSION,
};
const hexToInt = (h: string) => (parseInt(h.replace('#', ''), 16) | 0xff000000) | 0;
const toNative = (c: Config) => JSON.stringify({ ...c, bg: hexToInt(c.bg), fg: hexToInt(c.fg) });

const Ctx = createContext<{ cfg: Config; update: (p: Partial<Config>) => void; reset: () => void }>({ cfg: DEFAULTS, update: () => {}, reset: () => {} });
export const useConfig = () => useContext(Ctx);

export function ConfigProvider({ children }: { children: React.ReactNode }) {
  const [cfg, setCfg] = useState<Config>(DEFAULTS);
  const [ready, setReady] = useState(false);
  const ref = useRef<Config>(DEFAULTS);
  useEffect(() => {
    (async () => {
      let merged = DEFAULTS;
      try { merged = { ...DEFAULTS, ...migrateConfig(await getSetting<Partial<Config>>('config', {})) }; } catch { /* use defaults */ }
      ref.current = merged; setCfg(merged);
      try { native.setConfig(toNative(merged)); } catch { /* native side reports its own errors */ }
      setReady(true);
    })();
  }, []);
  const commit = (n: Config) => { ref.current = n; setCfg(n); setSetting('config', n); try { native.setConfig(toNative(n)); } catch { /* ignore */ } };
  const update = useCallback((p: Partial<Config>) => commit({ ...ref.current, ...p }), []);
  const reset = useCallback(() => commit({ ...DEFAULTS, limits: ref.current.limits, fx: ref.current.fx, fy: ref.current.fy }), []);
  if (!ready) return null;
  return <Ctx.Provider value={{ cfg, update, reset }}>{children}</Ctx.Provider>;
}
