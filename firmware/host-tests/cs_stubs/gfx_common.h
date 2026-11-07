// Tiny stand-in for gfx_common.h + config.h, used ONLY by custom_store_test.cpp so the real custom_store.h (multi-slot storage, playback) runs on a desktop
// without the Adafruit GFX library. The "TFT" is a 240x320 RGB565 array that drawRGBBitmap writes into.
#pragma once
#include <Arduino.h>
#include <vector>
constexpr uint16_t TFT_W = 240, TFT_H = 320; constexpr bool SWAP_RED_BLUE = false;
constexpr uint16_t C_BG = 0, C_DATE = 1, C_TEXT = 2, C_TIME = 3, C_LAV = 4;
struct GfxFont {}; static GfxFont FreeSansBold12pt7b, FreeSans9pt7b;
struct FakeTft {
  std::vector<uint16_t> px = std::vector<uint16_t>(240 * 320, 0); int bitmapCalls = 0, fills = 0;
  void fillScreen(uint16_t c) { std::fill(px.begin(), px.end(), c); fills++; }
  void drawRGBBitmap(int x, int y, uint16_t* b, int w, int h) { bitmapCalls++; for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) { assert(x + i < 240 && y + j < 320); px[(size_t)(y + j) * 240 + x + i] = b[j * w + i]; } }
};
static FakeTft tft; static FakeTft* G = &tft; static int16_t Y0 = 0;
static int messages = 0; static std::string lastMsg, firstMsg;
static void drawPill(const char*, uint16_t, int) {}
static void gText(int, int, const char* t, const GfxFont*, uint16_t, int) { if (messages++ == 0) firstMsg = t; lastMsg = t; }
static void drawStar(int, int, int, uint16_t) {}
