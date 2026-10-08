// On-device model registry entries: what a model is, where it comes from, under which license.
package app.croptop.core.ai

import kotlinx.serialization.Serializable

@Serializable
enum class ModelTask { SEGMENT_SUBJECT, SEGMENT_PROMPT, INPAINT, DENOISE, UPSCALE, DEPTH, MATTING }

/** One file of a model: [sha256] is lowercase hex and is checked after every download. */
@Serializable
data class ModelFile(val name: String, val sha256: String, val sizeBytes: Long, val url: String)

/** [spdx] is the upstream license id. Only models marked [commercialUse] may be registered. */
@Serializable
data class ModelLicense(
    val spdx: String,
    val commercialUse: Boolean,
    val attribution: String = "",
    val sourceUrl: String = "",
)

@Serializable
data class ModelSpec(
    val id: String,
    val version: Int,
    val task: ModelTask,
    val displayName: String,
    val files: List<ModelFile>,
    val license: ModelLicense,
    /** Square input edge the network expects, 0 when it accepts any size. */
    val inputSize: Int = 0,
    val notes: String = "",
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** Everything wrong with this entry; empty means it may be registered. */
    fun problems(): List<String> = buildList {
        if (!ID.matches(id)) add("id '$id' must be 1-64 lowercase letters, digits, '-' or '_'")
        if (version < 1) add("version must be at least 1")
        if (displayName.isBlank()) add("displayName is empty")
        if (files.isEmpty()) add("no files")
        if (files.map { it.name }.toSet().size != files.size) add("duplicate file names")
        for (file in files) {
            if (!FILE_NAME.matches(file.name)) add("file name '${file.name}' is not a plain file name")
            if (!SHA256.matches(file.sha256)) add("file '${file.name}' needs a 64-digit lowercase hex sha256")
            if (file.sizeBytes <= 0L) add("file '${file.name}' needs a positive size")
            if (!file.url.startsWith("https://")) add("file '${file.name}' must be fetched over https")
        }
        if (license.spdx.isBlank()) add("license id is empty")
        if (!license.commercialUse) add("license '${license.spdx}' does not allow commercial use")
        val spdx = license.spdx.uppercase()
        if (NON_COMMERCIAL_MARKERS.any { spdx.contains(it) }) add("license '${license.spdx}' is non-commercial")
        if (inputSize < 0) add("inputSize must not be negative")
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val NON_COMMERCIAL_MARKERS = listOf("-NC", "NONCOMMERCIAL", "NON-COMMERCIAL", "RESEARCH", "AGPL", "GPL-")
    }
}
