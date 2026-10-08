package app.croptop.feature.editor.session

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.api.RejectionCode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorActionFeedbackLabelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun visibleStatusTracksAcknowledgementCompletionAndRejection() {
        val feedback = mutableStateOf(EditorActionFeedback(1, "Save", ActionFeedbackPhase.ACKNOWLEDGED))
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                EditorActionFeedbackLabel(feedback.value)
            }
        }

        compose.onNodeWithText("Save…").assertExists()
        feedback.value = feedback.value.copy(concurrentNotice = "Fill layer queued", concurrentNoticePhase = ActionFeedbackPhase.ACCEPTED)
        compose.onNodeWithText("Fill layer queued").assertExists()
        feedback.value = feedback.value.copy(phase = ActionFeedbackPhase.COMPLETED, detail = "Saved")
        compose.onNodeWithText("Saved").assertExists()
        compose.onNodeWithText("Save…").assertDoesNotExist()

        feedback.value = feedback.value.copy(
            phase = ActionFeedbackPhase.REJECTED,
            detail = "This format is unavailable",
            rejectionCode = RejectionCode.UNSUPPORTED,
        )
        compose.onNodeWithText("This format is unavailable").assertExists()

        feedback.value = feedback.value.copy(
            phase = ActionFeedbackPhase.RUNNING,
            action = "Save",
            detail = "Saving…",
            concurrentNotice = "Wait for the current action to finish before preparing the image",
            concurrentNoticePhase = ActionFeedbackPhase.REJECTED,
            concurrentNoticeAction = "Share image",
            concurrentNoticeRejectionCode = RejectionCode.INVALID_ARGUMENT,
        )
        compose.onNodeWithText("Share image: Wait for the current action to finish before preparing the image").assertExists()
    }
}
