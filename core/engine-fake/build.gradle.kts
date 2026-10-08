// Pure-Kotlin fake of :core:engine-api for UI work, previews and tests.
plugins {
    alias(libs.plugins.croptop.android.library)
}

dependencies {
    api(projects.core.engineApi)
    testImplementation(testFixtures(projects.core.engineApi))
}
