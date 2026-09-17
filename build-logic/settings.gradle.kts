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
        // The shared catalog, taken as what it is — a published one. This build does not get it the
        // way the main build does: it has its own settings and does not apply `sborka.settings`,
        // which is what being an included build means here.
        //
        // The pin is READ out of the main catalog rather than written again. A second copy of
        // 0.4.0.x is the drift this move removes, in its worst form: the conventions on this
        // classpath would come from one release and the compiler from another, and both would
        // build.
        create("wip") {
            val pin = file("../gradle/libs.versions.toml").readLines()
                .first { it.trimStart().startsWith("sborka = ") }
                .substringAfter('"').substringBefore('"')
            from("io.github.youndie.sborka:catalog:$pin")
        }
    }
}

rootProject.name = "build-logic"
