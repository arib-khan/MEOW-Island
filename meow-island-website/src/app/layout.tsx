import type { Metadata, Viewport } from "next";
import "./globals.css";
import Navbar from "@/components/Navbar";
import Footer from "@/components/Footer";
import JsonLd from "@/components/JsonLd";
import { SITE } from "@/lib/site";

export const metadata: Metadata = {
  metadataBase: new URL(SITE.url),
  title: { default: SITE.title, template: `%s | ${SITE.name}` },
  description: SITE.description,
  applicationName: SITE.name,
  authors: [{ name: SITE.author, url: SITE.repo }],
  keywords: ["MEOW Island", "Android floating island", "dynamic island for Android", "open source Android app", "screen time limit", "step counter", "ESP32 display"],
  alternates: { canonical: "/" },
  robots: { index: true, follow: true },
  formatDetection: { telephone: false, email: false },
  openGraph: {
    type: "website",
    siteName: SITE.name,
    title: SITE.title,
    description: SITE.description,
    url: "/",
    locale: "en_US",
  },
  twitter: { card: "summary_large_image", title: SITE.title, description: SITE.description },
};

export const viewport: Viewport = { themeColor: "#0b0813", colorScheme: "dark" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <JsonLd data={{
          "@context": "https://schema.org",
          "@graph": [
            { "@type": "Organization", "@id": `${SITE.url}/#org`, name: SITE.name, url: SITE.url, logo: `${SITE.url}/icons/icon-512.png`, email: SITE.email.info, sameAs: [SITE.repo], contactPoint: { "@type": "ContactPoint", contactType: "customer support", email: SITE.email.support } },
            { "@type": "WebSite", "@id": `${SITE.url}/#site`, url: SITE.url, name: SITE.name, description: SITE.description, inLanguage: "en", publisher: { "@id": `${SITE.url}/#org` } },
            { "@type": "SoftwareApplication", "@id": `${SITE.url}/#app`, name: SITE.name, description: SITE.description, applicationCategory: "UtilitiesApplication", operatingSystem: "Android", url: SITE.url, image: `${SITE.url}/icons/icon-512.png`, license: SITE.license, codeRepository: SITE.repo, author: { "@type": "Person", name: SITE.author }, publisher: { "@id": `${SITE.url}/#org` } },
          ],
        }} />
        <a href="#main" className="sr-only z-[60] rounded-full bg-lilac px-4 py-2 font-semibold text-ink focus:not-sr-only focus:fixed focus:left-4 focus:top-4">Skip to content</a>
        <Navbar />
        {children}
        <Footer />
      </body>
    </html>
  );
}
