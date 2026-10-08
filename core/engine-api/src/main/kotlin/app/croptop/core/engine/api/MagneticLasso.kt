package app.croptop.core.engine.api

import app.croptop.core.model.DocPoint
import app.croptop.core.model.SelectionOp

/**
 * Magnetic Lasso options (Photoshop's): [width] 1–256 px edge search radius, [contrast] 0.01–1 edge
 * strength, [frequency] 0–100 automatic fastening points.
 */
data class MagneticLassoSpec(
    val width: Float = 10f,
    val contrast: Float = 0.1f,
    val frequency: Float = 57f,
    val op: SelectionOp = SelectionOp.REPLACE,
    val feather: Float = 0f,
    val antiAlias: Boolean = true,
) {
    init {
        require(width.isFinite() && width in 1f..256f) { "Width must be 1–256 px" }
        require(contrast.isFinite() && contrast in 0.01f..1f) { "Contrast must be 1–100 %" }
        require(frequency.isFinite() && frequency in 0f..100f) { "Frequency must be 0–100" }
        require(feather.isFinite() && feather in 0f..1000f) { "Feather must be 0–1000 px" }
    }
}

/**
 * One Magnetic Lasso outline. [move] returns the live wire (document points) from the last
 * fastening point to the snapped cursor and may add automatic fastening points; [anchor] fastens at a
 * click; [undoAnchor] removes the last one (Backspace); [outline] is the committed path. [close] closes
 * back to the start and applies the selection with the spec's op (one history step); [cancel] discards.
 */
interface MagneticLassoSession {
    fun move(point: DocPoint): List<DocPoint>

    fun anchor(point: DocPoint)

    fun undoAnchor(): Boolean

    fun outline(): List<DocPoint>

    fun close(): CommandResult

    fun cancel()
}

/** Default for engines without a Magnetic Lasso: [close] reports why. */
class UnsupportedMagneticLassoSession : MagneticLassoSession {
    override fun move(point: DocPoint): List<DocPoint> = emptyList()

    override fun anchor(point: DocPoint) = Unit

    override fun undoAnchor(): Boolean = false

    override fun outline(): List<DocPoint> = emptyList()

    override fun close(): CommandResult = unsupported("Magnetic Lasso")

    override fun cancel() = Unit
}
