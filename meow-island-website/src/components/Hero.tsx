import { Github, ArrowRight } from "lucide-react";
import { SITE } from "@/lib/site";
import HeroVisual from "./HeroVisual";

const FACTS = ["Open source", "Android only", "Health tools off by default"];

export default function Hero() {
  return (
    <section id="home" aria-labelledby="hero-title" className="relative mx-auto grid max-w-6xl items-center gap-14 px-5 pb-20 pt-28 sm:px-8 sm:pt-32 lg:min-h-[100svh] lg:grid-cols-[1.05fr_0.95fr] lg:gap-8 lg:pb-16">
      <div className="rise relative z-10">
        <p className="inline-flex items-center gap-2 rounded-full border border-lilac/25 bg-white/5 px-3.5 py-1.5 text-sm text-lilac">
          <span className="h-2 w-2 rounded-full bg-pink" aria-hidden />
          Early development · source-only, no Google Play listing yet
        </p>
        <h1 id="hero-title" className="mt-6 text-[2.75rem] font-extrabold leading-[1.04] tracking-[-0.035em] sm:text-6xl lg:text-[4.25rem]">
          A cat-inspired floating island for your Android phone.
        </h1>
        <p className="mt-6 max-w-xl text-lg leading-relaxed text-mist">
          MEOW Island keeps music controls, notification previews, reminders and screen-time nudges one glance away, so you switch apps less. Everything beyond the basics is opt-in.
        </p>
        <div className="mt-9 flex flex-wrap gap-3">
          <a href={SITE.repo} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-full bg-lilac px-6 py-3.5 font-semibold text-ink transition hover:bg-white">
            <Github size={18} aria-hidden /> Follow on GitHub
          </a>
          <a href="#how-it-works" className="inline-flex items-center gap-2 rounded-full border border-lilac/35 px-6 py-3.5 font-semibold text-white transition hover:border-lilac hover:bg-white/5">
            How it works <ArrowRight size={16} aria-hidden />
          </a>
        </div>
        <ul className="mt-8 flex flex-wrap gap-x-5 gap-y-2 text-sm text-mist">
          {FACTS.map((f) => (
            <li key={f} className="flex items-center gap-2"><span className="h-1 w-1 rounded-full bg-lilac" aria-hidden />{f}</li>
          ))}
        </ul>
      </div>
      <div className="rise relative [animation-delay:120ms]"><HeroVisual /></div>
    </section>
  );
}
