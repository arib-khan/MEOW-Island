// Runs the REFACTORED clock (firmware/MeowIsland/clock_screen.h) through the same scenario as regress_original.cpp.
#include "common.h"
#include "../MeowIsland/clock_screen.h"
int main(int, char** argv) {
  const char* out = argv[1];
  setenv("TZ", "IST-5:30", 1); tzset();
  struct { uint32_t ms; time_t epoch; } cp[] = {{1000, 0}, {5000, 0}, {9000, 1800000000L}, {9300, 1800000000L}, {14100, 1800000000L}, {20050, 1800000060L}, {41234, 1800000060L}};
  host_millis = 1000; host_epoch = 0;
  tft.setRotation(TFT_ROTATION); tft.fillScreen(C_BG);
  canvas = new GFXcanvas16(CANVAS_W, CANVAS_H);
  clockEnter(false);                                  // the original setup(): placeholder digits, colon and the first cat frame
  uint32_t t = 1000; size_t ci = 0;
  while (ci < sizeof cp / sizeof cp[0]) {
    host_millis = t; host_epoch = cp[ci].epoch ? cp[ci].epoch : host_epoch;
    if (cp[ci].epoch == 0) host_epoch = 0;
    clockTick(t, false);
    if (t >= cp[ci].ms) { char p[256]; snprintf(p, sizeof p, "%s/new_%zu.raw", out, ci); dumpRaw(p, tft); ci++; }
    t += 50;
  }
  printf("refactored clock: %zu frames dumped\n", ci);
  return 0;
}
