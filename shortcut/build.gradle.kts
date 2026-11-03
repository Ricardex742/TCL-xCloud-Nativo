plugins { id("com.android.application") }
android {
    namespace = "com.xcloud.shortcut"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.xcloud.shortcut.template"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
