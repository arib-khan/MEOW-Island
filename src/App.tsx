import React, { useEffect, useRef, useState } from 'react';
import { Animated, Pressable, ScrollView, StatusBar, Text, View } from 'react-native';
import * as Notifications from 'expo-notifications';
import * as Battery from 'expo-battery';
import { available, nativeCurrent, native, dev } from './native';
import { initDb } from './core/db';
import { setupReminderActions, snooze } from './core/reminders';
import { ConfigProvider } from './config';
import { HealthProvider } from './health';
import { C, CatLogo, ErrorBox, H, Loading, Sub, useReduceMotion } from './ui';
import Home from './screens/Home';
import Reminders from './screens/Reminders';
import ScreenTime from './screens/ScreenTime';
import Health from './screens/Health';
import Wellbeing from './screens/Wellbeing';
import Device from './screens/Device';
import CustomScreen from './screens/CustomScreen';
import Settings from './screens/Settings';
import { WellbeingProvider } from './wellbeing';

class Boundary extends React.Component<{ children: React.ReactNode }, { err?: Error }> {
  state: { err?: Error } = {};
  static getDerivedStateFromError(err: Error) { return { err }; }
  render() {
    return this.state.err
      ? <ScrollView style={{ flex: 1, backgroundColor: C.bg, padding: 20, paddingTop: 60 }}><H>Something went wrong</H><ErrorBox text={String(this.state.err.stack ?? this.state.err.message)} /></ScrollView>
      : this.props.children;
  }
}

const TABS = [{ k: 'Home', i: '🐾' }, { k: 'Reminders', i: '⏰' }, { k: 'Screen Time', i: '⏳' }, { k: 'Health', i: '🚶' }, { k: 'Wellbeing', i: '🛡️' }, { k: 'Settings', i: '⚙️' }];

function Shell() {
  const [tab, setTab] = useState(0);
  const fade = useRef(new Animated.Value(1)).current;
  const reduce = useReduceMotion();
  const go = (i: number) => {
    if (i === tab) return;
    if (reduce) { setTab(i); return; }
    Animated.timing(fade, { toValue: 0, duration: 90, useNativeDriver: true }).start(() => { setTab(i); Animated.timing(fade, { toValue: 1, duration: 160, useNativeDriver: true }).start(); });
  };

  useEffect(() => {
    dev.start();                      // reconnect a paired Meow Island display (does nothing if none is paired)
    const rec = Notifications.addNotificationReceivedListener((n) => native.postActivity('reminder', 90, '⏰ ' + (n.request.content.body ?? 'Reminder'), 'Reminder', 15000));
    const resp = Notifications.addNotificationResponseReceivedListener((r) => { if (r.actionIdentifier === 'snooze') snooze(r.notification.request.content.body ?? 'Reminder'); });
    Battery.getBatteryLevelAsync().then((l) => { if (l > 0 && l < 0.15) native.postActivity('battery', 40, `🔋 ${Math.round(l * 100)}%`, 'Low battery', 6000); }).catch(() => {});
    return () => { rec.remove(); resp.remove(); };
  }, []);

  return (
    <View style={{ flex: 1, backgroundColor: C.bg, paddingTop: StatusBar.currentHeight ?? 24 }}>
      <StatusBar barStyle="light-content" backgroundColor={C.bg} />
      <View style={{ paddingHorizontal: 20, paddingVertical: 12, flexDirection: 'row', alignItems: 'center', gap: 12 }}><CatLogo /><View><Text style={{ color: C.text, fontSize: 24, fontWeight: '900' }}>Meow Island</Text><Sub>Your pocket-sized companion</Sub></View></View>
      <Animated.View style={{ flex: 1, opacity: fade }}>
        <ScrollView contentContainerStyle={{ padding: 16, paddingBottom: 32 }} keyboardShouldPersistTaps="handled">
          {tab === 0 && <Home go={go} />}
          {tab === 1 && <Reminders />}
          {tab === 2 && <ScreenTime />}
          {tab === 3 && <Health />}
          {tab === 4 && <Wellbeing />}
          {tab === 5 && <Settings openDevice={() => go(6)} />}
          {tab === 6 && <Device back={() => go(5)} openCustom={() => go(7)} />}
          {tab === 7 && <CustomScreen back={() => go(6)} />}
        </ScrollView>
      </Animated.View>
      <View style={{ flexDirection: 'row', backgroundColor: C.card, borderTopWidth: 1, borderTopColor: C.line, paddingBottom: 8 }}>
        {TABS.map((t, i) => (
          <Pressable key={t.k} accessibilityRole="tab" accessibilityState={{ selected: tab === i }} accessibilityLabel={t.k} onPress={() => go(i)} style={{ flex: 1, minHeight: 56, alignItems: 'center', justifyContent: 'center' }}>
            {tab === i && <View style={{ position: 'absolute', top: 0, width: 28, height: 3, borderRadius: 2, backgroundColor: C.accent2 }} />}
            <Text style={{ fontSize: 20, opacity: tab === i ? 1 : 0.5 }}>{t.i}</Text>
            <Text style={{ fontSize: 11, color: tab === i ? C.accent : C.sub, fontWeight: tab === i ? '700' : '400' }}>{t.k}</Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

function Root() {
  const [ready, setReady] = useState(false);
  const [err, setErr] = useState('');
  useEffect(() => {
    (async () => {
      try {
        await initDb();
        await setupReminderActions();
        Notifications.setNotificationHandler({ handleNotification: async () => ({ shouldShowAlert: true, shouldPlaySound: true, shouldSetBadge: false }) });
        setReady(true);
      } catch (e: any) { setErr(String(e?.stack ?? e)); }
    })();
  }, []);
  if (err) return <ScrollView style={{ flex: 1, backgroundColor: C.bg, padding: 20, paddingTop: 60 }}><H>Startup failed</H><ErrorBox text={err} /></ScrollView>;
  if (!ready) return <View style={{ flex: 1, backgroundColor: C.bg, justifyContent: 'center' }}><Loading label="Starting…" /></View>;
  return <ConfigProvider><HealthProvider><WellbeingProvider><Shell /></WellbeingProvider></HealthProvider></ConfigProvider>;
}

export default function App() {
  return (
    <Boundary>
      {available && nativeCurrent ? <Root /> : (
        <ScrollView style={{ flex: 1, backgroundColor: C.bg, padding: 20, paddingTop: 60 }}>
          <H>{available ? 'Outdated native build' : 'Native module missing'}</H>
          <Sub>{'This APK was built without the current native code. The previous "npx expo prebuild --platform android --clean" probably failed (EBUSY: a folder was locked). Stop Gradle, close terminals/Explorer windows inside the android folder, run prebuild again from the project root, confirm it prints "Finished prebuild" with no error, then rebuild.'}</Sub>
        </ScrollView>
      )}
    </Boundary>
  );
}
