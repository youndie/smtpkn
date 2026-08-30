import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    id("smtp.publish")
    kotlin("multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
}

// A module that binds to a C library through cinterop.
//
// Only the host target is declared, and that is not a shortcut: cinterop needs the *target*
// platform's headers, so cross-compiling such a module from macOS to Linux does not work at all.
// The consequence for releases is that linuxX64 artifacts of this module have to be published
// from a Linux runner (M-100).
// NOT `smtp.kmp`: that convention declares the full target set, and this module cannot have it.
kotlin {
    when {
        HostManager.hostIsLinux -> linuxX64()
        HostManager.hostIsMac -> macosArm64()
        else -> error("Unsupported host for a cinterop module: only Linux and macOS are set up")
    }
}
