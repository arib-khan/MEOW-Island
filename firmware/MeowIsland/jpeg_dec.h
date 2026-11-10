// Small baseline-JPEG decoder for the gallery. Pure C++ (no Arduino / flash headers) so it is unit-tested on a desktop against a real decoder.
//
//   * the phone compresses every photo to a 240 x 320 baseline JPEG (Android's own encoder, quality chosen to fit a size budget); the ESP32 stores the
//     file in flash and decodes it again for every slide, straight to the TFT
//   * streaming: the file is read in small pieces through a read callback, and the picture is produced in horizontal bands of one MCU row (8 or 16
//     pixel rows). RAM use is a few KB plus the caller's band buffer (width x 16 x 2 bytes = 7.7 KB for 240 px), never a whole frame
//   * supported: baseline / extended-sequential Huffman, 8-bit, 1 component (grey) or 3 components (YCbCr) with 4:4:4, 4:2:2, 4:4:0 or 4:2:0
//     sampling, restart markers, custom Huffman and quantisation tables, any APPn/COM segments (skipped). Not supported (rejected, never crashes):
//     progressive, arithmetic coding, 12-bit, CMYK, more than 3 components
//   * the IDCT is a separable floating point one (the ESP32 has an FPU); chroma is upsampled by replication, so pixels differ from libjpeg's
//     "fancy upsampling" by a small amount at colour edges only
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <math.h>

namespace meow {

constexpr uint16_t JPG_MAX_SIDE = 1024;

class JpegDecoder {
 public:
  typedef size_t (*ReadFn)(void* ctx, uint8_t* buf, size_t n);

  // Reads the headers up to the start of the scan. Returns false for anything unsupported or malformed.
  bool begin(ReadFn rd, void* ctx, uint32_t len) {
    rd_ = rd; ctx_ = ctx; left_ = len; bufPos_ = bufLen_ = 0; bad_ = true; done_ = false; eof_ = false;
    bitBuf_ = 0; bitCnt_ = 0; hitMarker_ = false; marker_ = 0; dri_ = 0; sof_ = false; nq_ = 0; ncomp_ = 0; y_ = 0; mcuCount_ = 0;
    for (int i = 0; i < 4; i++) { qOk_[i] = false; hOk_[0][i] = hOk_[1][i] = false; }
    for (int u = 0; u < 8; u++) for (int x = 0; x < 8; x++) cosT_[x][u] = (float)((u == 0 ? 0.35355339059327373 : 0.5) * cos((2 * x + 1) * u * 3.14159265358979323846 / 16.0));
    if (byteIn() != 0xFF || byteIn() != 0xD8) return false;
    for (;;) {
      int m = nextMarker(); if (m < 0) return false;
      if (m == 0xD9 || m == 0xD8) return false;                                   // end of image before any scan / second SOI
      if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) continue;                        // markers without a length
      int hi = byteIn(), lo = byteIn(); if (lo < 0) return false;
      int segLen = (hi << 8) | lo; if (segLen < 2) return false; segLen -= 2;
      if (m == 0xC0 || m == 0xC1) { if (!readSof(segLen)) return false; }
      else if (m == 0xC4) { if (!readDht(segLen)) return false; }
      else if (m == 0xDB) { if (!readDqt(segLen)) return false; }
      else if (m == 0xDD) { if (segLen != 2) return false; int a = byteIn(), b = byteIn(); if (b < 0) return false; dri_ = (uint16_t)((a << 8) | b); }
      else if (m == 0xDA) { if (!sof_ || !readSos(segLen)) return false; break; }
      else if ((m >= 0xC2 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC)) return false;   // progressive, lossless, arithmetic, hierarchical
      else if (!skip(segLen)) return false;
    }
    mcusX_ = (w_ + mcuW_ - 1) / mcuW_; mcusY_ = (h_ + mcuH_ - 1) / mcuH_;
    bad_ = false; return true;
  }

  uint16_t width() const { return w_; }
  uint16_t height() const { return h_; }
  uint16_t bandRows() const { return mcuH_; }            // rows per band (8 or 16); the last band may be shorter
  bool failed() const { return bad_; }
  bool finished() const { return !bad_ && done_; }

  // Decodes the next band into out (row-major, width() pixels per row, RGB565, host byte order). Returns the number of rows written; 0 = no more bands
  // (finished() tells whether the picture was complete and well-formed) or an error (failed()).
  int nextBand(uint16_t* out) {
    if (bad_ || done_ || y_ >= h_) return 0;
    for (uint16_t mx = 0; mx < mcusX_; mx++) {
      if (dri_ && mcuCount_ && (mcuCount_ % dri_) == 0 && !restart()) { bad_ = true; return 0; }
      if (!decodeMcu()) { bad_ = true; return 0; }
      mcuCount_++;
      emitMcu(out, mx);
    }
    int rows = h_ - y_ < mcuH_ ? h_ - y_ : mcuH_; y_ += (uint16_t)rows;
    if (y_ >= h_) { if (!finish()) { bad_ = true; return 0; } done_ = true; }
    return rows;
  }

 private:
  // ---------------------------------------------------------------------------------------------- byte / bit input
  int byteIn() {
    if (bufPos_ == bufLen_) {
      if (left_ == 0) return -1;
      size_t want = left_ < sizeof buf_ ? left_ : sizeof buf_; size_t got = rd_(ctx_, buf_, want);
      if (got == 0) { left_ = 0; return -1; }
      bufLen_ = got; bufPos_ = 0; left_ -= (uint32_t)got;
    }
    return buf_[bufPos_++];
  }
  bool skip(int n) { while (n-- > 0) if (byteIn() < 0) return false; return true; }
  int nextMarker() {                                    // skips to the next marker (FF followed by a non-zero, non-FF byte)
    int b = byteIn();
    for (;;) {
      while (b >= 0 && b != 0xFF) b = byteIn();
      if (b < 0) return -1;
      while (b == 0xFF) b = byteIn();
      if (b < 0) return -1;
      if (b != 0) return b;
      b = byteIn();
    }
  }
  void fill() {
    while (bitCnt_ <= 24) {
      int b;
      if (hitMarker_) b = 0;
      else {
        b = byteIn();
        if (b < 0) { eof_ = true; hitMarker_ = true; b = 0; }
        else if (b == 0xFF) { int b2 = byteIn(); if (b2 == 0) b = 0xFF; else { marker_ = (uint8_t)(b2 < 0 ? 0 : b2); hitMarker_ = true; b = 0; if (b2 < 0) eof_ = true; } }
      }
      bitBuf_ |= (uint32_t)b << (24 - bitCnt_); bitCnt_ += 8;
    }
  }
  int getBits(int n) { if (n == 0) return 0; fill(); int v = (int)(bitBuf_ >> (32 - n)); bitBuf_ <<= n; bitCnt_ -= n; return v; }
  int getBit() { return getBits(1); }

  // ---------------------------------------------------------------------------------------------- tables
  bool readSof(int len) {
    if (sof_ || len < 6) return false;
    int p = byteIn(), hh = byteIn(), hl = byteIn(), wh = byteIn(), wl = byteIn(), n = byteIn(); if (n < 0) return false;
    if (p != 8) return false;
    h_ = (uint16_t)((hh << 8) | hl); w_ = (uint16_t)((wh << 8) | wl);
    if (w_ == 0 || h_ == 0 || w_ > JPG_MAX_SIDE || h_ > JPG_MAX_SIDE) return false;
    if ((n != 1 && n != 3) || len != 6 + 3 * n) return false;
    uint8_t hmax = 1, vmax = 1;
    for (int i = 0; i < n; i++) {
      int id = byteIn(), hv = byteIn(), tq = byteIn(); if (tq < 0) return false;
      int hs = hv >> 4, vs = hv & 15; if (hs < 1 || hs > 2 || vs < 1 || vs > 2 || tq > 3) return false;
      cid_[i] = (uint8_t)id; ch_[i] = (uint8_t)hs; cv_[i] = (uint8_t)vs; cq_[i] = (uint8_t)tq;
      if (hs > hmax) hmax = (uint8_t)hs; if (vs > vmax) vmax = (uint8_t)vs;
    }
    ncomp_ = (uint8_t)n; hmax_ = hmax; vmax_ = vmax;
    if (n == 1) { ch_[0] = cv_[0] = 1; hmax_ = vmax_ = 1; }                      // a grey picture is one 8x8 block per MCU whatever the header says
    else { for (int i = 0; i < 3; i++) if (hmax_ % ch_[i] != 0 || vmax_ % cv_[i] != 0) return false; }
    mcuW_ = (uint8_t)(8 * hmax_); mcuH_ = (uint8_t)(8 * vmax_);
    sof_ = true; return true;
  }
  bool readDqt(int len) {
    while (len > 0) {
      int pq = byteIn(); if (pq < 0) return false; len--;
      int t = pq & 15, prec = pq >> 4; if (t > 3 || prec > 1) return false;
      int need = prec ? 128 : 64; if (len < need) return false;
      for (int i = 0; i < 64; i++) { int v = byteIn(); if (prec) v = (v << 8) | byteIn(); if (v < 0) return false; q_[t][kZigzag[i]] = (uint16_t)v; }
      qOk_[t] = true; len -= need;
    }
    return true;
  }
  bool readDht(int len) {
    while (len > 0) {
      int tc = byteIn(); if (tc < 0 || len < 17) return false;
      int cls = tc >> 4, id = tc & 15; if (cls > 1 || id > 1) return false;
      Huff& hf = hf_[cls][id]; int total = 0; uint8_t counts[17];
      for (int i = 1; i <= 16; i++) { int c = byteIn(); if (c < 0) return false; counts[i] = (uint8_t)c; total += c; }
      len -= 17; if (total > 256 || total > len) return false;
      for (int i = 0; i < total; i++) { int v = byteIn(); if (v < 0) return false; hf.val[i] = (uint8_t)v; }
      len -= total;
      int code = 0, k = 0;                                                       // canonical code assignment; rejects over-subscribed tables
      for (int l = 1; l <= 16; l++) {
        hf.valptr[l] = (int16_t)k; hf.mincode[l] = (int32_t)code; code += counts[l]; k += counts[l];
        if (code > (1 << l)) return false;
        hf.maxcode[l] = counts[l] ? (int32_t)(code - 1) : -1; code <<= 1;
      }
      hOk_[cls][id] = true;
    }
    return true;
  }
  bool readSos(int len) {
    int n = byteIn(); if (n != ncomp_ || len != 1 + 2 * n + 3) return false;
    for (int i = 0; i < n; i++) {
      int id = byteIn(), t = byteIn(); if (t < 0) return false;
      int k = -1; for (int j = 0; j < n; j++) if (cid_[j] == id) k = j; if (k < 0) return false;
      td_[k] = (uint8_t)(t >> 4); ta_[k] = (uint8_t)(t & 15); if (td_[k] > 1 || ta_[k] > 1) return false;
      if (!qOk_[cq_[k]] || !hOk_[0][td_[k]] || !hOk_[1][ta_[k]]) return false;
    }
    int ss = byteIn(), se = byteIn(), a = byteIn(); if (a < 0 || ss != 0 || se != 63 || a != 0) return false;   // baseline scan only
    for (int i = 0; i < 3; i++) pred_[i] = 0;
    return true;
  }

  // ---------------------------------------------------------------------------------------------- entropy decoding
  struct Huff { int32_t maxcode[17]; int32_t mincode[17]; int16_t valptr[17]; uint8_t val[256]; };
  int decodeHuff(const Huff& h) {
    int code = 0;
    for (int l = 1; l <= 16; l++) {
      code = (code << 1) | getBit();
      if (h.maxcode[l] >= 0 && code <= h.maxcode[l] && code >= h.mincode[l]) return h.val[h.valptr[l] + (code - h.mincode[l])];
    }
    return -1;
  }
  static int extend(int v, int t) { return v < (1 << (t - 1)) ? v - (1 << t) + 1 : v; }
  bool decodeBlock(int c, int16_t* coef) {                                       // fills coef (natural order, dequantised)
    memset(coef, 0, 64 * sizeof(int16_t));
    int t = decodeHuff(hf_[0][td_[c]]); if (t < 0 || t > 11) return false;
    int diff = t ? extend(getBits(t), t) : 0; pred_[c] += diff;
    const uint16_t* q = q_[cq_[c]];
    coef[0] = (int16_t)(pred_[c] * q[0]);
    for (int k = 1; k < 64;) {
      int rs = decodeHuff(hf_[1][ta_[c]]); if (rs < 0) return false;
      int r = rs >> 4, s = rs & 15;
      if (s == 0) { if (r == 15) { k += 16; continue; } break; }
      k += r; if (k > 63) return false;
      coef[kZigzag[k]] = (int16_t)(extend(getBits(s), s) * q[kZigzag[k]]); k++;
    }
    return true;
  }
  void idct(const int16_t* in, uint8_t* out, int stride) {
    float t[64];
    for (int v = 0; v < 8; v++) {
      const int16_t* row = in + v * 8; bool any = false; for (int u = 0; u < 8; u++) if (row[u]) { any = true; break; }
      if (!any) { for (int x = 0; x < 8; x++) t[v * 8 + x] = 0.f; continue; }
      for (int x = 0; x < 8; x++) { float s = 0; for (int u = 0; u < 8; u++) s += cosT_[x][u] * row[u]; t[v * 8 + x] = s; }
    }
    for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) {
      float s = 0; for (int v = 0; v < 8; v++) s += cosT_[y][v] * t[v * 8 + x];
      int p = (int)lrintf(s) + 128; out[y * stride + x] = (uint8_t)(p < 0 ? 0 : p > 255 ? 255 : p);
    }
  }
  bool decodeMcu() {
    int16_t coef[64];
    for (int c = 0; c < ncomp_; c++) {
      int stride = ch_[c] * 8;
      for (int by = 0; by < cv_[c]; by++) for (int bx = 0; bx < ch_[c]; bx++) {
        if (!decodeBlock(c, coef)) return false;
        idct(coef, smp_[c] + (by * 8) * stride + bx * 8, stride);
      }
    }
    return true;
  }
  void emitMcu(uint16_t* out, uint16_t mx) {
    int x0 = mx * mcuW_; int cw = (int)w_ - x0 < mcuW_ ? (int)w_ - x0 : mcuW_;
    int rows = (int)h_ - y_ < mcuH_ ? (int)h_ - y_ : mcuH_;
    for (int py = 0; py < rows; py++) {
      uint16_t* o = out + (size_t)py * w_ + x0;
      for (int px = 0; px < cw; px++) {
        int Y = smp_[0][(py * cv_[0] / vmax_) * (ch_[0] * 8) + (px * ch_[0] / hmax_)], r, g, b;
        if (ncomp_ == 1) { r = g = b = Y; }
        else {
          int cb = smp_[1][(py * cv_[1] / vmax_) * (ch_[1] * 8) + (px * ch_[1] / hmax_)] - 128, cr = smp_[2][(py * cv_[2] / vmax_) * (ch_[2] * 8) + (px * ch_[2] / hmax_)] - 128;
          r = Y + ((91881 * cr + 32768) >> 16); g = Y - ((22554 * cb + 46802 * cr + 32768) >> 16); b = Y + ((116130 * cb + 32768) >> 16);
          r = r < 0 ? 0 : r > 255 ? 255 : r; g = g < 0 ? 0 : g > 255 ? 255 : g; b = b < 0 ? 0 : b > 255 ? 255 : b;
        }
        o[px] = (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
      }
    }
  }
  bool restart() {                                                               // consume RSTn, reset predictors and the bit reader
    bitBuf_ = 0; bitCnt_ = 0;
    if (!hitMarker_) { int m = nextMarker(); if (m < 0) return false; marker_ = (uint8_t)m; }
    if (marker_ < 0xD0 || marker_ > 0xD7) return false;
    hitMarker_ = false; marker_ = 0;
    for (int i = 0; i < 3; i++) pred_[i] = 0;
    return true;
  }
  bool finish() {                                                                // after the last MCU: the next marker must be EOI
    if (eof_ && hitMarker_ && marker_ == 0) return false;                        // the data ended without an end-of-image marker
    int m = hitMarker_ ? marker_ : nextMarker();
    return m == 0xD9;
  }

  static constexpr uint8_t kZigzag[64] = {0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
                                          35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};
  ReadFn rd_ = nullptr; void* ctx_ = nullptr; uint32_t left_ = 0; uint8_t buf_[128]; size_t bufPos_ = 0, bufLen_ = 0;
  uint32_t bitBuf_ = 0; int bitCnt_ = 0; bool hitMarker_ = false, eof_ = false, bad_ = true, done_ = false, sof_ = false; uint8_t marker_ = 0;
  uint16_t w_ = 0, h_ = 0, mcusX_ = 0, mcusY_ = 0, y_ = 0, dri_ = 0; uint32_t mcuCount_ = 0; uint8_t mcuW_ = 8, mcuH_ = 8, hmax_ = 1, vmax_ = 1, ncomp_ = 0, nq_ = 0;
  uint8_t cid_[3] = {0}, ch_[3] = {1, 1, 1}, cv_[3] = {1, 1, 1}, cq_[3] = {0}, td_[3] = {0}, ta_[3] = {0}; int pred_[3] = {0};
  uint16_t q_[4][64]; bool qOk_[4]; Huff hf_[2][2]; bool hOk_[2][4]; float cosT_[8][8]; uint8_t smp_[3][256];
};
constexpr uint8_t JpegDecoder::kZigzag[64];

// Structural check of a whole file: headers, every MCU, the end-of-image marker. Needs one band buffer (width x 16 pixels). The decoder object is about 3.5 KB:
// pass one that is not on a small task stack (the firmware keeps a static one), or use the overload that creates it locally (desktop tests).
inline bool jpegValidate(JpegDecoder& d, JpegDecoder::ReadFn rd, void* ctx, uint32_t len, uint16_t expectW, uint16_t expectH, uint16_t* band, size_t bandPixels) {
  if (!d.begin(rd, ctx, len)) return false;
  if ((expectW && d.width() != expectW) || (expectH && d.height() != expectH)) return false;
  if ((size_t)d.width() * d.bandRows() > bandPixels) return false;
  while (d.nextBand(band) > 0) {}
  return d.finished();
}
inline bool jpegValidate(JpegDecoder::ReadFn rd, void* ctx, uint32_t len, uint16_t expectW, uint16_t expectH, uint16_t* band, size_t bandPixels) {
  JpegDecoder d; return jpegValidate(d, rd, ctx, len, expectW, expectH, band, bandPixels);
}

}  // namespace meow
