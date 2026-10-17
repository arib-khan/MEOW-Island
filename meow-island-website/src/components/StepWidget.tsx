"use client";

import { useState } from "react";
import { Minus, Plus } from "lucide-react";
import { motion } from "framer-motion";
import CatFace, { PeekingCat } from "./CatFace";
import ContributionGrid from "./ContributionGrid";
import { useCountUp } from "./hooks";

const fmt = (n: number) => n.toLocaleString("en-US");

export default function StepWidget() {
  const [steps, setSteps] = useState(6420);
  const [goal, setGoal] = useState(8000);
  const { ref, value } = useCountUp(steps, 450);
  const pct = Math.round((steps / goal) * 100);
  const reached = steps >= goal;

  return (
    <div className="mx-auto w-full max-w-xl pt-10">
      <div className="relative">
        <PeekingCat className="absolute -top-[50px] left-1/2 z-10 w-48 -translate-x-1/2 sm:-top-[54px] sm:w-56" />
        <div className="neon-edge relative rounded-[36px] bg-gradient-to-b from-[#1b1432] to-[#100b1d] p-5 pt-9 sm:p-8 sm:pt-11">
          <div className="flex items-center justify-between gap-3 text-sm">
            <h3 className="font-bold text-white">Today</h3>
            <span className="rounded-full border border-pink/40 bg-pink/10 px-2.5 py-1 text-xs text-pink">Sample data</span>
          </div>

          <div className="mt-3 flex items-end justify-between gap-4">
            <p className="text-5xl font-extrabold tabular-nums tracking-tight sm:text-6xl">
              <span ref={ref as React.RefObject<HTMLSpanElement>} aria-hidden>{fmt(value)}</span>
              <span className="sr-only">{fmt(steps)} sample steps</span>
              <span className="ml-2 text-base font-medium text-mist">steps</span>
            </p>
            <div className="text-right">
              {reached ? <CatFace mood="happy" className="ml-auto h-10 w-10" title="Happy cat: sample goal reached" /> : null}
              <p className="mt-1 text-2xl font-bold text-lilac tabular-nums">{pct}%</p>
              <p className="text-xs text-mist">complete</p>
            </div>
          </div>

          <div className="mt-5" role="progressbar" aria-label="Sample daily step progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(100, pct)}>
            <div className="h-3.5 overflow-hidden rounded-full bg-white/10">
              <motion.div className="h-full rounded-full bg-gradient-to-r from-violet via-lilac to-pink shadow-[0_0_18px_rgb(183_156_255/0.7)]" animate={{ width: `${Math.min(100, pct)}%` }} transition={{ type: "spring", stiffness: 120, damping: 20 }} />
            </div>
            <div className="mt-2 flex justify-between text-xs text-mist"><span>0</span><span>Goal {fmt(goal)}</span></div>
          </div>

          <div className="mt-6 grid gap-5 rounded-2xl border border-lilac/10 bg-black/20 p-4 sm:grid-cols-2">
            <div>
              <label htmlFor="sample-steps" className="mb-2 block text-sm font-semibold">Try it: sample steps</label>
              <input id="sample-steps" type="range" min={0} max={20000} step={100} value={steps} onChange={(e) => setSteps(Number(e.target.value))} className="w-full accent-[#b79cff]" />
            </div>
            <div>
              <p id="goal-label" className="mb-2 text-sm font-semibold">Daily step goal</p>
              <div className="flex items-center gap-3" role="group" aria-labelledby="goal-label">
                <button type="button" aria-label="Lower goal by 500" onClick={() => setGoal((g) => Math.max(2000, g - 500))} className="grid h-9 w-9 place-items-center rounded-full border border-lilac/30 hover:bg-white/10"><Minus size={16} /></button>
                <output className="min-w-[4.5rem] text-center font-bold tabular-nums" aria-live="polite">{fmt(goal)}</output>
                <button type="button" aria-label="Raise goal by 500" onClick={() => setGoal((g) => Math.min(20000, g + 500))} className="grid h-9 w-9 place-items-center rounded-full border border-lilac/30 hover:bg-white/10"><Plus size={16} /></button>
              </div>
            </div>
          </div>

          <div className="mt-7 border-t border-lilac/10 pt-5">
            <h3 className="mb-3 text-sm font-bold text-white">History <span className="ml-1 font-normal text-mist">(made-up sample weeks)</span></h3>
            <ContributionGrid todaySteps={steps} todayGoal={goal} />
          </div>
          <p className="mt-5 text-xs leading-relaxed text-mist">
            Every day keeps the goal that was active when it ended, so changing today&rsquo;s goal never rewrites your history. Nothing on this page reads a sensor or stores anything.
          </p>
        </div>
      </div>
    </div>
  );
}
