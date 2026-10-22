import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Modal, Text, TextInput, View } from 'react-native';
import { AdultStatus, ShortsStatus, wb, wbEvents, wellbeingAvailable } from './native';
import { AdultConfig, DayRec, DEFAULT_ADULT, DEFAULT_SHORTS, mergeAdult, mergeShorts, parseDays, ShortsConfig } from './core/wellbeing';
import { Btn, C, Sub } from './ui';

type Ctx = {
  ready: boolean; shorts: ShortsConfig; adult: AdultConfig; status: ShortsStatus | null; adultStatus: AdultStatus | null; days: Record<string, DayRec>;
  setShorts: (n: ShortsConfig) => Promise<void>; setAdult: (n: AdultConfig) => Promise<boolean>; refresh: () => Promise<void>;
  askPin: (msg?: string) => Promise<string | null>;
};
const Wb = createContext<Ctx | null>(null);
export const useWellbeing = () => useContext(Wb)!;

/** Pull-based state: native owns the settings and the counts, so this provider only mirrors them and re-reads when something changes. */
export function WellbeingProvider({ children }: { children: React.ReactNode }) {
  const [ready, setReady] = useState(false);
  const [shorts, setS] = useState<ShortsConfig>(DEFAULT_SHORTS);
  const [adult, setA] = useState<AdultConfig>(DEFAULT_ADULT);
  const [status, setStatus] = useState<ShortsStatus | null>(null);
  const [adultStatus, setAdultStatus] = useState<AdultStatus | null>(null);
  const [days, setDays] = useState<Record<string, DayRec>>({});
  const [pinReq, setPinReq] = useState<{ msg?: string; done: (p: string | null) => void } | null>(null);
  const queue = useRef<Promise<unknown>>(Promise.resolve());

  const refresh = useCallback(async () => {
    if (!wellbeingAvailable) { setReady(true); return; }
    try {
      const [sc, ac, st, as, d] = await Promise.all([wb.getShortsConfig(), wb.getAdultConfig(), wb.getShortsStatus(), wb.getAdultStatus(), wb.getShortsStats()]);
      setS(mergeShorts(JSON.parse(sc || '{}'))); setA(mergeAdult(JSON.parse(ac || '{}'))); setStatus(st); setAdultStatus(as); setDays(parseDays(d));
    } catch { /* keep what is shown */ }
    setReady(true);
  }, []);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (s) => { if (s === 'active') refresh(); });
    const subs = wbEvents ? ['shortsCount', 'shortsState', 'adultState'].map((e) => wbEvents.addListener(e, () => refresh())) : [];
    return () => { sub.remove(); subs.forEach((x) => x.remove()); };
  }, [refresh]);

  const askPin = useCallback((msg?: string) => new Promise<string | null>((done) => setPinReq({ msg, done })), []);

  /** Runs a native write; when native asks for the PIN, prompts and retries. Returns undefined if the user cancelled. */
  const withPin = useCallback(async (fn: (pin?: string) => Promise<string>): Promise<string | undefined> => {
    try { return await fn(undefined); } catch (e: any) {
      if (e?.code !== 'PIN_REQUIRED' && e?.code !== 'PIN_WRONG') throw e;
      let msg: string | undefined = e.code === 'PIN_WRONG' ? 'That PIN is not right.' : 'This change needs your PIN.';
      for (;;) {
        const pin = await askPin(msg);
        if (pin == null) return undefined;
        try { return await fn(pin); } catch (e2: any) { if (e2?.code === 'PIN_WRONG') msg = 'That PIN is not right.'; else throw e2; }
      }
    }
  }, [askPin]);

  const setShorts = useCallback(async (n: ShortsConfig) => {
    setS(n);                                                    // optimistic; native may still refuse (PIN), then we re-read the truth
    const job = queue.current.then(async () => {
      try { const r = await withPin((pin) => wb.setShortsConfig(JSON.stringify(n), pin)); if (r === undefined) await refresh(); else setS(mergeShorts(JSON.parse(r))); }
      catch (e: any) { Alert.alert('Could not save', String(e?.message ?? e)); await refresh(); }
    });
    queue.current = job; await job;
  }, [withPin, refresh]);

  const setAdult = useCallback(async (n: AdultConfig) => {
    setA(n);
    let ok = false;
    const job = queue.current.then(async () => {
      try { const r = await withPin((pin) => wb.setAdultConfig(JSON.stringify(n), pin)); if (r !== undefined) { setA(mergeAdult(JSON.parse(r))); ok = true; } }
      catch (e: any) { Alert.alert('Could not save', e?.code === 'NO_VPN_PERMISSION' ? 'Android has not given Meow Island the VPN permission yet.' : String(e?.message ?? e)); }
      await refresh();
    });
    queue.current = job; await job; return ok;
  }, [withPin, refresh]);

  return (
    <Wb.Provider value={{ ready, shorts, adult, status, adultStatus, days, setShorts, setAdult, refresh, askPin }}>
      {children}
      <PinModal req={pinReq} close={(p) => { const r = pinReq; setPinReq(null); r?.done(p); }} />
    </Wb.Provider>
  );
}

function PinModal({ req, close }: { req: { msg?: string } | null; close: (p: string | null) => void }) {
  const [v, setV] = useState('');
  useEffect(() => { if (req) setV(''); }, [req]);
  return (
    <Modal transparent visible={!!req} animationType="fade" onRequestClose={() => close(null)}>
      <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.7)', justifyContent: 'center', padding: 24 }}>
        <View style={{ backgroundColor: C.card, borderRadius: 22, padding: 20, borderWidth: 1, borderColor: C.line }}>
          <Text accessibilityRole="header" style={{ color: C.text, fontSize: 20, fontWeight: '800', marginBottom: 6 }}>Enter your PIN</Text>
          {!!req?.msg && <Sub>{req.msg}</Sub>}
          <TextInput value={v} onChangeText={(t) => setV(t.replace(/\D/g, '').slice(0, 8))} keyboardType="number-pad" secureTextEntry autoFocus maxLength={8} accessibilityLabel="PIN"
            style={{ color: C.text, backgroundColor: C.card2, borderRadius: 12, padding: 14, minHeight: 52, fontSize: 22, letterSpacing: 6, textAlign: 'center', marginVertical: 14 }} />
          <View style={{ flexDirection: 'row', gap: 10, justifyContent: 'flex-end' }}><Btn kind="ghost" title="Cancel" onPress={() => close(null)} /><Btn title="OK" disabled={v.length < 4} onPress={() => close(v)} /></View>
        </View>
      </View>
    </Modal>
  );
}
