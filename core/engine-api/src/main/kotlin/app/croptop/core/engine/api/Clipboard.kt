package app.croptop.core.engine.api

import app.croptop.core.model.DocRect
import app.croptop.core.model.DocSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Edit ▸ Paste would add. The engine owns the pixels and shares them across its documents.
 * [origin] is where they were copied from, for Paste in Place.
 */
data class ClipboardInfo(val size: DocSize, val origin: DocRect, val merged: Boolean)

internal val EmptyClipboard: StateFlow<ClipboardInfo?> = MutableStateFlow<ClipboardInfo?>(null).asStateFlow()
