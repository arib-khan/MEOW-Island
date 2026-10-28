#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
class Print {
 public:
  virtual ~Print() {}
  virtual size_t write(uint8_t) = 0;
  virtual size_t write(const uint8_t* b, size_t n) { size_t c = 0; while (n--) c += write(*b++); return c; }
  size_t write(const char* s) { return s ? write((const uint8_t*)s, strlen(s)) : 0; }
  size_t print(const char* s) { return write(s); }
  size_t print(char c) { return write((uint8_t)c); }
  size_t print(int v) { char b[24]; snprintf(b, sizeof b, "%d", v); return write(b); }
  size_t print(unsigned v) { char b[24]; snprintf(b, sizeof b, "%u", v); return write(b); }
  size_t println(const char* s) { size_t n = write(s); n += write("\r\n"); return n; }
};
