// Room catalog, DataStore prefs, repositories.
plugins {
    alias(libs.plugins.croptop.android.library)
    alias(libs.plugins.croptop.android.hilt)
    alias(libs.plugins.room)
}

room {
    // Exported schemas are committed so migrations can be tested.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    api(libs.androidx.paging.common)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.paging.testing)
}

// Room MigrationTestHelper reads exported schemas from test assets (JVM/Robolectric tests).
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.assets?.addStaticSourceDirectory("$projectDir/schemas") }
    }
}
