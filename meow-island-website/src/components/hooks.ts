"use client";

import { useEffect, useRef, useState, useCallback } from "react";
import { useInView, useReducedMotion } from "framer-motion";

/**
 * Cycles through `count` demo states while the element is on screen.
 * Reduced motion: no autoplay, shows `restStep`; the user can still step manually.
 * Returns a `next()` so clicks / keyboard can advance the demo (and pause autoplay briefly).
 */
export function useDemoCycle(count: number, ms = 2200, restStep = count - 1) {
  const ref = useRef<HTMLDivElement>(null);
  const inView = useInView(ref, { margin: "-80px" });
  const reduce = useReducedMotion();
  const [step, setStep] = useState(reduce ? restStep : 0);
  const [pausedUntil, setPausedUntil] = useState(0);

  useEffect(() => {
    if (reduce || !inView) return;
    const id = window.setInterval(() => {
      if (Date.now() < pausedUntil) return;
      setStep((s) => (s + 1) % count);
    }, ms);
    return () => window.clearInterval(id);
  }, [reduce, inView, count, ms, pausedUntil]);

  const next = useCallback(() => {
    setPausedUntil(Date.now() + 7000);
    setStep((s) => (s + 1) % count);
  }, [count]);

  return { ref, step, next, reduce: !!reduce };
}

/** Counts up to `target` once visible. Reduced motion jumps straight to the value. */
export function useCountUp(target: number, ms = 1100) {
  const ref = useRef<HTMLElement>(null);
  const inView = useInView(ref, { once: true });
  const reduce = useReducedMotion();
  const [value, setValue] = useState(reduce ? target : 0);
  const from = useRef(0);

  useEffect(() => {
    if (reduce) { setValue(target); return; }
    if (!inView) return;
    const start = performance.now();
    const a = from.current;
    let raf = 0;
    const tick = (now: number) => {
      const p = Math.min(1, (now - start) / ms);
      const eased = 1 - Math.pow(1 - p, 3);
      const v = Math.round(a + (target - a) * eased);
      setValue(v);
      from.current = v;
      if (p < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, inView, reduce, ms]);

  return { ref, value };
}
