/**
 * Permissions as declared by the app's Expo config plugin (plugins/withIsland.js)
 * and described in docs/HEALTH.md and docs/SETUP.md of the repository.
 */
export const PERMISSIONS = [
  { name: "Display over other apps", android: "SYSTEM_ALERT_WINDOW", why: "Draws the island above other apps.", feature: "Floating island", revoke: "Android Settings, Apps, Special app access, Display over other apps." },
  { name: "Notification access", android: "Notification listener service", why: "Lets the app read media sessions (music title, artist, art, controls) and show previews of incoming notifications. While it is on, Android makes your notifications visible to the app.", feature: "Music controls, notification previews", revoke: "Android Settings, Apps, Special app access, Notification access." },
  { name: "Usage access", android: "PACKAGE_USAGE_STATS", why: "Tells the app which app is in the foreground and for how long.", feature: "Screen Time Guardian", revoke: "Android Settings, Apps, Special app access, Usage access." },
  { name: "Physical activity", android: "ACTIVITY_RECOGNITION", why: "Reads the phone's step counter or step detector sensor.", feature: "Step counting, walking progress, heatmap", revoke: "Android Settings, Apps, Meow Island, Permissions." },
  { name: "Notifications", android: "POST_NOTIFICATIONS", why: "Shows reminder, timer and water-reminder notifications.", feature: "Reminders, timers, water reminders", revoke: "Android Settings, Apps, Meow Island, Notifications." },
  { name: "Alarms and reminders", android: "SCHEDULE_EXACT_ALARM", why: "Declared so reminders can fire on time. Water reminders themselves use inexact, battery-friendly alarms.", feature: "Reminders", revoke: "Android Settings, Apps, Special app access, Alarms and reminders." },
  { name: "Foreground service and boot", android: "FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED", why: "Keeps the island running, and can restore it after a restart if you left it running.", feature: "Floating island", revoke: "Stop the island in the app, or Force stop in Android app settings." },
] as const;
