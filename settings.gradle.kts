import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (offline Cyrillic OCR) is published via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "gallery-lens"
include(":app")
