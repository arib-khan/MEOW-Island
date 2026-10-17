import Link from "next/link";
import type { ReactNode } from "react";
import { ArrowLeft, TriangleAlert } from "lucide-react";

export default function LegalLayout({ title, updated, children, callout }: { title: string; updated: string; children: ReactNode; callout: ReactNode }) {
  return (
    <main id="main" className="mx-auto max-w-3xl px-5 pb-24 pt-32 sm:px-8">
      <Link href="/" className="inline-flex items-center gap-2 text-sm text-mist hover:text-white"><ArrowLeft size={16} aria-hidden /> Back to MEOW Island</Link>
      <h1 className="mt-6 text-4xl font-extrabold tracking-[-0.03em] sm:text-5xl">{title}</h1>
      <p className="mt-3 text-sm text-mist">{updated}</p>
      <div className="mt-8 flex gap-3 rounded-2xl border border-pink/30 bg-pink/[0.07] p-4 text-sm leading-relaxed text-[#f3d9e6]">
        <TriangleAlert size={18} className="mt-0.5 shrink-0 text-pink" aria-hidden />
        <div>{callout}</div>
      </div>
      <div className="prose-legal mt-4">{children}</div>
    </main>
  );
}
