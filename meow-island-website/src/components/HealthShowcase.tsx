import { Eraser, Lock, SlidersHorizontal } from "lucide-react";
import MotionSection from "./MotionSection";
import StepWidget from "./StepWidget";
import { SITE } from "@/lib/site";

const points = [
  { icon: SlidersHorizontal, t: "Off until you say so", d: "Step counting, the walking island, the heatmap and water reminders each have their own switch. Turning one off leaves the others alone." },
  { icon: Lock, t: "Kept on the phone", d: "The app's code stores step history, goals and the water log locally, and a review of the repository found no network or analytics code." },
  { icon: Eraser, t: "Clear it any time", d: "A Clear health data button deletes history, contribution records and the water log after you confirm." },
];

export default function HealthShowcase() {
  return (
    <section id="health" className="relative mx-auto max-w-6xl px-5 py-24 sm:px-8">
      <div className="pointer-events-none absolute inset-x-0 top-10 -z-10 h-[520px] bg-[radial-gradient(600px_300px_at_70%_30%,rgb(139_92_246/0.18),transparent_70%)]" aria-hidden />
      <div className="grid items-center gap-14 lg:grid-cols-[0.9fr_1.1fr]">
        <MotionSection>
          <h2 className="text-4xl font-extrabold tracking-[-0.03em] sm:text-5xl">Steps, one purple square at a time.</h2>
          <p className="mt-5 text-xl leading-relaxed text-[#e4def8]">
            Build your own walking routine, one step at a time. Set a daily goal, follow your progress, and celebrate the days you reach it.
          </p>
          <p className="mt-4 leading-relaxed text-mist">
            Health features are optional and built around your choices. Today&rsquo;s progress sits up top; the grid below it is your history, where bright squares mean you hit your goal and dashed outlines mean the data was incomplete, never a failure.
          </p>
          <ul className="mt-8 space-y-5">
            {points.map(({ icon: Icon, t, d }) => (
              <li key={t} className="flex gap-4">
                <span className="mt-0.5 grid h-10 w-10 shrink-0 place-items-center rounded-2xl bg-violet/20 text-lilac ring-1 ring-lilac/25"><Icon size={18} aria-hidden /></span>
                <div><p className="font-semibold">{t}</p><p className="mt-1 text-sm leading-relaxed text-mist">{d}</p></div>
              </li>
            ))}
          </ul>
          <p className="mt-8 text-sm leading-relaxed text-mist">
            Needs the Physical activity permission and a step sensor, and Android may delay readings. Details are in the{" "}
            <a className="text-lilac underline underline-offset-4 hover:text-white" href={SITE.healthDoc} target="_blank" rel="noopener noreferrer">health notes</a>.
            The app also includes a Meow Step Heatmap home-screen widget; the repository notes it has not yet been seen on a real launcher.
          </p>
        </MotionSection>
        <MotionSection delay={0.1}><StepWidget /></MotionSection>
      </div>
    </section>
  );
}
