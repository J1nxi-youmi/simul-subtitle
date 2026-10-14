package io.github.korenkrita.simulsub

import java.io.File

/** Same version as the Android app of this release (one GitHub Release carries the APK, the dmg and the zip). */
val APP_VERSION: String = System.getProperty("simulsub.version") ?: "dev"
val APP_VERSION_CODE: Int = System.getProperty("simulsub.versionCode")?.toIntOrNull() ?: 0

/** Windows (x64, the zip from tools/win-app) or macOS (Apple silicon, the dmg): the few things that differ ask this. */
val isWindows = System.getProperty("os.name").startsWith("Windows")
/** "这台 Mac" / "这台电脑" in texts */
val thisComputer = if (isWindows) "这台电脑" else "这台 Mac"

/**
 * The speech models (asr/, vad/): inside the packaged app (its resources, put there by tools/mac-app or win-app), or
 * where -Dsimulsub.models points (./models when run from the repo, see ./setup.sh).
 */
val modelsDir: File = File(System.getProperty("simulsub.models")
    ?: File(System.getProperty("compose.application.resources.dir") ?: ".", "models").path)

/** The audio helper: next to the app's resources in the package, or tools/mac-capture's build when run from the repo. */
val sysaudioFile: File = System.getProperty("compose.application.resources.dir")
    ?.let { File(it, if (isWindows) "sysaudio.exe" else "sysaudio") }
    ?.takeIf { it.isFile }
    ?: File(System.getProperty("simulsub.root") ?: ".", "tools/mac-capture/build/sysaudio")

fun main() {
    // the packaged app: sherpa-onnx loads its native libraries from here
    System.getProperty("compose.application.resources.dir")?.let { System.setProperty("sherpa_onnx.native.path", it) }
    // quitting / SIGTERM: the helper only notices a lost reader when it next writes, so end it here (not via the UI
    // thread, which may already be gone)
    Runtime.getRuntime().addShutdownHook(Thread { CaptionService.killCapture() })
    app()
}
