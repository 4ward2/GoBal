pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (OCR for memory files) is only published on JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.adaptech-cz.*") }
        }
    }
}

rootProject.name = "AndroidRemoteControlMcp"
include(":app")
include(":compose-test-app")
include(":e2e-tests")
