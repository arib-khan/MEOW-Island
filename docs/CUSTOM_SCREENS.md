# Custom screens: HTML, CSS, JavaScript and animation on the display

## What runs where

The ESP32 cannot run a web engine (no GPU, about 300 KB of RAM) and Bluetooth LE moves only a few KB per second, so a live web page cannot be streamed to the TFT (one full 240 x 320 RGB565 frame is 150 KB). So the work is split:

| step | where | what |
|---|---|---|
| render | phone, sealed WebView | your HTML, CSS and JavaScript run; a **virtual clock** advances CSS animations, transitions, Web Animations, `setTimeout`/`setInterval`, `requestAnimationFrame`, `Date` and `performance.now` exactly one step per recorded frame (deterministic, same result every time) |
| record | phone | 240 x 320 frames, box-filtered from the screen density, only the changed rectangle of each frame is kept |
| encode | phone (`src/core/animscreen.ts`) | one palette (<= 256 colours) for the whole animation: exact when the design is flat, median-cut plus a fixed ordered dither when it has gradients or photos (flat colours stay exact); run-length coding; identical frames merged |
| store + play | display | up to 8 screens in flash; frame by frame to the TFT |

## What works in your HTML

* HTML and CSS: layout, gradients, web fonts you provide, SVG, `<canvas>`, CSS keyframes and transitions, Web Animations (`element.animate`).
* JavaScript (switch "Run JavaScript"): timers, `requestAnimationFrame`, canvas drawing, DOM updates. The page sees a clock that starts when it loads and moves only as frames are recorded. A page that shows the time shows the time of the recording.
* Not possible: video, audio, WebGL, `fetch`/XHR to anything (answered with an error), forms, navigation, storage, camera/location, and anything that needs the live app or the live display. A screen is a recording, so it does not react to touch or live data.
* Length: automatic (one cycle of the longest looping CSS animation, 3 s for script-only animation), or 2 / 4 / 8 / 12 / 20 s. Frame rate 1, 2, 5, 10 or 15. For a slideshow choose a length that covers the whole loop and 1-2 fps. Up to 120 frames are recorded.

## Images, fonts, scripts

Why images used to be missing: the old renderer answered every request that was not a `data:` URL with an error, and the file picker took a single HTML file, so `<img src="cat.png">`, `url(bg.jpg)` and `https://...` images could never load. Now:

* **Choose the HTML file together with its images** in the file picker (select several), **or pick a .zip** that contains `index.html` and its folders. Relative paths (`images/cat.png`) resolve inside your selection; case differences and a flat selection (file name only) are tolerated.
* **Online resources** (switch, on by default): `https://` images, fonts, style sheets and, when JavaScript is on, scripts named in your HTML are fetched by the app (6 MB per file, 24 MB and 64 files in total, 6 s timeout, content-type checked) and handed to the renderer. Nothing else is sent. Switch it off to use only your own files.
* After rendering, the app lists anything that did not load ("Not found in your files: images/cat.png", "host answered 404", "http is not allowed") with a button to add the missing files.
* Photos work: they are reduced to 256 colours with dithering (the old format refused them as "too complex"). Fine photographic detail is limited by the 256-colour palette; use flat colours where it matters.

## Managing screens

The Custom Screens page keeps up to 8 screens: add, preview (animated, exactly what the display will play), edit and render again with other settings, rename, remove (also from the display), send, send all, show a chosen one on the display now. The display's BOOT button steps through all stored screens. "Keep the display up to date" re-sends missing or changed screens when the display connects; the display answers a screen it already has without writing flash. Screens and their source files stay in the app's private storage; nothing is uploaded.

## Limits you will meet

* Per screen at most 768 KB (the display's limit); sending takes roughly size / 4 KB per second, so a 200 KB animation takes about a minute. A full-screen photo costs roughly 60-80 KB, so a slideshow of six photos needs about 450 KB and the 1.5 MB partition.
* Flash: with *Minimal SPIFFS* the display has about 190 KB for all screens; flash the *Default 4MB with spiffs* scheme for 1.5 MB. The app reads the free space from the display and tells you before sending.
* Playback is limited by the TFT bus: roughly 15 full-screen frames per second; small moving areas are much cheaper because only the changed rectangle is drawn.

## File format (MANI) and protocol

See the header comments in `firmware/MeowIsland/mani.h` (file layout), `protocol.h` (messages, status extension), `image_rx.h` (transfer rules) and `src/core/animscreen.ts` (encoder, decoder, preview). `tests/mani_vectors.txt` is produced by the encoder and checked frame by frame by the firmware player in `firmware/host-tests/mani_test.cpp`.

## Tests

* `npm test`: codec, quantiser quality, delta rectangles, decoder rejection of corrupt files, GIF preview decoded back and compared, settings, digest, HTML preparation, virtual clock (plain Node).
* `npm run test:browser`: the virtual clock and HTML preparation in real headless Chromium (needs Playwright). Not part of `npm test`.
* `firmware/host-tests/run.sh`: the firmware logic on a desktop (see `firmware/README.md`).
* Not covered by any automated test here: the Kotlin code (WebView capture, file picker, transfer queue) and the physical display. Try it on a phone and a board.
