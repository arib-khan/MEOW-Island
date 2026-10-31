// Link-level behaviour of the firmware under repeated connect / disconnect / pairing attempts (stand-in NimBLE, no radio).
#include "common.h"
#include "../MeowIsland/MeowIsland.ino"
static int fails = 0, ok = 0;
#define CHECK(c, m) do { if (c) ok++; else { fails++; printf("FAIL: %s (line %d)\n", m, __LINE__); } } while (0)
static NimBLEConnInfo ci; static uint32_t T = 1000;
static void run(uint32_t ms) { for (uint32_t e = T + ms; T < e; T += 20) { host_millis = T; loop(); } }
static MeowServerCb scb;
static void connect() { scb.onConnect(bleServer, ci); }
static void disconnect() { scb.onDisconnect(bleServer, ci, 0x13); }

int main() {
  host_millis = T; setup(); run(400);
  // ---- first pairing: window open (no phone paired), code shown, bond stored, window closes
  connect(); CHECK(bleLinked && !bleSecure, "connected, not yet secure");
  scb.onPassKeyDisplay(); run(100); CHECK(overlay == OV_CODE, "the code is shown on the TFT while pairing"); CHECK(blePairingActive, "pairing in progress is tracked");
  NimBLEDevice::bonds = 1; scb.onAuthenticationComplete(ci); run(200);
  CHECK(bleSecure && !blePairingActive && overlay == OV_NONE, "pairing completes: secure, code removed from the display");
  CHECK(!blePairingOpen, "window closes once a phone is paired");

  // ---- repeated disconnect / reconnect of the bonded phone (no pairing window needed, no new code)
  int starts0 = NimBLEDevice::getAdvertising()->starts;
  for (int i = 0; i < 25; i++) {
    disconnect(); run(100); CHECK(!bleLinked && !bleSecure, "disconnected");
    connect(); scb.onAuthenticationComplete(ci); run(100); CHECK(bleLinked && bleSecure, "bonded phone reconnects straight to a secure link");
    CHECK(overlay == OV_NONE, "no code is displayed for a bonded reconnect");
  }
  CHECK(NimBLEDevice::getAdvertising()->starts > starts0, "advertising is restarted after disconnects");
  { int n0 = bleStatus->notifies; setScreen(meow::SCREEN_MUSIC); run(200); CHECK(bleStatus->notifies > n0, "status notifications flow again after reconnecting"); }

  // ---- a secure link is never dropped by the watchdog
  { int d0 = bleServer->disconnects; run(60000); CHECK(bleServer->disconnects == d0, "an authenticated link stays up"); }

  // ---- a link that never becomes secure is dropped (zombie / half-open connections cannot block the single connection)
  disconnect(); run(100); connect();
  { int d0 = bleServer->disconnects; run(15000); CHECK(bleServer->disconnects == d0, "an insecure link gets time to start security"); run(8000); CHECK(bleServer->disconnects > d0 && bleServer->disconnects <= d0 + 9, "an insecure link is dropped after the grace period (about once a second, not in a storm)"); }
  disconnect(); run(100);

  // ---- pairing attempted while the window is closed: refused immediately, no code is ever shown
  CHECK(bleBonds() == 1 && !blePairingOpen, "window is closed with one paired phone");
  connect(); scb.onPassKeyDisplay(); { int d0 = bleServer->disconnects; run(100); CHECK(bleServer->disconnects == d0 + 1, "pairing while closed is refused at once"); CHECK(overlay == OV_NONE && !blePasskeyReady, "and no code is shown"); }
  disconnect(); run(100);

  // ---- pairing in progress is allowed to take its time (the user types the code), but not forever
  pairingOpenUntil = T + 200000; run(100); CHECK(blePairingOpen, "BOOT hold opens the window");
  connect(); scb.onPassKeyDisplay(); run(100);
  { int d0 = bleServer->disconnects; run(100000); CHECK(bleServer->disconnects == d0, "a pairing in progress is not cut short"); run(25000); CHECK(bleServer->disconnects > d0 && bleServer->disconnects <= d0 + 26, "a pairing that never finishes is dropped"); }
  disconnect(); run(100);

  // ---- unauthenticated completion is refused
  connect(); { NimBLEConnInfo weak; weak.auth = false; int d0 = bleServer->disconnects; scb.onAuthenticationComplete(weak); CHECK(bleServer->disconnects == d0 + 1 && !bleSecure, "an unauthenticated link is dropped"); }
  disconnect(); run(100);

  // ---- code overlay disappears when the phone gives up
  pairingOpenUntil = T + 200000; run(100); connect(); scb.onPassKeyDisplay(); run(100); CHECK(overlay == OV_CODE, "code shown"); disconnect(); run(100); CHECK(overlay == OV_NONE, "code removed when the pairing attempt is abandoned");

  // ---- device side forget: window opens, a phone can pair again without restarting the ESP32
  connect(); scb.onAuthenticationComplete(ci); run(100);
  printf("link tests: %d passed, %d failed\n", ok, fails); return fails ? 1 : 0;
}
