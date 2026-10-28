// Desktop stand-in for the ST7789 panel: a 240x320 RGB565 framebuffer with the same drawing API as the real driver.
#pragma once
#include <Adafruit_GFX.h>
class Adafruit_ST7789 : public GFXcanvas16 {
 public:
  Adafruit_ST7789(int8_t, int8_t, int8_t) : GFXcanvas16(240, 320) {}
  void init(uint16_t, uint16_t, uint8_t = 0) {}
  void setSPISpeed(uint32_t) {}
  void invertDisplay(bool) {}
};
