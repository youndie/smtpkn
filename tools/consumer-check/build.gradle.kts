import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The version under test. A timestamped snapshot works as well (`0.1.0-20260902.063559-4`), which
// is how the artefact published BEFORE the fix is shown to fail this build.
val smtpknVersion: String = providers.gradleProperty("smtpknVersion").getOrElse("0.1.0-SNAPSHOT")

// The host target, because the library publishes each native target from its own host.
val hostTarget: String =
    when {
        HostManager.hostIsLinux -> "linuxX64"
        HostManager.hostIsMac -> "macosArm64"
        else -> error("consumer-check runs on Linux and macOS only, like the library it checks")
    }

kotlin {
    val host = if (hostTarget == "linuxX64") linuxX64() else macosArm64()
    host.binaries.executable { entryPoint = "main" }

    // NO LINKER OPTIONS HERE, and none may ever be added. A consumer gets only what the klib
    // brings; one `-lssl` on this side would make the check pass for the same reason
    // `examples/send` always did.

    sourceSets {
        nativeMain.dependencies {
            implementation("io.github.youndie:smtp-tls-openssl:$smtpknVersion")
        }
    }
}

// One name on both hosts for the task that links AND runs the binary: linking proves the symbols
// resolve, running proves the shared object is found at start-up as well.
tasks.register("consumerCheck") {
    group = "verification"
    description = "Link and run a binary against the published smtp-tls-openssl, with no linker options of its own"
    dependsOn(tasks.named("runReleaseExecutable${hostTarget.replaceFirstChar(Char::uppercase)}"))
}
