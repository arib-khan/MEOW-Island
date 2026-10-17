# Meow Island — Health & Wellness and overlay notes

## Privacy
Every health feature is **off by default**. Settings are stored in the app's local database and Android SharedPreferences; step counts,
goals, history, the water log and preferences never leave the phone: no account, no server, no analytics, no cloud sync.
*Clear health data* (Health tab) deletes history, contribution records and the water log after a confirmation. Turning a feature off stops its work.

## Permissions (requested only when you use the feature)
| Feature | Permission | When asked |
|---|---|---|
| Step features | Physical activity (`ACTIVITY_RECOGNITION`, Android 10+) | after an explanation, when you switch a step feature on |
| Water notifications | Notifications (Android 13+) | when you enable water reminders / the notification option |
| Island | Display over other apps | from the Home setup card |
No exact-alarm, accessibility, location or broad health-data permission is used.

## Steps: how it works
* Uses the hardware **step counter** (cumulative since boot); falls back to the **step detector** (counted only while the island service runs, so those days are flagged incomplete). If the phone has neither, tracking is shown as unavailable and cannot be switched on.
* The app stores a baseline of the last raw reading and adds only the difference. A reading lower than the baseline means a reboot: the new value is added and that day is flagged *incomplete* (steps between the last reading and the reboot cannot be recovered).
* Steps taken before tracking was enabled are never invented: the first reading only sets the baseline, and that first day is flagged incomplete.
* Event-driven: the sensor is listened to only while the screen is on and the island service runs. Otherwise one coarse alarm (~30 min, plus one just after midnight) takes a single reading, and opening the app takes a fresh one. Because the counter is cumulative, steps taken while nothing listened are still added at the next reading.
* Days are the phone's **local calendar days**. Steps reported after midnight count toward the new day, so up to ~30 min of steps near midnight may land on the wrong side (the midnight alarm keeps this small).
* **Goal rule:** each day keeps the goal that was active when it ended. Changing the goal changes *today only*; history is never recalculated.
* **Heatmap levels:** goal reached (bright purple, ringed) · 50–99% · below 50% · incomplete data (dashed pink outline, never counted as a miss) · no data (dashed, empty). Missing days are never filled in.
* **Walking mode:** needs N steps inside 10 s (Calmer 10 / Normal 6 / Quicker 4) and leaves after a configurable idle time (default 8 s), so it does not flicker. Large catch-up deltas (after the screen was off) never trigger it. The goal celebration plays once per day.

## Water reminders
Schedule = start, end, interval, active days, optional quiet window (may cross midnight), pause, snooze, island and/or notification. One inexact
`AlarmManager.setAndAllowWhileIdle` alarm is always re-computed for the *next* slot using the same PendingIntent, so restarts and boot never create duplicates;
stale or double deliveries are ignored, and alarms Doze delayed past the end of the window are dropped. No polling. Android battery saving can delay reminders by minutes.
Island-only reminders need the island running. This is a reminder on *your* schedule, not medical advice.

## Overlay: root cause and honest limits
* **Root cause of "hidden by the top bar":** `TYPE_APPLICATION_OVERLAY` windows sit below the status-bar window in Android's layer order, so the system clock and icons always draw over an app overlay. The island had been placed over them.
* **Fix:** default position *Status bar* centres the pill vertically in the status-bar band (cutout aware) where the middle is normally free; *Below bars* and *Full screen* are also available. Position is stored as fractions, clamped on screen, margin-adjusted for rounded corners, and recomputed on rotation, resolution/density, navigation-mode and cutout changes (checked each second, redrawn only when something changed).
* Only an accessibility overlay or privileged window types can cover status-bar icons; this app deliberately uses neither. Permission dialogs, the lock screen, secure windows and some system UI always stay above overlays.
* The island **cannot be dragged** on the phone any more; it moves only from Settings → Island position.
* If overlay permission is revoked while running, the service stops cleanly and Home shows how to grant it again.

## Home-screen widgets
Three real Android app widgets (`AppWidgetProvider` + `RemoteViews`), added from the launcher's Widgets list or the Settings buttons (`requestPinAppWidget`, Android 8+ launchers that allow it):
* **Meow Clock** (live stacked time + date, optional "N steps" line only if the step counter is on) and **Meow Round Clock** (round face, date, battery). Time and date tick by themselves; battery/steps lines refresh about every 30 minutes (Android's minimum widget refresh).
* **Meow Step Heatmap**: the same purple contribution grid as the Health tab, drawn from the local step ledger and redrawn to the widget's size. It shows a prompt until "Contribution heatmap" is on. Adding a widget never enables tracking. Refreshes at most every ~10 min while steps change, at midnight sync, and when settings change; a ring marks today.
* Tapping a widget opens the app. Widgets are not copies of other apps' widgets (the clock/GitHub widgets in your screenshot come from other apps); they show *local* data only and never contact GitHub.
* Limits: a launcher may refuse programmatic pinning (use the manual way); some launchers crop or round widgets differently; the bitmap is capped at ~240k pixels, so very large widgets are upscaled.

## Tests
* `npm test` — settings persistence/clamping, defaults off, day status/levels, heatmap grid across month/year/DST, formatting, config migration.
* Kotlin JVM logic tests (no Android needed): `kotlinc plugins/kotlin/HealthLogic.kt tests/kotlin/HealthLogicTest.kt -include-runtime -d t.jar && java -jar t.jar` — step aggregation (no double count, reboot, midnight, DST, per-day goals), walking hysteresis, water schedule (intervals, quiet window across midnight, weekdays, pause, DST monotonicity).

## Physical-device checklist (NOT yet run by the author)
Overlay: pill sits in the status-bar band on a notch/punch-hole phone and with 3-button + gesture navigation; rotate; open apps; revoke overlay permission. Steps: enable, grant, walk 20 steps, watch the bar; reboot and re-check totals; goal celebration once. Water: 1-minute-ish test with *Send test reminder*, Done/Snooze/Dismiss from island and notification, pause, disable cancels. Regression: music buttons, notifications, timers, reminders, screen-time reminders (Remind every = 1 min).
