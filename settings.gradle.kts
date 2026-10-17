pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Fallback if Maven Central is unreachable or returns 404 for a file from the CI runner
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}

rootProject.name = "EqualAI"
include(":app")
