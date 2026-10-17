import { Bug, Code2, Star } from "lucide-react";
import MotionSection from "./MotionSection";
import CatFace from "./CatFace";
import { SITE } from "@/lib/site";

export default function CommunityCTA() {
  return (
    <section id="community" className="mx-auto max-w-6xl px-5 pb-28 pt-12 sm:px-8">
      <MotionSection className="neon-edge relative overflow-hidden rounded-[40px] bg-gradient-to-br from-[#241848] via-[#160f2b] to-[#0f0a1c] px-6 py-14 text-center sm:px-14">
        <div className="pointer-events-none absolute -top-24 left-1/2 h-64 w-[34rem] -translate-x-1/2 rounded-full bg-violet/30 blur-3xl" aria-hidden />
        <CatFace mood="happy" className="relative mx-auto h-20 w-20" />
        <h2 className="relative mt-5 text-4xl font-extrabold tracking-[-0.03em] sm:text-5xl">Help this little cat grow.</h2>
        <p className="relative mx-auto mt-4 max-w-xl text-lg leading-relaxed text-[#d6d0ea]">
          MEOW Island is an evolving project. Explore the code, share feedback, report issues, and leave a star if you like the idea.
        </p>
        <div className="relative mt-8 flex flex-wrap justify-center gap-3">
          <a href={SITE.repo} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-full bg-lilac px-6 py-3.5 font-semibold text-ink transition hover:bg-white"><Star size={18} aria-hidden /> Star on GitHub</a>
          <a href={SITE.repo} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-full border border-lilac/40 px-6 py-3.5 font-semibold hover:bg-white/5"><Code2 size={18} aria-hidden /> Explore Source Code</a>
          <a href={SITE.issues} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-full border border-lilac/40 px-6 py-3.5 font-semibold hover:bg-white/5"><Bug size={18} aria-hidden /> Report an Issue</a>
        </div>
      </MotionSection>

      <MotionSection className="mx-auto mt-14 max-w-3xl">
        <h3 className="text-2xl font-bold">Run it yourself</h3>
        <p className="mt-2 text-mist">
          You need Node.js 20+, JDK 17, Android Studio with the SDK, and an Android 10+ phone with USB debugging. The full walkthrough, including first-run permissions and keeping the island alive, is in the{" "}
          <a className="text-lilac underline underline-offset-4" href={SITE.setupDoc} target="_blank" rel="noopener noreferrer">setup guide</a>.
        </p>
        <pre className="glass mt-4 overflow-x-auto rounded-2xl p-4 font-mono text-sm leading-relaxed text-[#e4def8]"><code>{`git clone ${SITE.repo}.git
cd MEOW-Island
npm install
npx expo prebuild --platform android --clean
npx expo run:android`}</code></pre>
      </MotionSection>
    </section>
  );
}
