"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { Menu, X, Github } from "lucide-react";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { NAV, SITE } from "@/lib/site";

export default function Navbar() {
  const pathname = usePathname();
  const onHome = pathname === "/";
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState("home");
  const reduce = useReducedMotion();

  useEffect(() => {
    if (!onHome) return;
    let raf = 0;
    // The active item is the last section whose top has passed 40% of the viewport.
    const update = () => {
      raf = 0;
      const line = window.innerHeight * 0.4;
      let current: string = NAV[0].id;
      for (const n of NAV) {
        const el = document.getElementById(n.id);
        if (el && el.getBoundingClientRect().top <= line) current = n.id;
      }
      setActive(current);
    };
    const onScroll = () => { if (!raf) raf = requestAnimationFrame(update); };
    update();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll);
    return () => { window.removeEventListener("scroll", onScroll); window.removeEventListener("resize", onScroll); if (raf) cancelAnimationFrame(raf); };
  }, [onHome]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  const link = (id: string, label: string, href: string, mobile = false) => {
    const current = onHome && active === id;
    return (
      <Link
        key={id}
        href={href}
        onClick={() => setOpen(false)}
        aria-current={current ? "location" : undefined}
        className={
          mobile
            ? `block rounded-xl px-4 py-3 text-lg ${current ? "bg-white/10 text-white" : "text-mist hover:bg-white/5 hover:text-white"}`
            : `relative rounded-full px-3.5 py-2 text-sm transition-colors ${current ? "text-white" : "text-mist hover:text-white"}`
        }
      >
        {!mobile && current && <span className="absolute inset-0 -z-10 rounded-full bg-white/10 ring-1 ring-lilac/30" />}
        {label}
      </Link>
    );
  };

  return (
    <header className="fixed inset-x-0 top-0 z-50 px-3 pt-3 sm:px-6">
      <nav aria-label="Primary" className="glass mx-auto flex max-w-6xl items-center justify-between rounded-full py-2 pl-4 pr-2">
        <Link href="/#home" className="flex items-center gap-2.5 font-bold tracking-tight" aria-label="MEOW Island home">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/logo.svg" alt="" width={28} height={28} className="rounded-lg" />
          <span>MEOW Island</span>
        </Link>
        <div className="hidden items-center gap-0.5 lg:flex">{NAV.map((n) => link(n.id, n.label, n.href))}</div>
        <div className="flex items-center gap-1.5">
          <a
            href={SITE.repo}
            target="_blank"
            rel="noopener noreferrer"
            className="hidden items-center gap-2 rounded-full bg-lilac px-4 py-2 text-sm font-semibold text-ink transition hover:bg-white sm:inline-flex"
          >
            <Github size={16} aria-hidden /> GitHub
          </a>
          <button
            type="button"
            className="inline-flex h-10 w-10 items-center justify-center rounded-full text-white hover:bg-white/10 lg:hidden"
            aria-expanded={open}
            aria-controls="mobile-menu"
            aria-label={open ? "Close menu" : "Open menu"}
            onClick={() => setOpen((o) => !o)}
          >
            {open ? <X size={22} /> : <Menu size={22} />}
          </button>
        </div>
      </nav>
      <AnimatePresence>
        {open && (
          <motion.div
            id="mobile-menu"
            initial={reduce ? false : { opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={reduce ? undefined : { opacity: 0, y: -8 }}
            transition={{ duration: 0.18 }}
            className="glass mx-auto mt-2 max-w-6xl rounded-3xl p-2 lg:hidden"
          >
            {NAV.map((n) => link(n.id, n.label, n.href, true))}
            <a href={SITE.repo} target="_blank" rel="noopener noreferrer" className="mt-1 flex items-center justify-center gap-2 rounded-2xl bg-lilac px-4 py-3 font-semibold text-ink">
              <Github size={18} aria-hidden /> View on GitHub
            </a>
          </motion.div>
        )}
      </AnimatePresence>
    </header>
  );
}
