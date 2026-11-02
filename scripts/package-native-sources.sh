#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
TANUKI_PROOT="$PWD/.build/sources/termux-proot"
TANUKI_COMMIT=b27d81a68d765f3462e807f4a14e01a03f8e89ea
[ "$(git -C "$TANUKI_PROOT" rev-parse HEAD)" = "$TANUKI_COMMIT" ]
[ "$(shasum -a 256 .build/downloads/talloc.tar.gz | awk '{print $1}')" = dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd ]
mkdir -p artifacts .build
TANUKI_STAGE="$(mktemp -d "$PWD/.build/native-source-package.XXXXXX")"
trap 'rm -rf "$TANUKI_STAGE"' EXIT
mkdir -p "$TANUKI_STAGE/.build/sources/termux-proot" "$TANUKI_STAGE/runtime/patches" "$TANUKI_STAGE/app/src/main/cpp" "$TANUKI_STAGE/licenses"
# 仅复制固定公开提交中的路径，内容使用实际参与构建的源码（包含本地补丁）。
while IFS= read -r -d '' path; do
  mkdir -p "$TANUKI_STAGE/.build/sources/termux-proot/$(dirname "$path")"
  cp -Pp "$TANUKI_PROOT/$path" "$TANUKI_STAGE/.build/sources/termux-proot/$path"
done < <(git -C "$TANUKI_PROOT" ls-tree -r --name-only -z "$TANUKI_COMMIT")
# 最小公开Git元数据保留固定HEAD与版本核验能力。
# 不复制开发者的配置、远端、索引、钩子或引用日志。
TANUKI_BUNDLE_GIT="$TANUKI_STAGE/.build/sources/termux-proot/.git"
mkdir -p "$TANUKI_BUNDLE_GIT/objects/pack" "$TANUKI_BUNDLE_GIT/refs/tags"
printf '%s\n' "$TANUKI_COMMIT" > "$TANUKI_BUNDLE_GIT/HEAD"
printf '%s\n' "$TANUKI_COMMIT" > "$TANUKI_BUNDLE_GIT/refs/tags/v5.1.107.101"
printf '[core]\nrepositoryformatversion = 0\nbare = false\nfilemode = true\n' > "$TANUKI_BUNDLE_GIT/config"
if [ -f "$TANUKI_PROOT/.git/shallow" ]; then cp "$TANUKI_PROOT/.git/shallow" "$TANUKI_BUNDLE_GIT/shallow"; fi
printf '%s\n' "$TANUKI_COMMIT" | git -C "$TANUKI_PROOT" pack-objects --revs "$TANUKI_BUNDLE_GIT/objects/pack/pack" >/dev/null
git -C "$TANUKI_STAGE/.build/sources/termux-proot" read-tree "$TANUKI_COMMIT"
tar -xzf .build/downloads/talloc.tar.gz -C "$TANUKI_STAGE/.build/sources"
cmp .build/sources/talloc-2.4.3/talloc.c "$TANUKI_STAGE/.build/sources/talloc-2.4.3/talloc.c"
cmp .build/sources/talloc-2.4.3/talloc.h "$TANUKI_STAGE/.build/sources/talloc-2.4.3/talloc.h"
cp runtime/build-native-runtime.sh "$TANUKI_STAGE/runtime/"
cp runtime/patches/proot-exitkill.patch "$TANUKI_STAGE/runtime/patches/"
cp app/src/main/cpp/supervisor.c "$TANUKI_STAGE/app/src/main/cpp/"
cp LICENSE THIRD_PARTY_NOTICES "$TANUKI_STAGE/"
cp -R licenses/. "$TANUKI_STAGE/licenses/"
printf '%s\n' '只解压到全新目录。在Apple Silicon macOS设置ANDROID_SDK_ROOT指向含NDK 27.2.12479018的SDK，再运行bash runtime/build-native-runtime.sh。完整对应的PRoot/talloc源码和补丁已包含；SDK/JDK需自行提供。本包不含设备数据或签名密钥。' > "$TANUKI_STAGE/NATIVE-SOURCE-README.txt"
# 不携带打包者的登录名、数字身份、时间戳或macOS扩展属性。
TANUKI_STAGE="$TANUKI_STAGE" python3 - <<'PY'
import gzip
import os
from pathlib import Path
import tarfile


def public_metadata(info):
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    info.mtime = 0
    info.pax_headers = {}
    return info


output = Path("artifacts/Tanuki-native-corresponding-sources.tar.gz")
with output.open("wb") as stream:
    with gzip.GzipFile(filename="", mode="wb", fileobj=stream, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
            archive.add(os.environ["TANUKI_STAGE"], arcname=".", filter=public_metadata)
PY
shasum -a 256 artifacts/Tanuki-native-corresponding-sources.tar.gz > artifacts/native-source-SHA256SUMS
printf '%s\n' 'artifacts/Tanuki-native-corresponding-sources.tar.gz'
