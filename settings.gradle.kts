pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Android-форк JAudiotagger (тег-райтер FLAC/MP3/M4A) живёт только тут.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "RipsterMobile"
include(":app")
