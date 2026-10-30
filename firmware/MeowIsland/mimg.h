// MIMG: the compact image format used for the custom HTML screen. Pure C++ (no Arduino / flash headers) so it is unit-tested on a desktop.
// The phone renders the HTML to a bitmap, reduces it to <= 256 colours and run-length encodes it (src/core/customscreen.ts). The ESP32 never
// holds a full bitmap in RAM: it stores this file in flash and decodes it row by row straight to the TFT.
//
//   file   = header (16) | palette (colors x u16 RGB565, little-endian) | RLE data (dataLen bytes)        (no trailer)
//   header = 'M' 'I' | version u8 = 1 | flags u8 = 0 | width u16 | height u16 | colors u16 (1..256) | reserved u16 = 0 | dataLen u32
//   RLE    = tokens over palette indices in row-major order (runs may cross rows):
//              0x00 lo hi idx        long run  : u16 count (1..65535) of idx
//              0x01..0x7F idx        short run : count = token
//              0x80..0xFF i0 i1 ...  literal   : (token & 0x7F) + 1 indices follow
//            exactly width x height pixels must be produced and every byte of dataLen consumed.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include "protocol.h"

namespace meow {

constexpr uint32_t MIMG_MAX_FILE = 65536;       // what the phone may send and the device will store (see README: flash budget)
constexpr uint16_t MIMG_MAX_SIDE = 320;
constexpr size_t   MIMG_HDR = 16;

// IEEE CRC-32 (the zlib / PNG one), nibble table: small and fast enough for 64 KB.
inline uint32_t crc32Update(uint32_t crc, const uint8_t* d, size_t n) {
  static const uint32_t T[16] = {0x00000000, 0x1DB71064, 0x3B6E20C8, 0x26D930AC, 0x76DC4190, 0x6B6B51F4, 0x4DB26158, 0x5005713C,
                                 0xEDB88320, 0xF00F9344, 0xD6D6A3E8, 0xCB61B38C, 0x9B64C2B0, 0x86D3D2D4, 0xA00AE278, 0xBDBDF21C};
  for (size_t i = 0; i < n; i++) { crc ^= d[i]; crc = (crc >> 4) ^ T[crc & 15]; crc = (crc >> 4) ^ T[crc & 15]; }
  return crc;
}
inline uint32_t crc32Begin() { return 0xFFFFFFFFu; }
inline uint32_t crc32End(uint32_t crc) { return ~crc; }
inline uint32_t crc32Of(const uint8_t* d, size_t n) { return crc32End(crc32Update(crc32Begin(), d, n)); }

struct MimgHeader { uint16_t w = 0, h = 0, colors = 0; uint32_t dataLen = 0; };

// Checks the 16 header bytes against the file length. expectW/expectH = 0 means "any size within limits".
inline bool mimgParseHeader(const uint8_t* p, uint32_t fileLen, uint16_t expectW, uint16_t expectH, MimgHeader& o) {
  if (fileLen < MIMG_HDR + 2 + 1 || fileLen > MIMG_MAX_FILE) return false;
  if (p[0] != 'M' || p[1] != 'I' || p[2] != 1 || p[3] != 0) return false;
  MimgHeader h; h.w = rd16(p + 4); h.h = rd16(p + 6); h.colors = rd16(p + 8); h.dataLen = rd32(p + 12);
  if (rd16(p + 10) != 0) return false;
  if (h.w == 0 || h.h == 0 || h.w > MIMG_MAX_SIDE || h.h > MIMG_MAX_SIDE) return false;
  if (expectW && h.w != expectW) return false;
  if (expectH && h.h != expectH) return false;
  if (h.colors < 1 || h.colors > 256) return false;
  if ((uint64_t)MIMG_HDR + 2ull * h.colors + h.dataLen != fileLen) return false;
  if (h.dataLen < 2) return false;
  o = h; return true;
}

// Streaming decoder. `read` supplies the file sequentially from byte 0 (a LittleFS file, or a memory block in the tests).
class MimgDecoder {
 public:
  typedef size_t (*ReadFn)(void* ctx, uint8_t* buf, size_t n);
  bool begin(ReadFn rd, void* ctx, uint32_t fileLen, uint16_t expectW = 0, uint16_t expectH = 0) {
    rd_ = rd; ctx_ = ctx; bad_ = false; bufLen_ = bufPos_ = 0; runLeft_ = litLeft_ = 0; y_ = 0;
    uint8_t hb[MIMG_HDR];
    if (rd_(ctx_, hb, MIMG_HDR) != MIMG_HDR || !mimgParseHeader(hb, fileLen, expectW, expectH, hdr_)) { bad_ = true; return false; }
    for (int i = 0; i < hdr_.colors; i++) { uint8_t b[2]; if (rd_(ctx_, b, 2) != 2) { bad_ = true; return false; } pal_[i] = rd16(b); }
    dataLeft_ = hdr_.dataLen;
    return true;
  }
  const MimgHeader& header() const { return hdr_; }
  bool failed() const { return bad_; }
  uint16_t rowsDone() const { return y_; }
  bool finished() const { return !bad_ && y_ >= hdr_.h; }
  // Decodes up to `rows` rows into out (row-major, width x rows RGB565). Returns the number of rows produced; 0 = finished or failed.
  int rows(uint16_t* out, int rows) {
    if (bad_ || y_ >= hdr_.h) return 0;
    int n = 0;
    while (n < rows && y_ < hdr_.h) {
      for (uint16_t x = 0; x < hdr_.w; x++) { uint8_t idx; if (!next(idx)) { bad_ = true; return n; } out[(size_t)n * hdr_.w + x] = pal_[idx]; }
      y_++; n++;
    }
    if (y_ >= hdr_.h && (runLeft_ || litLeft_ || dataLeft_ || bufPos_ != bufLen_)) bad_ = true;      // trailing data = a corrupt or forged file
    return n;
  }
 private:
  bool byte(uint8_t& b) {
    if (dataLeft_ == 0) return false;
    if (bufPos_ == bufLen_) {
      size_t want = dataLeft_ < sizeof buf_ ? dataLeft_ : sizeof buf_;
      size_t got = rd_(ctx_, buf_, want); if (got == 0) return false;
      bufLen_ = got; bufPos_ = 0;
    }
    b = buf_[bufPos_++]; dataLeft_--; return true;
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
  ReadFn rd_ = nullptr; void* ctx_ = nullptr; MimgHeader hdr_; uint16_t pal_[256]; bool bad_ = true;
  uint8_t buf_[64]; size_t bufLen_ = 0, bufPos_ = 0; uint32_t dataLeft_ = 0; uint16_t runLeft_ = 0, litLeft_ = 0; uint8_t runIdx_ = 0; uint16_t y_ = 0;
};

// Full structural check of a stored / received file: header, every token, exact pixel count. Does not need any RAM beyond one row.
inline bool mimgValidate(MimgDecoder::ReadFn rd, void* ctx, uint32_t fileLen, uint16_t expectW, uint16_t expectH) {
  MimgDecoder d; if (!d.begin(rd, ctx, fileLen, expectW, expectH)) return false;
  uint16_t row[MIMG_MAX_SIDE];
  while (!d.finished()) { if (d.rows(row, 1) != 1 || d.failed()) return false; }
  return !d.failed();
}

}  // namespace meow
