/*
  MEOW ISLAND - neon sleeping-cat clock
  Board : ESP32 DevKit V1 (ESP-WROOM-32), "ESP32 Dev Module"
  Screen: 2.8" SPI TFT, ST7789, 240x320, portrait, non-touch
  Libs  : Adafruit GFX Library, Adafruit ST7735 and ST7789 Library

  Rendering strategy (no bitmap assets, ~60 KB heap, no full framebuffer):
   - Clock digits, date pill and the top stars are drawn directly to the TFT,
     and only the digits that changed are redrawn.
   - The cat scene (y = 196..319) is composed in a 240x124 RGB565 off-screen
     canvas (59,520 bytes of heap) and pushed in one burst -> flicker-free
     breathing, floating Z's and pulsing cheeks/stars.
   - If the canvas cannot be allocated, the cat is drawn once, statically.
*/

#include <Arduino.h>
#include <WiFi.h>
#include <SPI.h>
#include <time.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ST7789.h>
#include <Fonts/FreeSansBold9pt7b.h>

// Types used in function signatures must be declared before any function
// (Arduino IDE auto-generates prototypes above the first function).
struct Pt { int16_t x, y; };

// ============================ USER CONFIGURATION ===========================
// Wi-Fi: put real values in secrets.h (see secrets_example.h) or edit below.
#if __has_include("secrets.h")
  #include "secrets.h"
#endif
#ifndef WIFI_SSID
  #define WIFI_SSID "YOUR_WIFI_NAME"
#endif
#ifndef WIFI_PASS
  #define WIFI_PASS "YOUR_WIFI_PASSWORD"
#endif

// POSIX timezone string. India (IST, UTC+5:30, no DST):
const char* TZ_INFO = "IST-5:30";
// Other examples: "UTC0", "CET-1CEST,M3.5.0,M10.5.0/3", "EST5EDT,M3.2.0,M11.1.0"
const char* NTP_1 = "pool.ntp.org";
const char* NTP_2 = "time.google.com";
const char* NTP_3 = "time.nist.gov";

constexpr bool     TIME_24H          = true;   // false = 12-hour clock
constexpr bool     LEADING_ZERO_12H  = false;  // 12-hour mode: "9:41" instead of "09:41"
constexpr uint32_t WIFI_RETRY_MS     = 20000;  // reconnect attempt interval

// ---- Pins (your existing wiring, unchanged; these are the default VSPI pins)
constexpr int8_t PIN_CS   = 5;
constexpr int8_t PIN_DC   = 2;
constexpr int8_t PIN_RST  = 4;
constexpr int8_t PIN_SCK  = 18;
constexpr int8_t PIN_MOSI = 23;
constexpr int8_t PIN_MISO = 19;   // not needed for display-only use
constexpr int8_t PIN_BL   = -1;   // -1 = backlight wired straight to 3.3V

// ---- Display module variant (change here if your module differs)
constexpr uint16_t TFT_W         = 240;
constexpr uint16_t TFT_H         = 320;
constexpr uint8_t  TFT_ROTATION  = 0;        // 0 portrait; 2 = portrait rotated 180
constexpr uint8_t  TFT_SPI_MODE  = SPI_MODE0;// try SPI_MODE3 if the screen stays white/black
constexpr bool     TFT_INVERT    = false;     // flip to false if colours look negative
constexpr bool     SWAP_RED_BLUE = false;    // true if red and blue are swapped
constexpr uint32_t SPI_HZ        = 40000000; // lower to 20000000 if you see glitches
// ===========================================================================

constexpr uint16_t RGB(uint8_t r, uint8_t g, uint8_t b) {
  return SWAP_RED_BLUE
    ? (uint16_t)(((b & 0xF8) << 8) | ((g & 0xFC) << 3) | (r >> 3))
    : (uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

// ------------------------------- palette -----------------------------------
constexpr uint16_t C_BG      = RGB(3, 1, 6);
constexpr uint16_t C_GLOW2   = RGB(36, 1, 80);
constexpr uint16_t C_GLOW1   = RGB(76, 12, 170);
constexpr uint16_t C_LINE    = RGB(189, 77, 248);
constexpr uint16_t C_LINE_HI = RGB(221, 130, 255);
constexpr uint16_t C_LAV     = RGB(221, 170, 252);
constexpr uint16_t C_PINK    = RGB(255, 55, 225);
constexpr uint16_t C_NOSE    = RGB(255, 85, 205);
constexpr uint16_t C_MOUTH   = RGB(255, 105, 215);
constexpr uint16_t C_TOE     = RGB(70, 35, 130);
constexpr uint16_t C_DATE    = RGB(190, 125, 255);
constexpr uint16_t C_DGLOW2  = RGB(34, 4, 75);
constexpr uint16_t C_DGLOW1  = RGB(105, 25, 195);

// ------------------------------- layout ------------------------------------
constexpr int CANVAS_Y = 196, CANVAS_W = 240, CANVAS_H = 124;
constexpr int DY = 46, DW = 48, DH = 142, DR = 7;           // digit cell geometry
constexpr int DIGIT_X[4] = {9, 61, 131, 183};
constexpr uint8_t CODE_DASH = 10, CODE_BLANK = 11;
constexpr time_t  MIN_VALID_EPOCH = 1700000000;             // Nov 2023: "clock is set"

Adafruit_ST7789 tft(PIN_CS, PIN_DC, PIN_RST);
GFXcanvas16* canvas = nullptr;

// All scene drawing goes through G / Y0 so the same code can target the TFT
// (Y0 = 0) or the off-screen canvas (Y0 = CANVAS_Y).
static Adafruit_GFX* G = &tft;
static int16_t Y0 = 0;
static inline void gHLine(int x, int y, int w, uint16_t c) { G->drawFastHLine(x, y - Y0, w, c); }
static inline void gRect(int x, int y, int w, int h, uint16_t c) { G->fillRect(x, y - Y0, w, h, c); }
static inline void gCircle(int x, int y, int r, uint16_t c) { G->fillCircle(x, y - Y0, r, c); }
static inline void gLine(int x0, int y0, int x1, int y1, uint16_t c) { G->drawLine(x0, y0 - Y0, x1, y1 - Y0, c); }
static inline void gTri(int x0, int y0, int x1, int y1, int x2, int y2, uint16_t c) {
  G->fillTriangle(x0, y0 - Y0, x1, y1 - Y0, x2, y2 - Y0, c);
}

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

// Digit gradient: light lavender (top) -> violet (middle) -> hot pink (bottom)
static uint16_t gradColor(int y) {
  int t = (y - DY) * 255 / DH;
  if (t < 0) t = 0; if (t > 255) t = 255;
  if (t < 150) return RGB(lerp8(206, 184, t, 150), lerp8(172, 128, t, 150), 255);
  t -= 150;
  return RGB(lerp8(184, 255, t, 105), lerp8(128, 100, t, 105), lerp8(255, 250, t, 105));
}

static void fillEllipse(int cx, int cy, int rx, int ry, uint16_t c) {
  if (rx <= 0 || ry <= 0) return;
  int32_t rx2 = (int32_t)rx * rx, ry2 = (int32_t)ry * ry;
  for (int dy = -ry; dy <= ry; dy++) {
    int w = isqrt32((uint32_t)(rx2 * (ry2 - (int32_t)dy * dy) / ry2));
    gHLine(cx - w, cy + dy, 2 * w + 1, c);
  }
}

// Round-capped thick line made of stamped circles (optional vertical gradient).
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

// Arc tables: angles 25..155 deg in 10 deg steps, values x100.
static const int8_t COS_T[14] = {91, 82, 71, 57, 42, 26, 9, -9, -26, -42, -57, -71, -82, -91};
static const int8_t SIN_T[14] = {42, 57, 71, 82, 91, 97, 100, 100, 97, 91, 82, 71, 57, 42};
static void arc(int cx, int cy, int r, int dirY, int rad, uint16_t c) {   // dirY -1 = ∩, +1 = ∪
  int px = 0, py = 0;
  for (int i = 0; i < 14; i++) {
    int x = cx + (r * COS_T[i]) / 100;
    int y = cy + dirY * ((r * SIN_T[i]) / 100);
    if (i) strokeLine(px, py, x, y, rad, c, 1, false);
    px = x; py = y;
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

// ------------------------------ digit glyphs -------------------------------
// Centre-line strokes on a 0..100 grid; pair (255,x) = new stroke, (254,x) = end.
static const uint8_t g0[] PROGMEM = {50,0, 88,8, 100,30, 100,70, 88,92, 50,100, 12,92, 0,70, 0,30, 12,8, 50,0, 254,0};
static const uint8_t g1[] PROGMEM = {0,28, 72,0, 72,100, 254,0};
static const uint8_t g2[] PROGMEM = {0,25, 14,8, 50,0, 86,8, 100,27, 88,52, 0,100, 100,100, 254,0};
static const uint8_t g3[] PROGMEM = {0,14, 48,0, 92,10, 100,32, 56,50, 100,68, 92,90, 48,100, 0,86, 254,0};
static const uint8_t g4[] PROGMEM = {75,0, 0,68, 100,68, 255,0, 75,0, 75,100, 254,0};
static const uint8_t g5[] PROGMEM = {95,0, 8,0, 4,46, 50,38, 90,50, 100,72, 90,92, 50,100, 0,90, 254,0};
static const uint8_t g6[] PROGMEM = {92,10, 50,0, 12,15, 0,50, 0,72, 14,94, 50,100, 88,94, 100,72, 88,50, 50,42, 8,56, 254,0};
static const uint8_t g7[] PROGMEM = {0,0, 100,0, 35,100, 254,0};
static const uint8_t g8[] PROGMEM = {50,0, 88,10, 92,33, 50,50, 8,33, 12,10, 50,0, 255,0, 50,50, 100,68, 95,92, 50,100, 5,92, 0,68, 50,50, 254,0};
static const uint8_t g9[] PROGMEM = {8,90, 50,100, 88,85, 100,50, 100,28, 88,6, 50,0, 12,6, 0,28, 12,50, 50,58, 92,44, 254,0};
static const uint8_t gD[] PROGMEM = {10,50, 90,50, 254,0};
static const uint8_t* const GLYPHS[11] = {g0, g1, g2, g3, g4, g5, g6, g7, g8, g9, gD};

static void drawGlyph(int x0, uint8_t code) {
  if (code >= CODE_BLANK) return;
  const uint8_t* d = GLYPHS[code];
  for (int pass = 0; pass < 3; pass++) {            // glow, glow, gradient core
    int rad = (pass == 0) ? DR + 4 : (pass == 1) ? DR + 2 : DR;
    int step = (pass == 2) ? 2 : 3;
    uint16_t col = (pass == 0) ? C_DGLOW2 : C_DGLOW1;
    bool have = false; int px = 0, py = 0;
    for (const uint8_t* p = d; p[0] != 254; p += 2) {
      if (p[0] == 255) { have = false; continue; }
      int x = x0 + DR + ((int)p[0] * (DW - 2 * DR)) / 100;
      int y = DY + DR + ((int)p[1] * (DH - 2 * DR)) / 100;
      if (have) strokeLine(px, py, x, y, rad, col, step, pass == 2);
      else      strokeLine(x, y, x, y, rad, col, 1, pass == 2);
      px = x; py = y; have = true;
    }
  }
}

static void drawColon() {
  G = &tft; Y0 = 0;
  int ys[2] = {DY + (DH * 30) / 100, DY + (DH * 66) / 100};
  for (int i = 0; i < 2; i++) {
    gCircle(120, ys[i], 12, C_DGLOW2);
    gCircle(120, ys[i], 10, C_DGLOW1);
    gCircle(120, ys[i], 8, gradColor(ys[i]));
  }
}

static uint8_t shown[4] = {255, 255, 255, 255};
static char shownDate[24] = "";

static void drawDigitCell(int i, uint8_t code) {
  G = &tft; Y0 = 0;
  gRect(DIGIT_X[i] - 2, DY - 4, DW + 4, DH + 8, C_BG);
  drawGlyph(DIGIT_X[i], code);
  if (i == 1 || i == 2) drawColon();               // neighbouring glow may have been trimmed
}

static void drawDatePill(const char* text) {
  G = &tft; Y0 = 0;
  tft.fillRoundRect(27, 6, 186, 34, 17, C_GLOW2);
  tft.fillRoundRect(29, 8, 182, 30, 15, C_GLOW1);
  tft.fillRoundRect(31, 10, 178, 26, 13, C_LINE);
  tft.fillRoundRect(33, 12, 174, 22, 11, C_BG);
  tft.setFont(&FreeSansBold9pt7b);
  tft.setTextColor(C_DATE);
  int16_t x1, y1; uint16_t w, h;
  tft.getTextBounds(text, 0, 0, &x1, &y1, &w, &h);
  tft.setCursor(120 - (int)w / 2 - x1, 23 - y1 - (int)h / 2);
  tft.print(text);
  tft.setFont();
}

// ------------------------------- stars -------------------------------------
struct StarDef { int16_t x, y, h; uint16_t phase; };
static const StarDef TOP_STARS[] = {{13, 18, 12, 0}, {21, 36, 6, 700}, {226, 21, 13, 1200}, {221, 38, 6, 1900}};
static const StarDef LOW_STARS[] = {{9, 226, 8, 400}, {7, 246, 4, 1500}, {233, 246, 7, 900}};

static uint16_t starColor(uint32_t now, uint16_t phase) {
  uint8_t k = 130 + (tri(now + phase, 2400) * 125) / 255;
  return scaleRGB(205, 175, 255, k);
}
static void drawTopStars(uint32_t now) {
  G = &tft; Y0 = 0;
  for (const StarDef& s : TOP_STARS) drawStar(s.x, s.y, s.h, starColor(now, s.phase));
  tft.drawPixel(24, 14, C_LAV);  tft.drawPixel(216, 17, C_LAV);
  tft.drawPixel(10, 40, C_GLOW1); tft.drawPixel(231, 44, C_GLOW1);
}

// ------------------------------- the cat -----------------------------------
static const Pt EAR_L[3] = {{36, 202}, {17, 250}, {84, 228}};
static const Pt EAR_R[3] = {{206, 204}, {212, 252}, {158, 228}};

static void triScaled(const Pt* p, int by, int pct, uint16_t c) {
  int cx = (p[0].x + p[1].x + p[2].x) / 3;
  int cy = (p[0].y + p[1].y + p[2].y) / 3;
  int x[3], y[3];
  for (int i = 0; i < 3; i++) {
    x[i] = cx + ((p[i].x - cx) * pct) / 100;
    y[i] = (cy - by) + ((p[i].y - cy) * pct) / 100;
  }
  gTri(x[0], y[0], x[1], y[1], x[2], y[2], c);
}
static void drawEar(const Pt* p, int by) {
  triScaled(p, by, 128, C_GLOW2);
  triScaled(p, by, 117, C_GLOW1);
  triScaled(p, by, 108, C_LINE);
  triScaled(p, by, 94, C_LINE_HI);
  triScaled(p, by, 86, C_BG);
  triScaled(p, by, 52, C_PINK);
}

static void drawHead(int by) {
  const int cx = 120, cy = 262 - by, rx = 108, ry = 50;
  fillEllipse(cx, cy, rx + 7, ry + 7, C_GLOW2);
  fillEllipse(cx, cy, rx + 5, ry + 5, C_GLOW1);
  fillEllipse(cx, cy, rx + 3, ry + 3, C_LINE);
  fillEllipse(cx, cy, rx + 1, ry + 1, C_LINE_HI);
  fillEllipse(cx, cy, rx, ry, C_BG);
}

static void spike(int x, int base, int h, int w, int by) {
  gTri(x - w, base - by, x, base - h - by, x + w, base - by, C_LINE);
  gTri(x - w + 3, base - by, x, base - h + 5 - by, x + w - 3, base - by, C_BG);
}
static void drawTufts(int by) {
  spike(96, 217, 12, 6, by);
  spike(113, 216, 14, 6, by);
  spike(127, 216, 9, 5, by);
}

static void drawFace(int by, uint32_t now) {
  uint8_t l = tri(now, 3200);
  uint16_t cheek = RGB(225 + (l * 30) / 255, 70 + (l * 50) / 255, 200 + (l * 40) / 255);
  fillEllipse(55, 284 - by, 14, 7, cheek);
  fillEllipse(178, 288 - by, 14, 7, cheek);
  arc(78, 278 - by, 18, -1, 2, C_LAV);                    // closed eyes
  arc(154, 281 - by, 18, -1, 2, C_LAV);
  gTri(108, 281 - by, 124, 281 - by, 116, 291 - by, C_NOSE);
  gLine(116, 291 - by, 116, 296 - by, C_MOUTH);
  arc(110, 297 - by, 6, 1, 1, C_MOUTH);                   // "w" mouth
  arc(122, 297 - by, 6, 1, 1, C_MOUTH);
  // whiskers (2 px thick)
  gLine(3, 255 - by, 32, 274 - by, C_LAV);  gLine(3, 256 - by, 32, 275 - by, C_LAV);
  gLine(1, 269 - by, 30, 281 - by, C_LAV);  gLine(1, 270 - by, 30, 282 - by, C_LAV);
  gLine(236, 265 - by, 209, 277 - by, C_LAV); gLine(236, 266 - by, 209, 278 - by, C_LAV);
  gLine(235, 279 - by, 211, 284 - by, C_LAV); gLine(235, 280 - by, 211, 285 - by, C_LAV);
}

static void drawPaw(int cx, int cy, const int* toeX) {
  const int rx = 34, ry = 19;
  fillEllipse(cx, cy, rx + 5, ry + 5, C_GLOW1);
  fillEllipse(cx, cy, rx + 3, ry + 3, C_LINE);
  fillEllipse(cx, cy, rx + 1, ry + 1, C_LINE_HI);
  fillEllipse(cx, cy, rx, ry, C_BG);
  for (int i = 0; i < 3; i++) gRect(toeX[i], 298, 2, 14, C_TOE);
}
static void drawPaws() {
  static const int TL[3] = {24, 35, 47};
  static const int TR[3] = {187, 198, 210};
  drawPaw(37, 303, TL);
  drawPaw(198, 304, TR);
}

static void drawZ(int x, int y, int s, uint16_t c) {
  gHLine(x, y, s + 1, c);         gHLine(x, y + 1, s + 1, c);
  gLine(x + s, y, x, y + s, c);   gLine(x + s - 1, y, x - 1, y + s, c);
  gHLine(x, y + s, s + 1, c);     gHLine(x, y + s - 1, s + 1, c);
}
static void drawZs(uint32_t now) {
  static const int16_t ZX[3] = {227, 221, 217};
  static const int16_t ZS[3] = {9, 7, 6};
  static const int16_t ZY[3] = {212, 224, 235};
  for (int i = 0; i < 3; i++) {
    uint32_t life = (now + (uint32_t)i * 1000) % 3000;
    uint8_t b = life < 700 ? (life * 255) / 700 : (life > 2300 ? ((3000 - life) * 255) / 700 : 255);
    drawZ(ZX[i], ZY[i] - (int)(life / 300), ZS[i], scaleRGB(205, 175, 255, b));
  }
}

static void drawScene(int by, uint32_t now) {
  drawEar(EAR_L, by);
  drawEar(EAR_R, by);
  drawHead(by);
  drawTufts(by);
  drawFace(by, now);
  drawPaws();
  drawZs(now);
  for (const StarDef& s : LOW_STARS) drawStar(s.x, s.y, s.h, starColor(now, s.phase));
}

// Breathing offset 0..3 px, 4 s period, 32-entry cosine table
static const uint8_t BREATH[32] = {0,0,0,0,0,1,1,1,2,2,2,2,3,3,3,3,3,3,3,3,3,2,2,2,2,1,1,1,0,0,0,0};

static void renderCatFrame(uint32_t now) {
  if (!canvas) return;
  int by = BREATH[(now % 4000) / 125];
  G = canvas; Y0 = CANVAS_Y;
  canvas->fillScreen(C_BG);
  drawScene(by, now);
  G = &tft; Y0 = 0;
  tft.drawRGBBitmap(0, CANVAS_Y, canvas->getBuffer(), CANVAS_W, CANVAS_H);
}

// ------------------------------ clock logic --------------------------------
static void updateClock() {
  uint8_t codes[4];
  char date[24];
  time_t t = time(nullptr);
  if (t > MIN_VALID_EPOCH) {
    struct tm ti;
    localtime_r(&t, &ti);
    int h = ti.tm_hour;
    bool blank0 = false;
    if (!TIME_24H) { h %= 12; if (h == 0) h = 12; blank0 = (!LEADING_ZERO_12H && h < 10); }
    codes[0] = blank0 ? CODE_BLANK : (uint8_t)(h / 10);
    codes[1] = h % 10;
    codes[2] = ti.tm_min / 10;
    codes[3] = ti.tm_min % 10;
    strftime(date, sizeof(date), "%a %b ", &ti);
    size_t n = strlen(date);
    snprintf(date + n, sizeof(date) - n, "%d", ti.tm_mday);
  } else {                                           // not synchronised yet
    codes[0] = codes[1] = codes[2] = codes[3] = CODE_DASH;
    strcpy(date, WiFi.status() == WL_CONNECTED ? "Syncing..." : "No Wi-Fi");
  }
  if (strcmp(date, shownDate) != 0) { strcpy(shownDate, date); drawDatePill(date); }
  for (int i = 0; i < 4; i++) {
    if (codes[i] != shown[i]) { shown[i] = codes[i]; drawDigitCell(i, codes[i]); }
  }
}

// ------------------------------ Wi-Fi / NTP --------------------------------
static bool ntpStarted = false;
static uint32_t lastWifiTry = 0;

static void wifiService(uint32_t now) {
  if (WiFi.status() == WL_CONNECTED) {
    if (!ntpStarted) { configTzTime(TZ_INFO, NTP_1, NTP_2, NTP_3); ntpStarted = true; }
    return;
  }
  if (now - lastWifiTry >= WIFI_RETRY_MS) {          // non-blocking retry; display untouched
    lastWifiTry = now;
    WiFi.disconnect();
    WiFi.begin(WIFI_SSID, WIFI_PASS);
  }
}

// ------------------------------- setup/loop --------------------------------
void setup() {
  Serial.begin(115200);
  if (PIN_BL >= 0) { pinMode(PIN_BL, OUTPUT); digitalWrite(PIN_BL, HIGH); }

  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI, PIN_CS);
  tft.init(TFT_W, TFT_H, TFT_SPI_MODE);
  tft.setSPISpeed(SPI_HZ);
  tft.invertDisplay(TFT_INVERT);
  tft.setRotation(TFT_ROTATION);
  tft.fillScreen(C_BG);

  canvas = new GFXcanvas16(CANVAS_W, CANVAS_H);
  if (!canvas->getBuffer()) { delete canvas; canvas = nullptr; }
  Serial.printf("Canvas: %s, free heap: %u\n", canvas ? "OK" : "FAILED (static cat)", ESP.getFreeHeap());

  drawTopStars(millis());
  updateClock();                                     // placeholder "--:--" until NTP
  drawColon();
  if (canvas) renderCatFrame(millis());
  else { G = &tft; Y0 = 0; drawScene(0, 0); }

  WiFi.mode(WIFI_STA);
  WiFi.persistent(false);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  lastWifiTry = millis();
}

void loop() {
  static uint32_t lastClock = 0, lastFrame = 0;
  static uint8_t frameNo = 0;
  uint32_t now = millis();

  wifiService(now);

  if (now - lastClock >= 250) { lastClock = now; updateClock(); }

  if (now - lastFrame >= 100) {                      // ~10 fps, millis()-timed, no delay()
    lastFrame = now;
    renderCatFrame(now);
    if ((++frameNo % 3) == 0) drawTopStars(now);
  }
}
