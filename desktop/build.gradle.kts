// The desktop app (macOS on Apple silicon, Windows x64): system audio → the shared core → a transparent always-on-top
// caption window.
// From the repo, with the libraries tools/mac-e2e/run.sh builds and the models ./setup.sh downloads:
//   tools/mac-capture/build.sh && tools/mac-e2e/run.sh && ./gradlew :desktop:run
// The dmg: tools/mac-app/build.sh (it builds the native libraries itself and passes -Psimulsub.dist).
// The Windows zip: tools/win-app/build.sh on Windows (Git Bash, MSVC), run by .github/workflows/windows.yml.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

// the Android app's version: one release carries the APK and the dmg
val appGradle = rootProject.file("app/build.gradle.kts").readText()
val appVersion = Regex("versionName = \"([^\"]+)\"").find(appGradle)!!.groupValues[1]
val appCode = Regex("versionCode = (\\d+)").find(appGradle)!!.groupValues[1]

val macE2e = rootProject.layout.projectDirectory.dir("tools/mac-e2e/build")
// tools/mac-app's or win-app's output: resources/macos-arm64 or windows-x64 (the native libraries, sysaudio and the
// models) and sherpa-jvm.jar
val dist = (findProperty("simulsub.dist") as String?)?.let { file(it) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Windows: the keys, encrypted with DPAPI (Crypt32Util)
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation(files((dist ?: macE2e.asFile).resolve("sherpa-jvm.jar")))
    // Prefs is exercised against its own settings file and keychain service (see PrefsTest)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "io.github.korenkrita.simulsub.MainKt"
        jvmArgs += listOf("-Dsimulsub.version=$appVersion", "-Dsimulsub.versionCode=$appCode")
        // packaged, the native libraries and sysaudio sit in compose.application.resources.dir (see Main.kt)
        if (dist == null) {
            jvmArgs += listOf(
                "-Djava.library.path=${macE2e.dir("native").asFile}",
                "-Dsimulsub.root=${rootProject.projectDir}",
                // from the repo: the models ./setup.sh downloaded, a data folder of its own
                "-Dsimulsub.models=${rootProject.projectDir}/models",
                "-Dsimulsub.data=${layout.buildDirectory.dir("run-data").get().asFile}",
            )
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "SimulSub"
            // jpackage wants MAJOR > 0: the bundle's own version is 1.<versionCode> (it only orders builds for macOS);
            // the app, the Release and the dmg's file name show versionName (-Dsimulsub.version)
            packageVersion = "1.$appCode"
            // jpackage on Windows reads its arguments in the system code page: ASCII only there
            val windows = System.getProperty("os.name").startsWith("Windows")
            description = if (windows) "SimulSub: live captions for what the computer plays" else "同传字幕：把电脑播放的日语和英语实时变成字幕"
            vendor = "KorenKrita"
            copyright = if (windows) "(c) KorenKrita" else "© KorenKrita"
            modules("java.net.http", "jdk.crypto.ec", "java.naming")
            // Windows: the Windows-ROOT trust store (Main.kt), only in Windows' JDK
            if (windows) modules("jdk.crypto.mscapi")
            dist?.let { appResourcesRootDir.set(it.resolve("resources")) }
            // the exe's icon (the zip's folder, see tools/win-app); icons/make.py draws both
            windows { iconFile.set(project.file("icons/icon.ico")) }
            macOS {
                iconFile.set(project.file("icons/icon.icns"))
                bundleID = "io.github.korenkrita.simulsub"
                dockName = "同传字幕"
                minimumSystemVersion = "14.2"
                // the menu bar icon keeps captions on with the window closed; the Dock icon reopens it
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSAudioCaptureUsageDescription</key>
                        <string>同传字幕要听这台 Mac 播放的声音，才能把它变成字幕。声音只在电脑上识别，不会上传。</string>
                        <key>CFBundleDisplayName</key>
                        <string>同传字幕</string>
                    """.trimIndent()
                }
            }
        }
    }
}
