#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
select_device
cd "$TANUKI_PROJECT"
TANUKI_APK="${1:-app/build/outputs/apk/modern/debug/app-modern-debug.apk}"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" install -r "$TANUKI_APK"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" shell am start -n com.tanuki.modern/com.tanuki.MainActivity
