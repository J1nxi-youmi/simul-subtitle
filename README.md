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
2. **设置 → 翻译 → LLM API**：填 OpenAI 兼容地址、模型 ID、API Key，点「测试连接」
   - 翻译建议 `qwen-mt-plus`，地址 `https://dashscope.aliyuncs.com/compatible-mode/v1`
   - 译文检查建议 `qwen-flash`，和翻译共用同一个地址和 key
3. **首页**选「识别语种 → 字幕语言」，点「开始字幕」，在系统弹窗里选要听的 App
4. 以后更新：**设置 → 页面 → 检查更新**

成本参考：一场 1 小时直播的翻译约 ¥0.1~0.2（qwen-mt-plus，按 372 次翻译实测推算）。

## 已知限制

- 识别语种选「自动」时，短句容易判错语种（实测 52 句里 19 句被判成中文或英文）。纯日语直播请选「日语」
- 游戏里的玩家名、技能名仍会认错；术语表能修译文，修不了识别
- 应用内更新会依次尝试 github.com、gh-proxy、ghfast、ghproxy、gh.llkk 五个源，都不通时只能用浏览器手动下载

## 构建

推送到 `main` 由 GitHub Actions 自动完成：下载模型（校验 SHA-256）→ 编译 → 上传 artifact → 发布到 Releases（滚动 tag `diy-latest`）。

- 签名固定在 Actions 缓存里，覆盖安装不丢配置
- `versionCode` 取 `1000 + 构建序号`，应用内更新靠它判断有没有新版本

## 许可

沿用 [LICENSE](LICENSE)。
