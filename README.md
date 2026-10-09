# jp-subtitle

Minimal Android app: captures another app's Japanese audio (screen-share / MediaProjection),
recognises and translates it **fully on the phone**, and shows Chinese subtitles in a floating window.
Target device: realme GT5 Pro (Snapdragon 8 Gen 3, arm64).

```
AudioPlaybackCapture 16 kHz mono
  → Silero VAD (per 32 ms frame)
  → Segmenter: draft every 0.7 s while talking, final after 0.4 s pause (forced cut ≤ 7 s, inside the longest pause)
  → ASR thread: ReazonSpeech zipformer (sherpa-onnx, int8)
  → MT thread:  HY-MT1.5-1.8B Q4_K_M (llama.cpp, CPU)
  → overlay: grey = draft (may change), white = final
```

Drafts are disposable (stale queued drafts are merged), finals are never dropped silently. If finals pile
up beyond 30 s of speech the app stops capturing, keeps translating the sentences it already accepted
(up to 60 s), then reports either "all translated" or how many were abandoned. Long-press to end.

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35, NDK 27.2.12479018, CMake 3.22.1).

```bash
git clone --recursive <this repo>            # llama.cpp is a submodule
./setup.sh                                   # sherpa-onnx AAR + models (~1.2 GB) into app/libs, models/
export JAVA_HOME=<jdk17> ANDROID_HOME=~/Library/Android/sdk
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Behind a proxy: `HTTPS_PROXY=... ./setup.sh` and `./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 ...`.

## Install on the phone

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
./setup.sh push                              # copies models to /sdcard/Android/data/dev.krita.jpsub/files
```

Open "日语字幕" → 开始 → allow audio / overlay → choose the app to share (or the whole screen).
Drag the subtitle vertically to move it, long-press it (or the notification's 停止) to stop.

## Measuring

Every caption is logged with a per-stage breakdown:

```bash
adb logcat -s jpsub                          # seg=3.4F audio=3072 wait=319 asrQ=9 asr=35 mtQ=13 mt=225 mtDone=601 | …
                                             # shown seg=3.4F update=640ms hal=true
adb pull /sdcard/Android/data/dev.krita.jpsub/files/timing.csv
```

`wait` = end-of-speech detection, `mtDone` = last captured sample → translation done.
`update` (and 延迟 in the overlay) = last captured sample → overlay text updated for that caption revision;
it is a text-update time, not a measured display frame. Capture time comes from the AudioRecord HAL
timestamp; if the device does not provide one, read time is used and the overlay prefixes the value
with `~` (it then underestimates by the time audio sat in the AudioRecord buffer).
The overlay also shows asr / mt times and a backlog warning.

## Mac end-to-end check

`tools/mac-e2e/run.sh [file.wav]` (exit 0 only if every accepted final is delivered, in order) runs the same Pipeline/Segmenter/JNI code with the real models on
macOS (CPU), streaming the WAV in real time. Without an argument it synthesises a 6-sentence regression
clip with `say -v Kyoko`. `JAVA_OPTS=-DdropFinal=3 tools/mac-e2e/run.sh` is the negative check (must fail). `tools/mac-e2e/sample-run.log` is a reference run (M-series Mac, 4 MT threads):
finals arrive 0.6-0.8 s after a sentence ends (2.5 s once: that sentence hit the length limit, was cut
back at its longest pause, and the remainder waited ~2.1 s in the segmenter); ASR 35-60 ms, MT 220-390 ms.
These are Mac numbers; the phone has not been measured yet.

## Decisions (measured, see git log)

- **ASR: ReazonSpeech, not SenseVoice.** SenseVoice int8 with `language=ja` dropped most kana on the test clip.
- **ASR input padded with 0.3 s silence.** ReazonSpeech drops the first word when speech starts at sample 0.
- **No cross-sentence MT context.** HY-MT's context template made finals worse (re-translated the previous
  sentence, changed meaning), so each sentence is translated alone.
- Models are pushed with adb instead of bundled, to keep the APK at ~40 MB.
