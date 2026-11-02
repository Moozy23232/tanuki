#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$TANUKI_PROJECT"
mkdir -p docs
TANUKI_REPORT="${1:-.build/logs/environment-current.txt}"
mkdir -p "$(dirname "$TANUKI_REPORT")"
{
 date -u
 uname -m
 sw_vers
 "$JAVA_HOME/bin/java" -version 2>&1
 "$TANUKI_ADB" version
 "$TANUKI_ADB" devices -l
 "$TANUKI_SDK/emulator/emulator" -version
 "$TANUKI_SDK/emulator/emulator" -list-avds
 "$TANUKI_SDK/cmdline-tools/latest/bin/avdmanager" list avd
 "$TANUKI_SDK/cmdline-tools/latest/bin/sdkmanager" --list_installed
 git --version
 df -h "$TANUKI_PROJECT"
 if [ -n "${TANUKI_DEVICE:-}" ]; then
   "$TANUKI_ADB" -s "$TANUKI_DEVICE" shell 'getprop ro.product.cpu.abi; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop sys.boot_completed; getenforce; df -h /data; getconf PAGE_SIZE'
 fi
} > "$TANUKI_REPORT"
printf '%s\n' "$TANUKI_REPORT"
