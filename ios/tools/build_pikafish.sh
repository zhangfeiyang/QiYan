#!/bin/bash
# Runs on the Mac mini. Compiles PikaFish (GPL-3) plus Engine/pikafish_bridge.cpp into
# static libraries the app links: Vendor/pikafish/{iphoneos,iphonesimulator,macosx}/libpikafish.a
# PikaFish sources come from ../Pikafish/src (sync.sh copies third_party/Pikafish/src there).
#   build_pikafish.sh            all three platforms
#   build_pikafish.sh macosx     one platform (macosx is only for tools/pikafish_smoke.c)
set -euo pipefail
IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$IOS_DIR/../Pikafish/src"
OUT="$IOS_DIR/Vendor/pikafish"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
[ -f "$SRC/engine.cpp" ] || { echo "missing $SRC (run sync.sh)" >&2; exit 1; }

SOURCES=()
for f in "$SRC"/*.cpp "$SRC"/nnue/*.cpp "$SRC"/nnue/features/*.cpp "$SRC"/external/common/*.cpp \
         "$SRC"/external/decompress/*.cpp; do
  case "$(basename "$f")" in main.cpp|tune.cpp|pikafish_jni.cpp) continue ;; esac
  SOURCES+=("$f")
done
SOURCES+=("$IOS_DIR/Engine/pikafish_bridge.cpp")

CXXFLAGS=(-std=c++17 -O3 -DNDEBUG -fno-exceptions -fno-rtti -fvisibility=hidden
          -DIS_64BIT -DUSE_POPCNT -DUSE_NEON=8 -I"$SRC" -I"$IOS_DIR/Engine" -w)

build() {
  local platform="$1" target sdk
  case "$platform" in
    iphoneos) target=arm64-apple-ios17.0; sdk=iphoneos ;;
    iphonesimulator) target=arm64-apple-ios17.0-simulator; sdk=iphonesimulator ;;
    macosx) target=arm64-apple-macos13.0; sdk=macosx ;;
    *) echo "unknown platform $platform" >&2; exit 1 ;;
  esac
  local sysroot obj="$OUT/obj/$platform"
  sysroot="$(xcrun --sdk "$sdk" --show-sdk-path)"
  mkdir -p "$obj" "$OUT/$platform"
  local objects=() pids=()
  for f in "${SOURCES[@]}"; do
    local o="$obj/$(echo "${f#"$IOS_DIR/../"}" | tr '/' '_').o"
    objects+=("$o")
    if [ ! -f "$o" ] || [ "$f" -nt "$o" ]; then
      xcrun --sdk "$sdk" clang++ -target "$target" -isysroot "$sysroot" "${CXXFLAGS[@]}" -c "$f" -o "$o" &
      pids+=($!)
      if [ "${#pids[@]}" -ge 8 ]; then wait "${pids[0]}"; pids=("${pids[@]:1}"); fi
    fi
  done
  for p in "${pids[@]}"; do wait "$p"; done
  rm -f "$OUT/$platform/libpikafish.a"
  xcrun --sdk "$sdk" libtool -static -o "$OUT/$platform/libpikafish.a" "${objects[@]}"
  echo "== $platform: $(du -h "$OUT/$platform/libpikafish.a" | cut -f1)"
}

if [ $# -gt 0 ]; then build "$1"; else for p in iphoneos iphonesimulator macosx; do build "$p"; done; fi
