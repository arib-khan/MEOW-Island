import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Image, Pressable, Text, TextInput, View, useWindowDimensions } from 'react-native';
import { Btn, C, Card, Chip, ErrorBox, H, Sub, Toggle } from '../ui';
import { dev, devEvents, devx, deviceExtrasAvailable, Library, ScreenEntry } from '../native';
import { useDevice } from '../device';
import { b64decode, b64encode, sizeLabel, transferEstimateSeconds } from '../core/customscreen';
import {
  Animation, Built, DEFAULT_MAX_FILE, DEFAULT_SETTINGS, HARNESS_JS, MAX_FPS, ScreenSettings, buildAnimation, bundleProblem, composeFrames, decodeAnimation, durationLabel,
  framesFromNative, gifUri, normalizeSettings, prepareHtml, screenNameFromFile, storageProblem, thumbUri,
} from '../core/animscreen';

type Draft = {
  source: string; token: string; id: string; name: string; html: string; files: number; settings: ScreenSettings; built: Built; gif: string; notes: string[]; warnings: string[];
};
type Busy = '' | 'picking' | 'rendering' | 'saving';

const RENDER_ERRORS: Record<string, string> = {
  TIMEOUT: 'The design took too long to render. Remove heavy scripts or very large images, or lower the frame rate or duration.',
  NO_ACTIVITY: 'Keep the app open on screen while the design is rendered, then try again.',
  RENDERER_GONE: 'The web renderer crashed on this design. Try a simpler file.',
  TOO_HEAVY: 'This animation changes too much on screen to record. Lower the frame rate or the duration, or animate a smaller area.',
  NOT_FOUND: 'The files of this screen are no longer on the phone. Choose them again.',
};
const FPS = [5, 10, MAX_FPS];
const DURATIONS: [string, number][] = [['Auto', 0], ['2 s', 2000], ['4 s', 4000], ['8 s', 8000]];

function Seg({ label, options, value, onChange }: { label: string; options: [string, any][]; value: any; onChange: (v: any) => void }) {
  return (
    <View style={{ marginTop: 10 }}>
      <Text style={{ color: C.sub, fontSize: 12, marginBottom: 6 }}>{label}</Text>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {options.map(([t, v]) => (
          <Pressable key={t} accessibilityRole="button" accessibilityLabel={`${label}: ${t}`} onPress={() => onChange(v)}
            style={{ paddingHorizontal: 14, minHeight: 36, justifyContent: 'center', borderRadius: 18, backgroundColor: value === v ? C.accent : C.card2 }}>
            <Text style={{ color: value === v ? C.onAccent : C.text, fontSize: 13 }}>{t}</Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

/**
 * "Custom Screens": pick an .html file (with its images, or a .zip), see it exactly as the display will play it, keep up to 8 of them and choose which one
 * the display shows. The phone renders the page (HTML, CSS, JavaScript, animations) and records it; the display plays the recording from its flash memory.
 * Everything stays on this phone and travels over the existing Bluetooth link.
 */
export default function CustomScreen({ back }: { back: () => void }) {
  const { state: d } = useDevice();
  const { width } = useWindowDimensions();
  const [lib, setLib] = useState<Library | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [thumbs, setThumbs] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<Busy>('');
  const [err, setErr] = useState('');
  const [note, setNote] = useState('');
  const [renaming, setRenaming] = useState<{ id: string; text: string } | null>(null);
  const alive = useRef(true);
  const thumbFor = useRef<Record<string, string>>({});

  const refresh = useCallback(async () => {
    if (!deviceExtrasAvailable) return;
    try {
      const l = await devx.screensList(); if (!alive.current || !l) return; setLib(l);
      for (const e of l.screens) {                       // list thumbnails: first picture of each saved file, decoded once per version
        const key = `${e.id}:${e.crc}`; if (thumbFor.current[e.id] === key) continue; thumbFor.current[e.id] = key;
        devx.screensGetDevice(e.id).then((b64) => { const a = decodeAnimation(b64decode(b64)); const u = thumbUri(composeFrames(a)[0], a.w, a.h); if (alive.current) setThumbs((t) => ({ ...t, [e.id]: u })); }).catch(() => { delete thumbFor.current[e.id]; });
      }
    } catch (e: any) { if (alive.current) setErr(e?.message ?? 'Could not read the saved screens'); }
  }, []);

  useEffect(() => {
    alive.current = true; refresh();
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') refresh(); });
    const ev = devEvents ? devEvents.addListener('customTransfer', (e: any) => {
      if (!alive.current) return;
      setLib((p) => (p ? { ...p, transfer: { ...p.transfer, state: e?.state ?? p.transfer.state, id: String(e?.id ?? ''), sent: Number(e?.sent) || 0, total: Number(e?.total) || p.transfer.total, message: String(e?.message ?? '') } } : p));
      if (e?.state === 'done' || e?.state === 'error') refresh();
    }) : null;
    const poll = setInterval(refresh, 4000);                // the display's state (which screens it holds) changes without an event
    return () => { alive.current = false; sub.remove(); ev?.remove(); clearInterval(poll); };
  }, [refresh]);

  const fail = (e: any, fallback: string) => setErr(RENDER_ERRORS[e?.code] ?? (e?.message ? String(e.message) : fallback));

  /** Renders, records, encodes and previews a screen. source = 'pending:<token>' (just picked) or 'saved:<id>' (re-render with other settings). */
  const build = async (src: { source: string; token: string; id: string; name: string; html: string; files: number; warnings: string[] }, settings: ScreenSettings) => {
    setBusy('rendering'); setErr(''); setNote('');
    try {
      const prepared = prepareHtml(src.html, settings.scripts);
      const r = await devx.screensRender(src.source, prepared, HARNESS_JS, JSON.stringify(settings));
      const { frames, delays } = framesFromNative(r.frames);
      const built = buildAnimation(frames, delays, { loop: r.loop, maxBytes: DEFAULT_MAX_FILE });
      const gif = gifUri(decodeAnimation(built.bytes));
      if (alive.current) setDraft({ ...src, settings, built, gif, notes: r.notes ?? [] });
    } catch (e: any) { fail(e, 'Could not render this file.'); }
    finally { if (alive.current) setBusy(''); }
  };

  const choose = async (addTo?: string) => {
    setBusy('picking'); setErr(''); setNote('');
    try {
      const p = await devx.screensPick(addTo);
      if (!p) { setBusy(''); return; }
      const why = bundleProblem(p.htmlName, p.html.length, p.html);
      if (why) { setErr(why); devx.screensDiscard(p.token); setBusy(''); return; }
      const settings = draft && addTo ? draft.settings : normalizeSettings(DEFAULT_SETTINGS);
      await build({ source: 'pending:' + p.token, token: p.token, id: draft && addTo ? draft.id : '', name: draft && addTo ? draft.name : screenNameFromFile(p.htmlName), html: p.html, files: p.assets.length, warnings: p.warnings }, settings);
    } catch (e: any) { fail(e, 'Could not open the file.'); setBusy(''); }
  };

  const edit = async (e: ScreenEntry) => {
    setBusy('rendering'); setErr(''); setNote('');
    try {
      const html = await devx.screensGetHtml('saved:' + e.id);
      await build({ source: 'saved:' + e.id, token: '', id: e.id, name: e.name, html, files: 0, warnings: [] }, normalizeSettings(e.settings));
    } catch (x: any) { fail(x, 'Could not open this screen.'); setBusy(''); }
  };

  const save = async (andSend: boolean) => {
    if (!draft) return;
    const problem = storageProblem(draft.built.bytes.length, lib?.device.hasSlots ? { totalKb: lib.device.totalKb, freeKb: lib.device.freeKb } : null);
    if (problem && andSend) { setErr(problem); return; }
    setBusy('saving'); setErr('');
    try {
      const r = await devx.screensSave(draft.id, draft.token, draft.name, JSON.stringify(draft.settings), b64encode(draft.built.bytes), draft.built.frames, draft.built.animated, draft.built.loop, draft.built.durationMs);
      if (!r) throw new Error('Could not save');
      if (!alive.current) return;
      setLib(r.library); setDraft(null);
      if (andSend && d.ready) { try { await devx.screensSend([r.saved.id]); setNote('Sending to the display…'); } catch (e: any) { setErr(e?.message ?? 'Could not send'); } }
      else setNote(d.ready ? 'Saved on this phone.' : 'Saved. It is sent when the display connects.');
    } catch (e: any) { fail(e, 'Could not save the screen.'); }
    finally { if (alive.current) setBusy(''); }
  };

  const discard = () => { if (draft?.token) devx.screensDiscard(draft.token); setDraft(null); setErr(''); };
  const confirmRemove = (e: ScreenEntry) => Alert.alert('Remove "' + e.name + '"?', 'It is deleted from this phone and from the display. The other screens stay.', [
    { text: 'Cancel', style: 'cancel' },
    { text: 'Remove', style: 'destructive', onPress: () => { devx.screensRemove(e.id).then((l) => { if (l && alive.current) { setLib(l); delete thumbFor.current[e.id]; } }).catch((x: any) => setErr(x?.message ?? 'Could not remove')); } },
  ]);
  const act = (p: Promise<any>, ok = '') => p.then(() => { if (ok) setNote(ok); refresh(); }).catch((e: any) => setErr(e?.message ?? 'Not connected'));

  if (!deviceExtrasAvailable) return <Card><H>Needs the latest build</H><Sub>This APK was built before custom screens were added. Rebuild the app (see the README).</Sub><View style={{ height: 8 }} /><Btn title="Back" kind="ghost" onPress={back} /></Card>;

  const pw = Math.min(240, width - 64), ph = Math.round((pw * 320) / 240);
  const tr = lib?.transfer; const sending = tr?.state === 'sending';
  const oldFw = !!lib && lib.connected && !lib.device.hasSlots;
  const upd = (patch: Partial<ScreenSettings>) => draft && setDraft({ ...draft, settings: normalizeSettings({ ...draft.settings, ...patch }) });
  const changed = !!draft && JSON.stringify(draft.settings) !== JSON.stringify(normalizeSettings((lib?.screens.find((s) => s.id === draft.id)?.settings) ?? draft.settings));
  const fullStorage = draft ? storageProblem(draft.built.bytes.length, lib?.device.hasSlots ? { totalKb: lib.device.totalKb, freeKb: lib.device.freeKb } : null) : null;

  return (
    <View style={{ gap: 12 }}>
      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Custom Screens</H><Chip ok={d.ready} label={d.ready ? 'Display connected' : 'Display not connected'} /></View>
        <Sub>Pick an HTML file (with its images, or a .zip). Your HTML, CSS and JavaScript run on this phone, animations are recorded, and the display plays the result. Nothing is uploaded; images come from your files or, if you allow it, from the web addresses in your HTML.</Sub>
        {oldFw && <Text style={{ color: C.warn, marginTop: 8 }}>This display runs firmware {lib?.device.fw || 'older than 1.3'}. Flash firmware 1.3 or later (firmware folder) to keep several or animated screens on it; everything else keeps working.</Text>}
        {!!err && <ErrorBox text={err} />}
        {!!note && !err && <Text style={{ color: C.ok, marginTop: 8 }}>{note}</Text>}
        <View style={{ height: 10 }} />
        <Btn title={busy === 'picking' ? 'Opening…' : busy === 'rendering' ? 'Rendering…' : 'Add a screen from HTML files'} disabled={busy !== '' || (lib?.screens.length ?? 0) >= (lib?.max ?? 8)} onPress={() => choose()} />
        {lib && lib.screens.length >= lib.max && <Sub>The display holds {lib.max} screens. Remove one to add another.</Sub>}
      </Card>

      {draft && (
        <Card>
          <H>{draft.id ? 'Edit screen' : 'New screen'}</H>
          <View style={{ alignItems: 'center', marginVertical: 10 }}>
            <Image source={{ uri: draft.gif }} style={{ width: pw, height: ph, borderRadius: 10, backgroundColor: '#000' }} resizeMode="contain" accessibilityLabel="Preview of the screen as the display will play it" />
          </View>
          <Sub>{draft.built.animated ? `${draft.built.frames} frames, ${durationLabel(draft.built.durationMs)}${draft.built.loop ? ', repeats' : ', plays once'}` : 'Still picture'} · {sizeLabel(draft.built.bytes.length)} · about {transferEstimateSeconds(draft.built.bytes.length)} s to send{draft.built.reduced ? ' · colours reduced to 256' : ''}</Sub>
          {draft.warnings.map((w, i) => <Text key={'w' + i} style={{ color: C.sub, fontSize: 12, marginTop: 4 }}>{w}</Text>)}
          {draft.notes.length > 0 && (
            <View style={{ marginTop: 8, padding: 10, borderRadius: 10, backgroundColor: C.card2 }}>
              <Text style={{ color: C.warn, fontWeight: '600', marginBottom: 4 }}>Some files did not load</Text>
              {draft.notes.map((n, i) => <Text key={'n' + i} style={{ color: C.text, fontSize: 12, marginTop: 2 }}>• {n}</Text>)}
              {draft.token !== '' && <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Add the missing files" onPress={() => choose(draft.token)} /></View>}
            </View>
          )}
          <View style={{ height: 8 }} />
          <TextInput value={draft.name} onChangeText={(t) => setDraft({ ...draft, name: t.slice(0, 40) })} placeholder="Name" placeholderTextColor={C.sub} accessibilityLabel="Screen name"
            style={{ color: C.text, backgroundColor: C.card2, borderRadius: 10, paddingHorizontal: 12, minHeight: 44 }} />
          <Toggle label="Run JavaScript" hint="Lets your page draw and animate itself. It runs sealed inside the renderer with no access to the app or your data." value={draft.settings.scripts} onChange={(v) => upd({ scripts: v })} />
          <Toggle label="Load online images, fonts and styles" hint="The phone fetches the https addresses your HTML names (nothing else is sent). Off: only the files you chose are used." value={draft.settings.online} onChange={(v) => upd({ online: v })} />
          <Toggle label="Animate" hint="Records CSS animations, transitions, timers and requestAnimationFrame. Off: a single picture of the finished page." value={draft.settings.animate} onChange={(v) => upd({ animate: v })} />
          {draft.settings.animate && <>
            <Seg label="Frames per second" options={FPS.map((f) => [String(f), f] as [string, number])} value={draft.settings.fps} onChange={(v) => upd({ fps: v })} />
            <Seg label="Length" options={DURATIONS} value={draft.settings.durationMs} onChange={(v) => upd({ durationMs: v })} />
            <Seg label="When it ends" options={[['Automatic', 'auto'], ['Repeat', 'on'], ['Play once', 'off']]} value={draft.settings.loop} onChange={(v) => upd({ loop: v })} />
          </>}
          {!!fullStorage && <Text style={{ color: C.warn, marginTop: 8 }}>{fullStorage}</Text>}
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
            <Btn title={busy === 'saving' ? 'Saving…' : d.ready && lib?.device.hasSlots ? 'Save and send to display' : 'Save'} disabled={busy !== '' || sending || !draft.name.trim()} onPress={() => save(!!(d.ready && lib?.device.hasSlots))} />
            <Btn kind="ghost" title={changed || draft.token === '' ? 'Render again with these settings' : 'Render again'} disabled={busy !== ''} onPress={() => build(draft, draft.settings)} />
            <Btn kind="ghost" title="Cancel" disabled={busy === 'saving'} onPress={discard} />
          </View>
        </Card>
      )}

      {lib && lib.screens.length > 0 && (
        <Card>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Your screens ({lib.screens.length}/{lib.max})</H>
            {lib.device.hasSlots && lib.device.totalKb > 0 && <Text style={{ color: C.sub, fontSize: 12 }}>Display storage: {lib.device.freeKb} of {lib.device.totalKb} KB free</Text>}</View>
          <Sub>The BOOT button on the display steps through every screen stored on it, one tap each.</Sub>
          {lib.screens.map((e) => {
            const isSending = tr?.state === 'sending' && tr.id === e.id; const pct = isSending && tr && tr.total > 0 ? Math.min(100, Math.round((tr.sent / tr.total) * 100)) : 0;
            return (
              <View key={e.id} style={{ marginTop: 12, padding: 10, borderRadius: 12, backgroundColor: C.card2 }}>
                <View style={{ flexDirection: 'row', gap: 12 }}>
                  {thumbs[e.id] ? <Image source={{ uri: thumbs[e.id] }} style={{ width: 60, height: 80, borderRadius: 6, backgroundColor: '#000' }} accessibilityLabel={`Preview of ${e.name}`} /> : <View style={{ width: 60, height: 80, borderRadius: 6, backgroundColor: '#000' }} />}
                  <View style={{ flex: 1 }}>
                    {renaming?.id === e.id ? (
                      <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center' }}>
                        <TextInput autoFocus value={renaming.text} onChangeText={(t) => setRenaming({ id: e.id, text: t.slice(0, 40) })} accessibilityLabel="New name" style={{ flex: 1, color: C.text, backgroundColor: C.card, borderRadius: 8, paddingHorizontal: 10, minHeight: 40 }} />
                        <Btn small title="Save" onPress={() => { const t = renaming.text; setRenaming(null); devx.screensRename(e.id, t).then((l) => l && setLib(l)); }} />
                      </View>
                    ) : <Text style={{ color: C.text, fontSize: 16, fontWeight: '600' }} numberOfLines={1}>{e.name}</Text>}
                    <Text style={{ color: C.sub, fontSize: 12, marginTop: 2 }}>{e.animated ? `${e.frames} frames · ${durationLabel(e.durationMs)}` : 'Still'} · {sizeLabel(e.size)} · slot {e.slot + 1}</Text>
                    <View style={{ flexDirection: 'row', gap: 6, marginTop: 6, flexWrap: 'wrap' }}>
                      <Chip ok={e.onDevice} label={isSending ? `Sending ${pct}%` : e.queued ? 'Waiting to send' : e.onDevice ? 'On the display' : 'Not on the display'} />
                      {e.shown && <Chip ok label="Showing now" />}
                    </View>
                  </View>
                </View>
                <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 }}>
                  <Btn small title="Show" disabled={!d.ready || !e.onDevice || oldFw} onPress={() => act(devx.screensShow(e.id))} />
                  <Btn small kind="ghost" title={e.onDevice ? 'Send again' : 'Send'} disabled={!d.ready || sending || oldFw} onPress={() => act(devx.screensSend([e.id]), 'Sending to the display…')} />
                  <Btn small kind="ghost" title="Edit" disabled={busy !== ''} onPress={() => edit(e)} />
                  <Btn small kind="ghost" title="Rename" onPress={() => setRenaming({ id: e.id, text: e.name })} />
                  <Btn small kind="danger" title="Remove" onPress={() => confirmRemove(e)} />
                </View>
                {isSending && <View style={{ marginTop: 8, height: 6, borderRadius: 3, backgroundColor: C.card }}><View style={{ width: `${pct}%`, height: 6, borderRadius: 3, backgroundColor: C.accent }} /></View>}
              </View>
            );
          })}
          {tr?.state === 'error' && <ErrorBox text={tr.message || 'The transfer failed.'} />}
          {sending && <View style={{ marginTop: 10, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Cancel sending" onPress={() => devx.screensCancel()} /></View>}
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
            <Btn small title="Send all to display" disabled={!d.ready || sending || oldFw} onPress={() => act(devx.screensSend([]), 'Sending to the display…')} />
            <Btn small kind="ghost" title="Back to clock" disabled={!d.ready} onPress={() => act(dev.setScreen(0))} />
          </View>
          <Toggle label="Keep the display up to date" hint="When the display connects, screens it is missing or has an old version of are sent automatically." value={!!lib.auto} onChange={(v) => { setLib({ ...lib, auto: v }); devx.screensSetAuto(v); }} />
          {lib.device.deviceOnly.length > 0 && <View style={{ marginTop: 8 }}>
            <Sub>{lib.device.deviceOnly.length} screen{lib.device.deviceOnly.length > 1 ? 's are' : ' is'} on the display but not in this app (for example after reinstalling).</Sub>
            <View style={{ marginTop: 6, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Remove them from the display" onPress={() => Alert.alert('Remove from the display?', 'These screens are not saved on this phone, so they cannot be restored.', [{ text: 'Cancel', style: 'cancel' }, { text: 'Remove', style: 'destructive', onPress: () => act(devx.screensClearDeviceOnly()) }])} /></View>
          </View>}
        </Card>
      )}
      <Btn title="Back" kind="ghost" onPress={back} />
    </View>
  );
}
