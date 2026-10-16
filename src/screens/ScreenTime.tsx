import React, { useEffect, useMemo, useState } from 'react';
import { Alert, FlatList, Image, Modal, Pressable, Text, TextInput, View } from 'react-native';
import { Btn, Card, C, Chip, Empty, ErrorBox, H, Loading, Stepper, Sub, Toggle } from '../ui';
import { AppInfo, native, usePerms } from '../native';
import { LimitItem, useConfig } from '../config';

let cache: AppInfo[] | null = null;
const Icon = ({ a, size = 40 }: { a?: AppInfo; size?: number }) =>
  a ? <Image source={{ uri: 'data:image/png;base64,' + a.icon }} style={{ width: size, height: size, borderRadius: 10 }} /> : <View style={{ width: size, height: size, borderRadius: 10, backgroundColor: C.card2 }} />;

export default function ScreenTime() {
  const { cfg, update } = useConfig();
  const perms = usePerms();
  const [apps, setApps] = useState<AppInfo[] | null>(cache);
  const [appsErr, setAppsErr] = useState('');
  const [usage, setUsage] = useState<Record<string, number>>({});
  const [usageErr, setUsageErr] = useState('');
  const [picker, setPicker] = useState(false);
  const [q, setQ] = useState('');
  const [editPkg, setEditPkg] = useState<string | null>(null);

  useEffect(() => {
    if (cache) return;
    native.getInstalledApps().then((a) => { cache = a; setApps(a); }).catch((e) => setAppsErr(e?.message ?? 'Could not list installed apps'));
  }, []);
  useEffect(() => {
    if (!perms.usage) return;
    let alive = true;
    const f = async () => { try { const u = await native.getUsageToday(); if (alive) { setUsage(u); setUsageErr(''); } } catch (e: any) { if (alive) setUsageErr(e?.message ?? 'Usage unavailable'); } };
    f(); const id = setInterval(f, 5000);
    return () => { alive = false; clearInterval(id); };
  }, [perms.usage]);

  const byPkg = useMemo(() => { const m: Record<string, AppInfo> = {}; (apps ?? []).forEach((a) => { m[a.pkg] = a; }); return m; }, [apps]);
  const filtered = useMemo(() => (apps ?? []).filter((a) => a.name.toLowerCase().includes(q.trim().toLowerCase())), [apps, q]);
  const setLimits = (l: LimitItem[]) => update({ limits: l });
  const patch = (pkg: string, p: Partial<LimitItem>) => setLimits(cfg.limits.map((l) => (l.pkg === pkg ? { ...l, ...p } : l)));
  const add = (a: AppInfo) => { if (!cfg.limits.some((l) => l.pkg === a.pkg)) setLimits([...cfg.limits, { pkg: a.pkg, name: a.name, limitMin: 30, enabled: true }]); setPicker(false); setQ(''); };
  const editing = cfg.limits.find((l) => l.pkg === editPkg);

  return (
    <View>
      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Screen Time Guardian</H><Chip ok={perms.usage} label={perms.usage ? 'Usage access on' : 'Usage access off'} /></View>
        <Sub>While you are still in an app past its daily limit the island shows 😾, expands with your usage every few minutes, and shows 😺 briefly when you leave. It only gives feedback and never blocks or closes apps. Detection can lag a few seconds because Android delivers usage events in batches.</Sub>
        <View style={{ height: 8 }} />
        <Toggle label="Enable monitoring" value={cfg.guardEnabled} onChange={(v) => update({ guardEnabled: v })} />
        <Stepper label="Remind every" value={cfg.remindMin} min={1} max={30} step={1} unit=" min" onChange={(v) => update({ remindMin: v })} />
        {!perms.usage && <><Sub>Usage Access is required. On the next screen open Meow Island and allow access.</Sub><View style={{ height: 8 }} /><Btn title="Grant Usage Access" onPress={() => native.requestUsageAccess()} /></>}
        {!!usageErr && <ErrorBox text={usageErr} />}
      </Card>

      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}><H>Monitored apps</H><Btn small title="Add app" onPress={() => setPicker(true)} /></View>
      {cfg.limits.length === 0 ? <Empty icon="⏱" title="No apps selected" hint="Tap “Add app” and pick from your installed apps." /> :
        cfg.limits.map((l) => {
          const used = (usage[l.pkg] ?? 0) / 60000; const left = Math.max(0, l.limitMin - used); const over = used > l.limitMin;
          return (
            <Card key={l.pkg} style={over && l.enabled ? { borderColor: C.bad } : undefined}>
              <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                <Icon a={byPkg[l.pkg]} />
                <View style={{ flex: 1, marginLeft: 12 }}>
                  <Text style={{ color: C.text, fontSize: 16, fontWeight: '700' }}>{l.name}</Text>
                  <Sub>{perms.usage ? `${Math.floor(used)} of ${l.limitMin} min today · ${over ? 'Limit exceeded' : `${Math.ceil(left)} min left`}` : `Limit ${l.limitMin} min/day`}</Sub>
                </View>
                <Toggle label="" value={l.enabled} onChange={(v) => patch(l.pkg, { enabled: v })} />
              </View>
              <View style={{ height: 6, backgroundColor: C.line, borderRadius: 3, marginTop: 10 }}><View style={{ height: 6, borderRadius: 3, width: `${Math.min(100, (used / l.limitMin) * 100)}%`, backgroundColor: over ? C.bad : C.accent }} /></View>
              <View style={{ flexDirection: 'row', gap: 8, marginTop: 10 }}><Btn small kind="ghost" title="Edit limit" onPress={() => setEditPkg(l.pkg)} /><Btn small kind="danger" title="Remove" onPress={() => Alert.alert('Stop monitoring?', `Remove the limit for ${l.name}?`, [{ text: 'Keep', style: 'cancel' }, { text: 'Remove', style: 'destructive', onPress: () => setLimits(cfg.limits.filter((x) => x.pkg !== l.pkg)) }])} /></View>
            </Card>
          );
        })}

      <Modal visible={picker} animationType="slide" onRequestClose={() => setPicker(false)}>
        <View style={{ flex: 1, backgroundColor: C.bg, padding: 16, paddingTop: 44 }}>
          <H>Choose an app</H>
          <TextInput value={q} onChangeText={setQ} placeholder="Search apps" placeholderTextColor={C.sub} accessibilityLabel="Search apps" style={{ color: C.text, backgroundColor: C.card, borderRadius: 12, padding: 12, minHeight: 48, marginBottom: 10 }} />
          {appsErr ? <ErrorBox text={appsErr} /> : apps === null ? <Loading label="Loading installed apps…" /> :
            <FlatList data={filtered} keyExtractor={(a) => a.pkg} keyboardShouldPersistTaps="handled" initialNumToRender={14} windowSize={7}
              ListEmptyComponent={<Empty icon="🔍" title="No match" hint="Try a different name." />}
              renderItem={({ item }) => {
                const picked = cfg.limits.some((l) => l.pkg === item.pkg);
                return <Pressable accessibilityRole="button" accessibilityLabel={item.name} disabled={picked} onPress={() => add(item)} style={{ flexDirection: 'row', alignItems: 'center', minHeight: 56, opacity: picked ? 0.4 : 1 }}>
                  <Icon a={item} /><Text style={{ color: C.text, fontSize: 16, marginLeft: 12, flex: 1 }}>{item.name}</Text>{picked && <Sub>Added</Sub>}</Pressable>;
              }} />}
          <View style={{ height: 8 }} /><Btn kind="ghost" title="Close" onPress={() => setPicker(false)} />
        </View>
      </Modal>

      <Modal visible={!!editing} transparent animationType="fade" onRequestClose={() => setEditPkg(null)}>
        <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'center', padding: 24 }}>
          {editing && <Card>
            <H>{editing.name}</H>
            <Stepper label="Daily limit" value={editing.limitMin} min={1} max={480} step={5} unit=" min" onChange={(v) => patch(editing.pkg, { limitMin: v })} />
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginVertical: 10 }}>
              {[1, 15, 30, 45, 60, 90, 120].map((m) => <Pressable key={m} accessibilityRole="button" onPress={() => patch(editing.pkg, { limitMin: m })} style={{ paddingHorizontal: 14, minHeight: 40, justifyContent: 'center', borderRadius: 20, backgroundColor: editing.limitMin === m ? C.accent : C.card2 }}><Text style={{ color: C.text }}>{m} min</Text></Pressable>)}
            </View>
            <Sub>1 min is handy for testing the 😡 / 😊 behaviour.</Sub><View style={{ height: 10 }} /><Btn title="Done" onPress={() => setEditPkg(null)} />
          </Card>}
        </View>
      </Modal>
    </View>
  );
}
