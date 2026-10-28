#pragma once
#include <string>
class String {
 public:
  String(const char* s = "") : s_(s ? s : "") {}
  const char* c_str() const { return s_.c_str(); }
  unsigned length() const { return (unsigned)s_.size(); }
 private:
  std::string s_;
};
