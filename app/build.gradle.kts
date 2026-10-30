plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
}

android {
    namespace = "com.jarvis.pineapple"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.pineapple"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // BuildConfig 字段：运行时可配置的服务地址（默认值可直接运行）
        // 语音包下载 - 默认使用本地 assets 目录（首次启动自动复制）
        buildConfigField("String", "VOICE_DOWNLOAD_BASE_URL", "\"file:///android_asset/voices/\"")
        // 云端同步 - 默认关闭（设为空字符串时自动禁用云端功能）
        buildConfigField("String", "CLOUD_SYNC_BASE_URL", "\"\"")
        // 翻译 API - 默认使用 DuckDuckGo HTML（国内可直接访问）
        buildConfigField("String", "TRANSLATE_API_BASE_URL", "\"https://html.duckduckgo.com/html/\"")
        // 地图服务商 - 国内首选高德
        buildConfigField("String", "MAP_PROVIDER", "\"amap\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

sqldelight {
    databases {
        create("JarvisDatabase") {
            packageName.set("com.jarvis.pineapple.db")
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Koin DI
    implementation("io.insert-koin:koin-android:3.5.6")
    implementation("io.insert-koin:koin-androidx-compose:3.5.6")

    // SQLDelight
    implementation("app.cash.sqldelight:android-driver:2.0.2")
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")

    // Ktor
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-client-logging:2.3.12")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Biometric
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // BouncyCastle
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")

    // Apache Commons Compress (tar.gz / tar.xz rootfs 解压)
    implementation("org.apache.commons:commons-compress:1.21")
    implementation("org.tukaani:xz:1.9")

    // ML Kit
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("com.google.mlkit:object-detection:17.0.1")
    implementation("com.google.mlkit:vision-common:17.3.0")

    // LiteRT-LM
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    // ONNX Runtime (Piper TTS 男声模型推理)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
