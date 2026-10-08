package app.croptop.feature.editor.canvas

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CanvasViewportPickTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pickUsesCurrentViewportDocumentCoordinatesAndBypassesTheTool() {
        val viewportState = mutableStateOf(ViewportState())
        var picked: DocPoint? = null
        compose.setContent {
            CanvasViewport(
                viewport = viewportState.value,
                onViewportChange = { viewportState.value = it },
                document = DocSize(100, 100),
                modifier = Modifier.size(200.dp).testTag("canvas"),
                background = Color.Black,
                onCanvasPick = { picked = it },
            )
        }
        compose.waitForIdle()
        val expected = viewportState.value.viewToDoc(
            ViewPoint(viewportState.value.viewWidth / 2f, viewportState.value.viewHeight / 2f),
        )

        compose.onNodeWithTag("canvas").performTouchInput { click(center) }

        compose.runOnIdle {
            assertEquals(expected.x, picked!!.x, 0.01f)
            assertEquals(expected.y, picked!!.y, 0.01f)
        }
    }
}
