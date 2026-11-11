"use client";

import { useState, useId } from "react";
import { ChevronDown } from "lucide-react";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import MotionSection from "./MotionSection";
import JsonLd from "./JsonLd";

const ITEMS: { q: string; a: string }[] = [
  { q: "What is MEOW Island?", a: "A cat-themed floating island for Android: a small pill that sits above other apps and shows music controls, notification previews, reminders, a Screen Time Guardian and optional step and water tools. It is an open-source project written with Expo and React Native (TypeScript), with Kotlin for the Android system parts." },
  { q: "Is the app available on Google Play yet?", a: "No. There is no Google Play listing, so for now it is source code you build yourself using the setup guide. The repository's own notes say it has not yet been fully tested on physical devices, so expect rough edges." },
  { q: "Which Android versions are supported?", a: "The setup guide targets phones running Android 10 or newer, and the step-counting permission applies from Android 10. The exact minimum version has not been verified, and some behaviour differs by phone maker." },
  { q: "Why does the app need overlay permission?", a: "The island is a window drawn above other apps, and Android only allows that after you turn on Display over other apps for it. Without that permission the island cannot be shown. You can turn it off again in Android Settings at any time." },
  { q: "Can the island appear above every Android screen?", a: "No. Overlays sit below the status-bar icons, and system dialogs, the lock screen and secure windows stay above them. The app deliberately avoids accessibility or privileged tricks to get around that." },
  { q: "Does the step counter work on every phone?", a: "No. It needs a hardware step counter, or a step detector as a fallback. The fallback only counts while the island is running, and those days are flagged as incomplete. With neither sensor, step tracking shows as unavailable. Android may also deliver readings late." },
  { q: "Are health features optional?", a: "Yes. Step tracking, the walking island, the heatmap and water reminders are all off by default, each with its own switch." },
  { q: "Does step history require an account?", a: "No. The app has no account system. Step history is kept in local storage on the phone, and you can delete it with Clear health data." },
  { q: "Can I disable water reminders?", a: "Yes. They are off until you enable them, you choose the schedule, and turning them off cancels the alarms. Reminders can arrive late when Android is saving battery." },
  { q: "What is the MEOW Island display?", a: "An optional ESP32 board with a 2.8-inch colour screen that shows a clock, step goals, music and more, fed by the Android app over Bluetooth LE. The firmware is in the repository for you to flash yourself. It is not sold as a product, and it has not yet been verified on real hardware." },
  { q: "How can I follow progress or get updates?", a: "Watch or star the GitHub repository and check its Releases page. There is no newsletter yet. For anything else, email info@meow-island.tech, or support@meow-island.tech for help. Replies are best-effort." },
  { q: "How can I report a bug or request a feature?", a: "Open an issue on GitHub and say which phone and Android version you use, since overlay and sensor behaviour varies a lot. You can also email support@meow-island.tech." },
];

export default function FAQ() {
  const [open, setOpen] = useState<number | null>(0);
  const reduce = useReducedMotion();
  const base = useId();
  return (
    <section id="faq" aria-labelledby="faq-title" className="mx-auto max-w-3xl px-5 py-24 sm:px-8">
      <JsonLd data={{ "@context": "https://schema.org", "@type": "FAQPage", mainEntity: ITEMS.map((i) => ({ "@type": "Question", name: i.q, acceptedAnswer: { "@type": "Answer", text: i.a } })) }} />
      <MotionSection>
        <h2 id="faq-title" className="text-4xl font-extrabold tracking-[-0.03em] sm:text-5xl">Questions, answered honestly.</h2>
      </MotionSection>
      <MotionSection className="glass mt-10 divide-y divide-lilac/10 overflow-hidden rounded-[28px]" delay={0.05}>
        {ITEMS.map((it, i) => {
          const isOpen = open === i;
          return (
            <div key={it.q}>
              <h3>
                <button type="button" id={`${base}-b${i}`} aria-expanded={isOpen} aria-controls={`${base}-p${i}`} onClick={() => setOpen(isOpen ? null : i)} className="flex w-full items-center justify-between gap-4 px-5 py-4 text-left font-semibold hover:bg-white/[0.03] sm:px-6">
                  {it.q}
                  <ChevronDown size={18} aria-hidden className={`shrink-0 text-lilac transition-transform ${isOpen ? "rotate-180" : ""}`} />
                </button>
              </h3>
              <AnimatePresence initial={false}>
                {isOpen && (
                  <motion.div id={`${base}-p${i}`} role="region" aria-labelledby={`${base}-b${i}`} initial={reduce ? false : { height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={reduce ? undefined : { height: 0, opacity: 0 }} transition={{ duration: reduce ? 0 : 0.25 }} className="overflow-hidden">
                    <p className="px-5 pb-5 leading-relaxed text-[#cfc8e6] sm:px-6">{it.a}</p>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          );
        })}
      </MotionSection>
    </section>
  );
}
