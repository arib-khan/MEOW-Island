#!/bin/sh
# Desktop tests for the firmware. Needs g++, git, python3 with Pillow. Usage: sh firmware/host-tests/run.sh
set -e
cd "$(dirname "$0")"
DEPS=.deps; OUT=out
mkdir -p "$DEPS" "$OUT"
if [ ! -d "$DEPS/gfx/.git" ]; then git clone -q --depth 1 https://github.com/adafruit/Adafruit-GFX-Library.git "$DEPS/gfx"; fi
# Adafruit_GFX reads font pointers as 32-bit values (right on the ESP32); on a 64-bit desktop read real pointers instead.
rm -rf "$DEPS/gfxp"; mkdir -p "$DEPS/gfxp"; cp -r "$DEPS/gfx/Adafruit_GFX.cpp" "$DEPS/gfx/Adafruit_GFX.h" "$DEPS/gfx/glcdfont.c" "$DEPS/gfx/gfxfont.h" "$DEPS/gfx/Fonts" "$DEPS/gfxp/"
sed -i 's|#define pgm_read_pointer(addr) ((void \*)pgm_read_dword(addr))|#define pgm_read_pointer(addr) (*(void *const *)(addr))|' "$DEPS/gfxp/Adafruit_GFX.cpp"
CXX="g++ -std=c++17 -O1 -w -DARDUINO=10800 -I stubs -I $DEPS/gfxp -I ../MeowIsland -I ."
$CXX -fsanitize=address,undefined -o $OUT/protocol_test protocol_test.cpp && $OUT/protocol_test ../../tests/device_vectors.txt
$CXX regress_original.cpp "$DEPS/gfxp/Adafruit_GFX.cpp" -o $OUT/regress_original
$CXX regress_new.cpp "$DEPS/gfxp/Adafruit_GFX.cpp" -o $OUT/regress_new
$OUT/regress_original $OUT; $OUT/regress_new $OUT
ok=1; for i in 0 1 2 3 4 5 6; do if cmp -s $OUT/orig_$i.raw $OUT/new_$i.raw; then echo "clock frame $i: identical to the original"; else echo "clock frame $i: DIFFERENT"; ok=0; fi; done
[ $ok = 1 ] || { echo "CLOCK REGRESSION"; exit 1; }
$CXX render_screens.cpp "$DEPS/gfxp/Adafruit_GFX.cpp" -o $OUT/render_screens && $OUT/render_screens $OUT
$CXX -fsanitize=address,undefined -o $OUT/image_test image_test.cpp && $OUT/image_test ../../tests/mimg_vectors.txt
$CXX sketch_smoke2.cpp "$DEPS/gfxp/Adafruit_GFX.cpp" -o $OUT/sketch_smoke2 && $OUT/sketch_smoke2 $OUT
$CXX sketch_smoke_link.cpp "$DEPS/gfxp/Adafruit_GFX.cpp" -o $OUT/sketch_smoke_link && $OUT/sketch_smoke_link
$CXX sketch_smoke.cpp "$DEPS/gfxp/Adafruit_GFX.cpp" -o $OUT/sketch_smoke && $OUT/sketch_smoke $OUT
python3 to_png.py $OUT
# ---- animated / multi-slot custom screens (pure logic, no GFX library needed)
CS=$OUT/cs; rm -rf "$CS"; mkdir -p "$CS"; for f in custom_store mani mimg image_rx protocol; do cp ../MeowIsland/$f.h "$CS/"; done; cp cs_stubs/gfx_common.h "$CS/"
g++ -std=c++17 -fsanitize=address,undefined -w -I ../MeowIsland mani_test.cpp -o $OUT/mani_test && $OUT/mani_test ../../tests/mani_vectors.txt
g++ -std=c++17 -fsanitize=address,undefined -w -I stubs -I "$CS" custom_store_test.cpp -o $OUT/custom_store_test && $OUT/custom_store_test ../../tests/mani_vectors.txt
