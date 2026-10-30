# New display screens: screen time, notifications, custom HTML, music redesign

Read this together with `docs/DEVICE.md`. **The link between phone and display is Bluetooth LE** (not Wi-Fi: the display's Wi-Fi is only used to fetch the time). Everything here uses that existing link, pairing and reconnect logic (`DeviceLink.kt`); no second connection manager was added.

## Screens
| id | screen | data source |
|----|--------|-------------|
| 0 | Clock | unchanged |
| 1 | Steps | unchanged |
| 2 | Music (redesigned, no cat) | `MediaListener` metadata + album art + optional lyrics source |
| 3 | Screen time | Android UsageStats through the existing `UsageUtil` (phone usage, not the display's on-time) |
| 4 | Notifications | `MediaListener` (the existing `NotificationListenerService`), opt-in |
| 5 | Custom | user's HTML, rendered on the phone, stored on the display |

## Android permissions
No new permission was added. Used: `PACKAGE_USAGE_STATS` (already declared, user grants Usage Access in settings), Notification Access (already used), `INTERNET` (already declared; used only by the optional LRCLIB lyrics lookup, which is off by default). The HTML file is chosen with Android's document picker (`ACTION_OPEN_DOCUMENT`), which needs no permission.

## New dependencies
None (no npm or Gradle additions). The picker, WebView rendering and transfer are native Kotlin; LittleFS is part of the ESP32 Arduino core.

## Protocol additions (all little-endian; frame = `A5 | type | len | payload`, payload <= 200 bytes)
Unchanged: types 1-8, the 10-byte status. Screen ids 3-5 are new (`SCREEN` payload 0..5).

* `0x09 SCREENTIME`: `flags u8 | totalMin u16 | goalMin u16 | n u8 | n x (min u16 | nameLen u8 | name)`. flags: bit0 data, bit1 needs Usage Access, bit2 unavailable, bit3 goal on. At most 4 apps, names ASCII (14 chars), minutes <= 1440.
* `0x0A NOTIFS`: `flags u8 | count u8 | count x (ageMin u16 | appLen u8 app | titleLen u8 title | textLen u8 text)`, newest first, as many as fit one frame (app 12, title 24, text 48 chars). flags: bit0 feature on, bit1 needs Notification Access. A whole snapshot replaces the previous one. RAM only on the display.
* `0x0B MUSIC2`: `flags u8 | lyricState u8 | lineIdx u16 | srcLen u8 src | prevLen prev | curLen cur | nextLen next`. flags: bit0 album art on the display belongs to this track, bit1 lyrics are time-synced. lyricState: 0 n/a, 1 loading, 2 available (synced lines), 3 unavailable, 4 provider off, 5 offline, 6 text exists but is NOT synced (never shown as lyrics).
* Image transfer, kind 0 = custom screen file, kind 1 = album art (raw RGB565 80x80, RAM only):
  * `0x0C IMG_BEGIN`: `xfer u8 | kind u8 | total u32 | crc32 u32 | chunk u16 | w u16 | h u16`
  * `0x0D IMG_DATA`: `xfer u8 | seq u16 | bytes` (<= 180 bytes per chunk), accepted strictly in order
  * `0x0E IMG_END`: `xfer u8`; `0x0F IMG_CTRL`: `op u8` (1 abort, 2 delete custom image, 3 clear art)
  * Image frames go through their own 20-frame queue on the display; every other message type keeps "newest wins".
* Status gains 9 bytes (total 19; a 10-byte status from old firmware is still understood): `xferState u8 (0 idle,1 receiving,2 done,3 failed) | xferId u8 | nextSeq u16 | reason u8 | customCrc u32`. `customCrc` is the CRC-32 of the stored custom file, 0 if none.
  reasons: 1 bad header, 2 too large, 3 no space, 4 flash error, 5 CRC mismatch, 6 sequence/timeout, 7 invalid image, 8 no memory, 9 busy.

**Transfer rules.** Phone sends BEGIN, then a window of 8 chunks, advancing as `nextSeq` grows in the status; after 2.5 s without progress it rewinds to the device's `nextSeq` (or repeats BEGIN); after 6 stalls it gives up. The device acks DONE only after the whole file matched the CRC and passed structural validation, then atomically replaces the old file (`/custom.tmp` -> `/custom.mimg`). A BEGIN whose CRC equals the stored one answers DONE immediately and writes nothing. A failed or interrupted upload never touches the stored design. A silent transfer is dropped after 6 s; losing the link drops it at once.

**Image format (MIMG)**, `firmware/MeowIsland/mimg.h`: 16-byte header (`MI`, version 1, w, h, colours <= 256, data length), RGB565 palette, run-length data (long run / short run / literal tokens). 240x320 portrait (`TFT_ROTATION 0`). The encoder is `src/core/customscreen.ts` (exact when the design has <= 256 colours, otherwise colour depth is lowered step by step; no dithering); the device decodes 8 rows at a time and never holds a full bitmap.

## Custom HTML rendering and safety
`CustomRender.kt` loads the HTML from a string into a throw-away, off-screen `WebView` laid out at 240x320 CSS px: no JavaScript bridge, JavaScript off unless the user switches on "Allow scripts" for that design, no storage, no file/content access, network loads blocked (remote images/fonts/scripts do not render), all navigation refused, 8 s timeout, view destroyed afterwards. Limits: 256 KB HTML, 64 KB encoded picture. The HTML source and encoded image are kept in the app's private storage (`filesDir`) only and re-sent automatically after reconnecting if the display does not have the exact design.

## Music screen
Layout: source pill (SPOTIFY / YOUTUBE / BRAVE / app name) with a play/pause glyph, 80x80 album art (placeholder tile when none), title (scrolls if long), artist, progress bar with times, lyric zone. The dancing cat was removed **from the device's music screen only**; the Android home-screen "Dancing Cat" widget and `cat_assets.h` are untouched because other screens and the widget still use them. The TFT has no touch input, so playback controls stay on the phone.
* **Spotify lyrics:** Android's media session carries no lyrics and Spotify has no public lyrics API, so nothing is scraped. A `LyricsProvider` interface exists with one optional implementation, LRCLIB (https://lrclib.net, public API, time-synced LRC). It is **off by default**; when switched on, title, artist and length of the Spotify track are sent to lrclib.net. Only text with real timestamps is shown, and only the current/previous/next lines in sync with the playback position.
* **YouTube / Brave / other apps:** Android does not expose their captions through MediaSession or any supported API, so the display says "Captions unavailable". No accessibility-service scraping was added.

## Tests
* `NODE_PATH=$(npm root -g) node tests/run.js` : JS logic (device state, screen-time rules, image codec, PNG preview, file checks). Also writes `tests/mimg_vectors.txt` from the TypeScript encoder.
* `sh tests/kotlin/run_device.sh` (needs `kotlinc`): Kotlin protocol/LRC/sender tests; rewrites `tests/device_vectors.txt`.
* `sh firmware/host-tests/run.sh` (needs g++, git, Pillow; downloads Adafruit GFX): protocol vectors, image codec + receiver (sanitizers), clock regression, sketch smoke tests.
