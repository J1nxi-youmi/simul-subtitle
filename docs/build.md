# 构建与开发

## 环境

JDK 17，Android SDK（platform 35、build-tools 34.0.0）。minSdk 29，arm64-v8a。不需要 NDK：App 没有自己的原生代码，识别用的是 sherpa-onnx AAR 里编好的库。

```bash
git clone https://github.com/KorenKrita/simul-subtitle
cd simul-subtitle
./setup.sh                      # sherpa-onnx AAR 到 app/libs，识别模型到 models/ 并复制进 app/src/main/assets/models
export JAVA_HOME=<jdk17> ANDROID_HOME=~/Library/Android/sdk
./gradlew -q :app:compileDebugUnitTestKotlin :app:assembleRelease
```

需要代理时：`HTTPS_PROXY=... ./setup.sh`，以及 `./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 ...`。

APK 在 `app/build/outputs/apk/release/app-release.apk`（约 90 MB：识别模型约 78 MB 不压缩存放，原生库压缩存放、安装时解压）。
上面的命令只编译测试，不运行单元测试或模拟器。

代码分两个模块：`core/` 是不依赖 Android 的核心（断句、识别和翻译的流水线、翻译提示词与 BYOK 翻译客户端、Jev、会话日志、服务状态），手机和桌面版共用；`app/` 是 Android 外壳（界面、浮层、后台服务、检查更新）。跑全部单元测试：`./gradlew :core:test :app:testDebugUnitTest`。

## 模型

识别模型直接打包进 App，不在运行时下载。`./setup.sh` 只从官方地址取文件，逐个核对大小和 SHA-256，不对就报错退出：

| 文件 | 大小 | 来源 | 用途 |
|---|---|---|---|
| `asr/encoder.int8.onnx` 等 4 个 | 约 77 MB | 固定版本的 Hugging Face 仓库 [csukuangfj/reazonspeech-k2-v2-ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en) | ReazonSpeech 识别 |
| `vad/silero_vad.onnx` | 0.6 MB | sherpa-onnx 的 GitHub Release `asr-models` | 静音检测 |
| `asr/LICENSE`、`vad/LICENSE` | | apache.org 的 Apache-2.0 原文、[silero-vad](https://github.com/snakers4/silero-vad) 固定 commit 的 MIT 许可证 | 随模型一起分发 |

- 手机：`setup.sh` 把这些文件复制到 `app/src/main/assets/models/`（不进 git），`.onnx` 在 APK 里不压缩（`noCompress`），sherpa-onnx 直接从 assets 读取。
- Mac：`tools/mac-app/build.sh` 把 `models/asr`、`models/vad` 复制进 App 的资源目录；从仓库运行时用 `./models`（或 `-Dsimulsub.models=<目录>`）。

## Mac 端到端测试

```bash
tools/mac-e2e/run.sh [file.wav]
```

在 macOS 上用真实识别模型和你自己的翻译服务运行和手机相同的 Pipeline / Segmenter 代码，按实时速度输入 WAV；翻译服务由环境变量 `BYOK_URL`、`BYOK_MODEL`、`BYOK_KEY` 给出（要能关闭思考，第一次会先探测关闭思考的参数）；所有定稿都按顺序送达才返回 0。不带参数时用 `say -v Kyoko` 合成一段 6 句的回归音频。

- `JAVA_OPTS=-DdropFinal=3 tools/mac-e2e/run.sh`：反向检查，必须失败。
- `-DdeadlineS`（默认 180 s）：超时后以 2 退出，卡住的原生调用不会让测试挂起。
- `tools/mac-e2e/sample-run.log` 是 0.6.5 用手机上的本地翻译模型时的一次参考运行，翻译耗时和译文不代表 BYOK。

## Mac 版

只支持 Apple 芯片、macOS 14.2 及以上（系统声音抓取用的 Core Audio process tap 从 14.2 起才有）。

### 从仓库运行

```bash
tools/mac-capture/build.sh          # 系统声音抓取 sysaudio
tools/mac-e2e/run.sh                # 顺带下载 sherpa-onnx 的 Mac 原生库（需要 BYOK_* 环境变量）
./gradlew :desktop:run
```

直接用仓库的 `models/`（`./setup.sh` 下载）和上面编出的原生库，设置存在 `desktop/build/run-data/`，不碰装好的 App。

### 打包 dmg

```bash
export JAVA_HOME=$(mise where java@zulu-17.68.203.0)
tools/mac-app/build.sh
```

产物是 `desktop/build/compose/binaries/main/dmg/SimulSub-<versionName>-mac-arm64.dmg`，脚本最后打印它的 SHA-256。脚本自己完成全部原生部分，不依赖 `tools/mac-e2e`：

- sherpa-onnx 1.13.8 的 JVM jar 和 `osx-aarch64` 原生库（与 `tools/mac-e2e` 同一版本）。
- 识别模型（`./setup.sh` 校验过的 `models/asr`、`models/vad`）。
- `sysaudio` 只编 arm64。
- `:desktop:createDistributable` 带上精简 JRE（`java.net.http`、`jdk.crypto.ec`、`java.naming` 等），原生库、模型和 `sysaudio` 放在 `SimulSub.app/Contents/app/resources/`（`compose.application.resources.dir`）。
- 版本号跟安卓同一个 `versionName`。jpackage 要求主版本号大于 0，所以 bundle 的 `CFBundleVersion` 是 `1.<versionCode>`（只用来排序），`CFBundleShortVersionString` 改回 `versionName` 后重新 ad hoc 签名。
- `Info.plist` 带 `NSAudioCaptureUsageDescription`，第一次「开始字幕」时系统会问是否允许录制系统声音。

没有 Developer ID 签名和公证。用户第一次打开时 macOS 会拦下；要在「系统设置 → 隐私与安全性」里点「仍要打开」，或右键 App 选「打开」。

装好的 App 把设置和使用记录放在 `~/Library/Application Support/SimulSub/`，key 存在钥匙串（服务名 `SimulSub`）。删除 App 不会删除这些。

## Windows 版

只做 x64，免安装 zip。这台 Mac 编不了，打包在 GitHub Actions 的 Windows 机器上：`.github/workflows/windows.yml` 在推送 `windows` 分支或手动运行（Actions 页 → windows → Run workflow）时执行 `tools/win-app/build.sh`，产物是 `SimulSub-<versionName>-windows-x64.zip`，Actions 的 artifact 是解开后的 `SimulSub/` 文件夹。

`tools/win-app/build.sh` 在 Windows 的 Git Bash 里运行，要 JDK 17 和 MSVC 的 `cl.exe`（Developer 命令行，或 CI 里的 `ilammy/msvc-dev-cmd`）：

- sherpa-onnx 1.13.8 的 JVM jar 和 `win-x64` 原生库（`sherpa-onnx-jni.dll`、`onnxruntime.dll`）。
- 识别模型（`./setup.sh` 校验过的 `models/asr`、`models/vad`；在 Git Bash 里用 `sha256sum`）。
- `tools/win-capture/sysaudio.cpp`：WASAPI loopback 录默认播放设备，转 16 kHz 单声道 float32 写到标准输出，和 Mac 的 `sysaudio` 格式相同；没声音时写静音保持实时，默认设备变了会跟过去，每 5 秒往 stderr 写一行统计（设备名和音量，记进使用记录的 `sysaudio` 事件）。静态 C 运行库（`/MT`），不用另装 VC++ 运行库。
- `:desktop:createDistributable` 出 `SimulSub/SimulSub.exe` 和精简 JRE（Windows 多带 `jdk.crypto.mscapi`），用 Windows 自带的 `tar.exe` 打成 zip（路径分隔符是 `/`）。jpackage 在 Windows 上按系统代码页读参数，所以 Windows 的包描述是英文。

和 Mac 版不同的地方都在桌面端代码里用 `isWindows` 判断：

- 绘制用 skiko 的 `SOFTWARE`：默认的 Direct3D 画透明字幕窗会出错（拖动后工具栏被裁掉、放大后字幕消失、关窗口时整个 App 卡住），OpenGL 会让工具栏底部发黑。环境变量 `SKIKO_RENDER_API` 仍可覆盖。
- 证书信任 Windows 证书库（`Windows-ROOT`），和浏览器一致。
- 设置在 `%APPDATA%\SimulSub\`；key 用 DPAPI（jna-platform 的 `Crypt32Util`）加密后存在 `keys.json`。
- 托盘图标按注册表 `SystemUsesLightTheme` 画成白色或黑色。

两个平台共用的：网络请求跟随系统代理（`java.net.useSystemProxies`），代理之后再加一条直连线路；OkHttp 只在请求还没开始发送时（连接或 TLS 握手失败）换下一条线路，请求体是一次性的，已经发出的请求不会重发。

## 签名与包名

包名 `io.github.korenkrita.simulsub`。从旧包名 `dev.krita.jpsub` 的版本升级时会作为新 App 安装。

## App 内更新与发布

### 检查协议（从 0.4 起）

不另建更新服务器，不解析网页，也不通过代理请求 GitHub API。每个正式版本的 GitHub Release 必须同时附带 APK 和固定名称的 `update.json`，并设为 **Latest**（预览版不要设为 Latest）。[GitHub 支持 latest/download 固定文件地址](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)。

按以下顺序获取清单，第一个有效结果为准：

1. `https://github.com/KorenKrita/simul-subtitle/releases/latest/download/update.json`
2. `https://gh-proxy.com/https://github.com/KorenKrita/simul-subtitle/releases/latest/download/update.json`
3. `https://gh.llkk.cc/https://github.com/KorenKrita/simul-subtitle/releases/latest/download/update.json`

APK 使用清单里指向**具体版本**的 GitHub 地址；失败时按同样的两条代理顺序重试。连接超时 6 秒、读取停滞超时清单 8 秒 / APK 10 秒；单个清单请求最多 15 秒，单个 APK 最多 3 分钟。清单最大 128 KiB，APK 最大 200 MB。不带认证信息；拒绝 HTTPS 降级到 HTTP。

清单字段：

```json
{
  "versionCode": 4,
  "versionName": "0.4",
  "notes": "## 这次更新\n\n- 新版本可以在 App 里下载安装。",
  "apkUrl": "https://github.com/KorenKrita/simul-subtitle/releases/download/v0.4/simul-subtitle-v0.4.apk",
  "size": 12345678,
  "sha256": "替换为实际 APK 的 64 位 SHA-256"
}
```

`size` 是实际字节数，不是上面示例数。`versionCode` 必须递增，以它判断更新；`versionName` 用于展示，并与 APK、tag 和文件名一致（数字点分格式，例如 `0.4`、`0.10`）。`notes` 与 GitHub Release 的中文更新内容使用同一份文本。清单缺失、损坏或字段不合规会换源，不会误报“已是最新版本”。代理可能缓存旧清单；请求带 `Cache-Control: no-cache`，但新版本发布后仍应确认两条代理实际返回的内容。

- 每次进入 App（MainActivity 的 onStart：冷启动、进程被杀后恢复、从后台切回前台）后台检查一次；旋转屏幕或换主题造成的重建不算，除非重建打断了正在进行的检查。后台结果等页面恢复前台再显示，自动检查失败不打扰使用。
- 「永不检查」持久关闭自动检查；「设置 → 关于」可以重新开启，手动检查始终忽略关闭、跳过和稍后提醒的选择。
- 「跳过本次更新」只跳过该 versionCode；「稍后提醒」延后该版本 24 小时，下次启动且到时间后再提示，不屏蔽后续更新版本。
- 更新内容和四个按钮放在同一个可滚动区域，大字号 / 长更新内容也能滚到所有选项。
- 已查到但等待前台显示、或已显示但尚未选择的更新提示会保留；旋转屏幕或进程重建后恢复，并重新判断提示偏好。
- 下载与校验在后台线程执行；取消、页面销毁时停止网络请求。旋转屏幕、切主题或进程结束中断下载后，回到页面可点「重试」从头下载，不做断点续传。
- APK 只放在 App 专用缓存 `updates/update.apk`，重试覆盖这份缓存。仅通过独立 FileProvider 共享该缓存目录，不共享记录或其他缓存。
- 每个来源均核对实际大小、SHA-256、包名、versionCode、versionName 和与已安装 App 相同的签名证书。代理提供的哈希不是身份认证；同签名检查及系统安装器是另一道独立校验。
- 安装请求会先解释「允许来自此来源的应用」，跳转系统设置后返回继续；待安装版本在本机持久化，进程重建后重新校验缓存。最终必须由用户在系统安装器确认。缓存丢失或校验失败可重新下载。

手动验收步骤（需要另行在手机上执行，编译检查不代表已验收）：用更低 versionCode 的同签名构建检查已发布版本；提示出现后旋转屏幕，四个选项应恢复；下载或校验时取消，再退回首页重进，不应重新启动安装；安装权限页拒绝后应能重试，允许后应进入系统安装器；大字号下能滚到四个按钮；关闭自动检查、跳过及稍后提醒后，手动检查仍应显示新版本。

### 发布步骤（必须在原签名 Mac 上执行）

当前 release 构建使用本机 `~/.android/debug.keystore`。不能在另一台机器临时生成 debug key，否则旧用户不能覆盖升级。首次发布和后续发布都保持包名及证书相同。
已发布 v0.3 的证书 SHA-256：`d75c33a978e0d78c3392ccefc7972abfbc75856fd9843a9d5e08762e2f94fdd8`。

1. 更新 `app/build.gradle.kts` 的 `versionCode` / `versionName`，单独提交 `Version X.Y`。先完成功能提交，版本号另一个提交。编译测试但不运行：

   ```bash
   export JAVA_HOME=$(mise where java@zulu-17.68.203.0) ANDROID_HOME=~/Library/Android/sdk
   ./gradlew -q :app:compileDebugUnitTestKotlin :app:assembleRelease
   "$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
   "$ANDROID_HOME/build-tools/34.0.0/aapt" dump badging app/build/outputs/apk/release/app-release.apk | head -1
   ```

   核对证书与上个已发布 APK 相同，核对 aapt 的版本与本次版本一致。不启动模拟器或单元测试。

2. 准备附件和中文更新内容，`notes.md` 同时用于清单与 Release；以下用 0.4 举例，下次改 `VERSION` / `CODE`：

   ```bash
   export VERSION=0.4 CODE=4
   mkdir -p build/release
   cp app/build/outputs/apk/release/app-release.apk "build/release/simul-subtitle-v$VERSION.apk"
   # 在 build/release/notes.md 写好这次中文更新内容，再生成清单：
   uv run python - <<'PY'
   import hashlib, json, os
   from pathlib import Path
   version, code = os.environ["VERSION"], int(os.environ["CODE"])
   folder = Path("build/release")
   apk = folder / f"simul-subtitle-v{version}.apk"
   manifest = dict(versionCode=code, versionName=version,
       notes=(folder / "notes.md").read_text(),
       apkUrl=f"https://github.com/KorenKrita/simul-subtitle/releases/download/v{version}/{apk.name}",
       size=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
   (folder / "update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
   PY
   ```

   Mac 版和 Windows 版用同一个版本号，打包后放进同一目录（桌面版检查更新只读清单的版本和更新内容，打开 Release 页让用户下载，不需要清单里有 dmg 或 zip）：

   ```bash
   tools/mac-app/build.sh
   cp "desktop/build/compose/binaries/main/dmg/SimulSub-$VERSION-mac-arm64.dmg" build/release/
   # Windows：版本号提交推送后，在 GitHub Actions 手动运行 windows 工作流（选 main），下载它的 artifact
   gh workflow run windows.yml --repo KorenKrita/simul-subtitle --ref main
   gh run watch "$(gh run list --repo KorenKrita/simul-subtitle --workflow windows.yml --limit 1 --json databaseId -q '.[0].databaseId')" --repo KorenKrita/simul-subtitle --exit-status
   gh run download "$(gh run list --repo KorenKrita/simul-subtitle --workflow windows.yml --limit 1 --json databaseId -q '.[0].databaseId')" \
     --repo KorenKrita/simul-subtitle -n SimulSub-windows-x64 --dir build/release/win
   (cd build/release/win && zip -qr "../SimulSub-$VERSION-windows-x64.zip" SimulSub)
   ```

3. 推送 main，把四份附件一起发布（不要先发布一个缺清单或缺 APK 的版本）：

   ```bash
   git push origin main
   gh release create "v$VERSION" --repo KorenKrita/simul-subtitle --target "$(git rev-parse HEAD)" --latest \
     --title "同传字幕 v$VERSION" --notes-file build/release/notes.md \
     "build/release/simul-subtitle-v$VERSION.apk" build/release/update.json "build/release/SimulSub-$VERSION-mac-arm64.dmg" \
     "build/release/SimulSub-$VERSION-windows-x64.zip"
   ```

4. 回读验证，不只检查上传命令退出码：

   ```bash
   mkdir -p "build/release/readback-v$VERSION"
   gh release download "v$VERSION" --repo KorenKrita/simul-subtitle \
     --pattern "simul-subtitle-v$VERSION.apk" --dir "build/release/readback-v$VERSION"
   shasum -a 256 "build/release/simul-subtitle-v$VERSION.apk" \
     "build/release/readback-v$VERSION/simul-subtitle-v$VERSION.apk"
   for prefix in '' 'https://gh-proxy.com/' 'https://gh.llkk.cc/'; do
     curl -fL --connect-timeout 6 --max-time 30 -H 'Cache-Control: no-cache' \
       "${prefix}https://github.com/KorenKrita/simul-subtitle/releases/latest/download/update.json"
   done
   ```

   两份 APK 哈希必须相同；三个清单的 versionCode、versionName、大小、哈希和更新内容均应与本地相同。代理还应回读具体版本的 APK 并对比哈希。海外出口成功不代表中国大陆的网络一定可达。
