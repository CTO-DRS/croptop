package app.croptop.core.model

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.serialization.Serializable

/** A point in view (window) pixels. */
@Serializable
data class ViewPoint(val x: Float, val y: Float)

/**
 * Canvas view transform with bounded zoom, view rotation and flip state.
 *
 * view = translate(pan) · rotate(rotation about pan) · scale(zoom, ±zoom) · doc
 * [panX]/[panY] is where the document origin lands in view pixels before rotation.
 */
@Serializable
data class ViewportState(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val viewWidth: Int = 0,
    val viewHeight: Int = 0,
) {
    private val rad: Double get() = Math.toRadians(rotationDegrees.toDouble())
    private val sx: Float get() = if (flipHorizontal) -zoom else zoom

    fun docToView(p: DocPoint): ViewPoint {
        val x = p.x * sx
        val y = p.y * zoom
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return ViewPoint(x * c - y * s + panX, x * s + y * c + panY)
    }

    fun viewToDoc(p: ViewPoint): DocPoint {
        val x = p.x - panX
        val y = p.y - panY
        val c = cos(-rad).toFloat()
        val s = sin(-rad).toFloat()
        return DocPoint((x * c - y * s) / sx, (x * s + y * c) / zoom)
    }

    /** Zoom by [factor] keeping [anchor] (view px) fixed — Ctrl+wheel / pinch at cursor. */
    fun zoomAt(anchor: ViewPoint, factor: Float): ViewportState {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val docAnchor = viewToDoc(anchor)
        val zoomed = copy(zoom = newZoom)
        val moved = zoomed.docToView(docAnchor)
        return zoomed.copy(panX = panX + anchor.x - moved.x, panY = panY + anchor.y - moved.y)
    }

    fun panBy(dx: Float, dy: Float): ViewportState = copy(panX = panX + dx, panY = panY + dy)

    /** Rotate the view about [anchor] (view px). */
    fun rotateAt(anchor: ViewPoint, degrees: Float): ViewportState {
        val docAnchor = viewToDoc(anchor)
        val rotated = copy(rotationDegrees = ((rotationDegrees + degrees) % 360f + 360f) % 360f)
        val moved = rotated.docToView(docAnchor)
        return rotated.copy(panX = panX + anchor.x - moved.x, panY = panY + anchor.y - moved.y)
    }

    /** Fit [doc] inside the view with [marginPx] on every side, unrotated, centered. */
    fun fit(doc: DocSize, marginPx: Float = 24f): ViewportState {
        if (viewWidth <= 0 || viewHeight <= 0) return this
        val z = min((viewWidth - 2 * marginPx) / doc.width, (viewHeight - 2 * marginPx) / doc.height).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return actualSize(doc, z)
    }

    /** Center [doc] at [zoomLevel] (1f = 100%), unrotated. */
    fun actualSize(doc: DocSize, zoomLevel: Float = 1f): ViewportState = copy(
        zoom = zoomLevel,
        rotationDegrees = 0f,
        flipHorizontal = false,
        panX = (viewWidth - doc.width * zoomLevel) / 2f,
        panY = (viewHeight - doc.height * zoomLevel) / 2f,
    )

    /** Next preset zoom step, Photoshop-style (Ctrl+= / Ctrl+-). */
    fun stepZoom(anchor: ViewPoint, zoomIn: Boolean): ViewportState {
        val next = if (zoomIn) {
            ZOOM_STEPS.firstOrNull { it > zoom * 1.001f } ?: MAX_ZOOM
        } else {
            ZOOM_STEPS.lastOrNull { it < zoom * 0.999f } ?: MIN_ZOOM
        }
        return zoomAt(anchor, next / zoom)
    }

    val zoomPercent: Float get() = zoom * 100f

    /** Pixel grid shows at ≥ 800%. */
    val showsPixelGrid: Boolean get() = zoom >= 8f

    companion object {
        const val MIN_ZOOM = 0.01f
        const val MAX_ZOOM = 64f
        val ZOOM_STEPS = floatArrayOf(
            0.01f, 0.02f, 0.03f, 0.04f, 0.05f, 0.0625f, 0.0833f, 0.125f, 0.1667f, 0.25f, 0.3333f, 0.5f, 0.6667f,
            1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 12f, 16f, 20f, 24f, 32f, 48f, 64f,
        )
    }
}
