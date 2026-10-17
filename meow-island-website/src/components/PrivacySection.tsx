import Link from "next/link";
import { ShieldCheck, TriangleAlert } from "lucide-react";
import MotionSection from "./MotionSection";
import { PERMISSIONS } from "@/lib/privacy-data";

export default function PrivacySection() {
  return (
    <section id="privacy" className="mx-auto max-w-6xl px-5 py-24 sm:px-8">
      <div className="grid gap-12 lg:grid-cols-[0.85fr_1.15fr]">
        <MotionSection>
          <h2 className="text-4xl font-extrabold tracking-[-0.03em] sm:text-5xl">Clear about what it asks for.</h2>
          <p className="mt-5 leading-relaxed text-[#e4def8]">
            MEOW Island needs some powerful Android permissions to do its job, so here is each one in plain language. You grant them one at a time, each unlocks one feature, and you can take any of them back.
          </p>
          <div className="mt-6 space-y-4 text-sm leading-relaxed text-mist">
            <p className="flex gap-3"><ShieldCheck size={18} className="mt-0.5 shrink-0 text-lilac" aria-hidden /> In the app&rsquo;s source, settings, reminders, timers, limits and health history are stored on the phone (a local database and Android preferences). A review of the repository found no account system, analytics, advertising or network-request code.</p>
            <p className="flex gap-3"><TriangleAlert size={18} className="mt-0.5 shrink-0 text-pink" aria-hidden /> That is a reading of the code, not a guarantee. Standard React Native and Expo builds can add permissions of their own, and the app has not been audited. Check the built manifest before relying on it.</p>
            <p>Health features are optional and off by default. <em className="not-italic text-white">Clear health data</em> in the Health tab deletes step history, contribution records and the water log. Android can still delay or stop background work.</p>
          </div>
          <Link href="/privacy" className="mt-8 inline-flex rounded-full border border-lilac/40 px-5 py-3 font-semibold hover:bg-white/5">Read the full Privacy Policy</Link>
        </MotionSection>
        <MotionSection delay={0.1}>
          <ul className="glass divide-y divide-lilac/10 overflow-hidden rounded-[28px]">
            {PERMISSIONS.map((p) => (
              <li key={p.name} className="p-5">
                <p className="font-semibold text-white">{p.name} <span className="ml-1 text-xs font-normal text-mist">for {p.feature.toLowerCase()}</span></p>
                <p className="mt-1.5 text-sm leading-relaxed text-[#cfc8e6]">{p.why}</p>
                <p className="mt-1.5 text-xs text-mist">Turn off: {p.revoke}</p>
              </li>
            ))}
          </ul>
        </MotionSection>
      </div>
    </section>
  );
}
