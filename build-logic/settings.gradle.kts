dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // The shared conventions. This build applies them from inside its own convention scripts, so
        // it needs them on its compile classpath rather than through `pluginManagement`.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
