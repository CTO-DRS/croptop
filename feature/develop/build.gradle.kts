plugins {
    alias(libs.plugins.croptop.android.feature)
    alias(libs.plugins.croptop.android.roborazzi)
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.engineApi)
    implementation(projects.core.ai)
}
