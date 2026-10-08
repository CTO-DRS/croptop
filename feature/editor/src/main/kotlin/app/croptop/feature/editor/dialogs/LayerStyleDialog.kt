package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeColorPicker
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeGradientEditor
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.engine.api.PatternInfo
import app.croptop.core.model.BevelStyle
import app.croptop.core.model.BlendMode
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.GradientStop
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerStyles
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.StrokePosition
import app.croptop.core.model.StyleGlow
import app.croptop.core.model.StyleShadow
import app.croptop.core.model.StyleStroke

private enum class StyleEffect(val title: String) {
    DROP_SHADOW("Drop Shadow"),
    INNER_SHADOW("Inner Shadow"),
    OUTER_GLOW("Outer Glow"),
    INNER_GLOW("Inner Glow"),
    BEVEL("Bevel & Emboss"),
    SATIN("Satin"),
    COLOR_OVERLAY("Color Overlay"),
    GRADIENT_OVERLAY("Gradient Overlay"),
    PATTERN_OVERLAY("Pattern Overlay"),
    STROKE("Stroke"),
}

private data class StylePreset(val name: String, val styles: LayerStyles)

private val presets = listOf(
    StylePreset("Soft Shadow", LayerStyles(dropShadow = StyleShadow(enabled = true, opacity = 0.45f, distance = 4f, size = 12f))),
    StylePreset("White Stroke", LayerStyles(stroke = StyleStroke(enabled = true, color = RgbaColor(1f, 1f, 1f), size = 2f))),
    StylePreset("Warm Glow", LayerStyles(outerGlow = StyleGlow(enabled = true, color = RgbaColor(1f, 0.58f, 0.2f), size = 18f))),
)

/** Layer ▸ Layer Style. Numeric edits preview immediately and become one history entry on Apply. */
@Composable
fun LayerStyleDialog(
    layerId: LayerId,
    layerName: String,
    initial: LayerStyles?,
    onPreview: (LayerId, LayerStyles?) -> Boolean,
    onApply: (LayerId, LayerStyles?) -> Boolean,
    onCopy: (LayerStyles) -> Unit,
    onPaste: () -> LayerStyles?,
    patterns: List<PatternInfo> = emptyList(),
    patternThumbnail: (String, Int) -> app.croptop.core.engine.api.LayerThumb? = { _, _ -> null },
    onDismiss: () -> Unit,
) {
    var draft by remember(layerId) { mutableStateOf(initial ?: LayerStyles()) }
    var selected by remember(layerId) { mutableStateOf(StyleEffect.DROP_SHADOW) }
    var selectedPreset by remember(layerId) { mutableStateOf(presets.first()) }
    var failure by remember(layerId) { mutableStateOf<String?>(null) }

    fun update(styles: LayerStyles) {
        draft = styles
        failure = null
        if (!onPreview(layerId, styles)) failure = "Style preview could not be rendered"
    }

    fun apply() {
        if (draft.patternOverlay.enabled && draft.patternOverlay.patternId == null) {
            failure = "Choose a pattern for Pattern Overlay"
            return
        }
        if (onApply(layerId, draft)) {
            onDismiss()
        } else {
            failure = "Style could not be applied"
        }
    }

    val baseline = {
        onPreview(layerId, initial)
        onDismiss()
    }
    AeDialog(
        title = "Layer Style · $layerName",
        body = "Enable effects and adjust their appearance. Changes preview on the canvas and Apply records one history step.",
        onDismiss = baseline,
        onConfirm = ::apply,
        width = 520.dp,
        actions = {
            AeButton("Cancel", baseline)
            AeButton("Apply", ::apply, style = AeButtonStyle.Primary)
        },
    ) {
        Column(
            Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AeDropdown(StyleEffect.entries, selected, { selected = it }, { it.title })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeDropdown(presets, selectedPreset, { selectedPreset = it }, { it.name }, modifier = Modifier.weight(1f))
                AeButton("Load Preset", { update(selectedPreset.styles) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeButton("Copy Style", { onCopy(draft) })
                AeButton("Paste Style", { onPaste()?.let(::update) })
            }
            StyleEffect.entries.forEach { effect ->
                AeCheckbox(
                    checked = draft.isEnabled(effect),
                    onCheckedChange = { update(draft.withEnabled(effect, it)) },
                    label = effect.title,
                )
            }
            AeText("${selected.title} settings")
            draft.effectColor(selected)?.let { color ->
                AeColorPicker(color, { update(draft.withColor(selected, it)) }, modifier = Modifier.fillMaxWidth())
            }
            draft.effectBlendMode(selected)?.let { blend ->
                AeDropdown(
                    BlendMode.entries.filterNot { it.groupOnly },
                    blend,
                    { update(draft.withBlendMode(selected, it)) },
                    { it.displayName },
                )
            }
            draft.effectOpacity(selected)?.let { value ->
                AeSlider("Opacity", value * 100f, { update(draft.withOpacity(selected, it / 100f)) }, range = 0f..100f, unit = "%")
            }
            draft.effectSize(selected)?.let { (label, value, range) ->
                AeSlider(label, value, { update(draft.withSize(selected, it)) }, range = range, unit = "px")
            }
            draft.effectDistance(selected)?.let { value ->
                AeSlider("Distance", value, { update(draft.withDistance(selected, it)) }, range = 0f..250f, unit = "px")
            }
            draft.effectAngle(selected)?.let { value ->
                AeSlider("Angle", value, { update(draft.withAngle(selected, it)) }, range = 0f..360f, unit = "°")
            }
            draft.effectScale(selected)?.let { value ->
                AeSlider("Scale", value * 100f, { update(draft.withScale(it / 100f)) }, range = 10f..200f, unit = "%")
            }
            if (selected == StyleEffect.BEVEL) {
                AeDropdown(BevelStyle.entries, draft.bevel.style, {
                    update(draft.copy(bevel = draft.bevel.copy(style = it)))
                }, { it.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase) })
                AeCheckbox(draft.bevel.chiselHard, {
                    update(draft.copy(bevel = draft.bevel.copy(chiselHard = it)))
                }, "Chisel Hard")
                AeCheckbox(draft.bevel.up, {
                    update(draft.copy(bevel = draft.bevel.copy(up = it)))
                }, "Direction: Up")
                AeSlider("Depth", draft.bevel.depth * 100f, {
                    update(draft.copy(bevel = draft.bevel.copy(depth = it / 100f)))
                }, range = 1f..1000f, unit = "%")
                AeSlider("Soften", draft.bevel.soften, {
                    update(draft.copy(bevel = draft.bevel.copy(soften = it)))
                }, range = 0f..100f, unit = "px")
                AeSlider("Altitude", draft.bevel.altitude, {
                    update(draft.copy(bevel = draft.bevel.copy(altitude = it)))
                }, range = 0f..90f, unit = "°")
                AeText("Highlight")
                AeColorPicker(draft.bevel.highlightColor, {
                    update(draft.copy(bevel = draft.bevel.copy(highlightColor = it)))
                }, modifier = Modifier.fillMaxWidth())
                AeDropdown(BlendMode.entries.filterNot { it.groupOnly }, draft.bevel.highlightMode, {
                    update(draft.copy(bevel = draft.bevel.copy(highlightMode = it)))
                }, { it.displayName })
                AeSlider("Highlight Opacity", draft.bevel.highlightOpacity * 100f, {
                    update(draft.copy(bevel = draft.bevel.copy(highlightOpacity = it / 100f)))
                }, range = 0f..100f, unit = "%")
                AeText("Shadow")
                AeColorPicker(draft.bevel.shadowColor, {
                    update(draft.copy(bevel = draft.bevel.copy(shadowColor = it)))
                }, modifier = Modifier.fillMaxWidth())
                AeDropdown(BlendMode.entries.filterNot { it.groupOnly }, draft.bevel.shadowMode, {
                    update(draft.copy(bevel = draft.bevel.copy(shadowMode = it)))
                }, { it.displayName })
                AeSlider("Shadow Opacity", draft.bevel.shadowOpacity * 100f, {
                    update(draft.copy(bevel = draft.bevel.copy(shadowOpacity = it / 100f)))
                }, range = 0f..100f, unit = "%")
            }
            if (selected == StyleEffect.DROP_SHADOW || selected == StyleEffect.INNER_SHADOW) {
                val shadow = if (selected == StyleEffect.DROP_SHADOW) draft.dropShadow else draft.innerShadow
                AeSlider("${if (selected == StyleEffect.DROP_SHADOW) "Spread" else "Choke"}", shadow.spread * 100f, {
                    update(draft.withSpread(selected, it / 100f))
                }, range = 0f..100f, unit = "%")
            }
            if (selected == StyleEffect.OUTER_GLOW || selected == StyleEffect.INNER_GLOW) {
                val glow = if (selected == StyleEffect.OUTER_GLOW) draft.outerGlow else draft.innerGlow
                AeSlider("${if (selected == StyleEffect.OUTER_GLOW) "Spread" else "Choke"}", glow.spread * 100f, {
                    update(draft.withGlowSpread(selected, it / 100f))
                }, range = 0f..100f, unit = "%")
                if (selected == StyleEffect.INNER_GLOW) {
                    AeCheckbox(glow.fromCenter, {
                        update(draft.copy(innerGlow = draft.innerGlow.copy(fromCenter = it)))
                    }, "Source: Center")
                }
            }
            if (selected == StyleEffect.SATIN) {
                AeCheckbox(draft.satin.invert, {
                    update(draft.copy(satin = draft.satin.copy(invert = it)))
                }, "Invert")
            }
            if (selected == StyleEffect.GRADIENT_OVERLAY) {
                val gradient = ColorGradient(draft.gradientOverlay.stops.map { GradientStop(it.position, it.color) })
                AeGradientEditor(gradient, { edited ->
                    update(
                        draft.copy(
                            gradientOverlay = draft.gradientOverlay.copy(
                                stops = edited.stops.map { app.croptop.core.model.StyleGradientStop(it.position, it.color) },
                            ),
                        ),
                    )
                }, modifier = Modifier.fillMaxWidth())
                AeCheckbox(draft.gradientOverlay.reverse, {
                    update(draft.copy(gradientOverlay = draft.gradientOverlay.copy(reverse = it)))
                }, "Reverse")
            }
            if (selected == StyleEffect.PATTERN_OVERLAY) {
                PatternPicker(patterns, draft.patternOverlay.patternId, { id ->
                    update(draft.copy(patternOverlay = draft.patternOverlay.copy(patternId = id)))
                }, patternThumbnail)
                AeSlider("Scale", draft.patternOverlay.scale * 100f, {
                    update(draft.copy(patternOverlay = draft.patternOverlay.copy(scale = it / 100f)))
                }, range = 1f..1000f, unit = "%")
            }
            if (selected == StyleEffect.STROKE) {
                AeDropdown(StrokePosition.entries, draft.stroke.position, {
                    update(draft.copy(stroke = draft.stroke.copy(position = it)))
                }, { it.name.lowercase().replaceFirstChar(Char::uppercase) })
            }
            failure?.let { AeText(it, color = app.croptop.core.designsystem.theme.Ae.colors.danger) }
        }
    }
}

private fun LayerStyles.isEnabled(effect: StyleEffect): Boolean = when (effect) {
    StyleEffect.DROP_SHADOW -> dropShadow.enabled
    StyleEffect.INNER_SHADOW -> innerShadow.enabled
    StyleEffect.OUTER_GLOW -> outerGlow.enabled
    StyleEffect.INNER_GLOW -> innerGlow.enabled
    StyleEffect.BEVEL -> bevel.enabled
    StyleEffect.SATIN -> satin.enabled
    StyleEffect.COLOR_OVERLAY -> colorOverlay.enabled
    StyleEffect.GRADIENT_OVERLAY -> gradientOverlay.enabled
    StyleEffect.PATTERN_OVERLAY -> patternOverlay.enabled
    StyleEffect.STROKE -> stroke.enabled
}

private fun LayerStyles.withEnabled(effect: StyleEffect, enabled: Boolean): LayerStyles = when (effect) {
    StyleEffect.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(enabled = enabled))
    StyleEffect.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(enabled = enabled))
    StyleEffect.OUTER_GLOW -> copy(outerGlow = outerGlow.copy(enabled = enabled))
    StyleEffect.INNER_GLOW -> copy(innerGlow = innerGlow.copy(enabled = enabled))
    StyleEffect.BEVEL -> copy(bevel = bevel.copy(enabled = enabled))
    StyleEffect.SATIN -> copy(satin = satin.copy(enabled = enabled))
    StyleEffect.COLOR_OVERLAY -> copy(colorOverlay = colorOverlay.copy(enabled = enabled))
    StyleEffect.GRADIENT_OVERLAY -> copy(gradientOverlay = gradientOverlay.copy(enabled = enabled))
    StyleEffect.PATTERN_OVERLAY -> copy(patternOverlay = patternOverlay.copy(enabled = enabled))
    StyleEffect.STROKE -> copy(stroke = stroke.copy(enabled = enabled))
}

private fun LayerStyles.effectOpacity(effect: StyleEffect): Float? = when (effect) {
    StyleEffect.DROP_SHADOW -> dropShadow.opacity
    StyleEffect.INNER_SHADOW -> innerShadow.opacity
    StyleEffect.OUTER_GLOW -> outerGlow.opacity
    StyleEffect.INNER_GLOW -> innerGlow.opacity
    StyleEffect.BEVEL -> null
    StyleEffect.SATIN -> satin.opacity
    StyleEffect.COLOR_OVERLAY -> colorOverlay.opacity
    StyleEffect.GRADIENT_OVERLAY -> gradientOverlay.opacity
    StyleEffect.PATTERN_OVERLAY -> patternOverlay.opacity
    StyleEffect.STROKE -> stroke.opacity
}

private fun LayerStyles.withOpacity(effect: StyleEffect, value: Float): LayerStyles = when (effect) {
    StyleEffect.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(opacity = value))
    StyleEffect.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(opacity = value))
    StyleEffect.OUTER_GLOW -> copy(outerGlow = outerGlow.copy(opacity = value))
    StyleEffect.INNER_GLOW -> copy(innerGlow = innerGlow.copy(opacity = value))
    StyleEffect.BEVEL -> this
    StyleEffect.SATIN -> copy(satin = satin.copy(opacity = value))
    StyleEffect.COLOR_OVERLAY -> copy(colorOverlay = colorOverlay.copy(opacity = value))
    StyleEffect.GRADIENT_OVERLAY -> copy(gradientOverlay = gradientOverlay.copy(opacity = value))
    StyleEffect.PATTERN_OVERLAY -> copy(patternOverlay = patternOverlay.copy(opacity = value))
    StyleEffect.STROKE -> copy(stroke = stroke.copy(opacity = value))
}

private fun LayerStyles.effectSize(effect: StyleEffect): Triple<String, Float, ClosedFloatingPointRange<Float>>? = when (effect) {
    StyleEffect.DROP_SHADOW -> Triple("Size", dropShadow.size, 0f..250f)
    StyleEffect.INNER_SHADOW -> Triple("Size", innerShadow.size, 0f..250f)
    StyleEffect.OUTER_GLOW -> Triple("Size", outerGlow.size, 0f..250f)
    StyleEffect.INNER_GLOW -> Triple("Size", innerGlow.size, 0f..250f)
    StyleEffect.BEVEL -> Triple("Size", bevel.size, 0f..250f)
    StyleEffect.SATIN -> Triple("Size", satin.size, 0f..250f)
    StyleEffect.COLOR_OVERLAY, StyleEffect.GRADIENT_OVERLAY, StyleEffect.PATTERN_OVERLAY -> null
    StyleEffect.STROKE -> Triple("Size", stroke.size, 0f..250f)
}

private fun LayerStyles.withSize(effect: StyleEffect, value: Float): LayerStyles = when (effect) {
    StyleEffect.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(size = value))
    StyleEffect.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(size = value))
    StyleEffect.OUTER_GLOW -> copy(outerGlow = outerGlow.copy(size = value))
    StyleEffect.INNER_GLOW -> copy(innerGlow = innerGlow.copy(size = value))
    StyleEffect.BEVEL -> copy(bevel = bevel.copy(size = value))
    StyleEffect.SATIN -> copy(satin = satin.copy(size = value))
    StyleEffect.COLOR_OVERLAY, StyleEffect.GRADIENT_OVERLAY, StyleEffect.PATTERN_OVERLAY -> this
    StyleEffect.STROKE -> copy(stroke = stroke.copy(size = value))
}

private fun LayerStyles.effectDistance(effect: StyleEffect): Float? = when (effect) {
    StyleEffect.DROP_SHADOW -> dropShadow.distance
    StyleEffect.INNER_SHADOW -> innerShadow.distance
    StyleEffect.SATIN -> satin.distance
    else -> null
}

private fun LayerStyles.withDistance(effect: StyleEffect, value: Float): LayerStyles = when (effect) {
    StyleEffect.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(distance = value))
    StyleEffect.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(distance = value))
    StyleEffect.SATIN -> copy(satin = satin.copy(distance = value))
    else -> this
}

private fun LayerStyles.withSpread(effect: StyleEffect, value: Float): LayerStyles = when (effect) {
    StyleEffect.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(spread = value))
    StyleEffect.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(spread = value))
    else -> this
}

private fun LayerStyles.withGlowSpread(effect: StyleEffect, value: Float): LayerStyles = when (effect) {
    StyleEffect.OUTER_GLOW -> copy(outerGlow = outerGlow.copy(spread = value))
    StyleEffect.INNER_GLOW -> copy(innerGlow = innerGlow.copy(spread = value))
    else -> this
}

private fun LayerStyles.effectAngle(effect: StyleEffect): Float? = when (effect) {
    StyleEffect.DROP_SHADOW -> dropShadow.angle
    StyleEffect.INNER_SHADOW -> innerShadow.angle
    StyleEffect.SATIN -> satin.angle
    StyleEffect.GRADIENT_OVERLAY -> gradientOverlay.angle
    else -> null
}

private fun LayerStyles.withAngle(effect: StyleEffect, value: Float): LayerStyles = when (effect) {
    StyleEffect.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(angle = value))
    StyleEffect.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(angle = value))
    StyleEffect.SATIN -> copy(satin = satin.copy(angle = value))
    StyleEffect.GRADIENT_OVERLAY -> copy(gradientOverlay = gradientOverlay.copy(angle = value))
    else -> this
}

private fun LayerStyles.effectScale(effect: StyleEffect): Float? =
    if (effect == StyleEffect.GRADIENT_OVERLAY) gradientOverlay.scale else null

private fun LayerStyles.withScale(value: Float): LayerStyles = copy(gradientOverlay = gradientOverlay.copy(scale = value))

private fun LayerStyles.effectColor(effect: StyleEffect): RgbaColor? = when (effect) {
    StyleEffect.DROP_SHADOW -> dropShadow.color
    StyleEffect.INNER_SHADOW -> innerShadow.color
    StyleEffect.OUTER_GLOW -> outerGlow.color
    StyleEffect.INNER_GLOW -> innerGlow.color
    StyleEffect.BEVEL -> null
    StyleEffect.SATIN -> satin.color
    StyleEffect.COLOR_OVERLAY -> colorOverlay.color
    StyleEffect.GRADIENT_OVERLAY, StyleEffect.PATTERN_OVERLAY -> null
    StyleEffect.STROKE -> stroke.color
}

private fun LayerStyles.withColor(effect: StyleEffect, value: RgbaColor): LayerStyles = when (effect) {
    StyleEffect.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(color = value))
    StyleEffect.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(color = value))
    StyleEffect.OUTER_GLOW -> copy(outerGlow = outerGlow.copy(color = value))
    StyleEffect.INNER_GLOW -> copy(innerGlow = innerGlow.copy(color = value))
    StyleEffect.BEVEL -> copy(bevel = bevel.copy(highlightColor = value))
    StyleEffect.SATIN -> copy(satin = satin.copy(color = value))
    StyleEffect.COLOR_OVERLAY -> copy(colorOverlay = colorOverlay.copy(color = value))
    StyleEffect.GRADIENT_OVERLAY, StyleEffect.PATTERN_OVERLAY -> this
    StyleEffect.STROKE -> copy(stroke = stroke.copy(color = value))
}

private fun LayerStyles.effectBlendMode(effect: StyleEffect): BlendMode? = when (effect) {
    StyleEffect.DROP_SHADOW -> dropShadow.blendMode
    StyleEffect.INNER_SHADOW -> innerShadow.blendMode
    StyleEffect.OUTER_GLOW -> outerGlow.blendMode
    StyleEffect.INNER_GLOW -> innerGlow.blendMode
    StyleEffect.BEVEL -> null
    StyleEffect.SATIN -> satin.blendMode
    StyleEffect.COLOR_OVERLAY -> colorOverlay.blendMode
    StyleEffect.GRADIENT_OVERLAY -> gradientOverlay.blendMode
    StyleEffect.PATTERN_OVERLAY -> patternOverlay.blendMode
    StyleEffect.STROKE -> stroke.blendMode
}

private fun LayerStyles.withBlendMode(effect: StyleEffect, value: BlendMode): LayerStyles = when (effect) {
    StyleEffect.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(blendMode = value))
    StyleEffect.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(blendMode = value))
    StyleEffect.OUTER_GLOW -> copy(outerGlow = outerGlow.copy(blendMode = value))
    StyleEffect.INNER_GLOW -> copy(innerGlow = innerGlow.copy(blendMode = value))
    StyleEffect.BEVEL -> this
    StyleEffect.SATIN -> copy(satin = satin.copy(blendMode = value))
    StyleEffect.COLOR_OVERLAY -> copy(colorOverlay = colorOverlay.copy(blendMode = value))
    StyleEffect.GRADIENT_OVERLAY -> copy(gradientOverlay = gradientOverlay.copy(blendMode = value))
    StyleEffect.PATTERN_OVERLAY -> copy(patternOverlay = patternOverlay.copy(blendMode = value))
    StyleEffect.STROKE -> copy(stroke = stroke.copy(blendMode = value))
}
