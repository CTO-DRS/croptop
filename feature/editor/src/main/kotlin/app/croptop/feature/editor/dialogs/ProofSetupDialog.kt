package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.ProofIntent
import app.croptop.core.model.ProofTarget
import app.croptop.core.model.SoftProofSettings

/** View ▸ Proof Setup…: which device the soft proof simulates and how colours are mapped to it. */
@Composable
fun ProofSetupDialog(initial: SoftProofSettings, onApply: (SoftProofSettings) -> Unit, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf(initial.target) }
    var ink by remember { mutableStateOf(initial.inkLimitPercent) }
    var intent by remember { mutableStateOf(initial.intent) }
    var blackPoint by remember { mutableStateOf(initial.blackPointCompensation) }
    var gamut by remember { mutableStateOf(initial.gamutWarning) }
    AeDialog(
        title = "Proof Setup",
        body = "Shows the picture as the chosen device would reproduce it. Only the view changes; your document and exports do not.",
        onDismiss = onDismiss,
        onConfirm = { onApply(SoftProofSettings(target, ink, intent, blackPoint, gamut)) },
        width = 420.dp,
        actions = {
            AeButton("Cancel", onDismiss, style = AeButtonStyle.Secondary)
            AeButton("OK", { onApply(SoftProofSettings(target, ink, intent, blackPoint, gamut)) }, style = AeButtonStyle.Primary)
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeText("Device to simulate", style = Ae.type.caption, color = Ae.colors.textSecondary, modifier = Modifier.weight(0.45f))
                AeDropdown(ProofTarget.entries, target, { target = it }, { it.label }, Modifier.weight(0.55f))
            }
            if (target == ProofTarget.PRESS_CMYK) {
                AeBarSlider(
                    "Total ink limit", ink, { ink = it }, range = SoftProofSettings.INK_MIN..SoftProofSettings.INK_MAX, defaultValue = 300f,
                    step = 5f, unit = "%",
                )
                AeText(
                    "A generic process press, not a measured one: choose it to judge how much of the picture CMYK can hold.",
                    style = Ae.type.caption,
                    color = Ae.colors.textTertiary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeText("Rendering intent", style = Ae.type.caption, color = Ae.colors.textSecondary, modifier = Modifier.weight(0.45f))
                AeDropdown(ProofIntent.entries, intent, { intent = it }, { it.label }, Modifier.weight(0.55f))
            }
            AeCheckbox(blackPoint, { blackPoint = it }, "Black point compensation")
            AeCheckbox(gamut, { gamut = it }, "Mark colours the device cannot reproduce (gamut warning)")
        }
    }
}
