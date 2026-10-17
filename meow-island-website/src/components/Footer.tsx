import Link from "next/link";
import { SITE } from "@/lib/site";
import { PawPrint } from "./CatFace";

export default function Footer() {
  const year = new Date().getFullYear();
  return (
    <footer className="relative border-t border-lilac/10 px-5 pb-10 pt-14 sm:px-8">
      <div className="mx-auto grid max-w-6xl gap-10 md:grid-cols-[1.4fr_1fr_1fr]">
        <div>
          <div className="flex items-center gap-2.5 text-lg font-bold">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/logo.svg" alt="" width={32} height={32} className="rounded-lg" />
            MEOW Island
          </div>
          <p className="mt-3 max-w-sm text-sm leading-relaxed text-mist">
            A cat-inspired floating island for Android. Open source, in development, and built one paw at a time.
          </p>
        </div>
        <nav aria-label="Project" className="text-sm">
          <h2 className="mb-3 font-semibold text-white">Project</h2>
          <ul className="space-y-2 text-mist">
            <li><a className="hover:text-white" href={SITE.repo} target="_blank" rel="noopener noreferrer">GitHub repository</a></li>
            <li><a className="hover:text-white" href={SITE.issues} target="_blank" rel="noopener noreferrer">Support and issues</a></li>
            <li><a className="hover:text-white" href={SITE.setupDoc} target="_blank" rel="noopener noreferrer">Setup guide</a></li>
          </ul>
        </nav>
        <nav aria-label="Legal" className="text-sm">
          <h2 className="mb-3 font-semibold text-white">Legal</h2>
          <ul className="space-y-2 text-mist">
            <li><Link className="hover:text-white" href="/privacy">Privacy Policy</Link></li>
            <li><Link className="hover:text-white" href="/terms">Terms of Use</Link></li>
            <li><a className="hover:text-white" href={SITE.license} target="_blank" rel="noopener noreferrer">MIT License</a></li>
          </ul>
        </nav>
      </div>
      <div className="mx-auto mt-10 flex max-w-6xl flex-col gap-3 border-t border-lilac/10 pt-6 text-xs leading-relaxed text-mist/80 sm:flex-row sm:items-start sm:justify-between">
        <p className="max-w-2xl">
          MEOW Island is an independent open-source project. It is not affiliated with, endorsed by or sponsored by Google, Android, Apple or any music, health or social-media service. Android is a trademark of Google LLC.
        </p>
        <p className="flex shrink-0 items-center gap-2"><PawPrint className="h-4 w-4 text-lilac/60" /> © {year} {SITE.author}</p>
      </div>
    </footer>
  );
}
