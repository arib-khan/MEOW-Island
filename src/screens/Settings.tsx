import React from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';
import { Btn, Card, C, Chip, H, Stepper, Sub, Toggle } from '../ui';
import { native, usePerms } from '../native';
import { useConfig } from '../config';

const BG = ['#000000', '#1C1C1E', '#2D1B69', '#0B3D2E', '#5A1A1A', '#FFFFFF'];
const FG = ['#FFFFFF', '#E0E0E0', '#7C5CFF', '#34C759', '#FF9F0A', '#000000'];
const Swatches = ({ label, list, value, onPick }: { label: string; list: string[]; value: string; onPick: (c: string) => void }) => (
  <View style={{ marginVertical: 6 }}><Text style={{ color: C.text, marginBottom: 6 }}>{label}</Text>
    <View style={{ flexDirection: 'row', gap: 10 }}>{list.map((c) => <Pressable key={c} accessibilityRole="button" accessibilityLabel={`${label} ${c}`} onPress={() => onPick(c)} style={{ width: 40, height: 40, borderRadius: 20, backgroundColor: c, borderWidth: value === c ? 3 : 1, borderColor: value === c ? C.accent : C.line }} />)}</View></View>
);
const Field = ({ label, value, onChange, max }: { label: string; value: string; onChange: (v: string) => void; max: number }) => (
  <View style={{ marginVertical: 6 }}><Text style={{ color: C.text, marginBottom: 6 }}>{label}</Text>
    <TextInput value={value} onChangeText={onChange} maxLength={max} accessibilityLabel={label} style={{ color: C.text, backgroundColor: C.card2, borderRadius: 12, padding: 12, minHeight: 48 }} /></View>
);

export default function Settings() {
  const { cfg, update, reset } = useConfig();
  const p = usePerms();
  const perm = (title: string, why: string, ok: boolean, action: () => void, label = 'Open settings') => (
    <View key={title} style={{ paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: C.line }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><Text style={{ color: C.text, fontWeight: '700', flex: 1 }}>{title}</Text><Chip ok={ok} label={ok ? 'Granted' : 'Not granted'} /></View>
      <Sub>{why}</Sub>{!ok && <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn small kind="ghost" title={label} onPress={action} /></View>}
    </View>
  );
  return (
    <View>
      <Card>
        <H>Permissions</H>
        {perm('Display over other apps', 'Lets the island pill appear on top of other apps.', p.overlay, native.requestOverlay)}
        {perm('Notifications', 'Needed to deliver reminder notifications (Android 13+).', p.notif, native.openNotificationSettings)}
        {perm('Notification access', 'Separate from the setting above. Lets the island read media playback and preview other apps’ notifications.', p.listener, native.requestNotificationAccess)}
        {perm('Usage Access', 'Needed by Screen Time Guardian to read how long apps are used.', p.usage, native.requestUsageAccess)}
        {perm('Exact alarms', 'Lets reminders fire at the exact minute (Android 12+).', p.exact, native.requestExactAlarm)}
        {perm('Unrestricted battery', 'Recommended. Without it some phones stop the island service in the background.', p.battery, native.openBatterySettings)}
        <View style={{ marginTop: 10 }}><Btn small kind="ghost" title="Re-check now" onPress={p.refresh} /></View>
      </Card>

      <Card>
        <H>Features</H>
        <Toggle label="Music on island" hint="Real media session: title, artist, artwork, controls." value={cfg.musicEnabled} onChange={(v) => update({ musicEnabled: v })} />
        <Toggle label="Notification previews" hint="Show other apps’ notifications on the island." value={cfg.notifEnabled} onChange={(v) => update({ notifEnabled: v })} />
        <Toggle label="Show notification text" hint="Off = only “New notification” (privacy)." value={cfg.notifPreview} onChange={(v) => update({ notifPreview: v })} />
        <Toggle label="Screen Time Guardian" value={cfg.guardEnabled} onChange={(v) => update({ guardEnabled: v })} />
      </Card>

      <Card>
        <H>Island look</H>
        <View style={{ alignItems: 'center', paddingVertical: 14 }}>
          <View style={{ width: cfg.w * 1.3, height: cfg.h * 1.3, borderRadius: cfg.r * 1.3, backgroundColor: cfg.bg, borderWidth: 1, borderColor: C.line, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: cfg.fg, fontSize: 12 }}>Preview</Text></View>
        </View>
        <Stepper label="Width" value={cfg.w} min={80} max={260} step={10} unit=" dp" onChange={(v) => update({ w: v })} />
        <Stepper label="Height" value={cfg.h} min={24} max={60} step={2} unit=" dp" onChange={(v) => update({ h: v })} />
        <Stepper label="Corner radius" value={cfg.r} min={6} max={40} step={2} unit=" dp" onChange={(v) => update({ r: v })} />
        <Stepper label="Expand time" value={cfg.expandMs / 1000} min={2} max={15} step={1} unit=" s" onChange={(v) => update({ expandMs: v * 1000 })} />
        <Stepper label="Animation" value={cfg.animMs} min={0} max={800} step={40} unit=" ms" onChange={(v) => update({ animMs: v })} />
        <Swatches label="Background" list={BG} value={cfg.bg} onPick={(c) => update({ bg: c })} />
        <Swatches label="Text" list={FG} value={cfg.fg} onPick={(c) => update({ fg: c })} />
      </Card>

      <Card>
        <H>Screen-time feedback</H>
        <Field label="Over-limit emoji" value={cfg.angry} onChange={(v) => update({ angry: v })} max={4} />
        <Field label="Leaving-app emoji" value={cfg.happy} onChange={(v) => update({ happy: v })} max={4} />
        <Field label="Warning text" value={cfg.warnText} onChange={(v) => update({ warnText: v })} max={40} />
        <Stepper label="Happy emoji duration" value={cfg.happyMs / 1000} min={1} max={10} step={1} unit=" s" onChange={(v) => update({ happyMs: v * 1000 })} />
        <View style={{ marginTop: 10 }}><Btn kind="ghost" title="Reset look & feedback to defaults" onPress={reset} /></View>
      </Card>
    </View>
  );
}
