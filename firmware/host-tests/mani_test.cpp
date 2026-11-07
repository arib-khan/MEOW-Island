// Desktop tests for firmware/MeowIsland/mani.h and the multi-slot parts of image_rx.h / protocol.h. No Arduino, no GFX.
// Build: g++ -std=c++17 -fsanitize=address,undefined -I ../MeowIsland mani_test.cpp -o out/mani_test && out/mani_test ../../tests/mani_vectors.txt
#include "../MeowIsland/protocol.h"
#include "../MeowIsland/mani.h"
#include "../MeowIsland/image_rx.h"
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <map>
using namespace meow;
typedef std::vector<uint8_t> Bytes;
static int passed = 0, failed = 0;
#define CHECK(c, msg) do { if (c) passed++; else { failed++; printf("FAIL: %s  (line %d)\n", msg, __LINE__); } } while (0)
static Bytes unhex(const std::string& h) { Bytes v; for (size_t i = 0; i + 1 < h.size(); i += 2) v.push_back((uint8_t)strtol(h.substr(i, 2).c_str(), nullptr, 16)); return v; }
static std::vector<std::string> split(const std::string& s, char d) { std::vector<std::string> o; std::stringstream ss(s); std::string t; while (std::getline(ss, t, d)) o.push_back(t); return o; }
struct Mem { const Bytes* b; size_t at = 0; };
static size_t memRead(void* c, uint8_t* buf, size_t n) { Mem* m = (Mem*)c; size_t k = std::min(n, m->b->size() - m->at); if (k) memcpy(buf, m->b->data() + m->at, k); m->at += k; return k; }
static uint32_t pixCrc(const std::vector<uint16_t>& px) { Bytes b; for (auto v : px) { b.push_back(v & 255); b.push_back(v >> 8); } return crc32Of(b.data(), b.size()); }

// plays a whole file like the sketch does and returns the picture after every frame
static bool play(const Bytes& f, std::vector<std::vector<uint16_t>>& shots, std::vector<ManiFrame>& frames, ManiHeader& hdr, int rowBatch = 8) {
  Mem m{&f}; ManiPlayer p; if (!p.begin(memRead, &m, (uint32_t)f.size(), 240, 320)) return false; hdr = p.header();
  std::vector<uint16_t> screen((size_t)hdr.w * hdr.h, 0), buf((size_t)hdr.w * rowBatch); ManiFrame fr;
  while (p.moreFrames()) {
    if (!p.nextFrame(fr)) return false; frames.push_back(fr); int y = 0;
    while (y < fr.h) { int n = p.rows(buf.data(), std::min(rowBatch, fr.h - y)); if (n <= 0) return false; for (int j = 0; j < n; j++) for (int i = 0; i < fr.w; i++) screen[(size_t)(fr.y + y + j) * hdr.w + fr.x + i] = buf[(size_t)j * fr.w + i]; y += n; }
    if (p.failed()) return false; shots.push_back(screen);
  }
  return !p.failed();
}

struct SlotSink : RxSink {
  Bytes slot[SLOTS], tmp; uint32_t crc[SLOTS] = {0}; int target = -1; bool failOpen = false; int commits = 0, aborts = 0, writes = 0;
  uint32_t storedCustomCrc() override { return crc[0]; }
  bool open(uint8_t, uint32_t, uint16_t, uint16_t, uint8_t& r) override { r = XR_BAD_HEADER; return false; } bool write(uint8_t, const uint8_t*, size_t) override { return false; }
  bool commit(uint8_t, uint32_t, uint8_t& r) override { r = XR_BAD_IMAGE; return false; } void abort(uint8_t) override {} void deleteCustom() override {} void clearArt() override {}
  uint32_t storedSlotCrc(uint8_t s) override { return crc[s]; }
  bool openSlot(uint8_t s, uint32_t, uint8_t& r) override { if (failOpen) { r = XR_NO_SPACE; return false; } target = s; tmp.clear(); return true; }
  bool writeSlot(const uint8_t* d, size_t n) override { writes++; tmp.insert(tmp.end(), d, d + n); return true; }
  bool commitSlot(uint8_t s, uint32_t c, uint8_t& r) override { Mem m{&tmp}; if (!maniValidate(memRead, &m, (uint32_t)tmp.size(), 240, 320)) { r = XR_BAD_IMAGE; return false; } slot[s] = tmp; crc[s] = c; commits++; return true; }
  void abortSlot() override { aborts++; tmp.clear(); } void deleteSlot(uint8_t s) override { slot[s].clear(); crc[s] = 0; }
};
static void send(ImageReceiver& rx, const Bytes& f, uint8_t id, uint8_t slot, uint32_t crcOverride = 0, bool useOverride = false) {
  ImgBegin b; b.xfer = id; b.kind = KIND_SCREEN; b.total = (uint32_t)f.size(); b.crc = useOverride ? crcOverride : crc32Of(f.data(), f.size()); b.chunk = 190; b.w = 240; b.h = 320; b.slot = slot;
  rx.onBegin(b, 1000); if (rx.state() != XFER_RECEIVING) return;
  uint16_t seq = 0; for (size_t off = 0; off < f.size(); off += 190) { size_t n = std::min<size_t>(190, f.size() - off); uint8_t p[200]; p[0] = id; wr16(p + 1, seq++); memcpy(p + 3, f.data() + off, n); rx.onData(p, (uint8_t)(n + 3), 1000); }
  uint8_t e[1] = {id}; rx.onEnd(e, 1);
}

int main(int argc, char** argv) {
  std::ifstream in(argc > 1 ? argv[1] : "../../tests/mani_vectors.txt"); std::string line; int vectors = 0; Bytes first;
  while (std::getline(in, line)) {
    if (line.empty()) continue; auto f = split(line, '|'); if (f.size() < 8) { CHECK(false, "vector line malformed"); continue; }
    Bytes file = unhex(f[1]); if (first.empty()) first = file; auto crcs = split(f[5], ','), delays = split(f[6], ','), rects = split(f[7], ',');
    std::vector<std::vector<uint16_t>> shots; std::vector<ManiFrame> frames; ManiHeader h;
    for (int batch : {8, 1, 5}) {
      shots.clear(); frames.clear(); bool ok = play(file, shots, frames, h, batch);
      CHECK(ok, (f[0] + ": plays without error").c_str()); if (!ok) break;
      CHECK((int)frames.size() == atoi(f[3].c_str()) && (int)shots.size() == (int)crcs.size(), (f[0] + ": frame count").c_str());
      CHECK(h.loop == (f[4] == "1"), (f[0] + ": loop flag").c_str());
      for (size_t i = 0; i < shots.size() && i < crcs.size(); i++) { char want[16]; snprintf(want, sizeof want, "%x", pixCrc(shots[i])); CHECK(crcs[i] == want, (f[0] + ": picture after frame " + std::to_string(i) + " is what the phone predicted (rows " + std::to_string(batch) + ")").c_str()); }
      for (size_t i = 0; i < frames.size(); i++) { char r[64]; snprintf(r, sizeof r, "%d:%d:%d:%d", frames[i].x, frames[i].y, frames[i].w, frames[i].h); CHECK(rects[i] == r && frames[i].delay == atoi(delays[i].c_str()), (f[0] + ": rectangle and delay of frame " + std::to_string(i)).c_str()); }
    }
    { Mem m{&file}; uint16_t n = 0; CHECK(maniValidate(memRead, &m, (uint32_t)file.size(), 240, 320, &n) && n == atoi(f[3].c_str()), (f[0] + ": validator accepts it").c_str()); }
    vectors++;
  }
  CHECK(vectors >= 6, "all vectors read");

  // ---- corruption: every single-byte flip is either harmless-and-valid or rejected; truncations and extensions are rejected; never a crash (sanitizers)
  { int rejected = 0; for (size_t i = 0; i < first.size(); i += 1) { Bytes c = first; c[i] ^= 0x55; Mem m{&c}; if (!maniValidate(memRead, &m, (uint32_t)c.size(), 240, 320)) rejected++; } CHECK(rejected > (int)first.size() / 3, "byte flips are mostly detected");
    Bytes t = first; t.pop_back(); Mem m1{&t}; CHECK(!maniValidate(memRead, &m1, (uint32_t)t.size(), 240, 320), "truncated file rejected");
    Bytes e = first; e.push_back(0); Mem m2{&e}; CHECK(!maniValidate(memRead, &m2, (uint32_t)e.size(), 240, 320), "extended file rejected");
    Mem m3{&first}; CHECK(!maniValidate(memRead, &m3, (uint32_t)first.size(), 320, 240), "wrong screen size rejected");
    Bytes z(100, 0); Mem m4{&z}; CHECK(!maniValidate(memRead, &m4, 100, 240, 320), "zeros rejected"); }
  { Bytes b = first; b[4 + 16 + 0] = 0; /* palette byte: harmless */ Mem m{&b}; CHECK(maniValidate(memRead, &m, (uint32_t)b.size(), 240, 320), "a different palette colour is still a valid file"); }

  // ---- protocol additions
  { uint8_t p[17] = {7, KIND_SCREEN}; wr32(p + 2, 5000); wr32(p + 6, 0xCAFEBABE); wr16(p + 10, 190); wr16(p + 12, 240); wr16(p + 14, 320); p[16] = 3; ImgBegin b;
    CHECK(parseImgBegin(p, 17, b) && b.kind == KIND_SCREEN && b.slot == 3 && b.total == 5000 && b.crc == 0xCAFEBABE, "BEGIN with a slot parses"); p[16] = 8; CHECK(!parseImgBegin(p, 17, b), "slot 8 rejected"); p[16] = 3; CHECK(!parseImgBegin(p, 16, b), "screen BEGIN without a slot rejected");
    p[1] = KIND_CUSTOM; CHECK(!parseImgBegin(p, 17, b) && parseImgBegin(p, 16, b) && b.slot == 0, "the original 16-byte custom BEGIN still parses, a 17-byte one does not"); p[1] = 3; CHECK(!parseImgBegin(p, 17, b), "unknown kind rejected"); }
  { ScreenSel s; uint8_t a[1] = {5}, b[2] = {5, 3}, c[2] = {5, 9}, d[3] = {1, 2, 3}; CHECK(parseScreenSel(a, 1, s) && s.screen == 5 && !s.hasSlot, "old one-byte SCREEN"); CHECK(parseScreenSel(b, 2, s) && s.hasSlot && s.slot == 3, "SCREEN with a slot"); CHECK(!parseScreenSel(c, 2, s) && !parseScreenSel(d, 3, s) && !parseScreenSel(a, 0, s), "bad SCREEN payloads rejected"); }
  { SlotStatus s; s.mask = 0x0105; s.active = 2; s.freeKb = 1234; s.totalKb = 1408; s.digest = 0xA1B2C3D4; uint8_t o[STATUS_SLOTS_LEN]; encodeStatusSlots(s, o);
    CHECK(o[0] == 0x05 && o[1] == 0x01 && o[2] == 2 && rd16(o + 3) == 1234 && rd16(o + 5) == 1408 && rd32(o + 7) == 0xA1B2C3D4 && STATUS_V3_LEN == 30 && STATUS_FULL_LEN == 19, "status extension 2 layout"); }
  { uint32_t c[SLOTS] = {0}; CHECK(slotDigest(c, SLOTS) == 0, "empty digest is 0"); c[3] = 0xdeadbeef; c[0] = 0x01020304; const uint8_t ex[] = {0, 4, 3, 2, 1, 3, 0xef, 0xbe, 0xad, 0xde}; CHECK(slotDigest(c, SLOTS) == crc32Of(ex, sizeof ex), "digest definition matches the phone (tests/animscreen.test.js)"); }

  // ---- receiving into slots
  { SlotSink sink; ImageReceiver rx(&sink, 240, 320); send(rx, first, 5, 2);
    CHECK(rx.state() == XFER_DONE && sink.slot[2] == first && sink.crc[2] == crc32Of(first.data(), first.size()) && sink.slot[0].empty(), "a screen lands in the slot it names, other slots untouched");
    XferStatus st = rx.status(); CHECK(st.state == XFER_DONE && st.id == 5 && st.customCrc == sink.crc[2], "status reports the CRC of the slot just written");
    int w = sink.writes; send(rx, first, 6, 2); CHECK(rx.state() == XFER_DONE && sink.writes == w, "re-sending the same screen is acknowledged without a write");
    send(rx, first, 7, 4); CHECK(rx.state() == XFER_DONE && sink.slot[4] == first && sink.crc[2] != 0, "the same screen in a second slot is a separate copy");
    int cm = sink.commits; send(rx, first, 8, 5, 0x12345678, true); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_CRC && sink.commits == cm && sink.slot[5].empty() && sink.aborts >= 1, "a bad checksum is rejected and the slot stays empty");
    Bytes bad = first; bad[20] ^= 0xFF; bad[bad.size() - 3] ^= 0xFF; send(rx, bad, 9, 6); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_BAD_IMAGE && sink.slot[6].empty(), "a file that arrives intact but is not a valid screen is rejected by the validator");
    sink.failOpen = true; send(rx, first, 10, 7); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_NO_SPACE, "no space is reported"); sink.failOpen = false;
    ImgBegin big; big.xfer = 11; big.kind = KIND_SCREEN; big.total = MANI_MAX_FILE + 1; big.crc = 1; big.chunk = 190; big.w = 240; big.h = 320; big.slot = 1; rx.onBegin(big, 1); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_TOO_LARGE, "oversized screen refused up front");
    big.total = 5000; big.w = 100; rx.onBegin(big, 1); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_BAD_HEADER, "wrong picture size refused up front");
    rx.onCtrlSlot(CTRL_DELETE_SLOT, 2); CHECK(sink.slot[2].empty() && sink.crc[2] == 0 && sink.slot[4] == first, "delete removes just that slot");
    rx.onCtrlSlot(CTRL_DELETE_SLOT, 9); rx.onCtrlSlot(CTRL_CLEAR_ART, 1); CHECK(sink.slot[4] == first, "out-of-range delete and wrong op are ignored");
    // a transfer that is interrupted by a delete of the same slot
    ImgBegin b; b.xfer = 12; b.kind = KIND_SCREEN; b.total = (uint32_t)first.size(); b.crc = crc32Of(first.data(), first.size()); b.chunk = 190; b.w = 240; b.h = 320; b.slot = 6; rx.onBegin(b, 1); CHECK(rx.state() == XFER_RECEIVING, "receiving"); rx.onCtrlSlot(CTRL_DELETE_SLOT, 6); CHECK(rx.state() == XFER_IDLE, "deleting the target slot abandons the transfer");
    rx.onBegin(b, 1); rx.onCtrl(CTRL_ABORT); CHECK(rx.state() == XFER_IDLE && sink.aborts >= 3, "abort drops a screen transfer");
    rx.onBegin(b, 1); rx.service(1 + RX_TIMEOUT_MS + 5); CHECK(rx.state() == XFER_FAILED, "a silent screen transfer times out"); }
  printf("mani tests: %d passed, %d failed\n", passed, failed); return failed ? 1 : 0;
}
