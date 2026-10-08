package app.croptop.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import app.croptop.core.ai.ModelManagerState
import app.croptop.core.ai.ModelRow
import app.croptop.core.ai.formatModelBytes
import app.croptop.core.designsystem.theme.Ae

/**
 * First-use sheet for an AI feature whose model is not on the device: the size up front, live progress, Cancel. When every model
 * the feature needs is installed it calls [onReady] (which re-runs the feature). [onBasic], when the feature has a basic
 * fallback, is offered as "Use basic selection".
 */
@Composable
fun AiDownloadDialog(
    featureName: String,
    modelIds: List<String>,
    state: ModelManagerState,
    onInstall: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
    onReady: () -> Unit,
    onBasic: (() -> Unit)? = null,
) {
    val rows = modelIds.mapNotNull { id -> state.rows.firstOrNull { it.spec.id == id } }
    val latestReady by rememberUpdatedState(onReady)
    val allInstalled = rows.isNotEmpty() && rows.all { it.status == ModelRow.Status.Installed }
    LaunchedEffect(allInstalled) { if (allInstalled) latestReady() }
    if (rows.isEmpty() || allInstalled) return
    val missing = rows.filter { it.status != ModelRow.Status.Installed }
    val bytes = formatModelBytes(missing.sumOf { it.spec.totalBytes })
    val downloading = missing.firstOrNull { it.status is ModelRow.Status.Downloading }
    val failed = missing.firstOrNull { it.status is ModelRow.Status.Failed }
    AeDialog(
        title = "Download $featureName",
        body = "$featureName needs a $bytes download. It is fetched once, stored on this device, and then runs offline. Use Wi-Fi if you are on a limited data plan.",
        onDismiss = {
            missing.forEach { if (it.status is ModelRow.Status.Downloading) onCancel(it.spec.id) }
            onDismiss()
        },
        actions = {
            if (downloading != null) {
                AeButton("Cancel", {
                    missing.forEach { if (it.status is ModelRow.Status.Downloading) onCancel(it.spec.id) }
                    onDismiss()
                }, style = AeButtonStyle.Secondary)
            } else {
                AeButton("Not now", onDismiss, style = AeButtonStyle.Secondary)
                if (onBasic != null) AeButton("Use basic selection", onBasic, style = AeButtonStyle.Secondary)
                AeButton(if (failed != null) "Retry" else "Download $bytes", { missing.forEach { onInstall(it.spec.id) } }, style = AeButtonStyle.Primary)
            }
        },
        onConfirm = if (downloading != null) null else ({ missing.forEach { onInstall(it.spec.id) } }),
    ) {
        val status = downloading?.status as? ModelRow.Status.Downloading
        if (status != null) {
            AeProgressBar(status.fraction, "Downloading, ${(status.fraction * 100).toInt()} percent")
            AeText(
                "${formatModelBytes(status.done)} of ${formatModelBytes(status.total)}",
                style = Ae.type.caption,
                color = Ae.colors.textSecondary,
            )
        }
        (failed?.status as? ModelRow.Status.Failed)?.let {
            AeText("Download failed: ${it.reason}", style = Ae.type.caption, color = Ae.colors.warning)
        }
    }
}
