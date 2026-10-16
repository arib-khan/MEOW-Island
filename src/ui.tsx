import React from 'react';
import { View, Text, Pressable, StyleSheet, Switch, ActivityIndicator, StyleProp, ViewStyle } from 'react-native';

export const C = { bg: '#0B0B10', card: '#16161E', card2: '#1F1F2A', text: '#F5F5F8', sub: '#A0A0B2', accent: '#7C5CFF', ok: '#34C759', bad: '#FF453A', warn: '#FF9F0A', line: '#2A2A38' };
type Ch = { children: React.ReactNode };

export const Card = ({ children, style }: Ch & { style?: StyleProp<ViewStyle> }) => <View style={[s.card, style]}>{children}</View>;
export const H = ({ children }: Ch) => <Text accessibilityRole="header" style={s.h}>{children}</Text>;
export const Sub = ({ children }: Ch) => <Text style={s.sub}>{children}</Text>;
export const Body = ({ children }: Ch) => <Text style={s.body}>{children}</Text>;

export function Btn({ title, onPress, kind = 'primary', disabled, small }: { title: string; onPress: () => void; kind?: 'primary' | 'ghost' | 'danger'; disabled?: boolean; small?: boolean }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={title} disabled={disabled} onPress={onPress}
      style={({ pressed }) => [s.btn, small && { minHeight: 36, paddingHorizontal: 12 }, kind === 'ghost' && s.ghost, kind === 'danger' && { backgroundColor: C.bad }, (pressed || disabled) && { opacity: 0.55 }]}>
      <Text style={[s.btnT, kind === 'ghost' && { color: C.accent }]}>{title}</Text>
    </Pressable>
  );
}

export const Chip = ({ ok, label }: { ok: boolean; label: string }) => (
  <View style={[s.chip, { backgroundColor: ok ? '#1E3A2A' : '#3D1F1F' }]}><Text style={{ color: ok ? C.ok : C.bad, fontSize: 12, fontWeight: '700' }}>{label}</Text></View>
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
  card: { backgroundColor: C.card, borderRadius: 18, padding: 16, marginBottom: 14 },
  h: { color: C.text, fontSize: 20, fontWeight: '800', marginBottom: 8 },
  sub: { color: C.sub, fontSize: 13, lineHeight: 18 },
  body: { color: C.text, fontSize: 15, lineHeight: 21 },
  btn: { backgroundColor: C.accent, minHeight: 44, borderRadius: 14, paddingHorizontal: 18, alignItems: 'center', justifyContent: 'center' },
  ghost: { backgroundColor: 'transparent', borderWidth: 1, borderColor: C.accent },
  btnT: { color: '#fff', fontWeight: '700', fontSize: 15 },
  chip: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 999 },
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 48, marginVertical: 2 },
  step: { width: 40, height: 40, borderRadius: 12, backgroundColor: C.card2, alignItems: 'center', justifyContent: 'center' },
  stepT: { color: C.text, fontSize: 20, fontWeight: '700' },
  err: { backgroundColor: '#3D1F1F', borderRadius: 12, padding: 12, marginBottom: 12 },
});
