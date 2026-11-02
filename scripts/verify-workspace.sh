#!/bin/bash
# Safe default: build only. Device tests require an explicit device and test-data acknowledgement.
set -euo pipefail
usage() {
  printf '%s\n' \
    'Usage: bash scripts/verify-workspace.sh [--run-device-tests --confirm-test-data --device SERIAL]' \
    'Default: build isolated workspacecheck app/test APKs, JVM tests and lint; no device changes.' \
    'Device tests install in place and may change the test environment/settings. Use ONLY test data.' \
    'Prepare Ubuntu -> Node -> tools -> DSH first; stop DSH/installers before device tests.'
}
run_device=false
confirmed=false
explicit_device=false
device="${TANUKI_DEVICE:-}"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --run-device-tests) run_device=true; shift ;;
    --confirm-test-data) confirmed=true; shift ;;
    --device)
      [ "$#" -ge 2 ] && [ -n "$2" ] && [[ "$2" != --* ]] || { usage >&2; exit 2; }
      device="$2"; explicit_device=true; shift 2 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done
if [ "$run_device" = true ]; then
  [ "$confirmed" = true ] && [ -n "$device" ] || {
    printf '%s\n' 'Refusing device changes: pass --confirm-test-data and --device SERIAL (or TANUKI_DEVICE).' >&2
    exit 2
  }
elif [ "$confirmed" = true ] || [ "$explicit_device" = true ]; then
  printf '%s\n' 'Device options require --run-device-tests; omit them for a build-only run.' >&2
  exit 2
fi
source "$(dirname "$0")/env.sh"
cd "$TANUKI_PROJECT"
python3 scripts/check-handoff.py
app_id=com.tanuki.modern.workspacecheck
app_apk=app/build/outputs/apk/modern/debug/app-modern-debug.apk
test_apk=app/build/outputs/apk/androidTest/modern/debug/app-modern-debug-androidTest.apk
if [ "$run_device" = true ]; then
  export TANUKI_DEVICE="$device"
  analyzer="$TANUKI_SDK/cmdline-tools/latest/bin/apkanalyzer"
  [ -x "$analyzer" ] || { printf '%s\n' 'Install Android SDK cmdline-tools/latest (apkanalyzer required).' >&2; exit 2; }
  select_device
  abi="$("$TANUKI_ADB" -s "$TANUKI_DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')"
  [ "$abi" = arm64-v8a ] || { printf 'Unsupported test device ABI: %s (arm64-v8a required).\n' "$abi" >&2; exit 2; }
fi
./gradlew -PtestBuildType=debug -I scripts/workspace-verification.init.gradle \
  :app:assembleModernDebug :app:assembleModernDebugAndroidTest \
  :app:testModernDebugUnitTest :app:lintModernDebug
if [ "$run_device" != true ]; then
  printf '\nBuild-only complete; no APK was installed.\nApp: %s\nTests: %s\n' "$app_apk" "$test_apk"
  exit 0
fi
# Standard and isolated builds share output paths. Never trust a filename as package identity.
actual_app="$("$analyzer" manifest application-id "$app_apk")"
actual_test="$("$analyzer" manifest application-id "$test_apk")"
[ "$actual_app" = "$app_id" ] && [ "$actual_test" = "$app_id.test" ] || {
  printf '%s\n' 'APK package mismatch; refusing installation. Do not run competing variant builds.' >&2
  exit 2
}
actual_target="$("$analyzer" manifest print "$test_apk" | python3 -c '
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
items = root.findall("instrumentation")
if len(items) != 1:
    raise SystemExit("Expected exactly one instrumentation target")
print(items[0].get("{http://schemas.android.com/apk/res/android}targetPackage", ""))
')"
[ "$actual_target" = "$app_id" ] || { printf '%s\n' 'Instrumentation target mismatch; refusing installation.' >&2; exit 2; }
classes='com.tanuki.RuntimeTreeCleanupTest,com.tanuki.WorkspaceFileManagerTest,com.tanuki.BrowserIntegrationTest,com.tanuki.AgentBrowserControlTest,com.tanuki.BrowserLibraryTest,com.tanuki.BrowserWorkspacePresentationTest,com.tanuki.FilesBrowserPresentationTest,com.tanuki.SetupPresentationTest,com.tanuki.WorkspaceStorageUiTest,com.tanuki.BrowserOverlayPresentationTest,com.tanuki.BrowserTabLifecycleTest,com.tanuki.DshViewportTest,com.tanuki.WebAuthenticationTest,com.tanuki.RuntimeFoundationToolsTest'
mkdir -p .build/logs
log=".build/logs/workspace-regression-$(date -u +%Y%m%dT%H%M%SZ)-$$.txt"
printf 'Running on explicitly selected test device %s; log: %s\n' "$TANUKI_DEVICE" "$log"
# No uninstall, pm clear, force-stop, system-image reset, permission bypass or model invocation.
"$TANUKI_ADB" -s "$TANUKI_DEVICE" install -r "$app_apk"
"$TANUKI_ADB" -s "$TANUKI_DEVICE" install -r "$test_apk"
if ! "$TANUKI_ADB" -s "$TANUKI_DEVICE" shell am instrument -w -r -e class "$classes" \
  "$app_id.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1; then
  printf 'ADB/instrumentation command failed; inspect private log %s\n' "$log" >&2
  exit 1
fi
# am instrument can exit zero even when JUnit fails. Skipped prerequisites are NOT a clean pass.
python3 - "$log" <<'PY'
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text(errors="replace").replace("\r", "")
summary = re.findall(r"^OK \((\d+) tests?\)\s*$", text, flags=re.M)
codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", text, flags=re.M)
if (len(summary) != 1 or int(summary[0]) <= 0
        or re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", text)
        or any(code in {"-1", "-2", "-3", "-4"} for code in codes)
        or codes.count("0") != int(summary[0])):
    print(f"FAIL/incomplete/skipped: inspect {path}; verify runtime/WebView prerequisites.", file=sys.stderr)
    raise SystemExit(1)
print(f"PASS: {summary[0]} Android tests; detailed private log: {path}")
PY
