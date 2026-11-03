#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$TANUKI_PROJECT"
: "${TANUKI_KEYSTORE:?Set an existing production keystore outside the repository}"
: "${TANUKI_STORE_PASSWORD:?Provide the password through the environment}"
# 解析相对路径和符号链接，只允许实际位于仓库外的密钥文件；不输出密钥路径。
TANUKI_KEYSTORE="$(python3 - "$TANUKI_PROJECT" "$TANUKI_KEYSTORE" <<'PY'
from pathlib import Path
import sys
try:
    root, key = (Path(value).resolve(strict=True) for value in sys.argv[1:])
    if not key.is_file() or key.is_relative_to(root):
        raise ValueError()
except (OSError, ValueError, RuntimeError):
    print('Production keystore must be an existing file outside the repository.', file=sys.stderr)
    raise SystemExit(64)
print(key)
PY
)"
export TANUKI_KEY_PASSWORD="${TANUKI_KEY_PASSWORD:-$TANUKI_STORE_PASSWORD}"
TANUKI_SIGNING_ALIAS="${TANUKI_SIGNING_ALIAS:-tanuki}"
: "${TANUKI_VERSION_NAME:?Ask the user to confirm the release version first}"
: "${TANUKI_VERSION_CODE:?Provide a version code higher than every published build}"
python3 - "$TANUKI_VERSION_NAME" "$TANUKI_VERSION_CODE" <<'PY'
import re, sys
version, code = sys.argv[1:]
if (len(version) > 80 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?', version)
        or not re.fullmatch(r'[1-9][0-9]{0,9}', code) or not 2 <= int(code) <= 2_100_000_000):
    print('Confirmed release version and increasing version code are required.', file=sys.stderr)
    raise SystemExit(64)
PY
./gradlew -PdevelopmentReleaseSigning=false "-PtanukiVersionName=$TANUKI_VERSION_NAME" "-PtanukiVersionCode=$TANUKI_VERSION_CODE" :app:assembleModernRelease
TANUKI_OUTPUT="$TANUKI_PROJECT/artifacts/builds/production/$TANUKI_VERSION_NAME"
mkdir -p "$TANUKI_OUTPUT"
cp docs/ARTIFACTS.md artifacts/README.md
TANUKI_SIGNED_APK="$TANUKI_OUTPUT/Tanuki-$TANUKI_VERSION_NAME-arm64-release.apk"
"$TANUKI_SDK/build-tools/34.0.0/apksigner" sign --ks "$TANUKI_KEYSTORE" --ks-key-alias "$TANUKI_SIGNING_ALIAS" \
  --ks-pass env:TANUKI_STORE_PASSWORD --key-pass env:TANUKI_KEY_PASSWORD \
  --out "$TANUKI_SIGNED_APK" app/build/outputs/apk/modern/release/app-modern-release-unsigned.apk
"$TANUKI_SDK/build-tools/34.0.0/apksigner" verify --verbose --print-certs "$TANUKI_SIGNED_APK" > "$TANUKI_OUTPUT/release-signature.txt"
(cd "$TANUKI_OUTPUT" && shasum -a 256 "$(basename "$TANUKI_SIGNED_APK")" > release-SHA256SUMS)
printf '%s\n' "$TANUKI_SIGNED_APK"
