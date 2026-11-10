// Flash / RAM storage behind the image transfer, and the photo slideshow.
//   * photos: up to meow::PHOTO_SLOTS baseline JPEG files in LittleFS (/ph00.jpg .. /ph23.jpg), each exactly 240 x 320 and a few KB to a few tens of KB.
//     A new upload goes to /photo.tmp first and replaces its slot only after the CRC matched and a full decode succeeded, so a failed upload never
//     destroys the photo that is stored. Each slot also has a tiny sidecar /phNN.m (file CRC-32 + size) so boot does not have to read every file.
//   * the slideshow setting (play order and seconds per photo) is kept in /gallery.cfg, so it survives a restart without the phone.
//   * album art: a small raw RGB565 block in RAM (never written to flash), refreshed per track.
//   * the same bytes are never written twice: ImageReceiver answers DONE straight away when the CRC equals the one stored in that slot.
// LittleFS needs a data partition: use Tools > Partition Scheme > "No OTA (2MB APP/2MB SPIFFS)" or "Default 4MB with spiffs" (1.5 MB). With
// "Minimal SPIFFS" there is room for only a handful of photos; the app reads the free space from the display and warns before sending.
#pragma once
#include <LittleFS.h>
#include "gfx_common.h"
#include "protocol.h"
#include "crc32.h"
#include "jpeg_dec.h"
#include "image_rx.h"

static const char* const PHOTO_TMP = "/photo.tmp";
static const char* const GAL_CFG_PATH = "/gallery.cfg";
constexpr uint32_t FLASH_MARGIN = 8192;           // LittleFS metadata + block rounding headroom
constexpr int NPHOTO = meow::PHOTO_SLOTS;

struct PhotoInfo { uint32_t crc = 0; uint32_t bytes = 0; };   // crc 0 = empty
static PhotoInfo photos[NPHOTO];
static bool     fsReady = false;
static uint32_t galRev = 0;                       // bumps whenever a stored photo or the slideshow setting changes (the slideshow redraws)
static meow::GalleryCfg galCfg;                   // play order and seconds per photo
static uint8_t  shownSlot = 0;                    // the photo slot on the display (or shown last)
static uint16_t* artBuf = nullptr; static uint16_t artW = 0, artH = 0; static bool artReady = false; static uint32_t artRev = 0;
static uint32_t artCrc = 0;                       // CRC-32 of the art in RAM, 0 = none: lets the phone skip a repeated transfer
static uint16_t galBand[240 * 16];                // one band of the decoder's output (an MCU row is at most 16 pixel rows)
static meow::JpegDecoder galDec;                  // the decoder object (about 3.5 KB) lives here, not on the loop task's stack

static const char* photoPath(uint8_t s) { static char b[4][16]; static uint8_t n = 0; char* o = b[n++ & 3]; snprintf(o, 16, "/ph%02u.jpg", (unsigned)s); return o; }
static const char* photoMeta(uint8_t s) { static char b[4][16]; static uint8_t n = 0; char* o = b[n++ & 3]; snprintf(o, 16, "/ph%02u.m", (unsigned)s); return o; }
static bool photoUsed(uint8_t s) { return s < NPHOTO && photos[s].crc != 0; }
static uint32_t photoMask() { uint32_t m = 0; for (int i = 0; i < NPHOTO; i++) if (photos[i].crc) m |= 1u << i; return m; }
static int photoCount() { int n = 0; for (int i = 0; i < NPHOTO; i++) if (photos[i].crc) n++; return n; }
static uint32_t photosDigest() { uint32_t c[NPHOTO]; for (int i = 0; i < NPHOTO; i++) c[i] = photos[i].crc; return meow::photoDigest(c, NPHOTO); }
static void photosChanged() { galRev++; }

// Colours arrive as RGB565 from the decoder; boards with swapped red/blue need the fields exchanged.
static inline uint16_t fixColor(uint16_t c) { return SWAP_RED_BLUE ? (uint16_t)(((c & 0x1F) << 11) | (c & 0x07E0) | (c >> 11)) : c; }

static size_t fileRead(void* ctx, uint8_t* buf, size_t n) { return ((File*)ctx)->read(buf, n); }

// Reads a whole stored file once: complete decode check + CRC-32 of its bytes. Returns true when it is a valid 240x320 photo.
static bool photoInspect(const char* path, PhotoInfo& out) {
  File f = LittleFS.open(path, "r"); if (!f) return false;
  uint32_t len = (uint32_t)f.size();
  bool ok = len >= meow::PHOTO_MIN_FILE && len <= meow::PHOTO_MAX_FILE && meow::jpegValidate(galDec, fileRead, &f, len, TFT_W, TFT_H, galBand, sizeof galBand / sizeof galBand[0]);
  uint32_t crc = 0;
  if (ok) {
    f.seek(0); uint8_t b[128]; uint32_t c = meow::crc32Begin(); size_t n;
    while ((n = f.read(b, sizeof b)) > 0) c = meow::crc32Update(c, b, n);
    crc = meow::crc32End(c); if (crc == 0) crc = 1;       // 0 is reserved for "none"
  }
  f.close();
  if (!ok) return false;
  out.crc = crc; out.bytes = len; return true;
}
static void photoWriteMeta(uint8_t s) {
  File m = LittleFS.open(photoMeta(s), "w"); if (!m) return;
  uint8_t b[8]; meow::wr32(b, photos[s].crc); meow::wr32(b + 4, photos[s].bytes); m.write(b, sizeof b); m.flush(); m.close();
}
// Reads the sidecar; trusted only when it agrees with the file's size. Otherwise the file is inspected in full (and the sidecar rewritten).
static bool photoLoad(uint8_t s) {
  const char* path = photoPath(s); File f = LittleFS.open(path, "r"); if (!f) return false; uint32_t len = (uint32_t)f.size(); f.close();
  File m = LittleFS.open(photoMeta(s), "r");
  if (m) { uint8_t b[8]; size_t n = m.read(b, sizeof b); m.close(); if (n == 8 && meow::rd32(b + 4) == len && meow::rd32(b) != 0) { photos[s].crc = meow::rd32(b); photos[s].bytes = len; return true; } }
  PhotoInfo pi; if (!photoInspect(path, pi)) return false;
  photos[s] = pi; photoWriteMeta(s); return true;
}

// The play order that is really used: the phone's list without empty slots; photos the list does not mention follow in slot order (so a photo that
// arrived before the setting never stays invisible). Returns the number of slots written to seq (max PHOTO_SLOTS).
static int galSequence(uint8_t* seq) {
  int n = 0; uint32_t seen = 0;
  for (int i = 0; i < galCfg.n; i++) { uint8_t s = galCfg.order[i]; if (photoUsed(s) && !(seen >> s & 1)) { seq[n++] = s; seen |= 1u << s; } }
  for (int s = 0; s < NPHOTO; s++) if (photoUsed((uint8_t)s) && !(seen >> s & 1)) seq[n++] = (uint8_t)s;
  return n;
}
static void galSaveCfg() {
  uint8_t b[meow::GAL_CFG_MAX]; uint8_t n = meow::encodeGalleryCfg(galCfg, b);
  File f = LittleFS.open(GAL_CFG_PATH, "w"); if (!f) return; f.write(b, n); f.flush(); f.close();
}
static void galLoadCfg() {
  galCfg = meow::GalleryCfg();
  File f = LittleFS.open(GAL_CFG_PATH, "r"); if (!f) return;
  uint8_t b[meow::GAL_CFG_MAX + 2]; size_t n = f.read(b, sizeof b); f.close();
  meow::GalleryCfg c; if (n >= 5 && n <= meow::GAL_CFG_MAX && meow::parseGalleryCfg(b, (uint8_t)n, c)) galCfg = c;
}
// MSG_GALLERY from the phone. Stored only when it changed (flash wear); the slideshow redraws when it did.
static void galApplyCfg(const meow::GalleryCfg& c) {
  bool same = c.intervalSec == galCfg.intervalSec && c.n == galCfg.n && memcmp(c.order, galCfg.order, c.n) == 0;
  if (same) return;
  galCfg = c; if (fsReady) galSaveCfg(); photosChanged();
}

// Mounts the file system and finds the stored photos. Also removes the files of the earlier custom-screen feature, which would otherwise keep
// using flash for nothing.
static void photoBoot() {
  fsReady = LittleFS.begin(true);                          // true = format on first use
  for (int i = 0; i < NPHOTO; i++) photos[i] = PhotoInfo();
  if (!fsReady) { Serial.println("LittleFS: mount failed, gallery disabled"); photosChanged(); return; }
  static const char* const old[] = {"/custom.mimg", "/custom.tmp", "/s1.bin", "/s2.bin", "/s3.bin", "/s4.bin", "/s5.bin", "/s6.bin", "/s7.bin",
                                    "/s0.m", "/s1.m", "/s2.m", "/s3.m", "/s4.m", "/s5.m", "/s6.m", "/s7.m"};
  for (const char* p : old) if (LittleFS.exists(p)) LittleFS.remove(p);
  for (int i = 0; i < NPHOTO; i++) {
    if (!photoLoad((uint8_t)i)) { LittleFS.remove(photoPath((uint8_t)i)); LittleFS.remove(photoMeta((uint8_t)i)); photos[i] = PhotoInfo(); }
  }
  LittleFS.remove(PHOTO_TMP);
  galLoadCfg(); photosChanged();
  Serial.printf("Gallery: %d photo(s), %u s each\n", photoCount(), (unsigned)galCfg.intervalSec);
}

struct FlashSink : meow::RxSink {
  File tmp; uint32_t artWant = 0, artAt = 0;
  // ---- photos
  uint32_t storedPhotoCrc(uint8_t s) override { return s < NPHOTO ? photos[s].crc : 0; }
  bool openPhoto(uint8_t slot, uint32_t total, uint8_t& reason) override {
    (void)slot;
    if (!fsReady) { reason = meow::XR_FLASH; return false; }
    size_t freeB = LittleFS.totalBytes() - LittleFS.usedBytes();
    if (freeB < (size_t)total + FLASH_MARGIN) { reason = meow::XR_NO_SPACE; return false; }   // the old copy stays until the new one is complete, so both must fit
    LittleFS.remove(PHOTO_TMP);
    tmp = LittleFS.open(PHOTO_TMP, "w");
    if (!tmp) { reason = meow::XR_FLASH; return false; }
    return true;
  }
  bool writePhoto(const uint8_t* d, size_t n) override { return tmp && tmp.write(d, n) == n; }
  bool commitPhoto(uint8_t slot, uint32_t crc, uint8_t& reason) override {
    tmp.flush(); tmp.close();
    PhotoInfo pi; if (!photoInspect(PHOTO_TMP, pi)) { LittleFS.remove(PHOTO_TMP); reason = meow::XR_BAD_IMAGE; return false; }
    const char* path = photoPath(slot);
    if (!LittleFS.rename(PHOTO_TMP, path)) {                  // littlefs normally replaces the target atomically; if this build refuses, remove first
      LittleFS.remove(path);
      if (!LittleFS.rename(PHOTO_TMP, path)) { reason = meow::XR_FLASH; photos[slot] = PhotoInfo(); photosChanged(); return false; }
    }
    photos[slot] = pi; photos[slot].crc = crc ? crc : 1; photoWriteMeta(slot); photosChanged(); return true;
  }
  void abortPhoto() override { if (tmp) tmp.close(); LittleFS.remove(PHOTO_TMP); }
  void deletePhoto(uint8_t slot) override { if (slot >= NPHOTO) return; if (fsReady) { LittleFS.remove(photoPath(slot)); LittleFS.remove(photoMeta(slot)); } photos[slot] = PhotoInfo(); photosChanged(); }
  // ---- album art
  uint32_t storedArtCrc() override { return artCrc; }
  bool openArt(uint32_t total, uint16_t w, uint16_t h, uint8_t& reason) override {
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
  bool writeArt(const uint8_t* d, size_t n) override {
    if (!artBuf || artAt + n > artWant) return false;
    memcpy((uint8_t*)artBuf + artAt, d, n); artAt += (uint32_t)n; return true;
  }
  bool commitArt(uint32_t crc, uint8_t& reason) override {
    if (artAt != artWant) { reason = meow::XR_SEQUENCE; return false; }
    for (size_t i = 0; i < (size_t)artW * artH; i++) artBuf[i] = fixColor(artBuf[i]);    // little-endian bytes from the phone are already host order on the ESP32
    artReady = true; artCrc = crc ? crc : 1; artRev++; return true;
  }
  void abortArt() override { artReady = false; artCrc = 0; }
  void clearArt() override { artReady = false; artCrc = 0; artRev++; }
};
static FlashSink flashSink;
static meow::ImageReceiver imgRx(&flashSink, TFT_W, TFT_H);

// ------------------------------- the slideshow -------------------------------
struct GalleryView { bool linked = false; uint32_t drawnRev = 0xFFFFFFFF; int pos = 0; uint32_t nextAt = 0; };

static void galleryMessage(const char* l1, const char* l2) {
  G = &tft; Y0 = 0; tft.fillScreen(C_BG);
  drawPill("PHOTOS", C_DATE, 6);
  gText(120, 150, l1, &FreeSansBold12pt7b, C_TEXT, 1);
  if (l2 && l2[0]) gText(120, 182, l2, &FreeSans9pt7b, C_TIME, 1);
  drawStar(30, 100, 9, C_LAV); drawStar(210, 108, 7, C_LAV);
}
// Decodes one stored photo straight to the TFT, one MCU row at a time. Returns false when the file could not be shown completely.
static bool galleryDraw(uint8_t slot) {
  File f = LittleFS.open(photoPath(slot), "r");
  meow::JpegDecoder& d = galDec;
  if (!f || !d.begin(fileRead, &f, (uint32_t)f.size()) || d.width() != TFT_W || d.height() != TFT_H || (size_t)d.width() * d.bandRows() > sizeof galBand / sizeof galBand[0]) { if (f) f.close(); return false; }
  G = &tft; Y0 = 0; int y = 0, n;
  while ((n = d.nextBand(galBand)) > 0) {
    if (SWAP_RED_BLUE) for (int i = 0; i < n * TFT_W; i++) galBand[i] = fixColor(galBand[i]);
    tft.drawRGBBitmap(0, y, galBand, TFT_W, n); y += n;
  }
  bool ok = d.finished(); f.close(); return ok;
}
static uint32_t galleryHoldMs() { return (uint32_t)galCfg.intervalSec * 1000u; }
// Shows the photo at v.pos of the play order. Called when the screen is entered, and when a stored photo or the setting changed.
static void galleryEnter(GalleryView& v) {
  v.drawnRev = galRev;
  uint8_t seq[NPHOTO]; int n = galSequence(seq);
  if (!fsReady || n == 0) { galleryMessage("No photos yet", "Add some in the app"); v.nextAt = millis() + 1000; return; }
  if (v.pos >= n || v.pos < 0) v.pos = 0;
  shownSlot = seq[v.pos];
  if (!galleryDraw(shownSlot)) galleryMessage("Photo unreadable", "Send it again from the app");
  v.nextAt = millis() + galleryHoldMs();
}
// Every loop: redraw when something changed, otherwise move on to the next photo when its time is over.
static void galleryTick(GalleryView& v) {
  if (v.drawnRev != galRev) { galleryEnter(v); return; }
  uint32_t now = millis();
  if ((int32_t)(now - v.nextAt) < 0) return;
  uint8_t seq[NPHOTO]; int n = galSequence(seq);
  if (n <= 1) { v.nextAt = now + galleryHoldMs(); return; }      // nothing to move to
  v.pos = (v.pos + 1) % n; galleryEnter(v);
}
