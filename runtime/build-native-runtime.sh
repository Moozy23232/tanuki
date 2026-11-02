#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
NDK="$SDK/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64"
CC="$NDK/bin/aarch64-linux-android26-clang"
# Keep host paths out of __FILE__ strings and compiler debug metadata.
PROJECT_PREFIX_MAP="-ffile-prefix-map=$PWD=."
SDK_PREFIX_MAP="-ffile-prefix-map=$SDK=/android-sdk"
OUT="$PWD/.build/native"
SRC="$PWD/.build/sources"
mkdir -p "$OUT/include" "$SRC" "$PWD/.build/downloads" app/src/main/jniLibs/arm64-v8a
PROOT_COMMIT=b27d81a68d765f3462e807f4a14e01a03f8e89ea
if [ ! -d "$SRC/termux-proot/.git" ]; then
  git init "$SRC/termux-proot"
  git -C "$SRC/termux-proot" remote add origin https://github.com/termux/proot.git
  git -C "$SRC/termux-proot" fetch --depth 1 origin "$PROOT_COMMIT"
  git -C "$SRC/termux-proot" checkout --detach FETCH_HEAD
fi
[ "$(git -C "$SRC/termux-proot" rev-parse HEAD)" = "$PROOT_COMMIT" ] || { echo 'PRoot commit mismatch'; exit 1; }
if ! grep -q 'PTRACE_O_EXITKILL' "$SRC/termux-proot/src/tracee/event.c"; then
  patch -d "$SRC/termux-proot" -p1 < runtime/patches/proot-exitkill.patch
fi
if [ ! -f "$SRC/talloc-2.4.3/talloc.c" ]; then
  curl -fL --retry 2 -o .build/downloads/talloc.tar.gz https://download.samba.org/pub/talloc/talloc-2.4.3.tar.gz
  [ "$(shasum -a 256 .build/downloads/talloc.tar.gz | awk '{print $1}')" = dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd ]
  tar -xzf .build/downloads/talloc.tar.gz -C "$SRC"
fi
# talloc uses the platform C library through this small Bionic configuration header.
cat > "$OUT/include/replace.h" <<'HEADER'
#pragma once
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>
#include <limits.h>
#include <errno.h>
#include <assert.h>
#include <unistd.h>
#define HAVE_INTPTR_T 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_VA_COPY 1
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 3
#define _PUBLIC_ __attribute__((visibility("default")))
#define TANUKI_STRINGIFY_INNER(x) #x
#define TANUKI_STRINGIFY(x) TANUKI_STRINGIFY_INNER(x)
#define __location__ __FILE__ ":" TANUKI_STRINGIFY(__LINE__)
#ifndef MIN
#define MIN(a,b) ((a)<(b)?(a):(b))
#endif
HEADER
"$CC" "$PROJECT_PREFIX_MAP" "$SDK_PREFIX_MAP" -O2 -fPIC -I"$OUT/include" -I"$SRC/talloc-2.4.3" -c "$SRC/talloc-2.4.3/talloc.c" -o "$OUT/talloc.o"
"$NDK/bin/llvm-ar" rcs "$OUT/libtalloc.a" "$OUT/talloc.o"
ln -sf "$NDK/bin/llvm-readelf" "$OUT/readelf"
export PATH="$OUT:$PATH"
# GNU make command-line CFLAGS suppress makefile additions; keep this define explicit.
make -C "$SRC/termux-proot/src" clean >/dev/null
rm -f "$SRC/termux-proot/src/loader/loader-info.c"
make -C "$SRC/termux-proot/src" -j2 HAS_LOADER_32BIT= \
 CC="$CC" LD="$CC" STRIP="$NDK/bin/llvm-strip" \
 PROOT_UNBUNDLE_LOADER=/unused \
 CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -I. -I$SRC/talloc-2.4.3 $PROJECT_PREFIX_MAP $SDK_PREFIX_MAP" \
 CFLAGS='-O2 -fPIE -Wall -Wno-error=implicit-function-declaration -DPROOT_UNBUNDLE_LOADER=\"/unused\"' \
 LDFLAGS="-L$OUT -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384 -pie"
cp "$SRC/termux-proot/src/proot" app/src/main/jniLibs/arm64-v8a/libproot.so
cp "$SRC/termux-proot/src/loader/loader" app/src/main/jniLibs/arm64-v8a/libproot-loader.so
"$CC" "$PROJECT_PREFIX_MAP" "$SDK_PREFIX_MAP" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 app/src/main/cpp/supervisor.c -o app/src/main/jniLibs/arm64-v8a/libsupervisor.so
"$NDK/bin/llvm-readelf" -l app/src/main/jniLibs/arm64-v8a/libproot.so
shasum -a 256 app/src/main/jniLibs/arm64-v8a/*.so > "$OUT/SHA256SUMS"
