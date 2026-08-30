plugins {
    id("smtp.publish")
    // The Kotlin plugin is applied HERE and not by the shared convention. `sborka.kmp` configures
    // Kotlin and deliberately does not choose its version — so a wrapper like this one has to bring
    // it, or there is no `kotlin { }` extension for the lines below to configure.
    kotlin("multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
}

// The multiplatform library shape of this repository: the shared mechanics come from
// `ru.workinprogress.sborka.kmp` — explicit API, the toolchain, warnings as errors, `kotlin("test")`
// in `commonTest`, and the jvm target compiled to `sborka.jvmFloor` — and what stays here is the
// TARGET SET, which is a decision this repository argued out and no other repository shares.

kotlin {
    // The JVM. Its bytecode level is not written here any more: `sborka.kmp` compiles it to
    // `sborka.jvmFloor`, which is also what `sborka.publish` advertises — the JDK that builds this
    // library is not the JDK that has to run it, and now that is one number instead of two.
    jvm()

    // Target platform number one; milestones are closed against it.
    linuxX64()

    // The host target for the local TDD loop: linuxX64 tests do not run on macOS.
    // See docs/research/research-architecture.md, D9.
    macosArm64()

    // Server platforms next in line. They compile in every gate; their tests run only on a
    // matching host, which nobody here has — see the module documents for what that means.
    linuxArm64()
    macosX64()
    mingwX64()

    // Apple mobile, compile only. The protocol layer is pure Kotlin and works there, but there is
    // no TLS provider for it yet (M-83), and the simulator targets are left out on purpose: their
    // test tasks need an iOS SDK that is not installed here, and a test task that cannot run is
    // worse than an absent target — it looks like coverage.
    iosArm64()
}
