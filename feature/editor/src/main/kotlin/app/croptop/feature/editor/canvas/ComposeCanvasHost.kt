package app.croptop.feature.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.OverlayState
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewportState

/** Solid page color the fallback canvas shows until real pixels render; null = transparency checkerboard. */
val LocalDocumentFill = androidx.compose.runtime.compositionLocalOf<Color?> { null }

/**
 * Compose fallback canvas: draws the document bounds as a transparency checkerboard using the
 * same [ViewportState.docToView] transform the native renderer implements.
 */
object ComposeCanvasHost : CanvasHost {
    private val Light = Color(0xFFCCCCCC)
    private val Dark = Color(0xFF999999)

    @Composable
    override fun Content(
        viewport: ViewportState,
        document: DocSize,
        handle: DocumentHandle?,
        modifier: Modifier,
        overlays: OverlayState,
    ) {
        val fill = LocalDocumentFill.current
        Canvas(modifier) {
            val corners = listOf(
                DocPoint(0f, 0f),
                DocPoint(document.width.toFloat(), 0f),
                DocPoint(document.width.toFloat(), document.height.toFloat()),
                DocPoint(0f, document.height.toFloat()),
            ).map { viewport.docToView(it) }
            val page = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                corners.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            if (fill != null) {
                drawPath(page, fill)
                return@Canvas
            }
            clipPath(page) {
                // Checker cells are screen-aligned (like Photoshop), 8px.
                val cell = 8f
                val b = page.getBounds()
                var y = (b.top / cell).toInt() * cell
                while (y < b.bottom) {
                    var x = (b.left / cell).toInt() * cell
                    while (x < b.right) {
                        val even = (((x / cell).toInt() + (y / cell).toInt()) and 1) == 0
                        drawRect(if (even) Light else Dark, Offset(x, y), Size(cell, cell))
                        x += cell
                    }
                    y += cell
                }
            }
        }
    }
}
