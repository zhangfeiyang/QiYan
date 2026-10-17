#!/bin/bash
# Runs on the Mac mini: ui_test.sh <test[,test...]> [KEY=VALUE ...]
# Screenshots land in /tmp/qy_ui. KEY=VALUE pairs (QY_LANG, QY_ROUTE, QY_WAIT) reach the test runner.
set -o pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
UDID="${SIM_UDID:-D4AF2E0F-E034-4F8F-9AF4-2735ECD663D7}"
ONLY=(); IFS=, read -ra TESTS <<< "$1"; shift
for t in "${TESTS[@]}"; do ONLY+=(-only-testing:"QiYanUITests/QiYanUITests/$t"); done
for kv in "$@"; do export "TEST_RUNNER_$kv"; done
rm -rf /tmp/qy_ui; mkdir -p /tmp/qy_ui
xcrun simctl boot "$UDID" 2>/dev/null
xcrun simctl status_bar "$UDID" override --time "9:41" --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3 2>/dev/null
xcodebuild -project QiYan.xcodeproj -scheme QiYan -configuration Debug \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build/dd \
  CODE_SIGNING_ALLOWED=NO -collect-test-diagnostics never test "${ONLY[@]}" 2>&1 \
  | grep -E "error:|Test Case .*(passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|\*\* " | head -40
ls /tmp/qy_ui
