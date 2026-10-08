package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DepthRangeSpec
import app.croptop.core.engine.api.EngineJob
import app.croptop.core.engine.api.LensBlurDepthSource
import app.croptop.core.engine.api.LensBlurSpec
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerNode
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface DepthChoice {
    data object None : DepthChoice
    data class Pixels(val layer: LayerNode) : DepthChoice
    data class Mask(val layer: LayerNode) : DepthChoice
}

/** Filter ▸ Lens Blur. Uses the engine job so the native render never blocks composition. */
@Composable
fun LensBlurDialog(
    target: LayerId,
    layers: List<LayerNode>,
    onApply: (LayerId, LensBlurSpec, LensBlurDepthSource?) -> EngineJob,
    onDepthRange: (LayerId, LensBlurDepthSource, DepthRangeSpec) -> CommandResult,
    onDismiss: () -> Unit,
    onEstimateDepth: (() -> Boolean)? = null,
) {
    var radius by remember { mutableStateOf(15f) }
    var focus by remember { mutableStateOf(0f) }
    var invert by remember { mutableStateOf(false) }
    var blades by remember { mutableStateOf(6f) }
    var rotation by remember { mutableStateOf(0f) }
    var curvature by remember { mutableStateOf(0f) }
    var specular by remember { mutableStateOf(0f) }
    var threshold by remember { mutableStateOf(0.9f) }
    var noise by remember { mutableStateOf(0f) }
    var monochromatic by remember { mutableStateOf(true) }
    var depthChoice by remember { mutableStateOf<DepthChoice>(DepthChoice.None) }
    var near by remember { mutableStateOf(0.4f) }
    var far by remember { mutableStateOf(0.6f) }
    var feather by remember { mutableStateOf(0f) }
    var applying by remember { mutableStateOf(false) }
    var depthMaskApplying by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<EngineJob?>(null) }
    val busy = applying || depthMaskApplying
    val renderProgress = activeJob?.progress?.collectAsState()?.value
        ?.takeIf(Float::isFinite)
        ?.coerceIn(0f, 1f)
    val latestJob by rememberUpdatedState(activeJob)
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { latestJob?.cancel() } }

    val depthOptions = buildList {
        add(DepthChoice.None)
        layers.filter { it.kind == LayerKind.PIXEL }.forEach { layer ->
            add(DepthChoice.Pixels(layer))
            if (layer.mask != null) add(DepthChoice.Mask(layer))
        }
    }
    // After "Estimate depth" the new "Depth map" layer shows up in [layers]; pick it as the depth source.
    var depthKnownIds by remember { mutableStateOf<Set<LayerId>?>(null) }
    LaunchedEffect(layers) {
        val known = depthKnownIds ?: return@LaunchedEffect
        layers.firstOrNull { it.kind == LayerKind.PIXEL && it.name == "Depth map" && it.id !in known }?.let {
            depthChoice = DepthChoice.Pixels(it)
            depthKnownIds = null
        }
    }
    fun source(): LensBlurDepthSource? = when (val choice = depthChoice) {
        DepthChoice.None -> null
        is DepthChoice.Pixels -> LensBlurDepthSource.LayerPixels(choice.layer.id)
        is DepthChoice.Mask -> LensBlurDepthSource.LayerMask(choice.layer.id)
    }
    fun sourceForRange(): LensBlurDepthSource? = source()
    val runBlur = {
        if (!busy) {
            applying = true
            cancelRequested = false
            applyError = null
            val spec = LensBlurSpec(
                radius = radius,
                focalDistance = focus,
                invertDepth = invert,
                irisBlades = blades.toInt(),
                irisRotationDegrees = rotation,
                irisCurvature = curvature,
                specularBrightness = specular,
                specularThreshold = threshold,
                noise = noise,
                monochromaticNoise = monochromatic,
            )
            scope.launch {
                try {
                    withFrameNanos { }
                    val job = withContext(Dispatchers.Default) { onApply(target, spec, source()) }
                    activeJob = job
                    if (cancelRequested) job.cancel()
                    val result = try {
                        withContext(Dispatchers.Default) { job.await() }
                    } finally {
                        activeJob = null
                    }
                    when (result) {
                        is CommandResult.Accepted -> onDismiss()

                        is CommandResult.Rejected -> if (cancelRequested && result.code == RejectionCode.CANCELLED) {
                            onDismiss()
                        } else {
                            applyError = result.message
                            applying = false
                            cancelRequested = false
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    applyError = failure.message?.takeIf(String::isNotBlank) ?: "Lens Blur could not be applied"
                    applying = false
                    cancelRequested = false
                    activeJob = null
                }
            }
        }
    }
    val cancelOrDismiss = {
        if (applying) {
            if (!cancelRequested) {
                cancelRequested = true
                activeJob?.cancel()
            }
        } else if (!depthMaskApplying) {
            onDismiss()
        }
    }
    AeDialog(
        title = "Lens Blur",
        body = "Applies depth-aware optical blur to the selected pixel layer as one undoable edit.",
        onDismiss = cancelOrDismiss,
        onConfirm = runBlur,
        width = 500.dp,
        actions = {
            AeButton(
                when {
                    cancelRequested -> "Canceling…"
                    applying -> "Cancel render"
                    depthMaskApplying -> "Creating mask…"
                    else -> "Close"
                },
                cancelOrDismiss,
                enabled = !cancelRequested && !depthMaskApplying,
            )
            AeButton(if (applying) "Rendering…" else "Apply Lens Blur", runBlur, style = AeButtonStyle.Primary, enabled = !busy)
        },
    ) {
        // Leave room for the title, description, and pinned footer on an 800 dp tablet screen.
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (applying) {
                val progressSemantics = Modifier.semantics {
                    progressBarRangeInfo = renderProgress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                        ?: ProgressBarRangeInfo.Indeterminate
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (renderProgress == null) {
                        CircularProgressIndicator(progressSemantics, strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(progress = { renderProgress }, modifier = progressSemantics, strokeWidth = 2.dp)
                    }
                    AeText(
                        when {
                            cancelRequested -> "Canceling after the current native render…"
                            renderProgress != null -> "Rendering… ${(renderProgress * 100f).roundToInt()}%"
                            else -> "Rendering… progress is indeterminate"
                        },
                    )
                }
            }
            if (depthMaskApplying) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, strokeWidth = 2.dp)
                    AeText("Creating Depth Range Mask… keep this workspace open")
                }
            }
            applyError?.let { AeText(it, color = Ae.colors.danger) }
            Numeric("Blur radius", radius, { radius = it }, 0f..100f, "px", default = 15f, enabled = !busy)
            Numeric("Focal distance", focus, { focus = it }, 0f..1f, decimals = 3, default = 0f, enabled = !busy)
            AeCheckbox(invert, { invert = it }, "Invert depth", enabled = !busy)
            AeText("Depth source", color = Ae.colors.textSecondary)
            AeDropdown(depthOptions, depthChoice, { depthChoice = it }, { choice ->
                when (choice) {
                    DepthChoice.None -> "No depth map (uniform blur)"
                    is DepthChoice.Pixels -> "${choice.layer.name} · pixels"
                    is DepthChoice.Mask -> "${choice.layer.name} · layer mask"
                }
            }, enabled = !busy)
            if (onEstimateDepth != null) {
                AeButton("Estimate depth from the picture (AI)", {
                    depthKnownIds = layers.map { it.id }.toSet()
                    applyError = null
                    if (!onEstimateDepth()) depthKnownIds = null
                }, enabled = !busy)
            }
            AeText("Uses the selected layer's stored pixels or mask values directly, regardless of visibility or mask-enabled state.")
            AeText("Bokeh", color = Ae.colors.textSecondary)
            Numeric("Iris blades (0 = round)", blades, { blades = it }, 0f..8f, decimals = 0, default = 6f, enabled = !busy)
            Numeric("Iris rotation", rotation, { rotation = it }, -360f..360f, "°", default = 0f, enabled = !busy)
            Numeric("Iris curvature", curvature, { curvature = it }, 0f..1f, decimals = 2, default = 0f, enabled = !busy)
            Numeric("Specular brightness", specular, { specular = it }, 0f..1f, decimals = 2, default = 0f, enabled = !busy)
            Numeric("Specular threshold", threshold, { threshold = it }, 0f..1f, decimals = 2, default = 0.9f, enabled = !busy)
            Numeric("Noise", noise, { noise = it }, 0f..1f, decimals = 2, default = 0f, enabled = !busy)
            AeCheckbox(monochromatic, { monochromatic = it }, "Monochromatic noise", enabled = !busy)
            AeText("Depth Range Mask", color = Ae.colors.textSecondary)
            AeText("Creates an A16 mask on the selected layer from the chosen depth source.")
            Numeric("Near", near, { near = it.coerceAtMost(far) }, 0f..1f, decimals = 3, default = 0.4f, enabled = !busy)
            Numeric("Far", far, { far = it.coerceAtLeast(near) }, 0f..1f, decimals = 3, default = 0.6f, enabled = !busy)
            Numeric("Feather", feather, { feather = it }, 0f..1f, decimals = 3, default = 0f, enabled = !busy)
            AeButton(if (depthMaskApplying) "Creating mask…" else "Create Depth Range Mask", {
                val selectedSource = sourceForRange()
                if (selectedSource == null) {
                    applyError = "Choose a depth layer or layer mask first."
                } else if (!depthMaskApplying) {
                    depthMaskApplying = true
                    val range = DepthRangeSpec(near, far, feather)
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.Default) { onDepthRange(target, selectedSource, range) }
                            when (result) {
                                is CommandResult.Accepted -> applyError = null
                                is CommandResult.Rejected -> applyError = result.message
                            }
                        } catch (failure: Exception) {
                            applyError = failure.message?.takeIf(String::isNotBlank) ?: "Depth Range Mask could not be created"
                        } finally {
                            depthMaskApplying = false
                        }
                    }
                }
            }, enabled = !applying && !depthMaskApplying && depthChoice != DepthChoice.None)
        }
    }
}

@Composable
private fun Numeric(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    unit: String? = null,
    enabled: Boolean = true,
    decimals: Int = 1,
    default: Float = range.start,
) {
    // A bar with the number on it: drag to scrub, tap to type, double-tap to reset.
    AeBarSlider(
        label,
        value,
        onValue,
        range = range,
        defaultValue = default,
        step = if (decimals > 0) 10f.pow(-decimals) else 1f,
        decimals = decimals,
        unit = unit,
        enabled = enabled,
    )
}
