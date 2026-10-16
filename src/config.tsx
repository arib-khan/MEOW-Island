import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { getSetting, setSetting } from './core/db';
import { events, native } from './native';

export type LimitItem = { pkg: string; name: string; limitMin: number; enabled: boolean };
export type Config = {
  w: number; h: number; r: number; bg: string; fg: string; expandMs: number; animMs: number;
  notifEnabled: boolean; notifPreview: boolean; musicEnabled: boolean; guardEnabled: boolean;
  angry: string; happy: string; warnText: string; happyMs: number; limits: LimitItem[];
  fx: number; fy: number; posArea: 'usable' | 'screen'; dragEnabled: boolean; hideFullscreen: boolean; ears: boolean; remindMin: number;
};
export const DEFAULTS: Config = {
  w: 120, h: 34, r: 22, bg: '#000000', fg: '#FFFFFF', expandMs: 5000, animMs: 280,
  notifEnabled: true, notifPreview: true, musicEnabled: true, guardEnabled: true,
  angry: '\u{1F63E}', happy: '\u{1F63A}', warnText: 'Daily limit reached', happyMs: 3000, limits: [],
  fx: 0.5, fy: 0, posArea: 'usable', dragEnabled: true, hideFullscreen: true, ears: true, remindMin: 5,
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
      try { merged = { ...DEFAULTS, ...(await getSetting<Partial<Config>>('config', {})) }; } catch { /* use defaults */ }
      ref.current = merged; setCfg(merged);
      try { native.setConfig(toNative(merged)); } catch { /* native side reports its own errors */ }
      setReady(true);
    })();
  }, []);
  // Dragging the real island on screen saves the new position natively; mirror it into JS state/storage.
  useEffect(() => {
    const sub = events.addListener('position', (p: { fx: number; fy: number }) => {
      const n = { ...ref.current, fx: p.fx, fy: p.fy };
      ref.current = n; setCfg(n); setSetting('config', n);
    });
    return () => sub.remove();
  }, []);
  const commit = (n: Config) => { ref.current = n; setCfg(n); setSetting('config', n); try { native.setConfig(toNative(n)); } catch { /* ignore */ } };
  const update = useCallback((p: Partial<Config>) => commit({ ...ref.current, ...p }), []);
  const reset = useCallback(() => commit({ ...DEFAULTS, limits: ref.current.limits, fx: ref.current.fx, fy: ref.current.fy }), []);
  if (!ready) return null;
  return <Ctx.Provider value={{ cfg, update, reset }}>{children}</Ctx.Provider>;
}
