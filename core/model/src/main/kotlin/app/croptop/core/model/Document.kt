package app.croptop.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DocumentInfo(
    val id: DocumentId,
    val name: String,
    val size: DocSize,
    val depth: ColorDepth = ColorDepth.U8,
    val profile: ColorProfileRef = ColorProfileRef.DisplayP3,
    val resolutionPpi: Float = 72f,
    /** Has unsaved changes. */
    val dirty: Boolean = false,
)

@Serializable
enum class BackgroundContents { WHITE, BLACK, TRANSPARENT, BACKGROUND_COLOR }

/** Input to "New document". */
@Serializable
data class NewDocumentSpec(
    val name: String = "Untitled",
    val size: DocSize,
    val resolutionPpi: Float = 300f,
    val depth: ColorDepth = ColorDepth.U8,
    val profile: ColorProfileRef = ColorProfileRef.DisplayP3,
    val background: BackgroundContents = BackgroundContents.WHITE,
)

@Serializable
data class HistoryEntry(val label: String, val iconKey: String? = null)

@Serializable
data class HistorySnapshot(val name: String, val index: Int)

/** [current] indexes into [entries]; entries after it are redoable. Index 0 is the "Open"/"New" state. */
@Serializable
data class HistoryState(
    val entries: List<HistoryEntry> = listOf(HistoryEntry("Open")),
    val current: Int = 0,
    val snapshots: List<HistorySnapshot> = emptyList(),
) {
    val canUndo: Boolean get() = current > 0
    val canRedo: Boolean get() = current < entries.lastIndex
    val undoLabel: String? get() = entries.getOrNull(current)?.label?.takeIf { canUndo }
    val redoLabel: String? get() = entries.getOrNull(current + 1)?.label
}

@Serializable
sealed interface SelectionInfo {
    @Serializable
    @SerialName("none")
    data object None : SelectionInfo

    /** [bounds] is the tight bounding box of the selection mask. */
    @Serializable
    @SerialName("active")
    data class Active(val bounds: DocRect, val feathered: Boolean = false) : SelectionInfo
}
