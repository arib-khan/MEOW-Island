#!/bin/sh
# Renders the real StepCardRenderer on a desktop JVM (Java2D stand-in for android.graphics) and checks the pixels.
# Needs kotlinc + java. Output PNGs go to ./card-out. Usage: sh tests/shim/run.sh
set -e
mkdir -p card-out
kotlinc tests/shim/AndroidGraphicsShim.kt plugins/kotlin/StepCardRenderer.kt tests/shim/RenderCard.kt -include-runtime -d card-out/card.jar 2>&1 | grep -v '^warning' | grep -v '^tests/shim.*warning' || true
java -Djava.awt.headless=true -jar card-out/card.jar plugins/widgets/res/drawable-nodpi card-out
