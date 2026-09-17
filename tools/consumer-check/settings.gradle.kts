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
    }
    versionCatalogs {
        // Only for the Kotlin version: the consumer must compile with the compiler that produced the
        // klib, and that number is not going to be copied here to drift.
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "consumer-check"
