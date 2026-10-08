package app.croptop.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.ai.ModelManagerState
import app.croptop.core.ai.ModelRow
import app.croptop.core.ai.formatModelBytes
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeProgressBar
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae

/** Everything the "AI models" section and the first-use download dialog need. */
data class AiModelsUi(
    val state: ModelManagerState,
    val onInstall: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onDelete: (String) -> Unit,
)

@Composable
fun AiModelsBody(ui: AiModelsUi) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        for (row in ui.state.rows) ModelRowItem(row, ui)
        AeText(
            "Models use ${formatModelBytes(ui.state.usedBytes)} of storage. They run on this device; nothing is uploaded.",
            style = Ae.type.caption,
            color = Ae.colors.textTertiary,
            maxLines = 3,
        )
    }
}

@Composable
private fun ModelRowItem(row: ModelRow, ui: AiModelsUi) {
    val id = row.spec.id
    val name = row.spec.displayName
    val size = formatModelBytes(row.spec.totalBytes)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                AeText(name)
                AeText(statusText(row.status, size), style = Ae.type.caption, color = statusColor(row.status), maxLines = 2)
            }
            when (row.status) {
                is ModelRow.Status.Installed -> AeButton("Delete", { ui.onDelete(id) }, style = AeButtonStyle.Secondary)
                is ModelRow.Status.Downloading -> AeButton("Cancel", { ui.onCancel(id) }, style = AeButtonStyle.Secondary)
                is ModelRow.Status.Partial -> AeButton("Resume", { ui.onInstall(id) }, style = AeButtonStyle.Primary)
                is ModelRow.Status.Failed -> AeButton("Retry", { ui.onInstall(id) }, style = AeButtonStyle.Primary)
                ModelRow.Status.NotInstalled -> AeButton("Download", { ui.onInstall(id) }, style = AeButtonStyle.Primary)
            }
        }
        (row.status as? ModelRow.Status.Downloading)?.let {
            AeProgressBar(it.fraction, "Downloading $name, ${(it.fraction * 100).toInt()} percent")
        }
    }
}

@Composable
private fun statusColor(status: ModelRow.Status) = when (status) {
    is ModelRow.Status.Failed -> Ae.colors.warning
    ModelRow.Status.Installed -> Ae.colors.success
    else -> Ae.colors.textTertiary
}

internal fun statusText(status: ModelRow.Status, size: String): String = when (status) {
    ModelRow.Status.NotInstalled -> "Not downloaded · $size"
    is ModelRow.Status.Partial -> "Paused · ${formatModelBytes(status.bytesOnDisk)} of $size"
    is ModelRow.Status.Downloading -> "Downloading · ${formatModelBytes(status.done)} of ${formatModelBytes(status.total)}"
    ModelRow.Status.Installed -> "Ready · $size"
    is ModelRow.Status.Failed -> "Download failed: ${status.reason}"
}

/**
 * First-use sheet shown when a feature needs a model that is not on the device: size up front, live progress, Cancel.
 * Closes itself through [onReady] once the model is installed.
 */
@Composable
fun ModelDownloadDialog(
    modelId: String,
    featureName: String,
    ui: AiModelsUi,
    onDismiss: () -> Unit,
    onReady: () -> Unit,
) {
    val row = ui.state.rows.firstOrNull { it.spec.id == modelId }
    if (row == null) {
        onDismiss()
        return
    }
    if (row.status == ModelRow.Status.Installed) {
        androidx.compose.runtime.LaunchedEffect(modelId) { onReady() }
        return
    }
    val size = formatModelBytes(row.spec.totalBytes)
    val status = row.status
    AeDialog(
        title = "Download $featureName model",
        onDismiss = {
            if (status is ModelRow.Status.Downloading) ui.onCancel(modelId)
            onDismiss()
        },
        body = "$featureName needs a $size model. It is downloaded once, stored on this device, and runs offline. " +
            "Use Wi-Fi if you are on a limited data plan.",
        actions = {
            if (status is ModelRow.Status.Downloading) {
                AeButton("Cancel", {
                    ui.onCancel(modelId)
                    onDismiss()
                }, style = AeButtonStyle.Secondary)
            } else {
                AeButton("Not now", onDismiss, style = AeButtonStyle.Secondary)
                AeButton(
                    if (status is ModelRow.Status.Failed ||
                        status is ModelRow.Status.Partial
                    ) {
                        "Retry"
                    } else {
                        "Download $size"
                    },
                    { ui.onInstall(modelId) },
                    style = AeButtonStyle.Primary,
                )
            }
        },
        onConfirm = if (status is ModelRow.Status.Downloading) null else ({ ui.onInstall(modelId) }),
    ) {
        (status as? ModelRow.Status.Downloading)?.let {
            AeProgressBar(it.fraction, "Downloading, ${(it.fraction * 100).toInt()} percent")
            AeText(statusText(status, size), style = Ae.type.caption, color = Ae.colors.textSecondary)
        }
        (status as? ModelRow.Status.Failed)?.let { AeText(statusText(it, size), style = Ae.type.caption, color = Ae.colors.warning, maxLines = 3) }
    }
}
