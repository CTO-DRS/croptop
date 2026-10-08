plugins {
    alias(libs.plugins.croptop.android.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.engineApi)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    testImplementation(projects.core.engineFake)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
