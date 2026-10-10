# jp-subtitle

Minimal Android app: captures another app's Japanese audio (screen-share / MediaProjection),
recognises and translates it **fully on the phone**, and shows Chinese subtitles in a floating window.
Target device: realme GT5 Pro (Snapdragon 8 Gen 3, arm64).

```
AudioPlaybackCapture 16 kHz mono
  → Silero VAD (per 32 ms frame)
  → Segmenter: draft every 0.7 s while talking, final after 0.4 s pause (forced cut ≤ 7 s, inside the longest pause)
  → ASR thread: ReazonSpeech zipformer (sherpa-onnx, int8)
  → MT thread:  Index-Translate-2B Q4_K_M (llama.cpp, CPU; scene background + term list in the prompt)
  → overlay: grey = draft (may change), white = final
```

Drafts are disposable (stale queued drafts are merged), finals are never dropped silently. If finals pile
up beyond 30 s of speech the app stops capturing, keeps translating the sentences it already accepted
(up to 60 s, cut short by stopping), then cancels the rest, waits for any in-progress native call to
return (not interruptible, so this wait has no hard limit), releases the models and reports either "all
translated" or how many were abandoned — nothing arrives after that report. The captions stay until you
tap the subtitle and press ✕.

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35, NDK 27.2.12479018, CMake 3.22.1).

```bash
git clone --recursive <this repo>            # llama.cpp is a submodule
./setup.sh                                   # sherpa-onnx AAR + models (~1.4 GB) into app/libs, models/
export JAVA_HOME=<jdk17> ANDROID_HOME=~/Library/Android/sdk
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Behind a proxy: `HTTPS_PROXY=... ./setup.sh` and `./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 ...`.

## Install on the phone

No adb needed: copy `app/build/outputs/apk/release/app-release.apk` to the phone (cable, cloud drive,
chat app) and open it; allow "install unknown apps" for the app you opened it from. Updates install over
the old version (same signing key).

Models (~1.21 GB) are downloaded **by the app**: open "日语字幕" → 下载模型. The system DownloadManager
fetches them from pinned Hugging Face revisions / the sherpa-onnx GitHub release (keeps going in the
background); each file is checked for exact size and SHA-256 before it is used, so a partial or corrupt
download is never loaded. Needs access to huggingface.co and github.com. Files copied by an older version
(`./setup.sh push` over adb still works) are hash-checked once in the background.

Then 开始字幕 → allow audio / overlay → choose the app to share (or the whole screen).

Overlay: drag to move · drag the corner dot to change width · tap for the toolbar (A−/A+ size,
background −/+ opacity, lock position, ✕ stop). Size, opacity, width, Japanese line and timing line can
also be set on the home screen, with a live preview; changes apply to a running overlay immediately.
The notification's 停止 also stops.

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
timestamp; for frames where the device does not provide one, read time is used and that caption's value
is prefixed with `~` (`hal=false` in the log) (it then underestimates by the time audio sat in the AudioRecord buffer).
The overlay also shows asr / mt times and a backlog warning.

## Mac end-to-end check

`tools/mac-e2e/run.sh [file.wav]` (exit 0 only if every accepted final is delivered, in order) runs the same Pipeline/Segmenter/JNI code with the real models on
macOS (CPU), streaming the WAV in real time. Without an argument it synthesises a 6-sentence regression
clip with `say -v Kyoko`. `JAVA_OPTS=-DdropFinal=3 tools/mac-e2e/run.sh` is the negative check (must fail). A watchdog halts the JVM
with exit 2 after `-DdeadlineS` (default 180 s), so a hung native call fails the gate instead of hanging it. `tools/mac-e2e/sample-run.log` is a reference run (M-series Mac, 4 MT threads):
finals arrive 0.6-0.8 s after a sentence ends (2.5 s once: that sentence hit the length limit, was cut
back at its longest pause; the finalised part's last sample was ~2.1 s old when the cut was triggered); ASR 35-60 ms, MT 220-390 ms.
These are Mac numbers; the phone has not been measured yet.

## Decisions (measured, see git log)

- **ASR: ReazonSpeech, not SenseVoice.** SenseVoice int8 with `language=ja` dropped most kana on the test clip.
- **ASR input padded with 0.3 s silence.** ReazonSpeech drops the first word when speech starts at sample 0.
- **MT: Index-Translate-2B, not HY-MT.** On 37 hard sentences from a VTuber stream it distorted meaning in ~5 vs
  ~7 (Hy-MT2-1.8B) and handled stream slang better; it is ~45 % slower on the Mac. Prompt = the model's own
  instTrans format (terms found in the sentence as a hard constraint, the scene as a soft one), greedy, no penalty.
- **No cross-sentence MT context.** HY-MT's context template made finals worse (re-translated the previous
  sentence, changed meaning), so each sentence is translated alone.
- Models are pushed with adb instead of bundled, to keep the APK at ~40 MB.
