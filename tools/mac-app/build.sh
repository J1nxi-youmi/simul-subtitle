#!/usr/bin/env bash
# Builds the Mac app (Apple silicon only) and its unsigned dmg: desktop/build/compose/binaries/main/dmg/*.dmg
#   tools/mac-app/build.sh
# The app carries a JRE, its native libraries, the audio helper and the speech models (./models, see ./setup.sh).
# Needs a JDK 17 in JAVA_HOME and Xcode command line tools.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$HERE/../.."
JH="${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
W="$HERE/build"; R="$W/resources/macos-arm64"
[ -d "$R" ] && trash "$R"
mkdir -p "$R"

# 1. sherpa-onnx JVM jar + its macOS libraries (tools/mac-e2e fetches the same version)
SHERPA=1.13.8
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA
fetch() {
  unzip -tq "$2" >/dev/null 2>&1 && return 0
  curl -fL --retry 3 -o "$2.part" "$1" && unzip -tq "$2.part" >/dev/null && mv -f "$2.part" "$2"
}
fetch "$GH/sherpa-onnx-jvm-$SHERPA.jar" "$W/sherpa-jvm.jar"
fetch "$GH/sherpa-onnx-native-lib-osx-aarch64-$SHERPA.jar" "$W/sherpa-osx.jar"
(cd "$R" && unzip -o -q -j "$W/sherpa-osx.jar" 'sherpa-onnx/native/osx-aarch64/*')

# 2. the speech models, checked by ./setup.sh (it fetches what is missing)
"$ROOT/setup.sh" >/dev/null
mkdir -p "$R/models"
cp -R "$ROOT/models/asr" "$ROOT/models/vad" "$R/models/"

# 3. the audio helper, Apple silicon only
xcrun swiftc -O -target arm64-apple-macos14.2 "$ROOT/tools/mac-capture/SysAudio.swift" -o "$R/sysaudio"

# 4. the app and its dmg, named after the release (the bundle's own version is 1.<versionCode>, see desktop/build.gradle.kts)
cd "$ROOT"
OUT=desktop/build/compose/binaries/main/dmg
[ -d "$OUT" ] && trash "$OUT"
VER="$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)"
./gradlew -q :desktop:createDistributable -Psimulsub.dist="$W"
# Finder's "Get Info" shows the release's version, not jpackage's 1.<versionCode>; re-sign (ad hoc) after the edit
APP=desktop/build/compose/binaries/main/app/SimulSub.app
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VER" "$APP/Contents/Info.plist"
# Compose copies app resources without their mode: the helper must stay executable, or 开始字幕 cannot run it
chmod 755 "$APP/Contents/app/resources/sysaudio"
codesign --force --deep --sign - "$APP"
[ -x "$APP/Contents/app/resources/sysaudio" ] || { echo "sysaudio is not executable in the app"; exit 1; }
mkdir -p "$OUT"
DMG="$OUT/SimulSub-$VER-mac-arm64.dmg"
# the usual layout: the app and a link to /Applications to drag it onto
STAGE="$W/dmg"; [ -d "$STAGE" ] && trash "$STAGE"; mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"; ln -s /Applications "$STAGE/Applications"
hdiutil create -volname "同传字幕" -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$DMG" >/dev/null
shasum -a 256 "$DMG"
ls -la "$DMG"
