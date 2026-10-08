package app.croptop.feature.editor.properties

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeColorBalanceEditor
import app.croptop.core.designsystem.component.AeColorPicker
import app.croptop.core.designsystem.component.AeColorWell
import app.croptop.core.designsystem.component.AeCurveEditor
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeGradientEditor
import app.croptop.core.designsystem.component.AeHueSaturationEditor
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeLevelsEditor
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.ChannelCurves
import app.croptop.core.designsystem.component.LevelsPickTarget
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.AdjustmentSpec
import app.croptop.core.model.ChoiceParam
import app.croptop.core.model.ColorParam
import app.croptop.core.model.CurveChannels as PersistedCurveChannels
import app.croptop.core.model.EditorParam
import app.croptop.core.model.HueChannel
import app.croptop.core.model.NumberParam
import app.croptop.core.model.ParamSpec
import app.croptop.core.model.ParamValue
import app.croptop.core.model.ToggleParam
import app.croptop.core.model.Track
import app.croptop.core.model.defaultValue

/**
 * Properties panel content generated from an [AdjustmentSpec]: a slider per number, a
 * dropdown per choice, checkboxes, color wells and the dedicated editors; presets and reset.
 * [values] are normalized against the spec before drawing, so partial or stale maps are safe.
 */
@Composable
fun AdjustmentProperties(
    spec: AdjustmentSpec,
    values: Map<String, ParamValue>,
    onChange: (Map<String, ParamValue>, String) -> Unit,
    modifier: Modifier = Modifier,
    onLevelsPick: ((LevelsPickTarget) -> Unit)? = null,
    onCanvasTarget: (() -> Unit)? = null,
    /** The document's pixel layers a "layer" choice (such as Displace's map) may pick from, as `layer:<id>` options. */
    layerChoices: List<ChoiceParam.Option> = emptyList(),
    targetedHueChannel: HueChannel? = null,
    onActionCompleted: (String) -> Unit = {},
) {
    val v = spec.normalize(values)
    fun set(key: String, value: ParamValue, action: String) = onChange(v + (key to value), action)
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeText(spec.name, style = Ae.type.title, modifier = Modifier.weight(1f))
            AeIconButton(AeIcons.Undo, "Reset ${spec.name}", {
                onChange(spec.defaults(), "Reset ${spec.name}")
            }, enabled = v != spec.defaults())
        }
        if (spec.presets.isNotEmpty()) {
            // "Custom" appears only while the values match neither the defaults nor a preset.
            val matching = spec.presets.firstOrNull { spec.normalize(spec.defaults() + it.values) == v }?.name
            val current = matching ?: if (v == spec.defaults()) DEFAULT else CUSTOM
            val options = listOf(DEFAULT) + spec.presets.map { it.name } + listOfNotNull(CUSTOM.takeIf { current == CUSTOM })
            AeDropdown(options, current, { name ->
                val preset = spec.presets.firstOrNull { it.name == name }
                when {
                    name == DEFAULT -> {
                        onChange(spec.defaults(), "Reset ${spec.name} to defaults")
                    }

                    preset != null -> {
                        onChange(spec.normalize(spec.defaults() + preset.values), "Apply ${preset.name} ${spec.name} preset")
                    }
                }
            }, { it }, Modifier.fillMaxWidth())
        }
        if (spec.params.isEmpty()) {
            AeText("${spec.name} has no settings.", style = Ae.type.caption, color = Ae.colors.textTertiary)
        }
        val targetParam = spec.params.filterIsInstance<EditorParam>().firstOrNull {
            it.editor == EditorParam.Editor.CURVE || it.editor == EditorParam.Editor.HUE_SATURATION
        }
        if (targetParam != null && onCanvasTarget != null) {
            AeButton(
                if (targetParam.editor == EditorParam.Editor.CURVE) "Target tone on canvas" else "Target color on canvas",
                onCanvasTarget,
                Modifier.fillMaxWidth(),
                icon = AeIcons.Hand,
            )
        }
        spec.visible(v).forEach { p ->
            Param(
                p,
                v[p.key] ?: p.defaultValue(),
                { value, action -> set(p.key, value, action) },
                onLevelsPick,
                targetedHueChannel,
                onActionCompleted,
                layerChoices,
            )
        }
    }
}

private const val DEFAULT = "Default"
private const val CUSTOM = "Custom"

private fun PersistedCurveChannels.toEditorState(): ChannelCurves = ChannelCurves(rgb, red, green, blue)

private fun ChannelCurves.toPersisted(): PersistedCurveChannels = PersistedCurveChannels(rgb, red, green, blue)

@Composable
private fun Param(
    p: ParamSpec,
    value: ParamValue,
    set: (ParamValue, String) -> Unit,
    onLevelsPick: ((LevelsPickTarget) -> Unit)?,
    targetedHueChannel: HueChannel?,
    onActionCompleted: (String) -> Unit,
    layerChoices: List<ChoiceParam.Option> = emptyList(),
) {
    when (p) {
        is NumberParam -> AeSlider(
            p.label,
            (value as ParamValue.Num).v,
            { set(ParamValue.Num(it), "Set ${p.label.lowercase()}") },
            range = p.min..p.max,
            defaultValue = p.default,
            step = p.step,
            decimals = p.decimals,
            unit = p.unit,
            trackBrush = p.track.brush(),
        )

        is ChoiceParam -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeText(p.label, style = Ae.type.caption, color = Ae.colors.textSecondary, modifier = Modifier.width(84.dp))
            val options = if (p.source == ChoiceParam.Source.PIXEL_LAYERS) p.options + layerChoices else p.options
            val chosen = (value as ParamValue.Choice).v
            // A layer that is gone (deleted since) shows as the first option rather than breaking the control.
            val sel = options.firstOrNull { it.value == chosen } ?: options.first()
            AeDropdown(options, sel, {
                set(ParamValue.Choice(it.value), "Set ${p.label.lowercase()} to ${it.label}")
            }, { it.label }, Modifier.weight(1f))
        }

        is ToggleParam -> AeCheckbox((value as ParamValue.Bool).v, {
            set(ParamValue.Bool(it), "${if (it) "Enable" else "Disable"} ${p.label.lowercase()}")
        }, p.label)

        is ColorParam -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeText(p.label, style = Ae.type.caption, color = Ae.colors.textSecondary, modifier = Modifier.width(84.dp))
            var open by remember(p.key) { mutableStateOf(false) }
            val color = (value as ParamValue.Color).v
            var previous by remember(p.key) { mutableStateOf(color) }
            Box {
                AeColorWell(color, p.label, onClick = {
                    previous = color
                    open = true
                    onActionCompleted("Open ${p.label.lowercase()} color picker")
                })
                if (open) {
                    Popup(
                        offset = IntOffset(0, 36),
                        onDismissRequest = {
                            open = false
                            onActionCompleted("Close ${p.label.lowercase()} color picker")
                        },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Box(
                            Modifier.background(Ae.colors.surface2, Ae.shapes.popover)
                                .border(1.dp, Ae.colors.lineStrong, Ae.shapes.popover)
                                .padding(12.dp)
                                .focusable(),
                        ) {
                            AeColorPicker(
                                color = color,
                                onColorChange = { set(ParamValue.Color(it), "Set ${p.label.lowercase()} color") },
                                previous = previous,
                                onEditComplete = {},
                            )
                        }
                    }
                }
            }
        }

        is EditorParam -> when (value) {
            is ParamValue.CurveValue -> AeCurveEditor(value.v, {
                set(ParamValue.CurveValue(it), "Edit ${p.label.lowercase()}")
            }, Modifier.fillMaxWidth())

            is ParamValue.CurveChannelsValue -> AeCurveEditor(
                value.v.toEditorState(),
                { set(ParamValue.CurveChannelsValue(it.toPersisted()), "Edit ${p.label.lowercase()}") },
                Modifier.fillMaxWidth(),
            )

            is ParamValue.LevelsValue -> AeLevelsEditor(
                value.v,
                { set(ParamValue.LevelsValue(it), "Edit ${p.label.lowercase()}") },
                Modifier.fillMaxWidth(),
                onPickSample = onLevelsPick,
            )

            is ParamValue.HueSatValue -> AeHueSaturationEditor(
                value.v,
                { set(ParamValue.HueSatValue(it), "Edit ${p.label.lowercase()}") },
                targetedChannel = targetedHueChannel,
            )

            is ParamValue.BalanceValue -> AeColorBalanceEditor(value.v, { set(ParamValue.BalanceValue(it), "Edit ${p.label.lowercase()}") })

            is ParamValue.GradientValue -> AeGradientEditor(value.v, {
                set(ParamValue.GradientValue(it), "Edit ${p.label.lowercase()}")
            }, Modifier.fillMaxWidth())

            else -> Spacer(Modifier.height(0.dp))
        }
    }
}

private fun Track.brush(): Brush? = when (this) {
    Track.PLAIN -> null
    Track.HUE -> Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })
    Track.CYAN_RED -> Brush.horizontalGradient(listOf(Color(0xFF00B7C7), Color(0xFFE5484D)))
    Track.MAGENTA_GREEN -> Brush.horizontalGradient(listOf(Color(0xFFD23CC2), Color(0xFF30A46C)))
    Track.YELLOW_BLUE -> Brush.horizontalGradient(listOf(Color(0xFFF5C518), Color(0xFF3E7BFA)))
    Track.TEMPERATURE -> Brush.horizontalGradient(listOf(Color(0xFF3E7BFA), Color(0xFFF5F5F5), Color(0xFFF5A623)))
}
