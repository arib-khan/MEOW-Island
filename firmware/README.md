# Meow Island display firmware (ESP32 + ST7789)

Three screens on the 2.8" 240×320 TFT: the neon **clock** (your original design, pixel-identical), the **step goals** card and the **music** player,
fed by the Meow Island Android app over Bluetooth LE.

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
