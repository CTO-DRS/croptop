package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EngineJob
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.WorkingSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ColorProfileDialog(
    initial: ColorProfileRef,
    convertPixels: Boolean,
    onApply: (ColorProfileRef, Boolean) -> EngineJob,
    onDismiss: () -> Unit,
) {
    val initialBuiltIn = initial as? ColorProfileRef.BuiltIn
    var space by remember(initial) { mutableStateOf(initialBuiltIn?.space ?: WorkingSpace.SRGB) }
    var linear by remember(initial) { mutableStateOf(initialBuiltIn?.linear ?: false) }
    var activeJob by remember { mutableStateOf<EngineJob?>(null) }
    var busy by remember { mutableStateOf(false) }
    var closeAfterCancel by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val progress = activeJob?.progress?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val action = if (convertPixels) "Convert" else "Assign"

    fun dismiss() {
        if (busy) {
            closeAfterCancel = true
            activeJob?.cancel()
        } else {
            onDismiss()
        }
    }

    fun apply() {
        if (busy) return
        error = null
        busy = true
        closeAfterCancel = false
        scope.launch {
            try {
                val job = withContext(Dispatchers.Default) {
                    onApply(ColorProfileRef.BuiltIn(space, linear), convertPixels)
                }
                activeJob = job
                when (val result = job.await()) {
                    is CommandResult.Accepted -> onDismiss()

                    is CommandResult.Rejected -> when {
                        result.code == RejectionCode.CANCELLED && closeAfterCancel -> onDismiss()
                        result.code == RejectionCode.CANCELLED -> error = "Profile conversion canceled"
                        else -> error = result.message
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Color profile operation failed"
            } finally {
                busy = false
                activeJob = null
            }
        }
    }

    AeDialog(
        title = "$action Color Profile",
        body = if (convertPixels) {
            "Convert RGB pixel data and assign the selected profile as one undoable operation."
        } else {
            "Assign the selected profile without changing RGB pixel values."
        },
        onDismiss = ::dismiss,
        onConfirm = ::apply,
        width = 460.dp,
        actions = {
            AeButton(if (busy) "Cancel conversion" else "Cancel", ::dismiss)
            AeButton(action, ::apply, style = AeButtonStyle.Primary, enabled = !busy)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AeDropdown(
                WorkingSpace.entries,
                space,
                { space = it },
                WorkingSpace::label,
                enabled = !busy,
            )
            AeCheckbox(linear, { linear = it }, "Linear RGB", enabled = !busy)
            if (convertPixels && busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fraction = progress?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
                    if (fraction == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            },
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
                        )
                    }
                    AeText("Converting pixels…")
                }
            }
            error?.let { AeText(it, color = Ae.colors.danger) }
        }
    }
}
