package app.croptop.core.model

import kotlinx.serialization.Serializable

/** Home "Recent" entry. */
@Serializable
data class RecentDocument(
    val uri: String,
    val name: String,
    val size: DocSize?,
    val thumbnailPath: String?,
    val lastOpenedAt: Long,
    val pinned: Boolean,
)
