pluginManagement {
    includeBuild("build-logic")
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
    }
}

rootProject.name = "CropTop"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

// core
include(":core:designsystem")
include(":core:model")
include(":core:engine-api") // Kotlin engine contract
include(":core:engine") // Native engine
include(":core:engine-fake")
include(":core:io")
include(":core:color")
include(":core:raw")
include(":core:ai")
include(":core:data")
include(":core:cloud")
include(":core:input") // Keymap, shortcuts and pointer/stylus normalization
include(":core:agent") // Local MCP server and app-function tools

// features
include(":feature:home")
include(":feature:editor")
include(":feature:develop")
include(":feature:library")
include(":feature:export")
include(":feature:settings")

include(":benchmark")
include(":baselineprofile")
