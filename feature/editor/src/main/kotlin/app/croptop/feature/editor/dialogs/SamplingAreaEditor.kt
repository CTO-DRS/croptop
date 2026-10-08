package app.croptop.feature.editor.dialogs

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.LayerThumb
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocSize
import app.croptop.core.model.SamplingStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.hypot

/** What the Content-Aware Fill dialog needs to draw the layer and the hole behind the sampling area. */
data class ContentAwareContext(val thumbnail: LayerThumb?, val docSize: DocSize, val hole: DocRect?)

private enum class SamplingTool(val label: String, val add: Boolean) {
    ADD("Add", true),
    SUBTRACT("Subtract", false),
}

internal fun LayerThumb.toSamplingBitmap(): ImageBitmap {
    val pixels = IntArray(width * height) { i ->
        val p = i * 4
        ((rgba[p + 3].toInt() and 255) shl 24) or ((rgba[p].toInt() and 255) shl 16) or
            ((rgba[p + 1].toInt() and 255) shl 8) or (rgba[p + 2].toInt() and 255)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Paints where Content-Aware Fill may copy from. Sampled areas are tinted green; Add strokes include pixels, Subtract strokes
 * exclude them. With [onlyPainted] false everything outside the hole is sampled until a Subtract stroke removes it.
 */
@Composable
fun SamplingAreaEditor(
    context: ContentAwareContext,
    strokes: List<SamplingStroke>,
    onlyPainted: Boolean,
    onStrokes: (List<SamplingStroke>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tool by remember { mutableStateOf(if (onlyPainted) SamplingTool.ADD else SamplingTool.SUBTRACT) }
    var radius by remember { mutableStateOf((maxOf(context.docSize.width, context.docSize.height) / 20f).coerceIn(4f, 400f)) }
    var live by remember { mutableStateOf<SamplingStroke?>(null) }
    val strokesNow by rememberUpdatedState(strokes)
    val toolNow by rememberUpdatedState(tool)
    val radiusNow by rememberUpdatedState(radius)
    val image = remember(context.thumbnail) { context.thumbnail?.let { runCatching { it.toSamplingBitmap() }.getOrNull() } }
    val aspect = context.docSize.width.toFloat() / context.docSize.height.coerceAtLeast(1)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AeSegmented(SamplingTool.entries, tool, { tool = it }, { it.label })
            AeButton("Undo stroke", { onStrokes(strokes.dropLast(1)) }, enabled = strokes.isNotEmpty())
            AeButton("Clear", { onStrokes(emptyList()) }, enabled = strokes.isNotEmpty())
        }
        AeSlider("Brush size", radius, { radius = it }, range = 2f..400f, defaultValue = 40f, unit = "px")
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.2f, 5f))
                .semantics { contentDescription = "Sampling area editor. Drag to ${if (tool.add) "add" else "subtract"} sampled areas." }
                .pointerInput(context.docSize) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val scale = context.docSize.width / size.width.toFloat()
                        fun doc(o: Offset) = DocPoint(
                            (o.x * scale).coerceIn(0f, context.docSize.width.toFloat()),
                            (o.y * scale).coerceIn(0f, context.docSize.height.toFloat()),
                        )
                        if (strokesNow.size >= SamplingStroke.MAX_STROKES) return@awaitEachGesture
                        val points = mutableListOf(doc(down.position))
                        val minStep = (radiusNow / 4f).coerceAtLeast(1f)
                        live = SamplingStroke(points.toList(), radiusNow, toolNow.add)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val p = doc(change.position)
                            val last = points.last()
                            if (hypot(p.x - last.x, p.y - last.y) >= minStep && points.size < SamplingStroke.MAX_POINTS) {
                                points += p
                                live = SamplingStroke(points.toList(), radiusNow, toolNow.add)
                            }
                            change.consume()
                        }
                        onStrokes(strokesNow + SamplingStroke(points.toList(), radiusNow, toolNow.add))
                        live = null
                    }
                },
        ) {
            val scale = size.width / context.docSize.width.toFloat()
            image?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
                ?: drawRect(Color(0xFF3A3A40), size = size)
            val sampled = Color(0xFF3DDC84).copy(alpha = 0.38f)
            val excluded = Color(0xFFE5484D).copy(alpha = 0.38f)
            if (!onlyPainted) drawRect(sampled, size = Size(size.width, size.height))
            for (s in strokes + listOfNotNull(live)) {
                val path = androidx.compose.ui.graphics.Path()
                s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * scale, p.y * scale) else path.lineTo(p.x * scale, p.y * scale) }
                if (s.points.size == 1) path.lineTo(s.points[0].x * scale + 0.01f, s.points[0].y * scale)
                drawPath(path, if (s.add) sampled else excluded, style = Stroke(width = s.radius * 2f * scale, cap = StrokeCap.Round))
            }
            context.hole?.let {
                drawRect(
                    Color.White,
                    topLeft = Offset(it.left * scale, it.top * scale),
                    size = Size((it.right - it.left) * scale, (it.bottom - it.top) * scale),
                    style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                )
            }
        }
        AeText(
            if (onlyPainted) "Green areas are the only places patches are copied from." else "Green areas are sampled. Subtract strokes (red) are excluded.",
            style = Ae.type.caption,
            color = Ae.colors.textTertiary,
            maxLines = 2,
        )
    }
}
