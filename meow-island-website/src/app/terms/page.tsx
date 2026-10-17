import type { Metadata } from "next";
import LegalLayout from "@/components/LegalLayout";
import { SITE } from "@/lib/site";

export const metadata: Metadata = {
  title: "Terms of Use",
  description: "Plain-language terms for the MEOW Island open-source Android project and this website.",
  alternates: { canonical: "/terms" },
};

export default function TermsPage() {
  return (
    <LegalLayout
      title="Terms of Use"
      updated={SITE.policyDate}
      callout={<><strong>Draft, not legal advice.</strong> These terms are written in plain language for a hobby open-source project. They have not been reviewed by a lawyer and do not guarantee legal compliance in any country.</>}
    >
      <h2>1. Project status and feature availability</h2>
      <p>MEOW Island is an independent project in development. It is distributed as source code, has no Google Play listing, and the repository states that it has not been fully tested on physical devices. Features may be missing, change, or break without notice.</p>

      <h2>2. Device compatibility</h2>
      <p>The project targets Android 10 or newer, but compatibility has not been verified across phones, Android versions or manufacturers. Overlays, sensors, alarms and background services behave differently from phone to phone.</p>

      <h2>3. Android permission requirements</h2>
      <p>Features depend on permissions you grant: display over other apps, notification access, usage access, physical activity and notifications. Without a permission the matching feature will not work. Android may revoke permissions, limit background work, or keep system screens above the island. See the <a href="/privacy">Privacy Policy</a> for details.</p>

      <h2>4. General wellness disclaimer</h2>
      <p>Step counts, goals, walking detection, water reminders and screen-time reactions are for general interest and motivation only. They can be inaccurate, incomplete or late.</p>

      <h2>5. No medical advice</h2>
      <p>MEOW Island is not a medical device and does not provide medical, health or hydration advice. Talk to a qualified professional before changing your exercise, diet or fluid intake, especially if you have a medical condition.</p>

      <h2>6. Responsible use</h2>
      <p>Use the app lawfully and sensibly. Do not rely on a reminder or timer for anything safety-critical, such as medication, driving or emergencies. Install builds only from sources you trust, since notification and usage access are powerful permissions.</p>

      <h2>7. Open-source licensing</h2>
      <p>The app&rsquo;s source code is released under the <a href={SITE.license} target="_blank" rel="noopener noreferrer">MIT License</a>, copyright {SITE.author}. The license text in the repository governs your rights to use, copy, modify and distribute the code. The name and the cat artwork on this site are not given any separate license, so please ask via an issue if you want to reuse them.</p>

      <h2>8. Service availability and disclaimers</h2>
      <p>The app and this website are provided &ldquo;as is&rdquo;, without warranty of any kind, as set out in the MIT License. There is no guaranteed uptime, support or fixes. To the extent the law allows, the author is not liable for losses arising from using the app or the site, including missed reminders or inaccurate counts. Nothing here limits rights you have by law that cannot be waived.</p>
      <p>MEOW Island is not affiliated with Google, Android, or any music, health or social-media service. Product names belong to their owners.</p>

      <h2>9. Changes to the terms</h2>
      <p>These terms may change as the project changes. The current version is always on this page, and earlier versions are in the GitHub history. Continuing to use the app after a change means you accept the updated terms.</p>

      <h2>10. Contact and support</h2>
      <p>Support is community-based. Report bugs, ask questions or request features at <a href={SITE.issues} target="_blank" rel="noopener noreferrer">{SITE.issues}</a>. No dedicated support email exists yet. <em>[Placeholder: add one here if it becomes available.]</em></p>
    </LegalLayout>
  );
}
