# 同声传译（diy版）

个人自用的改动版，基于 [KorenKrita/simul-subtitle](https://github.com/KorenKrita/simul-subtitle)。
**只构建 Android**，桌面端（macOS / Windows）的代码保留但不再同步维护。

## 和上游的区别

| | 上游 | 本 fork |
|---|---|---|
| 语音识别 | ReazonSpeech（只认日/英） | **SenseVoice**（中/英/日/韩/粤，可锁语种） |
| APK 体积 | 约 90 MB | 约 251 MB（模型更大） |
| 译文检查 | TypeSafe Jev 云服务 | **你自己的 LLM API**（Jev 已移除） |
| 智能断句 | 由 Jev 判断句尾 | **已移除** |
| 断句参数 | 编译期写死 | **设置里可调**（停顿切段 / 单句最长） |
| 术语表 | 塞进 system prompt | 走 Qwen-MT 的 `translation_options`，**只发本句真的出现的术语** |
| 更新方式 | 上游 release | 本仓库 release + **应用内更新** |

## 用起来

1. 装[最新 APK](../../releases/latest/download/app-debug.apk)（约 251 MB）
2. **设置 → 翻译 → LLM API**：填 OpenAI 兼容地址、模型 ID、API Key，点「测试连接」
   - 翻译建议 `qwen-mt-plus`，地址 `https://dashscope.aliyuncs.com/compatible-mode/v1`
   - 译文检查（可选）建议 `qwen-flash`，和翻译共用同一个地址和 key
3. **首页**选「识别语种 → 字幕语言」，点「开始字幕」，在系统弹窗里选要听的 App
4. 以后更新：**设置 → 页面 → 检查更新**

成本参考：一场 1 小时直播的翻译约 ¥0.1~0.2（qwen-mt-plus，按 372 次翻译实测推算）。

## 已知限制

- **识别语种「自动」在短句上不可靠**：实测 52 句里 19 句被判成中文或英文。纯日语直播请选「日语」，只有真的中日混说时才用「自动」
- **游戏专有名词仍会认错**（玩家名、技能名）：术语表只能修译文，修不了识别
- 应用内更新会依次尝试 github.com、gh-proxy、ghfast、ghproxy、gh.llkk 五个源，都不通时只能浏览器手动下载

## 构建

推送到 `main` 由 GitHub Actions 自动完成：下载 SenseVoice 模型（校验 SHA-256）→ 编译 → 上传 artifact → 发布到 Releases（滚动 tag `diy-latest`）。

- 签名固定在 Actions 缓存里，**覆盖安装不会丢配置**
- `versionCode` 取 `1000 + 构建序号`，App 内的更新器靠它判断有没有新版本

## 许可

沿用上游 [LICENSE](LICENSE)。原项目文档在 [docs/](docs/)，其中 [asr.md](docs/asr.md)、[jev.md](docs/jev.md) 描述的是上游行为，与本 fork 一致的部分才作参考。
