import type { Metadata } from "next";
import LegalLayout from "@/components/LegalLayout";
import { PERMISSIONS } from "@/lib/privacy-data";
import { SITE } from "@/lib/site";

export const metadata: Metadata = {
  title: "Privacy Policy",
  description: "How the MEOW Island Android app handles permissions, notifications, usage and optional health data.",
  alternates: { canonical: "/privacy" },
};

export default function PrivacyPage() {
  return (
    <LegalLayout
      title="Privacy Policy"
      updated={SITE.policyDate}
      callout={<><strong>Draft, not legal advice.</strong> This policy describes the app as it appears in its public source code. It has not been reviewed by a lawyer, has not been checked against a published release build, and is not a legally finalized document. The project maintainer should verify every statement before relying on it.</>}
    >
      <h2>1. Introduction and effective date</h2>
      <p>MEOW Island (&ldquo;the app&rdquo;) is an independent, open-source Android app by {SITE.author}. This page explains what the app can access on your phone, why, and where the information goes. This website is a separate thing: it is a static presentation of the project and its demos are mockups that read nothing from your device.</p>
      <p>Effective date: <strong>not yet set</strong>. This draft was last reviewed on October 18, 2026 and takes effect only once the maintainer publishes a release and sets a date.</p>

      <h2>2. Information the app may process</h2>
      <p>Depending on the features you switch on, the app may process:</p>
      <ul>
        <li>Your own settings: island position, size, colours, animation, and which features are enabled.</li>
        <li>Reminders and timers you create (text, time, repeat rule), and the apps and limits you choose for Screen Time Guardian.</li>
        <li>Media-session details (track title, artist, album art, playback state) from the player you are using.</li>
        <li>The content of notifications that arrive on your phone, in order to show a short preview.</li>
        <li>Which app is in the foreground and for how long, for Screen Time Guardian.</li>
        <li>The battery level, shown on the Meow Round Clock widget.</li>
        <li>Step counts, goals, history and a water log, only if you enable the health features.</li>
      </ul>

      <h2>3. Health and activity information</h2>
      <p>Every health feature is off by default and has its own switch. If you enable step features, the app reads your phone&rsquo;s hardware step counter (or step detector), stores a baseline, and records daily step totals, the goal that was active each day, and heatmap levels. The water-reminder schedule and your Done and Snooze actions are stored as the water log. Per the repository&rsquo;s documentation, none of this is sent to a server, account, cloud sync or analytics service.</p>
      <p>The app is not a medical device and does not give medical advice. Step data can be incomplete: after a reboot, before tracking was enabled, or when Android delays sensor delivery. Such days are flagged rather than guessed.</p>

      <h2>4. Notifications and media information</h2>
      <p>If you grant Notification access, Android lets the app see notifications as they arrive and the media sessions of your players. The app uses these to show previews on the island and to display and control music. In the source code reviewed, this information is used on the device for display and is not transmitted. Granting this access is a significant permission, so grant it only if you want those features.</p>

      <h2>5. Permissions and their purposes</h2>
      <table>
        <thead><tr><th>Permission</th><th>Purpose</th><th>Used for</th><th>How to turn it off</th></tr></thead>
        <tbody>
          {PERMISSIONS.map((p) => (
            <tr key={p.name}><td>{p.name}<br /><span style={{ opacity: 0.6, fontSize: "0.8em" }}>{p.android}</span></td><td>{p.why}</td><td>{p.feature}</td><td>{p.revoke}</td></tr>
          ))}
        </tbody>
      </table>
      <p>Denying a permission only disables the feature that needs it. The app does not use accessibility services, and its own configuration does not request broad health-data, location or all-packages-visibility permissions (see the unverified items in section 7). To list launchable apps for the Screen Time Guardian picker it uses a narrow package-visibility query.</p>

      <h2>6. Local storage and retention</h2>
      <p>Settings, reminders, timers and app limits are stored in a local SQLite database on the phone (<code>island.db</code>). Health settings, step history and the water log are stored in the app&rsquo;s local storage and Android SharedPreferences. Data stays until you delete it, clear the app&rsquo;s storage, or uninstall the app. The step ledger code trims daily step history older than about 400 days; other data is kept until you delete it.</p>

      <h2>7. Data sharing and third-party services</h2>
      <p>The code reviewed contains no analytics, advertising, crash-reporting or account libraries, and no code that makes network requests. The app is built on open-source libraries (Expo, React Native and expo-sqlite, expo-notifications and expo-battery), which run on your device.</p>
      <p><strong>Not yet verified:</strong> (a) whether the final built APK declares the <code>INTERNET</code> permission, which standard Expo and React Native builds often include; (b) the project lists the <code>expo-location</code> dependency although the source does not use it, and a built app may therefore declare location permissions it never asks you to grant. Check the built manifest. If you distribute a build, inspect it and update this section.</p>
      <p>Because the app is open source, you are free to read the code at <a href={SITE.repo} target="_blank" rel="noopener noreferrer">{SITE.repo}</a>. Android itself, your phone maker, and any other app or media player you use have their own privacy practices that this policy does not cover.</p>

      <h2>8. Security considerations</h2>
      <p>Data stays in the app&rsquo;s private storage on your device and is protected by Android&rsquo;s normal app sandbox. No extra encryption layer was found in the source. Anyone with access to an unlocked or rooted phone, or to a backup of it, may be able to read it. Notification and usage access are powerful permissions: grant them only to builds you trust, and prefer builds you compiled yourself from the source.</p>

      <h2>9. Data deletion and permission revocation</h2>
      <ul>
        <li><strong>Turn a feature off</strong> in the app. It stops that feature&rsquo;s work; features are independent.</li>
        <li><strong>Clear health data</strong> (Health tab) permanently deletes step history, contribution records and the water log after confirmation. Your settings are kept.</li>
        <li><strong>Revoke a permission</strong> in Android Settings (see the table above). The related feature stops working.</li>
        <li><strong>Remove everything</strong> by clearing the app&rsquo;s storage or uninstalling it in Android Settings.</li>
      </ul>
      <p>Android may stop background work (for example on aggressive battery-saving phones or after a force stop), and reminders can be delayed.</p>

      <h2>10. Children&rsquo;s privacy considerations</h2>
      <p>The app is not directed at children and does not knowingly collect personal information from anyone, since it has no accounts and sends nothing to a server. Notification access, usage access and step data are sensitive on a child&rsquo;s phone, so a parent or guardian should decide whether to enable them. Whether this app is suitable for a particular age group, or subject to rules such as COPPA or GDPR-K, has not been assessed.</p>

      <h2>11. Policy changes</h2>
      <p>The policy will change as the app changes. Updates will be published on this page with a new review date, and the project history on GitHub shows earlier versions.</p>

      <h2>12. Contact information</h2>
      <p>No dedicated privacy contact exists yet. Please raise privacy questions or concerns as an issue at <a href={SITE.issues} target="_blank" rel="noopener noreferrer">{SITE.issues}</a>. Do not post sensitive personal information in a public issue. <em>[Placeholder: add a dedicated privacy contact here before release.]</em></p>
    </LegalLayout>
  );
}
