# VYBE — Smart Island (Android)

Expo + React Native (TypeScript) UI, Kotlin for the system integration. See `docs/SETUP.md` to build.

## What changed in this revision (root causes fixed)
| Problem | Root cause | Fix |
|---|---|---|
| Island never shown / Start & Stop did nothing | `IslandModule` was not registered in `MainApplication`; plugin regex did not match SDK 52's `val packages = PackageList(this).packages` form. Start also never confirmed the service actually ran. | Plugin now handles both forms and fails the prebuild loudly if it cannot register. `startIsland` rejects with reasons; UI polls `isRunning()` and shows `lastError`. Service guards against duplicates, reports state events, supports cutouts/insets, tap + swipe, animated resize. |
| Screen time / emojis never worked in other apps | Guardian and the activity arbiter were **JS timers**; React Native pauses them when the app leaves the foreground. | Guardian, arbiter and expiry run **natively** inside the foreground service (`IslandBus`, `Guardian`). |
| Typing package names | No app picker | `getInstalledApps` (PackageManager, MAIN/LAUNCHER `<queries>` only, no QUERY_ALL_PACKAGES) + searchable picker with icons. |
| Usage numbers | Aggregated daily stats are bucketed/inexact | Event-based per-interval accounting from local midnight, each interval counted once, screen-off/keyguard close intervals. |
| Reminders invisible on island in background | JS listener not running | The notification listener also sees this app's own reminder notifications and posts them natively. |
| Deleting/editing a reminder left the old alarm | Row removed, scheduled notification not cancelled | `cancelReminder` before delete/edit. |
| Notification privacy | none | Toggles for notifications, text preview; ongoing/transport/group-summary/duplicate updates filtered. |

Reminders kept: same SQLite table, same scheduling code, plus edit/delete/cancel and a date/time UI.
Reanimated is not used (it broke Windows builds via long C++ paths); the in-app CD uses React Native `Animated` with the native driver, the overlay CD uses a native `ObjectAnimator`.

## Test report (honest)
| Check | Result |
|---|---|
| TypeScript parse of all sources | PASS (no syntax errors; module-type errors only because RN type packages were not installed in the checker) |
| Kotlin compile against real Android API 34 jar (RN/AndroidX stubbed) | PASS, 0 errors |
| Full Gradle build / APK | NOT TESTED in this environment (no Android SDK). Build locally per docs/SETUP.md |
| A overlay, B permissions, C music + CD, D screen time + emojis, E notifications, F reminders, G persistence/reboot | NOT TESTED on a device. Use the checklist in docs/SETUP.md and report failures |

## Limitations
- Android may stop the service on aggressive OEMs; overlay cannot be guaranteed after force-stop or permission revocation.
- Usage events arrive in batches: the 😡/😊 switch can lag several seconds. If one app stays foreground >3 h before the service starts, it is unknown until the next app switch.
- Not implemented: weather, timers/stopwatch UI (not part of this request), location permission (only needed for weather).
- Music controls depend on what each player exposes (Next/Previous disabled when unsupported).
