// MANI: the animated custom-screen file. Pure C++ (no Arduino / flash headers) so it is unit-tested on a desktop.
// The phone renders the user's HTML/CSS/JS frame by frame, builds ONE palette (<= 256 colours) for the whole animation, keeps only the rectangle that
// changed in every frame and run-length encodes it (src/core/animscreen.ts). The ESP32 stores the file in flash and plays it: it reads one frame at a
// time and draws it straight to the TFT, 8 rows at a time, so no full bitmap is ever held in RAM.
//
//   file   = header (16) | palette (colors x u16 RGB565, little-endian) | frames (bodyLen bytes, nothing after them)
//   header = 'M' 'A' | version u8 = 1 | flags u8 (bit0 = loop forever, other bits 0) | width u16 | height u16 | colors u16 (1..256) | frames u16 (1..240) | bodyLen u32
//   frame  = x u16 | y u16 | w u16 | h u16 | delayMs u16 | dataLen u32 | RLE data (dataLen bytes)           (14-byte frame header)
//   RLE    = the MIMG tokens (mimg.h) over palette indices of the w x h rectangle, row-major, runs may cross rows:
//              0x00 lo hi idx  long run | 0x01..0x7F idx  short run | 0x80..0xFF i0 i1 ...  literal ((token & 0x7F) + 1 indices)
//   Frame 0 covers the whole screen. Every other frame is a rectangle inside the screen: only those pixels change, the rest of the picture stays.
//   delayMs is how long the frame stays before the next one is drawn (the player never goes below MANI_MIN_DELAY_MS).
//   After the last frame the player starts again with frame 0 when the loop flag is set, otherwise the last frame stays on the display.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include "protocol.h"
#include "mimg.h"

namespace meow {

constexpr uint32_t MANI_MAX_FILE = 786432;      // 768 KB: what the device will accept for one screen
constexpr uint32_t MANI_MIN_FILE = 16 + 2 + 14 + 2;
constexpr uint16_t MANI_MAX_FRAMES = 240;
constexpr size_t   MANI_HDR = 16, MANI_FHDR = 14;
constexpr uint16_t MANI_MIN_DELAY_MS = 20;
constexpr uint16_t MANI_MAX_SIDE = 320;

struct ManiHeader { uint16_t w = 0, h = 0, colors = 0, frames = 0; bool loop = false; uint32_t bodyLen = 0; };
struct ManiFrame { uint16_t x = 0, y = 0, w = 0, h = 0, delay = 0; uint32_t dataLen = 0; };

inline bool maniParseHeader(const uint8_t* p, uint32_t fileLen, uint16_t expectW, uint16_t expectH, ManiHeader& o) {
  if (fileLen < MANI_MIN_FILE || fileLen > MANI_MAX_FILE) return false;
  if (p[0] != 'M' || p[1] != 'A' || p[2] != 1 || (p[3] & 0xFE) != 0) return false;
  ManiHeader h; h.loop = p[3] & 1; h.w = rd16(p + 4); h.h = rd16(p + 6); h.colors = rd16(p + 8); h.frames = rd16(p + 10); h.bodyLen = rd32(p + 12);
  if (h.w == 0 || h.h == 0 || h.w > MANI_MAX_SIDE || h.h > MANI_MAX_SIDE) return false;
  if (expectW && h.w != expectW) return false;
  if (expectH && h.h != expectH) return false;
  if (h.colors < 1 || h.colors > 256 || h.frames < 1 || h.frames > MANI_MAX_FRAMES) return false;
  if ((uint64_t)MANI_HDR + 2ull * h.colors + h.bodyLen != fileLen) return false;
  if (h.bodyLen < MANI_FHDR + 2) return false;
  o = h; return true;
}

// Streaming player. `read` supplies the file sequentially from byte 0 (a LittleFS file, or a memory block in the tests). To play again, call begin() again.
class ManiPlayer {
 public:
  typedef size_t (*ReadFn)(void* ctx, uint8_t* buf, size_t n);
  bool begin(ReadFn rd, void* ctx, uint32_t fileLen, uint16_t expectW = 0, uint16_t expectH = 0) {
    rd_ = rd; ctx_ = ctx; bad_ = true; fi_ = 0; frameLeft_ = 0; bufLen_ = bufPos_ = 0; runLeft_ = litLeft_ = 0; rowsLeft_ = 0; pixLeft_ = 0;
    uint8_t hb[MANI_HDR];
    if (rd_(ctx_, hb, MANI_HDR) != MANI_HDR || !maniParseHeader(hb, fileLen, expectW, expectH, hdr_)) return false;
    for (int i = 0; i < hdr_.colors; i++) { uint8_t b[2]; if (rd_(ctx_, b, 2) != 2) return false; pal_[i] = rd16(b); }
    bodyLeft_ = hdr_.bodyLen; bad_ = false; return true;
  }
  const ManiHeader& header() const { return hdr_; }
  bool failed() const { return bad_; }
  uint16_t framesStarted() const { return fi_; }
  bool moreFrames() const { return !bad_ && fi_ < hdr_.frames; }
  bool frameDone() const { return rowsLeft_ == 0; }
  // Reads the next frame header. The previous frame must have been decoded completely (rows() until it returns 0).
  bool nextFrame(ManiFrame& f) {
    if (bad_ || fi_ >= hdr_.frames || rowsLeft_ != 0 || bufPos_ != bufLen_) { bad_ = true; return false; }
    uint8_t b[MANI_FHDR];
    if (bodyLeft_ < MANI_FHDR || rd_(ctx_, b, MANI_FHDR) != MANI_FHDR) { bad_ = true; return false; }
    ManiFrame g; g.x = rd16(b); g.y = rd16(b + 2); g.w = rd16(b + 4); g.h = rd16(b + 6); g.delay = rd16(b + 8); g.dataLen = rd32(b + 10);
    bodyLeft_ -= MANI_FHDR;
    if (g.w == 0 || g.h == 0 || (uint32_t)g.x + g.w > hdr_.w || (uint32_t)g.y + g.h > hdr_.h) { bad_ = true; return false; }
    if (fi_ == 0 && (g.x != 0 || g.y != 0 || g.w != hdr_.w || g.h != hdr_.h)) { bad_ = true; return false; }
    if (g.dataLen < 2 || g.dataLen > bodyLeft_) { bad_ = true; return false; }
    bodyLeft_ -= g.dataLen; frameLeft_ = g.dataLen; bufLen_ = bufPos_ = 0; runLeft_ = litLeft_ = 0;
    curW_ = g.w; rowsLeft_ = g.h; fi_++;
    if (fi_ == hdr_.frames && bodyLeft_ != 0) { bad_ = true; return false; }             // trailing bytes after the last frame
    f = g; return true;
  }
  // Decodes up to `rows` rows of the current frame into out (row-major, w x rows RGB565). Returns the rows produced; 0 = frame finished or failure.
  int rows(uint16_t* out, int rows) {
    if (bad_ || rowsLeft_ == 0) return 0;
    int n = 0;
    while (n < rows && rowsLeft_ > 0) {
      for (uint16_t x = 0; x < curW_; x++) { uint8_t idx; if (!next(idx)) { bad_ = true; return n; } out[(size_t)n * curW_ + x] = pal_[idx]; }
      rowsLeft_--; n++;
    }
    if (rowsLeft_ == 0 && (runLeft_ || litLeft_ || frameLeft_ || bufPos_ != bufLen_)) bad_ = true;   // data left over inside the frame = corrupt or forged
    return n;
  }
 private:
  bool byte(uint8_t& b) {
    if (frameLeft_ == 0 && bufPos_ == bufLen_) return false;
    if (bufPos_ == bufLen_) {
      size_t want = frameLeft_ < sizeof buf_ ? frameLeft_ : sizeof buf_;
      size_t got = rd_(ctx_, buf_, want); if (got == 0) return false;
      bufLen_ = got; bufPos_ = 0; frameLeft_ -= (uint32_t)got;
    }
    b = buf_[bufPos_++]; return true;
  }
  bool next(uint8_t& idx) {
    for (;;) {
      if (runLeft_) { runLeft_--; idx = runIdx_; return true; }
      if (litLeft_) { litLeft_--; if (!byte(idx) || idx >= hdr_.colors) return false; return true; }
      uint8_t c; if (!byte(c)) return false;
      if (c == 0) { uint8_t lo, hi, i; if (!byte(lo) || !byte(hi) || !byte(i)) return false; uint16_t cnt = (uint16_t)(lo | (hi << 8)); if (cnt == 0 || i >= hdr_.colors) return false; runLeft_ = cnt; runIdx_ = i; }
      else if (c < 0x80) { uint8_t i; if (!byte(i) || i >= hdr_.colors) return false; runLeft_ = c; runIdx_ = i; }
      else litLeft_ = (uint16_t)((c & 0x7F) + 1);
    }
  }
  ReadFn rd_ = nullptr; void* ctx_ = nullptr; ManiHeader hdr_; uint16_t pal_[256]; bool bad_ = true;
  uint8_t buf_[64]; size_t bufLen_ = 0, bufPos_ = 0; uint32_t frameLeft_ = 0, bodyLeft_ = 0; uint16_t runLeft_ = 0, litLeft_ = 0, curW_ = 0, fi_ = 0; uint8_t runIdx_ = 0;
  uint16_t rowsLeft_ = 0; uint32_t pixLeft_ = 0;
};

// Full structural check of a stored / received file: header, every frame, every token, exact pixel counts. Needs RAM for one row only.
inline bool maniValidate(ManiPlayer::ReadFn rd, void* ctx, uint32_t fileLen, uint16_t expectW, uint16_t expectH, uint16_t* framesOut = nullptr) {
  ManiPlayer p; if (!p.begin(rd, ctx, fileLen, expectW, expectH)) return false;
  uint16_t row[MANI_MAX_SIDE]; ManiFrame f;
  while (p.moreFrames()) {
    if (!p.nextFrame(f)) return false;
    while (!p.frameDone()) { if (p.rows(row, 1) != 1 || p.failed()) return false; }
    if (p.failed()) return false;
  }
  if (framesOut) *framesOut = p.header().frames;
  return !p.failed();
}

// What the phone compares to know whether the display holds exactly its screens: CRC-32 over (slot u8, file CRC-32 u32 little-endian) of every occupied
// slot in slot order; 0 when no slot is occupied. crcs[i] == 0 means slot i is empty.
inline uint32_t slotDigest(const uint32_t* crcs, int n) {
  uint32_t c = crc32Begin(); bool any = false;
  for (int i = 0; i < n; i++) { if (!crcs[i]) continue; uint8_t b[5] = {(uint8_t)i, 0, 0, 0, 0}; wr32(b + 1, crcs[i]); c = crc32Update(c, b, 5); any = true; }
  return any ? crc32End(c) : 0;
}

}  // namespace meow
