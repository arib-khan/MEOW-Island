// Screen C: the Dancing Cat music widget adapted to the 240x320 TFT: header pill, the cat on its tile (animated while music plays),
// title and artist (long titles scroll), progress bar with elapsed / total time, play-pause badge and equaliser bars.
// No playback buttons: the TFT has no touch input, so the screen only reflects what the phone is playing.
#pragma once
#include "gfx_common.h"
#include "protocol.h"
#include "cat_assets.h"

struct MusicView {
  meow::MusicData d;
  uint32_t rxAt = 0;          // millis() when the phone last reported the position
  bool haveData = false;      // phone has sent something since boot
  bool linked = false;        // phone connected right now
};

constexpr int MUS_Y = 150;    // top of the animated band (canvas rows 150..273)
constexpr int MUS_TILE_X = 70, MUS_TILE_Y = 42, MUS_TILE = 100;

struct MusicUiState { int pill = -1; int frame = -1; int badge = -1; uint32_t marqueeStart = 0; char title[64] = ""; };
static MusicUiState musUi;

static int musicPillId(const MusicView& v) {
  if (!v.linked) return 3;
  if (!v.haveData || !v.d.session) return 2;
  return v.d.playing ? 0 : 1;
}
static void musicDrawPill(const MusicView& v) {
  static const char* const T[4] = {"NOW PLAYING", "PAUSED", "MUSIC", "PHONE OFFLINE"};
  int id = musicPillId(v);
  drawPill(T[id], id == 3 ? C_WARN : C_DATE);
}
static bool musicPlaying(const MusicView& v) { return v.linked && v.haveData && v.d.session && v.d.playing; }

static void fmtTime(char* out, size_t cap, uint32_t s) {
  if (s >= 3600) snprintf(out, cap, "%u:%02u:%02u", (unsigned)(s / 3600), (unsigned)((s / 60) % 60), (unsigned)(s % 60));
  else snprintf(out, cap, "%u:%02u", (unsigned)(s / 60), (unsigned)(s % 60));
}
static uint32_t musicPosSec(const MusicView& v, uint32_t now) {
  uint32_t p = v.d.posSec;
  if (musicPlaying(v)) p += (now - v.rxAt) / 1000;
  if (v.d.durSec > 0 && p > v.d.durSec) p = v.d.durSec;
  return p;
}

static void drawCatTile(int frame) {   // frame -1 = idle (paused / nothing playing)
  gRRect(MUS_TILE_X, MUS_TILE_Y, MUS_TILE, MUS_TILE, 24, C_TILE);
  gRRectLine(MUS_TILE_X, MUS_TILE_Y, MUS_TILE, MUS_TILE, 24, C_TILE_LINE);
  blit(MUS_TILE_X + 6, MUS_TILE_Y + 6, frame < 0 ? CAT_DANCE_IDLE : CAT_DANCE[frame % CAT_DANCE_FRAMES], CAT_DANCE_W, CAT_DANCE_H);
}

static void drawBadge(bool playing) {           // violet-to-pink disc with a play or pause glyph
  const int cx = 120, cy = 298, r = 15;
  for (int dy = -r; dy <= r; dy++) {
    int w = isqrt32((uint32_t)(r * r - dy * dy));
    int t = (dy + r) * 255 / (2 * r);
    gHLine(cx - w, cy + dy, 2 * w + 1, RGB(lerp8(124, 224, t, 255), lerp8(77, 102, t, 255), lerp8(255, 234, t, 255)));
  }
  if (playing) { gRect(cx - 7, cy - 8, 5, 16, C_TEXT); gRect(cx + 3, cy - 8, 5, 16, C_TEXT); }
  else gTri(cx - 5, cy - 9, cx - 5, cy + 9, cx + 9, cy, C_TEXT);
}

// Five bars on each side of the badge, heights follow independent triangle waves while playing.
static void drawEq(uint32_t now, bool playing) {
  static const uint16_t PERIOD[5] = {620, 480, 760, 540, 690};
  for (int side = 0; side < 2; side++) {
    for (int i = 0; i < 5; i++) {
      int x = side == 0 ? 148 + i * 10 : 86 - i * 10;   // mirrored around the badge
      int h = playing ? 4 + (tri(now + i * 137, PERIOD[i]) * 26) / 255 : 4;
      gRect(x, 284, 6, 34, C_BG);
      int t = h * 255 / 30;
      gRRect(x, 316 - h, 6, h, 3, RGB(lerp8(124, 224, t, 255), lerp8(77, 102, t, 255), lerp8(255, 234, t, 255)));
    }
  }
}

static void drawMusicBand(const MusicView& v, uint32_t now) {
  const meow::MusicData& d = v.d;
  const bool show = v.haveData && d.session;
  const char* title = show && d.title[0] ? d.title : (show ? "Playing" : (v.linked ? "Nothing playing" : "Phone not connected"));
  const char* artist = show ? d.artist : "";
  const int ty = MUS_Y + 22;

  // title: centred when it fits, otherwise a slow marquee
  int w = textWidth(title, &FreeSansBold12pt7b);
  if (w <= 224) gText(120, ty, title, &FreeSansBold12pt7b, show ? C_TEXT : C_DIM, 1);
  else {
    const int gap = 48, period = w + gap;
    uint32_t t = now - musUi.marqueeStart;
    int off = t < 1500 ? 0 : (int)(((t - 1500) * 28UL / 1000) % period);
    gText(8 - off, ty, title, &FreeSansBold12pt7b, C_TEXT, 0);
    gText(8 - off + period, ty, title, &FreeSansBold12pt7b, C_TEXT, 0);
    gRect(0, MUS_Y, 8, 30, C_BG); gRect(232, MUS_Y, 8, 30, C_BG);       // clip the marquee to the text margins
  }
  char fit[64];
  if (artist[0]) { fitText(fit, sizeof fit, artist, &FreeSans9pt7b, 224); gText(120, MUS_Y + 46, fit, &FreeSans9pt7b, C_ARTIST, 1); }

  // progress bar (212 px wide, thumb never leaves it)
  const int bx = 14, bw = 212, cy = MUS_Y + 72, r = 7;
  const bool hasLen = show && d.durSec > 0;
  uint32_t pos = hasLen ? musicPosSec(v, now) : 0;
  int fx = hasLen ? bx + r + (bw - 2 * r) * (int)pos / (int)d.durSec : bx + r;
  gRRect(bx + r, cy - 3, bw - 2 * r, 6, 3, C_TRACK);
  if (fx > bx + r + 1) for (int x = bx + r; x < fx; x++) {
    int t = (x - bx) * 255 / bw;
    gRect(x, cy - 3, 1, 6, RGB(lerp8(124, 208, t, 255), lerp8(77, 112, t, 255), lerp8(255, 242, t, 255)));
  }
  gCircle(fx, cy, r, C_RING); gCircle(fx, cy, r - 2, C_TEXT);
  char a[12], b[12];
  fmtTime(a, sizeof a, pos); if (hasLen) fmtTime(b, sizeof b, d.durSec); else strcpy(b, "--:--");
  if (!show) strcpy(a, "0:00");
  gText(bx, MUS_Y + 100, a, &FreeSans9pt7b, C_TIME, 0);
  gText(bx + bw, MUS_Y + 100, b, &FreeSans9pt7b, C_TIME, 2);
}

// Draws everything from scratch (screen switched to, or the track changed).
static void musicEnter(const MusicView& v, uint32_t now) {
  G = &tft; Y0 = 0;
  tft.fillScreen(C_BG);
  musUi.pill = musicPillId(v); musicDrawPill(v);
  musUi.frame = -2; musUi.badge = -1;
  musUi.marqueeStart = now; strncpy(musUi.title, v.d.title, sizeof musUi.title - 1);
  musUi.title[sizeof musUi.title - 1] = 0;
}

// Called every loop: redraws only what changed. Cat frames ~6 fps, band + equaliser ~10 fps.
static void musicTick(const MusicView& v, uint32_t now) {
  static uint32_t lastBand = 0;
  const bool playing = musicPlaying(v);
  G = &tft; Y0 = 0;
  int pill = musicPillId(v);
  if (pill != musUi.pill) { musUi.pill = pill; musicDrawPill(v); }
  if (strcmp(musUi.title, v.d.title) != 0) { strncpy(musUi.title, v.d.title, sizeof musUi.title - 1); musUi.title[sizeof musUi.title - 1] = 0; musUi.marqueeStart = now; }
  int frame = playing ? (int)((now / 170) % CAT_DANCE_FRAMES) : -1;
  if (frame != musUi.frame) { musUi.frame = frame; G = &tft; Y0 = 0; drawCatTile(frame); }
  if (now - lastBand >= 100) {
    lastBand = now;
    if (canvas) {
      G = canvas; Y0 = MUS_Y; canvas->fillScreen(C_BG);
      drawMusicBand(v, now);
      G = &tft; Y0 = 0; pushCanvas(MUS_Y);
    } else { G = &tft; Y0 = 0; tft.fillRect(0, MUS_Y, 240, 124, C_BG); drawMusicBand(v, now); }
    G = &tft; Y0 = 0;
    int badge = playing ? 1 : 0;
    if (badge != musUi.badge) { musUi.badge = badge; drawBadge(playing); }
    drawEq(now, playing);
  }
}
