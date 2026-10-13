# 技术文档

面向想了解实现、参与开发或自己编译的读者。使用说明见 [项目首页](../README.md)。

| 文档 | 内容 |
|---|---|
| [architecture.md](architecture.md) | 整体流程：采集 → 静音检测 → 分句 → 识别 → 翻译 → 悬浮字幕；线程与过载处理 |
| [asr.md](asr.md) | 语音识别模型、为什么选 ReazonSpeech、与 SenseVoice 的对比数据 |
| [translation.md](translation.md) | 自己的翻译模型（BYOK）、关闭思考、提示词、上下文、场景术语、支持的语言 |
| [jev.md](jev.md) | 可选的 Jev 云端判断：智能断句、译文检查 |
| [privacy.md](privacy.md) | 哪些数据留在手机上、哪些会发到网络 |
| [logs.md](logs.md) | 使用记录（JSONL）格式、logcat 性能字段 |
| [build.md](build.md) | 编译、内置模型的来源、Mac 端到端测试、Mac 版运行与打包 |
