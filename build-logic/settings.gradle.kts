dependencyResolutionManagement {
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

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
include(":convention")
