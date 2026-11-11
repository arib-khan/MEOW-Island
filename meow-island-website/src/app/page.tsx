import type { Metadata } from "next";
import { SITE } from "@/lib/site";
import Hero from "@/components/Hero";
import Features from "@/components/Features";
import Device from "@/components/Device";
import HealthShowcase from "@/components/HealthShowcase";
import HowItWorks from "@/components/HowItWorks";
import PrivacySection from "@/components/PrivacySection";
import FAQ from "@/components/FAQ";
import CommunityCTA from "@/components/CommunityCTA";

export const metadata: Metadata = {
  title: { absolute: SITE.title },
  alternates: { canonical: "/" },
};

export default function Home() {
  return (
    <main id="main">
      <Hero />
      <Features />
      <Device />
      <HealthShowcase />
      <HowItWorks />
      <PrivacySection />
      <FAQ />
      <CommunityCTA />
    </main>
  );
}
