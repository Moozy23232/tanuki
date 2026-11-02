#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
select_device
cd "$TANUKI_PROJECT"
TANUKI_VARIANT="${1:-modern}"
case "$TANUKI_VARIANT" in compat) TANUKI_PACKAGE=com.tanuki; TANUKI_TASK=Compat;; modern) TANUKI_PACKAGE=com.tanuki.modern; TANUKI_TASK=Modern;; *) exit 64;; esac
./gradlew ":app:assemble${TANUKI_TASK}Probe" ":app:assemble${TANUKI_TASK}ProbeAndroidTest"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" install -r "app/build/outputs/apk/$TANUKI_VARIANT/probe/app-$TANUKI_VARIANT-probe.apk"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" install -r "app/build/outputs/apk/androidTest/$TANUKI_VARIANT/probe/app-$TANUKI_VARIANT-probe-androidTest.apk"
mkdir -p .build/logs
TANUKI_RESULT=".build/logs/test-$TANUKI_VARIANT-result.txt"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" shell am instrument -w -r -e class com.tanuki.RuntimeProbeTest "$TANUKI_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$TANUKI_RESULT"
rg -q '^OK \([0-9]+ test' "$TANUKI_RESULT"
