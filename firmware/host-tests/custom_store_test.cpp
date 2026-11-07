// Runs the real firmware/MeowIsland/custom_store.h (slots, sidecars, boot recovery, playback) against the in-memory LittleFS stand-in and a fake TFT.
// run.sh copies the headers next to cs_stubs/gfx_common.h first (so the stand-in is found instead of the real gfx_common.h).
#include <cassert>
#include <string>
#include "Arduino.h"
#include "LittleFS.h"
#include "custom_store.h"
#include <fstream>
#include <sstream>
#include <map>
uint32_t host_millis = 0; time_t host_epoch = 0; bool host_btn_low = false, host_restarted = false;
using namespace meow;
typedef std::vector<uint8_t> Bytes;
static int passed = 0, failed = 0;
#define CHECK(c, msg) do { if (c) passed++; else { failed++; printf("FAIL: %s  (line %d)\n", msg, __LINE__); } } while (0)
static Bytes unhex(const std::string& h) { Bytes v; for (size_t i = 0; i + 1 < h.size(); i += 2) v.push_back((uint8_t)strtol(h.substr(i, 2).c_str(), nullptr, 16)); return v; }
static std::vector<std::string> split(const std::string& s, char d) { std::vector<std::string> o; std::stringstream ss(s); std::string t; while (std::getline(ss, t, d)) o.push_back(t); return o; }
static uint32_t screenCrc() { Bytes b; for (auto v : tft.px) { b.push_back(v & 255); b.push_back(v >> 8); } return crc32Of(b.data(), b.size()); }
static std::string hex(uint32_t v) { char b[16]; snprintf(b, sizeof b, "%x", v); return b; }
struct Vec { std::string name; Bytes file; std::vector<std::string> crcs; std::vector<int> delays; bool loop; };
static void upload(const Bytes& f, uint8_t id, uint8_t slot, uint8_t kind = KIND_SCREEN) {
  ImgBegin b; b.xfer = id; b.kind = kind; b.total = (uint32_t)f.size(); b.crc = crc32Of(f.data(), f.size()); b.chunk = 190; b.w = 240; b.h = 320; b.slot = slot;
  imgRx.onBegin(b, 1); if (imgRx.state() != XFER_RECEIVING) return; uint16_t seq = 0;
  for (size_t off = 0; off < f.size(); off += 190) { size_t n = std::min<size_t>(190, f.size() - off); uint8_t p[200]; p[0] = id; wr16(p + 1, seq++); memcpy(p + 3, f.data() + off, n); imgRx.onData(p, (uint8_t)(n + 3), 1); }
  uint8_t e[1] = {id}; imgRx.onEnd(e, 1);
}
int main(int, char** argv) {
  std::map<std::string, Vec> V; { std::ifstream in(argv[1]); std::string l; while (std::getline(in, l)) { auto f = split(l, '|'); if (f.size() < 8) continue; Vec v; v.name = f[0]; v.file = unhex(f[1]); v.crcs = split(f[5], ','); for (auto& d : split(f[6], ',')) v.delays.push_back(atoi(d.c_str())); v.loop = f[4] == "1"; V[v.name] = v; } }
  CHECK(V.size() >= 6, "vectors loaded");
  fsData().total = 1500 * 1024;
  customBoot(); CHECK(fsReady && slotFirst() < 0 && slotMask() == 0 && slotsDigest() == 0 && customCrc == 0, "empty display boots with no screens");
  CustomView view; view.slot = 0; messages = 0; customEnter(view); CHECK(messages == 2 && firstMsg == "No custom design yet", "empty custom screen explains itself");

  // ---- a looping animation in slot 3
  const Vec& flat = V["flat"]; upload(flat.file, 1, 3); uint32_t flatCrc = crc32Of(flat.file.data(), flat.file.size());
  CHECK(imgRx.state() == XFER_DONE && slots[3].crc == flatCrc && slots[3].mani && slots[3].frames == 9 && slotMask() == 0x08, "slot 3 holds the animation");
  CHECK(fsData().files.count("/s3.bin") && fsData().files.count("/s3.m") && !fsData().files.count("/custom.tmp") && !fsData().files.count("/custom.mimg"), "files: /s3.bin + sidecar, no temp file, slot 0 untouched");
  { uint32_t c[SLOTS] = {0}; c[3] = flatCrc; CHECK(slotsDigest() == slotDigest(c, SLOTS), "digest covers the stored slot"); }
  view.slot = 3; customEnter(view); CHECK(view.playing && screenCrc() == std::stoul(flat.crcs[0], nullptr, 16) - 0 + 0 * 0 || hex(screenCrc()) == flat.crcs[0], "frame 0 is drawn on entering");
  CHECK(hex(screenCrc()) == flat.crcs[0], "frame 0 picture matches what the phone predicted");
  uint32_t t = host_millis = 5000; view.nextAt = t + flat.delays[0];
  for (int round = 0; round < 2; round++) for (size_t i = 1; i <= flat.crcs.size(); i++) {
    size_t shown = i % flat.crcs.size(); int calls = tft.bitmapCalls;
    host_millis = view.nextAt - 1; customTick(view); CHECK(tft.bitmapCalls == calls, "nothing is drawn before the frame delay is over");
    host_millis = view.nextAt; customTick(view); CHECK(hex(screenCrc()) == flat.crcs[shown], ("frame " + std::to_string(shown) + " of round " + std::to_string(round)).c_str());
  }
  CHECK(view.playing, "a looping animation keeps playing");

  // ---- play once: the last picture stays
  const Vec& once = V["once"]; upload(once.file, 2, 1); view.slot = 1; customEnter(view); CHECK(hex(screenCrc()) == once.crcs[0] && view.playing, "play-once starts");
  for (size_t i = 1; i < once.crcs.size(); i++) { host_millis = view.nextAt; customTick(view); CHECK(hex(screenCrc()) == once.crcs[i], "play-once frame"); }
  CHECK(!view.playing && !view.f, "after the last frame the player stops and releases the file"); int calls = tft.bitmapCalls; host_millis += 100000; customTick(view); CHECK(tft.bitmapCalls == calls && hex(screenCrc()) == once.crcs.back(), "the last frame stays on the display");

  // ---- still (1 frame MANI) and photo
  const Vec& still = V["still"]; upload(still.file, 3, 5); view.slot = 5; customEnter(view); CHECK(hex(screenCrc()) == still.crcs[0] && !view.playing, "a one-frame screen is drawn once, no playback");
  const Vec& photo = V["photo"]; upload(photo.file, 4, 6); view.slot = 6; customEnter(view); CHECK(hex(screenCrc()) == photo.crcs[0], "photo frame 0"); host_millis = view.nextAt; customTick(view); CHECK(hex(screenCrc()) == photo.crcs[1], "photo delta frame");
  CHECK(slotMask() == ((1 << 1) | (1 << 3) | (1 << 5) | (1 << 6)) && slotFirst() == 1 && slotNext(1) == 3 && slotNext(3) == 5 && slotNext(5) == 6 && slotNext(6) == -1, "slot order for the BOOT button");

  // ---- reboot: everything is back, from the sidecars (no full read)
  { uint32_t before[SLOTS]; for (int i = 0; i < SLOTS; i++) before[i] = slots[i].crc; uint32_t d = slotsDigest(); customBoot(); bool same = true; for (int i = 0; i < SLOTS; i++) same &= before[i] == slots[i].crc; CHECK(same && slotsDigest() == d && slots[3].mani && slots[3].frames == 9, "all slots restored after a reboot"); }
  fsData().files["/s3.m"][0] ^= 0xFF; { uint32_t want = slots[3].crc; fsData().files["/s3.m"][0] ^= 0xFF; fsData().files["/s3.m"].clear(); customBoot(); CHECK(slots[3].crc == want && fsData().files["/s3.m"].size() == 10, "a damaged sidecar is rebuilt from the file"); }
  fsData().files["/s5.bin"][40] ^= 0xFF; fsData().files["/s5.bin"][41] ^= 0xFF; fsData().files.erase("/s5.m"); customBoot(); CHECK(!slotUsed(5) && !fsData().files.count("/s5.bin"), "a corrupted file without a sidecar is detected and removed at boot");
  fsData().files["/s6.bin"].resize(fsData().files["/s6.bin"].size() - 10); customBoot(); CHECK(!slotUsed(6), "a truncated file (sidecar size mismatch) is detected and removed"); CHECK(slotUsed(3) && slotUsed(1), "healthy slots survive");

  // ---- replace, failure and space rules
  { const Bytes old = fsData().files["/s3.bin"]; Bytes bad = V["gradient"].file; bad[bad.size() - 5] ^= 0xFF; ImgBegin b; b.xfer = 9; b.kind = KIND_SCREEN; b.total = (uint32_t)bad.size(); b.crc = 0x1234; b.chunk = 190; b.w = 240; b.h = 320; b.slot = 3;
    imgRx.onBegin(b, 1); uint16_t seq = 0; for (size_t off = 0; off < bad.size(); off += 190) { size_t n = std::min<size_t>(190, bad.size() - off); uint8_t p[200]; p[0] = 9; wr16(p + 1, seq++); memcpy(p + 3, bad.data() + off, n); imgRx.onData(p, (uint8_t)(n + 3), 1); } uint8_t e[1] = {9}; imgRx.onEnd(e, 1);
    CHECK(imgRx.state() == XFER_FAILED && fsData().files["/s3.bin"] == old && slots[3].crc == flatCrc && !fsData().files.count("/custom.tmp"), "a failed upload leaves the stored screen and removes the temp file"); }
  { fsData().failWrites = true; upload(V["gradient"].file, 10, 3); fsData().failWrites = false; CHECK(imgRx.state() == XFER_FAILED && slots[3].crc == flatCrc, "a flash write failure keeps the old screen"); }
  { size_t was = fsData().total; fsData().total = LittleFS.usedBytes() + 3000; upload(V["gradient"].file, 11, 3); fsData().total = was; CHECK(imgRx.state() == XFER_FAILED && imgRx.status().reason == XR_NO_SPACE && slots[3].crc == flatCrc, "not enough space is reported and nothing is lost"); }
  { uint32_t rev = customRev; upload(V["gradient"].file, 12, 3); CHECK(imgRx.state() == XFER_DONE && slots[3].crc == crc32Of(V["gradient"].file.data(), V["gradient"].file.size()) && customRev != rev, "a good upload replaces the slot and triggers a redraw"); }
  { size_t w = 0; for (auto& kv : fsData().files) w += kv.second.size(); upload(V["gradient"].file, 13, 3); size_t w2 = 0; for (auto& kv : fsData().files) w2 += kv.second.size(); CHECK(imgRx.state() == XFER_DONE && w == w2, "re-sending the same screen changes nothing in flash"); }

  // ---- redraw when the shown slot is replaced; delete; viewing a deleted slot falls back
  view.slot = 3; customEnter(view); uint32_t shown = screenCrc(); upload(flat.file, 14, 3); customTick(view); CHECK(screenCrc() != shown && hex(screenCrc()) == flat.crcs[0], "replacing the screen being shown redraws it");
  imgRx.onCtrlSlot(CTRL_DELETE_SLOT, 3); CHECK(!slotUsed(3) && !fsData().files.count("/s3.bin") && !fsData().files.count("/s3.m") && slotUsed(1), "delete removes the file, the sidecar and the slot");
  customTick(view); CHECK(view.slot == 1 && slotUsed(view.slot), "the view moves to the first stored screen when its slot disappears");

  // ---- the original single-image path: kind 0 -> slot 0, MIMG stills
  { std::ifstream in("../../tests/mimg_vectors.txt"); std::string l; std::getline(in, l); auto f = split(l, '|'); Bytes img = unhex(f[1]); upload(img, 20, 0, KIND_CUSTOM);
    CHECK(imgRx.state() == XFER_DONE && fsData().files.count("/custom.mimg") && customCrc == crc32Of(img.data(), img.size()) && !slots[0].mani && imgRx.status().customCrc == customCrc, "the single-image upload still lands in slot 0 as /custom.mimg");
    view.slot = 0; customEnter(view); CHECK(!view.playing && view.drawnOk && hex(screenCrc()) == f[2], "a MIMG still is drawn exactly as before");
    customBoot(); CHECK(customCrc == crc32Of(img.data(), img.size()) && slots[0].crc == customCrc, "slot 0 comes back after a reboot (no sidecar needed: found by inspection)");
    imgRx.onCtrl(CTRL_DELETE_CUSTOM); CHECK(customCrc == 0 && !fsData().files.count("/custom.mimg") && slotUsed(1), "the original delete removes slot 0 only"); }
  // ---- an animation can also be uploaded with the original kind (slot 0)
  upload(flat.file, 21, 0, KIND_CUSTOM); CHECK(imgRx.state() == XFER_DONE && slots[0].mani && customCrc == crc32Of(flat.file.data(), flat.file.size()), "a small MANI file sent the old way (kind 0) is stored in slot 0 and plays");
  view.slot = 0; customEnter(view); CHECK(hex(screenCrc()) == flat.crcs[0] && view.playing, "...and plays");
  printf("custom store tests: %d passed, %d failed\n", passed, failed); return failed ? 1 : 0;
}
