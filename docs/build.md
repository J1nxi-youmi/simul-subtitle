# 构建与开发

## 环境

JDK 17，Android SDK（platform 35、build-tools 35、NDK 27.2.12479018、CMake 3.22.1）。minSdk 29，arm64-v8a。

llama.cpp 按 `armv8.6-a+dotprod+i8mm+fp16` 编译（`app/build.gradle.kts`，`GGML_NATIVE=OFF`，没有运行时分派），所以「手机上翻译」只能在支持这些指令的处理器上运行；不支持的手机选这个方式会因非法指令崩溃。识别和在线翻译不受影响。

```bash
git clone --recursive https://github.com/KorenKrita/simul-subtitle   # llama.cpp 是子模块
cd simul-subtitle
./setup.sh                      # 下载 sherpa-onnx AAR 到 app/libs，模型到 models/（Mac 测试用）
export JAVA_HOME=<jdk17> ANDROID_HOME=~/Library/Android/sdk
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

需要代理时：`HTTPS_PROXY=... ./setup.sh`，以及 `./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 ...`。

APK 在 `app/build/outputs/apk/release/app-release.apk`（约 13 MB，原生库压缩存放，安装时解压）。

## 模型

App 里点「下载模型」时，由系统 DownloadManager 从固定版本的 Hugging Face 仓库和 sherpa-onnx 的 GitHub Release 下载，可以在后台继续。每个文件使用前都校验大小和 SHA-256，下载不完整或损坏的文件不会被加载。

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

## 签名与包名

包名 `io.github.korenkrita.simulsub`。从旧包名 `dev.krita.jpsub` 的版本升级时会作为新 App 安装，需要重新下载模型。
