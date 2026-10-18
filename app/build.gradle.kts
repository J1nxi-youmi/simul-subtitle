plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.korenkrita.simulsub"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.korenkrita.simulsub"
        minSdk = 29 // AudioPlaybackCapture
        targetSdk = 35
        // 注意：desktop/build.gradle.kts 用正则、tools/win-app/build.sh 用 sed 从本文件读版本号，
        // 两者都按「第一个匹配」/「所有匹配行」取，所以整个文件里只能有下面这一处版本号字面量。
        // CI 的覆盖写在后面，用 setter 而不是同样的赋值形式。
        versionCode = 27
        versionName = "1.0.7"
        // DIY: CI 传 CI_VERSION_CODE 时覆盖，App 内的更新器才有版本更新可比。
        System.getenv("CI_VERSION_CODE")?.toIntOrNull()?.let { code ->
            setVersionCode(code)
            setVersionName("1.0.7-diy." + code)
        }
        ndk { abiFilters += "arm64-v8a" }
    }

    // the speech models in assets/models (setup.sh): stored, so sherpa-onnx reads them from the APK without inflating
    androidResources { noCompress += "onnx" }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // DIY: CI 固定签名。默认的 debug keystore 由 AGP 在 runner 的临时目录里生成、用完不留，
    // 所以每次构建签名都不同，覆盖安装会失败（INSTALL_FAILED_UPDATE_INCOMPATIBLE），App 里的
    // 配置和 key 也跟着丢。这里在仓库里放一把固定的测试签名（.ci/debug.keystore，由 CI 首次生成
    // 并缓存），存在就用它；不存在时一切照旧，用 AGP 默认的 debug 签名。
    signingConfigs {
        getByName("debug") {
            val fixed = rootProject.file(".ci/debug.keystore")
            if (fixed.exists()) {
                storeFile = fixed
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // The sherpa-onnx AAR also ships its C and C++ API libraries; the Kotlin API only loads libsherpa-onnx-jni.so, which links none of their symbols.
    // useLegacyPackaging: .so files are stored compressed in the APK (release APK 13.6 MB at d83a627) and extracted
    // on install, so the installed footprint is larger (APK + extracted libraries) and the first install is slower.
    packaging {
        jniLibs {
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Jev and online MT: a Call can be cancelled before it runs, so stop never lets a request go out afterwards
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    // org.json is part of Android but stubbed out in local unit tests; the reference implementation stands in
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
