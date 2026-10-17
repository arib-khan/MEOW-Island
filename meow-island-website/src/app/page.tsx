import type { Metadata } from "next";
import Hero from "@/components/Hero";
import Features from "@/components/Features";
import HealthShowcase from "@/components/HealthShowcase";
import HowItWorks from "@/components/HowItWorks";
import PrivacySection from "@/components/PrivacySection";
import FAQ from "@/components/FAQ";
import CommunityCTA from "@/components/CommunityCTA";

export const metadata: Metadata = { alternates: { canonical: "/" } };

export default function Home() {
  return (
    <main id="main">
      <Hero />
      <Features />
      <HealthShowcase />
      <HowItWorks />
      <PrivacySection />
      <FAQ />
      <CommunityCTA />
    </main>
  );
}
