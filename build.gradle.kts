plugins {
    base
}

// The coordinates and the version used to be set here and copied down to every subproject. They are
// now `sborka.group` and `version` in `gradle.properties`, applied by `ru.workinprogress.sborka.base`
// — which every module reaches through the conventions in `build-logic`.
//
// The ktlint CLI that used to live here is gone too. It was wired in by hand because this project
// wanted exactly 1.8.0 and exactly its behaviour (research, D10); `sborka.lint` pins the same 1.8.0
// and, with it, the `.editorconfig` the tool reads — which is the other half of what a formatter's
// behaviour is, and the half a version number cannot pin.
