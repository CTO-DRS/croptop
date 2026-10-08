// Agent control: local MCP server and tools over engine-api.
plugins {
    alias(libs.plugins.croptop.android.library)
    alias(libs.plugins.croptop.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.engineApi)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(projects.core.data)
    testImplementation(projects.core.engineFake)
}
