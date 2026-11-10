// Desktop test of firmware/MeowIsland/jpeg_dec.h against Pillow/libjpeg. Build + run (see run.sh):
//   python3 ../../tools/make_jpeg_corpus.py out/jc && g++ -std=c++17 -fsanitize=address,undefined -I ../MeowIsland jpeg_test.cpp -o out/jpeg_test && out/jpeg_test out/jc
#include "../MeowIsland/jpeg_dec.h"
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <chrono>
using namespace meow;
typedef std::vector<uint8_t> Bytes;
static int passed = 0, failed = 0;
#define CHECK(c, msg) do { if (c) passed++; else { failed++; printf("FAIL: %s  (line %d)\n", msg, __LINE__); } } while (0)
static Bytes readFile(const std::string& p) { std::ifstream f(p, std::ios::binary); return Bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>()); }
struct Mem { const Bytes* b; size_t at = 0; };
static size_t memRead(void* c, uint8_t* buf, size_t n) { Mem* m = (Mem*)c; size_t k = std::min(n, m->b->size() - m->at); if (k) memcpy(buf, m->b->data() + m->at, k); m->at += k; return k; }
static size_t slowRead(void* c, uint8_t* buf, size_t n) { Mem* m = (Mem*)c; size_t k = std::min<size_t>(std::min<size_t>(n, 3), m->b->size() - m->at); if (k) memcpy(buf, m->b->data() + m->at, k); m->at += k; return k; }   // tiny reads: file system chunking must not matter
static bool decode(const Bytes& j, uint16_t& w, uint16_t& h, std::vector<uint16_t>& px, JpegDecoder::ReadFn rd = memRead) {
  Mem m{&j}; JpegDecoder d; if (!d.begin(rd, &m, (uint32_t)j.size())) return false; w = d.width(); h = d.height(); px.assign((size_t)w * h, 0);
  std::vector<uint16_t> band((size_t)w * d.bandRows()); int y = 0, n;
  while ((n = d.nextBand(band.data())) > 0) { for (int r = 0; r < n; r++) memcpy(&px[(size_t)(y + r) * w], &band[(size_t)r * w], w * 2); y += n; }
  return d.finished() && y == h;
}
static double mae565(const std::vector<uint16_t>& a, const Bytes& rgb) {            // mean absolute error per channel in 8-bit units against Pillow's pixels
  double s = 0; for (size_t i = 0; i < a.size(); i++) { int r = ((a[i] >> 11) << 3) | (a[i] >> 13), g = (((a[i] >> 5) & 63) << 2) | (((a[i] >> 5) & 63) >> 4), b = ((a[i] & 31) << 3) | ((a[i] & 31) >> 2);
    s += abs(r - rgb[i * 3]) + abs(g - rgb[i * 3 + 1]) + abs(b - rgb[i * 3 + 2]); } return s / (a.size() * 3.0); }

int main(int argc, char** argv) {
  std::string dir = argc > 1 ? argv[1] : "out/jc"; std::ifstream mf(dir + "/manifest.txt"); std::string name; int W, H, size, ss; int files = 0; double worst = 0; Bytes first;
  while (mf >> name >> W >> H >> size >> ss) {
    Bytes j = readFile(dir + "/" + name + ".jpg"), ref = readFile(dir + "/" + name + ".rgb"); uint16_t w = 0, h = 0; std::vector<uint16_t> px;
    bool ok = decode(j, w, h, px); CHECK(ok && w == W && h == H, (name + ": decodes with the right size").c_str()); if (!ok) continue;
    double e = mae565(px, ref); if (e > worst) worst = e;
    double lim = ss == 0 ? 3.0 : 6.0; if (name.find("odd_") == 0) lim = 14.0;                       // tiny 4:2:0 pictures: chroma upsampling differs from libjpeg's at every edge
    if (name.find("gray_odd") == 0) lim = 3.0;
    CHECK(e < lim, (name + ": mean error vs libjpeg " + std::to_string(e) + " (limit " + std::to_string(lim) + ")").c_str());
    std::vector<uint16_t> px2; uint16_t w2, h2; CHECK(decode(j, w2, h2, px2, slowRead) && px2 == px, (name + ": 3-byte reads give the identical picture").c_str());
    if (first.empty() && name == "p_q50_s2") first = j; files++;
  }
  CHECK(files >= 38, "corpus read");
  printf("decoded %d files, worst mean error %.2f levels\n", files, worst);

  // ---- things that must be refused, never crash
  { Bytes j = readFile(dir + "/progressive.jpg"); uint16_t w, h; std::vector<uint16_t> px; CHECK(!decode(j, w, h, px), "progressive JPEG is refused"); }
  CHECK(!first.empty(), "reference file");
  { uint16_t w, h; std::vector<uint16_t> px; Bytes t = first; t.resize(t.size() / 2); CHECK(!decode(t, w, h, px), "half a file is refused");
    t = first; t.pop_back(); t.pop_back(); CHECK(!decode(t, w, h, px), "a file without the end-of-image marker is refused");
    Bytes z(300, 0); CHECK(!decode(z, w, h, px), "zeros are refused"); Bytes e; CHECK(!decode(e, w, h, px), "empty input is refused");
    t = first; t[0] = 0; CHECK(!decode(t, w, h, px), "no SOI marker is refused"); }
  { int survived = 0, rejected = 0; srand(5); for (int t = 0; t < 1500; t++) { Bytes c = first; int flips = 1 + t % 5; for (int k = 0; k < flips; k++) c[rand() % c.size()] ^= 1 << (rand() % 8);
      uint16_t w, h; std::vector<uint16_t> px; if (decode(c, w, h, px)) survived++; else rejected++; }
    printf("fuzz: %d still decode (damage inside the entropy data), %d rejected\n", survived, rejected); CHECK(survived + rejected == 1500, "fuzz finished without a crash"); CHECK(rejected > 100, "header damage is detected"); }
  { Bytes c = first; size_t at = 0; for (size_t i = 0; i + 3 < c.size(); i++) if (c[i] == 0xFF && c[i + 1] == 0xC0) { at = i; break; }
    CHECK(at > 0, "found SOF"); uint16_t w, h; std::vector<uint16_t> px;
    Bytes t = c; t[at + 4] = 12; CHECK(!decode(t, w, h, px), "12-bit precision is refused"); t = c; t[at + 9] = 4; CHECK(!decode(t, w, h, px), "4 components are refused");
    t = c; t[at + 5] = 0xFF; t[at + 6] = 0xFF; CHECK(!decode(t, w, h, px), "65535-pixel-high picture is refused (size cap)"); }
  { // speed on this machine (the ESP32 is far slower; see docs/PHOTO_SLIDESHOW.md for the estimate)
    uint16_t w, h; std::vector<uint16_t> px; auto t0 = std::chrono::steady_clock::now(); for (int i = 0; i < 20; i++) decode(first, w, h, px);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 20; printf("desktop decode time for 240x320 (%zu bytes): %.2f ms\n", first.size(), ms); }
  printf("jpeg tests: %d passed, %d failed\n", passed, failed); return failed ? 1 : 0;
}
