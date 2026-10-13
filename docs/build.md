# 构建与开发

## 环境

JDK 17，Android SDK（platform 35、build-tools 34.0.0、NDK 27.2.12479018、CMake 3.22.1）。minSdk 29，arm64-v8a。

llama.cpp 按 `armv8.6-a+dotprod+i8mm+fp16` 编译（`app/build.gradle.kts`，`GGML_NATIVE=OFF`，没有运行时分派），所以「手机上翻译」只能在支持这些指令的处理器上运行；不支持的手机选这个方式会因非法指令崩溃。识别和在线翻译不受影响。

```bash
git clone --recursive https://github.com/KorenKrita/simul-subtitle   # llama.cpp 是子模块
cd simul-subtitle
./setup.sh                      # 下载 sherpa-onnx AAR 到 app/libs，模型到 models/（Mac 测试用）
export JAVA_HOME=<jdk17> ANDROID_HOME=~/Library/Android/sdk
./gradlew -q :app:compileDebugUnitTestKotlin :app:assembleRelease
```

需要代理时：`HTTPS_PROXY=... ./setup.sh`，以及 `./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 ...`。

APK 在 `app/build/outputs/apk/release/app-release.apk`（约 13 MB，原生库压缩存放，安装时解压）。
上面的命令只编译测试，不运行单元测试或模拟器。

代码分两个模块：`core/` 是不依赖 Android 的核心（断句、识别和翻译的流水线、翻译提示词与在线翻译、Jev、模型清单与校验、会话日志、服务状态），手机和桌面版共用；`app/` 是 Android 外壳（界面、浮层、后台服务、模型下载、检查更新、本地模型的 JNI）。跑全部单元测试：`./gradlew :core:test :app:testDebugUnitTest`。

## 模型

App 里点「下载模型」时，由系统 DownloadManager 在后台下载。每个文件有三层地址，内容完全相同：

| 文件 | ① 官方 | ② 本项目 GitHub | ③ 国内镜像 |
|---|---|---|---|
| 语音识别（4 个文件）、静音检测 | 固定版本的 Hugging Face 仓库 / sherpa-onnx 的 GitHub Release | 本项目 Release [`models-v1`](https://github.com/KorenKrita/simul-subtitle/releases/tag/models-v1) 的备份 | GitHub 下载代理 gh-proxy.com、gh.llkk.cc 访问同一份备份 |
| 手机上翻译的模型 | Hugging Face 官方仓库 | `models-v1` 的备份（随附 Apache-2.0 许可证） | 魔搭 ModelScope 上的官方仓库 `IndexTeam/Index-Translate-2B-GGUF`，再到两个 GitHub 代理 |

- **先选线路**：开始下载时，三层的所有地址同时下载最小的那个文件（识别用的 tokens.txt，约 26 KB，核对大小和 SHA-256 后丢弃；魔搭上没有这个文件，所以翻译模型的第三层按代理的结果算）。官方一层能用就走官方；官方不通就用能用的最低一层，下层能不能用，一知道就决定，不等上层超时。最多等 15 秒；都不成功就按完整顺序从官方开始（例如还没联网）。之后这一批的所有文件都从选中的那一层开始，按固定顺序往后试，上面的层不再试。小文件能下不代表大文件一定能下：大文件在选中的线路上不通时，照样按下一条规则换地址。选线路时 App 被系统关掉，下次打开会继续。点「下载」或「重试」会重新选。
- 一个地址下载失败、15 秒进度一直是 0，或者下回来的文件校验不通过，就换路线上的下一个地址；所有地址都失败才提示错误。以系统上报的进度为准：上报有延迟（至少每 64 KB 才更新一次），所以已经收到少量数据、但进度还没更新的下载也可能被换掉。**进度动过的下载，中途卡住不换地址**，等网络恢复后由系统接着下（换地址要从头下）。换地址只在 App 打开着时进行：切到后台后，系统会继续下载当前地址，回到 App 时再判断要不要换。
- 存储空间不足等手机本身的问题不会换地址。
- 每个文件使用前都校验大小和 SHA-256，不论从哪个地址下载，内容都必须与官方文件完全一致；下载不完整或损坏的文件不会被加载。

| 文件 | 大小 | 用途 |
|---|---|---|
| `asr/encoder.int8.onnx` 等 4 个 | 约 77 MB | ReazonSpeech 识别 |
| `vad/silero_vad.onnx` | 0.6 MB | 静音检测 |
| `mt/index-translate-2b.gguf` | 1.31 GB | 手机上翻译（只在选择时下载） |

也可以用 `./setup.sh push` 通过 adb 推送，推送的文件会在后台校验一次。

## Mac 端到端测试

```bash
tools/mac-e2e/run.sh [file.wav]
```

在 macOS 上用真实模型运行和手机相同的 Pipeline / Segmenter / JNI 代码，按实时速度输入 WAV；所有定稿都按顺序送达才返回 0。不带参数时用 `say -v Kyoko` 合成一段 6 句的回归音频。

- `JAVA_OPTS=-DdropFinal=3 tools/mac-e2e/run.sh`：反向检查，必须失败。
- `-DdeadlineS`（默认 180 s）：超时后以 2 退出，卡住的原生调用不会让测试挂起。
- `tools/mac-e2e/sample-run.log` 是一次参考运行。

## Mac 版

只支持 Apple 芯片、macOS 14.2 及以上（系统声音抓取用的 Core Audio process tap 从 14.2 起才有）。

### 从仓库运行

```bash
tools/mac-capture/build.sh          # 系统声音抓取 sysaudio
tools/mac-e2e/run.sh                # 顺带编好 sherpa-onnx 与 llama.cpp 的 Mac 原生库
./gradlew :desktop:run
```

直接用仓库的 `models/`（`./setup.sh` 下载）和上面编出的原生库，设置存在 `desktop/build/run-data/`，不碰装好的 App。

### 打包 dmg

```bash
export JAVA_HOME=$(mise where java@zulu-17.68.203.0)
tools/mac-app/build.sh
```

产物是 `desktop/build/compose/binaries/main/dmg/SimulSub-<versionName>-mac-arm64.dmg`（约 75 MB），脚本最后打印它的 SHA-256。脚本自己完成全部原生部分，不依赖 `tools/mac-e2e`：

- sherpa-onnx 1.13.8 的 JVM jar 和 `osx-aarch64` 原生库（与 `tools/mac-e2e` 同一版本）。
- llama.cpp 静态编译（CPU、`armv8.2-a+dotprod`、不开 Metal），和手机同一份 `translator_jni.cpp` 一起链成一个 `libjpsub_mac.dylib`；脚本会检查它不再动态依赖 llama / ggml。
- `sysaudio` 只编 arm64。
- `:desktop:createDistributable` 带上精简 JRE（`java.net.http`、`jdk.crypto.ec`、`java.naming` 等），原生库和 `sysaudio` 放在 `SimulSub.app/Contents/app/resources/`（`compose.application.resources.dir`）。
- 版本号跟安卓同一个 `versionName`。jpackage 要求主版本号大于 0，所以 bundle 的 `CFBundleVersion` 是 `1.<versionCode>`（只用来排序），`CFBundleShortVersionString` 改回 `versionName` 后重新 ad hoc 签名。
- `Info.plist` 带 `NSAudioCaptureUsageDescription`，第一次「开始字幕」时系统会问是否允许录制系统声音。

没有 Developer ID 签名和公证。用户第一次打开时 macOS 会拦下；要在「系统设置 → 隐私与安全性」里点「仍要打开」，或右键 App 选「打开」。

装好的 App 把设置、使用记录和模型放在 `~/Library/Application Support/SimulSub/`，key 存在钥匙串（服务名 `SimulSub`）。删除 App 不会删除这些。

## 签名与包名

包名 `io.github.korenkrita.simulsub`。从旧包名 `dev.krita.jpsub` 的版本升级时会作为新 App 安装，需要重新下载模型。

## App 内更新与发布

### 检查协议（从 0.4 起）

不另建更新服务器，不解析网页，也不通过代理请求 GitHub API。每个正式版本的 GitHub Release 必须同时附带 APK 和固定名称的 `update.json`，并设为 **Latest**（模型备份版本、预览版不要设为 Latest）。[GitHub 支持 latest/download 固定文件地址](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)。

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
- 下载与校验在后台线程执行；取消、页面销毁时停止网络请求，不影响现有模型下载。旋转屏幕、切主题或进程结束中断下载后，回到页面可点「重试」从头下载，不做断点续传。
- APK 只放在 App 专用缓存 `updates/update.apk`，重试覆盖这份缓存。仅通过独立 FileProvider 共享该缓存目录，不共享模型、记录或其他缓存。
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

   Mac 版用同一个版本号，打包后放进同一目录（Mac 版检查更新只读清单的版本和更新内容，打开 Release 页让用户下载，不需要清单里有 dmg）：

   ```bash
   tools/mac-app/build.sh
   cp "desktop/build/compose/binaries/main/dmg/SimulSub-$VERSION-mac-arm64.dmg" build/release/
   ```

3. 推送 main，把三份附件一起发布（不要先发布一个缺清单或缺 APK 的版本）：

   ```bash
   git push origin main
   gh release create "v$VERSION" --repo KorenKrita/simul-subtitle --target "$(git rev-parse HEAD)" --latest \
     --title "同传字幕 v$VERSION" --notes-file build/release/notes.md \
     "build/release/simul-subtitle-v$VERSION.apk" build/release/update.json "build/release/SimulSub-$VERSION-mac-arm64.dmg"
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
