// Keyboard shortcuts, action registry, pointer/trackpad/stylus normalization.
plugins {
    alias(libs.plugins.croptop.android.library)
    alias(libs.plugins.croptop.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
