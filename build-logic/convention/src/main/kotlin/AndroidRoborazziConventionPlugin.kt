import app.croptop.buildlogic.lib
import app.croptop.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/** Screenshot tests on the JVM (Robolectric native graphics + Roborazzi). */
class AndroidRoborazziConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "io.github.takahirom.roborazzi")
        dependencies {
            add("testImplementation", libs.lib("robolectric"))
            add("testImplementation", libs.lib("roborazzi"))
            add("testImplementation", libs.lib("roborazzi-compose"))
            add("testImplementation", libs.lib("roborazzi-junit-rule"))
            add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
            add("testImplementation", libs.lib("androidx-compose-ui-test-accessibility"))
            add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))
        }
        tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
            systemProperty("robolectric.graphicsMode", "NATIVE")
            systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            maxHeapSize = "2g"
        }
    }
}
