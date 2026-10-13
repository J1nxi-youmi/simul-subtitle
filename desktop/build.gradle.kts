// The desktop app (macOS first): system audio → the shared core → a transparent always-on-top caption window.
// For now it runs from the repo with the libraries tools/mac-e2e/run.sh builds and the models ./setup.sh downloads:
//   tools/mac-e2e/run.sh && ./gradlew :desktop:run
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

val macE2e = rootProject.layout.projectDirectory.dir("tools/mac-e2e/build")

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.json:json:20240303")
    implementation(files(macE2e.file("sherpa-jvm.jar")))
}

compose.desktop {
    application {
        mainClass = "io.github.korenkrita.simulsub.MainKt"
        jvmArgs += listOf(
            "-Djava.library.path=${macE2e.dir("native").asFile}",
            "-Dsimulsub.root=${rootProject.projectDir}",
        )
    }
}
