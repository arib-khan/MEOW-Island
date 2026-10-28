#pragma once
#include <stdint.h>
enum { WL_IDLE_STATUS = 0, WL_NO_SSID_AVAIL = 1, WL_CONNECTED = 3, WL_CONNECT_FAILED = 4, WL_DISCONNECTED = 6 };
enum { WIFI_STA = 1 };
struct WiFiClass {
  int st = WL_DISCONNECTED;
  void mode(int) {} void persistent(bool) {} void setAutoReconnect(bool) {}
  void begin(const char*, const char*) {} void disconnect(bool = false) {}
  int status() { return st; }
};
inline WiFiClass WiFi;
inline void configTzTime(const char*, const char*, const char*, const char*) {}
