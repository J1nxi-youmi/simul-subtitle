#!/usr/bin/env bash
# Builds the Windows app (x64) as a portable zip: desktop/build/compose/binaries/main/SimulSub-<version>-windows-x64.zip
#   tools/win-app/build.sh
# Runs on Windows in Git Bash with a JDK 17 in JAVA_HOME and MSVC's cl.exe on PATH (a Developer prompt, or
# .github/workflows/windows.yml). The zip holds a folder SimulSub/ with SimulSub.exe, a JRE, the native libraries, the
# audio helper and the speech models (./models, see ./setup.sh); unzip it anywhere and run SimulSub.exe.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$HERE/../.."
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
W="$HERE/build"; R="$W/resources/windows-x64"
rm -rf "$R"; mkdir -p "$R"

# 1. sherpa-onnx JVM jar + its Windows libraries (the same version as the Mac app)
SHERPA=1.13.8
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA
fetch() {
  unzip -tq "$2" >/dev/null 2>&1 && return 0
  curl -fL --retry 3 -o "$2.part" "$1" && unzip -tq "$2.part" >/dev/null && mv -f "$2.part" "$2"
}
fetch "$GH/sherpa-onnx-jvm-$SHERPA.jar" "$W/sherpa-jvm.jar"
fetch "$GH/sherpa-onnx-native-lib-win-x64-$SHERPA.jar" "$W/sherpa-win.jar"
(cd "$R" && unzip -o -q -j "$W/sherpa-win.jar" 'sherpa-onnx/native/win-x64/*')

# 2. the speech models, checked by ./setup.sh (it fetches what is missing)
"$ROOT/setup.sh" >/dev/null
mkdir -p "$R/models"
cp -R "$ROOT/models/asr" "$ROOT/models/vad" "$R/models/"

# 3. the audio helper (static C runtime: nothing else to install). MSVC's own folder first on PATH: Git Bash has a
# link.exe of its own that cl would otherwise run; MSYS_NO_PATHCONV keeps /O2 and the like as they are
CL="$(command -v cl)" || { echo "cl.exe not on PATH: run from a Developer prompt (or msvc-dev-cmd)"; exit 1; }
(cd "$W" && PATH="$(dirname "$CL"):$PATH" MSYS_NO_PATHCONV=1 cl /nologo /O2 /EHsc /MT /W3 \
  "$(cygpath -w "$ROOT/tools/win-capture/sysaudio.cpp")" ole32.lib /Fe:"$(cygpath -w "$R/sysaudio.exe")")

# 4. the app folder, zipped under the name the user sees
cd "$ROOT"
VER="$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)"
./gradlew -q :desktop:createDistributable -Psimulsub.dist="$(cygpath -w "$W")"
APP=desktop/build/compose/binaries/main/app/SimulSub
[ -f "$APP/SimulSub.exe" ] && [ -f "$APP/app/resources/sysaudio.exe" ] && [ -f "$APP/app/resources/sherpa-onnx-jni.dll" ] \
  || { echo "the app folder is missing a file"; exit 1; }
STAGE="$W/zip"; rm -rf "$STAGE"; mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ZIP="desktop/build/compose/binaries/main/SimulSub-$VER-windows-x64.zip"
rm -f "$ZIP"
# Windows' own bsdtar: entries with "/" (Windows PowerShell's Compress-Archive writes "\", which other unzippers keep
# in the file name)
(cd "$STAGE" && /c/Windows/System32/tar.exe -a -c -f "$(cygpath -w "$ROOT/$ZIP")" SimulSub)
sha256sum "$ZIP"
ls -la "$ZIP"
