/**
 * Pure helpers for the digital-wellbeing features: settings (validated on every read, hostile or old data is clamped, never
 * trusted), milestone tiers, daily history and the adult-filter lists. The same JSON shape is parsed again by the native side
 * (WellbeingStore.kt), which is the source of truth, so this file only normalises what the UI shows and sends.
 */
export type Tier = { min: number; emoji: string; text: string };
export const DEFAULT_TIERS: Tier[] = [
  { min: 1, emoji: '😺', text: 'Enjoying yourself' },
  { min: 10, emoji: '😼', text: 'Getting hooked' },
  { min: 25, emoji: '😿', text: 'Maybe take a break' },
  { min: 50, emoji: '🙀', text: "That's a lot!" },
  { min: 100, emoji: '😾', text: 'Time to log off?' },
];

export type ShortsConfig = {
  counter: { enabled: boolean; ig: boolean; yt: boolean; mode: 'app' | 'total'; fx: number; y: number; scale: number; opacity: number; bg: string; fg: string; text: 'milestone' | 'always' | 'never'; hideLandscape: boolean; tiers: Tier[] };
  block: { ig: boolean; yt: boolean; strict: boolean; autoLeave: boolean; pauseMin: number };
  goal: { daily: number; remindEvery: number };
};

export const DEFAULT_SHORTS: ShortsConfig = {
  counter: { enabled: true, ig: true, yt: true, mode: 'app', fx: 0.5, y: 16, scale: 1, opacity: 0.92, bg: '#000000', fg: '#FFFFFF', text: 'milestone', hideLandscape: true, tiers: DEFAULT_TIERS },
  block: { ig: false, yt: false, strict: false, autoLeave: true, pauseMin: 5 },
  goal: { daily: 0, remindEvery: 10 },
};

const num = (v: any, lo: number, hi: number, d: number) => (typeof v === 'number' && isFinite(v) ? Math.min(hi, Math.max(lo, v)) : d);
const int = (v: any, lo: number, hi: number, d: number) => Math.round(num(v, lo, hi, d));
const bool = (v: any, d: boolean) => (typeof v === 'boolean' ? v : d);
const hex = (v: any, d: string) => (typeof v === 'string' && /^#[0-9a-fA-F]{6}$/.test(v) ? v : d);
const pick = <T extends string>(v: any, ok: readonly T[], d: T): T => (ok.includes(v) ? v : d);

/** Sorted by threshold, thresholds >= 1 and unique, text/emoji length-limited; falls back to the defaults when nothing usable is left. */
export function sanitizeTiers(raw: any): Tier[] {
  if (!Array.isArray(raw)) return DEFAULT_TIERS.map((t) => ({ ...t }));
  const seen = new Set<number>(); const out: Tier[] = [];
  for (const t of raw.slice(0, 12)) {
    if (!t || typeof t !== 'object') continue;
    if (typeof t.min !== 'number' || !isFinite(t.min) || t.min < 1 || t.min > 9999) continue;          // reject, don't clamp: a bad threshold is dropped
    const min = Math.round(t.min); if (seen.has(min)) continue;
    seen.add(min);
    out.push({ min, emoji: typeof t.emoji === 'string' && t.emoji.trim() ? [...t.emoji.trim()].slice(0, 4).join('') : '🐱', text: typeof t.text === 'string' ? t.text.slice(0, 40) : '' });
  }
  return out.length ? out.sort((a, b) => a.min - b.min) : DEFAULT_TIERS.map((t) => ({ ...t }));
}

export function mergeShorts(raw: any): ShortsConfig {
  const r = raw && typeof raw === 'object' ? raw : {};
  const c = r.counter && typeof r.counter === 'object' ? r.counter : {}; const b = r.block && typeof r.block === 'object' ? r.block : {}; const g = r.goal && typeof r.goal === 'object' ? r.goal : {};
  const d = DEFAULT_SHORTS;
  return {
    counter: {
      enabled: bool(c.enabled, d.counter.enabled), ig: bool(c.ig, d.counter.ig), yt: bool(c.yt, d.counter.yt), mode: pick(c.mode, ['app', 'total'] as const, 'app'),
      fx: num(c.fx, 0, 1, d.counter.fx), y: int(c.y, 0, 400, d.counter.y), scale: Math.round(num(c.scale, 0.7, 1.6, 1) * 10) / 10, opacity: Math.round(num(c.opacity, 0.3, 1, d.counter.opacity) * 100) / 100,
      bg: hex(c.bg, d.counter.bg), fg: hex(c.fg, d.counter.fg), text: pick(c.text, ['milestone', 'always', 'never'] as const, 'milestone'), hideLandscape: bool(c.hideLandscape, true), tiers: sanitizeTiers(c.tiers),
    },
    block: { ig: bool(b.ig, false), yt: bool(b.yt, false), strict: bool(b.strict, false), autoLeave: bool(b.autoLeave, true), pauseMin: int(b.pauseMin, 1, 60, 5) },
    goal: { daily: int(g.daily, 0, 10000, 0), remindEvery: int(g.remindEvery, 0, 1000, 10) },
  };
}

/** Tier shown for a count (null for 0, or when every threshold is higher than the count). */
export function pickTier(count: number, tiers: Tier[]): Tier | null {
  let best: Tier | null = null;
  for (const t of tiers) if (t.min <= count && (!best || t.min > best.min)) best = t;
  return best;
}

// ---------------------------------------------------------------- history
export type DayRec = { ig?: number; yt?: number; igA?: number; ytA?: number };
export type Row = { day: string; ig: number; yt: number; total: number; approx: boolean };
const p2 = (n: number) => String(n).padStart(2, '0');
/** Local calendar day, identical to the native ShortsPolicy.dayKey. */
export const dayKey = (ms: number) => { const d = new Date(ms); return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`; };

export function parseDays(json: string | null | undefined): Record<string, DayRec> {
  try { const o = JSON.parse(json || '{}'); return o && typeof o === 'object' && !Array.isArray(o) ? o : {}; } catch { return {}; }
}

/** The last [n] days, oldest first, missing days as zeros (so a month boundary or a day without use still shows). */
export function lastDays(days: Record<string, DayRec>, n: number, now: number): Row[] {
  const rows: Row[] = [];
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(now); d.setHours(12, 0, 0, 0); d.setDate(d.getDate() - i);     // noon: immune to DST shifts
    const k = dayKey(d.getTime()); const r = days[k] || {};
    const ig = Math.max(0, Math.floor(Number(r.ig) || 0)), yt = Math.max(0, Math.floor(Number(r.yt) || 0));
    rows.push({ day: k, ig, yt, total: ig + yt, approx: (Number(r.igA) || 0) > 0 || (Number(r.ytA) || 0) > 0 });
  }
  return rows;
}

export function summarize(rows: Row[]) {
  const used = rows.filter((r) => r.total > 0);
  const sum = rows.reduce((a, r) => a + r.total, 0);
  return { sum, avg: rows.length ? Math.round(sum / rows.length) : 0, best: used.length ? Math.max(...used.map((r) => r.total)) : 0, activeDays: used.length };
}

export type GoalState = 'off' | 'under' | 'near' | 'reached' | 'over';
export function goalState(total: number, goal: number): GoalState {
  if (!goal || goal <= 0) return 'off';
  if (total > goal) return 'over'; if (total === goal) return 'reached'; if (total >= goal * 0.8) return 'near'; return 'under';
}

// ---------------------------------------------------------------- adult website filter
export const DEFAULT_BLOCKLIST_URL = 'https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts';
export type AdultConfig = { enabled: boolean; upstream: 'system' | 'cloudflare' | 'custom'; customDns: string; allow: string[]; block: string[]; blocklistUrl: string; notify: boolean };
export const DEFAULT_ADULT: AdultConfig = { enabled: false, upstream: 'system', customDns: '', allow: [], block: [], blocklistUrl: DEFAULT_BLOCKLIST_URL, notify: true };

/** A hostname the filter can match, or null. Mirrors DomainBlocklist.normalize in Kotlin. */
export function normalizeDomain(raw: any): string | null {
  if (typeof raw !== 'string') return null;
  let s = raw.trim().toLowerCase().replace(/^https?:\/\//, '').split(/[/?#]/)[0].replace(/\.+$/, '');
  if (s.startsWith('*.')) s = s.slice(2);
  if (!s || s.length > 253 || !s.includes('.') || /^[\d.]+$/.test(s)) return null;
  return s.split('.').every((l) => l.length > 0 && l.length <= 63 && /^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?$/.test(l)) ? s : null;
}
export const cleanDomains = (raw: any): string[] => Array.isArray(raw) ? [...new Set(raw.map(normalizeDomain).filter((x): x is string => !!x))].slice(0, 300) : [];
export const isHttps = (u: any) => typeof u === 'string' && /^https:\/\/[^\s/]+\.[^\s/]+\/\S*$/.test(u) && u.length < 400;
export const isIp = (v: any) => typeof v === 'string' && (/^(\d{1,3}\.){3}\d{1,3}$/.test(v) ? v.split('.').every((x) => +x <= 255) : /^[0-9a-fA-F:]+$/.test(v) && v.includes(':'));

export function mergeAdult(raw: any): AdultConfig {
  const r = raw && typeof raw === 'object' ? raw : {};
  return {
    enabled: bool(r.enabled, false), upstream: pick(r.upstream, ['system', 'cloudflare', 'custom'] as const, 'system'),
    customDns: isIp(r.customDns) ? r.customDns : '', allow: cleanDomains(r.allow), block: cleanDomains(r.block),
    blocklistUrl: isHttps(r.blocklistUrl) ? r.blocklistUrl : DEFAULT_BLOCKLIST_URL, notify: bool(r.notify, true),
  };
}
