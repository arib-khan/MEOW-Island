import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Image, Pressable, Text, View, useWindowDimensions } from 'react-native';
import { Btn, C, Card, Chip, ErrorBox, H, Sub, Toggle } from '../ui';
import { dev, devEvents, devx, deviceExtrasAvailable, CustomInfo } from '../native';
import { useDevice } from '../device';
import { DEVICE_H, DEVICE_W, MAX_HTML_BYTES, MAX_IMAGE_BYTES, b64decode, b64encode, decodeImage, encodeImage, htmlProblem, previewUri, rgb565FromBytes, sizeLabel, transferEstimateSeconds } from '../core/customscreen';

type Draft = { name: string; html: string; bytes: Uint8Array; colors: number; reduced: boolean; uri: string };
type Busy = '' | 'picking' | 'rendering' | 'saving';

const RENDER_ERRORS: Record<string, string> = {
  TIMEOUT: 'The design took too long to render (8 seconds). Remove heavy scripts or very large embedded images.',
  NO_ACTIVITY: 'Keep the app open on screen while the design is rendered, then try again.',
  RENDERER_GONE: 'The web renderer crashed on this design. Try a simpler file.',
  TOO_LARGE: `That file is larger than ${MAX_HTML_BYTES / 1024} KB.`,
};

/**
 * "Custom Screen": pick an .html file, see it exactly as the display will show it (240 x 320, after the same colour reduction), send it to the
 * device, and keep it. The file is rendered on this phone in an isolated WebView and sent over the existing Bluetooth link; nothing is uploaded.
 */
export default function CustomScreen({ back }: { back: () => void }) {
  const { state: d } = useDevice();
  const { width } = useWindowDimensions();
  const [info, setInfo] = useState<CustomInfo | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [savedUri, setSavedUri] = useState('');
  const [savedHtml, setSavedHtml] = useState('');
  const [busy, setBusy] = useState<Busy>('');
  const [err, setErr] = useState('');
  const [note, setNote] = useState('');
  const [scripts, setScripts] = useState(false);
  const alive = useRef(true);

  const loadSaved = useCallback(async (full: boolean) => {
    if (!deviceExtrasAvailable) return;
    try {
      const i = await devx.customGetSaved(full);
      if (!alive.current || !i) return;
      setInfo(i);
      if (full && i.image) { try { const dec = decodeImage(b64decode(i.image)); setSavedUri(previewUri(dec.pixels, dec.w, dec.h)); } catch { setSavedUri(''); } }
      if (full && i.html) setSavedHtml(i.html);
    } catch (e: any) { if (alive.current) setErr(e?.message ?? 'Could not read the saved design'); }
  }, []);

  useEffect(() => {
    alive.current = true; loadSaved(true);
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') loadSaved(false); });
    const ev = devEvents ? devEvents.addListener('customTransfer', (e: any) => {
      if (!alive.current) return;
      setInfo((p) => (p ? { ...p, state: e?.state ?? p.state, sent: Number(e?.sent) || 0, total: Number(e?.total) || p.total, message: String(e?.message ?? '') } : p));
      if (e?.state === 'done') loadSaved(false);
    }) : null;
    const poll = setInterval(() => loadSaved(false), 4000);   // device state (is this design on the display?) changes without an event
    return () => { alive.current = false; sub.remove(); ev?.remove(); clearInterval(poll); };
  }, [loadSaved]);

  const build = async (html: string, name: string) => {
    setBusy('rendering'); setErr(''); setNote('');
    try {
      const b64 = await devx.customRender(html, scripts);
      const px = rgb565FromBytes(b64decode(b64));
      const enc = encodeImage(px, DEVICE_W, DEVICE_H);
      const dec = decodeImage(enc.bytes);                              // the preview is the decoded device image, so what you see is what the display gets
      setDraft({ name, html, bytes: enc.bytes, colors: enc.colors, reduced: enc.reduced, uri: previewUri(dec.pixels, dec.w, dec.h) });
    } catch (e: any) {
      setDraft(null);
      setErr(e?.code === 'TOO_COMPLEX' || e?.code === 'BAD_SIZE' ? e.message : (RENDER_ERRORS[e?.code] ?? e?.message ?? 'Could not render this file.'));
    }
    setBusy('');
  };

  const choose = async () => {
    setErr(''); setNote(''); setBusy('picking');
    try {
      const f = await devx.customPick();
      if (!f) { setBusy(''); return; }                                   // cancelled
      if (f.tooLarge) { setBusy(''); setErr(`That file is larger than ${MAX_HTML_BYTES / 1024} KB. Simplify the design or shrink embedded images.`); return; }
      const problem = htmlProblem(f.name, f.size, f.html);
      if (problem) { setBusy(''); setErr(problem); return; }
      await build(f.html, f.name);
    } catch (e: any) { setBusy(''); setErr(e?.message ?? 'Could not open that file.'); }
  };

  const saveAndSend = async () => {
    if (!draft) return;
    setBusy('saving'); setErr(''); setNote('');
    try {
      await devx.customSave(b64encode(draft.bytes), draft.html, draft.name);
      setSavedUri(draft.uri); setSavedHtml(draft.html);
      await loadSaved(false);
      if (d.ready) { await devx.customSend(); setNote('Sending to the display…'); }
      else setNote('Saved on this phone. It will be sent automatically when the display connects.');
      setDraft(null);
    } catch (e: any) { setErr(e?.message ?? 'Could not save or send the design.'); }
    setBusy('');
  };
  const sendAgain = async () => { setErr(''); try { await devx.customSend(); } catch (e: any) { setErr(e?.message ?? 'Could not start the transfer.'); } };
  const remove = () => Alert.alert('Remove the custom design?', 'It is deleted from this phone and from the display. The display keeps all its other screens.', [
    { text: 'Cancel', style: 'cancel' },
    { text: 'Remove', style: 'destructive', onPress: async () => { try { await devx.customRemove(); setSavedUri(''); setSavedHtml(''); setDraft(null); await loadSaved(false); } catch (e: any) { setErr(e?.message ?? 'Failed'); } } },
  ]);
  const showScripts = (v: boolean) => {
    if (!v) { setScripts(false); return; }
    Alert.alert('Allow scripts?', 'JavaScript in your file will run in a sealed renderer with no network, no storage and no access to Meow Island. Only use this for files you trust (for example a QR-code library you embedded yourself).', [
      { text: 'Cancel', style: 'cancel' }, { text: 'Allow', onPress: () => setScripts(true) }]);
  };

  const pw = Math.min(width - 64, 240); const ph = Math.round((pw * DEVICE_H) / DEVICE_W);
  const Preview = ({ uri, label }: { uri: string; label: string }) => (
    <View style={{ alignItems: 'center', marginVertical: 10 }}>
      <View style={{ padding: 8, borderRadius: 18, backgroundColor: '#000', borderWidth: 1, borderColor: C.card2 }}>
        <Image source={{ uri }} style={{ width: pw, height: ph, borderRadius: 6 }} accessibilityLabel={label} />
      </View>
      <Sub>{DEVICE_W} × {DEVICE_H} px, the shape of the display</Sub>
    </View>
  );

  if (!deviceExtrasAvailable) return <Card><H>Needs the latest build</H><Sub>This APK was built before the custom screen was added. Rebuild the app (see the README).</Sub><View style={{ height: 8 }} /><Btn title="Back" kind="ghost" onPress={back} /></Card>;

  const onDevice = !!info?.hasSaved && info.deviceCrc === info.crc && info.crc !== '0';
  const sending = info?.state === 'sending';
  const pct = info && info.total > 0 ? Math.min(100, Math.round((info.sent * 100) / info.total)) : 0;

  return (
    <View>
      <Pressable accessibilityRole="button" onPress={back} style={{ minHeight: 44, justifyContent: 'center' }}><Text style={{ color: C.accent, fontWeight: '700' }}>‹ Device</Text></Pressable>

      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Custom Screen</H><Chip ok={d.ready} label={d.ready ? 'Display connected' : 'Display not connected'} /></View>
        <Sub>Make anything with an HTML file: your name, a QR code, a logo, a message. It becomes its own screen on the display, separate from the clock. HTML files only for now (no PNG or JPG).</Sub>
        <Sub>Design for {DEVICE_W} × {DEVICE_H} CSS pixels. Use inline CSS and simple shapes: flat colours compress best and photos or gradients may be refused. Files up to {MAX_HTML_BYTES / 1024} KB; after conversion the picture must fit in {MAX_IMAGE_BYTES / 1024} KB. Images must be embedded in the file (data: URLs): remote images, fonts and scripts are not loaded because rendering is fully offline.</Sub>
        <View style={{ height: 8 }} />
        <Btn title={busy === 'picking' ? 'Opening…' : busy === 'rendering' ? 'Rendering…' : info?.hasSaved || draft ? 'Choose a different HTML file' : 'Choose an HTML file'} disabled={busy !== ''} onPress={choose} />
        <Toggle label="Allow scripts (advanced)" hint="Off by default. Needed only if your design draws itself with JavaScript, such as an embedded QR library." value={scripts} onChange={showScripts} />
      </Card>

      {!!err && <ErrorBox text={err} />}
      {!!note && <Sub>{note}</Sub>}

      {draft && (
        <Card>
          <H>Preview</H>
          <Sub>{draft.name}</Sub>
          <Preview uri={draft.uri} label="Preview of the custom screen as the display will show it" />
          <Sub>{sizeLabel(draft.bytes.length)} to send · {draft.colors} colours{draft.reduced ? ' (reduced from a richer picture; fine detail in gradients may band)' : ''} · about {transferEstimateSeconds(draft.bytes.length)} s over Bluetooth.</Sub>
          <View style={{ height: 8 }} />
          <Btn title={busy === 'saving' ? 'Saving…' : info?.hasSaved ? 'Replace and send to display' : d.ready ? 'Save and send to display' : 'Save (send when connected)'} disabled={busy !== '' || sending} onPress={saveAndSend} />
          <View style={{ height: 6 }} />
          <Btn kind="ghost" title="Render again" disabled={busy !== ''} onPress={() => build(draft.html, draft.name)} />
        </Card>
      )}

      {info?.hasSaved && (
        <Card>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Saved design</H><Chip ok={onDevice} label={onDevice ? 'On the display' : sending ? 'Sending…' : 'Not on the display'} /></View>
          <Sub>{info.name || 'Custom design'} · {sizeLabel(info.size)}. Stored privately on this phone and on the display; it survives restarts of both.</Sub>
          {!!savedUri && <Preview uri={savedUri} label="The saved custom screen" />}
          {sending && (
            <View accessibilityRole="progressbar" accessibilityValue={{ min: 0, max: 100, now: pct }}>
              <View style={{ height: 8, borderRadius: 4, backgroundColor: C.card2, overflow: 'hidden' }}><View style={{ width: `${pct}%`, height: 8, backgroundColor: C.accent }} /></View>
              <Sub>Sending {pct}% ({sizeLabel(info.sent)} of {sizeLabel(info.total)}). The previous design stays visible until the new one is complete.</Sub>
              <View style={{ marginTop: 6, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Cancel" onPress={() => devx.customCancel()} /></View>
            </View>
          )}
          {info.state === 'error' && !!info.message && <ErrorBox text={info.message + (info.hasSaved ? ' The design on the display, if any, has not been changed.' : '')} />}
          {info.state === 'done' && onDevice && <Sub>Sent and checked: the display confirmed the checksum.</Sub>}
          {!info.deviceHasExt && info.connected && <ErrorBox text="The display firmware does not support custom screens yet. Flash the updated firmware from the firmware folder." />}
          <View style={{ marginTop: 8, flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
            <Btn small title={onDevice ? 'Send again' : 'Send to display'} disabled={!d.ready || sending} onPress={sendAgain} />
            <Btn small kind="ghost" title="Show on display" disabled={!d.ready} onPress={() => dev.setScreen(5).catch((e: any) => setErr(e?.message ?? 'Not connected'))} />
            <Btn small kind="ghost" title="Back to clock" disabled={!d.ready} onPress={() => dev.setScreen(0).catch((e: any) => setErr(e?.message ?? 'Not connected'))} />
            {!!savedHtml && <Btn small kind="ghost" title="Preview again" disabled={busy !== ''} onPress={() => build(savedHtml, info.name || 'Custom design')} />}
            <Btn small kind="danger" title="Remove" onPress={remove} />
          </View>
          <Toggle label="Re-send after reconnecting" hint="If the display does not have this exact design (for example after it was replaced), it is sent again automatically. A design the display already has is never rewritten." value={info.auto} onChange={(v) => { setInfo({ ...info, auto: v }); devx.customSetAuto(v).catch(() => undefined); }} />
        </Card>
      )}

      <Card>
        <H>How it works and its limits</H>
        <Sub>Your file is read on this phone and drawn by Android's web renderer in a sealed, offline view with no access to the app, files or the network. The picture is reduced to at most 256 colours, compressed, checked with a checksum and sent in small pieces over the existing Bluetooth connection. Nothing goes to a server.</Sub>
        <Sub>The display can only draw plain pictures, so the design is a snapshot: animations, hover effects and live content are not supported. If a transfer fails or the connection drops, the previous design stays on the display. The BOOT button skips the custom screen until a design is stored.</Sub>
      </Card>
    </View>
  );
}
