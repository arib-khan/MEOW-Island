# Smart Island — Setup Guide

> Status: source code only. Never compiled or run by the author. Expect small Kotlin/Gradle fixes on first build. See README.md for what is and isn't implemented.

## 1. Prerequisites
| Tool | Version | Check |
|---|---|---|
| Node.js | 20 or newer | `node -v` |
| JDK | 17 (recommended) | `java -version` |
| Android Studio | latest (installs SDK + platform tools) | — |
| Android SDK | Platform 34/35, Build-Tools, Platform-Tools | SDK Manager |
| Android phone | Android 10+ with USB debugging on | `adb devices` |

Set environment variables (add to your shell profile):
- macOS/Linux: `export ANDROID_HOME=$HOME/Android/Sdk` (macOS: `$HOME/Library/Android/sdk`) and add `$ANDROID_HOME/platform-tools` to `PATH`.
- Windows: set `ANDROID_HOME` to `C:\Users\<you>\AppData\Local\Android\Sdk` and add `%ANDROID_HOME%\platform-tools` to `Path`.

## 2. Install and generate the native project
```
unzip smart-island.zip && cd smart-island
npm install
npx expo prebuild --platform android --clean
```
`prebuild` creates the `android/` folder and runs `plugins/withIsland.js`, which:
- adds the manifest permissions and the two services (`IslandService`, `MediaListener`)
- copies the Kotlin files from `plugins/kotlin/` into `android/app/src/main/java/com/smartisland/app/`
- registers `IslandPackage` in `MainApplication`

If the registration step fails (the plugin matches `PackageList(this).packages.apply {`), open `android/app/src/main/java/com/smartisland/app/MainApplication.kt` and add `add(IslandPackage())` inside that block by hand.

## 3. Run or build
Debug build on a connected phone (fastest way to test):
```
npx expo run:android
```
Release APK:
```
cd android
./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease
```
Output: `android/app/build/outputs/apk/release/app-release.apk`. The default release build is signed with the debug key, which is fine for personal installs. Create your own keystore before sharing it widely.

Install: `adb install -r android/app/build/outputs/apk/release/app-release.apk`, or copy the APK to the phone and open it.

## 4. First-run permissions (in order)
1. **Allow notifications** — popup on first launch (needed for reminders).
2. **Overlay** — tap Grant, enable "Allow display over other apps" for Smart Island.
3. **Notification access** — tap Grant, enable Smart Island. Required for music info/controls and notification previews. On Android 13+ sideloaded apps may show "Restricted setting": open App info > ⋮ > *Allow restricted settings*, then retry.
4. **Usage access** — tap Grant, enable Smart Island. Required for Screen Time Guardian.
5. Return to the app, tap **Refresh permissions**, then **Start island**.

Denying a permission only disables the feature that needs it. Revoking one later shows ❌ again after refresh.

## 5. Keep it alive (important)
- Settings > Apps > Smart Island > Battery > **Unrestricted**.
- Xiaomi/Oppo/Vivo/Huawei/Samsung: enable Autostart and lock the app in Recents; disable "sleeping apps"/"adaptive battery" for it.
- Android 14+: Settings > Apps > Special access > *Alarms & reminders* > allow Smart Island (exact reminders).
- Reboot restart is NOT implemented yet; open the app and tap Start island after a reboot.

## 6. Testing checklist
- [ ] Pill appears at top after Start island, over other apps
- [ ] Play music (Spotify/YouTube Music): title, artist, art show; art spins; pausing stops spin
- [ ] Send yourself a notification: preview appears for ~5 s
- [ ] Schedule a reminder for 1 minute: island expands, notification fires; Snooze works; close the app and test again
- [ ] Add a limit (package name e.g. `com.android.chrome`, 1 min), use the app >1 min: 😡 appears; leave the app: 😊 for ~2.5 s
- [ ] Stop island removes the pill

## 7. Troubleshooting
| Problem | Fix |
|---|---|
| `SDK location not found` | Set `ANDROID_HOME` or create `android/local.properties` with `sdk.dir=/path/to/Sdk` |
| Gradle needs a different JDK | Use JDK 17: `export JAVA_HOME=<jdk17 path>` |
| `Unresolved reference: IslandPackage` | Re-run prebuild with `--clean`; confirm Kotlin files exist in `android/app/src/main/java/com/smartisland/app/` |
| `NativeModules.IslandModule` is undefined | Package not registered; add `add(IslandPackage())` manually (section 2) |
| No music info | Notification access not granted, or the player exposes no media session |
| Pill never shows | Overlay permission off, or OEM killed the service (section 5) |
| Package names for limits | Find with `adb shell pm list packages | grep <name>` |

## 8. Project layout
```
smart-island/
├── README.md              overview, status, limitations
├── docs/SETUP.md          this guide
├── package.json  app.json  tsconfig.json  index.js
├── plugins/
│   ├── withIsland.js      Expo config plugin (manifest, Kotlin copy, registration)
│   └── kotlin/            IslandService, MediaListener, IslandModule(+Package)
└── src/
    ├── App.tsx            permissions + reminder + limits UI
    └── core/              db, reminders, activities (priority), guardian
```
