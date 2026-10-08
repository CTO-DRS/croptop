package app.croptop.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** Namespace derived from the Gradle path: `:core:engine-api` → `app.croptop.core.engine.api`. */
val Project.croptopNamespace: String
    get() = "app.croptop" + path.replace(':', '.').replace('-', '.')
