# 语音识别

## 现在用的模型

ReazonSpeech k2 v2 ja-en，App 下载的是 sherpa-onnx 导出版 [csukuangfj/reazonspeech-k2-v2-ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en)（int8；原始模型由 [ReazonSpeech](https://github.com/reazon-research/ReazonSpeech) 发布），一个模型同时识别日语和英语。

- 文件约 77 MB（encoder 70.9 MB + decoder 5.2 MB + joiner 1.0 MB + tokens）。
- 输入前后各补 0.3 s 静音：语音从第 0 个采样就开始时，这个模型会丢掉第一个词。
- 这个模型没有“指定语言”的参数，也不输出语言标签。首页选的「视频里说的是」不改变识别本身，只告诉翻译和 Jev 原文是什么语言。
- 已知问题：日语里偶尔有片段被识别成英语。

## 与 SenseVoice-Small 的对比

测试条件：Mac M4 Pro，2 线程，int8，sherpa-onnx 1.13.8；整句离线解码，前后补 0.3 s 静音；FLEURS 测试集（朗读语音，不是直播）日语 650 句、英语 647 句。日语算 CER（NFKC，去标点和空格），英语算 WER（小写，去标点）。

| | ReazonSpeech（现用） | SenseVoice-Small (2024-07-17) |
|---|---|---|
| 日语 CER | 8.48% | 7.52% |
| 英语 WER | 22.66% | 8.33% |
| 语言标签 | 无 | 有（ja 649/650，en 647/647） |
| 输出 | 日语无标点；英语全大写、无标点、数字写成单词 | 有标点、大小写、阿拉伯数字 |
| 解码耗时 / 音频时长 | 0.0075 | 0.0164（约 2.2 倍） |
| 每句解码中位数（日 / 英） | 93 / 71 ms | 203 / 157 ms |
| 模型文件 | 77 MB | 239 MB |
| 加载后进程内存增加（Mac） | 约 +214 MiB | 约 +837…973 MiB |

说明：

- 错误率用上面的自定义归一化计算，不能和公开榜单直接比较；结论只针对这个朗读语音集：SenseVoice 英语明显更好，日语略好。
- 耗时只统计 `decode_stream()`，是 Mac 上的数字，手机上没有测。
- SenseVoice 设置 `language="ja"` 时，英语片段仍被识别成英语，这个参数不能强制语言。
- 在一段直播录音上抽查，两个模型在短片段上分歧很大，没有参考答案，无法判断谁对。

现在保留 ReazonSpeech：日语差距小，速度快一倍多，内存和下载体积小很多；英语较弱是已知取舍。

## 可能的改进

- 只听日语时换用纯日语的 [reazonspeech-k2-v2](https://huggingface.co/reazon-research/reazonspeech-k2-v2)，评估能否减少日语被识别成英语的情况（还没有测试）。
