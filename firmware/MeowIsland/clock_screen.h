// Screen A: the neon sleeping-cat clock. This is the original clock code, unchanged apart from three things: the shared helpers now live in
// gfx_common.h, the date pill uses the shared drawPill() (identical pixels), and the Wi-Fi state is passed in instead of read from WiFi.
#pragma once
#include "gfx_common.h"

constexpr int CANVAS_Y = 196;                                       // the cat scene (y = 196..319) is composed in the canvas
constexpr int DY = 46, DW = 48, DH = 142, DR = 7;                    // digit cell geometry
constexpr int DIGIT_X[4] = {9, 61, 131, 183};
constexpr uint8_t CODE_DASH = 10, CODE_BLANK = 11;
constexpr time_t  MIN_VALID_EPOCH = 1700000000;                      // Nov 2023: "clock is set"

// Digit gradient: light lavender (top) -> violet (middle) -> hot pink (bottom)
static uint16_t gradColor(int y) {
  int t = (y - DY) * 255 / DH;
  if (t < 0) t = 0; if (t > 255) t = 255;
  if (t < 150) return RGB(lerp8(206, 184, t, 150), lerp8(172, 128, t, 150), 255);
  t -= 150;
  return RGB(lerp8(184, 255, t, 105), lerp8(128, 100, t, 105), lerp8(255, 250, t, 105));
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
static void updateClock(bool wifiUp) {
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
    strcpy(date, wifiUp ? "Syncing..." : "No Wi-Fi");
  }
  if (strcmp(date, shownDate) != 0) { strcpy(shownDate, date); drawPill(date, C_DATE); }
  for (int i = 0; i < 4; i++) {
    if (codes[i] != shown[i]) { shown[i] = codes[i]; drawDigitCell(i, codes[i]); }
  }
}


// Draws the whole clock screen from scratch (used at start-up and whenever the screen is switched back to the clock).
static void clockEnter(bool wifiUp) {
  for (int i = 0; i < 4; i++) shown[i] = 255;
  shownDate[0] = 0;
  G = &tft; Y0 = 0;
  tft.fillScreen(C_BG);
  drawTopStars(millis());
  updateClock(wifiUp);                                // placeholder "--:--" until the time is known
  drawColon();
  if (canvas) renderCatFrame(millis());
  else { G = &tft; Y0 = 0; drawScene(0, 0); }
}

// The clock's part of loop(): digits every 250 ms, cat / stars at ~10 fps, all millis()-timed, no delay().
static void clockTick(uint32_t now, bool wifiUp) {
  static uint32_t lastClock = 0, lastFrame = 0;
  static uint8_t frameNo = 0;
  if (now - lastClock >= 250) { lastClock = now; updateClock(wifiUp); }
  if (now - lastFrame >= 100) {
    lastFrame = now;
    renderCatFrame(now);
    if ((++frameNo % 3) == 0) drawTopStars(now);
  }
}
