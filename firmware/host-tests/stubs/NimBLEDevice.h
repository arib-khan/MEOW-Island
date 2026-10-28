// Desktop stand-in for NimBLE-Arduino 2.x. The method signatures are copied from the real 2.5.2 headers, so the firmware's overrides and
// calls are type-checked against the real API shape (this does not run Bluetooth).
#pragma once
#include <string>
#include <vector>
#include <stdint.h>
#include <string.h>
#define BLE_HS_IO_DISPLAY_ONLY 0
#define BLE_HS_ADV_F_DISC_GEN 0x02
#define BLE_HS_ADV_F_BREDR_UNSUP 0x04
namespace NIMBLE_PROPERTY { enum : uint32_t { READ = 1, READ_ENC = 2, READ_AUTHEN = 4, WRITE = 8, WRITE_ENC = 16, WRITE_AUTHEN = 32, NOTIFY = 64 }; }
class NimBLEAttValue { public: std::vector<uint8_t> v; const uint8_t* data() const { return v.data(); } uint16_t length() const { return (uint16_t)v.size(); } uint16_t size() const { return (uint16_t)v.size(); } };
class NimBLEConnInfo { public: bool enc = true, auth = true, bonded = true; uint16_t handle = 7;
  uint16_t getConnHandle() const { return handle; } bool isBonded() const { return bonded; } bool isEncrypted() const { return enc; } bool isAuthenticated() const { return auth; } };
class NimBLEServer; class NimBLECharacteristic;
class NimBLEServerCallbacks { public: virtual ~NimBLEServerCallbacks() {}
  virtual void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) {}
  virtual void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) {}
  virtual void onMTUChange(uint16_t MTU, NimBLEConnInfo& connInfo) {}
  virtual uint32_t onPassKeyDisplay() { return 123456; }
  virtual void onConfirmPassKey(NimBLEConnInfo& connInfo, uint32_t pin) {}
  virtual void onAuthenticationComplete(NimBLEConnInfo& connInfo) {} };
class NimBLECharacteristicCallbacks { public: virtual ~NimBLECharacteristicCallbacks() {}
  virtual void onRead(NimBLECharacteristic* c, NimBLEConnInfo& i) {}
  virtual void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& i) {}
  virtual void onSubscribe(NimBLECharacteristic* c, NimBLEConnInfo& i, uint16_t s) {} };
class NimBLECharacteristic { public: NimBLEAttValue value; NimBLECharacteristicCallbacks* cb = nullptr; int notifies = 0;
  void setCallbacks(NimBLECharacteristicCallbacks* c) { cb = c; }
  const NimBLEAttValue& getValue() const { return value; }
  bool setValue(const uint8_t* d, size_t n) { value.v.assign(d, d + n); return true; }
  bool notify(bool = true) { notifies++; return true; } };
class NimBLEService { public: NimBLECharacteristic* createCharacteristic(const char*, uint32_t = 0) { chars.push_back(new NimBLECharacteristic()); return chars.back(); } std::vector<NimBLECharacteristic*> chars; };
class NimBLEServer { public: NimBLEServerCallbacks* cb = nullptr; int disconnects = 0;
  void setCallbacks(NimBLEServerCallbacks* c, bool = true) { cb = c; } void advertiseOnDisconnect(bool) {}
  NimBLEService* createService(const char*) { return new NimBLEService(); } bool start() { return true; }
  bool disconnect(uint16_t) const { const_cast<NimBLEServer*>(this)->disconnects++; return true; }
  bool disconnect(const NimBLEConnInfo&) const { const_cast<NimBLEServer*>(this)->disconnects++; return true; } };
class NimBLEAdvertisementData { public: std::vector<uint8_t> mfg; std::string name;
  bool setFlags(uint8_t) { return true; } bool addServiceUUID(const char*) { return true; }
  bool setName(const std::string& n, bool = true) { name = n; return true; }
  bool setManufacturerData(const uint8_t* d, size_t n) { mfg.assign(d, d + n); return true; } };
class NimBLEAdvertising { public: NimBLEAdvertisementData lastScan; int starts = 0;
  bool start(uint32_t = 0, const void* = nullptr) { starts++; return true; } bool stop() { return true; }
  bool setAdvertisementData(const NimBLEAdvertisementData&) { return true; }
  bool setScanResponseData(const NimBLEAdvertisementData& d) { lastScan = d; return true; } void enableScanResponse(bool) {} };
class NimBLEDevice { public:
  static inline NimBLEServer* server = nullptr; static inline NimBLEAdvertising* adv = nullptr; static inline int bonds = 0; static inline std::string name;
  static bool init(const std::string& n) { name = n; return true; }
  static void setSecurityAuth(bool, bool, bool) {} static void setSecurityIOCap(uint8_t) {} static bool setMTU(uint16_t) { return true; }
  static NimBLEServer* createServer() { server = new NimBLEServer(); return server; }
  static NimBLEAdvertising* getAdvertising() { if (!adv) adv = new NimBLEAdvertising(); return adv; }
  static int getNumBonds() { return bonds; } static bool deleteAllBonds() { bonds = 0; return true; } };
