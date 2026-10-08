package app.croptop.core.engine.api

import app.croptop.core.model.DocRect
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A saved selection (Photoshop alpha channel),. [bounds] is the channel's selected area, null
 * when it is empty.
 */
data class SelectionChannel(val name: String, val bounds: DocRect?)

internal val EmptySelectionChannels: StateFlow<List<SelectionChannel>> = MutableStateFlow<List<SelectionChannel>>(emptyList()).asStateFlow()

internal val QuickMaskOff: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

internal val DefaultQuickMaskOverlayColor: StateFlow<RgbaColor> = MutableStateFlow(RgbaColor(1f, 0f, 0f, 0.5f)).asStateFlow()

internal val NoMaskView: StateFlow<app.croptop.core.model.LayerId?> = MutableStateFlow<app.croptop.core.model.LayerId?>(null).asStateFlow()
