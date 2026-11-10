import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Image, Pressable, Text, View, useWindowDimensions } from 'react-native';
import { Btn, C, Card, Chip, ErrorBox, H, Sub, Toggle } from '../ui';
import { dev, devEvents, devx, deviceExtrasAvailable } from '../native';
import { useDevice } from '../device';
import { FITS, INTERVALS, Library, QUALITIES, addSummary, intervalLabel, loopLabel, photoBadge, sendSeconds, sizeLabel, storageLine } from '../core/gallery';

type Busy = '' | 'adding' | 'compressing';

function Seg({ label, options, value, onChange, disabled }: { label: string; options: [string, any][]; value: any; onChange: (v: any) => void; disabled?: boolean }) {
  return (
    <View style={{ marginTop: 12 }}>
      <Text style={{ color: C.sub, fontSize: 12, marginBottom: 6 }}>{label}</Text>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {options.map(([t, v]) => (
          <Pressable key={t} accessibilityRole="button" accessibilityLabel={`${label}: ${t}`} disabled={disabled} onPress={() => onChange(v)}
            style={{ paddingHorizontal: 14, minHeight: 38, justifyContent: 'center', borderRadius: 19, opacity: disabled ? 0.5 : 1, backgroundColor: value === v ? C.accent : C.card2 }}>
            <Text style={{ color: value === v ? C.onAccent : C.text, fontSize: 13 }}>{t}</Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

function Tool({ label, text, onPress, disabled }: { label: string; text: string; onPress: () => void; disabled?: boolean }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} disabled={disabled} onPress={onPress} hitSlop={4}
      style={{ minWidth: 40, minHeight: 40, alignItems: 'center', justifyContent: 'center', borderRadius: 10, backgroundColor: C.card, opacity: disabled ? 0.35 : 1 }}>
      <Text style={{ color: C.text, fontSize: 16 }}>{text}</Text>
    </Pressable>
  );
}

/**
 * "Photo Gallery": pick pictures, they are compressed heavily on this phone (a few KB to ~30 KB each) and sent to the display, which shows them full screen
 * one after the other. Order, time per photo, and how the picture is fitted can be changed at any time. Everything stays on this phone and travels over
 * the existing Bluetooth link.
 */
export default function Gallery({ back }: { back: () => void }) {
  const { state: d } = useDevice();
  const { width } = useWindowDimensions();
  const [lib, setLib] = useState<Library | null>(null);
  const [busy, setBusy] = useState<Busy>('');
  const [prog, setProg] = useState<{ done: number; total: number } | null>(null);
  const [err, setErr] = useState('');
  const [note, setNote] = useState('');
  const alive = useRef(true);

  const refresh = useCallback(async () => {
    if (!deviceExtrasAvailable) return;
    try { const l = await devx.galleryList(); if (alive.current && l) setLib(l); } catch (e: any) { if (alive.current) setErr(e?.message ?? 'Could not read the gallery'); }
  }, []);

  useEffect(() => {
    alive.current = true; refresh();
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') refresh(); });
    const t = devEvents ? devEvents.addListener('galleryTransfer', (e: any) => {
      if (!alive.current) return;
      setLib((p) => (p ? { ...p, transfer: { ...p.transfer, state: e?.state ?? p.transfer.state, id: String(e?.id ?? ''), sent: Number(e?.sent) || 0, total: Number(e?.total) || p.transfer.total, message: String(e?.message ?? '') } } : p));
      if (e?.state === 'done' || e?.state === 'error') refresh();
    }) : null;
    const g = devEvents ? devEvents.addListener('galleryProgress', (e: any) => { if (alive.current) setProg({ done: Number(e?.done) || 0, total: Number(e?.total) || 0 }); }) : null;
    const poll = setInterval(refresh, 4000);                 // the display's state (which photos it holds) changes without an event
    return () => { alive.current = false; sub.remove(); t?.remove(); g?.remove(); clearInterval(poll); };
  }, [refresh]);

  const fail = (e: any, fallback: string) => setErr(e?.message ? String(e.message) : fallback);

  const add = async () => {
    setErr(''); setNote(''); setBusy('adding'); setProg(null);
    try {
      const r = await devx.galleryPick();
      if (r && alive.current) { setLib(r.library); setNote(addSummary(r)); if (r.added > 0 && d.ready && r.library.device.hasGallery) { try { await devx.gallerySend(); } catch { /* shown by the badges */ } } }
    } catch (e: any) { fail(e, 'Could not add those pictures.'); }
    finally { if (alive.current) { setBusy(''); setProg(null); } }
  };
  const change = async (s: { intervalSec?: number; fit?: string; quality?: string }) => {
    setErr(''); setNote('');
    const recompress = s.fit !== undefined || s.quality !== undefined; if (recompress) setBusy('compressing');
    try { const l = await devx.gallerySetSettings(s); if (l && alive.current) setLib(l); } catch (e: any) { fail(e, 'Could not change the setting.'); }
    finally { if (alive.current) setBusy(''); }
  };
  const move = (id: string, delta: number) => devx.galleryMove(id, delta).then((l) => { if (l && alive.current) setLib(l); }).catch((e: any) => fail(e, 'Could not move the photo.'));
  const remove = (id: string) => Alert.alert('Remove this photo?', 'It is deleted from this phone and from the display. The other photos stay.', [
    { text: 'Cancel', style: 'cancel' },
    { text: 'Remove', style: 'destructive', onPress: () => { devx.galleryRemove(id).then((l) => { if (l && alive.current) setLib(l); }).catch((e: any) => fail(e, 'Could not remove the photo.')); } },
  ]);
  const act = (p: Promise<any>, ok = '') => p.then(() => { if (ok) setNote(ok); refresh(); }).catch((e: any) => setErr(e?.message ?? 'Not connected'));

  if (!deviceExtrasAvailable) return <Card><H>Needs the latest build</H><Sub>This APK was built before the photo gallery was added. Rebuild the app (see the README).</Sub><View style={{ height: 8 }} /><Btn title="Back" kind="ghost" onPress={back} /></Card>;

  const photos = lib?.photos ?? [];
  const st = lib?.settings; const tr = lib?.transfer; const sending = tr?.state === 'sending';
  const hasGallery = !!lib?.device.hasGallery; const oldFw = !!lib && lib.connected && !hasGallery;
  const missing = photos.filter((p) => !p.onDevice).length;
  const cell = Math.max(80, Math.floor((width - 64 - 16) / 3)); const cellH = Math.round((cell * 4) / 3);
  const avg = photos.length ? Math.round((lib?.totalBytes ?? 0) / photos.length) : 0;
  const full = photos.length >= (lib?.max ?? 24);

  return (
    <View style={{ gap: 12 }}>
      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Photo Gallery</H><Chip ok={d.ready} label={d.ready ? 'Display connected' : 'Display not connected'} /></View>
        <Sub>Pick photos on this phone. They are compressed small and the display shows them full screen as a slideshow. Nothing is uploaded anywhere; the photos travel to the display over Bluetooth.</Sub>
        {oldFw && <Text style={{ color: C.warn, marginTop: 8 }}>This display runs firmware {lib?.device.fw || 'older than 1.3'}. Flash firmware 1.3 or later to show photos; everything else keeps working.</Text>}
        {!!err && <ErrorBox text={err} />}
        {!!note && !err && <Text style={{ color: C.ok, marginTop: 8 }}>{note}</Text>}
        <View style={{ height: 10 }} />
        <Btn title={busy === 'adding' ? (prog && prog.total > 0 ? `Compressing ${Math.min(prog.done + 1, prog.total)} of ${prog.total}…` : 'Preparing…') : busy === 'compressing' ? 'Compressing again…' : 'Add photos'} disabled={busy !== '' || full} onPress={add} />
        {full && <Sub>The display holds {lib?.max ?? 24} photos. Remove one to add another.</Sub>}
      </Card>

      <Card>
        <H>Slideshow</H>
        <Sub>{loopLabel(photos.length, st?.intervalSec ?? 10)}</Sub>
        <Seg label="Time per photo" options={INTERVALS.map((i) => [i.label, i.sec] as [string, number])} value={st?.intervalSec} onChange={(v) => change({ intervalSec: v })} />
        <Seg label="Picture" options={FITS.map((f) => [f.label, f.id] as [string, string])} value={st?.fit} disabled={busy !== ''} onChange={(v) => change({ fit: v })} />
        <Text style={{ color: C.sub, fontSize: 12, marginTop: 6 }}>{FITS.find((f) => f.id === st?.fit)?.hint}</Text>
        <Seg label="Compression" options={QUALITIES.map((q) => [q.label, q.id] as [string, string])} value={st?.quality} disabled={busy !== ''} onChange={(v) => change({ quality: v })} />
        <Text style={{ color: C.sub, fontSize: 12, marginTop: 6 }}>{QUALITIES.find((q) => q.id === st?.quality)?.hint}</Text>
      </Card>

      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Photos ({photos.length}/{lib?.max ?? 24})</H>
          {!!lib && <Text style={{ color: C.sub, fontSize: 12 }}>{sizeLabel(lib.totalBytes)} in total</Text>}</View>
        {photos.length === 0 && <Sub>No photos yet. Tap "Add photos" to choose some. You can pick many at once.</Sub>}
        {!!lib && !!storageLine(lib.device, avg) && <Text style={{ color: C.sub, fontSize: 12, marginTop: 4 }}>{storageLine(lib.device, avg)}</Text>}
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 }}>
          {photos.map((p, i) => {
            const b = photoBadge(p, tr ?? { state: 'idle', id: '', sent: 0, total: 0, message: '', queued: 0 });
            return (
              <View key={p.id} style={{ width: cell }}>
                <View>
                  <Image source={{ uri: p.uri }} style={{ width: cell, height: cellH, borderRadius: 8, backgroundColor: '#000' }} resizeMode="cover" accessibilityLabel={`Photo ${i + 1}, ${b.text}`} />
                  <View style={{ position: 'absolute', top: 4, left: 4, backgroundColor: '#000a', borderRadius: 9, paddingHorizontal: 7, paddingVertical: 1 }}><Text style={{ color: '#fff', fontSize: 11 }}>{i + 1}</Text></View>
                  <View style={{ position: 'absolute', bottom: 4, left: 4, right: 4, alignItems: 'flex-start' }}><View style={{ backgroundColor: b.ok ? '#1b5e3acc' : '#000a', borderRadius: 8, paddingHorizontal: 6, paddingVertical: 1 }}><Text style={{ color: '#fff', fontSize: 10 }} numberOfLines={1}>{b.text}</Text></View></View>
                </View>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 6 }}>
                  <Tool label={`Move photo ${i + 1} earlier`} text="‹" disabled={i === 0} onPress={() => move(p.id, -1)} />
                  <Tool label={`Move photo ${i + 1} later`} text="›" disabled={i === photos.length - 1} onPress={() => move(p.id, 1)} />
                  <Tool label={`Remove photo ${i + 1}`} text="✕" onPress={() => remove(p.id)} />
                </View>
                <Text style={{ color: C.sub, fontSize: 11, marginTop: 2 }}>{sizeLabel(p.size)}</Text>
              </View>
            );
          })}
        </View>
        {sending && tr && <View style={{ marginTop: 12, height: 6, borderRadius: 3, backgroundColor: C.card2 }}><View style={{ width: `${tr.total > 0 ? Math.min(100, Math.round((tr.sent / tr.total) * 100)) : 0}%`, height: 6, borderRadius: 3, backgroundColor: C.accent }} /></View>}
        {tr?.state === 'error' && <ErrorBox text={tr.message || 'The transfer failed.'} />}
        {missing > 0 && d.ready && hasGallery && !sending && <Sub>{missing} photo{missing === 1 ? ' is' : 's are'} not on the display yet (about {sendSeconds((lib?.totalBytes ?? 0))} s to send everything).</Sub>}
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
          <Btn small title="Show on display" disabled={!d.ready || !hasGallery || photos.length === 0} onPress={() => act(devx.galleryShow())} />
          <Btn small kind="ghost" title={missing > 0 ? 'Send to display' : 'Send again'} disabled={!d.ready || !hasGallery || sending || photos.length === 0} onPress={() => act(devx.gallerySend(), 'Sending to the display…')} />
          {sending && <Btn small kind="ghost" title="Cancel sending" onPress={() => devx.galleryCancel()} />}
          <Btn small kind="ghost" title="Back to clock" disabled={!d.ready} onPress={() => act(dev.setScreen(0))} />
        </View>
        <Toggle label="Keep the display up to date" hint="When the display connects, photos it is missing are sent automatically and the slideshow setting is applied." value={!!lib?.auto} onChange={(v) => { if (lib) setLib({ ...lib, auto: v }); devx.gallerySetAuto(v); }} />
        {!!lib && lib.device.deviceOnly.length > 0 && (
          <View style={{ marginTop: 8 }}>
            <Sub>{lib.device.deviceOnly.length} photo{lib.device.deviceOnly.length > 1 ? 's are' : ' is'} on the display but not in this app (for example after reinstalling).</Sub>
            <View style={{ marginTop: 6, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Remove them from the display" onPress={() => Alert.alert('Remove from the display?', 'These photos are not saved on this phone, so they cannot be restored.', [{ text: 'Cancel', style: 'cancel' }, { text: 'Remove', style: 'destructive', onPress: () => act(devx.galleryClearDeviceOnly()) }])} /></View>
          </View>
        )}
      </Card>
      <Btn title="Back" kind="ghost" onPress={back} />
    </View>
  );
}
