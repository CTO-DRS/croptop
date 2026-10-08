import app.croptop.buildlogic.croptopNamespace
import app.croptop.buildlogic.configureKotlinAndroid
import app.croptop.buildlogic.lib
import app.croptop.buildlogic.libs
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "com.android.library")
        extensions.configure<LibraryExtension> {
            namespace = croptopNamespace
            configureKotlinAndroid(this)
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            testOptions.unitTests.isIncludeAndroidResources = true
            // Pure-logic tests touch android.* constants/stubs; don't throw on unmocked calls.
            testOptions.unitTests.isReturnDefaultValues = true
            lint.targetSdk = libs.findVersion("targetSdk").get().requiredVersion.toInt()
        }
        dependencies {
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("kotlin-test-junit"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            add("testImplementation", libs.lib("turbine"))
        }
    }
}
