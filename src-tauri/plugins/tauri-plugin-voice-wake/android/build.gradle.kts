plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 引擎与中文唤醒词模型体积较大（原生库约 30MB、模型约 12MB），不进仓库，
// 由 scripts/fetch-voice-wake-deps.mjs 在构建前准备。
val sherpaJar = file("libs/sherpa-onnx.jar")
require(sherpaJar.exists()) {
    "缺少 libs/sherpa-onnx.jar，请先运行：node scripts/fetch-voice-wake-deps.mjs"
}

android {
    namespace = "com.noiq.lingchat.voicewake"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // 只打包 arm64-v8a：项目 APK 仅构建 aarch64，其余 ABI 只会撑大体积。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":tauri-android"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    // 汉字 → 拼音（含声调），用于把唤醒词映射到模型词表
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation(files(sherpaJar))
}

