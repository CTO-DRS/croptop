import app.croptop.buildlogic.lib
import app.croptop.buildlogic.libs
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Feature modules: Compose + Hilt + the core modules a screen may use.
 * Enforces — features talk to engine-api only, never to core:engine.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "croptop.android.library")
        apply(plugin = "croptop.android.compose")
        apply(plugin = "croptop.android.hilt")
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
        dependencies {
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:model"))
            add("implementation", project(":core:engine-api"))
            add("implementation", project(":core:input"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-activity-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-navigation3-runtime"))
            add("implementation", libs.lib("androidx-compose-material3-adaptive"))
            add("implementation", libs.lib("kotlinx-serialization-json"))
            add("testImplementation", project(":core:engine-fake"))
        }
        afterEvaluate {
            listOf("api", "implementation").forEach { conf ->
                configurations.getByName(conf).dependencies
                    .filterIsInstance<ProjectDependency>()
                    .firstOrNull { it.path == ":core:engine" }
                    ?.let { throw GradleException("$path must not depend on :core:engine directly. Use :core:engine-api.") }
            }
        }
    }
}
