#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
select_device
TANUKI_DIAG_DIR="$TANUKI_PROJECT/.build/diagnostics/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$TANUKI_DIAG_DIR"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" shell 'getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; getenforce; df -h /data' > "$TANUKI_DIAG_DIR/device.txt"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" shell dumpsys package com.tanuki.modern > "$TANUKI_DIAG_DIR/package.txt"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" shell dumpsys activity services com.tanuki.modern > "$TANUKI_DIAG_DIR/services.txt"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" shell dumpsys webviewupdate > "$TANUKI_DIAG_DIR/webview.txt"
# No broad logcat export: other applications and DSH plugin errors may contain secrets.
"$TANUKI_ADB" -s "$TANUKI_DEVICE" logcat -d -s TANUKI_RUNTIME TANUKI_PROBE | python3 -c 'import sys,re; s=sys.stdin.read(); s=re.sub(r"(?i)([?&](?:token|key|auth)=)[^\s&#]+",r"\1[REDACTED]",s); print(s)' > "$TANUKI_DIAG_DIR/probe.log"
printf '%s\n' "$TANUKI_DIAG_DIR"
