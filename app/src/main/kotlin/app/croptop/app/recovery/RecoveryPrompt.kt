package app.croptop.app.recovery

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.RecoverableDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Documents an unexpected exit left unsaved, read once when Home first appears. */
@HiltViewModel
class RecoveryViewModel @Inject constructor(private val engine: Engine) : ViewModel() {
    private val pending = MutableStateFlow<List<RecoverableDocument>>(emptyList())
    val documents: StateFlow<List<RecoverableDocument>> = pending.asStateFlow()
    private val recoveryError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = recoveryError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val documents = try {
                withContext(Dispatchers.IO) { engine.recoverableDocuments() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recoveryError.value = "Could not check recovery data. Try again."
                return@launch
            }
            pending.value = documents
            recoveryError.value = null
        }
    }

    /** The editor takes over [id]; drop it from the prompt. */
    fun taken(id: String) {
        pending.update { list -> list.filterNot { it.id == id } }
        recoveryError.value = null
    }

    fun discard(id: String) {
        viewModelScope.launch {
            val discarded = withContext(Dispatchers.IO) { runCatching { engine.discardRecovery(id) }.getOrDefault(false) }
            if (discarded) {
                taken(id)
            } else {
                recoveryError.value = "Could not discard recovery data. The document is still available."
            }
        }
    }

    fun discardAll() {
        val ids = pending.value.map { it.id }
        viewModelScope.launch {
            val (discarded, failed) = withContext(Dispatchers.IO) {
                ids.partition { id -> runCatching { engine.discardRecovery(id) }.getOrDefault(false) }
            }
            if (discarded.isNotEmpty()) {
                pending.update { documents -> documents.filterNot { it.id in discarded } }
            }
            recoveryError.value = if (failed.isEmpty()) {
                null
            } else {
                "Could not discard ${failed.size} recovery document${if (failed.size == 1) "" else "s"}. They remain available."
            }
        }
    }

    /** Keeps the data on disk; the prompt comes back on the next launch. */
    fun later() {
        pending.value = emptyList()
        recoveryError.value = null
    }
}

/** Launch prompt: "CropTop closed before these documents were saved." Renders nothing when there is nothing to recover. */
@Composable
fun RecoveryPrompt(onRecover: (RecoverableDocument) -> Unit, viewModel: RecoveryViewModel = hiltViewModel()) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    RecoveryPromptContent(
        documents = documents,
        onRecover = { document ->
            viewModel.taken(document.id)
            onRecover(document)
        },
        onDiscard = viewModel::discard,
        onDiscardAll = viewModel::discardAll,
        onLater = viewModel::later,
        onRetry = viewModel::refresh,
        error = error,
    )
}

@Composable
internal fun RecoveryPromptContent(
    documents: List<RecoverableDocument>,
    onRecover: (RecoverableDocument) -> Unit,
    onDiscard: (String) -> Unit,
    onDiscardAll: () -> Unit,
    onLater: () -> Unit,
    error: String? = null,
    onRetry: () -> Unit = {},
) {
    if (documents.isEmpty() && error == null) return
    val title = when {
        documents.isEmpty() -> "Recovery unavailable"
        documents.size == 1 -> "Recover 1 document"
        else -> "Recover ${documents.size} documents"
    }
    AeDialog(
        title = title,
        body = if (documents.isEmpty()) {
            "CropTop could not check for unsaved documents. Retry to check again."
        } else {
            "CropTop closed before these documents were saved. Recover them to keep your changes."
        },
        onDismiss = onLater,
        width = 480.dp,
        actions = {
            if (documents.isNotEmpty()) AeButton("Discard All", onDiscardAll, style = AeButtonStyle.Ghost)
            AeButton("Later", onLater)
            if (documents.isEmpty()) {
                AeButton("Try Again", onRetry, style = AeButtonStyle.Primary)
            } else if (documents.size == 1) {
                AeButton("Recover", {
                    onRecover(documents.first())
                }, style = AeButtonStyle.Primary)
            }
        },
    ) {
        documents.forEach { document ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AeText(document.name, style = Ae.type.body)
                    val edited = DateUtils.getRelativeTimeSpanString(document.lastEditEpochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    AeText(
                        "${document.size.width} × ${document.size.height} px · edited $edited",
                        style = Ae.type.caption,
                        color = Ae.colors.textSecondary,
                    )
                }
                if (documents.size > 1) {
                    AeButton("Discard", { onDiscard(document.id) }, style = AeButtonStyle.Ghost)
                    AeButton("Recover", { onRecover(document) }, style = AeButtonStyle.Primary)
                }
            }
        }
        error?.let { AeText(it, color = Ae.colors.danger) }
    }
}
