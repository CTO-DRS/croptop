package app.croptop.feature.develop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.croptop.core.model.CropSettings

/** Which part of the crop frame a drag grabbed. */
enum class CropGrab { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Hit testing and drag maths for the crop frame in normalized image coordinates. */
object CropDrag {
    const val MIN_SIDE = 0.02f

    /** The part of [crop] at ([x], [y]); [slop] is the grab distance in the same units (per axis). */
    fun hit(crop: CropSettings, x: Float, y: Float, slopX: Float, slopY: Float): CropGrab {
        val nearL = kotlin.math.abs(x - crop.left) <= slopX
        val nearR = kotlin.math.abs(x - crop.right) <= slopX
        val nearT = kotlin.math.abs(y - crop.top) <= slopY
        val nearB = kotlin.math.abs(y - crop.bottom) <= slopY
        val insideX = x in (crop.left - slopX)..(crop.right + slopX)
        val insideY = y in (crop.top - slopY)..(crop.bottom + slopY)
        return when {
            nearL && nearT -> CropGrab.TOP_LEFT
            nearR && nearT -> CropGrab.TOP_RIGHT
            nearL && nearB -> CropGrab.BOTTOM_LEFT
            nearR && nearB -> CropGrab.BOTTOM_RIGHT
            nearL && insideY -> CropGrab.LEFT
            nearR && insideY -> CropGrab.RIGHT
            nearT && insideX -> CropGrab.TOP
            nearB && insideX -> CropGrab.BOTTOM
            x in crop.left..crop.right && y in crop.top..crop.bottom -> CropGrab.MOVE
            else -> CropGrab.NONE
        }
    }

    /** [crop] after dragging [grab] by ([dx], [dy]) normalized units; the frame stays inside the image and at least [MIN_SIDE] wide. */
    fun drag(crop: CropSettings, grab: CropGrab, dx: Float, dy: Float): CropSettings {
        var l = crop.left
        var t = crop.top
        var r = crop.right
        var b = crop.bottom
        when (grab) {
            CropGrab.NONE -> Unit
            CropGrab.MOVE -> {
                val mx = dx.coerceIn(-l, 1f - r)
                val my = dy.coerceIn(-t, 1f - b)
                l += mx
                r += mx
                t += my
                b += my
            }
            else -> {
                if (grab == CropGrab.LEFT || grab == CropGrab.TOP_LEFT || grab == CropGrab.BOTTOM_LEFT) l = (l + dx).coerceIn(0f, r - MIN_SIDE)
                if (grab == CropGrab.RIGHT || grab == CropGrab.TOP_RIGHT || grab == CropGrab.BOTTOM_RIGHT) r = (r + dx).coerceIn(l + MIN_SIDE, 1f)
                if (grab == CropGrab.TOP || grab == CropGrab.TOP_LEFT || grab == CropGrab.TOP_RIGHT) t = (t + dy).coerceIn(0f, b - MIN_SIDE)
                if (grab == CropGrab.BOTTOM || grab == CropGrab.BOTTOM_LEFT || grab == CropGrab.BOTTOM_RIGHT) b = (b + dy).coerceIn(t + MIN_SIDE, 1f)
            }
        }
        return crop.copy(left = l, top = t, right = r, bottom = b)
    }
}

/** The editable crop frame over the (uncropped) preview: drag a corner, an edge or the inside; each drag is one history step. */
@Composable
internal fun CropOverlay(crop: CropSettings, frameWidth: Int, frameHeight: Int, vm: DevelopViewModel, modifier: Modifier = Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val fitted = FittedFrame.of(box, frameWidth, frameHeight)
    val currentCrop by rememberUpdatedState(crop)
    Box(modifier.fillMaxSize().onSizeChanged { box = it }) {
        if (fitted != null) {
            Canvas(Modifier.fillMaxSize()) {
                val tl = fitted.toPx(Offset(crop.left, crop.top))
                val br = fitted.toPx(Offset(crop.right, crop.bottom))
                val shade = Color.Black.copy(alpha = 0.42f)
                val frameTopLeft = Offset(fitted.left, fitted.top)
                val frameSize = Size(fitted.width, fitted.height)
                drawRect(shade, frameTopLeft, Size(frameSize.width, tl.y - fitted.top))
                drawRect(shade, Offset(fitted.left, br.y), Size(frameSize.width, fitted.top + frameSize.height - br.y))
                drawRect(shade, Offset(fitted.left, tl.y), Size(tl.x - fitted.left, br.y - tl.y))
                drawRect(shade, Offset(br.x, tl.y), Size(fitted.left + frameSize.width - br.x, br.y - tl.y))
                drawRect(Color.White, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(1.dp.toPx()))
                for (n in 1..2) {
                    val x = tl.x + (br.x - tl.x) * n / 3f
                    val y = tl.y + (br.y - tl.y) * n / 3f
                    drawLine(Color.White.copy(alpha = 0.7f), Offset(x, tl.y), Offset(x, br.y), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.7f), Offset(tl.x, y), Offset(br.x, y), 1.dp.toPx())
                }
                listOf(tl, Offset(br.x, tl.y), Offset(tl.x, br.y), br).forEach { drawCircle(Color.White, 5.dp.toPx(), it) }
            }
            Box(
                Modifier.fillMaxSize().pointerInput(fitted.width, fitted.height) {
                    var grab = CropGrab.NONE
                    var start = currentCrop
                    var travelled = Offset.Zero
                    detectDragGestures(
                        onDragStart = { down ->
                            val n = fitted.toNorm(down)
                            val slop = 28.dp.toPx()
                            grab = CropDrag.hit(currentCrop, n.x, n.y, slop / fitted.width, slop / fitted.height)
                            start = currentCrop
                            travelled = Offset.Zero
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (grab == CropGrab.NONE) return@detectDragGestures
                            travelled += amount
                            val next = CropDrag.drag(start, grab, travelled.x / fitted.width, travelled.y / fitted.height)
                            vm.preview { it.copy(crop = next) }
                        },
                        onDragEnd = {
                            if (grab != CropGrab.NONE) vm.commit("Crop")
                            grab = CropGrab.NONE
                        },
                        onDragCancel = { grab = CropGrab.NONE },
                    )
                },
            )
        }
    }
}
