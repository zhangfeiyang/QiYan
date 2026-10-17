#!/bin/bash
# Runs on the Mac mini (via ssh from Linux after ./sync.sh).
#   mac_build.sh sim      build Debug for the simulator into build/sim
#   mac_build.sh archive  archive Release, sign with the persistent distribution
#                         certificate and export an App Store IPA into build/export
set -euo pipefail
IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$IOS_DIR"
mkdir -p build
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
MODE="${1:-sim}"
# 原生依赖: PikaFish 静态库与 ONNX Runtime, 缺了先补
[ -f Vendor/pikafish/iphoneos/libpikafish.a ] && [ -f Vendor/pikafish/iphonesimulator/libpikafish.a ] || bash tools/build_pikafish.sh
[ -d Vendor/onnxruntime.xcframework ] || bash tools/fetch_onnxruntime.sh

if [ "$MODE" = sim ]; then
  xcodebuild -project QiYan.xcodeproj -scheme QiYan -configuration Debug \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath build/dd CODE_SIGNING_ALLOWED=NO build 2>&1 \
    | grep -E "error:|warning: .*(deprecated|will never)|BUILD (SUCCEEDED|FAILED)|\*\* " | grep -v "^ld: warning" | head -150
  test -d build/dd/Build/Products/Debug-iphonesimulator/QiYan.app && echo "== SIM APP OK"
  exit 0
fi

# ---- archive: same signing material as the Crystal Grid pipeline (~/buildkeys, cgbuild keychain)
KC_PASS="$(sed -n 2p "$HOME/buildkeys/.passwords")"
security unlock-keychain -p "$KC_PASS" "$HOME/Library/Keychains/cgbuild.keychain"
SIGN_IDENTITY="$(security find-identity -v -p codesigning "$HOME/Library/Keychains/cgbuild.keychain-db" | awk -F'"' '/iPhone Distribution|Apple Distribution/ {print $2; exit}')"
[ -n "$SIGN_IDENTITY" ] || { echo "no distribution identity" >&2; exit 1; }
PROFILE_SRC="$HOME/buildkeys/qiyan_appstore.mobileprovision"
PROFILE_PLIST="$(security cms -D -i "$PROFILE_SRC")"
PROFILE_UUID="$(printf '%s' "$PROFILE_PLIST" | plutil -extract UUID raw -o - -)"
PROFILE_NAME="$(printf '%s' "$PROFILE_PLIST" | plutil -extract Name raw -o - -)"
PROFILE_DIR="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
mkdir -p "$PROFILE_DIR"
cp "$PROFILE_SRC" "$PROFILE_DIR/$PROFILE_UUID.mobileprovision"
TEAM_ID=LSAZF25AG8
BUNDLE_ID=com.zhangfeiyang.qiyan
cat > build/exportOptions.plist <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>teamID</key><string>$TEAM_ID</string>
  <key>signingStyle</key><string>manual</string>
  <key>signingCertificate</key><string>$SIGN_IDENTITY</string>
  <key>provisioningProfiles</key><dict><key>$BUNDLE_ID</key><string>$PROFILE_NAME</string></dict>
  <key>stripSwiftSymbols</key><true/>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
PLIST
rm -rf build/QiYan.xcarchive build/export
xcodebuild -project QiYan.xcodeproj -scheme QiYan -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/QiYan.xcarchive archive \
  CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$TEAM_ID" CODE_SIGN_IDENTITY="$SIGN_IDENTITY" \
  PROVISIONING_PROFILE_SPECIFIER="$PROFILE_NAME" \
  OTHER_CODE_SIGN_FLAGS="--keychain $HOME/Library/Keychains/cgbuild.keychain-db" 2>&1 \
  | grep -E "error:|ARCHIVE (SUCCEEDED|FAILED)|\*\* " | head -100
xcodebuild -exportArchive -archivePath build/QiYan.xcarchive \
  -exportOptionsPlist build/exportOptions.plist -exportPath build/export 2>&1 | tail -5
ls -la build/export/*.ipa
/usr/libexec/PlistBuddy -c 'Print :ApplicationProperties:CFBundleShortVersionString' \
  -c 'Print :ApplicationProperties:CFBundleVersion' build/QiYan.xcarchive/Info.plist
echo "== ARCHIVE OK"
