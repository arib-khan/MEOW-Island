// Phone screen time as shown on the device: formatting, goal rules and validation of what the native side reports.
// (The numbers come from Android UsageStats via the existing UsageUtil in Kotlin. This is the PHONE'S screen time, not the display-on time of the ESP32.)

export const GOAL_STEP_MIN = 15;
export const GOAL_MAX_MIN = 16 * 60;
export const DEFAULT_GOAL_MIN = 4 * 60;

export interface ScreenTimeApp { name: string; min: number }
export interface ScreenTimeState {
  access: boolean;          // Usage Access granted
  ok: boolean;              // the query worked and returned data
  totalMin: number;
  goalMin: number;          // 0 = no goal
  apps: ScreenTimeApp[];
  day: string;              // local date the numbers belong to, yyyy-mm-dd
  error: string;
}
export const EMPTY_SCREEN_TIME: ScreenTimeState = { access: false, ok: false, totalMin: 0, goalMin: 0, apps: [], day: '', error: '' };

const clampInt = (v: unknown, lo: number, hi: number, d = 0): number => { const x = typeof v === 'number' && isFinite(v) ? Math.round(v) : d; return Math.max(lo, Math.min(hi, x)); };

/** Never trusts the bridge: clamps numbers, drops malformed apps, keeps the list sorted and bounded. */
export function mergeScreenTime(raw: unknown): ScreenTimeState {
  const r: any = raw && typeof raw === 'object' ? raw : {};
  const apps: ScreenTimeApp[] = (Array.isArray(r.apps) ? r.apps : [])
    .filter((a: any) => a && typeof a.name === 'string' && a.name.trim())
    .map((a: any) => ({ name: String(a.name).slice(0, 40), min: clampInt(a.min, 0, 1440) }))
    .sort((a: ScreenTimeApp, b: ScreenTimeApp) => b.min - a.min).slice(0, 10);
  return { access: r.access === true, ok: r.ok === true, totalMin: clampInt(r.totalMin, 0, 1440), goalMin: clampGoal(r.goalMin), apps, day: typeof r.day === 'string' ? r.day.slice(0, 10) : '', error: typeof r.error === 'string' ? r.error.slice(0, 200) : '' };
}
/** 0 (off) or a multiple of 15 minutes up to 16 h. */
export function clampGoal(v: unknown): number {
  const x = clampInt(v, 0, GOAL_MAX_MIN);
  return x === 0 ? 0 : Math.max(GOAL_STEP_MIN, Math.round(x / GOAL_STEP_MIN) * GOAL_STEP_MIN);
}
export function stepGoal(current: number, dir: 1 | -1): number {
  if (current <= 0) return dir > 0 ? DEFAULT_GOAL_MIN : 0;                 // "off" -> first + press sets the default goal
  const next = current + dir * GOAL_STEP_MIN;
  return next < GOAL_STEP_MIN ? 0 : Math.min(GOAL_MAX_MIN, next);        // below 15 min = off
}
/** 222 -> "3h 42m", 42 -> "42m", 120 -> "2h 00m" (same as the device) */
export function fmtDuration(min: number): string {
  const m = Math.max(0, Math.round(min));
  return m >= 60 ? `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m` : `${m}m`;
}
export function goalPercent(total: number, goal: number): number { return goal > 0 ? Math.round((total * 100) / goal) : 0; }
export function goalText(total: number, goal: number): string {
  if (goal <= 0) return 'No daily goal set';
  const pct = goalPercent(total, goal);
  return total > goal ? `Over your ${fmtDuration(goal)} goal by ${fmtDuration(total - goal)}` : `${pct}% of your ${fmtDuration(goal)} goal`;
}
/** What the card should say about the data source. */
export function screenTimeStatus(s: ScreenTimeState): { tone: 'ok' | 'warn' | 'bad'; text: string } {
  if (!s.access) return { tone: 'warn', text: 'Usage Access is off. Allow it so Meow Island can read how long you use your phone.' };
  if (s.error) return { tone: 'bad', text: s.error };
  if (!s.ok) return { tone: 'warn', text: 'Android has not returned usage data yet.' };
  if (s.totalMin === 0 && s.apps.length === 0) return { tone: 'ok', text: 'No phone usage recorded yet today.' };
  return { tone: 'ok', text: 'Updates every minute and whenever the device reconnects.' };
}
/** Local calendar day of a timestamp, yyyy-mm-dd (the daily summary resets when this changes). */
export function localDayKey(ms: number): string {
  const d = new Date(ms); const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
