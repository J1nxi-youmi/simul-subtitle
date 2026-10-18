# 同声传译（diy版）

个人自用的 Android 悬浮字幕，基于 [KorenKrita/simul-subtitle](https://github.com/KorenKrita/simul-subtitle) 修改。

## 特点

- **识别**：SenseVoice，中/英/日/韩/粤一部模型全认，可锁语种也可自动判断
- **翻译**：填自己的 OpenAI 兼容接口。Qwen-MT 走原生协议，语言和术语用结构化参数传，不塞提示词
- **术语**：只把这一句里真的出现的词发过去，没出现的不发（否则模型会硬塞进译文）
- **译文检查**：用自己的 LLM 逐句复核，发现译反、漏否定、直译比喻就带提示重译这一句
- **断句**：停顿切段、单句最长都能调——调小出字快但句子碎，调大句子完整但更晚
- **字幕**：边说边出草稿（灰字会就地修正），或说完再出；可同时显示原文和用时
- **更新**：应用内检查更新，签名固定，覆盖安装不丢配置和 key
- **隐私**：声音只在手机上识别，不上传；翻译只发识别出的文字

## 用起来

1. 装[最新 APK](../../releases/latest/download/app-debug.apk)（约 251 MB）
2. **只需要填 API Key**：设置 → 翻译 → LLM API → 粘贴 key → 点「测试连接」
   - 地址（`https://dashscope.aliyuncs.com/compatible-mode/v1`）和模型（`qwen-flash`）已经填好了
   - 用别的服务商时再改这两项
3. **首页**选「识别语种 → 字幕语言」，点「开始字幕」，在系统弹窗里选要听的 App
4. 以后更新：**设置 → 页面 → 检查更新**

默认配置是实测出来的（一场直播 372 句的对比）：

| 模型 | 翻译 p50 | 端到端 p50 | 上下文 |
|---|---|---|---|
| `qwen-flash` + 上文 2 句 | **346 ms** | **833 ms** | ✅ |
| `qwen-mt-plus` | 268 ms | 1370 ms | ❌ |
| `qwen3.7-plus` | 1046 ms | 2684 ms | ✅ |

成本：一场 1 小时直播约 ¥0.1~0.2（`qwen-flash` 前缀缓存命中约 66%）。

## 已知限制

- 识别语种选「自动」时，短句容易判错语种（实测 52 句里 19 句被判成中文或英文）。纯日语直播请选「日语」
- 游戏里的玩家名、技能名仍会认错；术语表能修译文，修不了识别
- 应用内更新会依次尝试 github.com、gh-proxy、ghfast、ghproxy、gh.llkk 五个源，都不通时只能用浏览器手动下载
- 选「粤语」时识别正常，但翻译的源语言会退回「自动」（交给翻译模型自己判断，效果不受影响）

## 说明

- 这是我自己用的改版，**不承诺修问题、不接功能请求**；拿去用没问题，出了问题我不兜底
- 字幕是机器翻译出来的，会有错。**别用在需要准确信息的地方**（看病、法律、交易这类）
- API 费用、额度、账号风险都是你自己的，和原作者、以及文中提到的模型厂商都没关系
- 再分发请保留 [LICENSE](LICENSE) 和致谢，别说成自己的原创

## 构建

推送到 `main` 由 GitHub Actions 自动完成：下载模型（校验 SHA-256）→ 编译 → 上传 artifact → 发布到 Releases（滚动 tag `diy-latest`）。

- 签名固定在 Actions 缓存里，覆盖安装不丢配置
- `versionCode` 取 `1000 + 构建序号`，应用内更新靠它判断有没有新版本
- 每个版本的改动见 [CHANGELOG.md](CHANGELOG.md)；要重建某个历史版本的 APK，到对应 tag 上手动 Run workflow 即可

## 致谢

感谢 [KorenKrita](https://github.com/KorenKrita) 的 [simul-subtitle](https://github.com/KorenKrita/simul-subtitle)。

悬浮字幕、音频内录、VAD 断句、草稿/定稿字幕、BYOK 翻译、术语与场景、桌面端……这套东西的骨架全是他一个人搭的。没有他的工作，这个 diy 版根本不存在——我只是在他的成果上换了自己更习惯的识别模型，改了几处交互和译文检查的实现。

也感谢 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（本地推理）、[SenseVoice](https://github.com/FunAudioLLM/SenseVoice)（多语种识别）、[Silero VAD](https://github.com/snakers4/silero-vad)（断句）。

## 许可

沿用 [LICENSE](LICENSE)。
