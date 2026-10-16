# Meow Island (Android)

A cat-themed floating island for Android. Expo + React Native (TypeScript) for the app, Kotlin only for system integration
(overlay service, media sessions, notification listener, usage stats). Android package id is unchanged
(`com.smartisland.app`) so installs update in place. See `docs/SETUP.md` to build.

## What this revision adds
- **Rebrand to Meow Island**: app name, launcher icon + adaptive icon, branded launch window, notification icon, in-app copy, Notification-access label.
- **Island position**: drag-to-position editor, presets, X/Y steppers, usable-area vs full-screen mode, drag the real island, reset, persisted. Stored as fractions so it survives rotation/size changes and is clamped on screen.
- **Layering**: `TYPE_APPLICATION_OVERLAY`, explicit gravity/insets, cutout and nav-bar aware (3-button and gesture), window sized to the pill so other touches pass through.
- **Music on the island**: Previous / Play-Pause / Next buttons call the real `MediaController`; CD rotation follows real playback; progress bar; unsupported controls dimmed.
- **Full-screen video hiding** (best effort, see limits). **Screen-time reminders** every N minutes (default 5) with used / limit / over-by, run natively. **Timers** (absolute end time). Cat ears, 😾/😺 defaults.

## Test report (honest)
| Check | Result |
|---|---|
| Kotlin compiles against real Android API 34 jar (RN/AndroidX stubbed) | PASS (0 errors) |
| TypeScript: syntax + undefined-name check over the whole import tree | PASS |
| Icon assets generated; launcher icon viewed | PASS (only the icon was visually inspected) |
| Gradle build / APK | NOT TESTED here (no Android SDK). Build locally |
| Positioning, drag, layering over apps, gesture vs 3-button nav | NOT TESTED on a device |
| Music buttons drive the real player; CD pause/resume; skip | NOT TESTED |
| Full-screen hide/restore | NOT TESTED |
| Screen-time reminders (use "Remind every = 1 min" to test), happy emoji, no app blocked | NOT TESTED |
| Timers, reminders, persistence, reboot restore | NOT TESTED |

## Limitations
- Android has no universal full-screen-video callback. Heuristic: needs Usage Access; the foreground app must own a playing/paused/buffering media session, and the device must be landscape, or (YouTube-type apps) the system bars hidden. Portrait full-screen (Shorts/Reels) detection depends on the bars signal and may not work. Hiding needs 2 consecutive checks (~2 s).
- Usage events arrive in batches, so emoji/reminder timing can lag seconds. Reminders tick in a foreground service (no polling when the screen is off), not exact alarms.
- Dragging the island disables the swipe gestures (down/up expand, sideways dismiss); turn drag off to use them.
- Timers: start/cancel only (no pause/resume). Light theme and onboarding slides are not implemented (dark theme only; a setup card guides permissions).
- Icons in the tab bar are emoji (no icon font is installed). Reanimated is not used (it broke Windows builds).
- Android can stop the service on aggressive OEMs, after force-stop, or if a permission is revoked.
