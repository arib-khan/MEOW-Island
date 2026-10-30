// Screen C (redesigned): a clean music player for the 240x320 TFT. No cat animation any more.
//   header pill with the media source (SPOTIFY / YOUTUBE / ...) and a play / pause glyph
//   album artwork (80x80, sent by the phone) or a neutral placeholder tile
//   title (long titles scroll), artist, progress bar with elapsed / total time
//   lyric zone: Spotify lines only when the phone got time-synchronised lyrics from a real source; otherwise a plain, honest status line.
//   Other apps (YouTube, Brave, ...): "Captions unavailable" - Android does not give us their captions, and nothing is ever made up.
// No playback buttons: the TFT has no touch input, the screen reflects what the phone plays (controls stay on the phone).
#pragma once
#include "gfx_common.h"
#include "protocol.h"
#include "custom_store.h"      // artBuf / artReady / artRev

struct MusicView {
  meow::MusicData d;
  meow::Music2Data d2;
  uint32_t rxAt = 0;          // millis() when the phone last reported the position
  bool haveData = false;      // phone has sent something since boot
  bool haveData2 = false;     // ...and the extras (source, lyrics)
  bool linked = false;        // phone connected right now
};

constexpr int MUS_ART_X = 80, MUS_ART_Y = 46, MUS_ART = 80;     // album art tile
constexpr int MUS_Y = 130;                                       // top of the animated band: rows 130..253 (band A) and 254..318 (band B)
constexpr int MUS_BANDB_Y = MUS_Y + CANVAS_H, MUS_BANDB_ROWS = 320 - MUS_BANDB_Y;

struct MusicUiState { int pill = -1; int icon = -1; uint32_t artRevShown = 0xFFFFFFFF; int artMode = -1; uint32_t marqueeStart = 0; char title[64] = ""; uint32_t sig = 0; bool bandBDirty = true; };
static MusicUiState musUi;

static bool ieq(const char* a, const char* b) { for (; *a && *b; a++, b++) { char x = *a, y = *b; if (x >= 'a' && x <= 'z') x -= 32; if (y >= 'a' && y <= 'z') y -= 32; if (x != y) return false; } return *a == *b; }
static bool musicPlaying(const MusicView& v) { return v.linked && v.haveData && v.d.session && v.d.playing; }
static bool musicHasSession(const MusicView& v) { return v.linked && v.haveData && v.d.session; }
static bool isSpotify(const MusicView& v) { return v.haveData2 && ieq(v.d2.src, "SPOTIFY"); }

static int musicPillId(const MusicView& v) { return !v.linked ? 2 : (v.haveData2 && v.d2.src[0] && musicHasSession(v)) ? 1 : 0; }
static void musicDrawPill(const MusicView& v) {
  int id = musicPillId(v);
  drawPill(id == 2 ? "PHONE OFFLINE" : id == 1 ? v.d2.src : "MUSIC", id == 2 ? C_WARN : C_DATE);
}
// play / pause glyph left of the pill (x 6..20), state 0 none, 1 paused (triangle), 2 playing (bars)
static void musicDrawIcon(int state) {
  G = &tft; Y0 = 0;
  tft.fillRect(0, 8, 26, 30, C_BG);
  if (state == 2) { tft.fillRect(7, 15, 4, 16, C_LINE_HI); tft.fillRect(14, 15, 4, 16, C_LINE_HI); }
  else if (state == 1) tft.fillTriangle(8, 15, 8, 31, 19, 23, C_DIM);
}

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

// ----- artwork (static: drawn when the track, the art or the state changes) -----
static void musicDrawArt(const MusicView& v) {
  G = &tft; Y0 = 0;
  const bool art = musicHasSession(v) && v.haveData2 && v.d2.hasArt && artReady && artBuf;
  tft.fillRect(MUS_ART_X - 6, MUS_ART_Y - 6, MUS_ART + 12, MUS_ART + 12, C_BG);
  if (art) {
    int w = artW > MUS_ART ? MUS_ART : artW, h = artH > MUS_ART ? MUS_ART : artH;
    if (artW == w) tft.drawRGBBitmap(MUS_ART_X + (MUS_ART - w) / 2, MUS_ART_Y + (MUS_ART - h) / 2, artBuf, w, h);
    else for (int y = 0; y < h; y++) tft.drawRGBBitmap(MUS_ART_X, MUS_ART_Y + y, artBuf + (size_t)y * artW, w, 1);     // wider than the tile: crop each row
    tft.drawRoundRect(MUS_ART_X - 2, MUS_ART_Y - 2, MUS_ART + 4, MUS_ART + 4, 8, C_CARD_LINE);
  } else {   // placeholder: a quiet tile with a note, never broken artwork
    tft.fillRoundRect(MUS_ART_X, MUS_ART_Y, MUS_ART, MUS_ART, 14, C_TILE);
    tft.drawRoundRect(MUS_ART_X, MUS_ART_Y, MUS_ART, MUS_ART, 14, C_TILE_LINE);
    const int cx = MUS_ART_X + 40, cy = MUS_ART_Y + 40;
    tft.fillCircle(cx - 8, cy + 14, 8, C_ORCHID); tft.fillRect(cx - 2, cy - 18, 4, 32, C_ORCHID);
    tft.fillTriangle(cx + 2, cy - 18, cx + 18, cy - 8, cx + 2, cy - 4, C_ORCHID);
  }
}

// ----- word wrap for the lyric lines (at most maxLines lines; the last one is shortened with "..." if the text is longer) -----
static int wrapLines(const char* s, const GFXfont* f, int maxW, char out[][64], int maxLines) {
  int n = 0; char cur[64] = ""; char word[64];
  const char* p = s;
  while (*p && n < maxLines) {
    int k = 0; while (*p == ' ') p++;
    while (*p && *p != ' ' && k < 62) word[k++] = *p++;
    word[k] = 0; if (!k) break;
    char test[128]; snprintf(test, sizeof test, cur[0] ? "%s %s" : "%s%s", cur, word);
    if (textWidth(test, f) <= maxW || !cur[0]) { strncpy(cur, test, sizeof cur - 1); cur[sizeof cur - 1] = 0; }
    else { strncpy(out[n++], cur, 63); out[n - 1][63] = 0; strncpy(cur, word, sizeof cur - 1); cur[sizeof cur - 1] = 0; }
  }
  if (cur[0] && n < maxLines) { strncpy(out[n++], cur, 63); out[n - 1][63] = 0; }
  if (n == maxLines && *p) { size_t l = strlen(out[n - 1]); if (l > 58) l = 58; out[n - 1][l] = 0; strcat(out[n - 1], "..."); }
  for (int i = 0; i < n; i++) { char t[64]; fitText(t, sizeof t, out[i], f, maxW); strcpy(out[i], t); }
  return n;
}

static void drawLyricZone(const MusicView& v) {
  if (!musicHasSession(v)) return;
  const meow::Music2Data& m = v.d2;
  const char* l1 = nullptr; const char* l2 = nullptr;
  if (isSpotify(v)) {
    switch (m.lyricState) {
      case 2: {
        char fit[64];
        if (m.prev[0]) { fitText(fit, sizeof fit, m.prev, &FreeSans9pt7b, 224); gText(120, 246, fit, &FreeSans9pt7b, C_MUTE, 1); }
        char ln[2][64]; int n = wrapLines(m.cur, &FreeSansBold9pt7b, 224, ln, 2);
        for (int i = 0; i < n; i++) gText(120, 270 + i * 22, ln[i], &FreeSansBold9pt7b, C_TEXT, 1);
        if (m.next[0]) { fitText(fit, sizeof fit, m.next, &FreeSans9pt7b, 224); gText(120, n == 2 ? 314 : 296, fit, &FreeSans9pt7b, C_MUTE, 1); }
        return;
      }
      case 1: l1 = "Loading lyrics..."; break;
      case 3: l1 = "Lyrics unavailable"; l2 = "for this track"; break;
      case 4: l1 = "Lyrics are off"; l2 = "Enable in the Meow Island app"; break;
      case 5: l1 = "Lyrics need internet"; l2 = "on the phone"; break;
      case 6: l1 = "Synced lyrics unavailable"; l2 = "for this track"; break;
      default: return;                                              // nothing received yet: show nothing rather than guess
    }
  } else { l1 = "Captions unavailable"; l2 = "Android does not share them"; }
  gText(120, 272, l1, &FreeSans9pt7b, C_DIM, 1);
  if (l2) gText(120, 294, l2, &FreeSans9pt7b, C_MUTE, 1);
}

static void drawMusicBand(const MusicView& v, uint32_t now) {
  const meow::MusicData& d = v.d;
  const bool show = v.haveData && d.session;
  const char* title = show && d.title[0] ? d.title : (show ? "Playing" : (v.linked ? "Nothing playing" : "Phone not connected"));
  const char* artist = show ? d.artist : "";

  // title: centred when it fits, otherwise a slow marquee
  int w = textWidth(title, &FreeSansBold12pt7b);
  if (w <= 224) gText(120, MUS_Y + 24, title, &FreeSansBold12pt7b, show ? C_TEXT : C_DIM, 1);
  else {
    const int gap = 48, period = w + gap;
    uint32_t t = now - musUi.marqueeStart;
    int off = t < 1500 ? 0 : (int)(((t - 1500) * 28UL / 1000) % period);
    gText(8 - off, MUS_Y + 24, title, &FreeSansBold12pt7b, C_TEXT, 0);
    gText(8 - off + period, MUS_Y + 24, title, &FreeSansBold12pt7b, C_TEXT, 0);
    gRect(0, MUS_Y, 8, 34, C_BG); gRect(232, MUS_Y, 8, 34, C_BG);
  }
  char fit[64];
  if (artist[0]) { fitText(fit, sizeof fit, artist, &FreeSans9pt7b, 224); gText(120, MUS_Y + 46, fit, &FreeSans9pt7b, C_ARTIST, 1); }

  // progress bar (212 px wide, the thumb never leaves it)
  const int bx = 14, bw = 212, cy = MUS_Y + 64, r = 7;
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
  gText(bx, MUS_Y + 88, a, &FreeSans9pt7b, C_TIME, 0);
  gText(bx + bw, MUS_Y + 88, b, &FreeSans9pt7b, C_TIME, 2);

  drawLyricZone(v);
}

// Everything that decides what the lyric zone looks like; when it changes band B is redrawn.
static uint32_t musicLyricSig(const MusicView& v) {
  uint32_t h = 2166136261u; auto mix = [&](uint32_t x) { h ^= x; h *= 16777619u; };
  mix(musicHasSession(v)); mix(isSpotify(v)); mix(v.d2.lyricState); mix(v.d2.lineIdx); mix(v.haveData2);
  const char* parts[4] = {v.d2.prev, v.d2.cur, v.d2.next, v.d2.src};
  for (int i = 0; i < 4; i++) for (const char* s = parts[i]; *s; s++) mix((uint8_t)*s);
  return h;
}

// Draws everything from scratch (screen switched to, or the track changed).
static void musicEnter(const MusicView& v, uint32_t now) {
  G = &tft; Y0 = 0;
  tft.fillScreen(C_BG);
  musUi.pill = musicPillId(v); musicDrawPill(v);
  musUi.icon = -1; musUi.artRevShown = 0xFFFFFFFF; musUi.artMode = -1; musUi.sig = 0; musUi.bandBDirty = true;
  musUi.marqueeStart = now; strncpy(musUi.title, v.d.title, sizeof musUi.title - 1); musUi.title[sizeof musUi.title - 1] = 0;
}

// Called every loop: redraws only what changed. Band A ~10 fps (title scroll + progress); band B (lyrics) only when its content changed.
static void musicTick(const MusicView& v, uint32_t now) {
  static uint32_t lastBand = 0;
  G = &tft; Y0 = 0;
  int pill = musicPillId(v);
  const char* srcNow = v.haveData2 ? v.d2.src : "";
  static char lastSrc[16] = "";
  if (pill != musUi.pill || (pill == 1 && strcmp(lastSrc, srcNow) != 0)) { musUi.pill = pill; strncpy(lastSrc, srcNow, sizeof lastSrc - 1); lastSrc[sizeof lastSrc - 1] = 0; musicDrawPill(v); }
  if (strcmp(musUi.title, v.d.title) != 0) { strncpy(musUi.title, v.d.title, sizeof musUi.title - 1); musUi.title[sizeof musUi.title - 1] = 0; musUi.marqueeStart = now; }
  int icon = !musicHasSession(v) ? 0 : (musicPlaying(v) ? 2 : 1);
  if (icon != musUi.icon) { musUi.icon = icon; musicDrawIcon(icon); }
  int artMode = (musicHasSession(v) && v.haveData2 && v.d2.hasArt && artReady) ? 1 : 0;
  if (artMode != musUi.artMode || (artMode && musUi.artRevShown != artRev)) { musUi.artMode = artMode; musUi.artRevShown = artRev; musicDrawArt(v); }
  uint32_t sig = musicLyricSig(v); if (sig != musUi.sig) { musUi.sig = sig; musUi.bandBDirty = true; }

  if (now - lastBand >= 100) {
    lastBand = now;
    if (canvas) {
      G = canvas; Y0 = MUS_Y; canvas->fillScreen(C_BG); drawMusicBand(v, now);
      G = &tft; Y0 = 0; pushCanvas(MUS_Y);
      if (musUi.bandBDirty) {                                    // lower part of the lyric zone
        musUi.bandBDirty = false;
        G = canvas; Y0 = MUS_BANDB_Y; canvas->fillScreen(C_BG); drawMusicBand(v, now);
        G = &tft; Y0 = 0; tft.drawRGBBitmap(0, MUS_BANDB_Y, canvas->getBuffer(), CANVAS_W, MUS_BANDB_ROWS);
      }
    } else {
      G = &tft; Y0 = 0; tft.fillRect(0, MUS_Y, 240, 320 - MUS_Y, C_BG); drawMusicBand(v, now); musUi.bandBDirty = false;
    }
    G = &tft; Y0 = 0;
  }
}
