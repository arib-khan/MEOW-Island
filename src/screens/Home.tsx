import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Animated, Easing, Image, Pressable, Text, View } from 'react-native';
import { Btn, Card, Chip, C, ErrorBox, H, Sub, useReduceMotion } from '../ui';
import { useConfig } from '../config';
import { events, Media, native, usePerms } from '../native';

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** CD that rotates only while playing and resumes from the same angle. */
function Disc({ art, playing: playingProp }: { art?: string; playing: boolean }) {
  const reduce = useReduceMotion();
  const playing = playingProp && !reduce;
  const rot = useRef(new Animated.Value(0)).current;
  const pos = useRef(0);
  useEffect(() => {
    let alive = true;
    const run = () => {
      Animated.timing(rot, { toValue: 1, duration: 6000 * (1 - pos.current), easing: Easing.linear, useNativeDriver: true }).start(({ finished }) => {
        if (finished && alive) { rot.setValue(0); pos.current = 0; run(); }
      });
    };
    if (playing) run();
    else rot.stopAnimation((v) => { pos.current = v % 1; });
    return () => { alive = false; rot.stopAnimation((v) => { pos.current = v % 1; }); };
  }, [playing, rot]);
  const spin = rot.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '360deg'] });
  return (
    <Animated.View style={{ width: 132, height: 132, borderRadius: 66, backgroundColor: '#222', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', transform: [{ rotate: spin }] }}>
      {!!art && <Image source={{ uri: 'data:image/jpeg;base64,' + art }} style={{ position: 'absolute', width: 132, height: 132 }} />}
      {[0.86, 0.68, 0.5].map((k) => <View key={k} style={{ position: 'absolute', width: 132 * k, height: 132 * k, borderRadius: 66, borderWidth: 1, borderColor: 'rgba(0,0,0,0.35)' }} />)}
      <View style={{ width: 38, height: 38, borderRadius: 19, backgroundColor: '#111', alignItems: 'center', justifyContent: 'center' }}><View style={{ width: 14, height: 14, borderRadius: 7, backgroundColor: '#000', borderWidth: 1, borderColor: '#555' }} /></View>
    </Animated.View>
  );
}

function NowPlaying() {
  const [m, setM] = useState<Media>({ active: false });
  const [stamp, setStamp] = useState(Date.now());
  const [, tick] = useState(0);
  useEffect(() => {
    native.getMedia().then((x) => { setM(x); setStamp(Date.now()); }).catch(() => {});
    const sub = events.addListener('media', (x: Media) => { setM(x); setStamp(Date.now()); });
    const id = setInterval(() => tick((n) => n + 1), 1000);
    return () => { sub.remove(); clearInterval(id); };
  }, []);
  if (!m.active) return <Card><H>Now playing</H><Sub>Nothing is playing. Start music in any player and it appears here and on the island. This needs Notification access (see the setup card above).</Sub></Card>;
  const pos = (m.position ?? 0) + (m.playing ? Date.now() - stamp : 0);
  const pct = m.duration ? Math.min(1, pos / m.duration) : 0;
  const ctl = (label: string, a: 'prev' | 'play' | 'pause' | 'next', on = true) => (
    <Pressable accessibilityLabel={label} disabled={!on} onPress={() => native.mediaControl(a)} style={{ width: 52, height: 52, borderRadius: 26, backgroundColor: C.card2, alignItems: 'center', justifyContent: 'center', opacity: on ? 1 : 0.3 }}>
      <Text style={{ color: C.text, fontSize: 20 }}>{label === 'Previous' ? '⏮' : label === 'Next' ? '⏭' : m.playing ? '⏸' : '▶'}</Text>
    </Pressable>
  );
  return (
    <Card style={{ alignItems: 'center' }}>
      <Disc art={m.art} playing={!!m.playing} />
      <Text style={{ color: C.text, fontSize: 18, fontWeight: '800', marginTop: 12 }} numberOfLines={1}>{m.title}</Text>
      <Sub>{m.artist || 'Unknown artist'}</Sub>
      <View style={{ height: 4, alignSelf: 'stretch', backgroundColor: C.line, borderRadius: 2, marginVertical: 12 }}><View style={{ width: `${pct * 100}%`, height: 4, backgroundColor: C.accent, borderRadius: 2 }} /></View>
      <View style={{ flexDirection: 'row', gap: 16 }}>
        {ctl('Previous', 'prev', !!m.canPrev)}
        {ctl(m.playing ? 'Pause' : 'Play', m.playing ? 'pause' : 'play')}
        {ctl('Next', 'next', !!m.canNext)}
      </View>
      {(!m.canNext || !m.canPrev) && <Text style={{ color: C.sub, fontSize: 12, marginTop: 8 }}>This player does not expose every control.</Text>}
    </Card>
  );
}

export default function Home({ go }: { go: (i: number) => void }) {
  const perms = usePerms();
  const { cfg } = useConfig();
  const [running, setRunning] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const sync = useCallback(async () => { try { setRunning(await native.isRunning()); } catch { /* ignore */ } }, []);
  useEffect(() => {
    sync();
    const sub = events.addListener('islandState', (e: { running: boolean; error: string }) => { setRunning(e.running); if (e.error) setError(e.error); });
    return () => sub.remove();
  }, [sync]);
  useEffect(() => { if (!perms.loading) sync(); }, [perms.overlay, perms.loading, sync]);

  const start = async () => {
    setError('');
    if (!(await native.hasOverlay())) {
      Alert.alert('Permission needed', 'Meow Island draws a pill over other apps, which Android treats as a special permission.\n\nOn the next screen switch on "Allow display over other apps", then come back here.',
        [{ text: 'Cancel', style: 'cancel' }, { text: 'Open settings', onPress: () => native.requestOverlay() }]);
      return;
    }
    setBusy(true);
    try {
      await native.startIsland();
      await sleep(1000);
      const ok = await native.isRunning();
      setRunning(ok);
      if (!ok) setError((await native.lastError()) || 'The island service did not start. Check battery restrictions and try again.');
    } catch (e: any) { setError(e?.message ?? String(e)); }
    setBusy(false);
  };
  const stop = async () => { setBusy(true); try { await native.stopIsland(); await sleep(300); setRunning(await native.isRunning()); } catch (e: any) { setError(e?.message ?? String(e)); } setBusy(false); };

  const setupItems = [
    { k: 'overlay', t: 'Display over other apps', ok: perms.overlay, act: () => native.requestOverlay() },
    { k: 'listener', t: 'Notification access (music, notifications, reminders on the island)', ok: perms.listener, act: () => native.requestNotificationAccess() },
    { k: 'usage', t: 'Usage Access (screen time, full-screen hiding)', ok: perms.usage, act: () => native.requestUsageAccess() },
  ];
  const pending = setupItems.filter((i) => !i.ok);
  const features = [
    { t: 'Music', on: cfg.musicEnabled && perms.listener },
    { t: 'Notifications', on: cfg.notifEnabled && perms.listener },
    { t: 'Screen time', on: cfg.guardEnabled && perms.usage && cfg.limits.length > 0 },
    { t: 'Reminders', on: perms.notif },
    { t: 'Video hiding', on: cfg.hideFullscreen && perms.usage },
  ];
  return (
    <View>
      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Island</H><Chip ok={running} label={running ? 'Running' : 'Stopped'} /></View>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginBottom: 12 }}><Sub>Overlay permission</Sub><Chip ok={perms.overlay} label={perms.overlay ? 'Granted' : 'Not granted'} /></View>
        {!!error && <ErrorBox text={error} />}
        {running ? <Btn title={busy ? 'Stopping…' : 'Stop island'} kind="danger" disabled={busy} onPress={stop} /> : <Btn title={busy ? 'Starting…' : 'Start island'} disabled={busy} onPress={start} />}
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 12 }}>
          <View style={{ flex: 1 }}><Btn small kind="ghost" title="Add reminder" onPress={() => go(1)} /></View>
          <View style={{ flex: 1 }}><Btn small kind="ghost" title="Island position" onPress={() => go(4)} /></View>
        </View>
      </Card>
      {pending.length > 0 && (
        <Card>
          <H>Finish setup</H>
          <Sub>Meow Island needs a few Android permissions. Each opens the right settings page, and the status updates when you come back.</Sub>
          {pending.map((i) => <View key={i.k} style={{ marginTop: 10 }}><Text style={{ color: C.text, marginBottom: 6 }}>{i.t}</Text><View style={{ alignSelf: 'flex-start' }}><Btn small title="Grant" onPress={i.act} /></View></View>)}
        </Card>
      )}
      <Card>
        <H>Active features</H>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>{features.map((f) => <Chip key={f.t} ok={f.on} label={`${f.t}: ${f.on ? 'on' : 'off'}`} />)}</View>
      </Card>
      <NowPlaying />
    </View>
  );
}
