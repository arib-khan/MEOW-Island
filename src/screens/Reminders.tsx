import React, { useEffect, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import * as Notifications from 'expo-notifications';
import { Btn, Card, C, Empty, ErrorBox, H, Loading, Stepper, Sub } from '../ui';
import { deleteReminder, completeReminder, listReminders, Reminder } from '../core/db';
import { cancelReminder, scheduleReminder } from '../core/reminders';

const fmt = (t: number) => new Date(t).toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

export default function Reminders() {
  const [items, setItems] = useState<Reminder[] | null>(null);
  const [text, setText] = useState('');
  const [day, setDay] = useState(0);
  const [hh, setHh] = useState((new Date().getHours() + 1) % 24);
  const [mm, setMm] = useState(0);
  const [rep, setRep] = useState<Reminder['repeat']>('none');
  const [editing, setEditing] = useState<Reminder | null>(null);
  const [msg, setMsg] = useState('');
  const load = async () => setItems(await listReminders());
  useEffect(() => { load(); }, []);

  const when = () => { const d = new Date(); d.setDate(d.getDate() + day); d.setHours(hh, mm, 0, 0); return d; };
  const reset = () => { setText(''); setDay(0); setRep('none'); setEditing(null); setMsg(''); };

  const save = async () => {
    if (!text.trim()) return setMsg('Write a note first.');
    const d = when();
    if (rep === 'none' && d.getTime() <= Date.now() + 5000) return setMsg('Pick a time in the future.');
    try {
      let perm = await Notifications.getPermissionsAsync();
      if (!perm.granted) perm = await Notifications.requestPermissionsAsync();
      if (!perm.granted) return setMsg('Notifications are blocked. Enable them under Settings → Permissions.');
      if (editing) { await cancelReminder(editing); await deleteReminder(editing.id); }
      await scheduleReminder(text.trim(), d, rep);
      reset(); await load();
    } catch (e: any) { setMsg(e?.message ?? String(e)); }
  };
  const edit = (r: Reminder) => {
    const d = new Date(r.at); const t0 = new Date(); t0.setHours(0, 0, 0, 0);
    setEditing(r); setText(r.text); setRep(r.repeat); setHh(d.getHours()); setMm(d.getMinutes());
    setDay(Math.max(0, Math.min(6, Math.round((new Date(d).setHours(0, 0, 0, 0) - t0.getTime()) / 86400000)))); setMsg('');
  };
  const remove = async (r: Reminder) => { await cancelReminder(r); await deleteReminder(r.id); load(); };
  const done = async (r: Reminder) => { await cancelReminder(r); await completeReminder(r.id); load(); };

  const dayChip = (i: number) => {
    const d = new Date(); d.setDate(d.getDate() + i);
    const label = i === 0 ? 'Today' : i === 1 ? 'Tomorrow' : d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric' });
    return <Pressable key={i} accessibilityRole="button" onPress={() => setDay(i)} style={{ paddingHorizontal: 14, minHeight: 40, justifyContent: 'center', borderRadius: 20, marginRight: 8, backgroundColor: day === i ? C.accent : C.card2 }}><Text style={{ color: C.text }}>{label}</Text></Pressable>;
  };
  const repChip = (k: Reminder['repeat'], l: string) => <Pressable key={k} accessibilityRole="button" onPress={() => setRep(k)} style={{ paddingHorizontal: 14, minHeight: 40, justifyContent: 'center', borderRadius: 20, marginRight: 8, backgroundColor: rep === k ? C.accent : C.card2 }}><Text style={{ color: C.text }}>{l}</Text></Pressable>;

  return (
    <View>
      <Card>
        <H>{editing ? 'Edit reminder' : 'New reminder'}</H>
        <TextInput value={text} onChangeText={setText} placeholder="What should I remind you about?" placeholderTextColor={C.sub} accessibilityLabel="Reminder note"
          style={{ color: C.text, backgroundColor: C.card2, borderRadius: 12, padding: 12, minHeight: 48, marginBottom: 10 }} />
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: 8 }}>{[0, 1, 2, 3, 4, 5, 6].map(dayChip)}</ScrollView>
        <Stepper label="Hour" value={hh} min={0} max={23} step={1} onChange={setHh} />
        <Stepper label="Minute" value={mm} min={0} max={55} step={5} onChange={setMm} />
        <View style={{ flexDirection: 'row', marginVertical: 8 }}>{repChip('none', 'Once')}{repChip('daily', 'Daily')}{repChip('weekly', 'Weekly')}</View>
        <Sub>{rep === 'none' ? `Fires ${fmt(when().getTime())}` : `Repeats ${rep} at ${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`}</Sub>
        {!!msg && <View style={{ marginTop: 8 }}><ErrorBox text={msg} /></View>}
        <View style={{ flexDirection: 'row', gap: 10, marginTop: 10 }}><View style={{ flex: 1 }}><Btn title={editing ? 'Save changes' : 'Schedule'} onPress={save} /></View>{editing && <Btn kind="ghost" title="Cancel" onPress={reset} />}</View>
      </Card>
      <H>Upcoming</H>
      {items === null ? <Loading /> : items.length === 0 ? <Empty icon="⏰" title="No reminders yet" hint="Schedule one above. It will notify you and expand the island." /> :
        items.map((r) => (
          <Card key={r.id}>
            <Text style={{ color: C.text, fontSize: 16, fontWeight: '700' }}>{r.text}</Text>
            <Sub>{fmt(r.at)}{r.repeat !== 'none' ? ` · repeats ${r.repeat}` : ''}</Sub>
            <View style={{ flexDirection: 'row', gap: 8, marginTop: 10 }}><Btn small kind="ghost" title="Edit" onPress={() => edit(r)} /><Btn small kind="ghost" title="Done" onPress={() => done(r)} /><Btn small kind="danger" title="Delete" onPress={() => remove(r)} /></View>
          </Card>
        ))}
    </View>
  );
}
