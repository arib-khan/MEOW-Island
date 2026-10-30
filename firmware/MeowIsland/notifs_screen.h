// Screen E: recent notifications from the phone. Dark cards with a violet accent bar: app name + age, title, one-line preview.
// The list lives in RAM only (nothing is written to flash) and is replaced as a whole by every update from the phone.
#pragma once
#include "gfx_common.h"
#include "protocol.h"

struct NotifsView {
  meow::NotifData d;
  bool haveData = false;
  bool linked = false;
  uint32_t rxAt = 0;          // when the list arrived: item ages are relative to this moment
};

// minutes -> "now", "5m", "3h", "2d"
static void fmtAge(char* out, size_t cap, uint32_t min) {
  if (min < 1) snprintf(out, cap, "now");
  else if (min < 60) snprintf(out, cap, "%lum", (unsigned long)min);
  else if (min < 1440) snprintf(out, cap, "%luh", (unsigned long)(min / 60));
  else snprintf(out, cap, "%lud", (unsigned long)(min / 1440));
}

static void ntNote(const char* l1, const char* l2, const char* l3) {
  gText(120, 150, l1, textWidth(l1, &FreeSansBold12pt7b) <= 228 ? &FreeSansBold12pt7b : &FreeSansBold9pt7b, C_TEXT, 1);   // long headlines step down a size instead of running off the screen
  if (l2 && l2[0]) gText(120, 186, l2, &FreeSans9pt7b, C_TIME, 1);
  if (l3 && l3[0]) gText(120, 208, l3, &FreeSans9pt7b, C_TIME, 1);
}

static void drawNotifsScene(const NotifsView& v, uint32_t now) {
  const meow::NotifData& d = v.d;
  gPill(v.linked ? "NOTIFICATIONS" : "PHONE OFFLINE", v.linked ? C_DATE : C_WARN, 6);
  if (!v.haveData) { ntNote(v.linked ? "Waiting for phone" : "Phone not connected", v.linked ? "" : "Showing nothing until it", v.linked ? "" : "reconnects"); return; }
  if (d.needsAccess) { ntNote("Allow notification access", "Meow Island > Device >", "Notifications on display"); return; }
  if (!d.enabled) { ntNote("Notifications are off", "Turn them on in Meow Island", "> Device > Notifications"); return; }
  if (d.n == 0) { ntNote("All caught up", "No new notifications", ""); drawStar(120, 96, 12, C_LAV); return; }

  const uint32_t extra = (now - v.rxAt) / 60000UL;
  const int rows = d.n > 4 ? 4 : d.n;
  for (int i = 0; i < rows; i++) {
    const meow::NotifItem& it = d.items[i];
    const int y = 48 + i * 64;
    gRRect(8, y, 224, 60, 12, C_CARD); gRRectLine(8, y, 224, 60, 12, C_CARD_LINE);
    gRRect(8, y + 8, 4, 44, 2, i == 0 ? C_ORCHID : C_VIOLET);                       // accent bar; the newest one is brighter
    char app[20], age[10], title[32], text[64];
    fitText(app, sizeof app, it.app[0] ? it.app : "App", &FreeSans9pt7b, 120);
    fmtAge(age, sizeof age, it.ageMin + extra);
    const bool hasTitle = it.title[0] != 0, hasText = it.text[0] != 0;
    const char* tl = hasTitle ? it.title : (hasText ? it.text : "New notification");    // missing pieces never leave a blank card
    const char* tx = hasTitle ? it.text : "";
    fitText(title, sizeof title, tl, &FreeSansBold9pt7b, 196);
    fitText(text, sizeof text, tx, &FreeSans9pt7b, 196);
    gSmall(20, y + 7, app[0] ? app : "App", C_ORCHID);
    gSmall(222, y + 7, age, C_DIM, 2);
    gText(20, y + 32, title, &FreeSansBold9pt7b, C_TEXT, 0);
    if (text[0]) gText(20, y + 52, text, &FreeSans9pt7b, C_ARTIST, 0);
  }
  if (d.n > rows) { char b[24]; snprintf(b, sizeof b, "+%d more on the phone", d.n - rows); gSmall(120, 306, b, C_MUTE, 1); }
}
static void notifsRender(const NotifsView& v, uint32_t now) { renderBanded(C_BG, [&]() { drawNotifsScene(v, now); }); }
