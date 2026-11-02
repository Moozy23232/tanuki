#!/bin/bash
# 使用source加载本文件，不修改系统Java或全局Shell配置。
set -euo pipefail
TANUKI_PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TANUKI_SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
if [ -z "${TANUKI_JDK:-}" ]; then
  for candidate in "$TANUKI_PROJECT"/.build/jdk/*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then TANUKI_JDK="$candidate"; break; fi
  done
fi
: "${TANUKI_JDK:?Set TANUKI_JDK to a JDK 17 home, or run scripts/prepare-build-tools.sh}"
export JAVA_HOME="$TANUKI_JDK"
TANUKI_ADB="$TANUKI_SDK/platform-tools/adb"
select_device() {
  if [ -n "${TANUKI_DEVICE:-}" ]; then "$TANUKI_ADB" -s "$TANUKI_DEVICE" get-state >/dev/null; return; fi
  local devices
  devices="$("$TANUKI_ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
  [ "$(printf '%s\n' "$devices" | awk 'NF{n++} END{print n+0}')" = 1 ] || { echo 'Set TANUKI_DEVICE to one explicitly selected device serial.' >&2; exit 1; }
  TANUKI_DEVICE="$devices"
}
