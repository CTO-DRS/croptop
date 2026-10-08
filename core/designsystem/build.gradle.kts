// Tokens, theme, components, icons.
plugins {
    alias(libs.plugins.croptop.android.library)
    alias(libs.plugins.croptop.android.compose)
    alias(libs.plugins.croptop.android.roborazzi)
}

dependencies {
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(projects.core.model)
    // The model download dialog shows AI model state (size, progress), shared by the editor and Develop.
    implementation(projects.core.ai)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Espresso 3.7 uses the public InputManager service on API 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.accessibility)
}
