package app.croptop.buildlogic

import org.gradle.api.Project

/** Googlebook ships Intel Core Ultra (x86_64) and Snapdragon X Elite (arm64) — both are mandatory. */
val CROPTOP_ABIS = listOf("arm64-v8a", "x86_64")

/** `-Pcroptop.abi=x86_64` narrows a build to one ABI (CI matrix, fast local installs). */
fun Project.croptopAbis(): List<String> {
    val requested = providers.gradleProperty("croptop.abi").orNull ?: return CROPTOP_ABIS
    require(requested in CROPTOP_ABIS) { "croptop.abi must be one of $CROPTOP_ABIS, was '$requested'" }
    return listOf(requested)
}
