#!/usr/bin/env bash
# Fetches what the build needs but git does not hold: the sherpa-onnx AAR and the speech models (~75 MB), checked by
# size + SHA-256. The models go into ./models (the Mac app and tools/mac-e2e take them from there) and are copied into
# app/src/main/assets/models, so the APK carries them.
#   ./setup.sh
# Set HTTPS_PROXY if GitHub / Hugging Face need a proxy.
set -euo pipefail
cd "$(dirname "$0")"

SHERPA=1.13.8
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
ASR=https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870
# DIY: SenseVoice 多语言（中/英/日/韩/粤，自动判语种），给中日混说的场景用
SV=https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main

fetch() { [ -s "$2" ] || { echo "↓ $2"; curl -fL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"; }; }

# path in models/, official URL, size, SHA-256
MODELS=(
  "asr/encoder.int8.onnx $ASR/encoder-epoch-35-avg-1.int8.onnx 70876409 ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268"
  "asr/decoder.onnx $ASR/decoder-epoch-35-avg-1.onnx 5165084 90b60985672df5023e187e9d25aa2dc93abc4286bfacf71a35a78a6b76d88df5"
  "asr/joiner.int8.onnx $ASR/joiner-epoch-35-avg-1.int8.onnx 1033417 c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495"
  "asr/tokens.txt $ASR/tokens.txt 26631 144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24"
  "vad/silero_vad.onnx $GH/asr-models/silero_vad.onnx 643854 9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
  "asr_sv/model.int8.onnx $SV/model.int8.onnx 239233841 c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
  "asr_sv/tokens.txt $SV/tokens.txt 315894 f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
)
# shasum on the Mac, sha256sum in Git Bash on Windows (tools/win-app)
sha() { if command -v shasum >/dev/null; then shasum -a 256 "$1"; else sha256sum "$1"; fi; }
ok() { [ "$(wc -c < "$1" | tr -d ' ')" = "$2" ] && [ "$(sha "$1" | cut -d' ' -f1)" = "$3" ]; }

mkdir -p app/libs models/asr models/asr_sv models/vad
# the build that links onnxruntime into libsherpa-onnx-jni.so: one 24 MB library instead of 22 + 4.8 MB
fetch "$GH/v$SHERPA/sherpa-onnx-static-link-onnxruntime-$SHERPA.aar" "app/libs/sherpa-onnx-static-link-onnxruntime-$SHERPA.aar"

for m in "${MODELS[@]}"; do
  read -r path url size sha <<< "$m"
  f="models/$path"
  if [ -s "$f" ] && ok "$f" "$size" "$sha"; then continue; fi
  echo "↓ $f"
  curl -fL --retry 3 -o "$f.part" "$url"
  ok "$f.part" "$size" "$sha" || { echo "✗ $f: wrong size or checksum" >&2; exit 1; }
  mv "$f.part" "$f"
done

# the models' licences travel with them (ReazonSpeech: Apache-2.0, Silero VAD: MIT)
fetch https://www.apache.org/licenses/LICENSE-2.0.txt models/asr/LICENSE
fetch https://raw.githubusercontent.com/snakers4/silero-vad/1e261b036686cd0017d500ee96acd1c4ba572a9d/LICENSE models/vad/LICENSE

A=app/src/main/assets/models
mkdir -p "$A/asr" "$A/asr_sv" "$A/vad"
for path in $(for m in "${MODELS[@]}"; do echo "${m%% *}"; done) asr/LICENSE vad/LICENSE; do
  cmp -s "models/$path" "$A/$path" || cp "models/$path" "$A/$path"
done
echo "models ready: models/ and $A"
