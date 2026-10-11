<p align="center">
  <img src="docs/images/icon.svg" width="112" alt="Simul Subtitle">
</p>

<h1 align="center">Simul Subtitle（同传字幕）</h1>

<p align="center">
  Live subtitles on your Android phone for Japanese and English videos and streams,<br>
  translated into Chinese or 21 other languages, on top of any app.
</p>

<p align="center">
  <a href="README.md">中文</a> ·
  <a href="docs/README.md">Technical docs (Chinese)</a>
</p>

---

## What it does

- **Works with most apps**: Bilibili, YouTube, streaming apps, the browser… any app that allows its playback to be captured; a few apps and DRM-protected video do not.
- **Floating subtitles**: shown over the video like movie subtitles; move them anywhere, change size, colour and background.
- **As people speak**: text appears while someone is talking and becomes a final subtitle when the sentence ends.
- **Speech stays on the phone**: audio is recognised on the device and **never uploaded**.
- **22 caption languages**: Chinese, English, Japanese, Korean, French, German, Spanish, Russian, Arabic, Thai, Vietnamese…
- **Free**: uses bilibili's free public Index-Translate service by default; or download the translation model and translate on the phone, offline.

## Get started in three steps

1. **Download**: on first launch tap 下载模型 (about 80 MB, once).
2. **Allow**: let the app hear other apps' sound and draw over other apps.
3. **Start**: tap 开始字幕, pick the app you want to watch, and go back to your video.

Subtitle size, colours and translation options are under 字幕大小、颜色和更多设置 on the home screen.

On the subtitle: **drag** to move · **drag the corner dot** to resize · **tap** for the toolbar (text size, background, display mode, lock, stop).

The app's interface is in Chinese.

## Install

- Android 10 or newer, arm64. On-device translation additionally needs an ARMv8.6 CPU (dotprod, i8mm, fp16), e.g. Snapdragon 8 Gen 3 (the chip it is used on); if unsure, use online translation.
- Once published, download the APK from this repository's [Releases](https://github.com/KorenKrita/simul-subtitle/releases) page and open it on the phone (allow "install unknown apps").
- To build it yourself, see [docs/build.md](docs/build.md).

## FAQ

**Why are there no subtitles for some apps?**
Some apps (for example DRM-protected video) do not allow other apps to capture their audio. This is an Android restriction.

**Which spoken languages?**
Japanese and English. Choosing the right one on the home screen makes the translation more accurate.

**Is my audio uploaded?**
No. Audio is turned into text on the phone; with online translation only the recognised text is sent to the translation service. See [docs/privacy.md](docs/privacy.md).

## Acknowledgements

- **[Index-Translate](https://github.com/bilibili/Index-Translate)** by bilibili: the translation model at the heart of this app. Online translation uses bilibili's free public Index-Translate API; on-device translation uses Index-Translate-2B. Many thanks to the Index team for open-sourcing the models and offering the free service.
- **[ReazonSpeech](https://research.reazon.jp/projects/ReazonSpeech/)**: Japanese / English speech recognition.
- **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**: on-device speech recognition and voice activity detection (Silero VAD).
- **[llama.cpp](https://github.com/ggml-org/llama.cpp)**: on-device translation model runtime.
- **[TypeSafe Jev](https://typesafe.ai)**: optional AI assistance (sentence ends, translation checks).

---

Project: <https://github.com/KorenKrita/simul-subtitle>
