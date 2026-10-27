@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        // maven { url=uri("https://maven.aliyun.com/repository/public") }
        // maven { url=uri("https://maven.aliyun.com/repository/central") }
        // maven { url=uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://jitpack.io") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()

        maven{ url =uri ("https://oss.sonatype.org/content/repositories/snapshots")}
    }

}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // maven { url=uri("https://maven.aliyun.com/repository/public") }
        // maven { url=uri("https://maven.aliyun.com/repository/google") }
        // maven { url=uri("https://maven.aliyun.com/repository/central") }
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()

        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }

    }
}

rootProject.name = "music"
include(":app")

// Local and CI source builds use a sibling checkout; CI provisions its pinned revision.
if (providers.gradleProperty("glasmio.source").orNull == "true") {
    val glasmioDirectory = file("../Glasmio")
    require(glasmioDirectory.resolve("settings.gradle.kts").isFile) {
        "-Pglasmio.source=true requires a Glasmio checkout beside MeiloX."
    }
    includeBuild(glasmioDirectory)
}
