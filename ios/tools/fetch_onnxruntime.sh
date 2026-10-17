#!/bin/bash
# Runs on the Mac mini. Downloads the ONNX Runtime iOS package (static xcframework, MIT)
# into Vendor/onnxruntime.xcframework. Same runtime version as the Android build.
set -euo pipefail
IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION=1.22.0
ZIP="$IOS_DIR/Vendor/onnxruntime-c-$VERSION.zip"
mkdir -p "$IOS_DIR/Vendor"
[ -d "$IOS_DIR/Vendor/onnxruntime.xcframework" ] && { echo "already there"; exit 0; }
for i in 1 2 3 4 5 6; do
  curl --http1.1 -sS -L -C - -o "$ZIP" "https://download.onnxruntime.ai/pod-archive-onnxruntime-c-$VERSION.zip" && break
  sleep 2
done
unzip -q -o "$ZIP" "onnxruntime.xcframework/*" -d "$IOS_DIR/Vendor"
rm -f "$ZIP"
ls "$IOS_DIR/Vendor/onnxruntime.xcframework"
