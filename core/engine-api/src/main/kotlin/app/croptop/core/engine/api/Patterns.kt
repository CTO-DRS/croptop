package app.croptop.core.engine.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A pattern in the app's library (Edit ▸ Define Pattern); used by pattern fill layers and the Pattern Overlay style. */
data class PatternInfo(val id: String, val name: String, val width: Int, val height: Int)

internal val NoPatterns: StateFlow<List<PatternInfo>> = MutableStateFlow<List<PatternInfo>>(emptyList()).asStateFlow()
