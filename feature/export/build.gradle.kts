import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.croptop.android.feature)
}

// The export feature is still an empty scaffold, so its placeholder suite has no tests to discover.
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}
