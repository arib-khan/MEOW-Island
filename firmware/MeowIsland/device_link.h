// Bluetooth LE link to the phone, built on NimBLE-Arduino 2.x (written against the 2.5.2 headers).
//
// Security model
//   * Both characteristics require an ENCRYPTED and AUTHENTICATED link (LE Secure Connections, MITM protected, bonded).
//   * The device is "display only": during pairing it shows a random 6-digit passkey on the TFT and the phone user types it in
//     (the standard Android passkey dialog). Nobody who cannot see the screen can pair.
//   * New phones may pair only while the pairing window is open (no phone paired yet, or the BOOT button was held 3 s). When it is
//     closed a pairing attempt gets a code that is never displayed, so it cannot succeed.
//   * Wi-Fi details travel only over this encrypted link. Nothing here ever sends them back.
// Threading: NimBLE callbacks run on the BLE host task. They never touch the display; they only copy data into mailboxes that the main
// loop drains (one "latest frame" slot per message type, so a slow loop can never overflow anything).
#pragma once
#include <NimBLEDevice.h>
#include "protocol.h"
#include "config.h"

static const char* const BLE_SERVICE_UUID = "4d656f77-1000-4a3b-9c2d-0e7f6a5b4c3d";
static const char* const BLE_RX_UUID      = "4d656f77-1001-4a3b-9c2d-0e7f6a5b4c3d";
static const char* const BLE_STATUS_UUID  = "4d656f77-1002-4a3b-9c2d-0e7f6a5b4c3d";

static NimBLEServer* bleServer = nullptr;
static NimBLECharacteristic* bleRx = nullptr;
static NimBLECharacteristic* bleStatus = nullptr;
static char bleName[20] = "MeowIsland";

static portMUX_TYPE bleMux = portMUX_INITIALIZER_UNLOCKED;
static meow::Frame bleSlot[9];                       // indexed by message type 1..8
static volatile uint16_t bleDirty = 0;               // bit n = bleSlot[n] holds an unread frame
static meow::FrameAssembler bleAssembler;            // touched only by the BLE task

static volatile bool bleLinked = false;              // a phone is connected
static volatile bool bleSecure = false;              // ...and the link is encrypted + authenticated
static volatile bool blePairingOpen = false;         // written by the main loop, read by the BLE task
static volatile bool bleAuthDone = false;            // a pairing just completed (main loop closes the window)
static volatile bool bleAdvDirty = true;             // advertising data needs rebuilding
static volatile uint16_t bleConn = 0xFFFF;           // connection handle of the current phone
static volatile uint32_t blePasskey = 0;
static volatile bool blePasskeyReady = false;

class MeowServerCb : public NimBLEServerCallbacks {
 public:
  void onConnect(NimBLEServer*, NimBLEConnInfo& info) override { bleConn = info.getConnHandle(); bleLinked = true; bleSecure = false; bleAssembler.reset(); }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override { bleLinked = false; bleSecure = false; bleAssembler.reset(); bleAdvDirty = true; }
  uint32_t onPassKeyDisplay() override {
    uint32_t code = 100000 + (esp_random() % 900000);
    if (blePairingOpen) { blePasskey = code; blePasskeyReady = true; }       // shown on the TFT by the main loop
    return code;                                                              // when closed: a code nobody can see
  }
  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    if (info.isEncrypted() && info.isAuthenticated()) { bleSecure = true; if (info.isBonded()) bleAuthDone = true; }
    else if (bleServer) bleServer->disconnect(info);                          // unauthenticated link: drop it
  }
};

class MeowRxCb : public NimBLECharacteristicCallbacks {
 public:
  void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& info) override {
    if (!info.isEncrypted() || !info.isAuthenticated()) { if (bleServer) bleServer->disconnect(info); return; }   // defence in depth
    const NimBLEAttValue& v = chr->getValue();
    const uint8_t* d = v.data(); size_t n = v.length();
    meow::Frame f;
    for (size_t i = 0; i < n; i++) {
      if (bleAssembler.push(d[i], millis(), f) && f.type >= 1 && f.type <= 8) {
        portENTER_CRITICAL(&bleMux);
        bleSlot[f.type] = f; bleDirty |= (uint16_t)(1u << f.type);
        portEXIT_CRITICAL(&bleMux);
      }
    }
  }
};
static MeowServerCb bleServerCb;
static MeowRxCb bleRxCb;

// Takes the newest unread frame of `type`, if any.
static bool bleTake(uint8_t type, meow::Frame& out) {
  bool got = false;
  portENTER_CRITICAL(&bleMux);
  if (bleDirty & (1u << type)) { out = bleSlot[type]; bleDirty &= (uint16_t)~(1u << type); got = true; }
  portEXIT_CRITICAL(&bleMux);
  return got;
}
static bool bleTakePasskey(uint32_t& code) { if (!blePasskeyReady) return false; code = blePasskey; blePasskeyReady = false; return true; }
static int  bleBonds() { return NimBLEDevice::getNumBonds(); }

static void bleBuildAdvertising(bool pairingOpen) {
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->stop();
  NimBLEAdvertisementData ad, sr;
  ad.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  ad.addServiceUUID(BLE_SERVICE_UUID);
  sr.setName(bleName);
  const uint8_t mfg[] = {0xFF, 0xFF, 'M', 'I', (uint8_t)(pairingOpen ? 1 : 0), (uint8_t)bleBonds()};   // company id 0xFFFF (test), tag, flags, bonds
  sr.setManufacturerData(mfg, sizeof mfg);
  adv->setAdvertisementData(ad);
  adv->setScanResponseData(sr);
  adv->enableScanResponse(true);
  adv->start();
  bleAdvDirty = false;
}

static void bleBegin() {
  uint64_t mac = ESP.getEfuseMac();
  snprintf(bleName, sizeof bleName, "MeowIsland-%02X%02X", (unsigned)((mac >> 32) & 0xFF), (unsigned)((mac >> 40) & 0xFF));
  NimBLEDevice::init(bleName);
  NimBLEDevice::setSecurityAuth(true, true, true);              // bonding + MITM protection + LE Secure Connections
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);       // we can show a code but cannot type one
  NimBLEDevice::setMTU(247);
  bleServer = NimBLEDevice::createServer();
  bleServer->setCallbacks(&bleServerCb);
  bleServer->advertiseOnDisconnect(true);
  NimBLEService* svc = bleServer->createService(BLE_SERVICE_UUID);
  bleRx = svc->createCharacteristic(BLE_RX_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN);
  bleRx->setCallbacks(&bleRxCb);
  bleStatus = svc->createCharacteristic(BLE_STATUS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN | NIMBLE_PROPERTY::NOTIFY);
  uint8_t zero[meow::STATUS_LEN] = {0};
  bleStatus->setValue(zero, sizeof zero);
  bleServer->start();
  bleBuildAdvertising(true);
}

// Called every loop: keeps the advertisement truthful (pairing open? how many phones?), publishes the status, drops stalled frames.
static void bleService(uint32_t now, const meow::Status& st) {
  static uint8_t lastStatus[meow::STATUS_LEN] = {0};
  static bool lastOpen = true; static int lastBonds = -1;
  bleAssembler.expire(now);
  int bonds = bleBonds();
  if (!bleLinked && (bleAdvDirty || lastOpen != blePairingOpen || lastBonds != bonds)) { lastOpen = blePairingOpen; lastBonds = bonds; bleBuildAdvertising(blePairingOpen); }
  uint8_t cur[meow::STATUS_LEN]; meow::encodeStatus(st, cur);
  if (memcmp(cur, lastStatus, sizeof cur) != 0) {
    memcpy(lastStatus, cur, sizeof cur);
    bleStatus->setValue(cur, sizeof cur);
    if (bleLinked && bleSecure) bleStatus->notify();
  }
}

// Forget every paired phone (and drop the current link). The window then opens automatically because no phone is paired.
static void bleForgetPhones() {
  if (bleServer && bleLinked) bleServer->disconnect(bleConn);
  NimBLEDevice::deleteAllBonds();
  bleAdvDirty = true;
}
