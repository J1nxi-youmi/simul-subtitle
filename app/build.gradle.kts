plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.korenkrita.simulsub"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.korenkrita.simulsub"
        minSdk = 29 // AudioPlaybackCapture
        targetSdk = 35
        versionCode = 15
        versionName = "0.6.4"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    // Snapdragon 8 Gen 3: armv9-a with dotprod / i8mm / fp16
                    "-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm+fp16",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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
