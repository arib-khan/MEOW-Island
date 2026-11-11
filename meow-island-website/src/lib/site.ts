/**
 * Central site configuration. Everything here is either verified against the
 * app repository (https://github.com/arib-khan/MEOW-Island) or clearly marked.
 */
export const SITE = {
  name: "MEOW Island",
  tagline: "Your phone's cutest companion.",
  title: "MEOW Island: a cat-inspired floating island for Android",
  description:
    "Open-source, cat-inspired floating island for Android with music controls, notification previews, reminders and opt-in wellness tools. Early development.",
  url: (process.env.NEXT_PUBLIC_SITE_URL ?? "https://meow-island.tech").replace(/\/$/, ""),
  email: { info: "info@meow-island.tech", support: "support@meow-island.tech" },
  repo: "https://github.com/arib-khan/MEOW-Island",
  issues: "https://github.com/arib-khan/MEOW-Island/issues",
  releases: "https://github.com/arib-khan/MEOW-Island/releases",
  license: "https://github.com/arib-khan/MEOW-Island/blob/main/LICENSE",
  setupDoc: "https://github.com/arib-khan/MEOW-Island/blob/main/docs/SETUP.md",
  healthDoc: "https://github.com/arib-khan/MEOW-Island/blob/main/docs/HEALTH.md",
  deviceDoc: "https://github.com/arib-khan/MEOW-Island/blob/main/docs/DEVICE.md",
  firmwareDoc: "https://github.com/arib-khan/MEOW-Island/blob/main/firmware/README.md",
  author: "Arib Khan",
  lastModified: "2026-11-09",
  // Draft marker: update when the policy is reviewed against a real release build.
  policyDate: "Draft last reviewed October 18, 2026",
} as const;

export const NAV = [
  { label: "Home", href: "/#home", id: "home" },
  { label: "Features", href: "/#features", id: "features" },
  { label: "Display", href: "/#device", id: "device" },
  { label: "Health", href: "/#health", id: "health" },
  { label: "How It Works", href: "/#how-it-works", id: "how-it-works" },
  { label: "Privacy", href: "/#privacy", id: "privacy" },
  { label: "FAQ", href: "/#faq", id: "faq" },
] as const;
