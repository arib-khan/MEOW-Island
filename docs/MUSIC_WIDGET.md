# Dancing Cat music widget

A separate home-screen widget (**Meow Dancing Cat**, 4x2): the black neon cat on the left, track title, artist and album art, previous / play-pause / next buttons and a colourful equalizer. It is independent of the floating island and the step widgets.

## How it works
* **Data:** it reuses the existing `MediaListener` (Android `MediaSessionManager` / `MediaController`, enabled by the same *Notification access* permission the island uses). No new service, no audio access. It redraws only when the track, artwork or play state changes, and not at all if no widget is placed.
* **Dancing:** the "play" layout holds two `ViewFlipper` frame animators (6 cat frames at ~170 ms, 6 equalizer frames at ~150 ms) that the launcher runs. The "idle" layout has no animators, so nothing animates or wakes when music is paused, stopped or Dancing Cat Mode is off. Android's `ViewFlipper` is designed to stop flipping while the screen is off.
* **Equalizer:** it is a canned looping animation shown while playing. It is **not** driven by audio: Meow Island never captures, records or analyses audio.
* **Buttons:** they call the active session's transport controls. If notification access is missing (no controller), they send a standard media key to whatever player is active, so the widget still works as a remote.
* **States:** playing (dancing) / paused (still cat, play icon) / nothing playing / no notification access (explains how to enable it, never animates). Missing title or artist show "Unknown track" / "Unknown artist"; missing artwork hides the artwork slot. Previous/next dim when the player does not support them.
* **Setting:** Settings → Features → **Dancing Cat Mode**. Off = the cat stays still but the widget still shows the track and controls.
* **Add it:** Settings → Home screen widgets → *Dancing cat music*, or long-press the home screen → Widgets → Meow Island → Meow Dancing Cat.

## Limits (not verified on a physical launcher yet)
Launchers differ in how they crop/resize widgets and whether they pause the frame animators on their own. Some players do not report artwork or support every control. The widget refreshes when the player reports a change; if our process is killed mid-song it self-heals on the next system update (about 30 minutes). Artwork comes from the player's media metadata.

## Regenerating the artwork
`python3 tools/make_dancing_cat.py` (needs Pillow) rewrites the cat and equalizer frames in `plugins/widgets/res/drawable-nodpi/`.
