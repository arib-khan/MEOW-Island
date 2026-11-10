// Drives the whole sketch through the BLE write path for the NEW features: screen time, notifications, music extras, album art and the
// photo gallery (JPEG transfer -> LittleFS -> decoded pixels on the TFT -> slideshow timing -> reboot -> still there). Uses stand-ins for GFX/LittleFS/NimBLE (see README).
#include "common.h"
#include <vector>
#include <string>
#include "../MeowIsland/MeowIsland.ino"

static int fails = 0, ok = 0;
#define CHECK(c, m) do { if (c) ok++; else { fails++; printf("FAIL: %s (line %d)\n", m, __LINE__); } } while (0)
typedef std::vector<uint8_t> Bytes;
static NimBLEConnInfo ci; static uint32_t T = 1000;
static void run(uint32_t ms) { for (uint32_t e = T + ms; T < e; T += 20) { host_millis = T; loop(); } }
static Bytes frame(uint8_t type, const Bytes& p) { Bytes f = {0xA5, type, (uint8_t)p.size()}; f.insert(f.end(), p.begin(), p.end()); return f; }
static void write(const Bytes& b, size_t chunk = 244) { for (size_t i = 0; i < b.size(); i += chunk) { bleRx->value.v.assign(b.begin() + i, b.begin() + std::min(b.size(), i + chunk)); bleRxCb.onWrite(bleRx, ci); } }
static void le16(Bytes& b, uint32_t v) { b.push_back(v & 255); b.push_back((v >> 8) & 255); } static void le32(Bytes& b, uint32_t v) { le16(b, v & 0xFFFF); le16(b, v >> 16); }
static void str(Bytes& b, const char* s) { b.push_back((uint8_t)strlen(s)); b.insert(b.end(), s, s + strlen(s)); }
static uint16_t px(int x, int y) { return tft.getBuffer()[(size_t)y * 240 + x]; }

// photo files made with Pillow (tests/photo_*.jpg): left half red / right half blue with an 8-row green stripe on top, and the same with the colours rotated
static Bytes readFile(const char* path) { FILE* f = fopen(path, "rb"); Bytes o; if (!f) return o; uint8_t buf[4096]; size_t n; while ((n = fread(buf, 1, sizeof buf, f)) > 0) o.insert(o.end(), buf, buf + n); fclose(f); return o; }
static bool near565(uint16_t a, uint16_t b) {                                  // JPEG is lossy: flat saturated colours come back within a step or two
  return abs((a >> 11) - (b >> 11)) <= 2 && abs(((a >> 5) & 63) - ((b >> 5) & 63)) <= 3 && abs((a & 31) - (b & 31)) <= 2;
}
// the phone's transfer, without losses (loss handling is covered by image_test.cpp)
static void sendImage(const Bytes& file, uint8_t id, uint8_t kind, uint16_t w, uint16_t h, uint32_t crcOverride = 0, bool use = false, uint8_t slot = 0) {
  Bytes b; b.push_back(id); b.push_back(kind); le32(b, (uint32_t)file.size()); le32(b, use ? crcOverride : meow::crc32Of(file.data(), file.size())); le16(b, 180); le16(b, w); le16(b, h);
  if (kind == meow::KIND_PHOTO) b.push_back(slot);
  write(frame(meow::MSG_IMG_BEGIN, b)); run(60);
  uint16_t seq = 0;
  for (size_t off = 0; off < file.size(); off += 180, seq++) {
    Bytes d = {id}; le16(d, seq); size_t n = std::min<size_t>(180, file.size() - off); d.insert(d.end(), file.begin() + off, file.begin() + off + n);
    write(frame(meow::MSG_IMG_DATA, d)); run(20);
  }
  write(frame(meow::MSG_IMG_END, {id})); run(80);
}
static meow::XferStatus xs() { return imgRx.status(); }
static void selectScreen(uint8_t s) { write(frame(meow::MSG_SCREEN, {s})); run(200); }

int main(int, char** argv) {
  const char* OUT = argv[1];
  auto snap = [&](const char* n) { char p[256]; snprintf(p, sizeof p, "%s/%s.ppm", OUT, n); dumpPpm(p, tft); };
  setenv("TZ", "UTC", 1); tzset(); host_millis = T; host_epoch = 0; setup(); run(400);
  MeowServerCb scb; scb.onConnect(bleServer, ci); scb.onPassKeyDisplay(); run(60); NimBLEDevice::bonds = 1; scb.onAuthenticationComplete(ci); run(200);
  CHECK(bleSecure, "paired link is secure");

  // ---- screen ids
  CHECK(meow::SCREEN_COUNT == 6, "six screens"); selectScreen(3); CHECK(curScreen == meow::SCREEN_SCREENTIME, "phone selects screen time"); selectScreen(4); CHECK(curScreen == meow::SCREEN_NOTIFS, "phone selects notifications"); selectScreen(9); CHECK(curScreen == meow::SCREEN_CLOCK, "unknown screen falls back to the clock");

  // ---- screen time
  selectScreen(3); snap("st_nodata");
  { Bytes p = {9}; le16(p, 222); le16(p, 240); p.push_back(3); le16(p, 95); str(p, "Instagram"); le16(p, 70); str(p, "YouTube"); le16(p, 30); str(p, "Chrome"); write(frame(meow::MSG_SCREENTIME, p)); run(100); }
  CHECK(stView.haveData && stView.d.totalMin == 222 && stView.d.goalOn && stView.d.n == 3, "screen time stored"); snap("st_goal");
  { Bytes p = {2}; le16(p, 0); le16(p, 0); p.push_back(0); write(frame(meow::MSG_SCREENTIME, p)); run(100); } CHECK(stView.d.needsPermission, "permission state"); snap("st_permission");
  { Bytes p = {13}; le16(p, 400); le16(p, 240); p.push_back(1); le16(p, 400); str(p, "Social"); write(frame(meow::MSG_SCREENTIME, p)); run(100); } snap("st_over_goal");
  { Bytes bad = {1, 0xFF, 0xFF, 0, 0, 0}; write(frame(meow::MSG_SCREENTIME, bad)); run(60); CHECK(stView.d.totalMin == 400 && !stView.d.needsPermission, "an invalid frame changes nothing"); }

  // ---- notifications
  selectScreen(4); snap("nt_nodata");
  { Bytes p = {1, 3}; le16(p, 0); str(p, "WhatsApp"); str(p, "Aditi"); str(p, "Are we still meeting at 6?"); le16(p, 14); str(p, "Gmail"); str(p, ""); str(p, "Your receipt from Cafe"); le16(p, 190); str(p, "Instagram"); str(p, ""); str(p, ""); write(frame(meow::MSG_NOTIFS, p)); run(100); }
  CHECK(ntView.haveData && ntView.d.n == 3, "notifications stored"); snap("nt_list");
  { Bytes p = {2, 0}; write(frame(meow::MSG_NOTIFS, p)); run(100); } snap("nt_access");
  { Bytes p = {1, 0}; write(frame(meow::MSG_NOTIFS, p)); run(100); } snap("nt_empty");

  // ---- music extras + art ----
  auto music2 = [&](uint8_t flags, uint8_t ly, uint16_t idx, const char* src, const char* prev, const char* cur, const char* next) {
    Bytes b = {flags, ly}; le16(b, idx); str(b, src); str(b, prev); str(b, cur); str(b, next);
    write(frame(meow::MSG_MUSIC2, b)); run(60);
  };
  auto sendBand = [&](uint8_t token, uint16_t line, uint8_t fill) {
    Bytes mask(meow::LYR_BYTES, fill);
    for (size_t off = 0; off < mask.size(); off += meow::LYR_CHUNK) {
      uint16_t seq = (uint16_t)(off / meow::LYR_CHUNK);
      Bytes p = {token, (uint8_t)seq}; le16(p, line); le16(p, meow::LYR_W); le16(p, meow::LYR_H);
      size_t n = std::min<size_t>(meow::LYR_CHUNK, mask.size() - off); p.insert(p.end(), mask.begin() + off, mask.begin() + off + n);
      write(frame(meow::MSG_LYRIC, p)); run(20);
    }
    run(250);
  };
  { Bytes m = {3}; le16(m, 102); le16(m, 318); str(m, "Kabhi Kabhi Aditi"); str(m, "Rashid Ali"); write(frame(meow::MSG_MUSIC, m)); run(60); }
  music2(2, 2, 3, "SPOTIFY", "previous line of the song", "the current line is quite a bit longer than the screen", "next line");
  selectScreen(2); run(400); CHECK(musicView.haveData2 && isSpotify(musicView), "music extras stored"); snap("music_lyrics_noart");
  // phone-rendered mask: all bits set, so the lyric zone pixels must be the lyric colours
  sendBand(9, 3, 0xFF);
  CHECK(lyrReady && lyrValid(3), "lyric mask received and tied to the current line"); snap("music_lyrics_mask");
  CHECK(px(12, 240) == C_MUTE && px(12, 260) == C_TEXT && px(12, 306) == C_MUTE, "lyric mask pixels drawn in the lyric zone");
  Bytes art(80 * 80 * 2); for (int i = 0; i < 80 * 80; i++) { art[i * 2] = 0x1F; art[i * 2 + 1] = 0xF8 & 0; }   // pure blue (0x001F) pixels
  sendImage(art, 1, meow::KIND_ART, 80, 80); CHECK(xs().state == meow::XFER_DONE && artReady && artW == 80, "album art received into RAM");
  CHECK(xs().artCrc == meow::crc32Of(art.data(), art.size()), "the status carries the album-art CRC so a replay can skip the transfer");
  { Bytes p = {3, 2}; le16(p, 3); str(p, "SPOTIFY"); str(p, "previous line of the song"); str(p, "the current line is quite a bit longer than the screen"); str(p, "next line"); write(frame(meow::MSG_MUSIC2, p)); run(300); }
  CHECK(px(120, 80) == 0x001F, "album art pixels are on the TFT"); snap("music_lyrics_art");
  // video: no lyric zone, thumbnail/title/progress, bottom bar
  { Bytes m = {3}; le16(m, 102); le16(m, 318); str(m, "A YouTube video title"); str(m, "A channel"); write(frame(meow::MSG_MUSIC, m)); run(80); }
  music2(4, 0, 0, "YOUTUBE", "", "", ""); selectScreen(2); run(500);
  CHECK(musicVideo(musicView) && musicView.d2.video && musicView.d2.lyricState == 0, "YouTube is a video: no lyrics state");
  CHECK(px(30, 302) != C_BG, "video progress bar is at the bottom of the screen"); snap("music_video");
  // switching back to a music source clears the video flag and asks for the lyric mask of the new line
  music2(2, 2, 4, "AMAZON MUSIC", "prev", "current", "next");
  sendBand(10, 4, 0xFF); run(300);
  CHECK(!musicVideo(musicView) && lyrValid(4), "Amazon Music gets lyrics again");
  // a new track clears the old lyric mask and art flag
  { Bytes m = {3}; le16(m, 5); le16(m, 200); str(m, "Another Song"); str(m, "Someone"); write(frame(meow::MSG_MUSIC, m)); run(300); CHECK(musicView.d2.lyricState == 0 && !musicView.d2.hasArt && !musicView.d2.cur[0], "a new track clears the old lyric lines and art flag"); }
  CHECK(!lyrReady, "a new track clears the old lyric mask");

  // ---- photo slideshow: BOOT skips it while no photo is stored
  CHECK(photoCount() == 0, "no photo yet"); selectScreen(meow::SCREEN_MUSIC);
  { std::vector<uint8_t> seen; for (int i = 0; i < 6; i++) { host_btn_low = true; run(200); host_btn_low = false; run(100); seen.push_back(curScreen); } bool hit = false; for (auto s : seen) hit |= (s == meow::SCREEN_GALLERY); CHECK(!hit, "BOOT tap never lands on the empty slideshow"); }
  selectScreen(meow::SCREEN_GALLERY); snap("gallery_empty");

  // ---- photos
  Bytes jpgA = readFile("../../tests/photo_red_blue.jpg"), jpgB = readFile("../../tests/photo_green_red.jpg"); CHECK(jpgA.size() > 200 && jpgB.size() > 200, "test photos found");
  sendImage(jpgA, 5, meow::KIND_PHOTO, 240, 320, 0, false, 2); CHECK(xs().state == meow::XFER_DONE && xs().reason == 0 && photos[2].crc == meow::crc32Of(jpgA.data(), jpgA.size()) && xs().photoCrc == photos[2].crc, "photo stored in slot 2, crc reported in the status");
  CHECK(fsData().files.count("/ph02.jpg") && fsData().files.count("/ph02.m") && !fsData().files.count("/photo.tmp"), "file in flash, no temp file left"); run(300);
  CHECK(near565(px(10, 100), 0xF800) && near565(px(230, 100), 0x001F) && near565(px(100, 3), 0x07E0), "pixels of the photo are on the TFT"); snap("gallery_shown");
  { uint8_t st[meow::STATUS_FULL_LEN]; meow::Status s0; meow::encodeStatus(s0, st); meow::encodeStatusExt(xs(), st + meow::STATUS_LEN); CHECK(st[10] == meow::XFER_DONE && st[11] == 5, "status extension carries state and id"); }
  { bool hit = false; for (int i = 0; i < 6; i++) { host_btn_low = true; run(200); host_btn_low = false; run(100); hit |= curScreen == meow::SCREEN_GALLERY; } CHECK(hit, "BOOT tap reaches the slideshow once a photo exists"); }
  selectScreen(meow::SCREEN_CLOCK); CHECK(photoCount() == 1, "going back to the clock keeps the photo");

  // ---- slideshow: two photos, play order and timing from the phone
  sendImage(jpgB, 6, meow::KIND_PHOTO, 240, 320, 0, false, 5); CHECK(xs().state == meow::XFER_DONE && photoCount() == 2, "second photo stored");
  { Bytes c = {1}; le16(c, 3); c.push_back(0); c.push_back(2); c.push_back(5); c.push_back(2); write(frame(meow::MSG_GALLERY, c)); run(100); }
  CHECK(galCfg.intervalSec == 3 && galCfg.n == 2 && galCfg.order[0] == 5, "slideshow setting stored");
  selectScreen(meow::SCREEN_GALLERY); run(300); CHECK(near565(px(10, 100), 0x07E0), "the first photo of the order (slot 5) is shown first");
  run(2000); CHECK(near565(px(10, 100), 0x07E0), "it stays for the whole interval");
  run(1500); CHECK(near565(px(10, 100), 0xF800), "after 3 seconds the next photo (slot 2) is shown");
  run(3200); CHECK(near565(px(10, 100), 0x07E0), "then it starts over"); snap("gallery_second");
  { Bytes bad = {1, 3, 0, 0, 2, 5, 5}; write(frame(meow::MSG_GALLERY, bad)); run(60); CHECK(galCfg.order[1] == 2, "an invalid slideshow setting changes nothing"); }

  // ---- replacing: a failed upload keeps the old photo, a good one replaces it
  { int before = (int)fsData().files.size(); (void)before;
    sendImage(jpgB, 7, meow::KIND_PHOTO, 240, 320, 0xDEADBEEF, true, 2); CHECK(xs().state == meow::XFER_FAILED && xs().reason == meow::XR_CRC, "bad CRC rejected");
    CHECK(photos[2].crc == meow::crc32Of(jpgA.data(), jpgA.size()) && fsData().files["/ph02.jpg"] == jpgA && !fsData().files.count("/photo.tmp"), "old photo untouched, temp file removed");
    fsData().failWrites = true; sendImage(jpgB, 8, meow::KIND_PHOTO, 240, 320, 0, false, 2); fsData().failWrites = false; CHECK(xs().state == meow::XFER_FAILED && fsData().files["/ph02.jpg"] == jpgA, "flash write failure keeps the old photo");
    size_t was = fsData().total; fsData().total = LittleFS.usedBytes() + 6000; sendImage(jpgB, 9, meow::KIND_PHOTO, 240, 320, 0, false, 2); fsData().total = was; CHECK(xs().state == meow::XFER_FAILED && xs().reason == meow::XR_NO_SPACE && fsData().files["/ph02.jpg"] == jpgA, "no space is reported and the old photo survives");
    Bytes junk = jpgA; junk[junk.size() / 2] ^= 0xFF; junk[20] ^= 0xFF; junk[21] ^= 0xFF; sendImage(junk, 10, meow::KIND_PHOTO, 240, 320, 0, false, 3); CHECK(xs().state == meow::XFER_FAILED && !fsData().files.count("/ph03.jpg"), "a file that arrives intact but is not a decodable photo is refused");
    Bytes notJpeg(500, 7); sendImage(notJpeg, 11, meow::KIND_PHOTO, 240, 320, 0, false, 3); CHECK(xs().state == meow::XFER_FAILED && xs().reason == meow::XR_BAD_IMAGE, "bytes that are not a JPEG are refused");
    sendImage(jpgB, 12, meow::KIND_PHOTO, 240, 320, 0, false, 2); CHECK(xs().state == meow::XFER_DONE && fsData().files["/ph02.jpg"] == jpgB, "a good upload replaces it");
    sendImage(jpgB, 13, meow::KIND_PHOTO, 240, 320, 0, false, 2); CHECK(xs().state == meow::XFER_DONE && !fsData().files.count("/photo.tmp"), "re-sending the same photo is acknowledged without a rewrite");
  }

  // ---- reboot: photos and the slideshow setting come back from flash
  { uint32_t digest = photosDigest(); for (int i = 0; i < NPHOTO; i++) photos[i] = PhotoInfo(); galCfg = meow::GalleryCfg(); fsReady = false; photoBoot();
    CHECK(fsReady && photosDigest() == digest && photoCount() == 2 && galCfg.intervalSec == 3 && galCfg.order[0] == 5, "photos and the setting are restored after a reboot"); }
  { fsData().files["/ph05.jpg"][30] ^= 0xFF; fsData().files["/ph05.jpg"][31] ^= 0xFF; fsData().files.erase("/ph05.m"); photoBoot(); CHECK(!photoUsed(5) && !fsData().files.count("/ph05.jpg"), "a damaged photo is dropped at boot, the others stay"); }

  // ---- connection lost mid-transfer: partial data is dropped
  { Bytes b; b.push_back(14); b.push_back(meow::KIND_PHOTO); le32(b, (uint32_t)jpgA.size()); le32(b, meow::crc32Of(jpgA.data(), jpgA.size())); le16(b, 180); le16(b, 240); le16(b, 320); b.push_back(7);
    write(frame(meow::MSG_IMG_BEGIN, b)); run(60); Bytes d = {14}; le16(d, 0); d.insert(d.end(), jpgA.begin(), jpgA.begin() + 180); write(frame(meow::MSG_IMG_DATA, d)); run(60); CHECK(xs().state == meow::XFER_RECEIVING, "receiving");
    scb.onDisconnect(bleServer, ci, 0); run(200); CHECK(xs().state != meow::XFER_RECEIVING && !fsData().files.count("/photo.tmp"), "link loss aborts the half-received photo and removes the temp file"); }

  // ---- delete
  scb.onConnect(bleServer, ci); scb.onAuthenticationComplete(ci); run(100);
  write(frame(meow::MSG_IMG_CTRL, {meow::CTRL_DELETE_PHOTO, 2})); run(100); CHECK(!photoUsed(2) && !fsData().files.count("/ph02.jpg") && !fsData().files.count("/ph02.m"), "delete removes the photo and its sidecar");
  CHECK(!fsData().files.count("/custom.mimg"), "no file of the removed custom-screen feature is left");
  printf("sketch smoke test (new features): %d checks passed, %d failed\n", ok, fails); return fails ? 1 : 0;
}
