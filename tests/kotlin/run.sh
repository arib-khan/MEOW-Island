#!/bin/sh
# Compiles the android-free logic files and runs the desktop checks. Needs kotlinc + java. Usage: sh tests/kotlin/run.sh
set -e
KC=${KOTLINC:-kotlinc}
mkdir -p /tmp/meow-kt
$KC plugins/kotlin/ShortsLogic.kt plugins/kotlin/DomainBlocklist.kt plugins/kotlin/DnsPacket.kt tests/kotlin/WellbeingLogicTest.kt -include-runtime -d /tmp/meow-kt/wb.jar 2>&1 | grep -v '^warning' || true
java -cp /tmp/meow-kt/wb.jar WellbeingLogicTestKt
