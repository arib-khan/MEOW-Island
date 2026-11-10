// Desktop tests of the transfer protocol receiver (firmware/MeowIsland/image_rx.h) and the photo / album-art rules, including a lossy channel.
// Build + run:  g++ -std=c++17 -fsanitize=address,undefined -I stubs -I ../MeowIsland -I . image_test.cpp -o out/image_test && out/image_test out/jc
// (out/jc: the JPEG corpus from tools/make_jpeg_corpus.py; a real JPEG is used so the commit step runs the real decoder's validation)
#include "../MeowIsland/protocol.h"
#include "../MeowIsland/crc32.h"
#include "../MeowIsland/jpeg_dec.h"
#include "../MeowIsland/image_rx.h"
#include <vector>
#include <string>
#include <fstream>
#include <map>
#include <random>
#include <algorithm>
using namespace meow;
typedef std::vector<uint8_t> Bytes;
static int passed = 0, failed = 0;
#define CHECK(c, msg) do { if (c) passed++; else { failed++; printf("FAIL: %s  (line %d)\n", msg, __LINE__); } } while (0)
static Bytes readFile(const std::string& p) { std::ifstream f(p, std::ios::binary); return Bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>()); }
struct Mem { const Bytes* b; size_t at = 0; };
static size_t memRead(void* c, uint8_t* buf, size_t n) { Mem* m = (Mem*)c; size_t k = std::min(n, m->b->size() - m->at); if (k) memcpy(buf, m->b->data() + m->at, k); m->at += k; return k; }

struct MemSink : RxSink {
  std::map<int, Bytes> photo; Bytes tmp; std::map<int, uint32_t> crc; bool failOpen = false, failWrite = false; int opens = 0, writes = 0, commits = 0, aborts = 0, deletes = 0;
  Bytes art; uint32_t artCrc_ = 0; uint32_t artWant = 0; bool artOpen = false; int artCommits = 0, artClears = 0;
  uint32_t storedPhotoCrc(uint8_t s) override { auto it = crc.find(s); return it == crc.end() ? 0 : it->second; }
  bool openPhoto(uint8_t, uint32_t, uint8_t& r) override { opens++; if (failOpen) { r = XR_NO_SPACE; return false; } tmp.clear(); return true; }
  bool writePhoto(const uint8_t* d, size_t n) override { writes++; if (failWrite) return false; tmp.insert(tmp.end(), d, d + n); return true; }
  bool commitPhoto(uint8_t s, uint32_t c, uint8_t& r) override {
    static uint16_t band[240 * 16]; Mem m{&tmp};
    if (!jpegValidate(memRead, &m, (uint32_t)tmp.size(), 240, 320, band, sizeof band / 2)) { r = XR_BAD_IMAGE; return false; }
    photo[s] = tmp; crc[s] = c; commits++; return true; }
  void abortPhoto() override { aborts++; tmp.clear(); }
  void deletePhoto(uint8_t s) override { photo.erase(s); crc.erase(s); deletes++; }
  uint32_t storedArtCrc() override { return artCrc_; }
  bool openArt(uint32_t total, uint16_t, uint16_t, uint8_t&) override { art.clear(); artCrc_ = 0; artWant = total; artOpen = true; return true; }
  bool writeArt(const uint8_t* d, size_t n) override { art.insert(art.end(), d, d + n); return true; }
  bool commitArt(uint32_t c, uint8_t& r) override { if (art.size() != artWant) { r = XR_SEQUENCE; return false; } artCrc_ = c; artCommits++; artOpen = false; return true; }
  void abortArt() override { artCrc_ = 0; artOpen = false; }
  void clearArt() override { artCrc_ = 0; artClears++; }
};
static ImgBegin beginOf(uint8_t id, uint8_t kind, const Bytes& f, uint16_t w, uint16_t h, uint8_t slot, uint32_t crc, bool useCrc) {
  ImgBegin b; b.xfer = id; b.kind = kind; b.total = (uint32_t)f.size(); b.crc = useCrc ? crc : crc32Of(f.data(), f.size()); b.chunk = 180; b.w = w; b.h = h; b.slot = slot; return b; }
static void sendAll(ImageReceiver& rx, const Bytes& f, uint8_t id, uint32_t now = 1000) {
  uint16_t seq = 0; for (size_t off = 0; off < f.size(); off += 180) { size_t n = std::min<size_t>(180, f.size() - off); uint8_t p[200]; p[0] = id; wr16(p + 1, seq++); memcpy(p + 3, f.data() + off, n); rx.onData(p, (uint8_t)(n + 3), now); }
  uint8_t e[1] = {id}; rx.onEnd(e, 1);
}
static void sendPhoto(ImageReceiver& rx, const Bytes& f, uint8_t id, uint8_t slot, uint32_t crc = 0, bool useCrc = false) {
  rx.onBegin(beginOf(id, KIND_PHOTO, f, 240, 320, slot, crc, useCrc), 1000); if (rx.state() == XFER_RECEIVING) sendAll(rx, f, id); }

int main(int argc, char** argv) {
  std::string dir = argc > 1 ? argv[1] : "out/jc"; Bytes jpg = readFile(dir + "/p_q50_s2.jpg"), jpg2 = readFile(dir + "/p_q75_s0.jpg"); CHECK(jpg.size() > 500 && jpg2.size() > 500, "corpus photos found");
  // ---- BEGIN parsing
  { uint8_t p[17] = {7, KIND_PHOTO}; wr32(p + 2, 5000); wr32(p + 6, 0xCAFEBABE); wr16(p + 10, 190); wr16(p + 12, 240); wr16(p + 14, 320); p[16] = 3; ImgBegin b;
    CHECK(parseImgBegin(p, 17, b) && b.kind == KIND_PHOTO && b.slot == 3 && b.total == 5000 && b.crc == 0xCAFEBABE, "photo BEGIN with a slot parses");
    p[16] = 24; CHECK(!parseImgBegin(p, 17, b), "slot 24 rejected (24 slots: 0..23)"); p[16] = 23; CHECK(parseImgBegin(p, 17, b) && b.slot == 23, "slot 23 accepted"); p[16] = 3; CHECK(!parseImgBegin(p, 16, b), "photo BEGIN without a slot rejected");
    p[1] = KIND_ART; CHECK(parseImgBegin(p, 16, b) && b.slot == 0 && !parseImgBegin(p, 17, b), "album-art BEGIN is 16 bytes");
    p[1] = 0; CHECK(!parseImgBegin(p, 16, b) && !parseImgBegin(p, 17, b), "kind 0 (the removed custom screen) is rejected"); p[1] = 3; CHECK(!parseImgBegin(p, 17, b), "unknown kind rejected");
    p[1] = KIND_PHOTO; wr16(p + 10, 0); CHECK(!parseImgBegin(p, 17, b), "zero chunk rejected"); wr16(p + 10, 191); CHECK(!parseImgBegin(p, 17, b), "oversized chunk rejected"); }
  // ---- a photo lands in the slot it names; nothing else changes
  { MemSink sink; ImageReceiver rx(&sink, 240, 320); sendPhoto(rx, jpg, 5, 2);
    CHECK(rx.state() == XFER_DONE && sink.photo[2] == jpg && sink.crc[2] == crc32Of(jpg.data(), jpg.size()) && sink.photo.size() == 1, "photo stored in its slot");
    XferStatus st = rx.status(); CHECK(st.state == XFER_DONE && st.id == 5 && st.photoCrc == sink.crc[2], "status reports the CRC of the slot just written");
    int w = sink.writes; sendPhoto(rx, jpg, 6, 2); CHECK(rx.state() == XFER_DONE && sink.writes == w && sink.opens == 1, "re-sending the same photo is acknowledged without a write");
    sendPhoto(rx, jpg, 7, 9); CHECK(rx.state() == XFER_DONE && sink.photo[9] == jpg && sink.photo[2] == jpg, "the same photo in a second slot is a separate copy");
    sendPhoto(rx, jpg2, 8, 2); CHECK(rx.state() == XFER_DONE && sink.photo[2] == jpg2 && sink.photo[9] == jpg, "a different photo replaces just that slot");
    int cm = sink.commits; sendPhoto(rx, jpg, 9, 4, 0x12345678, true); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_CRC && sink.commits == cm && !sink.photo.count(4) && sink.aborts >= 1, "a bad checksum is rejected and the slot stays empty");
    Bytes bad = jpg; bad[bad.size() / 2] ^= 0xFF; bad[20] ^= 0xFF; bad[21] ^= 0xFF; sendPhoto(rx, bad, 10, 5); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_BAD_IMAGE && !sink.photo.count(5), "a file that arrives intact but is not a decodable photo is refused by validation");
    Bytes junk(500, 7); sendPhoto(rx, junk, 11, 5); CHECK(rx.status().reason == XR_BAD_IMAGE, "bytes that are not a JPEG are refused");
    sink.failOpen = true; sendPhoto(rx, jpg, 12, 6); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_NO_SPACE, "no space is reported"); sink.failOpen = false;
    sink.failWrite = true; sendPhoto(rx, jpg, 13, 6); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_FLASH && !sink.photo.count(6), "a flash write failure is reported"); sink.failWrite = false;
    Bytes big(PHOTO_MAX_FILE + 1, 1); rx.onBegin(beginOf(14, KIND_PHOTO, big, 240, 320, 1, 0, false), 1); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_TOO_LARGE, "an oversized photo is refused up front");
    Bytes tiny(PHOTO_MIN_FILE - 1, 1); rx.onBegin(beginOf(15, KIND_PHOTO, tiny, 240, 320, 1, 0, false), 1); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_BAD_HEADER, "a file too small to be a photo is refused up front");
    rx.onBegin(beginOf(16, KIND_PHOTO, jpg, 100, 100, 1, 0, false), 1); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_BAD_HEADER, "the wrong picture size is refused up front");
    rx.onDeletePhoto(2); CHECK(!sink.photo.count(2) && sink.photo.count(9), "delete removes just that photo"); rx.onDeletePhoto(24); rx.onCtrl(CTRL_CLEAR_ART); CHECK(sink.photo.count(9), "out-of-range delete is ignored");
    rx.onBegin(beginOf(17, KIND_PHOTO, jpg, 240, 320, 6, 0, false), 1); CHECK(rx.state() == XFER_RECEIVING, "receiving"); rx.onDeletePhoto(6); CHECK(rx.state() == XFER_IDLE, "deleting the target slot abandons the transfer");
    rx.onBegin(beginOf(18, KIND_PHOTO, jpg, 240, 320, 6, 0, false), 1); rx.onCtrl(CTRL_ABORT); CHECK(rx.state() == XFER_IDLE && sink.aborts >= 3, "abort drops a photo transfer");
    rx.onBegin(beginOf(19, KIND_PHOTO, jpg, 240, 320, 6, 0, false), 1); rx.service(1 + RX_TIMEOUT_MS + 5); CHECK(rx.state() == XFER_FAILED, "a silent photo transfer times out"); }
  // ---- chunk discipline
  { MemSink sink; ImageReceiver rx(&sink, 240, 320); rx.onBegin(beginOf(3, KIND_PHOTO, jpg, 240, 320, 1, 0, false), 1);
    uint8_t p[200]; p[0] = 3; wr16(p + 1, 1); memcpy(p + 3, jpg.data(), 100); rx.onData(p, 103, 2); CHECK(rx.status().nextSeq == 0, "a chunk that skips ahead is dropped");
    wr16(p + 1, 0); rx.onData(p, 103, 2); CHECK(rx.status().nextSeq == 1, "the right chunk is taken"); rx.onData(p, 103, 2); CHECK(rx.status().nextSeq == 1, "a duplicate is dropped");
    p[0] = 9; wr16(p + 1, 1); rx.onData(p, 103, 2); CHECK(rx.status().nextSeq == 1, "a chunk of another transfer is dropped");
    rx.onBegin(beginOf(3, KIND_PHOTO, jpg, 240, 320, 1, 0, false), 3); CHECK(rx.status().nextSeq == 1, "a repeated BEGIN keeps the progress"); }
  // ---- a lossy, duplicating, reordering link: the phone resends from the device's nextSeq until it is done
  { MemSink sink; ImageReceiver rx(&sink, 240, 320); std::mt19937 rng(77); int rounds = 0;
    rx.onBegin(beginOf(4, KIND_PHOTO, jpg, 240, 320, 8, 0, false), 1);
    std::vector<Bytes> chunks; for (size_t off = 0; off < jpg.size(); off += 180) { size_t n = std::min<size_t>(180, jpg.size() - off); Bytes c(3 + n); c[0] = 4; wr16(&c[1], (uint16_t)chunks.size()); memcpy(&c[3], &jpg[off], n); chunks.push_back(c); }
    while (rx.status().nextSeq < chunks.size() && rounds++ < 500) { uint16_t from = rx.status().nextSeq; std::vector<uint16_t> win; for (uint16_t s = from; s < chunks.size() && s < from + 8; s++) win.push_back(s);
      std::shuffle(win.begin(), win.end(), rng); for (uint16_t s : win) { if (rng() % 100 < 25) continue; rx.onData(chunks[s].data(), (uint8_t)chunks[s].size(), 10 + rounds); if (rng() % 100 < 20) rx.onData(chunks[s].data(), (uint8_t)chunks[s].size(), 10 + rounds); } }
    uint8_t e[1] = {4}; rx.onEnd(e, 1); CHECK(rx.state() == XFER_DONE && sink.photo[8] == jpg, "the photo arrives intact over a link that drops, duplicates and reorders"); printf("lossy link: %d rounds for %zu chunks\n", rounds, chunks.size()); }
  // ---- album art: raw RGB565 in RAM, untouched by photo transfers
  { MemSink sink; ImageReceiver rx(&sink, 240, 320); Bytes art(80 * 80 * 2); for (size_t i = 0; i < art.size(); i++) art[i] = (uint8_t)(i * 7);
    rx.onBegin(beginOf(1, KIND_ART, art, 80, 80, 0, 0, false), 1); sendAll(rx, art, 1); CHECK(rx.state() == XFER_DONE && sink.art == art && rx.status().artCrc == crc32Of(art.data(), art.size()), "album art received, its CRC is in the status");
    sendPhoto(rx, jpg, 2, 0); CHECK(rx.state() == XFER_DONE && rx.status().artCrc == crc32Of(art.data(), art.size()), "a photo transfer leaves the album art alone");
    Bytes wrong(100, 1); rx.onBegin(beginOf(3, KIND_ART, wrong, 80, 80, 0, 0, false), 1); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_BAD_HEADER, "album art of the wrong size is refused");
    rx.onBegin(beginOf(4, KIND_ART, art, 200, 80, 0, 0, false), 1); CHECK(rx.state() == XFER_FAILED, "album art above 128 pixels is refused");
    rx.onCtrl(CTRL_CLEAR_ART); CHECK(sink.artClears == 1 && rx.status().artCrc == 0, "clear art"); }
  printf("image / protocol-extension tests: %d passed, %d failed\n", passed, failed); return failed ? 1 : 0;
}
