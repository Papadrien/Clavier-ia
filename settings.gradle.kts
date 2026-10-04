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
    }
}

rootProject.name = "Taipo"
include(":app")

// Lot 4.4 (A5) : logique pure JVM (dictionnaire, suggestions), voir docs/modules.md.
include(":core")

// Lot 3.6 : module de macrobenchmarks, chargé seulement sur demande pour que le build normal (et la CI)
// ne dépende jamais de lui. Activer : -Ptaipo.benchmark=true, ou décommenter la ligne dans gradle.properties.
if (providers.gradleProperty("taipo.benchmark").isPresent) {
    include(":macrobenchmark")
}
