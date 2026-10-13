#!/usr/bin/env bash
# Builds the Mac app (Apple silicon only) and its unsigned dmg: desktop/build/compose/binaries/main/dmg/*.dmg
#   tools/mac-app/build.sh
# The app carries a JRE, its native libraries and the audio helper; models are downloaded on first start into
# ~/Library/Application Support/SimulSub/models. Needs a JDK 17 in JAVA_HOME and Xcode command line tools.
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

# 2. llama.cpp, static and CPU only (as on the phone), linked into the one JNI library the app loads
CMAKE="${CMAKE:-$(command -v cmake || echo "$HOME/Library/Android/sdk/cmake/3.22.1/bin/cmake")}"
L="$ROOT/third_party/llama.cpp"
LREV="$(git -C "$L" rev-parse HEAD)"
LB="$W/llama"
if [ ! -s "$LB/src/libllama.a" ] || [ "$(cat "$LB/REV" 2>/dev/null)" != "$LREV" ]; then
  "$CMAKE" -S "$L" -B "$LB" -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=14.2 \
    -DBUILD_SHARED_LIBS=OFF -DGGML_METAL=OFF -DGGML_BLAS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod \
    -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_CURL=OFF >/dev/null
  "$CMAKE" --build "$LB" -j 8 --target llama >/dev/null
  echo "$LREV" > "$LB/REV"
fi
sed 's/Java_io_github_korenkrita_simulsub_LlamaTranslator_/Java_io_github_korenkrita_simulsub_MacLlama_/' \
  "$ROOT/app/src/main/cpp/translator_jni.cpp" > "$W/mac_jni.cpp"
LIBS="$(find "$LB" -name 'libllama.a' -o -name 'libggml*.a' | tr '\n' ' ')"
# shellcheck disable=SC2086
clang++ -std=c++17 -O2 -shared -fPIC -arch arm64 -mmacosx-version-min=14.2 \
  -I"$JH/include" -I"$JH/include/darwin" -I"$L/include" -I"$L/ggml/include" \
  "$W/mac_jni.cpp" $LIBS -framework Accelerate -install_name @rpath/libjpsub_mac.dylib -o "$R/libjpsub_mac.dylib"
if otool -L "$R/libjpsub_mac.dylib" | grep -qE 'llama|ggml'; then echo "libjpsub_mac still links llama dynamically"; exit 1; fi

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
