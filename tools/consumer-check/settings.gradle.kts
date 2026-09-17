// A CONSUMER, NOT A MODULE. This build is deliberately absent from the root settings: it knows the
// library by coordinate and repository URL only, exactly as a stranger's project would. That is the
// whole point — `examples/send` shares source sets and linker options with the library, so a klib
// published without its linker options is invisible to it by construction (M-110).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Where the artefact under test comes from: the file repository that
// `publishAllPublicationsToConsumerCheckRepository` writes in CI, or the snapshot server by default.
val smtpknRepo: String =
    providers.gradleProperty("smtpknRepo").getOrElse("https://reposilite.kotlin.website/snapshots")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(smtpknRepo) {
            name = "smtpkn"
            content { includeGroup("io.github.youndie") }
        }
        // Where the shared catalog comes from. Separate from the repository above, because that one
        // points at wherever the artefact under test lives — a CI file repository, usually — and the
        // catalog is not published there.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("io\\.github\\.youndie\\.sborka.*") }
        }
    }
    versionCatalogs {
        // Only for the Kotlin version: the consumer must compile with the compiler that produced the
        // klib, and that number is not going to be copied here to drift.
        //
        // It lives in `wip` now — the catalog a sborka release publishes — so the reason points
        // there. This build is deliberately outside the root settings and applies no sborka plugin,
        // so it takes the catalog as what it is, published, with the release READ out of the main
        // catalog rather than written here. Copying the number is exactly what the sentence above
        // refuses, and it is the form of drift that still builds.
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
        create("wip") {
            val pin = file("../../gradle/libs.versions.toml").readLines()
                .first { it.trimStart().startsWith("sborka = ") }
                .substringAfter('"').substringBefore('"')
            from("io.github.youndie.sborka:catalog:$pin")
        }
    }
}

rootProject.name = "consumer-check"
