package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeBlendIfSlider
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.BlendIfChannel
import app.croptop.core.model.BlendIfSettings
import app.croptop.core.model.LayerId

/**
 * Layer ▸ Blending Options: Fill opacity (pixels without layer styles) and Blend If Gray sliders. Alt-drag a handle to split it.
 * Apply records one history step.
 */
@Composable
fun BlendOptionsDialog(
    layerId: LayerId,
    layerName: String,
    initialFill: Float,
    initialBlendIf: BlendIfSettings,
    onApply: (LayerId, Float, BlendIfSettings) -> Boolean,
    onDismiss: () -> Unit,
    /** Shows the sliders on the canvas without a history step; null restores the committed ones. */
    onPreview: (LayerId, BlendIfSettings?) -> Unit = { _, _ -> },
    onPreviewFill: (LayerId, Float?) -> Unit = { _, _ -> },
) {
    var fill by remember(layerId) { mutableStateOf(initialFill * 100f) }
    var blendIf by remember(layerId) { mutableStateOf(initialBlendIf) }
    var failure by remember(layerId) { mutableStateOf<String?>(null) }
    var channel by remember(layerId) { mutableStateOf(BlendIfChannel.GRAY) }

    val latestPreview by androidx.compose.runtime.rememberUpdatedState(onPreview)
    androidx.compose.runtime.LaunchedEffect(blendIf) { if (blendIf != initialBlendIf) latestPreview(layerId, blendIf) }
    val latestFillPreview by androidx.compose.runtime.rememberUpdatedState(onPreviewFill)
    androidx.compose.runtime.LaunchedEffect(fill) {
        if (kotlin.math.abs(fill / 100f - initialFill) >
            0.0005f
        ) {
            latestFillPreview(layerId, (fill / 100f).coerceIn(0f, 1f))
        }
    }
    androidx.compose.runtime.DisposableEffect(layerId) {
        onDispose {
            latestPreview(layerId, null)
            latestFillPreview(layerId, null)
        }
    }

    fun apply() {
        if (onApply(layerId, (fill / 100f).coerceIn(0f, 1f), blendIf)) onDismiss() else failure = "Blending options could not be applied"
    }
    AeDialog(
        title = "Blending Options · $layerName",
        body = "Fill opacity ignores layer styles. Blend If hides pixels by brightness or by one colour channel; Alt-drag a handle to split it into a soft edge.",
        onDismiss = onDismiss,
        onConfirm = ::apply,
        width = 480.dp,
        actions = {
            AeButton("Reset", {
                fill = 100f
                blendIf = BlendIfSettings()
                failure = null
            })
            AeButton("Cancel", onDismiss)
            AeButton("Apply", ::apply, style = AeButtonStyle.Primary)
        },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AeSlider("Fill opacity", fill, { fill = it }, range = 0f..100f, defaultValue = 100f, unit = "%")
            AeSegmented(
                BlendIfChannel.entries,
                channel,
                { channel = it },
                { c -> if (blendIf.pair(c).isDefault) c.label else "${c.label} •" },
            )
            val pair = blendIf.pair(channel)
            AeBlendIfSlider(
                "This layer",
                pair.self,
                { blendIf = blendIf.with(channel, pair.copy(self = it)) },
                Modifier.fillMaxWidth(),
            )
            AeBlendIfSlider(
                "Underlying layer",
                pair.under,
                { blendIf = blendIf.with(channel, pair.copy(under = it)) },
                Modifier.fillMaxWidth(),
            )
            failure?.let { AeText(it, style = Ae.type.caption, color = Ae.colors.warning) }
        }
    }
}
