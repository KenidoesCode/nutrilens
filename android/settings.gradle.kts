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
    // Modules must not declare their own repositories; resolution is centralised
    // so every artifact in the build comes from a known, auditable source.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Lets modules refer to each other as `projects.core.model` rather than
// stringly-typed paths, so a rename or a typo fails at configuration time.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "NutriLens"

include(":app")

include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:data")

include(":feature:auth")
include(":feature:home")
include(":feature:capture")
include(":feature:analysis")
include(":feature:timeline")
include(":feature:analytics")
include(":feature:settings")
