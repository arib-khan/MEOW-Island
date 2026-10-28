// Screen B: the "Step goals" card of the phone app, adapted to the 240x320 TFT: title + cat, 9x6 contribution heat-map, progress ring
// with the percentage, today's steps, the goal and a progress bar. Same data (StepCardData), same colours, same wording as the widget.
#pragma once
#include "gfx_common.h"
#include "protocol.h"
#include "cat_assets.h"

struct StepsView {
  meow::StepsData d;
  bool haveData = false;      // the phone has sent at least one update since boot
  bool linked = false;        // the phone is connected right now
};

static uint16_t heatColor(uint8_t lv) {
  switch (lv) { case 4: return RGB(201, 141, 251); case 3: return RGB(143, 88, 232); case 2: return RGB(74, 54, 133); case 1: return RGB(44, 38, 56); default: return RGB(39, 34, 47); }
}

// 12345 -> "12,345"
static void fmtThousands(char* out, size_t cap, uint32_t v) {
  char raw[16]; snprintf(raw, sizeof raw, "%lu", (unsigned long)v);
  size_t n = strlen(raw), o = 0;
  for (size_t i = 0; i < n && o + 2 < cap; i++) { if (i && (n - i) % 3 == 0) out[o++] = ','; out[o++] = raw[i]; }
  out[o] = 0;
}
static int stepsPercent(uint32_t steps, uint32_t goal) { return goal == 0 ? 0 : (int)(steps >= goal ? 100 : (uint64_t)steps * 100 / goal); }

// One ring segment centred on angle `deg` (0 = 12 o'clock, clockwise).
static void ringStamp(int cx, int cy, int R, int deg, int rad, uint16_t c) {
  float a = (float)deg * 0.01745329f;
  gCircle(cx + (int)lroundf(sinf(a) * R), cy - (int)lroundf(cosf(a) * R), rad, c);
}
static void drawRing(int cx, int cy, int R, int rad, int pct) {
  for (int a = 0; a < 360; a += 3) ringStamp(cx, cy, R, a, rad, RGB(42, 36, 56));
  if (pct <= 0) return;
  int sweep = pct * 360 / 100;
  for (int a = 0; a <= sweep; a += 2) {
    int t = a * 255 / 360;
    ringStamp(cx, cy, R, a, rad, RGB(lerp8(109, 226, t, 255), lerp8(60, 125, t, 255), lerp8(242, 245, t, 255)));
  }
  ringStamp(cx, cy, R, sweep, rad, RGB(lerp8(109, 226, sweep * 255 / 360, 255), lerp8(60, 125, sweep * 255 / 360, 255), 244));   // round cap
}

// Splits `s` into at most two lines of `maxChars` (size-1 font) at a space and draws them.
static void drawHint(int x, int y, const char* s, uint16_t col, int maxChars) {
  char l1[40], l2[40]; l2[0] = 0;
  size_t n = strlen(s);
  if ((int)n <= maxChars) { gSmall(x, y, s, col); return; }
  int cut = maxChars;
  while (cut > 0 && s[cut] != ' ') cut--;
  if (cut == 0) cut = maxChars;
  snprintf(l1, sizeof l1, "%.*s", cut, s);
  snprintf(l2, sizeof l2, "%s", s + cut + (s[cut] == ' ' ? 1 : 0));
  gSmall(x, y, l1, col); gSmall(x, y + 11, l2, col);
}

static void drawStepsScene(const StepsView& v) {
  const meow::StepsData& d = v.d;
  const bool has = v.haveData && d.hasSteps;
  const bool goalOn = v.haveData && d.goalOn && has && d.goal > 0;

  // ---- title, subtitle, cat ----
  gText(14, 34, "Step ", &FreeSansBold12pt7b, C_TEXT, 0);
  gText(14 + textWidth("Step", &FreeSansBold12pt7b) + 8, 34, "goals", &FreeSansBold12pt7b, RGB(222, 140, 242), 0);
  static const char* const SUB[3] = {"Every step counts", "Keep moving", "Goal reached!"};
  const char* sub = !v.haveData ? "No phone yet" : (!v.linked ? "Phone offline" : SUB[d.subtitle]);
  gText(14, 58, sub, &FreeSans9pt7b, (v.haveData && !v.linked) ? C_WARN : C_SUB, 0);
  blit(136, 4, (v.haveData && d.happy) ? CAT_STEP_HAPPY : CAT_STEP_ANGRY, CAT_STEP_W, CAT_STEP_H);

  // ---- heat-map panel ----
  gRRect(8, 76, 224, 126, 14, C_PANEL);
  gRRectLine(8, 76, 224, 126, 14, C_PANEL_LINE);
  const bool heat = v.haveData && d.heatOn;
  for (int i = 0; i < meow::STEP_CELLS; i++) {
    int x = 14 + (i % 9) * 24, y = 84 + (i / 9) * 19;
    uint8_t lv = heat ? d.levels[i] : 0;
    if (!heat) { gRRect(x, y, 20, 15, 4, RGB(28, 23, 38)); continue; }
    if (i == meow::STEP_CELLS - 1) {                                    // today: white outline
      gRRect(x, y, 20, 15, 4, lv >= 3 ? heatColor(lv) : RGB(21, 16, 31));
      gRRectLine(x, y, 20, 15, 4, C_TEXT); gRRectLine(x + 1, y + 1, 18, 13, 3, C_TEXT);
      gRect(x + 9, y + 6, 2, 3, C_TEXT);
    } else {
      if (lv >= 3) gRRect(x - 2, y - 2, 24, 19, 6, RGB(48, 28, 92));      // soft glow behind bright cells
      gRRect(x, y, 20, 15, 4, heatColor(lv));
    }
  }
  if (!heat) {
    gSmall(120, 134, v.haveData ? "Turn on Contribution heatmap" : "Waiting for your phone", C_TIME, 1);
    if (v.haveData) gSmall(120, 146, "in Meow Island > Health", C_TIME, 1);
  }

  // ---- bottom card: ring + numbers ----
  gRRect(8, 210, 224, 102, 22, C_CARD);
  gRRectLine(8, 210, 224, 102, 22, C_CARD_LINE);
  const int pc = goalOn ? stepsPercent(d.steps, d.goal) : 0;
  drawRing(52, 261, 28, 4, pc);
  if (goalOn) {
    char b[8]; snprintf(b, sizeof b, "%d%%", pc);
    if (textWidth(b, &FreeSans9pt7b) <= 38) gText(52, 267, b, &FreeSans9pt7b, C_TEXT, 1);   // "100%" does not fit inside the ring: use the small font
    else gSmall(52, 257, b, C_TEXT, 1);
  }
  else drawStar(52, 261, 11, RGB(181, 140, 255));

  const int X = 96;
  gText(X, 234, (has || !v.haveData || d.label == 0) ? "Today's Steps" : "Step counter is off", &FreeSans9pt7b, C_DIM, 0);
  char num[16] = "-", goalTxt[24] = "";
  if (has) { fmtThousands(num, sizeof num, d.steps); }
  const GFXfont* bigFont = &FreeSansBold18pt7b;
  if (textWidth(num, bigFont) > 134) bigFont = &FreeSansBold12pt7b;
  gText(X, 268, num, bigFont, C_TEXT, 0);
  if (goalOn) {
    char g[16]; fmtThousands(g, sizeof g, d.goal); snprintf(goalTxt, sizeof goalTxt, "/ %s", g);
    gText(X, 288, goalTxt, &FreeSans9pt7b, C_MUTE, 0);
    // linear progress
    gRRect(X, 296, 132, 9, 4, RGB(37, 32, 47));
    int fw = 132 * pc / 100; if (pc > 0 && fw < 9) fw = 9;
    for (int i = 0; i < fw; i++) { int t = i * 255 / 132; gRect(X + i, 296, 1, 9, RGB(lerp8(123, 232, t, 255), lerp8(69, 139, t, 255), lerp8(245, 240, t, 255))); }
    if (fw >= 9) { gRRect(X, 296, 4, 9, 2, RGB(123, 69, 245)); }
  } else {
    static const char* const HINT[4] = {"", "Turn it on in Meow Island > Health", "Allow Physical activity in Health", "Waiting for the first step reading"};
    const char* h = !v.haveData ? "Open Meow Island on your phone" : HINT[d.hint];
    drawHint(X, 282, h, C_DIM, 22);
  }
}

static void stepsRender(const StepsView& v) { renderBanded(C_BG, [&]() { drawStepsScene(v); }); }
