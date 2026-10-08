package app.croptop.app.recovery

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecoveryPromptContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun failedScanShowsRetryAndKeepsLaterAvailable() {
        val message = "Could not check recovery data. Try again."
        var retries = 0
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                RecoveryPromptContent(
                    documents = emptyList(),
                    onRecover = {},
                    onDiscard = {},
                    onDiscardAll = {},
                    onLater = {},
                    error = message,
                    onRetry = { retries++ },
                )
            }
        }

        compose.onNodeWithText("Recovery unavailable").assertExists()
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("Try Again").performClick()
        compose.onNodeWithText("Later").assertExists()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun failedDiscardKeepsTheRecoveryDocumentVisible() {
        val message = "Could not discard recovery data. The document is still available."
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                RecoveryPromptContent(
                    documents = listOf(recoverable()),
                    onRecover = {},
                    onDiscard = {},
                    onDiscardAll = {},
                    onLater = {},
                    error = message,
                )
            }
        }

        compose.onNodeWithText("Important draft").assertExists()
        compose.onNodeWithText(message).assertExists()
    }

    private fun recoverable() = app.croptop.core.engine.api.RecoverableDocument(
        id = "draft-id",
        name = "Important draft",
        size = app.croptop.core.model.DocSize(640, 480),
        lastEditEpochMs = 0L,
    )
}
