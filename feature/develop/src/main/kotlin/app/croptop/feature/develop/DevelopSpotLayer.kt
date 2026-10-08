package app.croptop.feature.develop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.croptop.core.model.CropSettings
import app.croptop.core.model.Spot
import kotlin.math.max

/** Where spots live relative to the shown frame, and where a new spot's source goes. */
object SpotGeometry {
    /** True when spot positions can be mapped to the shown frame (a crop without straightening). */
    fun placeable(crop: CropSettings) = crop.angle == 0f

    /** Uncropped fraction → fraction of the cropped frame that is shown. */
    fun toFrame(crop: CropSettings, x: Float, y: Float) = Offset((x - crop.left) / (crop.right - crop.left), (y - crop.top) / (crop.bottom - crop.top))

    /** Fraction of the shown (cropped) frame → uncropped fraction. */
    fun fromFrame(crop: CropSettings, x: Float, y: Float) = Offset(crop.left + x * (crop.right - crop.left), crop.top + y * (crop.bottom - crop.top))

    /**
     * A clean-looking source for a spot at ([x], [y]): the same distance away as three radii, to the right when there is room,
     * otherwise to the left. [aspect] is width / height so the offset is the same on screen in both axes.
     */
    fun defaultSource(x: Float, y: Float, radius: Float, aspect: Float): Offset {
        val dx = 3f * radii(radius, aspect).x
        val right = x + dx
        return Offset(if (right <= 1f) right else (x - dx).coerceAtLeast(0f), y.coerceIn(0f, 1f))
    }

    /** A spot's radii as fractions of the frame width (x) and height (y); [radius] is a fraction of the longer edge, [aspect] is width / height. */
    fun radii(radius: Float, aspect: Float) = if (aspect >= 1f) Offset(radius, radius * aspect) else Offset(radius / aspect, radius)

    /** The index of the spot whose target or source circle contains ([x], [y]) in uncropped fractions, preferring the last drawn. */
    fun hitSpot(spots: List<Spot>, x: Float, y: Float, aspect: Float): Pair<Int, Boolean>? {
        for (i in spots.indices.reversed()) {
            val s = spots[i]
            val (rx, ry) = radii(s.radius, aspect).let { it.x to it.y }
            fun inside(cx: Float, cy: Float) = ((x - cx) / max(rx, 1e-6f)).let { a -> a * a } + ((y - cy) / max(ry, 1e-6f)).let { b -> b * b } <= 1f
            if (inside(s.x, s.y)) return i to false
            if (inside(s.sourceX, s.sourceY)) return i to true
        }
        return null
    }
}

/**
 * Spot removal on the preview: tap to place a spot (the source is picked automatically), drag a spot's circle or its source
 * circle to move it, tap a circle to select it. Spots are stored on the uncropped frame and shown through the crop.
 */
@Composable
internal fun SpotLayer(
    spots: List<Spot>,
    selected: Int?,
    crop: CropSettings,
    frameWidth: Int,
    frameHeight: Int,
    placing: Boolean,
    vm: DevelopViewModel,
    modifier: Modifier = Modifier,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val fitted = FittedFrame.of(box, frameWidth, frameHeight)
    val currentSpots by rememberUpdatedState(spots)
    val currentCrop by rememberUpdatedState(crop)
    // The shown frame is the cropped image; spots live on the full frame, so the aspect is that of the uncropped image.
    val aspect = (frameWidth / (crop.right - crop.left).coerceAtLeast(0.001f)) / (frameHeight / (crop.bottom - crop.top).coerceAtLeast(0.001f))
    Box(modifier.fillMaxSize().onSizeChanged { box = it }) {
        if (fitted != null) {
            Canvas(Modifier.fillMaxSize()) {
                val longer = max(fitted.width, fitted.height) / max(crop.right - crop.left, crop.bottom - crop.top).coerceAtLeast(0.001f)
                spots.forEachIndexed { i, s ->
                    val target = fitted.toPx(SpotGeometry.toFrame(crop, s.x, s.y))
                    val source = fitted.toPx(SpotGeometry.toFrame(crop, s.sourceX, s.sourceY))
                    val r = s.radius * longer
                    val tone = if (i == selected) Color(0xFFFFD54F) else Color.White
                    drawCircle(tone, r, target, style = Stroke(1.5.dp.toPx()))
                    drawCircle(tone.copy(alpha = 0.8f), r, source, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                    drawLine(tone.copy(alpha = 0.6f), target, source, 1.dp.toPx())
                }
            }
            Box(
                Modifier.fillMaxSize().pointerInput(fitted.width, fitted.height, placing) {
                    detectTapGestures { tap ->
                        if (!fitted.contains(tap)) return@detectTapGestures
                        val n = fitted.toNorm(tap)
                        val full = SpotGeometry.fromFrame(currentCrop, n.x, n.y)
                        val hit = SpotGeometry.hitSpot(currentSpots, full.x, full.y, aspect)
                        if (hit != null) {
                            vm.selectSpot(hit.first)
                        } else if (placing && currentSpots.size < Spot.MAX_SPOTS) {
                            vm.addSpot(full.x.coerceIn(0f, 1f), full.y.coerceIn(0f, 1f), aspect)
                        }
                    }
                },
            )
            Box(
                Modifier.fillMaxSize().pointerInput(fitted.width, fitted.height) {
                    var dragging: Pair<Int, Boolean>? = null
                    detectDragGestures(
                        onDragStart = { down ->
                            val n = fitted.toNorm(down)
                            val full = SpotGeometry.fromFrame(currentCrop, n.x, n.y)
                            dragging = SpotGeometry.hitSpot(currentSpots, full.x, full.y, aspect)
                            dragging?.let { vm.selectSpot(it.first) }
                        },
                        onDrag = { change, _ ->
                            val grab = dragging ?: return@detectDragGestures
                            change.consume()
                            val n = fitted.toNorm(change.position)
                            val full = SpotGeometry.fromFrame(currentCrop, n.x, n.y)
                            val x = full.x.coerceIn(0f, 1f)
                            val y = full.y.coerceIn(0f, 1f)
                            vm.previewSpot(grab.first) { s -> if (grab.second) s.copy(sourceX = x, sourceY = y) else s.copy(x = x, y = y) }
                        },
                        onDragEnd = {
                            if (dragging != null) vm.commit("Move spot")
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    )
                },
            )
        }
    }
}
