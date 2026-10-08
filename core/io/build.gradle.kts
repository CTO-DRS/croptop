plugins {
    alias(libs.plugins.croptop.android.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
}
