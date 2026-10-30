import React, { useCallback, useEffect, useState } from 'react';
import { AppState, Alert, View } from 'react-native';
import { Btn, Card, Chip, ErrorBox, H, Sub, Toggle } from '../ui';
import { devx, deviceExtrasAvailable, native } from '../native';

/** Device screen cards for the notification display, the Spotify lyrics source and the entry to the custom HTML screen. */
export function DeviceExtrasCards({ ready, openCustom }: { ready: boolean; openCustom: () => void }) {
  const [n, setN] = useState<{ enabled: boolean; access: boolean; count: number; preview: boolean } | null>(null);
  const [ly, setLy] = useState<'off' | 'lrclib'>('off');
  const [err, setErr] = useState('');

  const load = useCallback(async () => {
    if (!deviceExtrasAvailable) return;
    try { setN(await devx.notifsGet()); const l = await devx.lyricsGet(); if (l) setLy(l.provider === 'lrclib' ? 'lrclib' : 'off'); setErr(''); } catch (e: any) { setErr(e?.message ?? 'Could not read the settings'); }
  }, []);
  useEffect(() => {
    load();
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') load(); });   // coming back from Android's notification-access screen
    return () => sub.remove();
  }, [load]);

  if (!deviceExtrasAvailable) return <Card><H>New display screens</H><Sub>This APK was built before the new display screens were added. Rebuild the app (see the README) to use them.</Sub></Card>;

  const toggleNotifs = async (on: boolean) => { try { await devx.notifsSetEnabled(on); await load(); } catch (e: any) { setErr(e?.message ?? 'Failed'); } };
  const setLyrics = async (on: boolean) => {
    if (!on) { try { await devx.lyricsSetProvider('off'); setLy('off'); } catch (e: any) { setErr(e?.message ?? 'Failed'); } return; }
    Alert.alert('Turn on online lyrics?', 'For Spotify, the title, artist and length of the song that is playing are sent over HTTPS to lrclib.net, a free community lyrics database, to look up time-synced lyrics. Nothing else is sent. Many songs have no synced lyrics. Without this switch no lyrics are ever fetched.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Turn on', onPress: async () => { try { await devx.lyricsSetProvider('lrclib'); setLy('lrclib'); } catch (e: any) { setErr(e?.message ?? 'Failed'); } } },
    ]);
  };

  return (
    <>
      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Notifications on the display</H><Chip ok={!!n?.enabled && !!n?.access} label={!n?.enabled ? 'Off' : n.access ? 'On' : 'Needs access'} /></View>
        <Sub>Shows your newest notifications (app, title, a short preview) on the Notifications screen. They go straight from this phone to the display over Bluetooth; nothing is uploaded and nothing is saved on the display or on this phone's storage. The list is kept in memory only, holds the newest 20 for up to 6 hours, and clearing it never dismisses anything on the phone.</Sub>
        <Toggle label="Show notifications on the display" value={!!n?.enabled} onChange={toggleNotifs} />
        {!!n?.enabled && !n.access && (
          <View style={{ marginTop: 6 }}>
            <Sub>Android needs to allow Meow Island to read notifications. On the next screen switch on Meow Island.</Sub>
            <View style={{ height: 8 }} /><Btn title="Open Notification access settings" onPress={() => native.requestNotificationAccess()} />
          </View>
        )}
        {!!n?.enabled && n.access && <Sub>{n.preview ? 'Titles and previews are shown.' : 'Previews are off (Settings › notification previews): only the app name is shown.'} Notifications marked private by their app are never shown. {n.count} in the list now.</Sub>}
        {!!n?.enabled && <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Clear the display's list" onPress={() => devx.notifsClear().then(load).catch((e: any) => setErr(e?.message ?? 'Failed'))} /></View>}
      </Card>

      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Music: lyrics and captions</H><Chip ok={ly === 'lrclib'} label={ly === 'lrclib' ? 'Lyrics on' : 'Lyrics off'} /></View>
        <Sub>The Music screen shows album art, title, artist and progress from the player on this phone. Synced lyrics need an outside source: Android does not provide lyrics and Spotify has no public lyrics service, so none are scraped. You can opt in to LRCLIB (below); with it off the display says "Lyrics are off".</Sub>
        <Sub>Captions: Android does not hand out the captions of YouTube, Brave or other players, so for those apps the display says "Captions unavailable". Nothing is ever invented.</Sub>
        <Toggle label="Look up synced lyrics for Spotify (LRCLIB)" value={ly === 'lrclib'} onChange={setLyrics} />
      </Card>

      <Card>
        <H>Custom screen</H>
        <Sub>Design your own display with an HTML file (a name, a QR code, a logo, a message). It is rendered on this phone and stored on the display.</Sub>
        <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn title="Open Custom Screen" onPress={openCustom} /></View>
        {!ready && <Sub>Connect to the display to send a design. You can prepare one now.</Sub>}
      </Card>
      {!!err && <ErrorBox text={err} />}
    </>
  );
}
