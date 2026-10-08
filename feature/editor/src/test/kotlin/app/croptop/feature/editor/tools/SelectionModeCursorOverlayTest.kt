package app.croptop.feature.editor.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.croptop.core.input.Modifiers
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SelectionModeCursorOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `magnetic lasso cursor overlay changes for add subtract and intersect`() {
        val modifiers = mutableStateOf(Modifiers.None)
        compose.setContent {
            Box(Modifier.size(100.dp).background(Color.DarkGray)) {
                SelectionModeCursorOverlay(ToolId.MAGNETIC_LASSO, ViewPoint(30f, 30f), modifiers.value)
            }
        }

        fun pixels(): List<Int> {
            val bitmap = compose.onRoot().captureToImage()
            val pixelMap = bitmap.toPixelMap()
            return buildList(bitmap.width * bitmap.height) {
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) add(pixelMap[x, y].toArgb())
                }
            }
        }

        val replace = pixels()
        compose.runOnIdle { modifiers.value = Modifiers(shift = true) }
        val add = pixels()
        compose.runOnIdle { modifiers.value = Modifiers(alt = true) }
        val subtract = pixels()
        compose.runOnIdle { modifiers.value = Modifiers(shift = true, alt = true) }
        val intersect = pixels()

        assertEquals(replace.size, add.size)
        assertNotEquals(replace, add)
        assertNotEquals(add, subtract)
        assertNotEquals(subtract, intersect)
    }
}
