import React, { useRef } from 'react';
import { Alert, PanResponder, Pressable, Text, TextInput, View } from 'react-native';
import { Btn, Card, C, Chip, H, Stepper, Sub, Toggle } from '../ui';
import { native, usePerms } from '../native';
import { Config, useConfig } from '../config';

const BG = ['#000000', '#16102A', '#2D1B69', '#0B3D2E', '#4A1A33', '#FFFFFF'];
const FG = ['#FFFFFF', '#E6DEFF', '#B79CFF', '#FF9EC7', '#5BD68A', '#000000'];
const MARGIN = 8, SCREEN_DP = 360;

const Swatches = ({ label, list, value, onPick }: { label: string; list: string[]; value: string; onPick: (c: string) => void }) => (
  <View style={{ marginVertical: 6 }}><Text style={{ color: C.text, marginBottom: 6 }}>{label}</Text>
    <View style={{ flexDirection: 'row', gap: 10 }}>{list.map((c) => <Pressable key={c} accessibilityRole="button" accessibilityLabel={`${label} ${c}`} onPress={() => onPick(c)} style={{ width: 40, height: 40, borderRadius: 20, backgroundColor: c, borderWidth: value === c ? 3 : 1, borderColor: value === c ? C.accent : C.line }} />)}</View></View>
);
const Field = ({ label, value, onChange, max }: { label: string; value: string; onChange: (v: string) => void; max: number }) => (
  <View style={{ marginVertical: 6 }}><Text style={{ color: C.text, marginBottom: 6 }}>{label}</Text>
    <TextInput value={value} onChangeText={onChange} maxLength={max} accessibilityLabel={label} style={{ color: C.text, backgroundColor: C.card2, borderRadius: 12, padding: 12, minHeight: 48 }} /></View>
);
const Chips = ({ items, value, onPick }: { items: { k: string; l: string }[]; value: string; onPick: (k: string) => void }) => (
  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginVertical: 6 }}>
    {items.map((i) => <Pressable key={i.k} accessibilityRole="button" onPress={() => onPick(i.k)} style={{ paddingHorizontal: 14, minHeight: 40, justifyContent: 'center', borderRadius: 20, backgroundColor: value === i.k ? C.accent : C.card2 }}><Text style={{ color: value === i.k ? C.onAccent : C.text, fontWeight: '600' }}>{i.l}</Text></Pressable>)}
  </View>
);

/** Miniature phone: drag the pill to place the real island. fx/fy are 0..1 fractions of the allowed range. */
function PositionEditor({ cfg, update }: { cfg: Config; update: (p: Partial<Config>) => void }) {
  const PW = 180, PH = 380, scale = PW / SCREEN_DP;
  const top = cfg.posArea === 'usable' ? 24 * scale * 2 : 0;
  const bottom = cfg.posArea === 'usable' ? 14 : 0;
  const pw = Math.min(PW - 8, cfg.w * scale), ph = Math.max(8, cfg.h * scale);
  const latest = useRef({ cfg, update, top, bottom, pw, ph });
  latest.current = { cfg, update, top, bottom, pw, ph };
  const base = useRef({ x: 0, y: 0 });
  const toFrac = (cx: number, ty: number) => {
    const L = latest.current;
    const m = MARGIN * scale;
    const fx = Math.min(1, Math.max(0, (cx - m) / (PW - 2 * m)));
    const range = PH - L.top - L.bottom - L.ph;
    const fy = Math.min(1, Math.max(0, range <= 0 ? 0 : (ty - L.top) / range));
    L.update({ fx: Math.round(fx * 1000) / 1000, fy: Math.round(fy * 1000) / 1000 });
  };
  const pan = useRef(PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onPanResponderTerminationRequest: () => false,   // keep the drag even though we are inside a ScrollView
    onPanResponderGrant: (e) => { base.current = { x: e.nativeEvent.locationX, y: e.nativeEvent.locationY - latest.current.ph / 2 }; toFrac(base.current.x, base.current.y); },
    onPanResponderMove: (_, g) => toFrac(base.current.x + g.dx, base.current.y + g.dy),
  })).current;
  const m = MARGIN * scale;
  const left = Math.min(PW - pw, Math.max(0, m + cfg.fx * (PW - 2 * m) - pw / 2));
  const topPx = top + cfg.fy * Math.max(0, PH - top - bottom - ph);
  return (
    <View style={{ alignItems: 'center', marginVertical: 8 }}>
      <View {...pan.panHandlers} accessibilityLabel="Drag to position the island" style={{ width: PW, height: PH, borderRadius: 26, borderWidth: 2, borderColor: C.line, backgroundColor: '#0A0812', overflow: 'hidden' }}>
        {top > 0 && <View pointerEvents="none" style={{ position: 'absolute', top: 0, left: 0, right: 0, height: top, backgroundColor: 'rgba(183,156,255,0.08)' }} />}
        {bottom > 0 && <View pointerEvents="none" style={{ position: 'absolute', bottom: 0, left: 0, right: 0, height: bottom, backgroundColor: 'rgba(183,156,255,0.08)' }} />}
        <View pointerEvents="none" style={{ position: 'absolute', left, top: topPx, width: pw, height: ph, borderRadius: cfg.r * scale, backgroundColor: cfg.bg, borderWidth: 1, borderColor: C.accent }} />
        <Text pointerEvents="none" style={{ position: 'absolute', bottom: 28, alignSelf: 'center', color: C.sub, fontSize: 11 }}>drag the pill</Text>
      </View>
    </View>
  );
}

export default function Settings() {
  const { cfg, update, reset } = useConfig();
  const p = usePerms();
  const pct = (v: number) => Math.round(v * 100);
  const perm = (title: string, why: string, ok: boolean, action: () => void, label = 'Open settings') => (
    <View key={title} style={{ paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: C.line }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><Text style={{ color: C.text, fontWeight: '700', flex: 1 }}>{title}</Text><Chip ok={ok} label={ok ? 'Granted' : 'Not granted'} /></View>
      <Sub>{why}</Sub>{!ok && <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn small kind="ghost" title={label} onPress={action} /></View>}
    </View>
  );
  const confirmReset = () => Alert.alert('Reset appearance?', 'Size, colours, animation and emoji go back to defaults. Position, monitored apps and reminders are kept.', [{ text: 'Cancel', style: 'cancel' }, { text: 'Reset', style: 'destructive', onPress: reset }]);

  return (
    <View>
      <Card>
        <H>Island position</H>
        <Sub>Drag the pill on the phone below, tap a preset, or fine-tune with the steppers. The real island moves live while it is running. You can also drag the island itself on your screen.</Sub>
        <PositionEditor cfg={cfg} update={update} />
        <Chips value="" onPick={(k) => { const m: Record<string, [number, number]> = { tl: [0, 0], t: [0.5, 0], tr: [1, 0], c: [0.5, 0.5], b: [0.5, 1] }; update({ fx: m[k][0], fy: m[k][1] }); }}
          items={[{ k: 'tl', l: 'Top left' }, { k: 't', l: 'Top' }, { k: 'tr', l: 'Top right' }, { k: 'c', l: 'Center' }, { k: 'b', l: 'Bottom' }]} />
        <Stepper label="Horizontal (X)" value={pct(cfg.fx)} min={0} max={100} step={5} unit=" %" onChange={(v) => update({ fx: v / 100 })} />
        <Stepper label="Vertical (Y)" value={pct(cfg.fy)} min={0} max={100} step={5} unit=" %" onChange={(v) => update({ fy: v / 100 })} />
        <Text style={{ color: C.text, marginTop: 8 }}>Position relative to</Text>
        <Chips value={cfg.posArea} onPick={(k) => update({ posArea: k as Config['posArea'] })} items={[{ k: 'usable', l: 'Usable area' }, { k: 'screen', l: 'Full screen' }]} />
        <Sub>{cfg.posArea === 'usable' ? 'Stays below the status bar / camera cutout and above the navigation bar (safe default).' : 'Uses the whole physical screen, so the pill can sit over the status bar or camera.'}</Sub>
        <View style={{ marginTop: 10 }}><Btn kind="ghost" title="Reset position" onPress={() => update({ fx: 0.5, fy: 0, posArea: 'usable' })} /></View>
      </Card>

      <Card>
        <H>Island look</H>
        <Stepper label="Width" value={cfg.w} min={80} max={260} step={10} unit=" dp" onChange={(v) => update({ w: v })} />
        <Stepper label="Height" value={cfg.h} min={24} max={60} step={2} unit=" dp" onChange={(v) => update({ h: v })} />
        <Stepper label="Corner radius" value={cfg.r} min={6} max={40} step={2} unit=" dp" onChange={(v) => update({ r: v })} />
        <Stepper label="Expanded time" value={cfg.expandMs / 1000} min={2} max={15} step={1} unit=" s" onChange={(v) => update({ expandMs: v * 1000 })} />
        <Stepper label="Animation speed" value={cfg.animMs} min={0} max={800} step={40} unit=" ms" onChange={(v) => update({ animMs: v })} />
        <Swatches label="Background" list={BG} value={cfg.bg} onPick={(c) => update({ bg: c })} />
        <Swatches label="Text" list={FG} value={cfg.fg} onPick={(c) => update({ fg: c })} />
        <Toggle label="Cat ears" hint="Two tiny ears on top of the pill." value={cfg.ears} onChange={(v) => update({ ears: v })} />
        <Sub>Animations also follow Android’s “Remove animations” setting.</Sub>
      </Card>

      <Card>
        <H>Behaviour</H>
        <Toggle label="Drag to reposition" hint="Drag the island on screen to move it. When off, swipe down/up expands/collapses and sideways dismisses." value={cfg.dragEnabled} onChange={(v) => update({ dragEnabled: v })} />
        <Toggle label="Hide island during full-screen video" hint="Best effort. Needs Usage Access; works when a playing video app is in landscape or (YouTube-type apps) hides the system bars." value={cfg.hideFullscreen} onChange={(v) => update({ hideFullscreen: v })} />
        <Toggle label="Music on island" hint="Real media session: title, artist, artwork and working controls." value={cfg.musicEnabled} onChange={(v) => update({ musicEnabled: v })} />
        <Toggle label="Notification previews" hint="Show other apps’ notifications on the island." value={cfg.notifEnabled} onChange={(v) => update({ notifEnabled: v })} />
        <Toggle label="Show notification text" hint="Off = only “New notification” (privacy)." value={cfg.notifPreview} onChange={(v) => update({ notifPreview: v })} />
        <Toggle label="Screen Time Guardian" value={cfg.guardEnabled} onChange={(v) => update({ guardEnabled: v })} />
      </Card>

      <Card>
        <H>Screen-time feedback</H>
        <Field label="Over-limit emoji" value={cfg.angry} onChange={(v) => update({ angry: v })} max={4} />
        <Field label="Leaving-app emoji" value={cfg.happy} onChange={(v) => update({ happy: v })} max={4} />
        <Field label="Warning text" value={cfg.warnText} onChange={(v) => update({ warnText: v })} max={40} />
        <Stepper label="Remind every" value={cfg.remindMin} min={1} max={30} step={1} unit=" min" onChange={(v) => update({ remindMin: v })} />
        <Stepper label="Happy emoji duration" value={cfg.happyMs / 1000} min={1} max={10} step={1} unit=" s" onChange={(v) => update({ happyMs: v * 1000 })} />
        <Sub>While you stay in an app over its limit, the island expands with usage, limit and excess every “Remind every” minutes. It never blocks the app.</Sub>
        <View style={{ marginTop: 10 }}><Btn kind="ghost" title="Reset appearance to defaults" onPress={confirmReset} /></View>
      </Card>

      <Card>
        <H>Permissions</H>
        {perm('Display over other apps', 'Lets the island pill appear on top of other apps.', p.overlay, native.requestOverlay)}
        {perm('Notifications', 'Needed to deliver reminder and timer notifications (Android 13+).', p.notif, native.openNotificationSettings)}
        {perm('Notification access', 'Separate from the setting above. Lets the island read media playback and preview other apps’ notifications.', p.listener, native.requestNotificationAccess)}
        {perm('Usage Access', 'Needed by Screen Time Guardian and full-screen detection.', p.usage, native.requestUsageAccess)}
        {perm('Exact alarms', 'Lets reminders fire at the exact minute (Android 12+).', p.exact, native.requestExactAlarm)}
        {perm('Unrestricted battery', 'Recommended. Without it some phones stop the island service in the background.', p.battery, native.openBatterySettings)}
        <View style={{ marginTop: 10 }}><Btn small kind="ghost" title="Re-check now" onPress={p.refresh} /></View>
      </Card>
    </View>
  );
}
