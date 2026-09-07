dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // The shared conventions. This build applies them from inside its own convention scripts, so
        // it needs them on its compile classpath rather than through `pluginManagement`.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                // One group, and it is the only one there can be. The portfolio's move to
                // `io.github.youndie` is finished: nothing this build resolves is under
                // `ru.workinprogress` any more, and a filter naming a group the server is never asked
                // about reads as a dependency that is still there.
                includeGroupByRegex("io\\.github\\.youndie.*")
            }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
