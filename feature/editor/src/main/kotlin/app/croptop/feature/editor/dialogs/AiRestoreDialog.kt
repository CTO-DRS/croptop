package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.ai.AiFeature
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae

/**
 * Options for AI Denoise (strength) and Super Resolution (×2 / ×4) before they start. [hasSelection] changes the wording of Denoise,
 * which works on the selected area when there is one. [sourceSize] lets Super Resolution show the size it would produce.
 */
@Composable
fun AiRestoreDialog(
    feature: AiFeature,
    hasSelection: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    onStart: (strength: Float, factor: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var strength by remember { mutableFloatStateOf(100f) }
    var factor by remember { mutableIntStateOf(2) }
    val denoise = feature == AiFeature.DENOISE
    AeDialog(
        title = if (denoise) "Denoise" else "Super Resolution",
        body = if (denoise) {
            "Removes grain and colour noise with an on-device model. The result goes on a new layer${if (hasSelection) " covering the selected area" else ""}, so the photo underneath is untouched."
        } else {
            "Makes the picture bigger and adds detail with an on-device model. The picture is resized and a new layer with the detail goes on top. Large pictures take a while; you can cancel."
        },
        onDismiss = onDismiss,
        actions = {
            AeButton("Cancel", onDismiss, style = AeButtonStyle.Secondary)
            AeButton("Start", { onStart(strength / 100f, factor) }, style = AeButtonStyle.Primary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (denoise) {
                AeBarSlider("Strength", strength, { strength = it }, range = 0f..100f, defaultValue = 100f, unit = "%")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4).forEach { f ->
                        AeButton(
                            "×$f",
                            { factor = f },
                            style = if (factor == f) AeButtonStyle.Primary else AeButtonStyle.Secondary,
                            modifier = Modifier,
                        )
                    }
                }
                AeText("Result: ${sourceWidth * factor} × ${sourceHeight * factor} px", color = Ae.colors.textSecondary)
            }
        }
    }
}
