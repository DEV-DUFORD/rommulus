// Opt-in desktop-only settings (excludes :app for machines without an Android SDK).
// Use: ./gradlew -c settings-desktop-only.gradle.kts :desktop:run
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
    }
}

rootProject.name = "romm-android-tv"
include(":shared:domain")
include(":shared:network")
include(":shared:storage-api")
include(":shared:presentation")
include(":shared:ui")
include(":desktop")
