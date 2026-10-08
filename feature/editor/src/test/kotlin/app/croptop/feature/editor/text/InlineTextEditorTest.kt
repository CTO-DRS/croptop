package app.croptop.feature.editor.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.CharacterStyle
import app.croptop.core.model.DocPoint
import app.croptop.core.model.ParagraphStyle
import app.croptop.core.model.ViewportState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w700dp-h400dp-mdpi")
class InlineTextEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private val committed = mutableListOf<String>()
    private var cancelled = 0
    private val typing = mutableListOf<Boolean>()

    private fun show(initial: String = "", editing: Boolean = false) {
        compose.setContent {
            var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
            CropTopTheme(density = AeDensity.Pointer) {
                InlineTextEditor(
                    text = text,
                    onTextChange = { text = it },
                    viewport = ViewportState(zoom = 1f, viewWidth = 800, viewHeight = 600),
                    origin = DocPoint(100f, 120f),
                    boxWidth = null,
                    ppi = 72f,
                    character = CharacterStyle(),
                    paragraph = ParagraphStyle(),
                    editing = editing,
                    onCommit = { committed += text },
                    onCancel = { cancelled++ },
                    onTyping = typing::add,
                )
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `typing then Ctrl+Enter commits the text`() {
        show()
        compose.onNodeWithContentDescription("Text content").performTextInput("Hello")
        compose.onNodeWithContentDescription("Text content").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Enter)
            keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle { assertEquals(listOf("Hello"), committed) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Escape cancels without committing`() {
        show("Keep me")
        compose.onNodeWithContentDescription("Text content").performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertTrue(committed.isEmpty())
        }
    }

    @Test
    fun `the place button needs some text and commits what was typed`() {
        show()
        compose.onNodeWithText("Place").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Text content").performTextInput("Hi")
        compose.onNodeWithText("Place").performClick()
        compose.runOnIdle { assertEquals(listOf("Hi"), committed) }
    }

    @Test
    fun `editing shows Update and the host is told while the field is open`() {
        show("Existing", editing = true)
        compose.onNodeWithText("Update").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Existing"), committed)
            assertEquals(true, typing.firstOrNull())
        }
    }

    @Test
    fun `geometry puts the first baseline of point text on the origin and scales type with zoom`() {
        val viewport = ViewportState(zoom = 2f, viewWidth = 800, viewHeight = 600)
        val fontPx = InlineTextGeometry.viewFontPx(sizePt = 24f, ppi = 144f, zoom = viewport.zoom)
        assertEquals(24f * 2f * 2f, fontPx, 1e-4f)
        val (left, top) = InlineTextGeometry.fieldTopLeft(viewport, DocPoint(100f, 120f), paragraph = false, viewFontPx = fontPx)
        val base = viewport.docToView(DocPoint(100f, 120f))
        assertEquals(base.x, left, 1e-4f)
        assertEquals(base.y - fontPx * InlineTextGeometry.ASCENT, top, 1e-4f)
        val (_, paragraphTop) = InlineTextGeometry.fieldTopLeft(viewport, DocPoint(100f, 120f), paragraph = true, viewFontPx = fontPx)
        assertEquals(base.y, paragraphTop, 1e-4f)
        assertEquals(400f, InlineTextGeometry.fieldWidthPx(200f, 2f))
        assertEquals(null, InlineTextGeometry.fieldWidthPx(null, 2f))
    }

    @Test
    fun `point and paragraph editors look right over a canvas`() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.size(640.dp, 360.dp).background(androidx.compose.ui.graphics.Color(0xFF7A9CC6)),
                ) {
                    InlineTextEditor(
                        text = "Summer sale",
                        onTextChange = {},
                        viewport = ViewportState(zoom = 1f, viewWidth = 640, viewHeight = 360),
                        origin = DocPoint(40f, 90f),
                        boxWidth = null,
                        ppi = 72f,
                        character = CharacterStyle(sizePt = 40f),
                        paragraph = ParagraphStyle(),
                        editing = false,
                        onCommit = {},
                        onCancel = {},
                    )
                    InlineTextEditor(
                        text = "A paragraph of text wraps inside its box as you type.",
                        onTextChange = {},
                        viewport = ViewportState(zoom = 1f, viewWidth = 640, viewHeight = 360),
                        origin = DocPoint(340f, 40f),
                        boxWidth = 260f,
                        ppi = 72f,
                        character = CharacterStyle(sizePt = 20f),
                        paragraph = ParagraphStyle(),
                        editing = true,
                        onCommit = {},
                        onCancel = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/text_inline_editor.png")
    }
}
