package app.croptop.core.engine.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Select ▸ Select and Mask settings, Photoshop's ranges: radius 0–250 px, smooth 0–100,
 * feather 0–250 px, contrast 0–100 %, shift edge −100…100 %, decontaminate amount 0–1.
 */
data class RefineEdgeSettings(
    val radius: Float = 10f,
    val smartRadius: Boolean = false,
    val smooth: Float = 0f,
    val feather: Float = 0f,
    val contrast: Float = 0f,
    val shiftEdge: Float = 0f,
    val decontaminate: Boolean = false,
    val decontaminateAmount: Float = 1f,
) {
    init {
        require(radius.isFinite() && radius in 0f..250f) { "Radius must be 0–250 px" }
        require(smooth.isFinite() && smooth in 0f..100f) { "Smooth must be 0–100" }
        require(feather.isFinite() && feather in 0f..250f) { "Feather must be 0–250 px" }
        require(contrast.isFinite() && contrast in 0f..100f) { "Contrast must be 0–100 %" }
        require(shiftEdge.isFinite() && shiftEdge in -100f..100f) { "Shift Edge must be −100…100 %" }
        require(decontaminateAmount.isFinite() && decontaminateAmount in 0f..1f) { "Decontaminate amount must be 0–1" }
    }
}

/** Where Select and Mask sends its result (Photoshop's Output To). */
enum class RefineEdgeOutput(val label: String) {
    SELECTION("Selection"),
    LAYER_MASK("Layer Mask"),

    /** A new layer with the refined (and optionally decontaminated) pixels of the primary layer. */
    NEW_LAYER("New Layer"),

    /** A transparent document containing the refined pixels on its own layer. */
    NEW_DOCUMENT("New Document"),
}

/** Canvas presentation for the live Select and Mask preview. */
enum class RefineEdgeViewMode(val label: String) {
    OVERLAY("Overlay"),
    ON_BLACK("On Black"),
    ON_WHITE("On White"),
    BLACK_AND_WHITE("Black and White"),
}

enum class RefineEdgeBrushMode { ADD, SUBTRACT }

/** Brush controls for manually refining the selection preview. Values use the same 0..1 ranges as [StrokeSpec]. */
data class RefineEdgeBrushSpec(
    val diameter: Float = 30f,
    val hardness: Float = 0.75f,
    val opacity: Float = 1f,
    val mode: RefineEdgeBrushMode = RefineEdgeBrushMode.ADD,
) {
    init {
        require(diameter.isFinite() && diameter in 1f..500f)
        require(hardness.isFinite() && hardness in 0f..1f)
        require(opacity.isFinite() && opacity in 0f..1f)
    }
}

/** One non-history brush gesture in the live Select and Mask preview. */
interface RefineEdgeBrushSession {
    fun add(samples: FloatArray)
    fun end(): CommandResult
    fun cancel()
}

class UnsupportedRefineEdgeBrushSession : RefineEdgeBrushSession {
    override fun add(samples: FloatArray) = throw UnsupportedOperationException("Select and Mask refine brush isn't available in this build yet.")
    override fun end(): CommandResult = unsupported("Select and Mask refine brush")
    override fun cancel() = Unit
}

private val defaultRefineEdgeViewMode: StateFlow<RefineEdgeViewMode> =
    MutableStateFlow(RefineEdgeViewMode.OVERLAY).asStateFlow()

/**
 * A Select and Mask workspace on the current selection and primary layer. [update] previews the refined
 * selection live on [DocumentHandle.selection] without history; [commit] applies it to [RefineEdgeOutput]
 * (history steps as the output requires); [cancel] restores the selection from before the session.
 */
interface RefineEdgeSession {
    /** Selected canvas preview presentation; changing it never changes document history. */
    val viewMode: StateFlow<RefineEdgeViewMode> get() = defaultRefineEdgeViewMode

    /** Set the live canvas preview presentation. */
    fun setViewMode(mode: RefineEdgeViewMode): CommandResult = unsupported("Select and Mask view modes")

    /** Begin an add/subtract brush gesture against the live refined selection preview. */
    fun beginBrushStroke(spec: RefineEdgeBrushSpec): RefineEdgeBrushSession = UnsupportedRefineEdgeBrushSession()

    /** Populated after a successful [RefineEdgeOutput.NEW_DOCUMENT] commit. */
    val outputDocument: DocumentHandle? get() = null

    fun update(settings: RefineEdgeSettings): CommandResult

    fun commit(output: RefineEdgeOutput = RefineEdgeOutput.SELECTION): CommandResult

    fun cancel()
}

/** Default for engines without Select and Mask: everything reports why. */
class UnsupportedRefineEdgeSession : RefineEdgeSession {
    override fun setViewMode(mode: RefineEdgeViewMode): CommandResult = unsupported("Select and Mask")

    override fun beginBrushStroke(spec: RefineEdgeBrushSpec): RefineEdgeBrushSession = UnsupportedRefineEdgeBrushSession()

    override fun update(settings: RefineEdgeSettings): CommandResult = unsupported("Select and Mask")

    override fun commit(output: RefineEdgeOutput): CommandResult = unsupported("Select and Mask")

    override fun cancel() = Unit
}
