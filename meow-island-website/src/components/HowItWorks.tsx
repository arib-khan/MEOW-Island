"use client";

import { motion, useReducedMotion } from "framer-motion";
import { Bell, Droplets, Music2, Timer } from "lucide-react";
import MotionSection from "./MotionSection";
import CatFace from "./CatFace";

function DiagramCustomize() {
  return (
    <div className="relative h-36 rounded-2xl bg-black/30 p-4" aria-hidden>
      <div className="mx-auto flex h-6 w-28 items-center justify-center gap-1.5 rounded-full bg-black ring-1 ring-lilac/50"><CatFace className="h-4 w-4" /></div>
      {[62, 38, 80].map((w, i) => (
        <div key={i} className="mt-3.5 h-1.5 rounded-full bg-white/10"><div className="h-full rounded-full bg-gradient-to-r from-violet to-lilac" style={{ width: `${w}%` }} /></div>
      ))}
    </div>
  );
}
function DiagramEssentials() {
  const icons = [Music2, Bell, Timer, Droplets];
  return (
    <div className="relative grid h-36 place-items-center rounded-2xl bg-black/30" aria-hidden>
      <div className="flex gap-3">
        {icons.map((I, i) => <span key={i} className="grid h-11 w-11 place-items-center rounded-2xl bg-violet/20 text-lilac ring-1 ring-lilac/25"><I size={18} /></span>)}
      </div>
      <span className="absolute bottom-3 text-[11px] text-mist">close at hand</span>
    </div>
  );
}
function DiagramToggles() {
  const reduce = useReducedMotion();
  const rows = [["Screen time", true], ["Step tracking", false], ["Water reminders", false]] as const;
  return (
    <div className="grid h-36 content-center gap-3 rounded-2xl bg-black/30 px-5" aria-hidden>
      {rows.map(([label, on], i) => (
        <div key={label} className="flex items-center justify-between text-xs text-[#d6d0ea]">
          {label}
          <span className={`relative h-5 w-9 rounded-full transition-colors ${on ? "bg-violet" : "bg-white/15"}`}>
            <motion.span className="absolute top-0.5 h-4 w-4 rounded-full bg-white" initial={reduce ? false : { left: 2 }} whileInView={{ left: on ? 18 : 2 }} viewport={{ once: true }} transition={{ delay: 0.3 + i * 0.15 }} style={{ left: on ? 18 : 2 }} />
          </span>
        </div>
      ))}
    </div>
  );
}

const STEPS = [
  { n: "01", t: "Customize your island", d: "Choose supported settings and personalize the experience.", D: DiagramCustomize },
  { n: "02", t: "Keep essentials close", d: "Access supported controls, reminders, and notifications.", D: DiagramEssentials },
  { n: "03", t: "Enable what matters", d: "Opt into screen-time awareness and wellness tools only when you want them.", D: DiagramToggles },
];

export default function HowItWorks() {
  return (
    <section id="how-it-works" className="mx-auto max-w-6xl px-5 py-24 sm:px-8">
      <MotionSection className="max-w-2xl">
        <h2 className="text-4xl font-extrabold tracking-[-0.03em] sm:text-5xl">Three small steps.</h2>
        <p className="mt-4 text-lg text-mist">The first run asks for permissions one at a time. Say no to any of them and only the feature that needs it stays off.</p>
      </MotionSection>
      <ol className="mt-12 grid gap-5 md:grid-cols-3">
        {STEPS.map(({ n, t, d, D }, i) => (
          <MotionSection as="li" key={n} delay={i * 0.1} className="glass rounded-[28px] p-6">
            <D />
            <p className="mt-5 text-sm font-semibold text-lilac">{n}</p>
            <h3 className="mt-1 text-xl font-bold">{t}</h3>
            <p className="mt-2 leading-relaxed text-mist">{d}</p>
          </MotionSection>
        ))}
      </ol>
    </section>
  );
}
