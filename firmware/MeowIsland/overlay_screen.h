// Full-screen messages: the pairing passkey, "pairing mode" confirmation and the reset notice. Drawn banded (flicker-free) like the other screens.
#pragma once
#include "gfx_common.h"
#include <Fonts/FreeSansBold24pt7b.h>

static void drawPairingCodeScene(uint32_t code) {
  gPill("PAIR WITH PHONE", C_DATE, 18);
  char b[12]; snprintf(b, sizeof b, "%03lu %03lu", (unsigned long)(code / 1000), (unsigned long)(code % 1000));
  gRRect(14, 84, 212, 76, 22, C_PANEL); gRRectLine(14, 84, 212, 76, 22, C_PANEL_LINE);
  gText(120, 136, b, &FreeSansBold24pt7b, C_TEXT, 1);
  gText(120, 196, "Type this code on your", &FreeSans9pt7b, C_TIME, 1);
  gText(120, 218, "phone when Android asks", &FreeSans9pt7b, C_TIME, 1);
  gText(120, 262, "Only enter it if you", &FreeSans9pt7b, C_DIM, 1);
  gText(120, 282, "started this pairing", &FreeSans9pt7b, C_DIM, 1);
  drawStar(28, 72, 9, C_LAV); drawStar(212, 76, 7, C_LAV);
}
static void drawPairingModeScene(uint32_t secondsLeft) {
  gPill("PAIRING MODE", C_DATE, 18);
  gText(120, 110, "Open Meow Island", &FreeSansBold12pt7b, C_TEXT, 1);
  gText(120, 138, "on your phone", &FreeSansBold12pt7b, C_TEXT, 1);
  gText(120, 184, "Settings > Meow Island", &FreeSans9pt7b, C_TIME, 1);
  gText(120, 206, "Device > Add Device", &FreeSans9pt7b, C_TIME, 1);
  char b[24]; snprintf(b, sizeof b, "Open for %lu s", (unsigned long)secondsLeft);
  gText(120, 262, b, &FreeSans9pt7b, C_DIM, 1);
  drawStar(28, 72, 9, C_LAV); drawStar(212, 76, 7, C_LAV);
}
static void drawResetScene() {
  gPill("FORGOT EVERYTHING", C_WARN, 18);
  gText(120, 120, "Phone pairing and", &FreeSansBold12pt7b, C_TEXT, 1);
  gText(120, 148, "Wi-Fi were erased", &FreeSansBold12pt7b, C_TEXT, 1);
  gText(120, 200, "Restarting...", &FreeSans9pt7b, C_TIME, 1);
}
static void overlayPairingCode(uint32_t code) { renderBanded(C_BG, [&]() { drawPairingCodeScene(code); }); }
static void overlayPairingMode(uint32_t s) { renderBanded(C_BG, [&]() { drawPairingModeScene(s); }); }
static void overlayReset() { renderBanded(C_BG, [&]() { drawResetScene(); }); }
