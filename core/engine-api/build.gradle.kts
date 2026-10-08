// The engine contract between the UI and the native engine.
plugins {
    alias(libs.plugins.croptop.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    testFixtures.enable = true
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testFixturesApi(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
