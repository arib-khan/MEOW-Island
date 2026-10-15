import React, { useEffect, useState } from 'react';
import { NativeModules, NativeEventEmitter, View, Text, Button, TextInput, ScrollView, StyleSheet } from 'react-native';
import * as Notifications from 'expo-notifications';
import * as Battery from 'expo-battery';
import { initDb, listReminders, Reminder, deleteReminder, completeReminder, setLimit } from './core/db';
import { scheduleReminder, setupReminderActions, snooze } from './core/reminders';
import { upsert, remove, bindSink, PRIORITY } from './core/activities';
import { startGuardian } from './core/guardian';
const N = NativeModules.IslandModule;
class Boundary extends React.Component<{ children: React.ReactNode }, { err?: Error }> {
  state: { err?: Error } = {};
  static getDerivedStateFromError(err: Error) { return { err }; }
  render() {
    return this.state.err
      ? <ScrollView style={s.c}><Text style={s.h}>App error</Text><Text selectable>{String(this.state.err.stack ?? this.state.err.message)}</Text></ScrollView>
      : this.props.children;
  }
}
export default function App() {
  return <Boundary>{N ? <Screen /> : <ScrollView style={s.c}><Text style={s.h}>Native module missing</Text>
    <Text selectable>{'IslandModule was not registered. Open android/app/src/main/java/com/smartisland/app/MainApplication.kt and add add(IslandPackage()) inside the PackageList(this).packages.apply block, then rebuild.'}</Text></ScrollView>}</Boundary>;
}
function Screen() {
  const [err, setErr] = useState('');
  const [perm, setPerm] = useState({ overlay: false, notif: false, usage: false });
  const [rems, setRems] = useState<Reminder[]>([]); const [text, setText] = useState(''); const [mins, setMins] = useState('1'); const [pkg, setPkg] = useState('');
  const refresh = async () => { setPerm({ overlay: await N.hasOverlay(), notif: await N.hasNotificationAccess(), usage: await N.hasUsageAccess() }); setRems(await listReminders()); };
  useEffect(() => {
    (async () => {
      try {
        await initDb(); await setupReminderActions(); await Notifications.requestPermissionsAsync(); await refresh();
        bindSink(a => N.show(a?.title ?? '', a?.sub ?? ''));
        const ev = new NativeEventEmitter(N);
        ev.addListener('notification', (n: any) => upsert({ id: 'n', priority: PRIORITY.notification, title: n.title, sub: n.text, expiresAt: Date.now() + 5000 }));
        Notifications.addNotificationReceivedListener(n => upsert({ id: 'r', priority: PRIORITY.reminder, title: '⏰ ' + n.request.content.body, expiresAt: Date.now() + 15000 }));
        Notifications.addNotificationResponseReceivedListener(r => { const t = r.notification.request.content.body ?? ''; if (r.actionIdentifier === 'snooze') snooze(t); });
        const lvl = await Battery.getBatteryLevelAsync(); if (lvl < 0.15) upsert({ id: 'b', priority: PRIORITY.battery, title: `🔋 ${Math.round(lvl * 100)}%`, expiresAt: Date.now() + 5000 });
        startGuardian();
      } catch (e: any) { setErr(String(e?.stack ?? e)); }
    })();
  }, []);
  const row = (label: string, ok: boolean, act: () => void) => <View style={s.row}><Text>{label}: {ok ? '✅' : '❌'}</Text>{!ok && <Button title="Grant" onPress={act} />}</View>;
  return <ScrollView style={s.c}>
    <Text style={s.h}>Smart Island</Text>{!!err && <Text selectable style={{ color: 'red' }}>{err}</Text>}
    {row('Overlay (needed to draw island)', perm.overlay, N.requestOverlay)}
    {row('Notification access (music + notifications)', perm.notif, N.requestNotificationAccess)}
    {row('Usage access (Screen Time Guardian)', perm.usage, N.requestUsageAccess)}
    <Button title="Refresh permissions" onPress={refresh} /><Button title="Start island" onPress={() => N.startIsland()} /><Button title="Stop island" onPress={() => N.stopIsland()} />
    <Text style={s.h}>Reminder</Text>
    <TextInput style={s.in} placeholder="Note" value={text} onChangeText={setText} />
    <TextInput style={s.in} placeholder="Minutes from now" keyboardType="numeric" value={mins} onChangeText={setMins} />
    <Button title="Schedule" onPress={async () => { await scheduleReminder(text, new Date(Date.now() + Number(mins) * 60000), 'none'); setText(''); refresh(); }} />
    {rems.map(r => <View key={r.id} style={s.row}><Text>{r.text} — {new Date(r.at).toLocaleString()}</Text>
      <Button title="✓" onPress={async () => { await completeReminder(r.id); refresh(); }} /><Button title="🗑" onPress={async () => { await deleteReminder(r.id); refresh(); }} /></View>)}
    <Text style={s.h}>Screen time limit (package, 1 min demo)</Text>
    <TextInput style={s.in} placeholder="com.instagram.android" autoCapitalize="none" value={pkg} onChangeText={setPkg} />
    <Button title="Set 1-min limit" onPress={() => setLimit(pkg, 1)} />
  </ScrollView>;
}
const s = StyleSheet.create({ c: { padding: 16, marginTop: 40 }, h: { fontSize: 20, fontWeight: '700', marginVertical: 10 }, row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginVertical: 4 }, in: { borderWidth: 1, borderColor: '#999', padding: 8, marginVertical: 4 } });