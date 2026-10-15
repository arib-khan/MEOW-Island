# Smart Island (partial implementation — NOT built or tested)

See docs/SETUP.md for the full setup guide. Quick version (needs JDK 17+, Android SDK, Node 20+):
    npm install
    npx expo prebuild --platform android --clean   # runs plugins/withIsland.js (manifest + Kotlin copy)
    cd android && ./gradlew assembleRelease          # APK: android/app/build/outputs/apk/release/
    adb install app/build/outputs/apk/release/app-release.apk
(`npx expo run:android` for a debug build on a connected device.)

## First-run permissions (in-app "Grant" buttons)
1. Overlay: draw over other apps. 2. Notification access: music metadata/controls + notification preview.
3. Usage access: Screen Time Guardian. 4. Allow notifications (reminders).
Then tap "Start island". Disable battery optimization for the app (Settings > Apps > Battery > Unrestricted); some OEMs (Xiaomi, Oppo, Samsung) also need "Autostart".

## Implemented
Overlay pill + foreground service; real MediaSession music (title/artist/art, rotating art paused when paused, transport controls bridge); notification previews; OS-scheduled reminders (once/daily/weekly) with snooze action, SQLite storage; priority arbiter; screen-time guardian (😡/😊, never blocks apps); low-battery alert; native prefs bridge for size/radius/colors.

## NOT implemented yet
Weather (API + city/location + cache), timers/stopwatch UI (DB table + activity hooks exist), settings UI for customization, swipe gestures and animated expand/collapse (tap toggles only), boot-time service restart (needs a BOOT_COMPLETED receiver), reminder edit, "mark complete" notification action, Reanimated usage.

## Known limitations
Never compiled: expect Kotlin/Gradle fixes on first build. Android may kill the service on aggressive OEMs; overlay is not guaranteed forever. Foreground detection is polled (~5s lag). Overlay is hidden on some secure screens/lockscreen. Reminders scheduled via expo-notifications may require exact-alarm permission on Android 14+.
