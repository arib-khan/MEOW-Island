import React, { useCallback, useEffect, useState } from 'react';
import { AppState, Text, View } from 'react-native';
import { Btn, C, Card, Chip, ErrorBox, H, Stepper, Sub } from '../ui';
import { dev, devx, deviceExtrasAvailable, native } from '../native';
import { EMPTY_SCREEN_TIME, GOAL_STEP_MIN, fmtDuration, goalPercent, goalText, mergeScreenTime, screenTimeStatus, stepGoal } from '../core/screentime';

/**
 * "Phone screen time" for the display: how long THIS PHONE was used today (Android UsageStats), the daily goal and the most-used apps.
 * It reads the same Usage Access data as the Guardian below. It is not the display's own on-time.
 */
export function PhoneScreenTimeCard({ usageAccess }: { usageAccess: boolean }) {
  const [s, setS] = useState(EMPTY_SCREEN_TIME);
  const [err, setErr] = useState('');
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    if (!deviceExtrasAvailable) return;
    try { setS(mergeScreenTime(await devx.screenTimeGet())); setErr(''); } catch (e: any) { setErr(e?.message ?? 'Could not read screen time'); }
    setLoaded(true);
  }, []);
  useEffect(() => {
    load();
    const id = setInterval(load, 15000);
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') load(); });
    return () => { clearInterval(id); sub.remove(); };
  }, [load, usageAccess]);

  if (!deviceExtrasAvailable) return <Card><H>Phone screen time on the display</H><Sub>This APK was built before the screen-time display was added. Rebuild the app (see the README) to use it.</Sub></Card>;

  const status = screenTimeStatus(s);
  const pct = Math.min(100, goalPercent(s.totalMin, s.goalMin));
  const over = s.goalMin > 0 && s.totalMin > s.goalMin;
  const setGoal = async (v: number) => { try { setS({ ...s, goalMin: await devx.screenTimeSetGoal(v) }); } catch (e: any) { setErr(e?.message ?? 'Could not save the goal'); } };
  const maxApp = Math.max(1, ...s.apps.map((a) => a.min));

  return (
    <Card>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <H>Phone screen time</H>
        <Chip ok={s.access && s.ok} label={s.access ? (s.ok ? 'Showing on display' : 'No data') : 'Usage access off'} />
      </View>
      <Sub>How long this phone has been used today. The display's Screen time screen shows the same numbers. It is not how long the display itself has been on.</Sub>

      {s.access && s.ok && (
        <View style={{ marginTop: 10 }}>
          <Text style={{ color: over ? C.bad : C.text, fontSize: 34, fontWeight: '800' }} accessibilityLabel={`Phone screen time today ${fmtDuration(s.totalMin)}`}>{fmtDuration(s.totalMin)}</Text>
          {s.goalMin > 0 && (
            <>
              <View style={{ height: 10, borderRadius: 5, backgroundColor: C.card2, marginTop: 8, overflow: 'hidden' }} accessibilityRole="progressbar" accessibilityValue={{ min: 0, max: 100, now: pct }}>
                <View style={{ width: `${pct}%`, height: 10, backgroundColor: over ? C.bad : C.accent }} />
              </View>
              <Sub>{goalText(s.totalMin, s.goalMin)}</Sub>
            </>
          )}
          {s.apps.slice(0, 5).map((a) => (
            <View key={a.name} style={{ marginTop: 8 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}><Text style={{ color: C.text }} numberOfLines={1}>{a.name}</Text><Text style={{ color: C.sub }}>{fmtDuration(a.min)}</Text></View>
              <View style={{ height: 4, borderRadius: 2, backgroundColor: C.card2, marginTop: 4 }}><View style={{ width: `${Math.max(3, Math.round((a.min * 100) / maxApp))}%`, height: 4, borderRadius: 2, backgroundColor: C.accent2 }} /></View>
            </View>
          ))}
          {s.apps.length === 0 && <Sub>No app has been used for a full minute yet today.</Sub>}
        </View>
      )}

      <View style={{ marginTop: 10 }}>
        <Stepper label="Daily goal" value={s.goalMin} min={0} max={960} step={GOAL_STEP_MIN} unit={s.goalMin === 0 ? ' (off)' : ' min'} onChange={(v) => setGoal(v === s.goalMin ? v : stepGoal(s.goalMin, v > s.goalMin ? 1 : -1))} />
        <Sub>{s.goalMin > 0 ? `Goal: ${fmtDuration(s.goalMin)} a day. It counts down from your phone's local midnight.` : 'No goal: the display shows the total only.'}</Sub>
      </View>

      {!s.access && (
        <View style={{ marginTop: 8 }}>
          <Sub>Usage Access lets Meow Island read how long each app was in the foreground. Android keeps this on your phone and Meow Island never uploads it. On the next screen choose Meow Island and allow access.</Sub>
          <View style={{ height: 8 }} /><Btn title="Open Usage Access settings" onPress={() => native.requestUsageAccess()} />
        </View>
      )}
      {s.access && <Sub>{status.text}</Sub>}
      {!!err && <ErrorBox text={err} />}
      <View style={{ marginTop: 8, flexDirection: 'row', gap: 8, flexWrap: 'wrap' }}>
        <Btn small kind="ghost" title="Refresh" onPress={load} />
        <Btn small kind="ghost" title="Show on display" onPress={() => { dev.setScreen(3).catch((e: any) => setErr(e?.message ?? 'The display is not connected')); }} />
      </View>
      {!loaded && <Sub>Reading usage…</Sub>}
    </Card>
  );
}
