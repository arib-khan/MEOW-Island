#!/bin/sh
# Renders the Dancing Cat progress bar on a desktop JVM and checks pixels. Usage: sh tests/shim/run_bar.sh (KOTLINC=... optional)
set -e
mkdir -p card-out
${KOTLINC:-kotlinc} tests/shim/AndroidGraphicsShim.kt plugins/kotlin/MusicBarRenderer.kt tests/shim/RenderBar.kt -include-runtime -d card-out/bar.jar 2>&1 | grep -v '^warning' | grep -v 'warning:' || true
java -Djava.awt.headless=true -cp card-out/bar.jar RenderBarKt card-out
