#pragma once
#define SPI_MODE0 0
#define SPI_MODE3 3
struct SPIClass { void begin(int = 0, int = 0, int = 0, int = 0) {} };
inline SPIClass SPI;
