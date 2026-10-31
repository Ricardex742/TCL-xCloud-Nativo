pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://maven.pkg.github.com/stream-io/stream-webrtc-android") } // Repositório LibWebRTC
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "TCL-xCloud-Native"
include(":app")
