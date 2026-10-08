package app.croptop.feature.editor.tabs

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/** Gesture behavior shared by document tabs: middle-click closes; a long drag moves by tab slots. */
@Composable
internal fun Modifier.documentTabGestures(
    tabKey: Any,
    tabPosition: () -> Int,
    tabCount: () -> Int,
    reorderThresholdPx: Float,
    onMiddleClick: () -> Unit,
    onMoveBy: (Int) -> Unit,
): Modifier {
    val currentPosition = rememberUpdatedState(tabPosition)
    val currentCount = rememberUpdatedState(tabCount)
    val currentMiddleClick = rememberUpdatedState(onMiddleClick)
    val currentMoveBy = rememberUpdatedState(onMoveBy)
    val drag = remember(tabKey) { DocumentTabReorderDrag() }

    return this
        .pointerInput(tabKey) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                        currentMiddleClick.value()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .pointerInput(tabKey, reorderThresholdPx) {
            detectDragGesturesAfterLongPress(
                onDragStart = { drag.reset() },
                onDragEnd = { drag.reset() },
                onDragCancel = { drag.reset() },
                onDrag = { change, amount ->
                    change.consume()
                    drag.drag(
                        deltaPx = amount.x,
                        currentPosition = currentPosition.value(),
                        tabCount = currentCount.value(),
                        thresholdPx = reorderThresholdPx,
                        onMoveBy = currentMoveBy.value,
                    )
                },
            )
        }
}

/** Accumulates horizontal drag distance and emits one neighboring move per slot threshold. */
internal class DocumentTabReorderDrag {
    private var remainderPx = 0f

    fun reset() {
        remainderPx = 0f
    }

    fun drag(
        deltaPx: Float,
        currentPosition: Int,
        tabCount: Int,
        thresholdPx: Float,
        onMoveBy: (Int) -> Unit,
    ) {
        if (thresholdPx <= 0f || tabCount <= 1 || currentPosition !in 0 until tabCount) return
        var position = currentPosition
        remainderPx += deltaPx
        while (remainderPx >= thresholdPx) {
            if (position >= tabCount - 1) {
                remainderPx = 0f
                return
            }
            onMoveBy(1)
            position++
            remainderPx -= thresholdPx
        }
        while (remainderPx <= -thresholdPx) {
            if (position <= 0) {
                remainderPx = 0f
                return
            }
            onMoveBy(-1)
            position--
            remainderPx += thresholdPx
        }
    }
}
