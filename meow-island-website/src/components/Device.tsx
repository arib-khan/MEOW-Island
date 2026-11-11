import { Bluetooth, Cpu, KeyRound, LayoutGrid, Wrench } from "lucide-react";
import DeviceViewer from "./DeviceViewer";
import MotionSection from "./MotionSection";
import { SITE } from "@/lib/site";

const SCREENS = ["Clock", "Steps", "Music", "Screen time", "Notifications", "Custom"];

const POINTS = [
  { icon: Bluetooth, t: "Fed by the app over Bluetooth LE", d: "The phone sends small frames; the display draws everything itself. Time is set by the phone, so Wi-Fi is optional." },
  { icon: KeyRound, t: "Paired with a code on its own screen", d: "Pairing opens a 6-digit passkey on the display that you type into Android. The link is encrypted and authenticated." },
  { icon: LayoutGrid, t: "Six screens, one button", d: "The BOOT button cycles screens. The display is not touch, so playback buttons stay on the phone." },
];

export default function Device() {
  return (
    <section id="device" aria-labelledby="device-title" className="mx-auto max-w-6xl px-5 py-24 sm:px-8">
      <div className="grid items-start gap-12 lg:grid-cols-[1fr_1fr]">
        <MotionSection>
          <p className="inline-flex items-center gap-2 text-sm font-semibold text-lilac"><Cpu size={16} aria-hidden /> Optional companion display</p>
          <h2 id="device-title" className="mt-3 text-4xl font-extrabold tracking-[-0.03em] sm:text-5xl">Put your steps and music on the desk.</h2>
          <p className="mt-5 text-lg leading-relaxed text-[#e4def8]">
            The repository also includes firmware for a small ESP32 with a 2.8&quot; 240&times;320 colour display that mirrors the clock, step goals and now-playing from the Android app.
          </p>
          <ul className="mt-8 space-y-5">
            {POINTS.map(({ icon: Icon, t, d }) => (
              <li key={t} className="flex gap-4">
                <span className="mt-0.5 grid h-10 w-10 shrink-0 place-items-center rounded-2xl bg-violet/20 text-lilac ring-1 ring-lilac/25"><Icon size={18} aria-hidden /></span>
                <div><h3 className="font-semibold">{t}</h3><p className="mt-1 text-sm leading-relaxed text-mist">{d}</p></div>
              </li>
            ))}
          </ul>
        </MotionSection>

        <MotionSection delay={0.08} className="lg:sticky lg:top-28">
          <DeviceViewer />
        </MotionSection>
      </div>

      <div className="mt-10 grid gap-5 lg:grid-cols-2">
        <MotionSection delay={0.08} className="contents">
          <div className="glass rounded-[28px] p-6">
            <h3 className="text-lg font-bold">Screens on the display</h3>
            <ul className="mt-4 flex flex-wrap gap-2">
              {SCREENS.map((s) => <li key={s} className="rounded-full border border-lilac/25 bg-white/5 px-3.5 py-1.5 text-sm text-[#e4def8]">{s}</li>)}
            </ul>
            <p className="mt-4 text-sm leading-relaxed text-mist">The Custom screen shows a design you send from the app; it is skipped until one is stored.</p>
          </div>
          <div className="rounded-[28px] border border-pink/30 bg-pink/[0.06] p-6">
            <h3 className="flex items-center gap-2 text-lg font-bold text-[#f6dce9]"><Wrench size={18} aria-hidden /> Honest status</h3>
            <ul className="mt-3 space-y-2.5 text-sm leading-relaxed text-[#f0d6e4]">
              <li>A build-it-yourself project, not a product you can buy.</li>
              <li>Firmware tests run on a computer, not yet on real hardware, and the 3D-printable enclosure is a design that still has assumed measurements to confirm.</li>
              <li>Works with the Android app only. There is no iOS version. Non-Latin song titles are dropped on the display (lyrics are rendered by the phone).</li>
            </ul>
            <p className="mt-4 text-sm">
              <a className="text-lilac underline underline-offset-4 hover:text-white" href={SITE.deviceDoc} target="_blank" rel="noopener noreferrer">Device notes</a>
              <span className="text-mist"> · </span>
              <a className="text-lilac underline underline-offset-4 hover:text-white" href={SITE.firmwareDoc} target="_blank" rel="noopener noreferrer">Firmware guide</a>
            </p>
          </div>
        </MotionSection>
      </div>
    </section>
  );
}
