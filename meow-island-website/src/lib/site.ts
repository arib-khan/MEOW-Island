/**
 * Central site configuration. Everything here is either verified against the
 * app repository (https://github.com/arib-khan/MEOW-Island) or clearly marked.
 */
export const SITE = {
  name: "MEOW Island",
  tagline: "Your phone's cutest companion.",
  description:
    "MEOW Island is a cat-inspired floating island for Android with music controls, notification previews, reminders, a Screen Time Guardian and optional wellness tools. An open-source project in development.",
  // Placeholder until a production domain exists. Set NEXT_PUBLIC_SITE_URL in Vercel.
  url: (process.env.NEXT_PUBLIC_SITE_URL ?? "https://meow-island.example.com").replace(/\/$/, ""),
  repo: "https://github.com/arib-khan/MEOW-Island",
  issues: "https://github.com/arib-khan/MEOW-Island/issues",
  license: "https://github.com/arib-khan/MEOW-Island/blob/main/LICENSE",
  setupDoc: "https://github.com/arib-khan/MEOW-Island/blob/main/docs/SETUP.md",
  healthDoc: "https://github.com/arib-khan/MEOW-Island/blob/main/docs/HEALTH.md",
  author: "Arib Khan",
  // Draft marker: update when the policy is reviewed against a real release build.
  policyDate: "Draft last reviewed October 18, 2026",
} as const;

export const NAV = [
  { label: "Home", href: "/#home", id: "home" },
  { label: "Features", href: "/#features", id: "features" },
  { label: "Health", href: "/#health", id: "health" },
  { label: "How It Works", href: "/#how-it-works", id: "how-it-works" },
  { label: "Privacy", href: "/#privacy", id: "privacy" },
  { label: "FAQ", href: "/#faq", id: "faq" },
] as const;
