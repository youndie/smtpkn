plugins {
    id("smtp.publish")
    // Applied here for the same reason as in `smtp.kmp`: the shared convention configures Kotlin
    // without choosing its version.
    kotlin("jvm")
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
}

// A JVM-only module. Used where the platform API is the whole point — `SSLEngine`, for one — and a
// multiplatform wrapper would only add a layer with nothing on the other side.
//
// Nothing else is left here. Explicit API, the toolchain, warnings as errors and `kotlin("test")`
// come from `ru.workinprogress.sborka.jvm`, and so does the pair that used to be spelled out twice:
// the Kotlin `jvmTarget` and the Java release, both taken from `sborka.jvmFloor`. They have to agree
// or the build refuses to run, which is why they were written together — and one line naming them
// once is the point of moving them.
