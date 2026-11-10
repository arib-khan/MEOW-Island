# Photo gallery: pick photos in the app, show them as a slideshow on the display

## What it does

In the app (Device page -> Photo gallery) you pick pictures. The phone makes each one upright, crops or fits it to the display's 240 x 320, compresses it
heavily to a small baseline JPEG and sends it to the display over Bluetooth. The display keeps up to 24 photos in flash and shows them full screen, one
after the other, with the time per photo you choose (3 s up to 1 hour). The order can be changed, photos can be removed one by one, and everything the
display needs is re-sent automatically when it reconnects.

| step | where | what |
|---|---|---|
| pick, rotate, scale | phone (`Gallery.kt`) | Android picker (several at once), EXIF rotation applied, kept as a 1280 px "master" so a later change of fit or quality can compress again |
| compress | phone | "Fill screen" crops the centre, "Whole picture" adds black bars; JPEG at the highest quality that stays under the target (Smaller about 12 KB, Balanced about 20 KB, Best up to 32 KB; never below quality 20) |
| transfer | Bluetooth | the existing CRC-checked, resumable transfer, one photo at a time; a photo the display already holds (same CRC) is acknowledged without sending or writing anything |
| store | display (`photo_store.h`) | one LittleFS file per photo, replaced only after the CRC matched and a full decode succeeded |
| show | display (`jpeg_dec.h`) | baseline JPEG decoded straight to the TFT one MCU row at a time, no frame buffer; next photo after the chosen time |

Numbers that matter: a typical 240 x 320 photo is 10-25 KB, so 24 photos are 250-600 KB and fit the 1.5 MB / 2 MB partitions (the app shows the free space
and the room left). Sending takes about 4-5 seconds per photo at the usual Bluetooth speed.

## Using it

1. Flash the display with firmware 1.3 and a partition scheme that has storage ("No OTA (2MB APP/2MB SPIFFS)" works; see `firmware/README.md`).
2. Device page -> Open Photo Gallery -> Add photos, choose as many as you like. They are compressed one by one (a few seconds each) and sent when the display is connected.
3. Pick the time per photo, "Fill screen" or "Whole picture", and the compression. Changing the picture mode or compression compresses all photos again.
4. Move photos with the arrows, remove with the cross. "Show on display" switches the display to the slideshow; the BOOT button on the display also reaches it.

Everything stays on the phone and the display. Nothing is uploaded; the app needs no network for this feature.

## Protocol (details in `firmware/MeowIsland/protocol.h`)

* Photo transfer: image `BEGIN` kind 2 with a slot byte (17 bytes), `DATA`, `END` as for album art; `CTRL [4][slot]` deletes a photo.
* `MSG_GALLERY` (0x11): `[1][intervalSec u16][0][n][slot x n]`, the play order and time. Stored on the display, applied only when it changed.
* Status: 15 gallery bytes (photo mask, shown slot, interval, flash free/total KB, digest of (slot, CRC) pairs). The phone compares the digest with its own
  library to know whether the display holds exactly its photos. The app understands both status layouts (with and without the album-art CRC) from the total length.
* Removing the custom HTML screen: kind 0 transfers are rejected, and the first boot of the new firmware deletes the old custom-screen files to free the flash.

## Tests

* `npm test`: gallery settings, labels, storage estimate, digest and badges, plus all existing app logic.
* `firmware/host-tests/run.sh`: the JPEG decoder against Pillow/libjpeg (40 generated files: 4:4:4, 4:2:2, 4:2:0, grey, odd sizes, restart markers, progressive refused,
  1 500 corruption trials), the transfer receiver over a lossy link, the real storage and slideshow code with a fake display and in-memory flash, and the protocol vectors shared with the Kotlin tests.
* Not covered by any automated test here: the Kotlin (picker, picture processing, transfer queue), the full sketch on a board, and Bluetooth with a real display.

## Video: evaluation (not implemented)

Short answer: smooth video is not realistic on this hardware; a short silent looping clip is technically possible with the same pieces, but it is a poor use
of the flash and the link, so it was left out. The numbers (estimates for an ESP32 at 240 MHz with this decoder, not measurements on a board):

* **Decoding.** Video would have to be motion-JPEG: every frame a 240 x 320 JPEG like the photos. The decoder here needs roughly 100-200 ms per frame on an ESP32
  (entropy decoding, 1 800 floating point 8x8 transforms, colour conversion, plus about 30 ms just to clock 150 KB to the TFT over SPI). That is about 5-8 frames per second;
  a hand-tuned integer decoder might reach 10-12. 24 fps would need a different display interface and hardware decoding, which this board does not have.
* **Flash.** At about 10-12 KB per frame, the 1.9 MB data partition holds roughly 150-190 frames: about 20 seconds at 8 fps, with nothing left for photos.
* **Transfer.** Bluetooth moves about 4 KB per second, so a 20-second clip (about 1.8 MB) takes around 7-8 minutes to send, and any dropped connection resumes from the last good chunk but still costs minutes.
* **RAM.** Not the limit: the decoder streams in bands (about 11 KB). Flash space, transfer time and decode speed are.
* **Sound and sync.** There is no audio path, so only silent clips make sense; frame timing would be approximate.

What would be needed if you want it anyway: the app extracts frames from a clip (about 8 per second, 240 x 320), compresses each as for a photo, and sends them as numbered
frames of one "clip"; the display plays the files in a loop with a frame delay. The storage rule above caps it at roughly 15-20 seconds, so it fits a short boomerang or
animated loop, not a video. Wi-Fi transfer (the ESP32 has Wi-Fi) would cut the send time from minutes to seconds and is the one change that would make clips pleasant to use.
