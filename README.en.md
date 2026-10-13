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

## Screenshots

<table>
  <tr>
    <td><img src="docs/images/screens/home.png" width="200" alt="Home"></td>
    <td><img src="docs/images/screens/overlay.png" width="300" alt="Floating subtitles (tap for the toolbar)"></td>
    <td><img src="docs/images/screens/caption.png" width="200" alt="Settings · Subtitles"></td>
    <td><img src="docs/images/screens/size.png" width="200" alt="Subtitle box size and kept sentences"></td>
    <td><img src="docs/images/screens/translate.png" width="200" alt="Settings · Translation"></td>
    <td><img src="docs/images/screens/page-dark.png" width="200" alt="Settings · Page (dark)"></td>
    <td><img src="docs/images/screens/records.png" width="200" alt="Settings · Records"></td>
    <td><img src="docs/images/screens/about.png" width="200" alt="Settings · About"></td>
    <td><img src="docs/images/screens/home-dark.png" width="200" alt="Home (dark)"></td>
  </tr>
  <tr>
    <td align="center">Home</td>
    <td align="center">Floating subtitles (tap for the toolbar)</td>
    <td align="center">Settings · Subtitles</td>
    <td align="center">Subtitle box size and kept sentences</td>
    <td align="center">Settings · Translation</td>
    <td align="center">Settings · Page (dark)</td>
    <td align="center">Settings · Records</td>
    <td align="center">Settings · About</td>
    <td align="center">Home (dark)</td>
  </tr>
</table>

## What it does

- **Works with most apps**: Bilibili, YouTube, streaming apps, the browser… any app that allows its playback to be captured; a few apps and DRM-protected video do not.
- **Floating subtitles**: shown over the video like movie subtitles; move them anywhere, change size, colour and background.
- **As people speak**: text appears while someone is talking and becomes a final subtitle when the sentence ends.
- **Speech stays on the phone**: audio is recognised on the device and **never uploaded**.
- **22 caption languages**: Chinese, English, Japanese, Korean, French, German, Spanish, Russian, Arabic, Thai, Vietnamese…
- **Bring your own translation model**: enter the address, model ID and key of any OpenAI-compatible service (e.g. DeepSeek, Qwen); you pay your provider for your own usage.

## Get started in three steps

1. **Set up translation**: tap the gear at the top right of the home screen, go to Settings → 翻译, fill in the service address, model ID and API key, then tap 测试连接 (test). The model must be able to turn thinking off and should answer fast (see the FAQ).
2. **Allow**: let the app hear other apps' sound and draw over other apps.
3. **Start**: tap 开始字幕, pick the app you want to watch, and go back to your video.

The speech recognition models ship inside the app; nothing else to download.

Subtitle size, colours and translation options are in Settings.

On the subtitle: **drag** to move · **tap** for the toolbar (text size, background, display mode, original text and timings on/off, lock, stop); while it is shown, **drag a corner mark** to resize · **locked**: position and size are fixed, a tap only shows an unlock button.

The app's interface is in Chinese.

## Install

- Android 10 or newer, arm64.
- Download the APK from the [Releases](../../releases) page and open it on the phone (allow "install unknown apps").
- From 0.4, the app checks for updates when opened. Read what's new and install, skip a version, or turn checks off; manual checking is always available under Settings → About.
- To build it yourself, see [docs/build.md](docs/build.md).

### Mac

- Apple silicon (M1 or later), macOS 14.2 or newer.
- Download `SimulSub-<version>-mac-arm64.dmg` from [Releases](../../releases), open it and drag 同传字幕 into Applications.
- The dmg is not notarized. The first time, macOS says it cannot verify the developer: right-click the app in Applications and choose Open, or click "Open Anyway" in System Settings → Privacy & Security.
- On the first start of captions, allow system audio recording (System Settings → Privacy & Security → Screen & System Audio Recording).
- The captions follow everything the Mac plays, no microphone needed; they float above all windows and can sit on an external display.
- Closing the main window keeps the captions on; the menu bar icon starts and stops them and reopens the window.
- Home and Settings are the same as on the phone. Update checks only open the download page; replace the app with the new dmg yourself.
- Settings and usage logs live in `~/Library/Application Support/SimulSub/`, keys in the keychain; deleting the app leaves them.

## FAQ

**Why are there no subtitles for some apps?**
Some apps (for example DRM-protected video) do not allow other apps to capture their audio. This is an Android restriction.

**Which spoken languages?**
Japanese and English. Choosing the right one on the home screen makes the translation more accurate.

**Is my audio uploaded?**
No. Audio is turned into text on the phone; for translation only the recognised text is sent to the service you entered. See [docs/privacy.md](docs/privacy.md).

**Which translation model should I use?**
One that can turn thinking (reasoning) off, otherwise every sentence takes a long wait; and preferably one that answers fast, so the subtitles keep up. The first time a model is used, the app finds out by itself how to turn its thinking off and remembers it.

## Acknowledgements

Built on these open-source projects:

- **[ReazonSpeech](https://github.com/reazon-research/ReazonSpeech)**: Japanese / English speech recognition (the app uses the sherpa-onnx export [reazonspeech-k2-v2-ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en)).
- **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**: speech recognition and voice activity detection ([Silero VAD](https://github.com/snakers4/silero-vad)) on the phone and the Mac.

The optional online Decisions model assistance (sentence ends, translation checks) uses **[TypeSafe Jev](https://typesafe.ai)**, a closed-source commercial cloud service (not open source), off by default. With a paid endpoint it is billed by usage: about US$0.04 per hour of speech (see [docs/jev.md](docs/jev.md)).

---

License: [MIT](LICENSE). The models and third-party libraries keep their own licenses.
