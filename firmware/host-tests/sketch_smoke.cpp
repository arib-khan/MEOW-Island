// Compiles the COMPLETE sketch (MeowIsland.ino) on the desktop and drives it with the same byte streams the phone produces.
#include "common.h"
#include <fstream>
#include <vector>
#include <string>
#include "../MeowIsland/MeowIsland.ino"

static int fails = 0, ok = 0;
#define CHECK(c, m) do { if (c) ok++; else { fails++; printf("FAIL: %s (line %d)\n", m, __LINE__); } } while (0)
static std::vector<uint8_t> vec(const char* name) {
  std::ifstream in("../../tests/device_vectors.txt"); std::string l;
  while (std::getline(in, l)) { size_t a = l.find('|'), b = l.find('|', a + 1); if (l.substr(0, a) == name) { std::vector<uint8_t> v; std::string h = l.substr(a + 1, b - a - 1); for (size_t i = 0; i + 1 < h.size(); i += 2) v.push_back((uint8_t)strtol(h.substr(i, 2).c_str(), nullptr, 16)); return v; } }
  return {};
}
static NimBLEConnInfo ci;
static void phoneWrites(const std::vector<uint8_t>& bytes, size_t chunk = 20) {   // as BLE writes of at most `chunk` bytes
  for (size_t i = 0; i < bytes.size(); i += chunk) {
    bleRx->value.v.assign(bytes.begin() + i, bytes.begin() + std::min(bytes.size(), i + chunk));
    bleRxCb.onWrite(bleRx, ci);
  }
}
static uint32_t T = 1000;
static void run(uint32_t ms) { for (uint32_t e = T + ms; T < e; T += 20) { host_millis = T; loop(); } }
static const char* OUT_DIR;
static void snap(const char* n) { char p[256]; snprintf(p, sizeof p, "%s/%s.ppm", OUT_DIR, n); dumpPpm(p, tft); }

int main(int, char** argv) {
  OUT_DIR = argv[1];
  setenv("TZ", "UTC", 1); tzset();
  host_millis = T; host_epoch = 0;
  setup();
  CHECK(curScreen == meow::SCREEN_CLOCK, "boots on the clock");
  CHECK(strncmp(bleName, "MeowIsland-", 11) == 0 && strlen(bleName) == 15, "device name MeowIsland-XXXX");
  CHECK(blePairingOpen == false, "window flag false until first loop"); run(400);
  CHECK(blePairingOpen == true, "pairing window open while no phone is paired");
  CHECK(NimBLEDevice::getAdvertising()->lastScan.mfg.size() == 6 && NimBLEDevice::getAdvertising()->lastScan.mfg[4] == 1, "advertisement says pairing is open");
  CHECK(!haveWifi && wifiState == meow::WIFI_NONE, "no Wi-Fi saved on a new device");

  // ---- pairing: the passkey goes on the screen, disappears when pairing completes
  MeowServerCb scb; scb.onConnect(bleServer, ci);
  uint32_t code = scb.onPassKeyDisplay(); run(100);
  CHECK(overlay == OV_CODE, "passkey overlay shown while pairing"); snap("smoke_pairing_code");
  NimBLEDevice::bonds = 1; scb.onAuthenticationComplete(ci); run(200);
  CHECK(overlay == OV_NONE && bleSecure && !blePairingOpen && NimBLEDevice::getAdvertising()->starts >= 1, "overlay gone, link secure, window closed after pairing");
  CHECK(code >= 100000 && code <= 999999, "passkey is six digits");
  // a stranger tries while the window is closed: the code they would be given is never displayed
  blePasskeyReady = false; scb.onPassKeyDisplay(); run(100); CHECK(overlay == OV_NONE && !blePasskeyReady, "no code is shown when the window is closed");

  // ---- unauthenticated writes are refused
  { NimBLEConnInfo bad; bad.auth = false; bleServer->disconnects = 0; auto f = vec("SCREEN"); (void)f; auto s2 = vec("TIME_IST");
    bleRx->value.v = s2; bleRxCb.onWrite(bleRx, bad); run(60); CHECK(bleServer->disconnects == 1 && !timeValid(), "write on an unauthenticated link is dropped and the link is cut"); }

  // ---- phone sends time, wifi, steps, music, screen
  phoneWrites(vec("TIME_IST")); run(100);
  CHECK(timeValid() && strcmp(tzCur, "<+0530>-5:30") == 0, "time and timezone from the phone");
  phoneWrites(vec("WIFI_OK")); run(100);
  CHECK(haveWifi && strcmp(wifiSsid, "HomeNet") == 0 && wifiState == meow::WIFI_CONNECTING, "wifi saved and connecting");
  { Preferences p; String s = p.getString("ssid", ""); CHECK(strcmp(s.c_str(), "HomeNet") == 0, "wifi persisted in flash storage"); }
  WiFi.st = WL_CONNECTED; run(100); CHECK(wifiState == meow::WIFI_CONNECTED, "connected state reported");
  WiFi.st = WL_NO_SSID_AVAIL; wifiState = meow::WIFI_CONNECTING; wifiBeganAt = T; run(100); CHECK(wifiReason == meow::REASON_NOT_FOUND, "'network not found' reason"); WiFi.st = WL_CONNECTED; run(60);

  phoneWrites(vec("STEPS_FULL"), 7); phoneWrites(vec("SCREEN"), 3); run(100);        // SCREEN vector #1 = steps? (last SCREEN vector is id 2)
  phoneWrites(vec("MUSIC_PLAY")); run(60);
  std::vector<uint8_t> s1 = {0xA5, 0x02, 0x01, 0x01}; phoneWrites(s1); run(150);
  CHECK(curScreen == meow::SCREEN_STEPS && stepsView.haveData && stepsView.d.steps == 12345 && stepsView.linked, "steps screen shows the synced data"); snap("smoke_steps");
  std::vector<uint8_t> s2 = {0xA5, 0x02, 0x01, 0x02}; phoneWrites(s2); run(900);
  CHECK(curScreen == meow::SCREEN_MUSIC && musicView.d.playing && strcmp(musicView.d.title, "Kabhi Kabhi Aditi") == 0, "music screen shows the synced track"); snap("smoke_music");
  host_btn_low = false;

  // ---- phone disappears: the device keeps working and says so
  scb.onDisconnect(bleServer, ci, 0); run(300);
  CHECK(!bleSecure && !musicView.linked, "disconnect handled"); snap("smoke_offline");
  std::vector<uint8_t> s0 = {0xA5, 0x02, 0x01, 0x00};
  scb.onConnect(bleServer, ci); scb.onAuthenticationComplete(ci); phoneWrites(s0); run(200); CHECK(curScreen == meow::SCREEN_CLOCK, "phone can return to the clock");

  // ---- BOOT button: tap cycles, 3 s opens pairing, 10 s forgets everything
  host_btn_low = true; run(200); host_btn_low = false; run(100); CHECK(curScreen == meow::SCREEN_STEPS, "button tap = next screen");
  host_btn_low = true; run(3300); host_btn_low = false; run(100); CHECK(overlay == OV_MODE && blePairingOpen, "3 s hold opens the pairing window"); snap("smoke_pairing_mode");
  run(6500); CHECK(overlay == OV_NONE, "pairing message goes away by itself");
  host_btn_low = true; run(10500); host_btn_low = false; run(100);
  CHECK(host_restarted && !haveWifi && NimBLEDevice::getNumBonds() == 0, "10 s hold forgets phones and Wi-Fi, then restarts");
  { Preferences p; String s = p.getString("ssid", ""); CHECK(s.c_str()[0] == 0, "wifi erased from flash storage"); }

  printf("sketch smoke test: %d checks passed, %d failed\n", ok, fails);
  return fails ? 1 : 0;
}
