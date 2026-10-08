package app.croptop.feature.editor.files

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.model.DocSize
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class ExportDialogFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectingFormatReportsItsValue() {
        var action: String? = null
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                ExportDialog(
                    document = DocSize(100, 100),
                    onExport = {},
                    onCancel = {},
                    onActionCompleted = { action = it },
                )
            }
        }

        compose.onNodeWithText("JPEG").performClick() // the format menu shows the current choice; open it first
        compose.onNodeWithText("PNG").performClick()

        assertEquals("Set export format to PNG", action)
    }

    @Test
    fun confirmingExportReportsDestinationHandoffBeforeLaunchingPicker() {
        val events = mutableListOf<String>()
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                ExportDialog(
                    document = DocSize(100, 100),
                    onExport = { events += "launch:${it.format}" },
                    onCancel = {},
                    onActionCompleted = { events += it },
                )
            }
        }

        compose.onNodeWithText("Export…").performClick()

        assertEquals(listOf("Choose export location", "launch:${ExportFormat.JPEG}"), events)
    }
}
