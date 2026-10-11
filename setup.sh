#!/usr/bin/env bash
# Download sherpa-onnx AAR + models, and (optionally) push models to a connected phone.
#   ./setup.sh            download only (into ./models and app/libs)
#   ./setup.sh push       also adb push models to the app's external files dir
# Set HTTPS_PROXY if GitHub / Hugging Face need a proxy.
set -euo pipefail
cd "$(dirname "$0")"

SHERPA=1.13.8
REAZON=sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
HF=https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/resolve/449c9e6457b3632d328c6cbb78ae8e8e0c8059a5

fetch() { [ -s "$2" ] || { echo "↓ $2"; curl -fL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"; }; }

mkdir -p app/libs models/asr models/vad models/mt
# the build that links onnxruntime into libsherpa-onnx-jni.so: one 24 MB library instead of 22 + 4.8 MB
fetch "$GH/v$SHERPA/sherpa-onnx-static-link-onnxruntime-$SHERPA.aar" "app/libs/sherpa-onnx-static-link-onnxruntime-$SHERPA.aar"
fetch "$GH/asr-models/silero_vad.onnx" models/vad/silero_vad.onnx
fetch "$HF/Index-Translate-2B.Q4_K_M.gguf" models/mt/index-translate-2b.gguf
if [ ! -s models/asr/tokens.txt ]; then
  fetch "$GH/asr-models/$REAZON.tar.bz2" "models/$REAZON.tar.bz2"
  tar xjf "models/$REAZON.tar.bz2" -C models
  cp "models/$REAZON/encoder-epoch-35-avg-1.int8.onnx" models/asr/encoder.int8.onnx
  cp "models/$REAZON/decoder-epoch-35-avg-1.onnx" models/asr/decoder.onnx
  cp "models/$REAZON/joiner-epoch-35-avg-1.int8.onnx" models/asr/joiner.int8.onnx
  cp "models/$REAZON/tokens.txt" models/asr/tokens.txt
fi

if [ "${1:-}" = push ]; then
  DST=/sdcard/Android/data/io.github.korenkrita.simulsub/files
  for d in asr vad mt; do
    adb shell mkdir -p "$DST/$d"
    for f in models/$d/*; do adb push "$f" "$DST/$d/"; done
  done
  echo "pushed to $DST"
fi
