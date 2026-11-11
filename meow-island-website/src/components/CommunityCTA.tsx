import { Bell, Bug, GitFork, Mail, Star } from "lucide-react";
import Image from "next/image";
import MotionSection from "./MotionSection";
import { SITE } from "@/lib/site";

const STATUS = [
  ["Google Play", "Not listed yet"],
  ["Install", "Build from source"],
  ["Platform", "Android 10+ (per setup guide)"],
  ["Hardware testing", "Still in progress"],
] as const;

export default function CommunityCTA() {
  return (
    <section id="community" aria-labelledby="community-title" className="mx-auto max-w-6xl px-5 pb-28 pt-12 sm:px-8">
      <MotionSection className="neon-edge relative overflow-hidden rounded-[36px] bg-gradient-to-br from-[#241848] via-[#160f2b] to-[#0f0a1c] p-6 sm:p-12">
        <div className="grid items-center gap-10 lg:grid-cols-[1.1fr_0.9fr]">
          <div>
            <Image src="/images/app-icon.png" alt="MEOW Island app icon" width={64} height={64} className="h-16 w-16 rounded-2xl" />
            <h2 id="community-title" className="mt-5 text-4xl font-extrabold tracking-[-0.03em] sm:text-5xl">Availability and updates</h2>
            <p className="mt-4 max-w-xl text-lg leading-relaxed text-[#d6d0ea]">
              MEOW Island is not on Google Play yet. Today it is source code you can build, test and improve. Watch the repository to follow releases, and tell us how it behaves on your phone.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <a href={SITE.repo} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-full bg-lilac px-6 py-3.5 font-semibold text-ink transition hover:bg-white"><Star size={18} aria-hidden /> Star on GitHub</a>
              <a href={SITE.releases} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-full border border-lilac/40 px-6 py-3.5 font-semibold transition hover:bg-white/5"><Bell size={18} aria-hidden /> Watch releases</a>
              <a href={SITE.issues} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-full border border-lilac/40 px-6 py-3.5 font-semibold transition hover:bg-white/5"><Bug size={18} aria-hidden /> Report an issue</a>
            </div>
            <p className="mt-6 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-mist">
              <Mail size={16} aria-hidden className="text-lilac" />
              <a className="underline underline-offset-4 hover:text-white" href={`mailto:${SITE.email.info}`}>{SITE.email.info}</a>
              <span aria-hidden>·</span>
              <a className="underline underline-offset-4 hover:text-white" href={`mailto:${SITE.email.support}`}>{SITE.email.support}</a>
            </p>
          </div>
          <dl className="glass divide-y divide-lilac/10 overflow-hidden rounded-[24px]">
            {STATUS.map(([k, v]) => (
              <div key={k} className="flex items-baseline justify-between gap-4 px-5 py-4 text-sm">
                <dt className="text-mist">{k}</dt>
                <dd className="text-right font-semibold text-white">{v}</dd>
              </div>
            ))}
          </dl>
        </div>
      </MotionSection>

      <MotionSection className="mx-auto mt-14 max-w-3xl">
        <h3 className="flex items-center gap-2 text-2xl font-bold"><GitFork size={22} className="text-lilac" aria-hidden /> Run it yourself</h3>
        <p className="mt-2 text-mist">
          You need Node.js 20+, JDK 17, Android Studio with the SDK, and an Android 10+ phone with USB debugging. The full walkthrough, including first-run permissions and keeping the island alive, is in the{" "}
          <a className="text-lilac underline underline-offset-4" href={SITE.setupDoc} target="_blank" rel="noopener noreferrer">setup guide</a>.
        </p>
        <pre className="glass mt-4 overflow-x-auto rounded-2xl p-4 font-mono text-sm leading-relaxed text-[#e4def8]" tabIndex={0}><code>{`git clone ${SITE.repo}.git
cd MEOW-Island
npm install
npx expo prebuild --platform android --clean
npx expo run:android`}</code></pre>
      </MotionSection>
    </section>
  );
}
