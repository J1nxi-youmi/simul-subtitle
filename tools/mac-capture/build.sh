#!/usr/bin/env bash
# Builds sysaudio (system audio capture, macOS 14.2+) for Apple silicon and Intel.
#   tools/mac-capture/build.sh && tools/mac-capture/build/sysaudio 30 | tools/mac-e2e/run.sh -
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; mkdir -p "$HERE/build"
for arch in arm64 x86_64; do
  xcrun swiftc -O -target "$arch-apple-macos14.2" "$HERE/SysAudio.swift" -o "$HERE/build/sysaudio-$arch"
done
lipo -create "$HERE/build/sysaudio-arm64" "$HERE/build/sysaudio-x86_64" -output "$HERE/build/sysaudio"
lipo -info "$HERE/build/sysaudio"
