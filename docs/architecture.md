# 整体架构

```
其他 App 播放的声音（AudioPlaybackCapture，16 kHz 单声道）
  → Silero VAD：每 32 ms 一帧，给出“是否在说话”的概率
  → Segmenter：把连续语音切成句子
        说话期间每 0.7 s 出一次草稿（整段到目前为止的音频）
        停顿 0.4 s 定稿；一段最长 7 s，超长时在这段里最长的停顿处切开
        开启 Jev 断句时：5 s 时先问 Jev 是否说完，没说完就继续，最长到 10 s 强制切开
        太短的片段（< 1.2 s 语音）先等最多 1.5 s，看说话人是否接着说，接上就并成一句
  → ASR 线程：ReazonSpeech zipformer（sherpa-onnx，int8）
  → MT 线程：你自己填的 OpenAI 兼容翻译服务（BYOK，流式请求）
  → 悬浮窗：灰色 = 草稿（还会变），白色 = 定稿
```

主要代码都在 `app/src/main/java/io/github/korenkrita/simulsub/`：

| 文件 | 作用 |
|---|---|
| `CaptionService.kt` | 前台服务：采集、从 APK 的 assets 加载识别模型、创建流水线、管理悬浮窗 |
| `Segmenter.kt` | 分句（草稿 / 定稿 / 强制切分 / 短句等待 / Jev 判断等待） |
| `Pipeline.kt` | 识别与翻译两个工作线程、草稿合并、上下文、重复翻译复用、过载处理 |
| `Engines.kt` | sherpa-onnx 识别与 VAD 的封装（模型直接从 assets 读） |
| `Translation.kt` | 提示词、场景、语言列表、BYOK 翻译客户端与关闭思考的探测 |
| `Jev.kt` | Jev 客户端与两个判断问题 |
| `CaptionOverlayView.kt` | 悬浮字幕和工具栏 |
| `SessionLog.kt` | 每次使用的文字记录 |
| `MainActivity.kt` | 首页与设置页 |

### 桌面版（Mac、Windows）

`core/` 里的 `Segmenter`、`Pipeline`、`Translation`、`Jev` 等手机和电脑共用。桌面版在 `desktop/`（Compose Desktop，Mac 和 Windows 同一份代码），替换的是两头：

| 文件 | 作用 |
|---|---|
| `tools/mac-capture/SysAudio.swift` | `sysaudio`：Core Audio process tap 听整台 Mac 的声音，输出 16 kHz 单声道 float32 到标准输出 |
| `tools/win-capture/sysaudio.cpp` | Windows 的 `sysaudio.exe`：WASAPI loopback 听默认播放设备，输出格式同上 |
| `Session.kt` | 对应 `CaptionService`：读 `sysaudio`、从 App 自带的资源目录（`compose.application.resources.dir`）下的 `models/` 加载识别模型、建流水线、运行中改设置 |
| `Engines.kt` | sherpa-onnx 的 Mac 封装 |
| `Overlay.kt` | 透明、置顶、可拖动和缩放的字幕窗口与工具栏 |
| `App.kt` / `Ui.kt` | 主窗口（首页与设置）、菜单栏图标、检查更新 |
| `Prefs.kt` | 设置；key 在 Mac 的钥匙串、Windows 的 DPAPI 加密文件 |
| `Main.kt` | 平台差异（`isWindows`）、系统代理与直连、Windows 的绘制方式和证书库 |

## 草稿与定稿

- 草稿可以丢：队列里过时的草稿会被合并，只识别最新的一份。选「说完再出」时，草稿根本不识别、不翻译，省电也省流量。
- 定稿不会被悄悄丢掉：每句定稿都会识别和翻译，按顺序显示。
- 一句话里，如果草稿识别出的前半句在定稿里丢了（长句被模型吞掉开头），会把草稿的前半句补回来再接上后半句；接缝处的重复可以用「去掉重复的字」开关处理（默认关）。
- 同一段的草稿文字没变时，直接复用上一次的翻译，不重复调用翻译。

## 过载

手机跟不上时，处理是明确的，而不是越积越多：

1. 等待翻译的定稿累计超过 30 s 语音时，停止采集新的声音，并在悬浮窗提示。
2. 继续翻译已经收到的句子，最多 60 s（用户停止会提前结束）。
3. 之后取消剩下的句子，取消正在进行的翻译请求，释放模型，报告“全部翻译完”或“放弃了几句”。报告之后不会再有字幕出现。

## 延迟

每条字幕都会在 logcat 里记录各阶段耗时（见 [logs.md](logs.md)）。声音的采集时间优先用 AudioRecord 的硬件时间戳；设备不提供时退回读取时间，并在数值前加 `~`。
