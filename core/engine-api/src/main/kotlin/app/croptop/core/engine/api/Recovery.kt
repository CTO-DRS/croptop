package app.croptop.core.engine.api

import app.croptop.core.model.DocSize

/**
 * A document an unexpected exit left behind: what the launch "Recover N documents" sheet shows
 * without loading pixels. [id] is opaque; pass it back to [Engine.recover] or [Engine.discardRecovery].
 */
data class RecoverableDocument(
    val id: String,
    val name: String,
    val size: DocSize,
    val lastEditEpochMs: Long,
    /** Where the document was opened from or last saved to, if anywhere. */
    val sourceUri: String? = null,
)
