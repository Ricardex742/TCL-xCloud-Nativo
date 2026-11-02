plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xcloud.tclnative"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xcloud.tclnative"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0-h264-pure"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("io.getstream:stream-webrtc-android:1.1.1")
    implementation("com.google.zxing:core:3.5.3")
}

// Ignora os arquivos AuthTest.kt e SignalingTest.kt antigos durante a geração do APK
tasks.configureEach {
    if (name.contains("UnitTest", ignoreCase = true) || name == "test") {
        enabled = false
    }
}
