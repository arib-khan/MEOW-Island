// Minimal Arduino core stand-in so the real Adafruit_GFX library and the firmware's drawing code run on a desktop.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <stdarg.h>
#include <algorithm>
#include <sys/time.h>
#include "pgmspace.h"
using std::min; using std::max;
typedef bool boolean; typedef uint8_t byte;
#ifndef PROGMEM
#define PROGMEM
#endif
#define PSTR(s) (s)
#define radians(deg) ((deg)*0.01745329251994329576923690768489)
#define degrees(rad) ((rad)*57.29577951308232)
class __FlashStringHelper;
#define F(s) (reinterpret_cast<const __FlashStringHelper *>(PSTR(s)))
extern uint32_t host_millis; extern time_t host_epoch; extern bool host_btn_low; extern bool host_restarted;
inline uint32_t millis() { return host_millis; }
inline void delay(uint32_t) {}
inline time_t host_time(time_t* t) { if (t) *t = host_epoch; return host_epoch; }
#define time(x) host_time(x)
inline int host_settimeofday(const struct timeval* tv) { host_epoch = tv->tv_sec; return 0; }
#define settimeofday(tv, tz) host_settimeofday(tv)
struct SerialStub { void begin(int) {} void printf(const char*, ...) {} void println(const char*) {} };
inline SerialStub Serial;
struct EspStub { uint32_t getFreeHeap() { return 123456; } uint64_t getEfuseMac() { return 0x0000A1B2C3D4E5F6ULL; } void restart() { host_restarted = true; } };
inline EspStub ESP;
inline void pinMode(int, int) {} inline void digitalWrite(int, int) {}
inline int digitalRead(int) { return host_btn_low ? 0 : 1; }
#define OUTPUT 1
#define INPUT_PULLUP 2
#define HIGH 1
#define LOW 0
typedef int portMUX_TYPE;
#define portMUX_INITIALIZER_UNLOCKED 0
#define portENTER_CRITICAL(m) ((void)(m))
#define portEXIT_CRITICAL(m) ((void)(m))
inline uint32_t esp_random() { static uint32_t x = 2463534242u; x ^= x << 13; x ^= x >> 17; x ^= x << 5; return x; }
#include "Print.h"
#include "WString.h"
