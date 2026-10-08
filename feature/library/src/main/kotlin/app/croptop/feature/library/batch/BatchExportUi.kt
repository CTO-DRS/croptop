package app.croptop.feature.library.batch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions

private val batchFormats = listOf(ExportFormat.JPEG, ExportFormat.PNG, ExportFormat.WEBP, ExportFormat.AVIF, ExportFormat.TIFF)

private fun ExportFormat.label() = when (this) {
    ExportFormat.JPEG -> "JPEG"
    ExportFormat.PNG -> "PNG"
    ExportFormat.WEBP -> "WebP"
    ExportFormat.TIFF -> "TIFF"
    ExportFormat.PSD -> "PSD"
    ExportFormat.AVIF -> "AVIF"
    ExportFormat.ULTRA_HDR -> "Ultra HDR"
}

private fun ExportFormat.lossy() = this == ExportFormat.JPEG || this == ExportFormat.WEBP || this == ExportFormat.AVIF

/** Preset + destination for a batch. Picking the folder starts the export, so there is exactly one primary action. */
@Composable
fun BatchExportDialog(
    count: Int,
    onStart: (ExportOptions, String) -> Unit,
    onDismiss: () -> Unit,
    userPresets: List<ExportPreset> = emptyList(),
    onSavePreset: (ExportPreset) -> Boolean = { false },
    onDeletePreset: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var format by rememberSaveable { mutableStateOf(ExportFormat.JPEG) }
    var quality by rememberSaveable { mutableStateOf(92f) }
    var scale by rememberSaveable { mutableStateOf(100f) }
    var strip by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var presetNote by remember { mutableStateOf<String?>(null) }
    val allPresets = ExportPresetStore.BUILT_IN + userPresets
    val matching = allPresets.firstOrNull {
        it.format == format && it.quality == quality.toInt() && it.scalePercent == scale.toInt() && it.stripMetadata == strip
    }
    val options = ExportOptions(format, quality.toInt().coerceIn(1, 100), (scale / 100f).coerceAtLeast(0.01f), strip)
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onStart(options, uri.toString())
        }
    }
    // Progress notifications need this on Android 13+; the export works without it, so a refusal is not an error.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pickFolder.launch(null) }
    fun chooseFolder() {
        val needsPermission = android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) else pickFolder.launch(null)
    }
    AeDialog(
        title = "Export $count photo${if (count == 1) "" else "s"}",
        body = "Each photo is exported with its current edits. Files are written to the folder you choose; nothing is overwritten.",
        onDismiss = onDismiss,
        onConfirm = ::chooseFolder,
        width = 460.dp,
        actions = {
            AeButton("Cancel", onDismiss)
            AeButton("Choose Folder & Export", ::chooseFolder, style = AeButtonStyle.Primary)
        },
    ) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AeDropdown(
                allPresets,
                matching ?: ExportPreset("Custom", format, quality.toInt().coerceIn(1, 100), scale.toInt().coerceIn(1, 100), strip),
                { preset ->
                    format = preset.format
                    quality = preset.quality.toFloat()
                    scale = preset.scalePercent.toFloat()
                    strip = preset.stripMetadata
                    presetNote = null
                },
                { it.name },
                modifier = Modifier.fillMaxWidth(),
            )
            AeDropdown(batchFormats, format, { format = it }, { it.label() }, modifier = Modifier.fillMaxWidth())
            if (format.lossy()) AeSlider("Quality", quality, { quality = it }, range = 1f..100f, defaultValue = 92f)
            AeSlider("Size", scale, { scale = it }, range = 5f..100f, defaultValue = 100f, unit = "%")
            AeCheckbox(strip, { strip = it }, "Remove location and camera metadata")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    value = presetName,
                    onValueChange = { presetName = it.take(40) },
                    singleLine = true,
                    textStyle = Ae.type.body.copy(color = Ae.colors.textPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ae.colors.accent),
                    modifier = Modifier.weight(1f).background(Ae.colors.surface3, Ae.shapes.control).padding(horizontal = 10.dp, vertical = 8.dp)
                        .semantics { contentDescription = "Preset name" },
                    decorationBox = { inner ->
                        if (presetName.isEmpty()) AeText("Save these settings as…", color = Ae.colors.textTertiary)
                        inner()
                    },
                )
                AeButton("Save", {
                    val saved = presetName.isNotBlank() && onSavePreset(
                        ExportPreset(presetName.trim(), format, quality.toInt().coerceIn(1, 100), scale.toInt().coerceIn(1, 100), strip),
                    )
                    presetNote = if (saved) "Saved “${presetName.trim()}”" else "Choose a different name"
                    if (saved) presetName = ""
                }, enabled = presetName.isNotBlank())
            }
            userPresets.firstOrNull { it == matching }?.let { current ->
                AeButton("Delete preset “${current.name}”", { onDeletePreset(current.name) }, style = AeButtonStyle.Ghost)
            }
            presetNote?.let { AeText(it, style = Ae.type.caption, color = Ae.colors.textSecondary) }
        }
    }
}

/** Progress while a batch runs, then the outcome. Leaving the Library does not stop a running batch. */
@Composable
fun BatchExportStatusDialog(state: BatchExportState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        BatchExportState.Idle -> Unit

        is BatchExportState.Running -> AeDialog(
            title = "Exporting ${state.completed + 1} of ${state.total}",
            body = state.current,
            onDismiss = {},
            width = 420.dp,
            actions = { AeButton("Cancel", onCancel) },
        ) {
            app.croptop.core.designsystem.component.AeProgressBar(state.fraction, "Export progress")
            if (state.failures.isNotEmpty()) AeText("${state.failures.size} failed so far", style = Ae.type.caption, color = Ae.colors.warning)
        }

        is BatchExportState.Finished -> AeDialog(
            title = when {
                state.cancelled -> "Export cancelled"
                state.failures.isEmpty() -> "Export finished"
                else -> "Export finished with problems"
            },
            body = "${state.succeeded} of ${state.total} exported" + if (state.failures.isNotEmpty()) ", ${state.failures.size} failed." else ".",
            onDismiss = onDismiss,
            onConfirm = onDismiss,
            width = 440.dp,
            actions = { AeButton("Done", onDismiss, style = AeButtonStyle.Primary) },
        ) {
            if (state.failures.isNotEmpty()) {
                Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.failures.forEach {
                        AeText("${it.name}: ${it.reason}", style = Ae.type.caption, color = Ae.colors.textSecondary, maxLines = 2)
                    }
                }
            }
        }
    }
}
