# 使用记录与调试

## 使用记录（JSONL）

默认不记录。在「设置 → 使用记录」打开「记录每次使用」后，从下次开始字幕起，每次生成一个文件：`Android/data/io.github.korenkrita.simulsub/files/logs/session-YYYYMMDD-HHMMSS.jsonl`。关闭后不再生成新记录，已有的记录保留，可以手动删除。

- 每行一个 JSON 对象，`ev` 是事件类型，`t` 是单调时钟毫秒。
- 内容包括：会话设置（`session_start`：场景、语言、Jev、上下文句数等；实际使用的翻译服务（地址、模型、关闭思考的参数，不含 key）记在 `byok` 事件里，第一次使用时的探测记在 `byok_probe`；字幕运行中改了设置会再记一条 `config_change`，`setup` 是新设置的编号，之后每次翻译记录里的 `setup` 说明它用的是哪一套）、每次识别结果、每次翻译的完整提示词、去掉思考内容和 key 后的译文、HTTP 状态、计时和 usage（含服务回报的 `cached_tokens`；服务的回复原文不记录）、每次 Jev 请求和原始回答、每条显示出来的字幕。
- 不含声音，不含 API key。
- Mac 版的记录在 `~/Library/Application Support/SimulSub/logs/`。
- 写入在后台线程进行，不会拖慢字幕；写的过程中文件名以 `.part` 结尾，正常结束才改名，所以中断的记录不会被当成完整记录。
- 在 App 的「设置 → 使用记录」里可以分享或删除。

## logcat

```bash
adb logcat -s jpsub
# seg=3.4F audio=3072 wait=319 asrQ=9 asr=35 mtQ=13 mt=225 mtDone=601 ja=18 zh=12
# shown seg=3.4F update=640ms hal=true
```

| 字段 | 含义 |
|---|---|
| `seg=段.版本F/d` | 段号、版本号，F = 定稿，d = 草稿 |
| `audio` | 这段音频时长（ms） |
| `wait` | 检测到说完所用时间 |
| `asrQ` / `asr` | 识别排队 / 识别耗时 |
| `mtQ` / `mt` | 翻译排队 / 翻译耗时 |
| `mtDone` | 最后一个采样 → 翻译完成 |
| `update` | 最后一个采样 → 悬浮窗文字更新（不是实际显示帧） |
| `hal` | 是否用了硬件时间戳；false 时数值前加 `~`，会偏小 |
| `ja` / `zh` | 原文 / 译文的字数；logcat 不输出原文和译文本身 |

设置里打开「显示识别和翻译用时」后，悬浮字幕下方也会显示这些数字。
