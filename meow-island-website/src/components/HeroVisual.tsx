import CatFace from "./CatFace";

/**
 * Hero illustration: a phone with the island expanded, next to the optional ESP32 display.
 * Pure HTML/CSS (server-rendered, no JavaScript, no image request) so it paints immediately.
 * Track, time and step numbers are placeholders; this is an illustration, not a product photo.
 */
export default function HeroVisual() {
  return (
    <figure className="relative mx-auto w-full max-w-[460px]" aria-label="Illustration of the MEOW Island app on a phone, next to the optional desk display">
      <div className="pointer-events-none absolute inset-x-8 top-10 h-72 rounded-full bg-violet/15 blur-3xl" aria-hidden />

      {/* Phone */}
      <div className="relative mx-auto h-[420px] w-[212px] rounded-[38px] border border-lilac/25 bg-[#0e0a1a] p-2 shadow-[0_30px_60px_-30px_rgb(0_0_0/0.9)] sm:h-[480px] sm:w-[242px] lg:-translate-x-14">
        <div className="relative h-full overflow-hidden rounded-[30px] bg-gradient-to-b from-[#1a1330] to-[#0b0813]">
          <div className="mx-auto mt-3 flex w-[88%] items-center gap-2.5 rounded-full bg-black px-2.5 py-1.5 ring-1 ring-lilac/40">
            <span className="disc-spin grid h-7 w-7 shrink-0 place-items-center rounded-full bg-gradient-to-br from-violet to-magenta">
              <span className="h-2 w-2 rounded-full bg-black" />
            </span>
            <span className="min-w-0 flex-1 leading-tight">
              <span className="block truncate text-[10px] font-semibold text-white">Sample track</span>
              <span className="block truncate text-[9px] text-mist">Sample artist</span>
            </span>
            <CatFace className="h-5 w-5 shrink-0" />
          </div>
          <div className="mt-6 space-y-2.5 px-4" aria-hidden>
            {[78, 54, 66, 40].map((w, i) => (
              <div key={i} className="h-2.5 rounded-full bg-white/[0.07]" style={{ width: `${w}%` }} />
            ))}
            <div className="mt-4 h-24 rounded-2xl bg-white/[0.05] ring-1 ring-white/[0.06]" />
            <div className="h-2.5 w-1/2 rounded-full bg-white/[0.07]" />
            <div className="h-2.5 w-3/4 rounded-full bg-white/[0.07]" />
          </div>
        </div>
      </div>

      {/* Optional ESP32 display */}
      <div className="absolute -bottom-3 right-0 w-[150px] rounded-[22px] border border-lilac/25 bg-[#15102a] p-2 shadow-[0_24px_50px_-24px_rgb(0_0_0/0.9)] sm:w-[168px] lg:-right-2">
        <div className="rounded-[14px] bg-black px-3 pb-3 pt-4 text-center ring-1 ring-lilac/20">
          <p className="font-mono text-3xl font-bold tracking-tight text-lilac">10:24</p>
          <p className="mt-1 font-mono text-[10px] uppercase tracking-[0.18em] text-mist">Mon 09 Nov</p>
          <div className="mx-auto mt-3 h-1 w-3/4 rounded-full bg-white/10"><div className="h-full w-3/5 rounded-full bg-lilac" /></div>
        </div>
        <figcaption className="mt-2 text-center text-[10px] leading-tight text-mist">Optional ESP32 display</figcaption>
      </div>
      <p className="sr-only">Placeholder values are shown. This is an illustration, not a photo of a finished product.</p>
    </figure>
  );
}
