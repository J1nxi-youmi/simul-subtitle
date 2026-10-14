<p align="center">
  <img src="docs/images/icon.svg" width="112" alt="同传字幕">
</p>

<h1 align="center">同传字幕</h1>

<p align="center">
  看日语、英语视频和直播时，手机或 Mac 实时给你配上中文（或其他语言）字幕。<br>
  不用等别人做字幕，不用换播放器，打开就能看懂。
</p>

<p align="center">
  <a href="README.en.md">English</a> ·
  <a href="docs/README.md">技术文档</a>
</p>

---

## 界面一览

<table>
  <tr>
    <td><img src="docs/images/screens/home.png" width="200" alt="首页"></td>
    <td><img src="docs/images/screens/overlay.png" width="300" alt="悬浮字幕（点一下出工具栏）"></td>
    <td><img src="docs/images/screens/caption.png" width="200" alt="设置 · 字幕"></td>
    <td><img src="docs/images/screens/size.png" width="200" alt="字幕框大小和保留几句"></td>
    <td><img src="docs/images/screens/translate.png" width="200" alt="设置 · 翻译（LLM API）"></td>
    <td><img src="docs/images/screens/page-dark.png" width="200" alt="设置 · 页面（夜间）"></td>
    <td><img src="docs/images/screens/records.png" width="200" alt="设置 · 记录"></td>
    <td><img src="docs/images/screens/about.png" width="200" alt="设置 · 关于"></td>
    <td><img src="docs/images/screens/home-dark.png" width="200" alt="首页（夜间）"></td>
  </tr>
  <tr>
    <td align="center">首页</td>
    <td align="center">悬浮字幕（点一下出工具栏）</td>
    <td align="center">设置 · 字幕</td>
    <td align="center">字幕框大小和保留几句</td>
    <td align="center">设置 · 翻译（LLM API）</td>
    <td align="center">设置 · 页面（夜间）</td>
    <td align="center">设置 · 记录</td>
    <td align="center">设置 · 关于</td>
    <td align="center">首页（夜间）</td>
  </tr>
</table>

## 它能做什么

- **大多数 App 都能用**：B 站、YouTube、直播软件、浏览器……适用于允许录制播放声音的 App；少数 App 和有版权保护的视频不支持。
- **字幕浮在画面上**：像电影字幕一样浮在视频上方，可以拖到任何位置，大小、颜色、透明度都能调。
- **边听边出**：说话人一开口，字幕就开始出现；一句说完，再变成正式字幕。
- **听写在手机上完成**：声音只在你的手机里识别成文字，**不会上传**。
- **想翻成什么语言都行**：列表里有中文、英语、日语、韩语，其他语言（比如法语、繁体中文、粤语）选「自己填…」写上就行。
- **告诉翻译你在看什么**：自带「B站 VTuber 直播」，也可以自己建场景：写几句背景说明，给常见词定好译法（术语表），还能让 LLM 按说明自动生成一批术语。
- **用你自己的 LLM API 翻译**：填上任意 OpenAI 兼容服务的地址、模型 ID 和 key（例如 DeepSeek、通义千问），按你自己的用量计费。

## 三步开始

1. **填 LLM API**：第一次打开，首页会提示「先填翻译用的 LLM API」，点「去填写」（或者「设置 → 翻译」），填好服务地址、模型 ID 和 API Key，点「测试连接」。模型要能关闭思考，并且尽量选回复快的（见下面的常见问题）。
2. **允许**：按提示允许「听 App 的声音」和「在屏幕上显示字幕」。
3. **开始**：点「开始字幕」，在弹出的窗口里选你要看的 App，回到视频就能看到字幕。

语音识别模型已经装在 App 里，不用另外下载。

想调字幕大小、颜色或翻译内容，进入「设置」。

### 字幕上的小技巧

- **按住拖动**：换字幕的位置。
- **点一下字幕，再拖四个角上的小箭头**：改字幕框的宽度和高度（小箭头和工具栏一起出现、一起消失）。
- **点一下字幕**：出现工具栏，可以调字号、调背景、切换「边听边出 / 说完再出」、开关原文和用时、锁定，或者停止。
- **锁定**：位置和大小都固定，不能拖动也不能改大小；点字幕不再出工具栏，只在右下角出一个开锁按钮，点它解锁。

## 安装

- 系统要求：Android 10 及以上，arm64 手机。
- 在 [Releases](../../releases) 页面下载 APK，在手机上打开安装（需要允许「安装未知应用」）。
- 从 0.4 起，打开 App 会自动检查新版本；有更新时可查看内容并下载安装，也可以跳过或关闭检查。「设置 → 关于」里随时可以手动检查。
- 想自己编译，看 [构建说明](docs/build.md)。

### Mac 版

- 系统要求：Apple 芯片（M1 及以后），macOS 14.2 及以上。
- 在 [Releases](../../releases) 页面下载 `SimulSub-版本号-mac-arm64.dmg`，打开后把「同传字幕」拖进「应用程序」。
- 这个 dmg 没有经过 Apple 公证。第一次打开时系统会提示无法验证开发者：在「应用程序」里右键同传字幕选「打开」，或到「系统设置 → 隐私与安全性」点「仍要打开」。
- 第一次「开始字幕」会问是否允许录制系统声音，请选「允许」（以后可以在「系统设置 → 隐私与安全性 → 屏幕与系统录音」里改）。
- 字幕听的是这台 Mac 播放出来的所有声音，不用麦克风；浮在所有窗口上面，可以拖到外接显示器上。
- 关掉主窗口字幕也会继续；菜单栏的同传字幕图标可以开始、停止，或重新打开窗口。
- 首页、设置和手机版一样。检查更新时只打开下载页，新版本要自己下载 dmg 替换。
- 设置和使用记录放在 `~/Library/Application Support/SimulSub/`，key 存在钥匙串里；删除 App 不会删除它们。

## 常见问题

**为什么有的 App 没有字幕？**
有些 App（比如开启了版权保护的视频）不允许其他 App 听它的声音，这是 Android 系统的限制，这时不会出字幕。

**能听哪些语言？**
现在能听日语和英语。在首页选好视频里说的语言，翻译会更准。

**会上传我的声音吗？**
不会。声音在手机上变成文字；翻译时只把识别出的文字发给你填的 LLM API。详见 [隐私说明](docs/privacy.md)。

**翻译模型怎么选？**
必须是能关闭思考（深度思考 / reasoning）的模型，否则每句都要等很久；尽量选回复速度快的模型，字幕才跟得上。第一次用某个模型时，App 会自动试出关闭思考的参数并记住。

**翻译不准怎么办？**
可以在设置里打开「结合上文来翻译」，或换更强的翻译模型；也可以在「设置 → 记录」里打开「记录每次使用」，把这次的文字记录分享出来，帮助改进。

## 致谢

同传字幕站在这些优秀的开源项目上：

- **[ReazonSpeech](https://github.com/reazon-research/ReazonSpeech)**：日语 / 英语语音识别模型（App 使用 sherpa-onnx 导出版 [reazonspeech-k2-v2-ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en)）。
- **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**：在手机和 Mac 上运行语音识别和静音检测（[Silero VAD](https://github.com/snakers4/silero-vad)）。

可选的 在线 Decisions 模型辅助（智能断句、检查译文）使用 **[TypeSafe Jev](https://typesafe.ai)**。它是 TypeSafe 的闭源商业云服务，不是开源项目，默认关闭。开启后通过网络调用，用付费接口时会按用量产生费用（每小时约 0.04 美元，见 [Jev 说明](docs/jev.md)）。

## 技术文档

- [整体架构](docs/architecture.md)：声音从哪来、怎么变成字幕
- [语音识别](docs/asr.md)：模型选择与对比测试
- [翻译](docs/translation.md)：自己的翻译模型（BYOK）、提示词、上下文、支持的语言
- [在线 Decisions 模型辅助（Jev）](docs/jev.md)：智能断句与译文检查
- [隐私说明](docs/privacy.md)：哪些数据会离开手机
- [使用记录与调试](docs/logs.md)：记录格式、性能数据
- [构建与开发](docs/build.md)：编译、Mac 端到端测试

---

许可证：[MIT](LICENSE)。使用的模型和第三方库各自遵循其许可证。
