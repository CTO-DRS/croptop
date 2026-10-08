package app.croptop.feature.editor.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class DocumentTabGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun middleClickClosesOnlyTheSelectedTab() {
        compose.setContent {
            TabRowForGestureTest()
        }

        compose.onNodeWithText("Second").performMouseInput {
            moveTo(center)
            press(button = MouseButton.Tertiary)
            release(button = MouseButton.Tertiary)
        }

        assertEquals(0, compose.onAllNodesWithText("Second").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("First").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Third").fetchSemanticsNodes().size)
    }

    @Test
    fun longPressDragReordersAcrossEachThreshold() {
        compose.setContent { TabRowForGestureTest() }

        compose.onNodeWithText("First").performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(96f, 0f))
            up()
        }

        assertEquals(1, compose.onAllNodesWithText("Order: Second, Third, First").fetchSemanticsNodes().size)
    }

    @Test
    fun reorderAccumulatorKeepsPartialDistanceAndStopsAtTheEnd() {
        val drag = DocumentTabReorderDrag()
        val moves = mutableListOf<Int>()

        drag.drag(deltaPx = 20f, currentPosition = 0, tabCount = 3, thresholdPx = 40f, onMoveBy = moves::add)
        drag.drag(deltaPx = 30f, currentPosition = 0, tabCount = 3, thresholdPx = 40f, onMoveBy = moves::add)
        drag.drag(deltaPx = 100f, currentPosition = 1, tabCount = 3, thresholdPx = 40f, onMoveBy = moves::add)
        drag.drag(deltaPx = -40f, currentPosition = 2, tabCount = 3, thresholdPx = 40f, onMoveBy = moves::add)

        assertEquals(listOf(1, 1, -1), moves)
    }

    @Test
    fun dragCancelResetsUnconsumedDistance() {
        val drag = DocumentTabReorderDrag()
        val moves = mutableListOf<Int>()

        drag.drag(deltaPx = 30f, currentPosition = 0, tabCount = 3, thresholdPx = 40f, onMoveBy = moves::add)
        drag.reset()
        drag.drag(deltaPx = 15f, currentPosition = 0, tabCount = 3, thresholdPx = 40f, onMoveBy = moves::add)

        assertEquals(emptyList(), moves)
    }
}

@androidx.compose.runtime.Composable
private fun TabRowForGestureTest() {
    var tabs by remember { mutableStateOf(listOf("First", "Second", "Third")) }
    Row {
        tabs.forEach { tab ->
            Box(
                Modifier
                    .size(width = 96.dp, height = 48.dp)
                    .documentTabGestures(
                        tabKey = tab,
                        tabPosition = { tabs.indexOf(tab) },
                        tabCount = { tabs.size },
                        reorderThresholdPx = 40f,
                        onMiddleClick = { tabs = tabs.filterNot { it == tab } },
                        onMoveBy = { delta ->
                            val from = tabs.indexOf(tab)
                            val to = (from + delta).coerceIn(tabs.indices)
                            if (to != from) {
                                tabs = tabs.toMutableList().apply { add(to, removeAt(from)) }
                            }
                        },
                    ),
            ) { Text(tab) }
        }
    }
    Text("Order: ${tabs.joinToString()}")
}
