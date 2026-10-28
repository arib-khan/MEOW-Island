// Meow Island link protocol (phone <-> ESP32 over BLE GATT). Pure C++: no Arduino or BLE headers, so it is unit-tested on a desktop
// (firmware/host-tests). The same byte layout is implemented in plugins/kotlin/DeviceProtocol.kt; tests/device_vectors.txt checks both
// sides against each other. All integers are little-endian.
//
//   frame   = 0xA5 | type | len | payload[len]            (len <= 200; any number of frames may be split across / packed into BLE writes)
//   status  = 10 fixed bytes, read / notify (device -> phone), see encodeStatus()
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

namespace meow {

constexpr uint8_t  PROTO_VERSION = 1;
constexpr uint8_t  FRAME_MAGIC   = 0xA5;
constexpr uint8_t  MAX_PAYLOAD   = 200;
constexpr uint32_t FRAME_TIMEOUT_MS = 2000;     // a half-received frame older than this is dropped

enum MsgType : uint8_t {
  MSG_HELLO = 0x01,       // [proto u8]
  MSG_SCREEN = 0x02,      // [screen u8: 0 clock, 1 steps, 2 music]
  MSG_STEPS = 0x03,       // see StepsData
  MSG_MUSIC = 0x04,       // see MusicData
  MSG_WIFI_SET = 0x05,    // [ssidLen u8][ssid][passLen u8][pass]
  MSG_WIFI_CLEAR = 0x06,  // (empty)
  MSG_UNPAIR = 0x07,      // [flags u8: bit0 also forget Wi-Fi]
  MSG_TIME = 0x08,        // [epoch u32][tzMinutes i16]
};

enum Screen : uint8_t { SCREEN_CLOCK = 0, SCREEN_STEPS = 1, SCREEN_MUSIC = 2, SCREEN_COUNT = 3 };
enum WifiState : uint8_t { WIFI_NONE = 0, WIFI_CONNECTING = 1, WIFI_CONNECTED = 2, WIFI_FAILED = 3 };
enum WifiReason : uint8_t { REASON_NONE = 0, REASON_NOT_FOUND = 1, REASON_AUTH = 2, REASON_TIMEOUT = 3, REASON_OTHER = 4 };

constexpr int STEP_CELLS = 54;   // 9 x 6 heatmap, oldest first, last = today (same as StepCardRenderer.CELLS)

struct Frame { uint8_t type; uint8_t len; uint8_t payload[MAX_PAYLOAD]; };

// Byte-stream reassembly. Feed it every byte of every BLE write; returns true when a whole frame is ready in `out`.
class FrameAssembler {
 public:
  FrameAssembler() { reset(); }
  void reset() { st_ = 0; n_ = 0; }
  // Drops a stalled partial frame (call from the main loop with millis()).
  void expire(uint32_t now) { if (st_ != 0 && (uint32_t)(now - last_) > FRAME_TIMEOUT_MS) reset(); }
  bool push(uint8_t b, uint32_t now, Frame& out) {
    last_ = now;
    switch (st_) {
      case 0: if (b == FRAME_MAGIC) st_ = 1; return false;            // anything else = noise, wait for the next magic byte
      case 1: cur_.type = b; st_ = 2; return false;
      case 2:
        if (b > MAX_PAYLOAD) { reset(); return false; }               // impossible length: resynchronise
        cur_.len = b; n_ = 0;
        if (b == 0) { out = cur_; reset(); return true; }
        st_ = 3; return false;
      default:
        cur_.payload[n_++] = b;
        if (n_ >= cur_.len) { out = cur_; reset(); return true; }
        return false;
    }
  }
 private:
  Frame cur_{}; uint8_t st_ = 0; uint8_t n_ = 0; uint32_t last_ = 0;
};

// ---- little-endian helpers ----
inline uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
inline uint32_t rd32(const uint8_t* p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
inline void wr16(uint8_t* p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
inline void wr32(uint8_t* p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24); }

// ---- STEPS: the data behind the phone's "Step goals" card (StepCardData) ----
// [steps u32][goal u32][flags u8][label u8][hint u8][subtitle u8][levels: 27 bytes = 54 nibbles, low nibble first]
struct StepsData {
  uint32_t steps = 0, goal = 0;
  bool hasSteps = false, goalOn = false, heatOn = false, happy = false;
  uint8_t label = 0;      // 0 "Today's Steps", 1 "Step counter is off"
  uint8_t hint = 0;       // 0 none, 1 "Turn it on in Meow Island > Health", 2 "Allow Physical activity in Health", 3 "Waiting for the first step reading"
  uint8_t subtitle = 0;   // 0 "Every step counts", 1 "Keep moving", 2 "Goal reached!"
  uint8_t levels[STEP_CELLS] = {0};   // 0..4
};
constexpr uint8_t STEPS_LEN = 4 + 4 + 4 + 27;
inline bool parseSteps(const uint8_t* p, uint8_t n, StepsData& o) {
  if (n != STEPS_LEN) return false;
  o.steps = rd32(p); o.goal = rd32(p + 4);
  if (o.goal > 10000000UL) return false;
  uint8_t f = p[8];
  o.hasSteps = f & 1; o.goalOn = f & 2; o.heatOn = f & 4; o.happy = f & 8;
  o.label = p[9] > 1 ? 0 : p[9];
  o.hint = p[10] > 3 ? 0 : p[10];
  o.subtitle = p[11] > 2 ? 0 : p[11];
  for (int i = 0; i < STEP_CELLS; i++) { uint8_t b = p[12 + i / 2]; uint8_t v = (i & 1) ? (b >> 4) : (b & 15); o.levels[i] = v > 4 ? 0 : v; }
  return true;
}

// ---- MUSIC: [flags u8: bit0 session, bit1 playing][pos s u16][dur s u16][titleLen u8][title][artistLen u8][artist] ----
constexpr uint8_t MAX_TITLE = 48, MAX_ARTIST = 40;
struct MusicData {
  bool session = false, playing = false;
  uint16_t posSec = 0, durSec = 0;
  char title[MAX_TITLE + 1] = "";
  char artist[MAX_ARTIST + 1] = "";
};
// Copies printable ASCII only (the TFT fonts have nothing else); anything else becomes a space and runs of spaces collapse.
inline void copyAscii(char* dst, size_t cap, const uint8_t* src, size_t n) {
  size_t o = 0; bool lastSpace = true;
  for (size_t i = 0; i < n && o + 1 < cap; i++) {
    uint8_t c = src[i];
    if (c < 0x20 || c > 0x7E) c = ' ';
    if (c == ' ') { if (lastSpace) continue; lastSpace = true; } else lastSpace = false;
    dst[o++] = (char)c;
  }
  while (o > 0 && dst[o - 1] == ' ') o--;
  dst[o] = 0;
}
inline bool parseMusic(const uint8_t* p, uint8_t n, MusicData& o) {
  if (n < 7) return false;
  o.session = p[0] & 1; o.playing = p[0] & 2;
  o.posSec = rd16(p + 1); o.durSec = rd16(p + 3);
  uint8_t tl = p[5];
  if (tl > MAX_TITLE || (size_t)6 + tl + 1 > n) return false;
  copyAscii(o.title, sizeof(o.title), p + 6, tl);
  uint8_t al = p[6 + tl];
  if (al > MAX_ARTIST || (size_t)7 + tl + al != n) return false;
  copyAscii(o.artist, sizeof(o.artist), p + 7 + tl, al);
  return true;
}

// ---- WIFI_SET ----
struct WifiCreds { uint8_t ssidLen = 0, passLen = 0; char ssid[33] = ""; char pass[64] = ""; };
// SSID 1..32 bytes; password empty (open network) or 8..63 bytes (WPA). Anything else is rejected, never truncated.
inline bool parseWifi(const uint8_t* p, uint8_t n, WifiCreds& o) {
  if (n < 2) return false;
  uint8_t sl = p[0];
  if (sl < 1 || sl > 32 || (size_t)2 + sl > n) return false;
  uint8_t pl = p[1 + sl];
  if ((pl != 0 && pl < 8) || pl > 63 || (size_t)2 + sl + pl != n) return false;
  memcpy(o.ssid, p + 1, sl); o.ssid[sl] = 0; o.ssidLen = sl;
  memcpy(o.pass, p + 2 + sl, pl); o.pass[pl] = 0; o.passLen = pl;
  for (uint8_t i = 0; i < sl; i++) if (o.ssid[i] == 0) return false;
  return true;
}

// ---- TIME ----
struct TimeData { uint32_t epoch = 0; int16_t tzMin = 0; };
inline bool parseTime(const uint8_t* p, uint8_t n, TimeData& o) {
  if (n != 6) return false;
  o.epoch = rd32(p); o.tzMin = (int16_t)rd16(p + 4);
  if (o.epoch < 1700000000UL || o.epoch > 4102444800UL) return false;       // Nov 2023 .. 2100: reject garbage
  if (o.tzMin < -14 * 60 || o.tzMin > 14 * 60) return false;
  return true;
}

// POSIX TZ string for a fixed offset, e.g. +330 -> "<+0530>-5:30". `out` needs 16 bytes.
inline void tzString(int16_t tzMin, char* out) {
  int a = tzMin < 0 ? -tzMin : tzMin; int h = a / 60, m = a % 60;
  char sign = tzMin < 0 ? '-' : '+';
  // POSIX offsets are written west-positive, so the sign flips after the colon form.
  int n = 0;
  out[n++] = '<'; out[n++] = sign; out[n++] = '0' + h / 10; out[n++] = '0' + h % 10; out[n++] = '0' + m / 10; out[n++] = '0' + m % 10; out[n++] = '>';
  out[n++] = tzMin < 0 ? '+' : '-';
  if (h >= 10) out[n++] = '0' + h / 10;
  out[n++] = '0' + h % 10;
  if (m) { out[n++] = ':'; out[n++] = '0' + m / 10; out[n++] = '0' + m % 10; }
  out[n] = 0;
}

// ---- STATUS (device -> phone), 10 bytes. Never contains Wi-Fi credentials or the SSID. ----
constexpr uint8_t STATUS_LEN = 10;
struct Status {
  uint8_t screen = 0, wifiState = 0, wifiReason = 0, bonds = 0, heapKb = 0, fwMajor = 1, fwMinor = 0;
  bool timeValid = false, pairingOpen = false, hasWifi = false, stepsFresh = false, musicFresh = false;
};
inline void encodeStatus(const Status& s, uint8_t* o) {
  o[0] = PROTO_VERSION; o[1] = s.fwMajor; o[2] = s.fwMinor; o[3] = s.screen; o[4] = s.wifiState; o[5] = s.wifiReason;
  o[6] = (s.timeValid ? 1 : 0) | (s.pairingOpen ? 2 : 0) | (s.hasWifi ? 4 : 0) | (s.stepsFresh ? 8 : 0) | (s.musicFresh ? 16 : 0);
  o[7] = s.bonds; o[8] = s.heapKb; o[9] = 0;
}

}  // namespace meow
