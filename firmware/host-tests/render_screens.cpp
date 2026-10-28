// Renders every new screen state to images so the layout can be checked without the hardware.
#include "common.h"
#include "../MeowIsland/clock_screen.h"
#include "../MeowIsland/steps_screen.h"
#include "../MeowIsland/music_screen.h"
#include "../MeowIsland/overlay_screen.h"
static const char* OUT;
static void snap(const char* name) { char p[256]; snprintf(p, sizeof p, "%s/%s.ppm", OUT, name); dumpPpm(p, tft); }
int main(int, char** argv) {
  OUT = argv[1];
  setenv("TZ", "IST-5:30", 1); tzset();
  canvas = new GFXcanvas16(CANVAS_W, CANVAS_H);
  // --- clock
  host_epoch = 1800000000L; host_millis = 5000; clockEnter(true); for (uint32_t t = 5000; t < 5400; t += 50) clockTick(t, true); snap("clock");
  // --- steps
  StepsView sv; sv.haveData = true; sv.linked = true;
  sv.d.steps = 6200; sv.d.goal = 8000; sv.d.hasSteps = sv.d.goalOn = sv.d.heatOn = true; sv.d.happy = false; sv.d.subtitle = 1;
  for (int i = 0; i < meow::STEP_CELLS; i++) sv.d.levels[i] = (i * 7 + 1) % 5; sv.d.levels[53] = 3;
  tft.fillScreen(C_BG); stepsRender(sv); snap("steps_progress");
  sv.d.steps = 12345; sv.d.goal = 10000; sv.d.happy = true; sv.d.subtitle = 2; tft.fillScreen(C_BG); stepsRender(sv); snap("steps_goal");
  sv.d.steps = 123456; sv.d.goal = 100000; tft.fillScreen(C_BG); stepsRender(sv); snap("steps_big");
  sv.d.steps = 4000; sv.d.goal = 8000; sv.d.heatOn = false; sv.d.goalOn = false; sv.d.hint = 0; tft.fillScreen(C_BG); stepsRender(sv); snap("steps_noheat_nogoal");
  sv.d.hasSteps = false; sv.d.hint = 2; tft.fillScreen(C_BG); stepsRender(sv); snap("steps_nopermission");
  StepsView sn; tft.fillScreen(C_BG); stepsRender(sn); snap("steps_nodata");
  sv.d.hasSteps = true; sv.d.goalOn = true; sv.d.heatOn = true; sv.linked = false; sv.d.steps = 6200; sv.d.goal = 8000; tft.fillScreen(C_BG); stepsRender(sv); snap("steps_offline");
  // --- music
  MusicView mv; mv.haveData = true; mv.linked = true; mv.d.session = true; mv.d.playing = true; mv.d.posSec = 102; mv.d.durSec = 318; mv.rxAt = 10000;
  strcpy(mv.d.title, "Kabhi Kabhi Aditi"); strcpy(mv.d.artist, "Rashid Ali, Warbartan - Topic");
  host_millis = 10000; musicEnter(mv, 10000); for (uint32_t t = 10000; t <= 10400; t += 50) musicTick(mv, t); snap("music_playing");
  mv.d.playing = false; musicEnter(mv, 20000); for (uint32_t t = 20000; t <= 20300; t += 50) musicTick(mv, t); snap("music_paused");
  mv.d.playing = true; strcpy(mv.d.title, "A Very Long Song Title That Needs To Scroll"); mv.d.posSec = 3700; mv.d.durSec = 7300; mv.rxAt = 30000;
  musicEnter(mv, 30000); for (uint32_t t = 30000; t <= 30400; t += 50) musicTick(mv, t); snap("music_long_start");
  for (uint32_t t = 30400; t <= 33000; t += 50) musicTick(mv, t); snap("music_long_scrolled");
  MusicView none; none.linked = true; none.haveData = true; musicEnter(none, 40000); for (uint32_t t = 40000; t <= 40300; t += 50) musicTick(none, t); snap("music_none");
  MusicView off = mv; off.linked = false; musicEnter(off, 50000); for (uint32_t t = 50000; t <= 50300; t += 50) musicTick(off, t); snap("music_offline");
  // --- overlays
  overlayPairingCode(482913); snap("overlay_code"); overlayPairingMode(118); snap("overlay_mode"); overlayReset(); snap("overlay_reset");
  printf("rendered screens\n");
  return 0;
}
