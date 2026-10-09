#!/usr/bin/env bash
# Run the real pipeline (Silero VAD → Segmenter → Pipeline → ReazonSpeech → HY-MT) on the Mac,
# streaming a 16 kHz mono WAV in real time. Needs ../../models (./setup.sh) and network for the first run.
#   tools/mac-e2e/run.sh [file.wav]     default: synthesise Japanese test sentences with `say -v Kyoko`
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$HERE/../.."
W="$HERE/build"; mkdir -p "$W/native" "$W/classes"
JH="${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
SHERPA=1.13.8
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA

# 1. sherpa-onnx JVM jar + macOS native libs
[ -s "$W/sherpa-jvm.jar" ] || curl -fL -o "$W/sherpa-jvm.jar" "$GH/sherpa-onnx-jvm-$SHERPA.jar"
if [ ! -s "$W/native/libsherpa-onnx-jni.dylib" ]; then
  curl -fL -o "$W/sherpa-osx.jar" "$GH/sherpa-onnx-native-lib-osx-aarch64-$SHERPA.jar"
  (cd "$W/native" && unzip -o -q -j ../sherpa-osx.jar 'sherpa-onnx/native/osx-aarch64/*')
fi

# 2. llama.cpp for macOS (CPU only, like the phone) + the same JNI source as the app
CMAKE="${CMAKE:-$(command -v cmake || echo "$HOME/Library/Android/sdk/cmake/3.22.1/bin/cmake")}"
L="$ROOT/third_party/llama.cpp"
if [ ! -s "$W/llama/bin/libllama.dylib" ]; then
  "$CMAKE" -S "$L" -B "$W/llama" -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF -DGGML_METAL=OFF -DCMAKE_BUILD_TYPE=Release >/dev/null
  "$CMAKE" --build "$W/llama" -j 8 --target llama >/dev/null
fi
sed 's/Java_dev_krita_jpsub_LlamaTranslator_/Java_dev_krita_jpsub_MacLlama_/' "$ROOT/app/src/main/cpp/translator_jni.cpp" > "$W/mac_jni.cpp"
clang++ -std=c++17 -O2 -shared -fPIC -I"$JH/include" -I"$JH/include/darwin" -I"$L/include" -I"$L/ggml/include" \
  "$W/mac_jni.cpp" -L"$W/llama/bin" -lllama -Wl,-rpath,"$W/llama/bin" -o "$W/native/libjpsub_mac.dylib"

# 3. compile the app's Pipeline/Segmenter + harness with the Kotlin compiler Gradle already downloaded
K=~/.gradle/caches/modules-2/files-2.1
jar() { find "$K/$1" -name "$2" | grep -v sources | head -1; }
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib/2.0.21 'kotlin-stdlib-2.0.21.jar')
KC="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21 '*.jar'):$STD"
for a in kotlin-script-runtime kotlin-reflect kotlin-daemon-embeddable; do KC="$KC:$(jar org.jetbrains.kotlin/$a '*.jar')"; done
KC="$KC:$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm*.jar')"
[ -n "$STD" ] || { echo "run ./gradlew :app:testDebugUnitTest once so Gradle fetches Kotlin"; exit 1; }
"$JH/bin/javac" -d "$W/classes" "$HERE/MacLlama.java"
P="$ROOT/app/src/main/java/dev/krita/jpsub"
"$JH/bin/java" -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn \
  -cp "$STD:$W/sherpa-jvm.jar:$W/classes" -d "$W/classes" "$P/Pipeline.kt" "$P/Segmenter.kt" "$HERE/E2E.kt"

# 4. test audio
WAV="${1:-}"
if [ -z "$WAV" ]; then
  WAV="$W/sample.wav"
  python3 "$HERE/make_sample.py" "$W" "$WAV"
fi

"$JH/bin/java" -Djava.library.path="$W/native" -Dwav="$WAV" -Dmodels="$ROOT/models" \
  -cp "$W/classes:$STD:$W/sherpa-jvm.jar" E2EKt 2>/dev/null | grep -E '^ +[0-9]|^---|^[^ a-z_.]'
