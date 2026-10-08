package app.croptop.feature.editor.dialogs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.BlendIfPair
import app.croptop.core.model.BlendIfRange
import app.croptop.core.model.BlendIfSettings
import app.croptop.core.model.LayerId
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
class BlendOptionsDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun typedBlendIfValuesApplyAsOneChange() {
        var applied: Triple<LayerId, Float, BlendIfSettings>? = null
        compose.setContent {
            CropTopTheme {
                BlendOptionsDialog(LayerId(7), "Sky", 1f, BlendIfSettings(), { id, fill, blendIf ->
                    applied = Triple(id, fill, blendIf)
                    true
                }, {})
            }
        }
        compose.onNodeWithContentDescription("This layer White start").performClick()
        compose.onNodeWithContentDescription("This layer White start").performTextClearance()
        compose.onNodeWithContentDescription("This layer White start").performTextInput("200")
        compose.onNodeWithContentDescription("This layer White start").performImeAction()
        compose.onNodeWithText("Apply").performClick()

        val (id, fill, blendIf) = applied!!
        assertEquals(LayerId(7), id)
        assertEquals(1f, fill)
        assertEquals(BlendIfSettings(self = BlendIfRange(whiteLo = 200)), blendIf)
    }

    @Test
    fun resetRestoresDefaults() {
        var applied: Triple<LayerId, Float, BlendIfSettings>? = null
        val start = BlendIfSettings(under = BlendIfRange(blackLo = 20, blackHi = 60))
        compose.setContent {
            CropTopTheme {
                BlendOptionsDialog(LayerId(1), "L", 0.4f, start, { id, fill, blendIf ->
                    applied = Triple(id, fill, blendIf)
                    true
                }, {})
            }
        }
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Apply").performClick()
        assertEquals(1f, applied!!.second)
        assertEquals(BlendIfSettings(), applied!!.third)
    }

    @Test
    fun aChannelTabEditsOnlyThatChannelsSliders() {
        var applied: Triple<LayerId, Float, BlendIfSettings>? = null
        compose.setContent {
            CropTopTheme {
                BlendOptionsDialog(LayerId(3), "Photo", 1f, BlendIfSettings(), { id, fill, blendIf ->
                    applied = Triple(id, fill, blendIf)
                    true
                }, {})
            }
        }
        compose.onNodeWithText("Red").performClick()
        compose.onNodeWithContentDescription("This layer White start").performClick()
        compose.onNodeWithContentDescription("This layer White start").performTextClearance()
        compose.onNodeWithContentDescription("This layer White start").performTextInput("180")
        compose.onNodeWithContentDescription("This layer White start").performImeAction()
        compose.onNodeWithText("Apply").performClick()

        val blendIf = applied!!.third
        assertEquals(BlendIfPair(self = BlendIfRange(whiteLo = 180)), blendIf.red)
        assertEquals(BlendIfRange(), blendIf.self, "the gray sliders are untouched")
        assertEquals(BlendIfPair(), blendIf.green)
        assertEquals(BlendIfPair(), blendIf.blue)
    }

    @Test
    fun aChannelWithValuesIsMarkedOnItsTab() {
        compose.setContent {
            CropTopTheme {
                BlendOptionsDialog(
                    LayerId(4),
                    "Photo",
                    1f,
                    BlendIfSettings(blue = BlendIfPair(under = BlendIfRange(blackLo = 10, blackHi = 20))),
                    { _, _, _ -> true },
                    {},
                )
            }
        }
        compose.onNodeWithText("Blue •").assertExists()
        compose.onNodeWithText("Red").assertExists()
    }
}
