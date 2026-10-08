package app.croptop.feature.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeInlineSlider
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** Maps zoom (0.01–64) to a 0–100 slider position on a log scale, and back. */
internal fun zoomToSlider(zoom: Float): Float =
    ((ln(zoom / ViewportState.MIN_ZOOM) / ln(ViewportState.MAX_ZOOM / ViewportState.MIN_ZOOM)) * 100f).toFloat().coerceIn(0f, 100f)

internal fun sliderToZoom(v: Float): Float = ViewportState.MIN_ZOOM * (ViewportState.MAX_ZOOM / ViewportState.MIN_ZOOM).pow(v / 100f)

private fun center(vp: ViewportState) = ViewPoint(vp.viewWidth / 2f, vp.viewHeight / 2f)

/** Navigator: document overview with the visible area; click/drag to move there. */
@Composable
fun NavigatorPanelContent(
    viewport: ViewportState,
    document: DocSize,
    onViewport: (ViewportState) -> Unit,
    onActionCompleted: (String) -> Unit = {},
    /** The whole document, small; null draws a neutral placeholder until it arrives. */
    preview: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    val vp by rememberUpdatedState(viewport)
    val change by rememberUpdatedState(onViewport)
    val c = Ae.colors
    Column(Modifier.padding(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = "Navigator. Click to move the view." }
                .pointerInput(document) {
                    awaitEachGesture {
                        fun centerOn(p: Offset) {
                            val scale = min(size.width / document.width.toFloat(), size.height / document.height.toFloat())
                            val ox = (size.width - document.width * scale) / 2
                            val oy = (size.height - document.height * scale) / 2
                            val target = DocPoint((p.x - ox) / scale, (p.y - oy) / scale)
                            val now = vp.docToView(target)
                            change(vp.panBy(vp.viewWidth / 2f - now.x, vp.viewHeight / 2f - now.y))
                        }
                        val down = awaitFirstDown()
                        centerOn(down.position)
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            centerOn(ch.position)
                            moved = true
                            ch.consume()
                        }
                        onActionCompleted(if (moved) "Pan from Navigator" else "Center Navigator view")
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val scale = min(size.width / document.width, size.height / document.height)
                val w = document.width * scale
                val h = document.height * scale
                val ox = (size.width - w) / 2
                val oy = (size.height - h) / 2
                if (preview != null) {
                    // Transparent areas read as the usual checkerboard, like the canvas.
                    val cell = 6.dp.toPx()
                    var y = 0f
                    while (y < h) {
                        var x = 0f
                        while (x < w) {
                            val dark = ((x / cell).toInt() + (y / cell).toInt()) % 2 == 1
                            drawRect(if (dark) Color(0xFFCCCCCC) else Color.White, Offset(ox + x, oy + y), Size(minOf(cell, w - x), minOf(cell, h - y)))
                            x += cell
                        }
                        y += cell
                    }
                    drawImage(
                        preview,
                        dstOffset = androidx.compose.ui.unit.IntOffset(ox.toInt(), oy.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    )
                } else {
                    drawRect(Color(0xFFBDBDBD), Offset(ox, oy), Size(w, h))
                }
                if (vp.viewWidth > 0) {
                    val corners = listOf(
                        ViewPoint(0f, 0f),
                        ViewPoint(vp.viewWidth.toFloat(), 0f),
                        ViewPoint(vp.viewWidth.toFloat(), vp.viewHeight.toFloat()),
                        ViewPoint(0f, vp.viewHeight.toFloat()),
                    ).map { vp.viewToDoc(it) }.map { Offset(ox + it.x * scale, oy + it.y * scale) }
                    val path = Path().apply {
                        moveTo(corners[0].x, corners[0].y)
                        corners.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    drawPath(path, c.accent, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeNumberField(
                vp.zoomPercent,
                { pct -> change(vp.zoomAt(center(vp), (pct / 100f) / vp.zoom)) },
                range = 1f..6400f,
                decimals = if (vp.zoomPercent < 10f) 1 else 0,
                unit = "%",
                width = 80.dp,
                contentDescription = "Zoom percent",
                onCommit = { onActionCompleted("Set Navigator zoom") },
            )
            AeInlineSlider(
                label = null,
                value = zoomToSlider(vp.zoom),
                onValueChange = { v -> change(vp.zoomAt(center(vp), sliderToZoom(v) / vp.zoom)) },
                range = 0f..100f,
                defaultValue = zoomToSlider(1f),
                trackWidth = 120.dp,
                showValueField = false,
                contentDescription = "Zoom",
                onValueChangeFinished = { onActionCompleted("Set Navigator zoom") },
            )
        }
    }
}
