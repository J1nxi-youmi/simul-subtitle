package io.github.korenkrita.simulsub

import java.io.File

/** Same version as the Android app of this release (one GitHub Release carries the APK and the dmg). */
val APP_VERSION: String = System.getProperty("simulsub.version") ?: "dev"
val APP_VERSION_CODE: Int = System.getProperty("simulsub.versionCode")?.toIntOrNull() ?: 0

/** Downloaded models (asr/, vad/, mt/), under the app's data folder unless -Dsimulsub.models points elsewhere. */
val modelsDir: File = File(System.getProperty("simulsub.models") ?: File(dataDir, "models").path).also { it.mkdirs() }

/** The audio helper: next to the app's resources in the package, or tools/mac-capture's build when run from the repo. */
val sysaudioFile: File = System.getProperty("compose.application.resources.dir")?.let { File(it, "sysaudio") }
    ?.takeIf { it.isFile }
    ?: File(System.getProperty("simulsub.root") ?: ".", "tools/mac-capture/build/sysaudio")

fun main() {
    // the packaged app: sherpa-onnx loads its dylibs from here (MacLlama finds its own the same way)
    System.getProperty("compose.application.resources.dir")?.let { System.setProperty("sherpa_onnx.native.path", it) }
    // Cmd-Q / SIGTERM: the helper only notices a lost reader when it next writes, so end it here (not via the UI
    // thread, which may already be gone)
    Runtime.getRuntime().addShutdownHook(Thread { CaptionService.killCapture() })
    app()
}
