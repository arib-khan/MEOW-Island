import type { ReactNode } from "react";
import { Info } from "lucide-react";
import MotionSection from "./MotionSection";

export default function FeatureCard({
  icon, title, description, note, demo, className = "", wide = false, delay = 0,
}: {
  icon: ReactNode; title: string; description: string; note: string; demo: ReactNode; className?: string; wide?: boolean; delay?: number;
}) {
  return (
    <MotionSection delay={delay} className={className}>
      <article className={`glass group flex h-full flex-col rounded-[28px] p-5 transition duration-300 hover:-translate-y-0.5 hover:border-lilac/40 hover:shadow-[0_0_44px_-14px_rgb(139_92_246/0.6)] sm:p-6 ${wide ? "gap-6" : "gap-5"}`}>
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-2xl bg-violet/20 text-lilac ring-1 ring-lilac/25 transition group-hover:bg-violet/30">{icon}</span>
          <h3 className="text-xl font-bold tracking-tight">{title}</h3>
        </div>
        <p className="leading-relaxed text-[#d6d0ea]">{description}</p>
        <div>{demo}</div>
        <p className="mt-auto flex gap-2 text-[13px] leading-relaxed text-mist"><Info size={15} className="mt-0.5 shrink-0 text-lilac/70" aria-hidden /> <span>{note}</span></p>
      </article>
    </MotionSection>
  );
}
