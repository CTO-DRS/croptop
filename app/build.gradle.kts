// Application shell, DI root, navigation.
plugins {
    alias(libs.plugins.croptop.android.application)
    alias(libs.plugins.croptop.android.compose)
    alias(libs.plugins.croptop.android.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.croptop.android.roborazzi)
}

android {
    namespace = "app.croptop.app"

    defaultConfig {
        applicationId = "app.croptop"
        versionCode = 1
        versionName = "0.1.0"
    }

    // `fake` builds without the NDK engine for fast UI work.
    flavorDimensions += "engine"
    productFlavors {
        create("fake") {
            dimension = "engine"
            applicationIdSuffix = ".fake"
            versionNameSuffix = "-fake"
        }
        create("full") {
            dimension = "engine"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local release builds are signed with the debug key until the release pipeline.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.engineApi)
    implementation(projects.core.data)
    implementation(projects.core.input)
    implementation(projects.core.agent)
    implementation(projects.core.ai)
    implementation(projects.core.cloud)
    implementation(projects.feature.home)
    implementation(projects.feature.editor)
    implementation(projects.feature.develop)
    implementation(projects.feature.library)
    implementation(projects.feature.export)
    implementation(projects.feature.settings)

    // The `fake` flavor binds FakeEngine; `full` binds NativeEngine from app/src/full.
    implementation(projects.core.engineFake)
    "fullImplementation"(projects.core.engine)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.metrics.performance)
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)
    implementation(libs.kotlinx.serialization.json)

    baselineProfile(projects.baselineprofile)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.exifinterface)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Licenses screen: ship the native inventory (core/engine/THIRD_PARTY.md) as an asset.
abstract class BundleNotices : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:InputDirectory
    abstract val licenseTexts: DirectoryProperty

    @get:InputDirectory
    abstract val appLicenseTexts: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("licenses").apply { mkdirs() }
        source.get().asFile.copyTo(dir.resolve("THIRD_PARTY.md"), overwrite = true)
        licenseTexts.get().asFile.copyRecursively(dir.resolve("texts"), overwrite = true)
        appLicenseTexts.get().asFile.copyRecursively(dir.resolve("texts"), overwrite = true)
    }
}

val bundleNotices = tasks.register<BundleNotices>("bundleThirdPartyNotices") {
    source.set(rootProject.layout.projectDirectory.file("core/engine/THIRD_PARTY.md"))
    licenseTexts.set(rootProject.layout.projectDirectory.dir("core/engine/licenses"))
    appLicenseTexts.set(layout.projectDirectory.dir("licenses/jvm"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleNotices, BundleNotices::outputDir)
    }
}
