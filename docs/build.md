# 构建与开发

## 模块划分

仓库包含三个 Gradle 模块：

| 模块 | 说明 |
|---|---|
| `:core` | 不依赖 Android SDK 的跨平台核心（分句状态机、识别与翻译流水线、翻译提示词与 BYOK 客户端、Jev 判断、会话日志、运行状态），由手机端与桌面端共用 |
| `:app` | Android 客户端（界面、悬浮窗、前台采集服务、Keystore 加密、应用内检查更新） |
| `:desktop` | 桌面客户端（Compose Desktop，Windows 专用的界面与悬浮窗代码，配合 `tools/` 下的原生音频采集子进程） |

---

## Android 编译与单元测试

### 环境要求

- **JDK 17**
- **Android SDK**：`platforms;android-35`、`build-tools;34.0.0`（`minSdk 29`，仅构建 `arm64-v8a`）
- **无需 NDK**：Android 端没有自建原生 C/C++ 代码，语音识别直接使用 `sherpa-onnx` AAR 内预编译好的 `.so` 库。

### 拉取依赖与构建

```bash
git clone https://github.com/KorenKrita/simul-subtitle
cd simul-subtitle

# 下载 sherpa-onnx AAR 到 app/libs，下载离线识别模型到 models/ 并复制进 app/src/main/assets/models
./setup.sh

export JAVA_HOME=<jdk17> ANDROID_HOME=~/Library/Android/sdk
./gradlew -q :app:compileDebugUnitTestKotlin :app:assembleRelease
```

- **代理配置**：需要代理时可使用 `HTTPS_PROXY=... ./setup.sh`，以及 `./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 ...`。
- **构建产物**：Release APK 位于 `app/build/outputs/apk/release/app-release.apk`（约 90 MB，其中约 78 MB 识别模型不压缩存放，原生库压缩存放并在安装时解压）。
- **运行单元测试**：上面的 `assembleRelease` 仅编译测试代码而不执行；如需运行全部单元测试：
  ```bash
  ./gradlew :core:test :app:testDebugUnitTest
  ```

---

## 内置模型来源与校验

语音识别与静音检测模型随应用直接打包分发，不在运行时联网下载。`./setup.sh` 仅从固定官方地址拉取文件，并逐一校验文件字节数与 SHA-256，校验不通过立即报错退出：

| 文件路径 | 大小 | 上游来源 | 用途 |
|---|---|---|---|
| `asr/encoder.int8.onnx` 等 4 个文件 | 约 77 MB | 固定版本的 Hugging Face 仓库 [csukuangfj/reazonspeech-k2-v2-ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en) | ReazonSpeech 日/英语音识别 |
| `vad/silero_vad.onnx` | 0.6 MB | `sherpa-onnx` GitHub Release（`asr-models`） | Silero VAD 静音检测 |
| `asr/LICENSE`、`vad/LICENSE` | — | `apache.org` 的 Apache-2.0 原文、`silero-vad` 固定 commit 的 MIT 许可证 | 随模型一同分发许可证 |

### 两端打包位置

- **Android**：`setup.sh` 将模型复制到 `app/src/main/assets/models/`（不纳入 Git），`.onnx` 在 APK 内配置为 `noCompress`，由 `sherpa-onnx` 直接从 `assets` 零拷贝读取。
- **Windows**：`tools/win-app/build.sh` 使用 `sha256sum` 校验 `models/asr` 与 `models/vad` 后打入应用资源目录。

---

## 桌面端构建与打包

### Windows 版（x64，免安装 ZIP）

Windows 版仅构建 64 位（`x64`）绿色免安装压缩包。通过 GitHub Actions 的 Windows runner 构建：推送 `windows` 分支或在 Actions 页面手动触发 `.github/workflows/windows.yml`，即可执行 `tools/win-app/build.sh`，生成 `SimulSub-<versionName>-windows-x64.zip`（Actions Artifact 为解压状态的 `SimulSub/` 目录）。

#### `tools/win-app/build.sh` 构建细节

在 Windows Git Bash 中运行，依赖 JDK 17 与 MSVC `cl.exe`（Developer Command Prompt 或 CI 中的 `ilammy/msvc-dev-cmd`）：

- 集成 `sherpa-onnx` 1.13.8 的 JVM JAR 与 `win-x64` 原生库（`sherpa-onnx-jni.dll`、`onnxruntime.dll`）。
- 使用 `sha256sum` 校验并复制 `models/asr` 与 `models/vad`。
- 编译 `tools/win-capture/sysaudio.cpp`：通过 WASAPI Loopback 录制默认播放设备，重采样为 16 kHz 单声道 `float32` 写入标准输出；静音期间持续输出零采样以保持实时时钟，默认播放设备切换时自动跟随，每 5 秒向 `stderr` 输出一行设备名与音量统计（写入会话日志的 `sysaudio` 事件）。采用静态 C 运行库（`/MT`）编译，用户无需额外安装 VC++ Redistributable。
- 执行 `:desktop:createDistributable` 生成 `SimulSub/SimulSub.exe` 及裁剪版 JRE（额外包含 `jdk.crypto.mscapi`），再用系统自带 `tar.exe` 打包为 `.zip`（统一使用 `/` 路径分隔符；因 `jpackage` 在 Windows 下按系统代码页解析参数，包描述使用英文）。

#### Windows 的平台行为

- **软件渲染（`SOFTWARE`）**：Windows 默认使用 Skiko 的 `SOFTWARE` 渲染后端——实测 Direct3D 渲染透明置顶字幕窗会出现拖动后工具栏被裁剪、放大后字幕消失、关闭窗口时主进程卡死等问题，而 OpenGL 会导致工具栏底部发黑（仍可通过环境变量 `SKIKO_RENDER_API` 手动覆盖）。
- **系统根证书库**：信任 Windows 系统证书库（`Windows-ROOT`），与桌面浏览器行为一致。
- **数据与密钥存储**：配置保存在 `%APPDATA%\SimulSub\`；API Key 通过 Windows DPAPI（`jna-platform` 的 `Crypt32Util`）加密后存入 `keys.json`。
- **托盘图标配色**：根据注册表 `SystemUsesLightTheme` 自动切换黑/白托盘图标。
- **双端共用的代理回退策略**：默认启用系统代理（`java.net.useSystemProxies`），并在代理路由末尾追加一条直连路由；OkHttp 仅在请求尚未发出（TCP 连接或 TLS 握手失败）时自动回退直连，单次流式请求体发出后不会重发。

---

## Android 签名、应用内更新与发布流程

### 包名与签名约束

- **应用包名**：`io.github.korenkrita.simulsub`（从早期 `dev.krita.jpsub` 升级时会作为独立新应用安装）。
- **固定签名证书**：当前 Release 构建使用原签名机器上的 `~/.android/debug.keystore`，**严禁在其他机器上临时生成新的 debug key 发布**，否则存量用户无法覆盖升级，且应用内更新的同签名校验会拒绝安装。
  - 已发布版本（v0.3 起）的签名证书 SHA-256 指纹：
    `d75c33a978e0d78c3392ccefc7972abfbc75856fd9843a9d5e08762e2f94fdd8`

### 应用内更新协议（v0.4 起）

不自建更新服务器，不解析 HTML 网页，也不通过反向代理请求 GitHub REST API。每个正式版的 GitHub Release 必须同时上传 APK 与固定命名的 `update.json`，并标记为 **Latest**（预览版切勿标记为 Latest）。

#### 1. 清单与 APK 获取顺序

按以下顺序请求版本清单，以第一个合法响应为准（APK 下载同样使用清单内的具体版本地址，并按相同代理顺序回退）：

1. `https://github.com/KorenKrita/simul-subtitle/releases/latest/download/update.json`
2. `https://gh-proxy.com/https://github.com/KorenKrita/simul-subtitle/releases/latest/download/update.json`
3. `https://gh.llkk.cc/https://github.com/KorenKrita/simul-subtitle/releases/latest/download/update.json`

- **超时与体积限制**：连接超时 6 秒；读取停滞超时：清单 8 秒 / APK 10 秒；单次总超时：清单 15 秒 / APK 3 分钟；体积上限：清单 128 KiB / APK 200 MB。不携带认证头，拒绝 HTTPS 降级为 HTTP。

#### 2. `update.json` 格式

```json
{
  "versionCode": 4,
  "versionName": "0.4",
  "notes": "## 这次更新\n\n- 新版本可以在 App 里下载安装。",
  "apkUrl": "https://github.com/KorenKrita/simul-subtitle/releases/download/v0.4/simul-subtitle-v0.4.apk",
  "size": 12345678,
  "sha256": "替换为实际 APK 的 64 位小写 SHA-256"
}
```

- `size` 必须为 APK 实际字节数；`versionCode` 必须严格递增（客户端据此判定是否有新版）；`versionName` 是 `DIY` + `versionCode`（如 `DIY1064`，旧版的 `1.0.7-diy.1028` 也仍被接受），须与 APK、Git tag 及文件名完全一致；`notes` 与 GitHub Release 正文使用同一份中文更新说明。
- 清单缺失、损坏或字段不合规时会自动尝试下一个镜像源，不会误报“已是最新版本”。

#### 3. 客户端检查与安装行为

- **触发时机**：每次进入 App（`MainActivity.onStart`：冷启动、后台恢复前台、进程重建恢复）在后台检查一次；旋转屏幕或切换深浅色主题引起的重建不重复触发（除非打断了进行中的检查）。自动检查失败静默忽略，不打扰用户。
- **用户偏好控制**：「永不检查」会持久关闭自动检查（可在「设置 → 关于」重新开启）；「跳过本次更新」仅跳过当前 `versionCode`；「稍后提醒」将当前版本延后 24 小时再提示，不影响更高版本。手动点击「检查更新」始终忽略上述屏蔽设置。
- **下载与多重校验**：APK 下载至应用私有缓存 `updates/update.apk`（仅通过独立 `FileProvider` 暴露该子目录）。无论从直连还是代理源下载，安装前均严格校验：**文件字节数、SHA-256、包名、`versionCode`、`versionName` 以及与当前已安装应用完全一致的签名证书**。
- **真机手动验收项**（仅编译通过不代表验收完成）：用较低 `versionCode` 的同签名包测试线上更新；弹窗出现后旋转屏幕应恢复四个按钮；下载或校验中途取消并重进首页不应自动跳安装；「允许安装未知应用」授权页拒绝后可重试、允许后正常拉起系统安装器；大字号下可滚动按到全部四个按钮；开启跳过/稍后/关闭自动检查后，手动检查仍能正常弹出新版。

---

### 正式版发布步骤（必须在原签名 Mac 上执行）

1. **递增版本号并编译校验**
   先提交完所有功能代码，再单独修改 `app/build.gradle.kts` 中的 `versionCode` 与 `versionName`，独立提交为 `Version X.Y`。编译 Release APK 并核对签名与版本号：

   ```bash
   export JAVA_HOME=$(mise where java@zulu-17.68.203.0) ANDROID_HOME=~/Library/Android/sdk
   ./gradlew -q :app:compileDebugUnitTestKotlin :app:assembleRelease
   "$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
   "$ANDROID_HOME/build-tools/34.0.0/aapt" dump badging app/build/outputs/apk/release/app-release.apk | head -1
   ```

2. **生成 `update.json` 并打包三端产物**
   将 `VERSION` 与 `CODE` 替换为本次发布的值，在 `build/release/notes.md` 中写好中文更新说明后生成清单：

   ```bash
   export VERSION=0.4 CODE=4
   mkdir -p build/release
   cp app/build/outputs/apk/release/app-release.apk "build/release/simul-subtitle-v$VERSION.apk"
   # 先在 build/release/notes.md 写好本次中文更新内容，再运行：
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

   推送版本号提交后，GitHub Actions 会自动构建 Windows ZIP，汇总到 `build/release/`：

   ```bash
   # Windows：将版本号提交推送到 main 后，触发远端工作流并下载产物
   gh workflow run windows.yml --repo KorenKrita/simul-subtitle --ref main
   gh run watch "$(gh run list --repo KorenKrita/simul-subtitle --workflow windows.yml --limit 1 --json databaseId -q '.[0].databaseId')" --repo KorenKrita/simul-subtitle --exit-status
   gh run download "$(gh run list --repo KorenKrita/simul-subtitle --workflow windows.yml --limit 1 --json databaseId -q '.[0].databaseId')" \
     --repo KorenKrita/simul-subtitle -n SimulSub-windows-x64 --dir build/release/win
   (cd build/release/win && zip -qr "../SimulSub-$VERSION-windows-x64.zip" SimulSub)
   ```

3. **一次性发布 GitHub Release（四份附件齐全）**
   切勿先发布缺 `update.json` 或缺 APK 的半成品 Release：

   ```bash
   git push origin main
   gh release create "v$VERSION" --repo KorenKrita/simul-subtitle --target "$(git rev-parse HEAD)" --latest \
     --title "同传字幕 v$VERSION" --notes-file build/release/notes.md \
     "build/release/simul-subtitle-v$VERSION.apk" build/release/update.json \
     "build/release/SimulSub-$VERSION-windows-x64.zip"
   ```

4. **线上回读校验**
   不要只看上传命令的退出码，必须回读线上 APK 与三条清单链路核对 SHA-256 及字段一致性：

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

   确认本地与回读 APK 的 SHA-256 完全一致，且直连与两条代理返回的 `update.json` 在 `versionCode`、`versionName`、`size`、`sha256`、`notes` 上均与本地生成的清单相同。
