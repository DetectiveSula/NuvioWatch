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

rootProject.name = "NuvioWatch"
include(":core", ":desktop")
// The Mac build skips the Android app (it needs the Android SDK).
if (!providers.gradleProperty("desktopOnly").isPresent) include(":app")
