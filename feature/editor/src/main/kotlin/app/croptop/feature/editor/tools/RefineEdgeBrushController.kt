package app.croptop.feature.editor.tools

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RefineEdgeBrushSession
import app.croptop.core.engine.api.RefineEdgeBrushSpec
import app.croptop.core.engine.api.RefineEdgeSession
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.StrokeSampleLayout
import app.croptop.core.model.DocPoint

/** Routes one pointer gesture to a Select and Mask brush session using document-space coordinates. */
class RefineEdgeBrushController(
    private val refineSession: RefineEdgeSession,
    private var spec: RefineEdgeBrushSpec = RefineEdgeBrushSpec(),
) {
    private var activeStroke: RefineEdgeBrushSession? = null
    private var startTimeMillis = 0L

    val isActive: Boolean get() = activeStroke != null
    val currentSpec: RefineEdgeBrushSpec get() = spec

    fun setSpec(value: RefineEdgeBrushSpec) {
        spec = value
    }

    /** Starts an Add or Subtract stroke and sends its initial document-space point. */
    fun begin(point: DocPoint, pressure: Float = 1f, timeMillis: Long = 0L): CommandResult? {
        if (activeStroke != null) return rejected("A refine brush stroke is already active")
        validate(point, pressure)?.let { return it }

        val stroke = try {
            refineSession.beginBrushStroke(spec)
        } catch (failure: Exception) {
            return rejected(failure)
        }
        activeStroke = stroke
        startTimeMillis = timeMillis
        return appendTo(stroke, point, pressure, timeMillis)
    }

    /** Adds a document-space pointer sample. View-to-document conversion belongs to the canvas adapter. */
    fun move(point: DocPoint, pressure: Float = 1f, timeMillis: Long): CommandResult? {
        val stroke = activeStroke ?: return rejected("No refine brush stroke is active")
        validate(point, pressure)?.let { return it }
        return appendTo(stroke, point, pressure, timeMillis)
    }

    /** Ends the active gesture once and returns the engine's acceptance or rejection. */
    fun end(): CommandResult {
        val stroke = activeStroke ?: return rejected("No refine brush stroke is active")
        activeStroke = null
        return try {
            stroke.end()
        } catch (failure: Exception) {
            rejected(failure)
        }
    }

    /** Cancels the active gesture without committing its samples. Safe to call repeatedly. */
    fun cancel() {
        val stroke = activeStroke ?: return
        activeStroke = null
        stroke.cancel()
    }

    private fun appendTo(
        stroke: RefineEdgeBrushSession,
        point: DocPoint,
        pressure: Float,
        timeMillis: Long,
    ): CommandResult? = try {
        stroke.add(pack(point, pressure, elapsedSeconds(timeMillis)))
        null
    } catch (failure: Exception) {
        activeStroke = null
        runCatching { stroke.cancel() }
        rejected(failure)
    }

    private fun elapsedSeconds(timeMillis: Long): Float =
        ((timeMillis.toDouble() - startTimeMillis.toDouble()).coerceAtLeast(0.0) / 1_000.0).toFloat()

    private fun validate(point: DocPoint, pressure: Float): CommandResult.Rejected? = when {
        !point.x.isFinite() || !point.y.isFinite() -> rejected("Refine brush coordinates must be finite")
        !pressure.isFinite() -> rejected("Refine brush pressure must be finite")
        else -> null
    }

    private fun pack(point: DocPoint, pressure: Float, timeSeconds: Float): FloatArray =
        FloatArray(StrokeSampleLayout.STRIDE).apply {
            this[StrokeSampleLayout.X] = point.x
            this[StrokeSampleLayout.Y] = point.y
            this[StrokeSampleLayout.PRESSURE] = pressure.coerceIn(0f, 1f)
            this[StrokeSampleLayout.TILT_X] = 0f
            this[StrokeSampleLayout.TILT_Y] = 0f
            this[StrokeSampleLayout.ORIENTATION] = 0f
            this[StrokeSampleLayout.TIME_SECONDS] = timeSeconds
        }

    private fun rejected(failure: Exception) = CommandResult.Rejected(
        code = if (failure is UnsupportedOperationException) RejectionCode.UNSUPPORTED else RejectionCode.INVALID_ARGUMENT,
        message = failure.message ?: "Refine brush operation was rejected",
    )

    private fun rejected(message: String) = CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, message)
}
