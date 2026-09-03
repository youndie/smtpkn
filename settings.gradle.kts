pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

plugins {
    // The repositories with their content filters, the shared `wip` catalog, and the check that this
    // repository's `.editorconfig` is the one the rest of them use.
    id("ru.workinprogress.sborka.settings") version "0.1.0.23"
}

dependencyResolutionManagement {
    repositories {
        // WHERE NODE COMES FROM, declared here because it cannot be declared where the Kotlin plugin
        // wants to declare it. The js and wasmJs targets need a Node distribution, and the plugin adds
        // an ivy repository for it TO THE PROJECT — which `sborka.settings` refuses, because a build
        // resolving the same coordinate from different places depending on which module asked is the
        // thing FAIL_ON_PROJECT_REPOS exists to stop. Moving it here keeps the rule and keeps Node.
        //
        // Not in the shared conventions: two repositories in the portfolio have JS targets, and an
        // ivy repository for a Node tarball has no business in the seventeen that do not.
        // Filtered, and that is not decoration: an unfiltered repository takes part in resolving EVERY
        // dependency, and when it is unreachable Gradle disables it and fails everything that had not
        // resolved earlier in the list — artifacts that are perfectly fine included.
        ivy("https://nodejs.org/dist/") {
            name = "Node distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }

        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "kmp-smtp-client"

include(":examples:send")
include(":smtp-core")
include(":smtp-client")
include(":smtp-mime")
include(":smtp-sasl")
include(":smtp-testing")
include(":smtp-tls-jvm")
include(":smtp-tls-openssl")
include(":smtp-transport-ktor")
