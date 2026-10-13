#!/usr/bin/env bash
# Run the real pipeline (Silero VAD → Segmenter → Pipeline → ReazonSpeech → your BYOK translation service) on the
# Mac, streaming a 16 kHz mono WAV in real time. Needs ../../models (./setup.sh) and network for the first run.
# Translation: BYOK_URL, BYOK_MODEL and BYOK_KEY (an OpenAI-compatible service whose thinking can be turned off).
#   tools/mac-e2e/run.sh [file.wav]     default: synthesise Japanese test sentences with `say -v Kyoko`
#   tools/mac-capture/build/sysaudio 30 | tools/mac-e2e/run.sh -   live: what the Mac plays (see tools/mac-capture)
#   JAVA_OPTS=-DdropFinal=3 tools/mac-e2e/run.sh   negative check: must exit non-zero
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$HERE/../.."
W="$HERE/build"; mkdir -p "$W/native"
JH="${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
SHERPA=1.13.8
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA

# 1. sherpa-onnx JVM jar + macOS native libs
# A cached file is used only if it is a valid zip; otherwise it is re-downloaded (to .part, renamed on success).
fetch() {
  unzip -tq "$2" >/dev/null 2>&1 && return 0
  curl -fL --retry 3 -o "$2.part" "$1" && unzip -tq "$2.part" >/dev/null && mv -f "$2.part" "$2"
}
fetch "$GH/sherpa-onnx-jvm-$SHERPA.jar" "$W/sherpa-jvm.jar"
if [ ! -s "$W/native/libsherpa-onnx-jni.dylib" ]; then
  fetch "$GH/sherpa-onnx-native-lib-osx-aarch64-$SHERPA.jar" "$W/sherpa-osx.jar"
  (cd "$W/native" && unzip -o -q -j ../sherpa-osx.jar 'sherpa-onnx/native/osx-aarch64/*')
fi

# 2. compile the app's Pipeline/Segmenter + harness with the Kotlin compiler Gradle already downloaded
K=~/.gradle/caches/modules-2/files-2.1
jar() { find "$K/$1" -name "$2" | grep -v sources | head -1; }
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib/2.0.21 'kotlin-stdlib-2.0.21.jar')
# org.json ships with Android; the app's unit tests fetch the reference jar (testImplementation) and so does this
STD="$STD:$(jar org.json/json/20240303 'json-20240303.jar')"
# OkHttp (Jev / BYOK), as resolved by the app's Gradle build
STD="$STD:$(jar com.squareup.okhttp3/okhttp/4.12.0 'okhttp-4.12.0.jar'):$(jar com.squareup.okio/okio-jvm '*.jar')"
KC="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21 '*.jar'):$STD"
for a in kotlin-script-runtime kotlin-reflect kotlin-daemon-embeddable; do KC="$KC:$(jar org.jetbrains.kotlin/$a '*.jar')"; done
KC="$KC:$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm*.jar')"
[ -n "${STD%%:*}" ] && [ -n "${STD#*:}" ] || { echo "run ./gradlew :app:testDebugUnitTest once so Gradle fetches Kotlin"; exit 1; }
# a fresh output dir per run: stale classes of removed/renamed declarations break overload resolution
C="$(mktemp -d "$W/classes.XXXXXX")"
P="$ROOT/core/src/main/java/io/github/korenkrita/simulsub"
"$JH/bin/java" -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn \
  -cp "$STD:$W/sherpa-jvm.jar:$C" -d "$C" "$P/Pipeline.kt" "$P/Segmenter.kt" "$P/Translation.kt" "$P/Jev.kt" "$HERE/E2E.kt"

# 3. test audio
WAV="${1:-}"
if [ -z "$WAV" ]; then
  WAV="$W/sample.wav"
  python3 "$HERE/make_sample.py" "$W" "$WAV"
fi

"$JH/bin/java" ${JAVA_OPTS:-} -Djava.library.path="$W/native" -Dwav="$WAV" -Dmodels="$ROOT/models" \
  -cp "$C:$STD:$W/sherpa-jvm.jar" E2EKt
# exit status is the JVM's: non-zero if a final was refused, lost, reordered or a worker failed
