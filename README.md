# Meow Island (Android)

A cat-themed floating island for Android. Expo + React Native (TypeScript) for the app, Kotlin only for system integration
(overlay service, media sessions, notification listener, usage stats). Android package id is unchanged
(`com.smartisland.app`) so installs update in place. See `docs/SETUP.md` to build.

## What this revision adds
- **Overlay fix (root cause documented in docs/HEALTH.md):** default position is now the status-bar band; position is changed *only* in Settings (on-screen dragging removed); position re-fits on rotation, density, nav-mode and cutout changes; overlay revocation handled.
- **Optional Health & Wellness** (new Health tab, all off by default, all local): step counter, walking progress island, daily step goal, contribution heatmap (+ optional 7-day preview on the island), water reminders with full schedule editor, Clear health data.
- **Home-screen widgets** (Meow Clock, Meow Round Clock, Meow Step Heatmap): see docs/HEALTH.md. Not yet seen on a launcher.
- Earlier work kept: music buttons, notifications, timers, reminders, Screen Time Guardian (5-minute reminders), customization.

## Test report (honest)
| Check | Result |
|---|---|
| Kotlin compiles against the real Android API 34 jar (RN/AndroidX stubbed) | PASS (0 errors) |
| Kotlin JVM logic tests: step ledger, walking detector, water schedule, widget heat grid | PASS (55 checks) |
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
