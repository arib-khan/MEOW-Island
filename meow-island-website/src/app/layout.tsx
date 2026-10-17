import type { Metadata, Viewport } from "next";
import "./globals.css";
import Navbar from "@/components/Navbar";
import Footer from "@/components/Footer";
import { SITE } from "@/lib/site";

export const metadata: Metadata = {
  metadataBase: new URL(SITE.url),
  title: { default: `${SITE.name}: ${SITE.tagline}`, template: `%s | ${SITE.name}` },
  description: SITE.description,
  applicationName: SITE.name,
  authors: [{ name: SITE.author, url: SITE.repo }],
  keywords: ["MEOW Island", "Android floating island", "dynamic island Android", "open source", "screen time", "step counter"],
  openGraph: {
    type: "website",
    siteName: SITE.name,
    title: `${SITE.name}: ${SITE.tagline}`,
    description: SITE.description,
    url: "/",
  },
  twitter: { card: "summary_large_image", title: `${SITE.name}: ${SITE.tagline}`, description: SITE.description },
};

export const viewport: Viewport = { themeColor: "#0b0813", colorScheme: "dark" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <a href="#main" className="sr-only z-[60] rounded-full bg-lilac px-4 py-2 font-semibold text-ink focus:not-sr-only focus:fixed focus:left-4 focus:top-4">Skip to content</a>
        <Navbar />
        {children}
        <Footer />
      </body>
    </html>
  );
}
