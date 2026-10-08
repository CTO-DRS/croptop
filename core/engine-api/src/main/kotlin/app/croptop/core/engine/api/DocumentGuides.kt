package app.croptop.core.engine.api

import app.croptop.core.model.GridSettings
import app.croptop.core.model.Guide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Document-owned view-aid geometry: guide positions and grid spacing/subdivisions, saved in
 * `.croptop`. Show/hide, snap and lock toggles stay per view in `ViewAids`.
 */
data class DocumentGuides(val guides: List<Guide> = emptyList(), val grid: GridSettings = GridSettings()) {
    init {
        require(guides.size <= MAX_GUIDES) { "at most $MAX_GUIDES guides" }
        require(guides.all { it.position.isFinite() }) { "guide positions must be finite" }
    }

    companion object {
        const val MAX_GUIDES = 10_000
    }
}

internal val NoDocumentGuides: StateFlow<DocumentGuides> = MutableStateFlow(DocumentGuides()).asStateFlow()
