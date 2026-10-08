package app.croptop.feature.editor.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.model.DocSize
import kotlin.math.roundToInt

val ExportFormat.mimeType: String
    get() = when (this) {
        ExportFormat.JPEG -> "image/jpeg"
        ExportFormat.PNG -> "image/png"
        ExportFormat.WEBP -> "image/webp"
        ExportFormat.TIFF -> "image/tiff"
        ExportFormat.PSD -> "image/vnd.adobe.photoshop"
        ExportFormat.AVIF -> "image/avif"
        ExportFormat.ULTRA_HDR -> "image/jpeg"
    }

val ExportFormat.extension: String
    get() = when (this) {
        ExportFormat.JPEG -> "jpg"
        ExportFormat.PNG -> "png"
        ExportFormat.WEBP -> "webp"
        ExportFormat.TIFF -> "tif"
        ExportFormat.PSD -> "psd"
        ExportFormat.AVIF -> "avif"
        ExportFormat.ULTRA_HDR -> "jpg"
    }

private val ExportFormat.label: String
    get() = when (this) {
        ExportFormat.JPEG -> "JPEG"
        ExportFormat.PNG -> "PNG"
        ExportFormat.WEBP -> "WebP"
        ExportFormat.TIFF -> "TIFF"
        ExportFormat.PSD -> "PSD"
        ExportFormat.AVIF -> "AVIF"
        ExportFormat.ULTRA_HDR -> "Ultra HDR"
    }

/** "Close with unsaved changes". Save is the primary action. */
@Composable
fun UnsavedChangesDialog(name: String, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AeDialog(
        title = "Save changes to “$name”?",
        body = "Your changes will be lost if you don't save them.",
        onDismiss = onCancel,
        onConfirm = onSave,
        actions = {
            AeButton("Don't Save", onDiscard, style = AeButtonStyle.Ghost)
            AeButton("Cancel", onCancel)
            AeButton("Save", onSave, style = AeButtonStyle.Primary)
        },
    )
}

/** Export As: format, quality, scale, metadata. Confirm opens the system save picker. */
@Composable
fun ExportDialog(
    document: DocSize,
    onExport: (ExportOptions) -> Unit,
    onCancel: () -> Unit,
    onActionCompleted: (String) -> Unit = {},
) {
    var format by rememberSaveable { mutableStateOf(ExportFormat.JPEG) }
    var quality by rememberSaveable { mutableFloatStateOf(90f) }
    var scale by rememberSaveable { mutableFloatStateOf(100f) }
    var strip by rememberSaveable { mutableStateOf(false) }
    val w = (document.width * scale / 100f).roundToInt().coerceAtLeast(1)
    val h = (document.height * scale / 100f).roundToInt().coerceAtLeast(1)
    fun confirm() {
        onActionCompleted("Choose export location")
        onExport(ExportOptions(format, quality.roundToInt().coerceIn(1, 100), scale / 100f, strip))
    }
    AeDialog(
        title = "Export As",
        onDismiss = onCancel,
        onConfirm = ::confirm,
        actions = {
            AeButton("Cancel", onCancel)
            AeButton("Export…", ::confirm, style = AeButtonStyle.Primary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AeText("Format", color = Ae.colors.textSecondary, modifier = Modifier.weight(1f))
                app.croptop.core.designsystem.component.AeDropdown(ExportFormat.entries, format, {
                    format = it
                    onActionCompleted("Set export format to ${it.label}")
                }, { it.label })
            }
            if (format == ExportFormat.JPEG || format == ExportFormat.WEBP || format == ExportFormat.AVIF || format == ExportFormat.ULTRA_HDR) {
                AeSlider(
                    "Quality",
                    quality,
                    { quality = it },
                    range = 1f..100f,
                    defaultValue = 90f,
                    onValueChangeFinished = { onActionCompleted("Set export quality") },
                )
            }
            AeSlider(
                "Scale",
                scale,
                { scale = it },
                range = 1f..400f,
                defaultValue = 100f,
                unit = "%",
                onValueChangeFinished = { onActionCompleted("Set export scale") },
            )
            AeText("$w × $h px", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeCheckbox(strip, {
                strip = it
                onActionCompleted(if (it) "Remove export metadata" else "Keep export metadata")
            }, "Remove metadata (camera, location)")
        }
    }
}

/** Shows only compatibility notes actually returned by the document engine after export. */
@Composable
fun ExportCompatibilityDialog(format: ExportFormat, warnings: List<String>, onDismiss: () -> Unit) {
    AeDialog(
        title = "Export notes",
        body = "The ${format.label} export completed with these format or metadata changes:",
        onDismiss = onDismiss,
        onConfirm = onDismiss,
        width = 480.dp,
        actions = { AeButton("Done", onDismiss, style = AeButtonStyle.Primary) },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            warnings.distinct().forEach { warning ->
                AeText("• $warning", color = Ae.colors.textPrimary)
            }
        }
    }
}
