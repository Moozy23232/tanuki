#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$TANUKI_PROJECT"
./gradlew :app:assembleModernDebug :app:assembleModernProbe :app:testModernDebugUnitTest :app:lintModernDebug
