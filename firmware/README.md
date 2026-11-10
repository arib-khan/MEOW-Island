# Meow Island display firmware (ESP32 + ST7789)

Six screens on the 2.8" 240×320 TFT: the neon **clock** (your original design, pixel-identical), the **step goals** card, the **music** player,
phone **screen time**, **notifications** and a full-screen **photo** slideshow, fed by the Meow Island Android app over Bluetooth LE.
The music screen shows provider-independent track metadata, cached album art, synced/plain lyrics (rendered on the phone so Hindi, Urdu/Arabic RTL,
Korean, Japanese and other scripts work) and a video mode with a bottom progress bar. See `../docs/MUSIC.md`.

## Hardware (unchanged from your working sketch)
ESP32 DevKit V1 · ST7789 SPI 240×320 · CS 5 · DC 2 · RST 4 · SCK 18 · MOSI 23 · backlight to 3.3 V · the **BOOT** button (GPIO0) is used as the only input.
Pins, rotation, SPI speed and the colour-order switches are in `config.h`.

## Flash it
1. Arduino IDE → Library Manager, install: **Adafruit GFX Library**, **Adafruit ST7735 and ST7789 Library**, **NimBLE-Arduino** (version **2.x**; written against 2.5.2).
2. Open `MeowIsland/MeowIsland.ino`. Board: **ESP32 Dev Module**.
3. Tools → Partition Scheme → **Minimal SPIFFS (1.9MB APP with OTA)** (or *Huge APP*) if the compiler says the sketch is too big.
4. Upload. The display shows the clock with “No Wi-Fi” until the phone or Wi-Fi sets the time.

No Wi-Fi name or password is in the source any more. (Your uploaded sketch contained them in plain text: change that password if the file was ever shared.)

## Using it
| Action | What happens |
|---|---|
| First power-up | Pairing is open (no phone is paired yet). |
| App → Settings → Meow Island Device → Find my display → Pair | The display shows a **6-digit code**; type it into Android's pairing box. |
| BOOT tap | next screen (clock → steps → music) |
| BOOT hold 3 s | opens pairing for 2 minutes (to add a new phone) |
| BOOT hold 10 s | forgets every phone **and** the Wi-Fi, then restarts |
| Power cycle | reconnects to Wi-Fi by itself; the phone reconnects when in range; the clock never stops |

## Security
* Both Bluetooth characteristics need an **encrypted + authenticated** link (LE Secure Connections, MITM protected, bonded). The code on the TFT is the authentication.
* A new phone can pair only while the pairing window is open; when closed, an attempt gets a code that is never displayed.
* Wi-Fi details arrive only over that link, are saved in the ESP32's flash (`Preferences`), are never printed to Serial and never sent back. Status messages contain no credentials and not even the network name.
* Every message is length- and range-checked; bad ones are dropped, never partly applied. NVS is not encrypted unless you enable ESP32 flash encryption.

## Protocol
See `protocol.h` (the single source of truth for the byte layout) and `../docs/DEVICE.md`. The Kotlin side is `plugins/kotlin/DeviceProtocol.kt`; `tests/device_vectors.txt` keeps the two in lock-step.

## Tests that run on a computer
`sh firmware/host-tests/run.sh` (needs g++, git, python3 + Pillow). It compiles the real Adafruit GFX library against small stand-ins and checks:
the protocol against the phone's bytes, the **clock against your original sketch pixel by pixel**, renders every screen state to `host-tests/out/*.png`,
and runs the whole sketch against a simulated phone and button. It does **not** run on real hardware or the real NimBLE stack.

## Regenerating the cat bitmaps
`python tools/make_tft_assets.py` rebuilds `cat_assets.h` (130 KB of flash) from the app's own artwork.

## Screens, photo storage and the partition scheme

The firmware has six screens: Clock, Steps, Music, Screen time, Notifications and Photos. BOOT-tap cycles them (Photos is skipped until a photo is stored).

**Photos.** The app compresses every picture to a 240 x 320 baseline JPEG (a few KB to about 30 KB; the phone picks the highest quality that fits a size target) and sends it over Bluetooth. Up to 24 photos are stored as files in LittleFS (`/ph00.jpg` .. `/ph23.jpg`, each with a tiny sidecar `/phNN.m` holding its CRC-32 and size so boot does not have to read every file). The slideshow shows them in the order and for the number of seconds the phone sends (`MSG_GALLERY`, kept in `/gallery.cfg`, so it keeps running after a restart without the phone). Each slide is decoded straight from flash to the TFT one MCU row at a time by `jpeg_dec.h` (baseline JPEG, 4:4:4 / 4:2:2 / 4:2:0, restart markers, grey), so no frame buffer is needed.

**Partition scheme.** LittleFS needs a data partition, so in the Arduino IDE choose **Tools > Partition Scheme** and use one that has a SPIFFS/LittleFS area. The sketch is about 1.33 MB, so "Default 4MB with spiffs" (1.2 MB APP) is too small; use **No OTA (2MB APP/2MB SPIFFS)** (2 MB for photos too) or `Huge APP (3 MB, 1 MB SPIFFS)`. With `Minimal SPIFFS` there is room for only a handful of photos. The phone reads the free and total storage from the display and warns before sending a photo that cannot fit.
- A new upload is written to `/photo.tmp` first and replaces its slot only after the CRC-32 matched and a complete decode succeeded, so the flash must hold the old and the new copy of that photo for a moment, and a failed upload never destroys the stored photo.
- `LittleFS.begin(true)` formats the data partition the first time it is used. Changing the partition scheme erases stored photos once; the app sends them again.
- At the first boot after updating from firmware with the custom HTML screen, its files (`/custom.mimg`, `/s1.bin` ..) are deleted to free the flash.

**RAM use.** A photo is never held in RAM (the decoder works in bands: about 7.7 KB output band plus about 3 KB of tables). Album art is one 80x80 RGB565 block (12.8 KB) allocated the first time art arrives. The phone-rendered lyric mask is 2 240 bytes. The BLE image queue is 20 frames and the lyric queue 16 frames (about 7 KB together).

**Notifications** are kept in RAM only and are never written to flash.

**Protocol.** See `docs/NEW_SCREENS.md` for the byte layouts (also commented in `protocol.h`, `jpeg_dec.h`, `photo_store.h` and `image_rx.h`).

## Photo gallery protocol (firmware 1.3)

* `MSG_GALLERY` (0x11, latest wins): `[version 1][intervalSec u16][flags 0][n][slot x n]`: play these photo slots in this order, each for `intervalSec` seconds (2..3600). Invalid messages (wrong version/length, slot out of range or listed twice) are ignored.
* Image `BEGIN` kind 2 = photo: the usual 16 bytes plus `slot u8` (17 bytes), `w`/`h` must be 240/320, total 160 B .. 48 KB. `CTRL [4][slot]` deletes one photo. Kind 0 (the removed custom screen) is rejected.
* The status grows by 15 bytes after the transfer bytes: photo mask u32, shown slot, interval, free and total flash KB, and a digest of (slot, CRC) pairs, so the phone knows without asking which photos the display holds.
* Tests: `host-tests/run.sh` builds `jpeg_test.cpp` (the decoder against Pillow/libjpeg on 40 generated JPEGs, plus corruption and fuzzing), `image_test.cpp` (the receiver, including a link that drops, duplicates and reorders chunks), `photo_store_test.cpp` (the real `photo_store.h` with a fake display and in-memory flash: storage rules, boot recovery, slideshow order and timing) and the protocol vectors shared with the app's Kotlin tests.
