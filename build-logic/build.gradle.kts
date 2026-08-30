plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.mavenPublish.gradlePlugin)
    // THE SHARED CONVENTIONS, applied from inside this build's own scripts. What stays here is what
    // is this repository's: which targets each class of module declares, and publishing to Maven
    // Central rather than only to a snapshot server.
    implementation(libs.sborka.conventions)
}
