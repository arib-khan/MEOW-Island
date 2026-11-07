// Flash / RAM storage behind the image transfer, and the custom-screen drawing and playback.
//   * custom screens: up to meow::SLOTS files in LittleFS. Slot 0 is /custom.mimg (the name the single-image firmware used, so an existing design is
//     simply slot 0), slots 1..7 are /s1.bin .. /s7.bin. A file is either a MANI animation (mani.h) or a MIMG still (mimg.h); the magic tells which.
//     A new upload goes to /custom.tmp first and replaces the slot only after the CRC matched and the whole file validated, so a failed upload never
//     destroys the screen that is on the display. Each slot also has a tiny sidecar /sN.m (file CRC-32 + size) so boot does not have to read every file.
//   * album art: a small raw RGB565 block in RAM (never written to flash), refreshed per track.
//   * the same bytes are never written twice: ImageReceiver answers DONE straight away when the CRC equals the one stored in that slot.
// LittleFS needs a data partition: use Tools > Partition Scheme > "Default 4MB with spiffs" (1.5 MB) for animations, or "Minimal SPIFFS" (190 KB) for a few stills.
#pragma once
#include <LittleFS.h>
#include "gfx_common.h"
#include "protocol.h"
#include "mimg.h"
#include "mani.h"
#include "image_rx.h"

static const char* const CUSTOM_TMP = "/custom.tmp";
constexpr uint32_t FLASH_MARGIN = 8192;           // LittleFS metadata + block rounding headroom
constexpr int NSLOT = meow::SLOTS;

struct SlotInfo { uint32_t crc = 0; uint32_t bytes = 0; bool mani = false; uint16_t frames = 0; };   // crc 0 = empty
static SlotInfo slots[NSLOT];
static bool     fsReady = false;
static uint32_t customCrc = 0;                    // CRC of slot 0 (what the single-image firmware reported); 0 = slot 0 empty
static uint32_t customRev = 0;                    // bumps whenever any stored screen changes (the custom screen redraws)
static uint8_t  curSlot = 0;                      // the slot the custom screen shows (or showed last)
static uint16_t* artBuf = nullptr; static uint16_t artW = 0, artH = 0; static bool artReady = false; static uint32_t artRev = 0;
static uint32_t artCrc = 0;                       // CRC-32 of the art in RAM, 0 = none: lets the phone skip a repeated transfer

static const char* slotPath(uint8_t s) { static char b[4][16]; static uint8_t n = 0; char* o = b[n++ & 3]; if (s == 0) strcpy(o, "/custom.mimg"); else snprintf(o, 16, "/s%u.bin", (unsigned)s); return o; }
static const char* slotMeta(uint8_t s) { static char b[4][16]; static uint8_t n = 0; char* o = b[n++ & 3]; snprintf(o, 16, "/s%u.m", (unsigned)s); return o; }
static bool slotUsed(uint8_t s) { return s < NSLOT && slots[s].crc != 0; }
static uint16_t slotMask() { uint16_t m = 0; for (int i = 0; i < NSLOT; i++) if (slots[i].crc) m |= (uint16_t)(1u << i); return m; }
static int slotFirst() { for (int i = 0; i < NSLOT; i++) if (slots[i].crc) return i; return -1; }
static int slotNext(int after) { for (int i = after + 1; i < NSLOT; i++) if (slots[i].crc) return i; return -1; }
static uint32_t slotsDigest() { uint32_t c[NSLOT]; for (int i = 0; i < NSLOT; i++) c[i] = slots[i].crc; return meow::slotDigest(c, NSLOT); }
static void slotsChanged() { customCrc = slots[0].crc; customRev++; }

// Colours arrive as RGB565 from the phone; boards with swapped red/blue need the fields exchanged.
static inline uint16_t fixColor(uint16_t c) { return SWAP_RED_BLUE ? (uint16_t)(((c & 0x1F) << 11) | (c & 0x07E0) | (c >> 11)) : c; }

static size_t fileRead(void* ctx, uint8_t* buf, size_t n) { return ((File*)ctx)->read(buf, n); }

// Reads a whole stored file once: structure check (MANI or MIMG) + CRC-32 of its bytes. Fills `out` and returns true when it is a valid screen for this display.
static bool customInspect(const char* path, SlotInfo& out) {
  File f = LittleFS.open(path, "r"); if (!f) return false;
  uint32_t len = (uint32_t)f.size(); uint8_t magic[2] = {0, 0};
  if (f.read(magic, 2) != 2) { f.close(); return false; }
  f.seek(0);
  bool mani = magic[0] == 'M' && magic[1] == 'A'; uint16_t frames = 1; bool ok;
  if (mani) ok = meow::maniValidate(fileRead, &f, len, TFT_W, TFT_H, &frames);
  else ok = meow::mimgValidate(fileRead, &f, len, TFT_W, TFT_H);
  uint32_t crc = 0;
  if (ok) {
    f.seek(0); uint8_t b[128]; uint32_t c = meow::crc32Begin(); size_t n;
    while ((n = f.read(b, sizeof b)) > 0) c = meow::crc32Update(c, b, n);
    crc = meow::crc32End(c); if (crc == 0) crc = 1;       // 0 is reserved for "none"
  }
  f.close();
  if (!ok) return false;
  out.crc = crc; out.bytes = len; out.mani = mani; out.frames = frames; return true;
}
static void slotWriteMeta(uint8_t s) {
  File m = LittleFS.open(slotMeta(s), "w"); if (!m) return;
  uint8_t b[10]; meow::wr32(b, slots[s].crc); meow::wr32(b + 4, slots[s].bytes); b[8] = slots[s].mani ? 1 : 0; b[9] = (uint8_t)(slots[s].frames > 255 ? 255 : slots[s].frames);
  m.write(b, sizeof b); m.flush(); m.close();
}
// Reads the sidecar; trusted only when it agrees with the file's size. Otherwise the file is inspected in full (and the sidecar rewritten).
static bool slotLoad(uint8_t s) {
  const char* path = slotPath(s); File f = LittleFS.open(path, "r"); if (!f) return false; uint32_t len = (uint32_t)f.size(); f.close();
  File m = LittleFS.open(slotMeta(s), "r");
  if (m) { uint8_t b[10]; size_t n = m.read(b, sizeof b); m.close(); if (n == 10 && meow::rd32(b + 4) == len && meow::rd32(b) != 0) { slots[s].crc = meow::rd32(b); slots[s].bytes = len; slots[s].mani = b[8]; slots[s].frames = b[9]; return true; } }
  SlotInfo si; if (!customInspect(path, si)) return false;
  slots[s] = si; slotWriteMeta(s); return true;
}

// Mounts the file system and finds the stored screens. Also finishes a replacement that was interrupted by a power cut between "remove old" and "rename new".
static void customBoot() {
  fsReady = LittleFS.begin(true);                          // true = format on first use
  for (int i = 0; i < NSLOT; i++) slots[i] = SlotInfo();
  if (!fsReady) { Serial.println("LittleFS: mount failed, custom screens disabled"); slotsChanged(); return; }
  for (int i = 0; i < NSLOT; i++) {
    if (!slotLoad((uint8_t)i)) { LittleFS.remove(slotPath((uint8_t)i)); LittleFS.remove(slotMeta((uint8_t)i)); slots[i] = SlotInfo(); }
  }
  if (!slots[0].crc) { SlotInfo si; if (customInspect(CUSTOM_TMP, si)) { LittleFS.rename(CUSTOM_TMP, slotPath(0)); slotLoad(0); } }   // an interrupted replacement of slot 0 (single-image firmware)
  LittleFS.remove(CUSTOM_TMP);
  slotsChanged();
  int n = 0; for (int i = 0; i < NSLOT; i++) if (slots[i].crc) n++;
  Serial.printf("Custom screens: %d stored\n", n);
}

struct FlashSink : meow::RxSink {
  File tmp; uint8_t artKind = 0; uint32_t artWant = 0, artAt = 0;
  uint32_t storedCustomCrc() override { return slots[0].crc; }
  uint32_t storedArtCrc() override { return artCrc; }
  uint32_t storedSlotCrc(uint8_t s) override { return s < NSLOT ? slots[s].crc : 0; }
  // ---- slots (kind 2) and the single custom image (kind 0 = slot 0)
  bool openSlot(uint8_t slot, uint32_t total, uint8_t& reason) override {
    (void)slot;
    if (!fsReady) { reason = meow::XR_FLASH; return false; }
    size_t freeB = LittleFS.totalBytes() - LittleFS.usedBytes();
    if (freeB < (size_t)total + FLASH_MARGIN) { reason = meow::XR_NO_SPACE; return false; }   // the old copy stays until the new one is complete, so both must fit
    LittleFS.remove(CUSTOM_TMP);
    tmp = LittleFS.open(CUSTOM_TMP, "w");
    if (!tmp) { reason = meow::XR_FLASH; return false; }
    return true;
  }
  bool writeSlot(const uint8_t* d, size_t n) override { return tmp && tmp.write(d, n) == n; }
  bool commitSlot(uint8_t slot, uint32_t crc, uint8_t& reason) override {
    tmp.flush(); tmp.close();
    SlotInfo si; if (!customInspect(CUSTOM_TMP, si)) { LittleFS.remove(CUSTOM_TMP); reason = meow::XR_BAD_IMAGE; return false; }
    const char* path = slotPath(slot);
    if (!LittleFS.rename(CUSTOM_TMP, path)) {                 // littlefs normally replaces the target atomically; if this build refuses, remove first
      LittleFS.remove(path);
      if (!LittleFS.rename(CUSTOM_TMP, path)) { reason = meow::XR_FLASH; slots[slot] = SlotInfo(); slotsChanged(); return false; }   // /custom.tmp is valid and (slot 0) promoted at the next boot
    }
    slots[slot] = si; slots[slot].crc = crc ? crc : 1; slotWriteMeta(slot); slotsChanged(); return true;
  }
  void abortSlot() override { if (tmp) tmp.close(); LittleFS.remove(CUSTOM_TMP); }
  void deleteSlot(uint8_t slot) override { if (slot >= NSLOT) return; if (fsReady) { LittleFS.remove(slotPath(slot)); LittleFS.remove(slotMeta(slot)); } slots[slot] = SlotInfo(); slotsChanged(); }
  // ---- kind 0 (single custom image) and kind 1 (album art)
  bool open(uint8_t kind, uint32_t total, uint16_t w, uint16_t h, uint8_t& reason) override {
    if (kind == meow::KIND_CUSTOM) return openSlot(0, total, reason);
    artReady = false;                                       // the old art is overwritten from now on
    artCrc = 0;                                             // the phone must not think the previous cover is still current
    if (!artBuf || artW != w || artH != h) {
      if (artBuf) { free(artBuf); artBuf = nullptr; }
      artBuf = (uint16_t*)malloc((size_t)w * h * 2);
      if (!artBuf) { reason = meow::XR_NO_MEMORY; artW = artH = 0; return false; }
      artW = w; artH = h;
    }
    artAt = 0; artWant = total; return true;
  }
  bool write(uint8_t kind, const uint8_t* d, size_t n) override {
    if (kind == meow::KIND_CUSTOM) return writeSlot(d, n);
    if (!artBuf || artAt + n > artWant) return false;
    memcpy((uint8_t*)artBuf + artAt, d, n); artAt += (uint32_t)n; return true;
  }
  bool commit(uint8_t kind, uint32_t crc, uint8_t& reason) override {
    if (kind == meow::KIND_CUSTOM) return commitSlot(0, crc, reason);
    if (artAt != artWant) { reason = meow::XR_SEQUENCE; return false; }
    for (size_t i = 0; i < (size_t)artW * artH; i++) artBuf[i] = fixColor(artBuf[i]);    // little-endian bytes from the phone are already host order on the ESP32
    artReady = true; artCrc = crc ? crc : 1; artRev++; return true;
  }
  void abort(uint8_t kind) override { if (kind == meow::KIND_CUSTOM) abortSlot(); else { artReady = false; artCrc = 0; } }
  void deleteCustom() override { deleteSlot(0); }
  void clearArt() override { artReady = false; artCrc = 0; artRev++; }
};
static FlashSink flashSink;
static meow::ImageReceiver imgRx(&flashSink, TFT_W, TFT_H);

// ------------------------------- custom screen drawing and playback -------------------------------
struct CustomView {
  bool linked = false; uint32_t drawnRev = 0xFFFFFFFF; bool drawnOk = true;
  uint8_t slot = 0, drawnSlot = 0xFF;                      // slot to show / slot that is on the display
  bool playing = false; File f; meow::ManiPlayer pl; uint32_t nextAt = 0;
};

static void customMessage(const char* l1, const char* l2) {
  G = &tft; Y0 = 0; tft.fillScreen(C_BG);
  drawPill("CUSTOM SCREEN", C_DATE, 6);
  gText(120, 150, l1, &FreeSansBold12pt7b, C_TEXT, 1);
  if (l2 && l2[0]) gText(120, 182, l2, &FreeSans9pt7b, C_TIME, 1);
  drawStar(30, 100, 9, C_LAV); drawStar(210, 108, 7, C_LAV);
}
static void customStop(CustomView& v) { if (v.f) v.f.close(); v.playing = false; }

// Draws the rectangle of the frame the player just started, 8 rows at a time (about 3.8 KB of RAM). Returns false when the data is bad.
static bool customBlit(CustomView& v, const meow::ManiFrame& fr) {
  static uint16_t rowsBuf[240 * 8];
  G = &tft; Y0 = 0; int y = 0;
  while (y < fr.h) {
    int want = fr.h - y < 8 ? fr.h - y : 8;
    int n = v.pl.rows(rowsBuf, want); if (n <= 0) return false;
    for (int i = 0; i < n * fr.w; i++) rowsBuf[i] = fixColor(rowsBuf[i]);
    tft.drawRGBBitmap(fr.x, fr.y + y, rowsBuf, fr.w, n); y += n;
  }
  return !v.pl.failed();
}
static bool customOpenAnim(CustomView& v) {
  customStop(v);
  v.f = LittleFS.open(slotPath(v.slot), "r");
  if (!v.f || !v.pl.begin(fileRead, &v.f, (uint32_t)v.f.size(), TFT_W, TFT_H)) { customStop(v); return false; }
  return true;
}
// Draws the next frame of the open animation and schedules the one after it. At the end: starts over (loop) or keeps the last picture on the display.
static bool customStep(CustomView& v, uint32_t now) {
  if (!v.pl.moreFrames()) {
    if (!v.pl.header().loop) { customStop(v); return true; }
    if (!customOpenAnim(v)) return false;
  }
  meow::ManiFrame fr; if (!v.pl.nextFrame(fr) || !customBlit(v, fr)) return false;
  uint16_t d = fr.delay < meow::MANI_MIN_DELAY_MS ? meow::MANI_MIN_DELAY_MS : fr.delay;
  v.nextAt = now + d; v.playing = true;
  if (!v.pl.moreFrames() && !v.pl.header().loop) { customStop(v); }          // played once: nothing more to do
  return true;
}
// Streams a still (MIMG) to the TFT 8 rows at a time. Returns false when the file could not be shown.
static bool customDrawStill(uint8_t slot) {
  static uint16_t rowsBuf[240 * 8];
  File f = LittleFS.open(slotPath(slot), "r");
  meow::MimgDecoder d;
  if (!f || !d.begin(fileRead, &f, (uint32_t)f.size(), TFT_W, TFT_H)) { if (f) f.close(); return false; }
  G = &tft; Y0 = 0; int y = 0;
  while (y < d.header().h) {
    int n = d.rows(rowsBuf, 8); if (n <= 0) break;
    for (int i = 0; i < n * d.header().w; i++) rowsBuf[i] = fixColor(rowsBuf[i]);
    tft.drawRGBBitmap(0, y, rowsBuf, d.header().w, n); y += n;
  }
  bool ok = d.finished() && !d.failed(); f.close(); return ok;
}
// Called when the screen is entered, the slot changed, or a stored screen changed.
static void customEnter(CustomView& v) {
  customStop(v); v.drawnRev = customRev; v.drawnSlot = v.slot; v.drawnOk = true;
  if (!fsReady || slotFirst() < 0) { customMessage("No custom design yet", "Send one from the app"); return; }
  if (!slotUsed(v.slot)) { int f = slotFirst(); if (f >= 0) { v.slot = (uint8_t)f; curSlot = v.slot; v.drawnSlot = v.slot; } }
  bool ok;
  if (slots[v.slot].mani) { ok = customOpenAnim(v) && customStep(v, millis()); }
  else ok = customDrawStill(v.slot);
  if (!ok) { customStop(v); customMessage("Design unreadable", "Send it again from the app"); v.drawnOk = false; }
}
// Every loop: redraw when something changed, otherwise play the next frame when its time has come.
static void customTick(CustomView& v) {
  if (v.drawnRev != customRev || v.drawnSlot != v.slot) { customEnter(v); return; }
  if (!v.playing) return;
  uint32_t now = millis();
  if ((int32_t)(now - v.nextAt) < 0) return;
  if (!customStep(v, now)) { customStop(v); customMessage("Design unreadable", "Send it again from the app"); v.drawnOk = false; }
}
