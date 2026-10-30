// THROWAWAY in-memory LittleFS stand-in (same call shapes the firmware uses).
#pragma once
#include <map>
#include <string>
#include <vector>
#include <memory>
#include <stdint.h>
#include <string.h>
struct FsData { std::map<std::string, std::vector<uint8_t>> files; size_t total = 190 * 1024; bool failWrites = false; bool mountFails = false; };
inline FsData& fsData() { static FsData d; return d; }
class File {
 public:
  File() {} File(const std::string& p, bool w) : path_(p), w_(w), ok_(true) { if (w) fsData().files[p].clear(); }
  operator bool() const { return ok_; }
  size_t size() const { return ok_ ? fsData().files[path_].size() : 0; }
  size_t read(uint8_t* b, size_t n) { auto& v = fsData().files[path_]; size_t k = std::min(n, v.size() - std::min(v.size(), pos_)); if (k) memcpy(b, v.data() + pos_, k); pos_ += k; return k; }
  size_t write(const uint8_t* b, size_t n) { if (!w_ || fsData().failWrites) return 0; auto& v = fsData().files[path_]; size_t used = 0; for (auto& kv : fsData().files) used += kv.second.size(); if (used + n > fsData().total) return 0; v.insert(v.end(), b, b + n); return n; }
  bool seek(size_t p) { pos_ = p; return true; } void flush() {} void close() { ok_ = false; }
 private: std::string path_; bool w_ = false, ok_ = false; size_t pos_ = 0;
};
struct LittleFSClass {
  bool begin(bool = false) { return !fsData().mountFails; }
  File open(const char* p, const char* mode = "r") { bool w = mode[0] == 'w'; if (!w && !fsData().files.count(p)) return File(); return File(p, w); }
  bool exists(const char* p) { return fsData().files.count(p) > 0; }
  bool remove(const char* p) { return fsData().files.erase(p) > 0; }
  bool rename(const char* a, const char* b) { auto it = fsData().files.find(a); if (it == fsData().files.end()) return false; fsData().files[b] = it->second; fsData().files.erase(a); return true; }
  size_t totalBytes() { return fsData().total; } size_t usedBytes() { size_t u = 0; for (auto& kv : fsData().files) u += kv.second.size(); return u; }
};
inline LittleFSClass LittleFS;
