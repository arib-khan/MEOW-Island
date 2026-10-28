// Shared drawing plumbing. Everything that draws goes through G / Y0 so the same code can target the TFT (Y0 = 0) or the 240x124
// off-screen canvas placed at any y (Y0 = that y): flicker-free band rendering for every screen.
#pragma once
#include <Adafruit_GFX.h>
#include <Adafruit_ST7789.h>
#include <Fonts/FreeSansBold9pt7b.h>
#include <Fonts/FreeSansBold12pt7b.h>
#include <Fonts/FreeSansBold18pt7b.h>
#include <Fonts/FreeSans9pt7b.h>
#include "config.h"

struct Pt { int16_t x, y; };   // declared before any function (Arduino IDE auto-generates prototypes above the first function)

Adafruit_ST7789 tft(PIN_CS, PIN_DC, PIN_RST);
GFXcanvas16* canvas = nullptr;
constexpr int CANVAS_W = 240, CANVAS_H = 124;

static Adafruit_GFX* G = &tft;
static int16_t Y0 = 0;
static inline void gHLine(int x, int y, int w, uint16_t c) { G->drawFastHLine(x, y - Y0, w, c); }
static inline void gRect(int x, int y, int w, int h, uint16_t c) { G->fillRect(x, y - Y0, w, h, c); }
static inline void gCircle(int x, int y, int r, uint16_t c) { G->fillCircle(x, y - Y0, r, c); }
static inline void gLine(int x0, int y0, int x1, int y1, uint16_t c) { G->drawLine(x0, y0 - Y0, x1, y1 - Y0, c); }
static inline void gTri(int x0, int y0, int x1, int y1, int x2, int y2, uint16_t c) { G->fillTriangle(x0, y0 - Y0, x1, y1 - Y0, x2, y2 - Y0, c); }
static inline void gRRect(int x, int y, int w, int h, int r, uint16_t c) { G->fillRoundRect(x, y - Y0, w, h, r, c); }
static inline void gRRectLine(int x, int y, int w, int h, int r, uint16_t c) { G->drawRoundRect(x, y - Y0, w, h, r, c); }

// ------------------------------ integer helpers ----------------------------
static uint16_t isqrt32(uint32_t v) {
  uint32_t r = 0, b = 1UL << 30;
  while (b > v) b >>= 2;
  while (b) {
    if (v >= r + b) { v -= r + b; r = (r >> 1) + b; } else r >>= 1;
    b >>= 2;
  }
  return (uint16_t)r;
}
static inline int iabs(int v) { return v < 0 ? -v : v; }

static uint8_t tri(uint32_t t, uint32_t period) {   // triangle wave 0..255
  uint32_t p = t % period, h = period / 2;
  uint32_t v = p < h ? p : period - p;
  return (uint8_t)((v * 255UL) / h);
}
static uint16_t scaleRGB(uint8_t r, uint8_t g, uint8_t b, uint8_t k) {
  return RGB((r * k) / 255, (g * k) / 255, (b * k) / 255);
}
static uint8_t lerp8(uint8_t a, uint8_t b, int t, int tmax) { return (uint8_t)(a + ((int)b - a) * t / tmax); }

static void fillEllipse(int cx, int cy, int rx, int ry, uint16_t c) {
  if (rx <= 0 || ry <= 0) return;
  int32_t rx2 = (int32_t)rx * rx, ry2 = (int32_t)ry * ry;
  for (int dy = -ry; dy <= ry; dy++) {
    int w = isqrt32((uint32_t)(rx2 * (ry2 - (int32_t)dy * dy) / ry2));
    gHLine(cx - w, cy + dy, 2 * w + 1, c);
  }
}

// Round-capped thick line made of stamped circles (optional vertical gradient is supplied by the clock via gradColor).
static uint16_t gradColor(int y);
static void strokeLine(int x0, int y0, int x1, int y1, int rad, uint16_t col, int stepPx, bool grad) {
  int dx = x1 - x0, dy = y1 - y0;
  int n = iabs(dx) > iabs(dy) ? iabs(dx) : iabs(dy);
  int steps = (n + stepPx - 1) / stepPx;
  if (steps < 1) steps = 1;
  for (int i = 0; i <= steps; i++) {
    int x = x0 + dx * i / steps, y = y0 + dy * i / steps;
    gCircle(x, y, rad, grad ? gradColor(y) : col);
  }
}

// Four-point sparkle
static void drawStar(int x, int y, int h, uint16_t c) {
  int w = h / 5; if (w < 1) w = 1;
  int hw = (h * 2) / 3; if (hw < 2) hw = 2;
  gTri(x, y - h, x + w, y, x - w, y, c);
  gTri(x, y + h, x + w, y, x - w, y, c);
  gTri(x - hw, y, x, y - w, x, y + w, c);
  gTri(x + hw, y, x, y - w, x, y + w, c);
}

// ------------------------------- text / bitmap helpers ---------------------
// Text goes through G with the band offset applied. align: 0 left, 1 centre, 2 right (x is the anchor).
static void gText(int x, int baseline, const char* s, const GFXfont* font, uint16_t col, int align = 0) {
  G->setTextWrap(false);                       // never wrap: marquee text starts off-screen
  G->setFont(font);
  G->setTextColor(col);
  int16_t x1, y1; uint16_t w, h;
  G->getTextBounds(s, 0, 0, &x1, &y1, &w, &h);
  int ox = align == 1 ? x - (int)w / 2 - x1 : align == 2 ? x - (int)w - x1 : x;
  G->setCursor(ox, baseline - Y0);
  G->print(s);
  G->setFont();
}
static int textWidth(const char* s, const GFXfont* font) {
  G->setFont(font);
  int16_t x1, y1; uint16_t w, h;
  G->getTextBounds(s, 0, 0, &x1, &y1, &w, &h);
  G->setFont();
  return (int)w;
}
// Default 6x8 font (size 1), used for small hints. align as in gText.
static void gSmall(int x, int y, const char* s, uint16_t col, int align = 0) {
  G->setTextWrap(false);
  G->setFont();
  G->setTextSize(1);
  G->setTextColor(col);
  int w = (int)strlen(s) * 6;
  int ox = align == 1 ? x - w / 2 : align == 2 ? x - w : x;
  G->setCursor(ox, y - Y0);
  G->print(s);
}
// Copies `src` into `dst`, shortening with "..." so it is at most `maxW` pixels wide in `font`.
static void fitText(char* dst, size_t cap, const char* src, const GFXfont* font, int maxW) {
  strncpy(dst, src, cap - 1); dst[cap - 1] = 0;
  if (textWidth(dst, font) <= maxW) return;
  size_t n = strlen(dst);
  while (n > 1) {
    n--; dst[n] = 0;
    char tmp[96]; snprintf(tmp, sizeof tmp, "%s...", dst);
    if (textWidth(tmp, font) <= maxW) { snprintf(dst, cap, "%s", tmp); return; }
  }
}

// RGB565 bitmap blit. On the TFT it takes the fast SPI path (the array lives in flash, readable like RAM on the ESP32);
// on the canvas it uses the generic per-pixel path, which is fine for the small amounts drawn there.
static void blit(int x, int y, const uint16_t* px, int w, int h) {
  if (G == &tft) tft.drawRGBBitmap(x, y, const_cast<uint16_t*>(px), w, h);
  else G->drawRGBBitmap(x, y - Y0, const_cast<uint16_t*>(px), w, h);
}

// Pushes the canvas to the screen with its top edge at screen row `y`.
static void pushCanvas(int y) { tft.drawRGBBitmap(0, y, canvas->getBuffer(), CANVAS_W, CANVAS_H); }

// Draws a full-screen scene flicker-free: for each 124-row band, fill the canvas, run `scene`, push. Needs the canvas; if it could
// not be allocated the scene is drawn straight to the TFT instead.
template <typename F> static void renderBanded(uint16_t bg, F scene) {
  if (!canvas) { G = &tft; Y0 = 0; tft.fillScreen(bg); scene(); return; }
  for (int y = 0; y < (int)TFT_H; y += CANVAS_H) {
    G = canvas; Y0 = y;
    canvas->fillScreen(bg);
    scene();
    G = &tft; Y0 = 0;
    pushCanvas(y);
  }
  G = &tft; Y0 = 0;
}

// Pill with a neon outline (the clock's date pill), reused by the other screens' headers.
static void drawPill(const char* text, uint16_t textColor, int y = 6) {
  G = &tft; Y0 = 0;
  tft.fillRoundRect(27, y, 186, 34, 17, C_GLOW2);
  tft.fillRoundRect(29, y + 2, 182, 30, 15, C_GLOW1);
  tft.fillRoundRect(31, y + 4, 178, 26, 13, C_LINE);
  tft.fillRoundRect(33, y + 6, 174, 22, 11, C_BG);
  tft.setFont(&FreeSansBold9pt7b);
  tft.setTextColor(textColor);
  int16_t x1, y1; uint16_t w, h;
  tft.getTextBounds(text, 0, 0, &x1, &y1, &w, &h);
  tft.setCursor(120 - (int)w / 2 - x1, (y + 17) - y1 - (int)h / 2);
  tft.print(text);
  tft.setFont();
}
// The same pill drawn through G / Y0 (so it can be part of a banded scene).
static void gPill(const char* text, uint16_t textColor, int y = 6) {
  gRRect(27, y, 186, 34, 17, C_GLOW2);
  gRRect(29, y + 2, 182, 30, 15, C_GLOW1);
  gRRect(31, y + 4, 178, 26, 13, C_LINE);
  gRRect(33, y + 6, 174, 22, 11, C_BG);
  G->setFont(&FreeSansBold9pt7b);
  int16_t x1, y1; uint16_t w, h;
  G->getTextBounds(text, 0, 0, &x1, &y1, &w, &h);
  G->setFont();
  gText(120, (y + 17) - y1 - (int)h / 2, text, &FreeSansBold9pt7b, textColor, 1);   // same centring as drawPill()
}
