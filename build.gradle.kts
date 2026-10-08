plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.spotless)
}

// Two agents build this checkout at the same time. Sharing module build/ dirs corrupts incremental
// Kotlin outputs (NoClassDefFoundError), so each agent may pass its own output dir, e.g.
// `-Pcroptop.buildDir=build-cl`. Unset = the normal build/ dirs.
providers.gradleProperty("croptop.buildDir").orNull?.let { dir ->
    allprojects { layout.buildDirectory.set(layout.projectDirectory.dir(dir)) }
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/build-*/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.kts")
        targetExclude("**/build/**", "**/build-*/**")
        ktlint(libs.versions.ktlint.get())
    }
}
