#pragma once
#include <Arduino.h>
#include <Adafruit_GFX.h>
#include <stdio.h>
uint32_t host_millis = 0; time_t host_epoch = 0; bool host_btn_low = false, host_restarted = false;
// Writes a GFXcanvas16 as a binary PPM (converted to PNG by run.sh).
static void dumpPpm(const char* path, GFXcanvas16& c) {
  FILE* f = fopen(path, "wb"); if (!f) return;
  fprintf(f, "P6\n%d %d\n255\n", c.width(), c.height());
  uint16_t* b = c.getBuffer();
  for (int i = 0; i < c.width() * c.height(); i++) { uint16_t v = b[i]; uint8_t px[3] = {(uint8_t)((v >> 8) & 0xF8), (uint8_t)((v >> 3) & 0xFC), (uint8_t)((v << 3) & 0xF8)}; fwrite(px, 1, 3, f); }
  fclose(f);
}
static void dumpRaw(const char* path, GFXcanvas16& c) { FILE* f = fopen(path, "wb"); if (f) { fwrite(c.getBuffer(), 2, (size_t)c.width() * c.height(), f); fclose(f); } }
