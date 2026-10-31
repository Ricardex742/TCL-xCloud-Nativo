plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xcloud.tclnative"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xcloud.tclnative"
        // MUDANÇA CRÍTICA: minSdk = 24 libera instalação no Android 7, 8, 9, 10 (API 29)
        // mantendo 100% dos recursos de Android 11/12/14 quando rodar na TCL P755!
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}
