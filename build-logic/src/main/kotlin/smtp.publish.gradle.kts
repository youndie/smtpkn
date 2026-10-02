plugins {
    id("com.vanniktech.maven.publish")
}

// Publication settings shared by every module.
//
// Credentials and the signing key are never written here: they come from the environment
// (ORG_GRADLE_PROJECT_mavenCentralUsername, ...Password, ...signingInMemoryKey and its password),
// so a checkout of this repository can build and test but cannot release.
// Snapshots go to a private Reposilite; releases go to Maven Central. Both read their credentials
// from the environment, so a checkout can build and test but cannot publish.
publishing {
    repositories {
        maven {
            name = "WipSnapshots"
            url = uri("https://reposilite.kotlin.website/snapshots")
            credentials {
                username = providers.environmentVariable("REPOSILITE_USER").orNull
                password = providers.environmentVariable("REPOSILITE_SECRET").orNull
            }
        }

        // A file repository for the consumer check (M-110): CI publishes here and then links
        // `tools/consumer-check` against it, the way a stranger's build would.
        maven {
            name = "ConsumerCheck"
            url = uri(rootProject.layout.buildDirectory.dir("consumer-check-repo"))
        }
    }
}

// THE POM'S REPOSITORY, DESCRIPTION AND YEAR ARE READ FROM `gradle.properties`, under the names
// sborka's own publish convention reads. This repository does not apply that convention (releases go
// through vanniktech here, see RELEASING.md), so those three lines used to be read by nothing while
// this script spelled the same values again. When the repository was renamed to youndie/smtpkn, both
// copies kept the old name, and every published POM pointed at a GitHub redirect. One copy now.
fun required(key: String): String =
    providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }
        ?: error("$key is not set in gradle.properties; the POM of '${project.path}' is built from it")

val repository = required("sborka.repository")

mavenPublishing {
    // Publishing goes to the Central Portal; the release itself stays manual on purpose —
    // an automatic release cannot be taken back.
    publishToMavenCentral()

    // Snapshots are not signed: Central requires a signature, a snapshot repository does not, and
    // demanding a GPG key for every snapshot would put one into CI for no reason.
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set(project.name)
        description.set(required("sborka.description"))
        inceptionYear.set(required("sborka.inceptionYear"))
        url.set("https://github.com/$repository")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/$repository/blob/main/LICENSE")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("youndie")
                name.set("Pavel Votyakov")
                url.set("https://github.com/youndie")
            }
        }

        scm {
            url.set("https://github.com/$repository")
            connection.set("scm:git:git://github.com/$repository.git")
            developerConnection.set("scm:git:ssh://git@github.com/$repository.git")
        }
    }
}
