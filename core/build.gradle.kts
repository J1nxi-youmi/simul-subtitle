// Platform-free core shared by the Android app and the desktop app: segmentation, the ASR/MT pipeline, translation
// prompts and the BYOK translator, Jev, session logs and service status.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    // Jev and online MT: a Call can be cancelled before it runs, so stop never lets a request go out afterwards
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json is part of Android; on the desktop (and in tests) the reference implementation provides it
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
