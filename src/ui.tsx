import React, { useEffect, useState } from 'react';
import { View, Text, Pressable, StyleSheet, Switch, ActivityIndicator, AccessibilityInfo, StyleProp, ViewStyle } from 'react-native';

export const C = { bg: '#0E0B16', card: '#181325', card2: '#221B33', text: '#F4F0FA', sub: '#A79DBA', accent: '#B79CFF', accent2: '#FF9EC7', onAccent: '#1A1128', ok: '#5BD68A', bad: '#FF6B81', warn: '#FFB86B', line: '#2D2540' };
type Ch = { children: React.ReactNode };

export const Card = ({ children, style }: Ch & { style?: StyleProp<ViewStyle> }) => <View style={[s.card, style]}>{children}</View>;
export const H = ({ children }: Ch) => <Text accessibilityRole="header" style={s.h}>{children}</Text>;
export const Sub = ({ children }: Ch) => <Text style={s.sub}>{children}</Text>;
export const Body = ({ children }: Ch) => <Text style={s.body}>{children}</Text>;

export function Btn({ title, onPress, kind = 'primary', disabled, small }: { title: string; onPress: () => void; kind?: 'primary' | 'ghost' | 'danger'; disabled?: boolean; small?: boolean }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={title} disabled={disabled} onPress={onPress}
      style={({ pressed }) => [s.btn, small && { minHeight: 36, paddingHorizontal: 12 }, kind === 'ghost' && s.ghost, kind === 'danger' && { backgroundColor: C.bad }, (pressed || disabled) && { opacity: 0.55 }]}>
      <Text style={[s.btnT, kind === 'ghost' && { color: C.accent }, kind === 'danger' && { color: '#2A0A12' }]}>{title}</Text>
    </Pressable>
  );
}

export const Chip = ({ ok, label }: { ok: boolean; label: string }) => (
  <View style={[s.chip, { backgroundColor: ok ? '#173325' : '#3A1A24' }]}><Text style={{ color: ok ? C.ok : C.bad, fontSize: 12, fontWeight: '700' }}>{label}</Text></View>
);

export function Toggle({ label, hint, value, onChange }: { label: string; hint?: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <View style={s.row}>
      <View style={{ flex: 1, paddingRight: 12 }}><Text style={s.body}>{label}</Text>{!!hint && <Text style={s.sub}>{hint}</Text>}</View>
      <Switch accessibilityLabel={label} value={value} onValueChange={onChange} trackColor={{ true: C.accent, false: C.line }} thumbColor="#fff" />
    </View>
  );
}

export function Stepper({ label, value, min, max, step, unit = '', onChange }: { label: string; value: number; min: number; max: number; step: number; unit?: string; onChange: (v: number) => void }) {
  const set = (v: number) => onChange(Math.max(min, Math.min(max, v)));
  return (
    <View style={s.row}>
      <Text style={[s.body, { flex: 1 }]}>{label}</Text>
      <Pressable accessibilityLabel={`Decrease ${label}`} onPress={() => set(value - step)} style={s.step}><Text style={s.stepT}>−</Text></Pressable>
      <Text style={[s.body, { minWidth: 64, textAlign: 'center' }]}>{value}{unit}</Text>
      <Pressable accessibilityLabel={`Increase ${label}`} onPress={() => set(value + step)} style={s.step}><Text style={s.stepT}>+</Text></Pressable>
    </View>
  );
}

export const Empty = ({ icon, title, hint }: { icon: string; title: string; hint?: string }) => (
  <View style={{ alignItems: 'center', padding: 28 }}><Text style={{ fontSize: 40 }}>{icon}</Text><Text style={[s.body, { marginTop: 8, fontWeight: '700' }]}>{title}</Text>{!!hint && <Text style={[s.sub, { textAlign: 'center', marginTop: 4 }]}>{hint}</Text>}</View>
);
export const Loading = ({ label }: { label?: string }) => (
  <View style={{ alignItems: 'center', padding: 28 }}><ActivityIndicator color={C.accent} />{!!label && <Text style={[s.sub, { marginTop: 8 }]}>{label}</Text>}</View>
);
export const ErrorBox = ({ text }: { text: string }) => <View style={s.err}><Text style={{ color: C.bad }} selectable>{text}</Text></View>;

export const s = StyleSheet.create({
  card: { backgroundColor: C.card, borderRadius: 22, padding: 16, marginBottom: 14, borderWidth: 1, borderColor: C.line },
  h: { color: C.text, fontSize: 20, fontWeight: '800', marginBottom: 8 },
  sub: { color: C.sub, fontSize: 13, lineHeight: 18 },
  body: { color: C.text, fontSize: 15, lineHeight: 21 },
  btn: { backgroundColor: C.accent, minHeight: 44, borderRadius: 14, paddingHorizontal: 18, alignItems: 'center', justifyContent: 'center' },
  ghost: { backgroundColor: 'transparent', borderWidth: 1, borderColor: C.accent },
  btnT: { color: C.onAccent, fontWeight: '800', fontSize: 15 },
  chip: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 999 },
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 48, marginVertical: 2 },
  step: { width: 40, height: 40, borderRadius: 12, backgroundColor: C.card2, alignItems: 'center', justifyContent: 'center' },
  stepT: { color: C.text, fontSize: 20, fontWeight: '700' },
  err: { backgroundColor: '#3A1A24', borderRadius: 12, padding: 12, marginBottom: 12 },
});

/** Honours the Android "remove animations" accessibility setting. */
export function useReduceMotion() {
  const [r, setR] = useState(false);
  useEffect(() => {
    AccessibilityInfo.isReduceMotionEnabled().then(setR).catch(() => {});
    const sub = AccessibilityInfo.addEventListener('reduceMotionChanged', setR);
    return () => sub.remove();
  }, []);
  return r;
}

/** Small cat head built from plain Views (no image/icon-font dependency). */
export function CatLogo({ size = 38 }: { size?: number }) {
  const ear = size * 0.36;
  const dot = (left: number) => <View style={{ position: 'absolute', top: size * 0.36, left, width: size * 0.12, height: size * 0.17, borderRadius: size * 0.07, backgroundColor: C.onAccent }} />;
  return (
    <View accessibilityLabel="Meow Island logo" style={{ width: size, height: size }}>
      <View style={{ position: 'absolute', left: size * 0.06, top: size * 0.04, width: ear, height: ear, backgroundColor: C.accent, borderRadius: 4, transform: [{ rotate: '45deg' }] }} />
      <View style={{ position: 'absolute', right: size * 0.06, top: size * 0.04, width: ear, height: ear, backgroundColor: C.accent, borderRadius: 4, transform: [{ rotate: '45deg' }] }} />
      <View style={{ position: 'absolute', top: size * 0.16, width: size, height: size * 0.84, borderRadius: size * 0.42, backgroundColor: C.accent }}>
        <View style={{ position: 'absolute', top: size * 0.1, left: size * 0.34, width: size * 0.32, height: size * 0.07, borderRadius: size * 0.04, backgroundColor: C.onAccent }} />
        {dot(size * 0.24)}{dot(size * 0.64)}
        <View style={{ position: 'absolute', top: size * 0.55, left: size * 0.45, width: size * 0.1, height: size * 0.07, borderRadius: size * 0.03, backgroundColor: C.accent2 }} />
      </View>
    </View>
  );
}
