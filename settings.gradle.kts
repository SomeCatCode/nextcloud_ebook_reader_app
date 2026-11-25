pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com.android.*")
                includeGroupByRegex("com.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Mirror of Maven Central that Google operates. Maven Central itself often answers HTTP 429 to CI runners.
        // Gradle asks Maven Central only for files that the mirror does not have.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Mirror of Maven Central that Google operates. Maven Central itself often answers HTTP 429 to CI runners.
        // Gradle asks Maven Central only for files that the mirror does not have.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}

rootProject.name = "nextcloud-ebook-reader-app"
include(":app")
