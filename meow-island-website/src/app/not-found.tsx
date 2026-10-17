import Link from "next/link";
import CatFace from "@/components/CatFace";

export default function NotFound() {
  return (
    <main id="main" className="mx-auto flex min-h-[80svh] max-w-xl flex-col items-center justify-center px-6 pt-24 text-center">
      <CatFace mood="angry" className="h-28 w-28 drop-shadow-[0_0_40px_rgb(255_120_170/0.4)]" title="A grumpy cat" />
      <h1 className="mt-6 text-5xl font-extrabold tracking-tight">404</h1>
      <p className="mt-3 text-lg text-mist">The cat looked everywhere and this page isn&rsquo;t on the island.</p>
      <Link href="/" className="mt-8 rounded-full bg-lilac px-6 py-3 font-semibold text-ink hover:bg-white">Back to the island</Link>
    </main>
  );
}
