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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.croptop.core.model.UprightGuide
import kotlin.math.abs

/** A guide drawn from [a] to [b] (fractions of the frame), clamped inside it; null when it is too short to mean anything. */
fun guideFromDrag(a: Offset, b: Offset, minLength: Float = 0.03f): UprightGuide? {
    fun c(v: Float) = v.coerceIn(0f, 1f)
    if (abs(b.x - a.x) + abs(b.y - a.y) < minLength) return null
    return UprightGuide(c(a.x), c(a.y), c(b.x), c(b.y))
}

/** Draws the Guided-upright lines and lets a drag add a new one (the lines should become vertical or horizontal). */
@Composable
internal fun GuideLayer(guides: List<UprightGuide>, frameWidth: Int, frameHeight: Int, vm: DevelopViewModel, modifier: Modifier = Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val fitted = FittedFrame.of(box, frameWidth, frameHeight)
    val currentFitted by rememberUpdatedState(fitted)
    var drawing by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    Box(modifier.fillMaxSize().onSizeChanged { box = it }) {
        if (fitted != null) {
            Canvas(Modifier.fillMaxSize()) {
                guides.forEach { g ->
                    drawLine(Color(0xFFFFD54F), fitted.toPx(Offset(g.x1, g.y1)), fitted.toPx(Offset(g.x2, g.y2)), 2.dp.toPx())
                }
                drawing?.let { (a, b) -> drawLine(Color.White, fitted.toPx(a), fitted.toPx(b), 2.dp.toPx()) }
            }
            Box(
                Modifier.fillMaxSize().pointerInput(fitted.width, fitted.height) {
                    detectDragGestures(
                        onDragStart = { p -> currentFitted?.toNorm(p)?.let { drawing = it to it } },
                        onDrag = { change, _ ->
                            change.consume()
                            val f = currentFitted ?: return@detectDragGestures
                            drawing = drawing?.let { it.first to f.toNorm(change.position) }
                        },
                        onDragEnd = {
                            drawing?.let { (a, b) -> guideFromDrag(a, b)?.let(vm::addGuide) }
                            drawing = null
                        },
                        onDragCancel = { drawing = null },
                    )
                },
            )
        }
    }
}
