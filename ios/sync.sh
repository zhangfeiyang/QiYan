#!/bin/bash
# Regenerate the derived files, then copy the iOS sources, the PikaFish sources and the
# large model files to the Mac mini (~/qiyan). Build there with tools/mac_build.sh.
set -euo pipefail
IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$IOS_DIR/.."
python3 "$IOS_DIR/tools/make_strings.py" >/dev/null
python3 "$IOS_DIR/tools/make_assets.py" >/dev/null
python3 "$IOS_DIR/tools/gen_xcodeproj.py" >/dev/null
ssh macmini "mkdir -p ~/qiyan/Pikafish ~/qiyan/ios/QiYan/Resources/Models"
rsync -az --delete --include '*/' --include '*.cpp' --include '*.h' --exclude '*' \
  "$ROOT/third_party/Pikafish/src/" macmini:qiyan/Pikafish/src/
rsync -az --delete --exclude build --exclude .build --exclude Vendor --exclude '__pycache__' \
  --exclude QiYan/Resources/Models "$IOS_DIR/" macmini:qiyan/ios/
# 引擎权重与识别模型不进 git (50 MB + 42 MB), 直接从安卓 assets 拷
rsync -az "$ROOT/app/src/main/assets/models/pikafish.nnue" "$ROOT/app/src/main/assets/models/board_pose.onnx" \
  "$ROOT/app/src/main/assets/models/board_layout.onnx" macmini:qiyan/ios/QiYan/Resources/Models/
