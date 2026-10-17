"use client";

import dynamic from "next/dynamic";
import { useEffect, useRef, useState } from "react";
import { motion, useInView, useReducedMotion } from "framer-motion";
import { ArrowDown, Github } from "lucide-react";
import { SITE } from "@/lib/site";
import CatFace from "./CatFace";

const IslandScene = dynamic(() => import("./IslandScene"), { ssr: false });

function webglOk() {
  try {
    const c = document.createElement("canvas");
    return !!(c.getContext("webgl2") || c.getContext("webgl"));
  } catch {
    return false;
  }
}

export default function Hero() {
  const reduce = !!useReducedMotion();
  const box = useRef<HTMLDivElement>(null);
  const visible = useInView(box);
  const [gl, setGl] = useState<boolean | null>(null);
  useEffect(() => setGl(webglOk()), []);

  const item = (i: number) => ({
    initial: reduce ? false : ({ opacity: 0, y: 22 } as const),
    animate: { opacity: 1, y: 0 },
    transition: { duration: 0.7, delay: 0.1 + i * 0.12, ease: [0.22, 1, 0.36, 1] as const },
  });

  return (
    <section id="home" className="relative mx-auto grid min-h-[100svh] max-w-6xl items-center gap-4 px-5 pb-16 pt-28 sm:px-8 lg:grid-cols-[1.05fr_1fr] lg:pt-24">
      <div className="relative z-10 order-2 lg:order-1">
        <motion.p {...item(0)} className="inline-flex items-center gap-2 rounded-full border border-lilac/25 bg-white/5 px-3.5 py-1.5 text-sm text-lilac">
          <span className="h-2 w-2 rounded-full bg-pink shadow-[0_0_10px_2px_rgb(255_160_200/0.7)]" aria-hidden />
          Android app in development
        </motion.p>
        <motion.h1 {...item(1)} className="mt-6 text-[2.9rem] font-extrabold leading-[1.02] tracking-[-0.035em] sm:text-6xl lg:text-7xl">
          Your phone&rsquo;s cutest companion.
        </motion.h1>
        <motion.p {...item(2)} className="mt-6 max-w-xl text-lg leading-relaxed text-mist">
          Meet MEOW Island &mdash; a cat-inspired floating island for Android that brings everyday controls, notifications, reminders, and optional wellness tools a little closer.
        </motion.p>
        <motion.div {...item(3)} className="mt-9 flex flex-wrap gap-3">
          <a href={SITE.repo} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-full bg-lilac px-6 py-3.5 font-semibold text-ink shadow-[0_0_40px_-8px_rgb(139_92_246/0.9)] transition hover:bg-white">
            <Github size={18} aria-hidden /> Explore the Project
          </a>
          <a href="#features" className="inline-flex items-center gap-2 rounded-full border border-lilac/35 px-6 py-3.5 font-semibold text-white transition hover:border-lilac hover:bg-white/5">
            Discover Features
          </a>
        </motion.div>
        <motion.p {...item(4)} className="mt-6 max-w-md text-sm text-mist/80">
          Source-only for now: no Google Play listing yet. Build it from the repository.
        </motion.p>
      </div>

      <div ref={box} className="relative order-1 h-[300px] [mask-image:linear-gradient(to_bottom,black_72%,transparent)] sm:h-[400px] lg:order-2 lg:h-[560px]">
        <div className="pointer-events-none absolute inset-6 rounded-full bg-violet/20 blur-3xl" aria-hidden />
        {gl === true && <IslandScene reduced={reduce} active={visible} />}
        {gl === false && (
          <div className="flex h-full items-center justify-center">
            <CatFace className="h-48 w-48 drop-shadow-[0_0_40px_rgb(139_92_246/0.6)]" title="MEOW Island cat mascot" />
          </div>
        )}
      </div>

      <a href="#features" aria-label="Scroll to features" className="absolute bottom-5 left-1/2 hidden -translate-x-1/2 text-mist hover:text-white lg:block">
        <motion.span animate={reduce ? undefined : { y: [0, 7, 0] }} transition={{ repeat: Infinity, duration: 2.2, ease: "easeInOut" }} className="block">
          <ArrowDown size={22} />
        </motion.span>
      </a>
    </section>
  );
}
