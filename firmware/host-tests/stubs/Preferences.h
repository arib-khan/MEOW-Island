#pragma once
#include <map>
#include <string>
#include "WString.h"
class Preferences {
 public:
  bool begin(const char*, bool = false) { return true; } void end() {}
  String getString(const char* k, const char* d = "") { auto it = store().find(k); return String(it == store().end() ? d : it->second.c_str()); }
  size_t putString(const char* k, const char* v) { store()[k] = v; return strlen(v); }
  bool remove(const char* k) { return store().erase(k) > 0; }
  static std::map<std::string, std::string>& store() { static std::map<std::string, std::string> m; return m; }
};
