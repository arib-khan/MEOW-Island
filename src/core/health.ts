/** Pure health helpers (no React Native imports) so they can be unit-tested with plain Node. */

export type WaterSettings = {
  enabled: boolean; intervalMin: number; startMin: number; endMin: number; days: boolean[];
  toIsland: boolean; toNotification: boolean; quietEnabled: boolean; quietStartMin: number; quietEndMin: number;
  snoozeMin: number; pausedUntil: number;
};
export type HealthCfg = {
  stepCounter: boolean; walkingIsland: boolean; goalEnabled: boolean; heatmap: boolean; heatmapOnIsland: boolean;
  goal: number; walkSensitivity: 'low' | 'normal' | 'high'; walkReturnSec: number; backgroundSteps: boolean; water: WaterSettings;
};

/** Everything is OFF by default: nothing health-related activates without the user's consent. */
export const HEALTH_DEFAULTS: HealthCfg = {
  stepCounter: false, walkingIsland: false, goalEnabled: false, heatmap: false, heatmapOnIsland: false,
  goal: 8000, walkSensitivity: 'normal', walkReturnSec: 8,
  // Only has an effect once a step feature is switched on (nothing starts by itself): keeps counting while locked / in the background.
  backgroundSteps: true,
  water: {
    enabled: false, intervalMin: 60, startMin: 9 * 60, endMin: 21 * 60, days: [true, true, true, true, true, true, true],
    toIsland: true, toNotification: true, quietEnabled: false, quietStartMin: 13 * 60, quietEndMin: 14 * 60, snoozeMin: 10, pausedUntil: 0,
  },
};

const clamp = (v: unknown, lo: number, hi: number, d: number) => { const n = typeof v === 'number' && isFinite(v) ? Math.round(v) : d; return Math.min(hi, Math.max(lo, n)); };
const bool = (v: unknown, d: boolean) => (typeof v === 'boolean' ? v : d);

/** Merges stored settings over the defaults and clamps every value, so corrupt or old data can never break the app. */
export function mergeHealth(saved: any): HealthCfg {
  const s = saved && typeof saved === 'object' ? saved : {};
  const w = s.water && typeof s.water === 'object' ? s.water : {};
  const D = HEALTH_DEFAULTS, DW = HEALTH_DEFAULTS.water;
  const days = Array.isArray(w.days) && w.days.length === 7 ? w.days.map((x: unknown) => x === true) : DW.days.slice();
  const start = clamp(w.startMin, 0, 1439, DW.startMin);
  const end = clamp(w.endMin, 0, 1439, DW.endMin);
  return {
    stepCounter: bool(s.stepCounter, D.stepCounter), walkingIsland: bool(s.walkingIsland, D.walkingIsland),
    goalEnabled: bool(s.goalEnabled, D.goalEnabled), heatmap: bool(s.heatmap, D.heatmap), heatmapOnIsland: bool(s.heatmapOnIsland, D.heatmapOnIsland),
    goal: clamp(s.goal, 500, 100000, D.goal),
    walkSensitivity: s.walkSensitivity === 'low' || s.walkSensitivity === 'high' ? s.walkSensitivity : 'normal',
    walkReturnSec: clamp(s.walkReturnSec, 3, 60, D.walkReturnSec),
    backgroundSteps: bool(s.backgroundSteps, D.backgroundSteps),
    water: {
      enabled: bool(w.enabled, DW.enabled), intervalMin: clamp(w.intervalMin, 5, 1440, DW.intervalMin),
      startMin: Math.min(start, end), endMin: Math.max(start, end), days,
      toIsland: bool(w.toIsland, DW.toIsland), toNotification: bool(w.toNotification, DW.toNotification),
      quietEnabled: bool(w.quietEnabled, DW.quietEnabled), quietStartMin: clamp(w.quietStartMin, 0, 1439, DW.quietStartMin), quietEndMin: clamp(w.quietEndMin, 0, 1439, DW.quietEndMin),
      snoozeMin: clamp(w.snoozeMin, 1, 120, DW.snoozeMin), pausedUntil: typeof w.pausedUntil === 'number' && w.pausedUntil > 0 ? w.pausedUntil : 0,
    },
  };
}

/** Does any step-based feature need the step sensor? (They share one local reader.) */
export const stepsNeeded = (c: HealthCfg) => c.stepCounter || c.walkingIsland || c.goalEnabled || c.heatmap;

export type DayRec = { s: number; g: number; p: boolean };
export type DayStatus = 'complete' | 'missed' | 'unknown' | 'nodata';

/** complete = goal reached; unknown = tracking was incomplete that day (never counted as a miss); missed = fully tracked, below goal. */
export function dayStatus(r?: DayRec | null): DayStatus {
  if (!r) return 'nodata';
  if (r.g > 0 && r.s >= r.g) return 'complete';
  return r.p ? 'unknown' : 'missed';
}

/** 0 no data, 1 unknown, 2 below 50%, 3 50-99%, 4 goal reached (matches the native DayRec.level). */
export function level(r?: DayRec | null): 0 | 1 | 2 | 3 | 4 {
  if (!r) return 0;
  if (r.g > 0 && r.s >= r.g) return 4;
  if (r.p) return 1;
  if (r.g <= 0) return 2;
  return Math.floor((r.s * 100) / r.g) >= 50 ? 3 : 2;
}

export const pct = (steps: number, goal: number) => (goal > 0 ? Math.min(100, Math.floor((steps * 100) / goal)) : 0);
export const fmt = (n: number) => String(Math.max(0, Math.round(n))).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
export const pad2 = (n: number) => String(n).padStart(2, '0');
/** Local calendar date key, e.g. 2026-10-16. */
export const ymd = (d: Date) => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
export const hhmm = (m: number) => `${pad2(Math.floor(m / 60))}:${pad2(m % 60)}`;

export type Cell = { key: string; date: Date; rec?: DayRec; level: number; future: boolean };

/**
 * Contribution grid: columns are weeks (oldest left), rows are Monday..Sunday, the last column contains today.
 * Dates are built with calendar arithmetic (not +24h) so daylight-saving changes cannot skip or repeat a day.
 */
export function buildGrid(today: Date, days: Record<string, DayRec>, weeks = 5): Cell[][] {
  const idx = (today.getDay() + 6) % 7;
  const cols: Cell[][] = [];
  for (let w = 0; w < weeks; w++) {
    const col: Cell[] = [];
    for (let r = 0; r < 7; r++) {
      const back = (weeks - 1 - w) * 7 + (idx - r);
      const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - back);
      const key = ymd(d);
      const future = back < 0;
      const rec = future ? undefined : days[key];
      col.push({ key, date: d, rec, level: future ? -1 : level(rec), future });
    }
    cols.push(col);
  }
  return cols;
}
