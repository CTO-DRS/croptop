package app.croptop.core.engine.api

import app.croptop.core.model.SelectionOp

/**
 * One Quick Selection brush stroke. [diameter] is in document pixels. INTERSECT isn't a brush mode:
 * the tool offers new, add and subtract, like Photoshop.
 */
data class QuickSelectionSpec(
    val diameter: Float,
    val op: SelectionOp = SelectionOp.REPLACE,
    val sampleAll: Boolean = false,
    val enhanceEdge: Boolean = false,
) {
    init {
        require(diameter.isFinite() && diameter in 1f..MAX_DIAMETER) { "Brush size must be 1–${MAX_DIAMETER.toInt()} px" }
        require(op != SelectionOp.INTERSECT) { "Quick Selection adds to or subtracts from the selection; it doesn't intersect" }
    }

    companion object {
        const val MAX_DIAMETER = 5000f
    }
}

/**
 * Painting grows (or, with SUBTRACT, shrinks) the selection toward similar pixels and edges.
 * [add] previews live on [DocumentHandle.selection] without a history step; [end] commits one step.
 */
interface QuickSelectionSession {
    /** Packed document-space x,y pairs; length must be even. */
    fun add(points: FloatArray)

    fun end(): CommandResult

    /** Restores the selection from before the stroke. */
    fun cancel()
}

/** Default session for engines that don't do Quick Selection yet: [end] reports why. */
class UnsupportedQuickSelectionSession(private val what: String) : QuickSelectionSession {
    override fun add(points: FloatArray) = Unit

    override fun end(): CommandResult = unsupported(what)

    override fun cancel() = Unit
}
