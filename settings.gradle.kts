pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "visor"

// The catalog's pin-* versions on every configuration, build script classpaths included.
gradle.beforeProject {
    // Read at resolution, when the project has its catalog.
    val pins by lazy {
        val libs = extensions.findByType<VersionCatalogsExtension>()?.find("libs")?.orElse(null)
        libs?.libraryAliases.orEmpty().filter { it.startsWith("pin.") }.map { libs!!.findLibrary(it).get().get() }
            .associate { it.module.toString() to it.version.orEmpty() }
    }
    val pin: DependencyResolveDetails.() -> Unit = { pins[requested.module.toString()]?.let { useVersion(it) } }
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(pin) }
    configurations.configureEach { resolutionStrategy.eachDependency(pin) }
}

include(":app")

include(":core:protocol")
include(":core:transport")
include(":core:session")
include(":core:identity")
include(":core:connection")
include(":core:media")
include(":core:settings")
include(":core:assistant")
include(":core:design")

