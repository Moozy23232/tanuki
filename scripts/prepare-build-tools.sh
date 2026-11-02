#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/downloads .build/jdk
TANUKI_JDK_ARCHIVE=.build/downloads/jdk17.tar.gz
TANUKI_JDK_HASH=78b08c7f5a0598242d5719c022e19479cb4a3aa45c37c19726a757a469ed1c15
if [ ! -x .build/jdk/jdk-17.0.21+9/Contents/Home/bin/java ]; then
  curl -fL --retry 2 -o "$TANUKI_JDK_ARCHIVE" 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.21_9.tar.gz'
  [ "$(shasum -a 256 "$TANUKI_JDK_ARCHIVE" | awk '{print $1}')" = "$TANUKI_JDK_HASH" ]
  tar -xzf "$TANUKI_JDK_ARCHIVE" -C .build/jdk
fi
printf 'sdk.dir=%s\n' "${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}" > local.properties.generated
if [ ! -e local.properties ]; then mv local.properties.generated local.properties; else rm local.properties.generated; fi
chmod +x gradlew
printf 'Project JDK ready. Existing SDK and global Java were not changed.\n'
