import React, { useCallback, useEffect, useState } from 'react';
import { AppState, Alert, View } from 'react-native';
import { Btn, Card, Chip, ErrorBox, H, Sub, Toggle } from '../ui';
import { devx, deviceExtrasAvailable, native } from '../native';

/** Device screen cards for the notification display, the Spotify lyrics source and the entry to the photo gallery. */
/** Plain-language meaning of the lyric state the phone reports (DeviceProtocol LY_*). */
function lyricStatus(state: number): string {
  switch (state) {
    case 1: return 'Looking up lyrics…';
    case 2: return 'Synced lyrics found for this song.';
    case 3: return 'No lyrics found for this song.';
    case 4: return 'Lyrics are off.';
    case 5: return 'The lyrics service could not be reached.';
    case 6: return 'Only plain lyrics exist for this song (not time-synced), so none are shown.';
    default: return 'Plays a Spotify song to look up its lyrics.';
  }
}

export function DeviceExtrasCards({ ready, openGallery }: { ready: boolean; openGallery: () => void }) {
  const [n, setN] = useState<{ enabled: boolean; access: boolean; count: number; preview: boolean } | null>(null);
  const [ly, setLy] = useState<'off' | 'lrclib'>('off');
  const [lyInfo, setLyInfo] = useState<{ state: number; detail: string }>({ state: 0, detail: '' });
  const [cap, setCap] = useState<{ enabled: boolean; service: boolean; liveCaption: number; lines: number } | null>(null);
  const [err, setErr] = useState('');

  const load = useCallback(async () => {
    if (!deviceExtrasAvailable) return;
    try { setN(await devx.notifsGet()); const l = await devx.lyricsGet(); if (l) { setLy(l.provider === 'lrclib' ? 'lrclib' : 'off'); setLyInfo({ state: l.state, detail: l.detail || '' }); } setCap(await devx.captionsGet()); setErr(''); } catch (e: any) { setErr(e?.message ?? 'Could not read the settings'); }
  }, []);
  useEffect(() => {
    load();
    const poll = setInterval(load, 4000);                      // lyrics / caption status changes while a song or video plays
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') load(); });   // coming back from Android's notification-access screen
    return () => { sub.remove(); clearInterval(poll); };
  }, [load]);

  if (!deviceExtrasAvailable) return <Card><H>New display screens</H><Sub>This APK was built before the new display screens were added. Rebuild the app (see the README) to use them.</Sub></Card>;

  const toggleCaptions = async (on: boolean) => { try { await devx.captionsSetEnabled(on); await load(); } catch (e: any) { setErr(e?.message ?? 'Failed'); } };
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
        <Sub>The Music screen shows album art, title, artist and progress from the player on this phone. Synced lyrics need an outside source: Android does not provide lyrics and Spotify has no public lyrics service, so none are scraped. You can opt in to LRCLIB (below); with it off the display says "Lyrics are off". The line under the switch tells you what the last lookup answered.</Sub>
        <Toggle label="Look up synced lyrics for Spotify (LRCLIB)" value={ly === 'lrclib'} onChange={setLyrics} />
        {ly === 'lrclib' && <Sub>{lyricStatus(lyInfo.state)}{lyInfo.detail ? ` (${lyInfo.detail})` : ''}</Sub>}
        <View style={{ height: 6 }} />
        <H>Captions for YouTube, Brave and other players</H>
        <Sub>Android gives apps no access to the subtitles of other apps, but it can turn any playing audio into text itself: Live Caption. With the switch below, Meow Island reads the text of that Live Caption window and shows the newest lines on the display's Music screen. It is speech recognised by Android, not the video's own subtitle track, so it can differ from the real subtitles and lags a little. It only works on phones that have Live Caption (Pixel and many Android 10+ phones).</Sub>
        <Toggle label="Show Live Caption text on the display" value={!!cap?.enabled} onChange={toggleCaptions} />
        {!!cap?.enabled && (
          <View style={{ marginTop: 6 }}>
            <Sub>{cap.service ? '✓ Caption access is on.' : '1. Allow caption access: open Accessibility settings, choose Meow Island captions and switch it on. It reads only Live Caption\u2019s window, ignores calls, and keeps nothing on the phone.'}</Sub>
            {!cap.service && <View style={{ marginTop: 6, alignSelf: 'flex-start' }}><Btn title="Open Accessibility settings" onPress={() => devx.captionsOpenAccessibility()} /></View>}
            <Sub>{cap.liveCaption === 1 ? '✓ Live Caption is on.' : cap.liveCaption === 0 ? '2. Live Caption is off: press a volume button, tap the captions icon under the volume slider (or Settings › Sound › Live Caption) and switch it on.' : '2. Make sure Live Caption is on: press a volume button and tap the captions icon, or Settings › Sound › Live Caption. (This phone does not report its state.)'}</Sub>
            {cap.service && cap.liveCaption !== 0 && <Sub>{cap.lines > 0 ? 'Receiving caption text now.' : 'Play a video: the text appears on the display after the first spoken words.'}</Sub>}
          </View>
        )}
      </Card>

      <Card>
        <H>Photo gallery</H>
        <Sub>Pick photos on this phone. They are compressed small and the display shows them full screen as a slideshow, with the time per photo you choose.</Sub>
        <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn title="Open Photo Gallery" onPress={openGallery} /></View>
        {!ready && <Sub>Connect to the display to send photos. You can add and arrange them now.</Sub>}
      </Card>
      {!!err && <ErrorBox text={err} />}
    </>
  );
}
