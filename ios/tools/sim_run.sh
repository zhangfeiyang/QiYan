#!/bin/bash
# Install the simulator build and take a screenshot.  Usage: sim_run.sh <out.png> [lang] [wait] [extra launch args...]
set -o pipefail
cd "$(dirname "$0")/.."
UDID="${SIM_UDID:-D4AF2E0F-E034-4F8F-9AF4-2735ECD663D7}"   # iPhone 18 Pro
OUT="${1:-/tmp/qiyan_shot.png}"; LANG_ARG="${2:-}"; WAIT="${3:-5}"; shift 3 2>/dev/null || shift $#
APP=build/dd/Build/Products/Debug-iphonesimulator/QiYan.app
xcrun simctl boot "$UDID" 2>/dev/null
xcrun simctl bootstatus "$UDID" -b >/dev/null 2>&1
xcrun simctl terminate "$UDID" com.zhangfeiyang.qiyan 2>/dev/null
xcrun simctl install "$UDID" "$APP"
xcrun simctl status_bar "$UDID" override --time "9:41" --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3 2>/dev/null
ARGS=()
if [ -n "$LANG_ARG" ]; then ARGS+=(-AppleLanguages "($LANG_ARG)" -AppleLocale "$LANG_ARG"); fi
xcrun simctl launch "$UDID" com.zhangfeiyang.qiyan "${ARGS[@]}" "$@" >/dev/null
sleep "$WAIT"
xcrun simctl io "$UDID" screenshot "$OUT" >/dev/null 2>&1 && echo "shot: $OUT"
