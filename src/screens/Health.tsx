import React, { useState } from 'react';
import { Alert, Linking, PermissionsAndroid, Platform, Pressable, Text, View } from 'react-native';
import * as Notifications from 'expo-notifications';
import { Btn, C, Card, Chip, ErrorBox, H, Loading, Stepper, Sub, Toggle, s } from '../ui';
import { native } from '../native';
import { useHealth } from '../health';
import { buildGrid, fmt, hhmm, pad2, pct, dayStatus, DayRec, ymd } from '../core/health';

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const LEVEL_BG: Record<number, string> = { 0: '#15101F', 1: '#15101F', 2: '#2F2648', 3: '#6C55B0', 4: '#B79CFF' };
const STATUS_TEXT = { complete: 'Goal reached', missed: 'Below goal', unknown: 'Incomplete data (tracking was not running all day)', nodata: 'No data recorded' };

/** hh:mm row with − / + in 30 minute steps. */
function TimeRow({ label, value, onChange }: { label: string; value: number; onChange: (v: number) => void }) {
  const set = (v: number) => onChange(Math.max(0, Math.min(1439, v)));
  return (
    <View style={s.row}>
      <Text style={[s.body, { flex: 1 }]}>{label}</Text>
      <Pressable accessibilityLabel={`Earlier ${label}`} onPress={() => set(value - 30)} style={s.step}><Text style={s.stepT}>−</Text></Pressable>
      <Text style={[s.body, { minWidth: 64, textAlign: 'center' }]}>{hhmm(value)}</Text>
      <Pressable accessibilityLabel={`Later ${label}`} onPress={() => set(value + 30)} style={s.step}><Text style={s.stepT}>+</Text></Pressable>
    </View>
  );
}
const Pill = ({ on, label, onPress }: { on: boolean; label: string; onPress: () => void }) => (
  <Pressable accessibilityRole="button" accessibilityState={{ selected: on }} onPress={onPress} style={{ paddingHorizontal: 14, minHeight: 40, justifyContent: 'center', borderRadius: 20, backgroundColor: on ? C.accent : C.card2 }}>
    <Text style={{ color: on ? C.onAccent : C.text, fontWeight: '600' }}>{label}</Text>
  </Pressable>
);

export default function Health() {
  const { cfg, snap, update, updateWater, resetWater, clearData, refresh } = useHealth();
  const [weeks, setWeeks] = useState(5);
  const [picked, setPicked] = useState<{ key: string; rec?: DayRec } | null>(null);
  const [busy, setBusy] = useState(false);
  const w = cfg.water;

  /** Explains first, then asks for the one runtime permission the step sensor needs. Nothing is requested before this. */
  const needsSensorConsent = (): Promise<boolean> => new Promise(async (resolve) => {
    let st: { counter: boolean; detector: boolean; permission: boolean } | undefined;
    try { st = JSON.parse(await native.getHealthSnapshot()).status; } catch { /* unknown: native re-checks permission before counting anything */ }
    if (st && !st.counter && !st.detector) {
      Alert.alert('Step sensor not available', 'This phone does not report a hardware step sensor, so step tracking cannot work reliably here. Nothing was turned on.');
      return resolve(false);
    }
    if (st?.permission !== false) return resolve(true);
    Alert.alert('Allow step counting?', 'Meow Island reads your phone’s step sensor to count steps. Android calls this “Physical activity” permission. Readings stay on this phone and are never uploaded. You can turn this off at any time.', [
      { text: 'Not now', style: 'cancel', onPress: () => resolve(false) },
      { text: 'Continue', onPress: async () => {
        let ok = Platform.OS !== 'android' || Number(Platform.Version) < 29;
        if (!ok) { const r = await PermissionsAndroid.request('android.permission.ACTIVITY_RECOGNITION' as any); ok = r === PermissionsAndroid.RESULTS.GRANTED; }
        if (!ok) Alert.alert('Permission needed', 'Without Physical activity permission steps cannot be counted. You can allow it in Android settings.', [{ text: 'Cancel', style: 'cancel' }, { text: 'Open settings', onPress: () => Linking.openSettings() }]);
        await refresh(); resolve(ok);
      } },
    ]);
  });
  const toggleStep = async (key: 'stepCounter' | 'walkingIsland' | 'goalEnabled' | 'heatmap', on: boolean) => {
    if (on && !(await needsSensorConsent())) return;
    update({ [key]: on } as any);
  };
  const askNotifications = async (): Promise<boolean> => { let p = await Notifications.getPermissionsAsync(); if (!p.granted) p = await Notifications.requestPermissionsAsync(); return p.granted; };
  const toggleWater = (on: boolean) => {
    if (!on) return updateWater({ enabled: false });
    Alert.alert('Turn on water reminders?', 'Meow Island will remind you to drink water on the schedule you set, as a notification and/or on the island. This is a simple reminder, not medical advice. Android battery saving can delay reminders by a few minutes.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Turn on', onPress: async () => {
        let n = w.toNotification;
        if (n && !(await askNotifications())) { n = false; Alert.alert('Notifications are blocked', 'Reminders will appear on the island only while it is running. You can allow notifications in Android settings.'); }
        updateWater({ enabled: true, toNotification: n });
      } },
    ]);
  };
  const confirmClear = () => Alert.alert('Clear health data?', 'This permanently deletes the step history, contribution records and water log stored on this phone. Your settings stay. This cannot be undone.', [
    { text: 'Cancel', style: 'cancel' },
    { text: 'Clear', style: 'destructive', onPress: async () => { setBusy(true); await clearData(); setPicked(null); setBusy(false); } },
  ]);
  const confirmDeleteSchedule = () => Alert.alert('Delete water schedule?', 'Reminders stop and the schedule goes back to defaults.', [{ text: 'Keep', style: 'cancel' }, { text: 'Delete', style: 'destructive', onPress: resetWater }]);

  const st = snap?.status;
  const goalOn = cfg.goalEnabled;
  const steps = snap?.steps ?? 0, goal = snap?.goal ?? cfg.goal;
  const stepsCard = () => {
    if (!cfg.stepCounter) return <Sub>Turn on “Step counter” above to see today’s steps here.</Sub>;
    if (!snap) return <Loading label="Reading sensor…" />;
    if (st && !st.counter && !st.detector) return <ErrorBox text="This phone has no step sensor, so steps cannot be tracked." />;
    if (st && !st.permission) return <View><ErrorBox text="Physical activity permission is required to count steps." /><Btn title="Grant permission" onPress={async () => { if (await needsSensorConsent()) update({}); }} /></View>;
    if (!snap.hasToday) return <Sub>Waiting for the first sensor reading. Steps taken before tracking started are never guessed.</Sub>;
    return (
      <View>
        <Text style={{ color: C.text, fontSize: 28, fontWeight: '900' }}>{fmt(steps)}{goalOn ? <Text style={{ color: C.sub, fontSize: 18 }}>{` / ${fmt(goal)} steps`}</Text> : <Text style={{ color: C.sub, fontSize: 18 }}> steps</Text>}</Text>
        {goalOn && <>
          <View style={{ height: 8, backgroundColor: C.line, borderRadius: 4, marginVertical: 10 }}><View style={{ width: `${pct(steps, goal)}%`, height: 8, borderRadius: 4, backgroundColor: C.accent }} /></View>
          <Sub>{steps >= goal ? 'Goal reached today 😺' : `${fmt(goal - steps)} steps remaining · ${pct(steps, goal)}% of your goal`}</Sub>
        </>}
        {snap.partial && <Sub>Tracking began part-way through today, so earlier steps are not included.</Sub>}
        {st && !st.counter && st.detector && <Sub>This phone only has a step detector: steps are counted only while Meow Island is running.</Sub>}
      </View>
    );
  };

  const grid = snap && cfg.heatmap ? buildGrid(new Date(), snap.days, weeks) : null;
  const cell = weeks > 5 ? 20 : 34, gap = weeks > 5 ? 3 : 5;

  return (
    <View>
      <Card>
        <H>Health & Wellness</H>
        <Sub>Everything here is optional and off until you turn it on. Step counts, goals, history and reminders stay on this phone: no account, no cloud, no analytics. Turning one feature off does not turn off the others.</Sub>
      </Card>

      <Card>
        <H>Steps</H>
        <Toggle label="Step counter" hint="Counts today’s steps from your phone’s step sensor." value={cfg.stepCounter} onChange={(v) => toggleStep('stepCounter', v)} />
        <Toggle label="Walking progress island" hint="While you walk, the island becomes a slim purple progress bar. It returns to normal after you stop." value={cfg.walkingIsland} onChange={(v) => toggleStep('walkingIsland', v)} />
        <Toggle label="Daily step goal" hint="Shows goal progress and celebrates once when you reach it." value={cfg.goalEnabled} onChange={(v) => toggleStep('goalEnabled', v)} />
        <Toggle label="Contribution heatmap" hint="A calendar of the days you reached your goal." value={cfg.heatmap} onChange={(v) => toggleStep('heatmap', v)} />
        {cfg.heatmap && <Toggle label="Heatmap preview on island" hint="Shows the last 7 days when the walking/goal island is expanded." value={cfg.heatmapOnIsland} onChange={(v) => update({ heatmapOnIsland: v })} />}
        <Sub>Step features share one local sensor reader, which runs only while at least one of them is on. Detection depends on your phone’s sensor and can be delayed or inaccurate.</Sub>
        {(cfg.stepCounter || cfg.walkingIsland || cfg.goalEnabled || cfg.heatmap) && <>
          <View style={{ height: 6 }} />
          <Toggle label="Count steps in the background" hint="Keeps your totals current while the phone is locked or the app is closed. Uses the hardware step counter in batches (very little battery) and shows one small silent notification. Off: totals catch up about every 30 minutes and when you open the app." value={cfg.backgroundSteps} onChange={(v) => update({ backgroundSteps: v })} />
          <Sub>{cfg.backgroundSteps ? (snap?.status.bg ? 'Background counting: running. Your totals refresh when you unlock the phone.' : 'Background counting: not running yet (needs the Physical activity permission). Some phones stop background apps: if so, totals still catch up on the next reading.') : 'Background counting is off.'}</Sub>
        </>}
        <View style={{ height: 8 }} />
        <Stepper label="Daily goal" value={cfg.goal} min={500} max={50000} step={500} unit=" steps" onChange={(v) => update({ goal: v })} />
        <Sub>Changing the goal updates today only. Past days keep the goal they ended with, so history stays accurate.</Sub>
        {cfg.walkingIsland && <>
          <Text style={{ color: C.text, marginTop: 10 }}>Walking detection</Text>
          <View style={{ flexDirection: 'row', gap: 8, marginVertical: 6 }}>
            {(['low', 'normal', 'high'] as const).map((k) => <Pill key={k} on={cfg.walkSensitivity === k} label={k === 'low' ? 'Calmer' : k === 'high' ? 'Quicker' : 'Normal'} onPress={() => update({ walkSensitivity: k })} />)}
          </View>
          <Stepper label="Return to normal after" value={cfg.walkReturnSec} min={3} max={60} step={1} unit=" s" onChange={(v) => update({ walkReturnSec: v })} />
        </>}
      </Card>

      {cfg.stepCounter && <Card><H>Today</H>{stepsCard()}</Card>}

      {cfg.heatmap && (
        <Card>
          <H>Goal history</H>
          {!snap ? <Loading /> : (
            <View>
              <View style={{ flexDirection: 'row' }}>
                <View style={{ marginRight: 6 }}>{DAYS.map((d, i) => <View key={d} style={{ height: cell, marginBottom: gap, justifyContent: 'center' }}><Text style={{ color: C.sub, fontSize: 10 }}>{i % 2 === 0 ? d[0] : ''}</Text></View>)}</View>
                {grid!.map((col, ci) => (
                  <View key={ci} style={{ marginRight: gap }}>
                    {col.map((c) => (
                      <Pressable key={c.key} disabled={c.future} accessibilityLabel={`${c.key}: ${c.rec ? fmt(c.rec.s) + ' steps' : 'no data'}`} onPress={() => setPicked({ key: c.key, rec: c.rec })}
                        style={{ width: cell, height: cell, marginBottom: gap, borderRadius: 6, backgroundColor: c.future ? 'transparent' : LEVEL_BG[c.level], borderWidth: c.future ? 0 : c.level === 4 ? 2 : 1, borderStyle: c.level === 1 || c.level === 0 ? 'dashed' : 'solid', borderColor: c.level === 4 ? '#E4D8FF' : c.level === 1 ? C.accent2 : C.line, opacity: picked?.key === c.key ? 0.7 : 1 }} />
                    ))}
                  </View>
                ))}
              </View>
              <View style={{ flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 10, marginTop: 6 }}>
                {([[0, 'No data'], [1, 'Incomplete'], [2, '< 50%'], [3, '50–99%'], [4, 'Goal']] as [number, string][]).map(([l, t]) => (
                  <View key={t} style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}><View style={{ width: 12, height: 12, borderRadius: 3, backgroundColor: LEVEL_BG[l], borderWidth: 1, borderStyle: l <= 1 ? 'dashed' : 'solid', borderColor: l === 1 ? C.accent2 : l === 4 ? '#E4D8FF' : C.line }} /><Text style={{ color: C.sub, fontSize: 11 }}>{t}</Text></View>
                ))}
              </View>
              <Text style={{ color: C.text, marginTop: 10, minHeight: 40 }}>
                {picked ? `${picked.key} · ${picked.rec ? fmt(picked.rec.s) + ' / ' + fmt(picked.rec.g) + ' steps' : 'no steps recorded'} · ${STATUS_TEXT[dayStatus(picked.rec)]}` : 'Tap a day to see its steps.'}
              </Text>
              <View style={{ alignSelf: 'flex-start' }}><Btn small kind="ghost" title={weeks > 5 ? 'Show 5 weeks' : 'Show 12 weeks'} onPress={() => setWeeks(weeks > 5 ? 5 : 12)} /></View>
              <Sub>Days are your phone’s local calendar days. A day with incomplete tracking is never counted as a missed goal.</Sub>
            </View>
          )}
        </Card>
      )}

      <Card>
        <H>Water reminders</H>
        <Toggle label="Remind me to drink water" hint="Independent of step tracking. A simple reminder on your own schedule." value={w.enabled} onChange={toggleWater} />
        {w.enabled && (
          <View>
            <Text style={{ color: C.text, marginTop: 8 }}>Repeat every</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginVertical: 6 }}>
              {[[30, '30 min'], [60, '1 hour'], [120, '2 hours'], [180, '3 hours']].map(([m, l]) => <Pill key={m} on={w.intervalMin === m} label={l as string} onPress={() => updateWater({ intervalMin: m as number })} />)}
            </View>
            <Stepper label="Custom interval" value={w.intervalMin} min={15} max={480} step={15} unit=" min" onChange={(v) => updateWater({ intervalMin: v })} />
            <TimeRow label="Start" value={w.startMin} onChange={(v) => updateWater({ startMin: Math.min(v, w.endMin) })} />
            <TimeRow label="End" value={w.endMin} onChange={(v) => updateWater({ endMin: Math.max(v, w.startMin) })} />
            <Text style={{ color: C.text, marginTop: 8 }}>Active days</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginVertical: 6 }}>
              {DAYS.map((d, i) => <Pill key={d} on={w.days[i]} label={d} onPress={() => updateWater({ days: w.days.map((x, j) => (j === i ? !x : x)) })} />)}
            </View>
            <Toggle label="Quiet period" hint="No reminders during this window (can cross midnight)." value={w.quietEnabled} onChange={(v) => updateWater({ quietEnabled: v })} />
            {w.quietEnabled && <><TimeRow label="Quiet from" value={w.quietStartMin} onChange={(v) => updateWater({ quietStartMin: v })} /><TimeRow label="Quiet until" value={w.quietEndMin} onChange={(v) => updateWater({ quietEndMin: v })} /></>}
            <Toggle label="Show on the island" value={w.toIsland} onChange={(v) => updateWater({ toIsland: v })} />
            <Toggle label="Send an Android notification" value={w.toNotification} onChange={async (v) => { if (v && !(await askNotifications())) { Alert.alert('Notifications are blocked', 'Allow notifications for Meow Island in Android settings.', [{ text: 'Cancel', style: 'cancel' }, { text: 'Open settings', onPress: () => native.openNotificationSettings() }]); return; } updateWater({ toNotification: v }); }} />
            {w.toIsland && !w.toNotification && <Sub>Island-only reminders appear only while the island is running.</Sub>}
            <Stepper label="Snooze for" value={w.snoozeMin} min={5} max={60} step={5} unit=" min" onChange={(v) => updateWater({ snoozeMin: v })} />
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginVertical: 8, flexWrap: 'wrap' }}>
              {w.pausedUntil > Date.now()
                ? <><Chip ok={false} label={`Paused until ${new Date(w.pausedUntil).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`} /><Btn small kind="ghost" title="Resume" onPress={() => updateWater({ pausedUntil: 0 })} /></>
                : <><Btn small kind="ghost" title="Pause 1 hour" onPress={() => updateWater({ pausedUntil: Date.now() + 3600000 })} /><Btn small kind="ghost" title="Pause until tomorrow" onPress={() => { const d = new Date(); d.setDate(d.getDate() + 1); d.setHours(0, 0, 0, 0); updateWater({ pausedUntil: d.getTime() }); }} /></>}
            </View>
            <Sub>{snap?.water.nextAt ? `Next reminder: ${new Date(snap.water.nextAt).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' })}` : 'No reminder is scheduled (check active days and times).'} · Glasses logged today: {snap?.water.glasses ?? 0}</Sub>
            <Sub>Reminders use Android’s battery-friendly alarms, so they can arrive a few minutes late, especially in battery saver. For best results set Meow Island to unrestricted battery.</Sub>
            <View style={{ flexDirection: 'row', gap: 8, flexWrap: 'wrap', marginTop: 10 }}>
              <Btn small kind="ghost" title="Send test reminder" onPress={() => native.waterAction('test')} />
              <Btn small kind="ghost" title="Battery settings" onPress={() => native.openBatterySettings()} />
              <Btn small kind="danger" title="Delete schedule" onPress={confirmDeleteSchedule} />
            </View>
          </View>
        )}
      </Card>

      <Card>
        <H>Your data</H>
        <Sub>Step history, contribution records and the water log live only on this phone. Clearing removes the stored history and contribution grid; your settings are kept.</Sub>
        <View style={{ height: 10 }} />
        <Btn kind="danger" title={busy ? 'Clearing…' : 'Clear health data'} disabled={busy} onPress={confirmClear} />
      </Card>
    </View>
  );
}
