"use client";

import { useMemo, useState } from "react";

export type DayLevel = "reached" | "mid" | "low" | "incomplete" | "none";
type Day = { steps: number; goal: number; level: DayLevel };

const WEEKS = 17;
const SAMPLE_GOAL = 8000;

function mulberry32(seed: number) {
  return () => {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function levelFor(steps: number, goal: number): DayLevel {
  const p = steps / goal;
  return p >= 1 ? "reached" : p >= 0.5 ? "mid" : "low";
}

/** Fixed sample history (seeded, so server and client agree). Not real data. */
function buildSample(): Day[] {
  const rnd = mulberry32(7);
  const days: Day[] = [];
  const total = WEEKS * 7 - 1; // today is added separately
  for (let i = 0; i < total; i++) {
    if (i < 9) { days.push({ steps: 0, goal: SAMPLE_GOAL, level: "none" }); continue; } // before tracking began
    if (i === 23 || i === 61) { days.push({ steps: 0, goal: SAMPLE_GOAL, level: "incomplete" }); continue; }
    const wk = Math.floor(i / 7), weekend = i % 7 >= 5;
    const base = 4200 + wk * 150 + (weekend ? 1800 : 0) + (rnd() - 0.35) * 6500;
    const steps = Math.max(600, Math.round(base / 10) * 10);
    days.push({ steps, goal: SAMPLE_GOAL, level: levelFor(steps, SAMPLE_GOAL) });
  }
  return days;
}

const CELL: Record<DayLevel, string> = {
  reached: "bg-[#a77bff] shadow-[0_0_10px_1px_rgb(167_123_255/0.75)]",
  mid: "bg-[#6a3fd0]",
  low: "bg-[#35235f]",
  incomplete: "border border-dashed border-pink/80",
  none: "border border-dashed border-white/15",
};

const LABEL: Record<DayLevel, string> = {
  reached: "Goal reached",
  mid: "50 to 99% of goal",
  low: "Under 50% of goal",
  incomplete: "Incomplete data (not counted as a miss)",
  none: "No data",
};

export default function ContributionGrid({ todaySteps, todayGoal }: { todaySteps: number; todayGoal: number }) {
  const sample = useMemo(buildSample, []);
  const [hover, setHover] = useState<string>("Hover a square to read it.");
  const reached = sample.filter((d) => d.level === "reached").length;
  const todayLevel = levelFor(todaySteps, todayGoal);

  const describe = (d: Day, today = false) =>
    `${today ? "Today" : "Sample day"}: ${d.level === "none" || d.level === "incomplete" ? LABEL[d.level] : `${d.steps.toLocaleString("en-US")} of ${d.goal.toLocaleString("en-US")} steps, ${LABEL[d.level].toLowerCase()}`}`;

  return (
    <div>
      <div
        role="img"
        aria-label={`Sample history heatmap: ${WEEKS} weeks of made-up data, goal reached on ${reached} days. Today is the last square and follows the controls above.`}
        className="grid gap-[3px] sm:gap-1"
        style={{ gridTemplateRows: "repeat(7, minmax(0, 1fr))", gridAutoFlow: "column", gridAutoColumns: "minmax(0, 1fr)" }}
        onMouseLeave={() => setHover("Hover a square to read it.")}
      >
        {sample.map((d, i) => (
          <span key={i} aria-hidden className={`aspect-square rounded-[4px] ${CELL[d.level]}`} onMouseEnter={() => setHover(describe(d))} />
        ))}
        <span
          aria-hidden
          className={`aspect-square rounded-[4px] outline outline-2 outline-offset-1 outline-white/90 transition-colors ${CELL[todayLevel]}`}
          onMouseEnter={() => setHover(describe({ steps: todaySteps, goal: todayGoal, level: todayLevel }, true))}
        />
      </div>
      <p className="mt-3 min-h-5 text-xs text-lilac" aria-hidden>{hover}</p>
      <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-2 text-xs text-mist">
        {(Object.keys(LABEL) as DayLevel[]).map((k) => (
          <li key={k} className="flex items-center gap-1.5"><span aria-hidden className={`h-3 w-3 rounded-[3px] ${CELL[k]}`} />{LABEL[k]}</li>
        ))}
        <li className="flex items-center gap-1.5"><span aria-hidden className="h-3 w-3 rounded-[3px] outline outline-2 outline-white/90" />Today</li>
      </ul>
    </div>
  );
}
