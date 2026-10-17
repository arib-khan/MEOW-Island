# 🐱 Meow Island

<div align="center">

<<<<<<< HEAD
## What this revision adds
- **Overlay fix (root cause documented in docs/HEALTH.md):** default position is now the status-bar band; position is changed *only* in Settings (on-screen dragging removed); position re-fits on rotation, density, nav-mode and cutout changes; overlay revocation handled.
- **Optional Health & Wellness** (new Health tab, all off by default, all local): step counter, walking progress island, daily step goal, contribution heatmap (+ optional 7-day preview on the island), water reminders with full schedule editor, Clear health data.
- Earlier work kept: music buttons, notifications, timers, reminders, Screen Time Guardian (5-minute reminders), customization.

## Test report (honest)
| Check | Result |
|---|---|
| Kotlin compiles against the real Android API 34 jar (RN/AndroidX stubbed) | PASS (0 errors) |
| Kotlin JVM logic tests: step ledger, walking detector, water schedule | PASS (48 checks) |
| TypeScript logic tests (`npm test`): persistence, levels, grid, migration | PASS (12 tests) |
| TypeScript syntax / undefined-name check over the whole import tree | PASS |
| Gradle build / APK | NOT TESTED here (no Android SDK). Build locally |
| Everything on a physical device: overlay placement, step sensor, walking mode, goal celebration, heatmap, water alarms/notifications/island actions, music, timers, reminders, screen time | NOT TESTED |

## Limitations
- TYPE_APPLICATION_OVERLAY always sits below the status-bar icons; system dialogs/lock screen/secure windows stay above overlays.
- Step sensor accuracy and delivery timing vary by phone; walking detection can be delayed. Step features need Physical activity permission and a hardware sensor. Steps near midnight can land on the adjacent day by up to ~30 min.
- The detector fallback counts only while the island service runs (those days are flagged incomplete).
- Water reminders use inexact battery-friendly alarms and can be late; island-only reminders need the island running.
- Full-screen video hiding is a heuristic (see earlier notes). Timers: start/cancel only. Dark theme only. Tab icons are emoji. Reanimated is not used.
- Android can stop the service on aggressive OEMs, after force-stop, or if permission is revoked.
=======
  <h3>A tiny floating island. A little cat energy. More control over your Android.</h3>

  <p>
    <a href="https://github.com/arib-khan/MEOW-Island/stargazers"><img src="https://img.shields.io/github/stars/arib-khan/MEOW-Island?style=for-the-badge&color=ba8cff" alt="GitHub stars"></a>
    <a href="https://github.com/arib-khan/MEOW-Island/forks"><img src="https://img.shields.io/github/forks/arib-khan/MEOW-Island?style=for-the-badge" alt="GitHub forks"></a>
    <a href="https://github.com/arib-khan/MEOW-Island/issues"><img src="https://img.shields.io/github/issues/arib-khan/MEOW-Island?style=for-the-badge" alt="GitHub issues"></a>
    <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android">
    <img src="https://img.shields.io/badge/status-early%20development-orange?style=for-the-badge" alt="Early development">
  </p>

  **Meow Island is an early-stage Android app exploring a customizable floating island for quick, glanceable controls and useful updates.**

  [Explore the repository](https://github.com/arib-khan/MEOW-Island) · [Report a bug](https://github.com/arib-khan/MEOW-Island/issues) · [Request a feature](https://github.com/arib-khan/MEOW-Island/issues)

</div>

---

> [!NOTE]
> **This is a very early, actively developing project.** Some features are implemented in source code but have not yet been fully tested on a physical Android device. Expect bugs, incomplete behavior, and platform-specific limitations. Please read the [known limitations](#-current-status--known-limitations) before trying it.

## ✨ What is Meow Island?

Meow Island is a cat-inspired floating interface for Android, designed to keep useful information and quick actions close without constantly switching between apps.

The goal is simple: make a compact, customizable island that can surface music controls, reminders, timers, notifications, and screen-time feedback—wrapped in a playful, polished UI.

## 🐾 Features

| Feature | What it aims to do | Status |
|---|---|---|
| **Customizable floating island** | Show a compact pill above other apps; adjust position and size, with saved settings | Implemented in source; device testing pending |
| **Drag-to-position editor** | Move the island, use position controls/presets, and reset its location | Implemented in source; device testing pending |
| **Music controls** | Show media information and artwork; control previous, play/pause, and next when supported by the active media session | Implemented in source; player/device testing pending |
| **Animated music artwork** | Rotate artwork while playback is active and stop when playback pauses | Implemented in source; device testing pending |
| **Screen Time Guardian** | Choose installed apps, configure daily limits, and show cat reactions when a limit is exceeded or when you leave the app | Implemented in source; usage-access testing pending |
| **Repeat limit reminders** | Remind you at a configurable interval (default: five minutes) while an app remains over its limit | Implemented in source; timing/foreground-service testing pending |
| **Reminders** | Schedule reminders and surface them through the island and notifications | Present in project; end-to-end testing pending |
| **Timers** | Run countdown timers and show timer state on the island | Basic start/cancel flow; pause/resume not implemented |
| **Notification previews** | Surface notification content when Notification Access is granted | Implemented in source; device testing pending |
| **Hide for full-screen video** | Attempt to hide the island during supported full-screen playback scenarios | Best-effort heuristic; not universally reliable |
| **Cat-inspired look** | Dark UI, cat ears, and expressive 😾 / 😺 feedback | Included in current design |

**Feature status is intentionally conservative:** source-code implementation is not the same as a verified, reliable feature on every device.

## 🎬 Vision

Meow Island is being developed around a few principles:

- **Useful at a glance:** keep the island compact until something needs attention.
- **Your screen, your setup:** personalize its position and dimensions.
- **Playful, not distracting:** use cat expressions and smooth motion with restraint.
- **Native where necessary:** use Android services and APIs for system-level capabilities.
- **Transparent about limitations:** respect Android permissions and platform restrictions.

## 🧰 Tech Stack

- **React Native + Expo**
- **TypeScript**
- **Kotlin** for Android-specific integration
- **Android WindowManager overlay** for the floating island
- **Android media sessions** for playback metadata and supported controls
- **Notification Listener** for notification access
- **UsageStatsManager** for screen-time tracking
- **Expo config plugin** to integrate the native Android components

The Android application ID is currently `com.smartisland.app`. It has been kept unchanged to support updating an existing installation.

## 🚀 Getting Started

Meow Island is currently source-first. A successful build and correct behavior on a physical device still need to be verified in your environment.

### Prerequisites

- Node.js 20 or newer
- JDK 17
- Android Studio and Android SDK (including platform/build tools)
- An Android phone or emulator; a physical phone is recommended for overlay, media-session, notification, and usage-access testing

### Set up

1. Clone the repository:

   ```bash
   git clone https://github.com/arib-khan/MEOW-Island.git
   cd MEOW-Island
   ```

2. Install dependencies:

   ```bash
   npm install
   ```

3. Generate the Android native project:

   ```bash
   npx expo prebuild --platform android --clean
   ```

4. Run on a connected Android device:

   ```bash
   npx expo run:android
   ```

For detailed Windows instructions, Gradle build commands, first-run permissions, and troubleshooting, see **[`docs/SETUP.md`](docs/SETUP.md)**.

### Build an APK

On Windows PowerShell:

```powershell
cd android
.\gradlew.bat assembleRelease
```

On macOS/Linux:

```bash
cd android
./gradlew assembleRelease
```

If the build succeeds, the APK is normally generated at:

```text
android/app/build/outputs/apk/release/app-release.apk
```

The setup guide notes that the default release build may use a debug signing key. **Do not treat that as a production release configuration.** Configure a proper signing key before distributing a public release.

## 🔐 First-run permissions

Meow Island uses Android special access for its system-level features. Grant only the permissions needed for the features you want to use.

1. **Notifications** — for reminder notifications.
2. **Display over other apps** — required for the floating island.
3. **Notification Access** — needed for notification previews and access to media-session information exposed through Android.
4. **Usage Access** — needed for Screen Time Guardian.

After granting access, return to Meow Island and refresh permission status if prompted. Some Android versions and manufacturers add extra restrictions for sideloaded apps or background services.

## ⚠️ Current Status & Known Limitations

Meow Island is in an early development stage, and the following limitations are known:

- **Physical-device testing is incomplete.** Overlay positioning, touch handling, music controls, screen-time behavior, and background reliability still need systematic testing.
- **Full-screen video detection is best-effort.** Android does not expose one universal full-screen-video callback for every app. Portrait video, Shorts/Reels, and different system-bar behaviors may not be detected reliably.
- **Screen-time updates can be delayed.** Android usage events may arrive in batches. Reminder timing is not guaranteed to be exact.
- **Background behavior varies by device.** Battery optimization and manufacturer-specific task management may stop the island service. Force-stopping the app or revoking permissions also affects behavior.
- **Timers are basic.** Pause/resume controls are not implemented in the current version.
- **Design scope is still evolving.** The current interface is dark-theme focused; some polish and setup flows may remain incomplete.
- **An APK is not provided by this README.** Build it locally using the setup guide, and verify the resulting build before installing or sharing it.

These limitations will be updated as features are tested and improved.

## 🧪 Testing Checklist

Testing contributions and device reports are especially helpful. When testing, include your Android version and phone model.

- [ ] Island appears over other apps after the required permission is granted.
- [ ] Dragging and saved position work after restarting the app.
- [ ] Island respects screen edges, display cutouts, and navigation mode.
- [ ] Music metadata updates and supported playback controls affect the real media session.
- [ ] Artwork rotation follows playback state.
- [ ] Notification previews work when access is granted.
- [ ] Screen-time limit behavior updates when entering and leaving a monitored app.
- [ ] Repeated screen-time reminders show current usage and the configured limit.
- [ ] Reminders and timers continue to behave as expected when the app is backgrounded.
- [ ] Full-screen video hiding works on the tested video app and device.

## 🛣️ Roadmap

- [ ] Verify builds and native behavior on physical Android devices.
- [ ] Improve overlay positioning and interaction across screen sizes and navigation modes.
- [ ] Validate media-session behavior with multiple music apps.
- [ ] Improve screen-time tracking, reminder timing, and lifecycle recovery.
- [ ] Test full-screen detection across popular video apps and document compatibility.
- [ ] Refine onboarding, accessibility, and error states.
- [ ] Prepare a reproducible release build and release checklist.

## 🤝 Contributing

Contributions, ideas, bug reports, and constructive feedback are welcome—especially while the project is still young.

1. Check existing [issues](https://github.com/arib-khan/MEOW-Island/issues) before opening a new one.
2. For bugs, include steps to reproduce, expected vs. actual behavior, Android version, and device model.
3. For feature ideas, explain the user problem the idea would solve.
4. For code changes, keep the scope focused and test the affected behavior when possible.

If you are planning a larger change, opening an issue first can help align on the approach.

## ⭐ Support the Project

Meow Island is at a **very early stage**, and there is a lot left to improve.

- ⭐ **Star the repository** if the idea interests you.
- 🐛 Report bugs and share what does not work on your device.
- 💡 Suggest features and UX improvements.
- 🔀 Contribute code or documentation if you would like to help build it.

**A star is a small gesture that helps an early project get noticed. Thank you for checking it out!**

## 📄 License

Meow Island is released under the [MIT License](LICENSE). You may use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the software, subject to the license terms. See the [`LICENSE`](LICENSE) file for the complete text.

---

<div align="center">

  **Made with 🐾 and lots of iteration.**

  [⭐ Star Meow Island](https://github.com/arib-khan/MEOW-Island/stargazers) · [🐛 Report an issue](https://github.com/arib-khan/MEOW-Island/issues)

</div>
>>>>>>> 87e75f3a523dbce9535ff1206082205540a0af4f
