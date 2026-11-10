// Runs the real firmware/MeowIsland/photo_store.h (photo files, sidecars, boot recovery, slideshow order and timing) against the in-memory LittleFS
// stand-in and a fake TFT. run.sh copies the headers next to cs_stubs/gfx_common.h first (so the stand-in is found instead of the real gfx_common.h).
#include <cassert>
#include <string>
#include "Arduino.h"
#include "LittleFS.h"
#include "photo_store.h"
#include <fstream>
uint32_t host_millis = 0; time_t host_epoch = 0; bool host_btn_low = false, host_restarted = false;
using namespace meow;
typedef std::vector<uint8_t> Bytes;
static int passed = 0, failed = 0;
#define CHECK(c, msg) do { if (c) passed++; else { failed++; printf("FAIL: %s  (line %d)\n", msg, __LINE__); } } while (0)
static Bytes readFile(const std::string& p) { std::ifstream f(p, std::ios::binary); return Bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>()); }
static bool near565(uint16_t a, uint16_t b) { return abs((a >> 11) - (b >> 11)) <= 2 && abs(((a >> 5) & 63) - ((b >> 5) & 63)) <= 3 && abs((a & 31) - (b & 31)) <= 2; }
static uint16_t px(int x, int y) { return tft.px[(size_t)y * 240 + x]; }
static void upload(const Bytes& f, uint8_t id, uint8_t slot, uint32_t crcOverride = 0, bool useCrc = false) {
  ImgBegin b; b.xfer = id; b.kind = KIND_PHOTO; b.total = (uint32_t)f.size(); b.crc = useCrc ? crcOverride : crc32Of(f.data(), f.size()); b.chunk = 180; b.w = 240; b.h = 320; b.slot = slot;
  imgRx.onBegin(b, 1); if (imgRx.state() != XFER_RECEIVING) return; uint16_t seq = 0;
  for (size_t off = 0; off < f.size(); off += 180) { size_t n = std::min<size_t>(180, f.size() - off); uint8_t p[200]; p[0] = id; wr16(p + 1, seq++); memcpy(p + 3, f.data() + off, n); imgRx.onData(p, (uint8_t)(n + 3), 1); }
  uint8_t e[1] = {id}; imgRx.onEnd(e, 1);
}
static void setCfg(uint16_t sec, std::vector<uint8_t> order) { GalleryCfg c; c.intervalSec = sec; c.n = (uint8_t)order.size(); for (size_t i = 0; i < order.size(); i++) c.order[i] = order[i]; galApplyCfg(c); }
int main() {
  Bytes A = readFile("../../tests/photo_red_blue.jpg"), B = readFile("../../tests/photo_green_red.jpg"), C = readFile("out/jc/p_q50_s2.jpg"); CHECK(A.size() > 500 && B.size() > 500 && C.size() > 500, "test photos found");
  // legacy files of the removed custom-screen feature are cleaned up at the first boot
  fsData().total = 1500 * 1024; fsData().files["/custom.mimg"] = Bytes(30000, 1); fsData().files["/s3.bin"] = Bytes(9000, 2); fsData().files["/s3.m"] = Bytes(10, 3); fsData().files["/custom.tmp"] = Bytes(5, 4);
  photoBoot(); CHECK(fsReady && photoCount() == 0 && photoMask() == 0 && photosDigest() == 0, "empty display boots with no photos");
  CHECK(!fsData().files.count("/custom.mimg") && !fsData().files.count("/s3.bin") && !fsData().files.count("/s3.m") && !fsData().files.count("/custom.tmp"), "files of the removed feature are deleted to free flash");
  GalleryView v; messages = 0; galleryEnter(v); CHECK(messages == 2 && firstMsg == "No photos yet", "the empty slideshow explains itself");

  // ---- two photos
  upload(A, 1, 2); upload(B, 2, 5); uint32_t crcA = crc32Of(A.data(), A.size()), crcB = crc32Of(B.data(), B.size());
  CHECK(imgRx.state() == XFER_DONE && photos[2].crc == crcA && photos[5].crc == crcB && photoMask() == ((1u << 2) | (1u << 5)) && photoCount() == 2, "both photos stored");
  CHECK(fsData().files.count("/ph02.jpg") && fsData().files.count("/ph02.m") && fsData().files.count("/ph05.jpg") && !fsData().files.count("/photo.tmp"), "files and sidecars, no temp file");
  { uint32_t c[NPHOTO] = {0}; c[2] = crcA; c[5] = crcB; CHECK(photosDigest() == photoDigest(c, NPHOTO) && photosDigest() != 0, "digest covers the stored photos"); }
  uint8_t seq[NPHOTO]; CHECK(galSequence(seq) == 2 && seq[0] == 2 && seq[1] == 5, "without a setting the photos play in slot order");

  // ---- the slideshow: order and interval from the phone
  setCfg(3, {5, 2}); CHECK(galCfg.intervalSec == 3 && galSequence(seq) == 2 && seq[0] == 5 && seq[1] == 2, "the phone's order is used");
  host_millis = 10000; v = GalleryView(); galleryEnter(v); CHECK(near565(px(10, 100), 0x07E0) && near565(px(230, 100), 0xF800) && shownSlot == 5, "slot 5 (green | red) is shown first");
  int calls = tft.bitmapCalls; host_millis = v.nextAt - 1; galleryTick(v); CHECK(tft.bitmapCalls == calls, "nothing is drawn before the interval is over");
  CHECK(v.nextAt == 10000 + 3000, "the interval is 3 seconds");
  host_millis = v.nextAt; galleryTick(v); CHECK(near565(px(10, 100), 0xF800) && near565(px(230, 100), 0x001F) && shownSlot == 2, "then slot 2 (red | blue)");
  host_millis = v.nextAt; galleryTick(v); CHECK(near565(px(10, 100), 0x07E0) && shownSlot == 5, "then it starts over");
  { uint32_t rev = galRev; setCfg(3, {5, 2}); CHECK(galRev == rev, "an unchanged setting causes no redraw and no flash write"); setCfg(7, {5, 2}); CHECK(galRev != rev && galCfg.intervalSec == 7, "a changed interval redraws"); host_millis += 20; galleryTick(v); CHECK(v.nextAt == host_millis + 7000, "the new interval applies at once"); }
  setCfg(3, {5, 2});

  // ---- reboot: photos, order and interval come back from flash
  { uint32_t d = photosDigest(); for (int i = 0; i < NPHOTO; i++) photos[i] = PhotoInfo(); galCfg = GalleryCfg(); fsReady = false; photoBoot();
    CHECK(photosDigest() == d && galCfg.intervalSec == 3 && galCfg.n == 2 && galCfg.order[0] == 5, "photos and the slideshow setting survive a reboot"); }
  fsData().files["/ph02.m"].clear(); { uint32_t d = photosDigest(); photoBoot(); CHECK(photosDigest() == d && fsData().files["/ph02.m"].size() == 8, "a missing sidecar is rebuilt from the file"); }
  { Bytes& f = fsData().files["/ph05.jpg"]; f[300] ^= 0xFF; f.resize(f.size() - 50); fsData().files.erase("/ph05.m"); photoBoot(); CHECK(!photoUsed(5) && !fsData().files.count("/ph05.jpg") && photoUsed(2), "a damaged photo is dropped at boot, the other stays"); }
  { fsData().files["/gallery.cfg"] = Bytes{9, 9, 9}; photoBoot(); CHECK(galCfg.intervalSec == GAL_DEFAULT_SEC && galCfg.n == 0, "a damaged setting file falls back to the defaults"); setCfg(3, {5, 2}); }
  upload(B, 3, 5);

  // ---- failure, space and replacement rules
  { const Bytes old2 = fsData().files["/ph02.jpg"]; Bytes bad = C; bad[bad.size() / 2] ^= 0xFF; bad[20] ^= 0xFF; bad[21] ^= 0xFF; upload(bad, 9, 2);
    CHECK(imgRx.state() == XFER_FAILED && fsData().files["/ph02.jpg"] == old2 && photos[2].crc == crcA && !fsData().files.count("/photo.tmp"), "a failed upload leaves the stored photo and removes the temp file"); }
  { fsData().failWrites = true; upload(C, 10, 2); fsData().failWrites = false; CHECK(imgRx.state() == XFER_FAILED && photos[2].crc == crcA, "a flash write failure keeps the old photo"); }
  { size_t was = fsData().total; fsData().total = LittleFS.usedBytes() + 3000; upload(C, 11, 2); fsData().total = was; CHECK(imgRx.state() == XFER_FAILED && imgRx.status().reason == XR_NO_SPACE && photos[2].crc == crcA, "not enough space is reported and nothing is lost"); }
  { uint32_t rev = galRev; upload(C, 12, 2); CHECK(imgRx.state() == XFER_DONE && photos[2].crc == crc32Of(C.data(), C.size()) && galRev != rev, "a good upload replaces the photo and triggers a redraw"); }
  { size_t w = 0; for (auto& kv : fsData().files) w += kv.second.size(); upload(C, 13, 2); size_t w2 = 0; for (auto& kv : fsData().files) w2 += kv.second.size(); CHECK(imgRx.state() == XFER_DONE && w == w2, "re-sending the same photo changes nothing in flash"); }
  upload(A, 14, 2);

  // ---- a photo the setting does not mention still plays; deleting the shown photo moves on
  setCfg(3, {5}); CHECK(galSequence(seq) == 2 && seq[0] == 5 && seq[1] == 2, "photos missing from the list follow in slot order");
  upload(B, 15, 7); CHECK(galSequence(seq) == 3 && seq[2] == 7, "a new photo joins the slideshow at once"); setCfg(3, {7, 5, 2});
  v = GalleryView(); host_millis += 50; galleryEnter(v); CHECK(shownSlot == 7, "slot 7 first"); 
  imgRx.onDeletePhoto(7); CHECK(!photoUsed(7) && !fsData().files.count("/ph07.jpg") && !fsData().files.count("/ph07.m"), "delete removes file and sidecar"); host_millis += 10; galleryTick(v); CHECK(shownSlot == 5, "the slideshow redraws and shows the first remaining photo of the order");
  imgRx.onDeletePhoto(5); imgRx.onDeletePhoto(2); messages = 0; host_millis += 10; galleryTick(v); CHECK(photoCount() == 0 && messages == 2 && firstMsg == "No photos yet", "with every photo deleted the slideshow says so");

  // ---- an unreadable file shows a message instead of garbage
  upload(A, 16, 4); fsData().files["/ph04.jpg"][60] ^= 0xFF; fsData().files["/ph04.jpg"].resize(fsData().files["/ph04.jpg"].size() - 40); messages = 0; v = GalleryView(); host_millis += 10; galleryEnter(v); CHECK(messages == 2 && firstMsg == "Photo unreadable", "a damaged stored photo shows a message");
  // ---- album art is independent
  { Bytes art(40 * 40 * 2, 5); ImgBegin b; b.xfer = 20; b.kind = KIND_ART; b.total = (uint32_t)art.size(); b.crc = crc32Of(art.data(), art.size()); b.chunk = 180; b.w = 40; b.h = 40; imgRx.onBegin(b, 1);
    for (size_t off = 0, s = 0; off < art.size(); off += 180, s++) { size_t n = std::min<size_t>(180, art.size() - off); uint8_t p[200]; p[0] = 20; wr16(p + 1, (uint16_t)s); memcpy(p + 3, &art[off], n); imgRx.onData(p, (uint8_t)(n + 3), 1); } uint8_t e[1] = {20}; imgRx.onEnd(e, 1);
    CHECK(imgRx.state() == XFER_DONE && artReady && artW == 40 && artCrc == b.crc && photoUsed(4), "album art lands in RAM and leaves the photos alone"); }
  printf("photo store tests: %d passed, %d failed\n", passed, failed); return failed ? 1 : 0;
}
