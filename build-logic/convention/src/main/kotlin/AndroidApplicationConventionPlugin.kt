import app.croptop.buildlogic.croptopAbis
import app.croptop.buildlogic.configureKotlinAndroid
import app.croptop.buildlogic.libs
import app.croptop.buildlogic.version
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "com.android.application")
        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            ndkVersion = libs.version("ndk")
            defaultConfig {
                targetSdk = libs.version("targetSdk").toInt()
                ndk { abiFilters += croptopAbis() }
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            buildFeatures.buildConfig = true
            testOptions.unitTests.isIncludeAndroidResources = true
            testOptions.unitTests.isReturnDefaultValues = true
        }
    }
}
