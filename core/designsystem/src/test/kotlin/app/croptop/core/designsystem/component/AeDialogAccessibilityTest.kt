package app.croptop.core.designsystem.component

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.croptop.core.designsystem.theme.CropTopTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AeDialogAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dialogExposesPaneTitleAndAccessibleActionLabels() {
        compose.setContent {
            CropTopTheme {
                AeDialog(
                    title = "Export image",
                    onDismiss = {},
                    onConfirm = {},
                    actions = {
                        AeButton("Cancel", {})
                        AeButton("Export", {}, style = AeButtonStyle.Primary)
                    },
                ) {
                    AeText("Choose an export format")
                }
            }
        }

        assertTrue(
            compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Export image"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithText("Export").assertExists()
    }
}
