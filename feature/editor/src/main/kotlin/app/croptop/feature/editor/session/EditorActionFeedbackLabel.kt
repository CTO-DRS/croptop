package app.croptop.feature.editor.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import kotlin.math.roundToInt

/** Visible status text for an editor action; progress is displayed only when the engine supplied it. */
@Composable
internal fun EditorActionFeedbackLabel(feedback: EditorActionFeedback) {
    val active = feedback.phase == ActionFeedbackPhase.ACKNOWLEDGED || feedback.phase == ActionFeedbackPhase.RUNNING ||
        (feedback.phase == ActionFeedbackPhase.ACCEPTED && feedback.engineSequence != null)
    val progress = feedback.progress
    val text = progress?.let { "${feedback.statusText} ${(it * 100f).roundToInt()}%" } ?: feedback.statusText
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (active) {
            if (progress == null) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            }
        }
        AeText(
            text,
            style = Ae.type.caption,
            color = if (feedback.phase == ActionFeedbackPhase.REJECTED || feedback.phase == ActionFeedbackPhase.FAILED) {
                Ae.colors.warning
            } else {
                Ae.colors.textSecondary
            },
        )
        feedback.concurrentNotice?.let { notice ->
            val visibleNotice = if (feedback.concurrentNoticePhase == ActionFeedbackPhase.REJECTED && feedback.concurrentNoticeAction != null) {
                "${feedback.concurrentNoticeAction}: $notice"
            } else {
                notice
            }
            AeText(
                visibleNotice,
                modifier = Modifier.widthIn(max = 260.dp),
                style = Ae.type.caption,
                color = if (feedback.concurrentNoticePhase == ActionFeedbackPhase.REJECTED ||
                    feedback.concurrentNoticePhase == ActionFeedbackPhase.FAILED
                ) {
                    Ae.colors.warning
                } else {
                    Ae.colors.textSecondary
                },
            )
        }
    }
}
