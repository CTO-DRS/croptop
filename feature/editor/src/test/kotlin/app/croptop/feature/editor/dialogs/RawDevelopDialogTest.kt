package app.croptop.feature.editor.dialogs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.BasicTone
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.LayerId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class RawDevelopDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun resetThenApplyKeepsTheRestOfTheRecipe() {
        var applied: DevelopSettings? = null
        val recipe = DevelopSettings(basic = BasicTone(exposure = 1.5f, contrast = 20f))
        compose.setContent {
            CropTopTheme { RawDevelopDialog(LayerId(3), "IMG_1", recipe, { _, s -> applied = s }, {}) }
        }
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Apply").performClick()
        assertEquals(recipe.copy(basic = BasicTone()), applied)
    }

    @Test
    fun applyIsDisabledUntilSomethingChanges() {
        var applied: DevelopSettings? = null
        compose.setContent {
            CropTopTheme { RawDevelopDialog(LayerId(3), "IMG_1", DevelopSettings(), { _, s -> applied = s }, {}) }
        }
        compose.onNodeWithText("Apply").performClick()
        assertNull(applied)
    }
}
