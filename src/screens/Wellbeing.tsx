import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Pressable, Text, TextInput, View } from 'react-native';
import { Btn, Card, C, Chip, Empty, ErrorBox, H, Loading, Stepper, Sub, Toggle } from '../ui';
import { wb, wellbeingAvailable } from '../native';
import { useWellbeing } from '../wellbeing';
import { dayKey, DEFAULT_BLOCKLIST_URL, DEFAULT_TIERS, goalState, isHttps, isIp, lastDays, normalizeDomain, pickTier, summarize, Tier } from '../core/wellbeing';

const Chips = ({ items, value, onPick }: { items: { k: string; l: string }[]; value: string; onPick: (k: string) => void }) => (
  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginVertical: 6 }}>
    {items.map((i) => <Pressable key={i.k} accessibilityRole="button" accessibilityState={{ selected: value === i.k }} onPress={() => onPick(i.k)} style={{ paddingHorizontal: 14, minHeight: 40, justifyContent: 'center', borderRadius: 20, backgroundColor: value === i.k ? C.accent : C.card2 }}><Text style={{ color: value === i.k ? C.onAccent : C.text, fontWeight: '600' }}>{i.l}</Text></Pressable>)}
  </View>
);
const Row = ({ label, chip, ok }: { label: string; chip: string; ok: boolean }) => (
  <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', minHeight: 40 }}><Text style={{ color: C.text, flex: 1 }}>{label}</Text><Chip ok={ok} label={chip} /></View>
);
const Field = ({ label, value, onChange, max = 60, placeholder, keyboard }: { label: string; value: string; onChange: (v: string) => void; max?: number; placeholder?: string; keyboard?: 'default' | 'url' | 'numbers-and-punctuation' }) => (
  <View style={{ marginVertical: 6 }}><Text style={{ color: C.text, marginBottom: 6 }}>{label}</Text>
    <TextInput value={value} onChangeText={onChange} maxLength={max} placeholder={placeholder} placeholderTextColor={C.sub} autoCapitalize="none" autoCorrect={false} keyboardType={keyboard} accessibilityLabel={label} style={{ color: C.text, backgroundColor: C.card2, borderRadius: 12, padding: 12, minHeight: 48 }} /></View>
);

/** Bars for the last N days; the tallest bar is the busiest day. Plain Views, no chart library. */
function Bars({ rows, goal }: { rows: ReturnType<typeof lastDays>; goal: number }) {
  const max = Math.max(1, goal, ...rows.map((r) => r.total));
  const dense = rows.length > 10;
  return (
    <View accessibilityLabel={`Chart of the last ${rows.length} days`} style={{ flexDirection: 'row', alignItems: 'flex-end', height: 96, gap: dense ? 2 : 6, marginTop: 10 }}>
      {rows.map((r) => (
        <View key={r.day} style={{ flex: 1, alignItems: 'center', justifyContent: 'flex-end', height: 96 }}>
          {!dense && r.total > 0 && <Text style={{ color: C.sub, fontSize: 10 }}>{r.total}</Text>}
          <View style={{ width: '100%', height: Math.max(2, (r.total / max) * 72), borderRadius: 4, backgroundColor: goal > 0 && r.total > goal ? C.accent2 : C.accent, opacity: r.total ? 1 : 0.25 }} />
          {!dense && <Text style={{ color: C.sub, fontSize: 10, marginTop: 2 }}>{r.day.slice(8)}</Text>}
        </View>
      ))}
    </View>
  );
}

export default function Wellbeing() {
  const { ready, shorts, adult, status, adultStatus, days, setShorts, setAdult, refresh, askPin } = useWellbeing();
  const [range, setRange] = useState('7');
  const [log, setLog] = useState<string | null>(null);
  const [busy, setBusy] = useState('');
  const [msg, setMsg] = useState('');
  const [allowTxt, setAllowTxt] = useState(''); const [blockTxt, setBlockTxt] = useState('');

  useEffect(() => { refresh(); const id = setInterval(refresh, 4000); return () => clearInterval(id); }, [refresh]);

  const rows = useMemo(() => lastDays(days, +range, Date.now()), [days, range]);
  const today = rows.length ? lastDays(days, 1, Date.now())[0] : { ig: 0, yt: 0, total: 0, approx: false, day: dayKey(Date.now()) };
  const sum = summarize(rows);
  const tier = pickTier(today.total, shorts.counter.tiers);
  const gs = goalState(today.total, shorts.goal.daily);

  if (!wellbeingAvailable) return <Card><H>Needs the latest build</H><Sub>This APK was built before the Wellbeing features were added. Run the prebuild again and rebuild the app.</Sub></Card>;
  if (!ready) return <Loading label="Loading…" />;

  const c = shorts.counter, b = shorts.block;
  const setC = (p: Partial<typeof c>) => setShorts({ ...shorts, counter: { ...c, ...p } });
  const setB = (p: Partial<typeof b>) => setShorts({ ...shorts, block: { ...b, ...p } });
  const setTier = (i: number, p: Partial<Tier>) => setC({ tiers: c.tiers.map((t, j) => (j === i ? { ...t, ...p } : t)) });
  const guardOn = !!status?.serviceEnabled;

  const askGuard = (why: string) => Alert.alert('Turn on the Reels & Shorts guard?', `${why}\n\nAndroid requires you to switch it on yourself: Settings → Accessibility → Meow Island Reels & Shorts guard. It only looks inside Instagram and YouTube, only for the video-feed screen, and nothing leaves your phone. If Android says “restricted setting”, open App info → ⋮ → Allow restricted settings first.`, [{ text: 'Not now', style: 'cancel' }, { text: 'Open accessibility settings', onPress: () => wb.requestShortsGuard() }]);
  const needsGuard = (on: boolean, why: string) => { if (on && !guardOn) askGuard(why); };

  const toggleAdult = async (on: boolean) => {
    setMsg('');
    if (!on) { await setAdult({ ...adult, enabled: false }); return; }
    setBusy('vpn');
    try {
      const has = await wb.hasVpnPermission();
      if (!has) {
        const granted = await new Promise<boolean>((res) => Alert.alert('Allow a local VPN?', 'The filter works as a VPN that stays on your phone. It only sees DNS lookups (the “what is this website’s address?” step), checks them against the blocklist and forwards the rest unchanged. No traffic goes through any server of ours. Android will now ask you to confirm.', [{ text: 'Cancel', style: 'cancel', onPress: () => res(false) }, { text: 'Continue', onPress: () => res(true) }]));
        if (!granted || !(await wb.requestVpnPermission())) { setMsg('VPN permission was not granted, so the filter is still off.'); return; }
      }
      await setAdult({ ...adult, enabled: true });
    } catch (e: any) { setMsg(String(e?.message ?? e)); } finally { setBusy(''); }
  };
  const updateList = async () => {
    setBusy('list'); setMsg('');
    try { const n = await wb.updateAdultBlocklist(adult.blocklistUrl); setMsg(`Blocklist updated: ${n.toLocaleString()} domains.`); await refresh(); }
    catch (e: any) { setMsg('Update failed: ' + String(e?.message ?? e) + '. The previous list is still in use.'); } finally { setBusy(''); }
  };
  const addDomain = (kind: 'allow' | 'block', txt: string, clear: () => void) => {
    const d = normalizeDomain(txt); if (!d) { Alert.alert('Not a website address', 'Enter something like example.com'); return; }
    if (!adult[kind].includes(d)) setAdult({ ...adult, [kind]: [...adult[kind], d] }); clear();
  };
  const pinFlow = async () => {
    if (status?.hasPin) {
      Alert.alert('PIN', 'Remove the PIN? Anyone using your phone could then turn the blockers off.', [{ text: 'Cancel', style: 'cancel' }, { text: 'Remove PIN', style: 'destructive', onPress: async () => {
        const p = await askPin('Enter your current PIN to remove it.'); if (p == null) return;
        try { await wb.clearPin(p); await refresh(); } catch (e: any) { Alert.alert('Could not remove PIN', String(e?.message ?? e)); } } }]);
      return;
    }
    const p1 = await askPin('Choose a PIN (4 to 8 digits). You will need it to turn blocking off or relax strict mode.'); if (p1 == null) return;
    const p2 = await askPin('Type the same PIN again to confirm.'); if (p2 == null) return;
    if (p1 !== p2) { Alert.alert('PINs do not match', 'Nothing was changed.'); return; }
    try { await wb.setPin(p1); await refresh(); } catch (e: any) { Alert.alert('Could not set PIN', String(e?.message ?? e)); }
  };

  const adultState = !adult.enabled ? { t: 'Off', ok: false } : adultStatus?.running ? { t: 'Active', ok: true } : { t: 'Not running', ok: false };
  const blockState = !(b.ig || b.yt) ? { t: 'Off', ok: false } : status?.blockingActive ? { t: b.strict ? 'Active · strict' : 'Active', ok: true } : { t: 'Waiting for accessibility', ok: false };

  return (
    <View>
      <Card>
        <H>Today</H>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
          <Text accessibilityLabel={tier?.text ?? 'No short videos yet'} style={{ fontSize: 54 }}>{tier?.emoji ?? '🐱'}</Text>
          <View style={{ flex: 1 }}>
            <Text style={{ color: C.text, fontSize: 34, fontWeight: '900' }}>{today.total}{today.approx ? <Text style={{ fontSize: 16, color: C.sub }}> ≈</Text> : null}</Text>
            <Sub>{today.total === 0 ? 'No Reels or Shorts counted yet today' : tier?.text ?? 'Reels + Shorts today'}</Sub>
          </View>
        </View>
        <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
          {[['Instagram Reels', today.ig, '📸'], ['YouTube Shorts', today.yt, '▶️']].map(([l, n, i]) => (
            <View key={String(l)} style={{ flex: 1, backgroundColor: C.card2, borderRadius: 16, padding: 12 }}><Text style={{ color: C.sub, fontSize: 12 }}>{i} {l}</Text><Text style={{ color: C.text, fontSize: 24, fontWeight: '800' }}>{n as number}</Text></View>
          ))}
        </View>
        {gs !== 'off' && (
          <View style={{ marginTop: 14 }}>
            <View style={{ height: 8, borderRadius: 4, backgroundColor: C.line, overflow: 'hidden' }}><View style={{ width: `${Math.min(100, (today.total / shorts.goal.daily) * 100)}%`, height: 8, backgroundColor: gs === 'over' ? C.accent2 : gs === 'reached' ? C.warn : C.ok }} /></View>
            <Sub>{gs === 'over' ? `${today.total - shorts.goal.daily} over your goal of ${shorts.goal.daily}. No judgement, tomorrow is a fresh start.` : gs === 'reached' ? `You reached today’s goal of ${shorts.goal.daily}.` : `${shorts.goal.daily - today.total} left until your goal of ${shorts.goal.daily}.`}</Sub>
          </View>
        )}
        <Chips value={range} onPick={setRange} items={[{ k: '7', l: '7 days' }, { k: '30', l: '30 days' }]} />
        {sum.sum === 0 ? <Empty icon="📊" title="Nothing counted yet" hint={guardOn ? 'Open Instagram Reels or YouTube Shorts and your counts will appear here.' : 'Turn on the guard below to start counting.'} /> : (
          <><Bars rows={rows} goal={shorts.goal.daily} /><Sub>{`${sum.sum} in ${rows.length} days · about ${sum.avg} a day · busiest day ${sum.best}`}</Sub></>
        )}
      </Card>

      <Card>
        <H>Status</H>
        <Row label="Reels & Shorts guard (Accessibility)" ok={guardOn} chip={guardOn ? (status?.serviceConnected ? 'Running' : 'Enabled') : 'Not enabled'} />
        <Row label="Reels / Shorts blocking" ok={blockState.ok} chip={blockState.t} />
        <Row label="Adult website filter" ok={adultState.ok} chip={adultState.t} />
        <Row label="Overlay permission" ok chip="Not needed" />
        {!guardOn && <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn small title="Set up the guard" onPress={() => askGuard('The guard is what lets Meow Island see that you are on a Reels or Shorts feed.')} /></View>}
        {(b.ig || b.yt) && !guardOn && <ErrorBox text="Blocking is switched on, but Android's accessibility service is off, so nothing is being blocked right now." />}
        <Sub>{'The guard draws its counter and block screen through the accessibility overlay layer, so “Display over other apps” is not needed. Turning the guard off in Android settings stops counting and blocking: Meow Island cannot prevent that, and will show the warning above.'}</Sub>
      </Card>

      <Card>
        <H>Reels & Shorts counter</H>
        <Toggle label="Show the floating counter" hint="A small pill below the status bar, only while a Reels or Shorts feed is on screen. It never takes touches." value={c.enabled} onChange={(v) => { setC({ enabled: v }); needsGuard(v, 'The counter needs the guard to know when a Reels or Shorts feed is open.'); }} />
        {c.enabled && <>
          <Toggle label="Instagram Reels" value={c.ig} onChange={(v) => setC({ ig: v })} />
          <Toggle label="YouTube Shorts" value={c.yt} onChange={(v) => setC({ yt: v })} />
          <Text style={{ color: C.text, marginTop: 6 }}>The pill shows</Text>
          <Chips value={c.mode} onPick={(k) => setC({ mode: k as any })} items={[{ k: 'app', l: 'This app’s count' }, { k: 'total', l: 'Combined total' }]} />
          <Text style={{ color: C.text }}>Cat message</Text>
          <Chips value={c.text} onPick={(k) => setC({ text: k as any })} items={[{ k: 'milestone', l: 'At milestones' }, { k: 'always', l: 'Always' }, { k: 'never', l: 'Never' }]} />
          <Stepper label="Distance below status bar" value={c.y} min={0} max={400} step={8} unit=" dp" onChange={(v) => setC({ y: v })} />
          <Stepper label="Horizontal position" value={Math.round(c.fx * 100)} min={0} max={100} step={10} unit=" %" onChange={(v) => setC({ fx: v / 100 })} />
          <Stepper label="Size" value={Math.round(c.scale * 100)} min={70} max={160} step={10} unit=" %" onChange={(v) => setC({ scale: v / 100 })} />
          <Stepper label="Opacity" value={Math.round(c.opacity * 100)} min={30} max={100} step={10} unit=" %" onChange={(v) => setC({ opacity: v / 100 })} />
          <Toggle label="Hide in landscape" hint="Keeps full-screen landscape video clear." value={c.hideLandscape} onChange={(v) => setC({ hideLandscape: v })} />
          <Text style={{ color: C.text, marginTop: 10, fontWeight: '700' }}>Cat reactions</Text>
          <Sub>The pill picks the highest row whose number you have reached.</Sub>
          {c.tiers.map((t, i) => (
            <View key={i} style={{ borderTopWidth: i ? 1 : 0, borderTopColor: C.line, paddingVertical: 6 }}>
              <Stepper label={`${t.emoji}  from`} value={t.min} min={1} max={999} step={t.min < 10 ? 1 : 5} onChange={(v) => setTier(i, { min: v })} />
              <View style={{ flexDirection: 'row', gap: 8 }}>
                <TextInput value={t.emoji} onChangeText={(v) => setTier(i, { emoji: v })} maxLength={8} accessibilityLabel="Emoji" style={{ width: 64, color: C.text, backgroundColor: C.card2, borderRadius: 12, padding: 10, minHeight: 44, textAlign: 'center' }} />
                <TextInput value={t.text} onChangeText={(v) => setTier(i, { text: v })} maxLength={40} accessibilityLabel="Message" style={{ flex: 1, color: C.text, backgroundColor: C.card2, borderRadius: 12, padding: 10, minHeight: 44 }} />
              </View>
            </View>
          ))}
          <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Reset reactions" onPress={() => setC({ tiers: DEFAULT_TIERS.map((t) => ({ ...t })) })} /></View>
        </>}
      </Card>

      <Card>
        <H>Block Reels & Shorts</H>
        <Sub>Only the short-video feeds are covered. Chats, search, subscriptions, regular YouTube videos and the rest of both apps keep working.</Sub>
        <Toggle label="Block Instagram Reels" value={b.ig} onChange={(v) => { setB({ ig: v }); needsGuard(v, 'Blocking needs the guard to detect the Reels feed.'); }} />
        <Toggle label="Block YouTube Shorts" value={b.yt} onChange={(v) => { setB({ yt: v }); needsGuard(v, 'Blocking needs the guard to detect the Shorts feed.'); }} />
        {(b.ig || b.yt) && <>
          <Toggle label="Strict mode" hint="No pause button and no timeout: the block screen stays for as long as the feed is open." value={b.strict} onChange={(v) => setB({ strict: v })} />
          <Toggle label="Also steer me out of the feed" hint="Taps the app’s Home tab, then Back, then the Android home screen if the feed is still open. Stops audio playing behind the block screen." value={b.autoLeave} onChange={(v) => setB({ autoLeave: v })} />
          {!b.strict && <Stepper label="“Pause” lasts" value={b.pauseMin} min={1} max={60} step={1} unit=" min" onChange={(v) => setB({ pauseMin: v })} />}
        </>}
        <Row label="PIN for changing these settings" ok={!!status?.hasPin} chip={status?.hasPin ? 'Set' : 'Not set'} />
        <View style={{ alignSelf: 'flex-start', marginTop: 6 }}><Btn small kind="ghost" title={status?.hasPin ? 'Remove PIN' : 'Set a PIN'} onPress={pinFlow} /></View>
        <Sub>{'What this can and cannot do: Instagram and YouTube do not offer a switch to remove their short-video feeds, so Meow Island covers the feed with a full-screen shield and leaves it only when you do. It follows what the apps show on screen, so an app update that renames its internal screens can make detection stop working until Meow Island is updated: the Status card and the detection log below show when that happens. The PIN only slows down impulsive changes. Anyone with your unlocked phone can still switch the guard off in Android settings or uninstall the app.'}</Sub>
        {!!status && status.shieldToday > 0 && <Sub>{`The shield appeared ${status.shieldToday} time${status.shieldToday === 1 ? '' : 's'} today.`}</Sub>}
      </Card>

      <Card>
        <H>Daily goal</H>
        <Stepper label="Reels + Shorts goal (0 = off)" value={shorts.goal.daily} min={0} max={1000} step={5} onChange={(v) => setShorts({ ...shorts, goal: { ...shorts.goal, daily: v } })} />
        {shorts.goal.daily > 0 && <Stepper label="Gentle reminder every" value={shorts.goal.remindEvery} min={0} max={200} step={5} unit={shorts.goal.remindEvery ? ' more' : ' (off)'} onChange={(v) => setShorts({ ...shorts, goal: { ...shorts.goal, remindEvery: v } })} />}
        <Sub>Reminders appear on the Meow Island pill, once when you reach the goal and then at the interval you choose.</Sub>
      </Card>

      <Card>
        <H>Adult website filter</H>
        <Toggle label="Block known adult websites" hint="Works in browsers and other apps by filtering website lookups on this phone." value={adult.enabled} onChange={toggleAdult} />
        <Row label="Status" ok={adultState.ok} chip={adultState.t} />
        {busy === 'vpn' && <Loading />}
        {!!msg && <Sub>{msg}</Sub>}
        {adult.enabled && !!adultStatus?.error && <ErrorBox text={adultStatus.error} />}
        {adultStatus?.privateDns === 'hostname' && <ErrorBox text="Private DNS is set to a specific provider in Android settings. Android then sends lookups there directly, past this filter. Switch Private DNS to Automatic or Off (Settings → Network → Private DNS) for the filter to work." />}
        {adult.enabled && !adultStatus?.running && adultStatus?.otherVpn && <ErrorBox text="Another VPN app is active. Android allows only one VPN at a time, so this filter cannot run while it is on. Meow Island never replaces another VPN by itself." />}
        {adult.enabled && !adultStatus?.running && !adultStatus?.permission && <View style={{ alignSelf: 'flex-start', marginVertical: 6 }}><Btn small title="Grant VPN permission" onPress={() => toggleAdult(true)} /></View>}
        {adultStatus && <Sub>{`Blocked lookups: ${adultStatus.blockedToday} today · ${adultStatus.blockedTotal} total. Only counts are kept, never the sites. Blocklist: ${adultStatus.listCount.toLocaleString()} domains (${adultStatus.listUpdatedAt ? 'updated ' + new Date(adultStatus.listUpdatedAt).toLocaleDateString() : 'bundled with the app'}).`}</Sub>}
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
          <Btn small kind="ghost" title={busy === 'list' ? 'Updating…' : 'Update blocklist'} disabled={busy === 'list'} onPress={updateList} />
          <Btn small kind="ghost" title="Android VPN settings" onPress={() => wb.openVpnSettings()} />
        </View>
        <Toggle label="Show “Blocked” on the island" hint="At most once every 20 seconds." value={adult.notify} onChange={(v) => setAdult({ ...adult, notify: v })} />
        <Text style={{ color: C.text, marginTop: 6 }}>Where lookups for other sites go</Text>
        <Chips value={adult.upstream} onPick={(k) => setAdult({ ...adult, upstream: k as any })} items={[{ k: 'system', l: 'My network’s DNS' }, { k: 'cloudflare', l: 'Cloudflare family' }, { k: 'custom', l: 'Custom' }]} />
        {adult.upstream === 'custom' && <Field label="DNS server address" value={adult.customDns} onChange={(v) => setAdult({ ...adult, customDns: isIp(v) || v === '' || /^[0-9a-fA-F:.]*$/.test(v) ? v : adult.customDns })} placeholder="9.9.9.9" keyboard="numbers-and-punctuation" />}
        <Sub>{adult.upstream === 'cloudflare' ? 'Cloudflare’s “family” resolver (1.1.1.3) also filters adult sites on its side, an extra layer. Your lookups for other sites then go to Cloudflare.' : adult.upstream === 'system' ? 'Your normal DNS is kept, so nothing changes about who sees your lookups.' : 'Lookups for allowed sites go to the server you enter.'}</Sub>
        <Field label="Always allow a website" value={allowTxt} onChange={setAllowTxt} placeholder="example.com" keyboard="url" />
        <View style={{ alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Add to allow list" onPress={() => addDomain('allow', allowTxt, () => setAllowTxt(''))} /></View>
        <Field label="Always block a website" value={blockTxt} onChange={setBlockTxt} placeholder="example.com" keyboard="url" />
        <View style={{ alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Add to block list" onPress={() => addDomain('block', blockTxt, () => setBlockTxt(''))} /></View>
        {([['allow', 'Allowed'], ['block', 'Blocked by you']] as const).map(([k, l]) => adult[k].length > 0 && (
          <View key={k} style={{ marginTop: 8 }}><Text style={{ color: C.text, fontWeight: '700' }}>{l}</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 4 }}>{adult[k].map((d) => <Pressable key={d} accessibilityRole="button" accessibilityLabel={`Remove ${d}`} onPress={() => setAdult({ ...adult, [k]: adult[k].filter((x) => x !== d) })} style={{ backgroundColor: C.card2, borderRadius: 14, paddingHorizontal: 10, minHeight: 36, justifyContent: 'center' }}><Text style={{ color: C.text }}>{d}  ✕</Text></Pressable>)}</View></View>
        ))}
        <Field label="Blocklist source (https only)" value={adult.blocklistUrl} onChange={(v) => setAdult({ ...adult, blocklistUrl: v })} max={300} keyboard="url" />
        {!isHttps(adult.blocklistUrl) && <Sub>Not a valid https address; the default source will be used.</Sub>}
        {adult.blocklistUrl !== DEFAULT_BLOCKLIST_URL && <View style={{ alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Use the default source" onPress={() => setAdult({ ...adult, blocklistUrl: DEFAULT_BLOCKLIST_URL })} /></View>}
        <Sub>{'Honest limits: this blocks the websites’ names, so it cannot catch every adult site, anything that connects by raw IP address, an app that brings its own encrypted DNS, new domains that are not on the list yet, cached pages, or explicit images and videos inside apps and on sites that are not on the list. Another VPN, or Private DNS set to a specific provider, replaces it (Android runs one VPN at a time). The popular community list it uses is the StevenBlack “porn” hosts list (MIT licence) plus the adult-only domain endings .xxx .porn .adult .sex. A blocked site simply fails to load: a DNS filter cannot show a friendly page for secure (https) sites. Nothing is logged except how many lookups were blocked.'}</Sub>
      </Card>

      <Card>
        <H>Detection log</H>
        <Sub>Event names only (no usernames, captions or links). Useful if counting or blocking stops working after an Instagram or YouTube update.</Sub>
        {status && <Sub>{`Checked ${status.evals} times since the guard started, matched a feed ${status.matches} times${status.lastError ? '. Last error: ' + status.lastError : '.'}`}</Sub>}
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
          <Btn small kind="ghost" title={log == null ? 'Show log' : 'Refresh log'} onPress={async () => setLog((await wb.getDiagnosticLog()) || '(empty)')} />
          <Btn small kind="ghost" title="Clear counts" onPress={() => Alert.alert('Clear all Reels & Shorts history?', 'This cannot be undone.', [{ text: 'Cancel', style: 'cancel' }, { text: 'Clear', style: 'destructive', onPress: async () => { await wb.clearShortsStats(); refresh(); } }])} />
        </View>
        {log != null && <ErrorBox text={log} />}
      </Card>
    </View>
  );
}
