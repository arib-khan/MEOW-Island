// Screen D: PHONE screen time today (from Android UsageStats, sent by the phone). This is not how long this display has been on.
// Ring = progress toward the daily goal, total in the middle, then the most-used apps with proportional bars.
#pragma once
#include "gfx_common.h"
#include "protocol.h"
#include "steps_screen.h"      // drawRing()

struct ScreenTimeView {
  meow::ScreenTimeData d;
  bool haveData = false;      // the phone has sent screen time since boot
  bool linked = false;        // phone connected right now
  uint32_t rxAt = 0;
};

// 222 -> "3h 42m", 42 -> "42m", 120 -> "2h 00m"
static void fmtDur(char* out, size_t cap, uint32_t min) {
  if (min >= 60) snprintf(out, cap, "%luh %02lum", (unsigned long)(min / 60), (unsigned long)(min % 60));
  else snprintf(out, cap, "%lum", (unsigned long)min);
}

static void stNote(const char* l1, const char* l2, const char* l3) {
  gText(120, 150, l1, textWidth(l1, &FreeSansBold12pt7b) <= 228 ? &FreeSansBold12pt7b : &FreeSansBold9pt7b, C_TEXT, 1);   // long headlines step down a size instead of running off the screen
  if (l2 && l2[0]) gText(120, 186, l2, &FreeSans9pt7b, C_TIME, 1);
  if (l3 && l3[0]) gText(120, 208, l3, &FreeSans9pt7b, C_TIME, 1);
}

static void drawScreenTimeScene(const ScreenTimeView& v) {
  const meow::ScreenTimeData& d = v.d;
  gPill(v.linked ? "SCREEN TIME" : "PHONE OFFLINE", v.linked ? C_DATE : C_WARN, 6);
  drawStar(26, 66, 8, C_LAV); drawStar(214, 70, 6, C_LAV);

  if (!v.haveData) { stNote(v.linked ? "Waiting for phone" : "Phone not connected", v.linked ? "Screen time arrives in a moment" : "Showing nothing until it", v.linked ? "" : "reconnects"); return; }
  if (d.needsPermission) { stNote("Allow Usage Access", "Meow Island > Screen Time", "> Grant Usage Access"); return; }
  if (d.unavailable || !d.hasData) { stNote("Not available", "Android gave no usage data", "Try again in a minute"); return; }

  char t[20], g[20], line[40];
  const bool goal = d.goalOn && d.goalMin > 0;
  const int pct = goal ? (int)((uint32_t)d.totalMin * 100 / d.goalMin) : 0;
  const bool over = goal && d.totalMin > d.goalMin;
  const int cx = 120, cy = 118, R = 54;
  fmtDur(t, sizeof t, d.totalMin);
  if (goal) {
    drawRing(cx, cy, R, 6, pct > 100 ? 100 : pct);
    gText(cx, cy + 6, t, &FreeSansBold12pt7b, over ? C_WARN : C_TEXT, 1);
    gSmall(cx, cy + 22, "TODAY", C_DIM, 1);
    fmtDur(g, sizeof g, d.goalMin);
    snprintf(line, sizeof line, over ? "Over goal %s  (%d%%)" : "Goal %s  -  %d%%", g, pct);
    gText(120, 196, line, &FreeSans9pt7b, over ? C_WARN : C_ARTIST, 1);
  } else {
    gText(cx, cy + 12, t, &FreeSansBold18pt7b, C_TEXT, 1);
    gSmall(cx, cy + 30, "PHONE SCREEN TIME TODAY", C_DIM, 1);
    gText(120, 196, "No daily goal set", &FreeSans9pt7b, C_MUTE, 1);
  }

  // most used apps
  gRRect(10, 206, 220, 108, 14, C_CARD); gRRectLine(10, 206, 220, 108, 14, C_CARD_LINE);
  if (d.n == 0) { gText(120, 266, "No app usage yet today", &FreeSans9pt7b, C_DIM, 1); return; }
  uint16_t mx = 1; for (int i = 0; i < d.n; i++) if (d.appMin[i] > mx) mx = d.appMin[i];
  for (int i = 0; i < d.n; i++) {
    const int ry = 212 + i * 26;
    char nm[24], tm[16]; fitText(nm, sizeof nm, d.appName[i][0] ? d.appName[i] : "App", &FreeSans9pt7b, 128);
    fmtDur(tm, sizeof tm, d.appMin[i]);
    gText(20, ry + 14, nm, &FreeSans9pt7b, C_TEXT, 0);
    gText(220, ry + 14, tm, &FreeSans9pt7b, C_TIME, 2);
    gRRect(20, ry + 18, 200, 4, 2, C_TRACK);
    int bw = (int)((uint32_t)200 * d.appMin[i] / mx); if (bw < 4) bw = 4;
    gRRect(20, ry + 18, bw, 4, 2, i == 0 ? C_ORCHID : C_VIOLET);
  }
}
static void screenTimeRender(const ScreenTimeView& v) { renderBanded(C_BG, [&]() { drawScreenTimeScene(v); }); }
