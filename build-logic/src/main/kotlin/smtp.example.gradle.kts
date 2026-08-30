import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
}

// An example, not a library: it is never published, and it exists so that the quick start cannot
// rot unnoticed — every build compiles it.
//
// The Kotlin plugin arrives through the convention rather than through the version catalog: the two
// come on different classloaders, and mixing them fails at configuration time.
kotlin {
    // NOT A LIBRARY: nothing is published and nothing depends on it, so there is no consumer for a
    // spelled-out public API to be spelled out FOR. The rest of what the convention brings — the
    // toolchain, warnings as errors — is wanted here as much as anywhere.
    explicitApi = null

    linuxX64()
    macosArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            entryPoint = "main"
        }
    }
}
