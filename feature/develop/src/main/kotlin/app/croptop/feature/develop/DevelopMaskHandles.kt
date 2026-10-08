package app.croptop.feature.develop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.croptop.core.engine.api.DevelopPreview
import app.croptop.core.model.LocalMask
import app.croptop.core.model.MaskComponent
import app.croptop.core.model.MaskKind
import app.croptop.core.model.MaskPoint
import app.croptop.core.model.MaskStroke

/** What a drag or tap on the preview does while a mask is selected. */
enum class MaskTool { HANDLES, BRUSH, ERASE, PICK }

/** Where the rendered frame sits inside the preview box (ContentScale.Fit), in box pixels. */
internal class FittedFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun toNorm(p: Offset) = Offset((p.x - left) / width, (p.y - top) / height)

    fun toPx(n: Offset) = Offset(left + n.x * width, top + n.y * height)

    fun contains(p: Offset) = p.x in left..(left + width) && p.y in top..(top + height)

    companion object {
        fun of(box: IntSize, frameWidth: Int, frameHeight: Int): FittedFrame? {
            if (box.width <= 0 || box.height <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
            val scale = minOf(box.width.toFloat() / frameWidth, box.height.toFloat() / frameHeight)
            val w = frameWidth * scale
            val h = frameHeight * scale
            return FittedFrame((box.width - w) / 2f, (box.height - h) / 2f, w, h)
        }
    }
}

/** A draggable point of a component. [apply] writes the dragged normalized position into that component. */
internal class MaskHandle(val component: Int, val at: Offset, val apply: (MaskComponent, Offset) -> MaskComponent)

private fun MaskComponent.withParams(vararg changes: Pair<Int, Float>): MaskComponent =
    copy(params = params.toMutableList().also { list -> changes.forEach { (i, v) -> list[i] = v } })

/** Sets the mask's first colour component to [r], [g], [b] (adding one when there is none). */
private fun LocalMask.withColor(r: Float, g: Float, b: Float): LocalMask {
    val at = components.indexOfFirst { it.kind == MaskKind.COLOR }
    if (at >= 0) {
        val updated = components[at].withParams(0 to r, 1 to g, 2 to b)
        return copy(components = components.toMutableList().also { it[at] = updated })
    }
    return if (components.size < LocalMask.MAX_COMPONENTS) copy(components = components + MaskComponent.color(r, g, b)) else this
}

private fun clamp01(o: Offset) = Offset(o.x.coerceIn(0f, 1f), o.y.coerceIn(0f, 1f))

/** Handles of the linear and radial components of [mask] (brush and colour components have none). */
internal fun handlesOf(mask: LocalMask): List<MaskHandle> = mask.components.withIndex().flatMap { (c, comp) ->
    val p = comp.params
    when (comp.kind) {
        MaskKind.LINEAR -> listOf(
            MaskHandle(c, Offset(p[0], p[1])) { m, o -> m.withParams(0 to clamp01(o).x, 1 to clamp01(o).y) },
            MaskHandle(c, Offset(p[2], p[3])) { m, o -> m.withParams(2 to clamp01(o).x, 3 to clamp01(o).y) },
        )
        // Radii are axis-aligned here; the rotation parameter is still edited in the panel.
        MaskKind.RADIAL -> listOf(
            MaskHandle(c, Offset(p[0], p[1])) { m, o -> m.withParams(0 to clamp01(o).x, 1 to clamp01(o).y) },
            MaskHandle(c, Offset(p[0] + p[2], p[1])) { m, o -> m.withParams(2 to (o.x - p[0]).coerceIn(0.005f, 1f)) },
            MaskHandle(c, Offset(p[0], p[1] + p[3])) { m, o -> m.withParams(3 to (o.y - p[1]).coerceIn(0.005f, 1f)) },
        )
        else -> emptyList()
    }
}

/**
 * Draws the selected mask's handles and turns drags and taps on the preview into mask edits: handle drags, brush and erase
 * strokes, or a colour pick from the rendered frame. Each gesture is one history step.
 */
@Composable
internal fun MaskHandleLayer(
    mask: LocalMask,
    maskIndex: Int,
    frame: DevelopPreview,
    tool: MaskTool,
    vm: DevelopViewModel,
    modifier: Modifier = Modifier,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val fitted = FittedFrame.of(box, frame.width, frame.height)
    val currentMask by rememberUpdatedState(mask)
    val currentIndex by rememberUpdatedState(maskIndex)
    val currentFitted by rememberUpdatedState(fitted)
    val handleColor = MaterialTheme.colorScheme.primary
    val handlePx = 24f
    Box(modifier.fillMaxSize().onSizeChanged { box = it }) {
        if (fitted != null) {
            Canvas(Modifier.fillMaxSize()) {
                handlesOf(mask).forEach { h ->
                    val at = fitted.toPx(h.at)
                    drawCircle(Color.White, radius = 8.dp.toPx(), center = at, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(handleColor, radius = 4.dp.toPx(), center = at)
                }
                mask.components.forEachIndexed { c, comp ->
                    if (comp.kind == MaskKind.RADIAL) {
                        val centre = fitted.toPx(Offset(comp.params[0], comp.params[1]))
                        val radii = Size(comp.params[2] * fitted.width, comp.params[3] * fitted.height)
                        drawOval(
                            Color.White.copy(alpha = 0.8f),
                            topLeft = Offset(centre.x - radii.width, centre.y - radii.height),
                            size = Size(radii.width * 2, radii.height * 2),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                    if (comp.kind == MaskKind.LINEAR) {
                        drawLine(
                            Color.White.copy(alpha = 0.8f),
                            fitted.toPx(Offset(comp.params[0], comp.params[1])),
                            fitted.toPx(Offset(comp.params[2], comp.params[3])),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
            }
        }
        val gestureKey = Triple(tool, fitted?.width, fitted?.height)
        Box(
            Modifier.fillMaxSize().pointerInput(gestureKey) {
                val f = currentFitted ?: return@pointerInput
                var activeHandle: MaskHandle? = null
                var strokePoints: List<Offset> = emptyList()
                when (tool) {
                    MaskTool.HANDLES -> detectDragGestures(
                        onDragStart = { down ->
                            val nearest = handlesOf(currentMask).minByOrNull { (f.toPx(it.at) - down).getDistance() }
                            activeHandle = nearest?.takeIf { (f.toPx(it.at) - down).getDistance() <= handlePx }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val h = activeHandle ?: return@detectDragGestures
                            val norm = f.toNorm(change.position)
                            vm.previewMask(currentIndex) { m ->
                                m.copy(components = m.components.mapIndexed { i, comp -> if (i == h.component) h.apply(comp, norm) else comp })
                            }
                        },
                        onDragEnd = {
                            if (activeHandle != null) vm.commit("Move mask handle")
                            activeHandle = null
                        },
                        onDragCancel = { activeHandle = null },
                    )
                    MaskTool.BRUSH, MaskTool.ERASE -> detectDragGestures(
                        onDragStart = { down -> strokePoints = listOf(f.toNorm(down)) },
                        onDrag = { change, _ ->
                            change.consume()
                            if (strokePoints.size < MaskStroke.MAX_POINTS) strokePoints = strokePoints + f.toNorm(change.position)
                        },
                        onDragEnd = {
                            val points = strokePoints
                            strokePoints = emptyList()
                            if (points.isNotEmpty()) {
                                vm.addStroke(
                                    currentIndex,
                                    MaskStroke(
                                        points.map { MaskPoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) },
                                        size = vm.brushSize.value,
                                        feather = vm.brushFeather.value,
                                        flow = vm.brushFlow.value,
                                        erase = tool == MaskTool.ERASE,
                                    ),
                                )
                            }
                        },
                        onDragCancel = { strokePoints = emptyList() },
                    )
                    MaskTool.PICK -> detectTapGestures { tap ->
                        if (!f.contains(tap)) return@detectTapGestures
                        val n = f.toNorm(tap)
                        val x = (n.x * frame.width).toInt().coerceIn(0, frame.width - 1)
                        val y = (n.y * frame.height).toInt().coerceIn(0, frame.height - 1)
                        val argb = frame.argb[y * frame.width + x]
                        val r = ((argb shr 16) and 0xff) / 255f
                        val g = ((argb shr 8) and 0xff) / 255f
                        val b = (argb and 0xff) / 255f
                        vm.setMask(currentIndex, "Pick colour") { m -> m.withColor(r, g, b) }
                    }
                }
            },
        )
    }
}
