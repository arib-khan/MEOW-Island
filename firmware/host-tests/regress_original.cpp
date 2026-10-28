// Runs the ORIGINAL clock sketch (original/MeowIsland_ReferenceColors.ino) on the desktop and dumps the screen at fixed moments.
#include "common.h"
#include "original/MeowIsland_ReferenceColors.ino"
int main(int, char** argv) {
  const char* out = argv[1];
  setenv("TZ", "IST-5:30", 1); tzset();
  struct { uint32_t ms; time_t epoch; } cp[] = {{1000, 0}, {5000, 0}, {9000, 1800000000L}, {9300, 1800000000L}, {14100, 1800000000L}, {20050, 1800000060L}, {41234, 1800000060L}};
  host_millis = 1000; host_epoch = 0;
  setup();
  uint32_t t = 1000; size_t ci = 0;
  while (ci < sizeof cp / sizeof cp[0]) {
    host_millis = t; host_epoch = cp[ci].epoch ? cp[ci].epoch : host_epoch;
    if (cp[ci].epoch == 0) host_epoch = 0;
    loop();
    if (t >= cp[ci].ms) { char p[256]; snprintf(p, sizeof p, "%s/orig_%zu.raw", out, ci); dumpRaw(p, tft); ci++; }
    t += 50;
  }
  printf("original clock: %zu frames dumped\n", ci);
  return 0;
}
