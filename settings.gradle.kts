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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NestSpace"
include(":app")
include(":plugin-api")
include(":runtime-api")
include(":runtime-plugin-api")
include(":runtime-core")
include(":runtime-compat")
include(":testapp")

fun sanitizeProjectName(value: String): String =
    value.lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "-")
        .trim('-')

fun includeDevPlugin(projectPath: String, directory: File) {
    if (!File(directory, "build.gradle.kts").isFile) return
    include(projectPath)
    project(projectPath).projectDir = directory
}

includeDevPlugin(
    ":dev-plugin-runtime-demo",
    file("example-plugin/plugin")
)

file("dev-plugins").listFiles()
    ?.filter { it.isDirectory }
    ?.sortedBy { it.name }
    ?.forEach { directory ->
        val name = sanitizeProjectName(directory.name)
        if (name.isNotEmpty()) {
            includeDevPlugin(":dev-plugin-$name", directory)
        }
    }
