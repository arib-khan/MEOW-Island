"use client";

import { useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Bell, Check, Pause, Play, SkipBack, SkipForward, Timer, Clock3, Sparkles } from "lucide-react";
import CatFace, { PeekingCat } from "./CatFace";
import { useCountUp, useDemoCycle } from "./hooks";

/* ---------- shared primitives ---------- */

function Stage({ children, tag = "Mockup" }: { children: ReactNode; tag?: string }) {
  return (
    <div className="relative h-[216px] overflow-hidden rounded-2xl border border-lilac/10 bg-gradient-to-b from-[#120c20] to-[#0a0711]">
      <span className="absolute right-3 top-2 z-20 text-[11px] text-mist/70">{tag}</span>
      {children}
    </div>
  );
}

function Phone({ children }: { children: ReactNode }) {
  return (
    <div className="absolute inset-x-0 bottom-0 top-7 mx-auto w-[216px] rounded-t-[30px] border border-b-0 border-lilac/25 bg-[#0d0919] shadow-[0_-12px_40px_-22px_rgb(139_92_246/0.7)]">
      {children}
    </div>
  );
}

function Skeleton({ top = 54 }: { top?: number }) {
  return (
    <div className="absolute inset-x-5 space-y-2.5" style={{ top }} aria-hidden>
      <div className="h-2.5 w-3/5 rounded-full bg-white/10" />
      <div className="h-2.5 w-full rounded-full bg-white/[0.06]" />
      <div className="h-2.5 w-4/5 rounded-full bg-white/[0.06]" />
      <div className="mt-4 h-16 rounded-2xl bg-white/[0.04]" />
    </div>
  );
}

function Pill({ w, h, children, reduce, top = 9 }: { w: number | string; h: number; children?: ReactNode; reduce: boolean; top?: number }) {
  return (
    <motion.div
      className="absolute left-1/2 z-10 -translate-x-1/2 overflow-hidden bg-black shadow-[0_0_0_1px_rgb(183_156_255/0.35),0_8px_30px_-8px_rgb(139_92_246/0.7)]"
      animate={{ width: w, height: h, borderRadius: Math.min(22, h / 2 + 4), top }}
      transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 240, damping: 26 }}
    >
      {children}
    </motion.div>
  );
}

function Fade({ k, children, reduce, className }: { k: string | number; children: ReactNode; reduce: boolean; className?: string }) {
  return (
    <motion.div key={k} className={className} initial={reduce ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: reduce ? 0 : 0.25 }}>
      {children}
    </motion.div>
  );
}

/** Wraps a demo in a button so it can be stepped through with mouse, touch or keyboard. */
function DemoShell({
  label, count, ms = 2300, rest, tag, render,
}: {
  label: string; count: number; ms?: number; rest?: number; tag?: string;
  render: (step: number, reduce: boolean) => ReactNode;
}) {
  const { ref, step, next, reduce } = useDemoCycle(count, ms, rest);
  return (
    <div ref={ref}>
      <button type="button" onClick={next} aria-label={`${label} Activate to step through the demo.`} className="block w-full rounded-2xl text-left">
        <Stage tag={tag}>{render(step, reduce)}</Stage>
      </button>
    </div>
  );
}

/* ---------- A. floating island ---------- */

export function IslandDemo() {
  return (
    <DemoShell label="Mockup of the island at the top of a phone." count={2} ms={2800} rest={1}
      render={(step, reduce) => (
        <Phone>
          <Pill w={step ? 150 : 82} h={step ? 30 : 22} reduce={reduce}>
            <div className="flex h-full items-center justify-center gap-2 px-2">
              <CatFace className="h-4 w-4 shrink-0" />
              {step ? <span className="whitespace-nowrap text-[11px] text-white/90">MEOW Island</span> : <span className="h-1.5 w-1.5 rounded-full bg-lilac eye-glow" />}
            </div>
          </Pill>
          <Skeleton />
        </Phone>
      )} />
  );
}

/* ---------- B. music ---------- */

export function MusicDemo() {
  return (
    <DemoShell label="Mockup of the music controls." count={2} ms={3600} rest={0} tag="Placeholder track"
      render={(step, reduce) => {
        const playing = step === 0;
        return (
          <Phone>
            <Pill w={190} h={78} reduce={reduce}>
              <div className="flex h-full items-center gap-3 px-3">
                <div className={`relative h-[52px] w-[52px] shrink-0 rounded-full disc-spin`} style={{ animationPlayState: playing ? "running" : "paused", background: "conic-gradient(from 20deg,#8b5cf6,#e255d6,#ffa0c8,#8b5cf6)" }} aria-hidden>
                  <span className="absolute inset-[38%] rounded-full bg-black" />
                  <span className="absolute inset-[44%] rounded-full bg-lilac/40" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[12px] font-semibold text-white">Track title</p>
                  <p className="truncate text-[11px] text-mist">Artist name</p>
                  <div className="mt-1.5 flex items-center gap-3 text-white">
                    <SkipBack size={14} aria-hidden />
                    <span className="grid h-6 w-6 place-items-center rounded-full bg-lilac text-ink">{playing ? <Pause size={12} fill="currentColor" aria-hidden /> : <Play size={12} fill="currentColor" aria-hidden />}</span>
                    <SkipForward size={14} aria-hidden />
                  </div>
                </div>
              </div>
            </Pill>
            <Skeleton top={100} />
          </Phone>
        );
      }} />
  );
}

/* ---------- C. notifications ---------- */

export function NotificationDemo() {
  return (
    <DemoShell label="Mockup of a notification expanding from the island." count={2} ms={2600} rest={1} tag="Sample notification"
      render={(step, reduce) => (
        <Phone>
          <Pill w={step ? 192 : 82} h={step ? 64 : 22} reduce={reduce}>
            <AnimatePresence mode="wait">
              {step ? (
                <Fade k="open" reduce={reduce} className="flex h-full items-center gap-2.5 px-3">
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-violet/30 text-lilac"><Bell size={16} aria-hidden /></span>
                  <div className="min-w-0">
                    <p className="truncate text-[12px] font-semibold text-white">New message</p>
                    <p className="truncate text-[11px] text-mist">A short preview of the text</p>
                  </div>
                </Fade>
              ) : (
                <Fade k="closed" reduce={reduce} className="flex h-full items-center justify-center"><span className="h-1.5 w-1.5 rounded-full bg-lilac eye-glow" /></Fade>
              )}
            </AnimatePresence>
          </Pill>
          <Skeleton top={86} />
        </Phone>
      )} />
  );
}

/* ---------- D. timers & reminders ---------- */

export function ReminderDemo() {
  return (
    <DemoShell label="Mockup of reminder cards expanding from the island." count={3} ms={2400} rest={0} tag="Sample reminders"
      render={(step, reduce) => {
        const items = [
          { icon: <Clock3 size={16} aria-hidden />, t: "Task reminder", s: "Call the dentist, 3:00 PM" },
          { icon: <Timer size={16} aria-hidden />, t: "Countdown", s: "04:12 left" },
          { icon: <Check size={16} aria-hidden />, t: "Timer finished", s: "Tea is ready" },
        ][step];
        return (
          <Phone>
            <Pill w={192} h={60} reduce={reduce}>
              <AnimatePresence mode="wait">
                <Fade k={step} reduce={reduce} className="flex h-full items-center gap-2.5 px-3">
                  <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl ${step === 2 ? "bg-pink/25 text-pink" : "bg-violet/30 text-lilac"}`}>{items.icon}</span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[12px] font-semibold text-white">{items.t}</p>
                    <p className="truncate text-[11px] text-mist">{items.s}</p>
                  </div>
                </Fade>
              </AnimatePresence>
            </Pill>
            <Skeleton top={84} />
          </Phone>
        );
      }} />
  );
}

/* ---------- E. screen time guardian ---------- */

export function GuardianDemo() {
  return (
    <DemoShell label="Animation of the cat reacting to an app-usage limit." count={3} ms={2400} rest={1} tag="Concept"
      render={(step, reduce) => {
        const moods = ["normal", "angry", "happy"] as const;
        const text = ["Using an app, 4 of 10 min", "Limit reached", "You left the app"][step];
        const pct = [40, 100, 0][step];
        return (
          <div className="flex h-full flex-col items-center justify-center gap-3 px-6">
            <motion.div key={step} initial={reduce ? false : { scale: 0.85, rotate: step === 1 ? -4 : 0 }} animate={{ scale: 1, rotate: 0 }} transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 300, damping: 14 }}
              className={`rounded-full p-2.5 ${step === 1 ? "bg-pink/10 shadow-[0_0_40px_-6px_rgb(255_120_170/0.6)]" : step === 2 ? "bg-lilac/10 shadow-[0_0_40px_-6px_rgb(183_156_255/0.6)]" : ""}`}>
              <CatFace mood={moods[step]} className="h-[74px] w-[74px]" title={`Cat looking ${moods[step]}`} />
            </motion.div>
            <p className="text-[13px] font-medium text-white" aria-live="polite">{text}</p>
            <div className="h-1.5 w-40 overflow-hidden rounded-full bg-white/10" aria-hidden>
              <motion.div className={`h-full rounded-full ${step === 1 ? "bg-pink" : "bg-lilac"}`} animate={{ width: `${pct}%` }} transition={{ duration: reduce ? 0 : 0.8 }} />
            </div>
          </div>
        );
      }} />
  );
}

/* ---------- F. optional health (mini) ---------- */

export function HealthMiniDemo() {
  const { ref, value } = useCountUp(6420);
  return (
    <Stage tag="Sample values">
      <div className="flex h-full flex-col items-center justify-end px-6 pb-5">
        <PeekingCat className="-mb-px w-36" />
        <div className="w-full rounded-2xl border border-lilac/20 bg-[#1a1430] p-4">
          <div className="flex items-baseline justify-between">
            <span ref={ref as React.RefObject<HTMLSpanElement>} className="text-2xl font-extrabold tabular-nums">{value.toLocaleString("en-US")}</span>
            <span className="text-xs text-mist">goal 8,000</span>
          </div>
          <div className="mt-2.5 h-2 overflow-hidden rounded-full bg-white/10" aria-hidden>
            <div className="h-full rounded-full bg-gradient-to-r from-violet via-lilac to-pink" style={{ width: `${Math.min(100, (value / 8000) * 100)}%` }} />
          </div>
          <p className="mt-2 text-right text-xs text-lilac">{Math.round((value / 8000) * 100)}% complete</p>
        </div>
      </div>
    </Stage>
  );
}

/* ---------- G. walking progress ---------- */

export function WalkingDemo() {
  return (
    <DemoShell label="Mockup of the island turning into a step-progress bar." count={5} ms={1500} rest={3} tag="Sample progress"
      render={(step, reduce) => {
        const pct = [8, 34, 62, 88, 100][step];
        const done = step === 4;
        return (
          <Phone>
            <Pill w={done ? 120 : 164} h={done ? 34 : 16} reduce={reduce}>
              {done ? (
                <div className="flex h-full items-center justify-center gap-2"><CatFace mood="happy" className="h-6 w-6" /><span className="text-[11px] text-white">Goal reached!</span></div>
              ) : (
                <div className="flex h-full items-center px-2"><div className="h-1.5 w-full overflow-hidden rounded-full bg-white/15"><motion.div className="h-full rounded-full bg-gradient-to-r from-violet to-pink" animate={{ width: `${pct}%` }} transition={{ duration: reduce ? 0 : 0.6 }} /></div></div>
              )}
            </Pill>
            {done && !reduce && [...Array(6)].map((_, i) => (
              <motion.span key={i} className="absolute left-1/2 top-4 text-pink" initial={{ opacity: 1, x: 0, y: 0 }} animate={{ opacity: 0, x: (i - 2.5) * 22, y: 40 + (i % 2) * 18 }} transition={{ duration: 1.1 }} aria-hidden><Sparkles size={12} /></motion.span>
            ))}
            <Skeleton top={60} />
          </Phone>
        );
      }} />
  );
}

/* ---------- H. water ---------- */

export function WaterDemo() {
  return (
    <DemoShell label="Mockup of a water reminder with Done and Snooze actions." count={4} ms={2000} rest={0} tag="Sample reminder"
      render={(step, reduce) => {
        const done = step === 1, snooze = step === 3;
        return (
          <Phone>
            <Pill w={196} h={done || snooze ? 52 : 76} reduce={reduce}>
              <AnimatePresence mode="wait">
                {done || snooze ? (
                  <Fade k="ack" reduce={reduce} className="flex h-full items-center justify-center gap-2 text-[12px] text-white">
                    <Check size={14} className="text-lilac" aria-hidden /> {done ? "Logged. Nice sip!" : "Snoozed, I'll ask again"}
                  </Fade>
                ) : (
                  <Fade k="ask" reduce={reduce} className="flex h-full flex-col justify-center gap-2 px-3">
                    <p className="flex items-center gap-1.5 text-[12px] font-semibold text-white"><span>💧 Time to drink water!</span></p>
                    <div className="flex gap-2 text-[11px]">
                      <span className="rounded-full bg-lilac px-3 py-1 font-semibold text-ink">Done</span>
                      <span className="rounded-full border border-lilac/40 px-3 py-1 text-lilac">Snooze</span>
                    </div>
                  </Fade>
                )}
              </AnimatePresence>
            </Pill>
            <Skeleton top={92} />
          </Phone>
        );
      }} />
  );
}

/* ---------- I. customization (real controls) ---------- */

const POS = [
  { id: "statusbar", label: "Status bar", top: 7 },
  { id: "below", label: "Below bars", top: 36 },
  { id: "screen", label: "Anywhere on screen", top: 92 },
] as const;
const COLORS = [
  { name: "Black", v: "#000000" },
  { name: "Violet", v: "#3b1d7a" },
  { name: "Plum", v: "#5a1646" },
] as const;

export function CustomizeDemo() {
  const [pos, setPos] = useState<(typeof POS)[number]["id"]>("statusbar");
  const [width, setWidth] = useState(120);
  const [color, setColor] = useState<string>(COLORS[0].v);
  const top = POS.find((p) => p.id === pos)!.top;
  return (
    <div className="grid items-center gap-5 sm:grid-cols-[216px_1fr]">
      <Stage tag="Mockup">
        <Phone>
          <motion.div className="absolute left-1/2 z-10 h-[24px] -translate-x-1/2 rounded-full shadow-[0_0_0_1px_rgb(183_156_255/0.4)]" animate={{ top, width: Math.round(width * 0.8) }} style={{ background: color }} transition={{ type: "spring", stiffness: 260, damping: 26 }}>
            <span className="flex h-full items-center justify-center gap-1.5"><span className="h-1.5 w-1.5 rounded-full bg-lilac" /><span className="h-1.5 w-1.5 rounded-full bg-lilac" /></span>
          </motion.div>
          <Skeleton top={60} />
        </Phone>
      </Stage>
      <div className="space-y-4 text-sm">
        <fieldset>
          <legend className="mb-2 font-semibold text-white">Position</legend>
          <div className="flex flex-wrap gap-2">
            {POS.map((p) => (
              <label key={p.id} className={`cursor-pointer rounded-full border px-3.5 py-1.5 transition has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-lilac ${pos === p.id ? "border-lilac bg-lilac/15 text-white" : "border-lilac/25 text-mist hover:text-white"}`}>
                <input type="radio" name="island-pos" className="sr-only" checked={pos === p.id} onChange={() => setPos(p.id)} /> {p.label}
              </label>
            ))}
          </div>
        </fieldset>
        <div>
          <label htmlFor="w" className="mb-2 block font-semibold text-white">Width <span className="font-normal text-mist">{width} dp</span></label>
          <input id="w" type="range" min={80} max={260} step={10} value={width} onChange={(e) => setWidth(Number(e.target.value))} className="w-full accent-[#b79cff]" />
        </div>
        <fieldset>
          <legend className="mb-2 font-semibold text-white">Colour</legend>
          <div className="flex gap-3">
            {COLORS.map((c) => (
              <label key={c.v} className="cursor-pointer has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-lilac rounded-full">
                <input type="radio" name="island-color" className="sr-only" checked={color === c.v} onChange={() => setColor(c.v)} aria-label={c.name} />
                <span className={`block h-9 w-9 rounded-full ring-2 ring-offset-2 ring-offset-[#14101f] ${color === c.v ? "ring-lilac" : "ring-white/15"}`} style={{ background: c.v }} />
              </label>
            ))}
          </div>
        </fieldset>
      </div>
    </div>
  );
}
