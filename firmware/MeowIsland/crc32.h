// IEEE CRC-32 (the zlib / PNG one), nibble table: small and fast enough for photo-sized files. Shared by the image receiver and the photo store.
#pragma once
#include <stdint.h>
#include <stddef.h>

namespace meow {

inline uint32_t crc32Update(uint32_t crc, const uint8_t* d, size_t n) {
  static const uint32_t T[16] = {0x00000000, 0x1DB71064, 0x3B6E20C8, 0x26D930AC, 0x76DC4190, 0x6B6B51F4, 0x4DB26158, 0x5005713C,
                                 0xEDB88320, 0xF00F9344, 0xD6D6A3E8, 0xCB61B38C, 0x9B64C2B0, 0x86D3D2D4, 0xA00AE278, 0xBDBDF21C};
  for (size_t i = 0; i < n; i++) { crc ^= d[i]; crc = (crc >> 4) ^ T[crc & 15]; crc = (crc >> 4) ^ T[crc & 15]; }
  return crc;
}
inline uint32_t crc32Begin() { return 0xFFFFFFFFu; }
inline uint32_t crc32End(uint32_t crc) { return ~crc; }
inline uint32_t crc32Of(const uint8_t* d, size_t n) { return crc32End(crc32Update(crc32Begin(), d, n)); }

// What the phone compares to know whether the display holds exactly its photos: CRC-32 over (slot u8, file CRC-32 u32 little-endian) of every occupied
// slot in slot order; 0 when no slot is occupied. crcs[i] == 0 means slot i is empty.
inline uint32_t photoDigest(const uint32_t* crcs, int n) {
  uint32_t c = crc32Begin(); bool any = false;
  for (int i = 0; i < n; i++) {
    if (!crcs[i]) continue;
    uint8_t b[5] = {(uint8_t)i, (uint8_t)crcs[i], (uint8_t)(crcs[i] >> 8), (uint8_t)(crcs[i] >> 16), (uint8_t)(crcs[i] >> 24)};
    c = crc32Update(c, b, 5); any = true;
  }
  return any ? crc32End(c) : 0;
}

}  // namespace meow
