import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.croptop.android.library)
    alias(libs.plugins.croptop.android.hilt)
}

dependencies {
    implementation(projects.core.model)
}

// This foundation module has no implementation or tests yet; keep aggregate `test` green until it does.
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}
