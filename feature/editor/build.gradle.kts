plugins {
    alias(libs.plugins.croptop.android.feature)
    alias(libs.plugins.croptop.android.roborazzi)
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.agent)
    implementation(projects.core.ai)
    implementation(libs.androidx.input.motionprediction)
    androidTestImplementation(projects.core.engineFake)
    androidTestImplementation(projects.core.engine)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
