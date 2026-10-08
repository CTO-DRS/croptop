package app.croptop.feature.editor.dialogs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeColorPicker
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentRegionPreview
import app.croptop.core.engine.api.EngineJob
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.AdjustmentSpec
import app.croptop.core.model.BlendMode
import app.croptop.core.model.FillSource
import app.croptop.core.model.ParamValue
import app.croptop.core.model.RgbaColor
import app.croptop.feature.editor.properties.AdjustmentProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edit ▸ Fill contents. */
enum class FillContents(val label: String) {
    FOREGROUND("Foreground Color"),
    BACKGROUND("Background Color"),
    COLOR("Color…"),
    CONTENT_AWARE("Content-Aware"),
    BLACK("Black"),
    GRAY("50% Gray"),
    WHITE("White"),
}

private val FillContents.actionLabel: String
    get() = when (this) {
        FillContents.FOREGROUND -> "Fill with foreground color"
        FillContents.BACKGROUND -> "Fill with background color"
        FillContents.COLOR -> "Fill with custom color"
        FillContents.CONTENT_AWARE -> "Content-Aware Fill"
        FillContents.BLACK -> "Fill with black"
        FillContents.GRAY -> "Fill with 50% gray"
        FillContents.WHITE -> "Fill with white"
    }

/**
 * Edit ▸ Fill (Shift+F5). [onApply] runs away from the UI thread and returns an [EngineJob]. The
 * dialog displays only engine-reported progress, requests cancellation on Cancel/Back, and remains
 * open until [EngineJob.await] acknowledges the terminal result.
 */
@Composable
fun FillDialog(
    foreground: RgbaColor,
    background: RgbaColor,
    hasSelection: Boolean,
    onApply: (FillSource, Float, BlendMode, Boolean, String) -> EngineJob,
    onDismiss: () -> Unit,
    onCancel: () -> Unit = {},
    /** Shift+Backspace opens with Content-Aware chosen when there is a selection (Photoshop). */
    initial: FillContents = FillContents.FOREGROUND,
    /** Loads the active layer's thumbnail and the hole for the sampling-area editor; null hides the editor. */
    contentAwareContext: (suspend () -> ContentAwareContext?)? = null,
    /** Id for the layer created by "Output to new layer". */
    newLayerId: () -> app.croptop.core.model.LayerId = { app.croptop.core.model.LayerId(kotlin.random.Random.nextLong(1L shl 40, Long.MAX_VALUE)) },
) {
    var contents by remember { mutableStateOf(if (initial == FillContents.CONTENT_AWARE && !hasSelection) FillContents.FOREGROUND else initial) }
    var color by remember { mutableStateOf(foreground) }
    var mode by remember { mutableStateOf(BlendMode.NORMAL) }
    var opacity by remember { mutableStateOf(100f) }
    var preserve by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var activeEngineJob by remember { mutableStateOf<EngineJob?>(null) }
    var rotation by remember { mutableStateOf(app.croptop.core.model.ContentAwareRotation.NONE) }
    var mirror by remember { mutableStateOf(false) }
    var colorAdaptation by remember { mutableStateOf(true) }
    var onlyPainted by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf(emptyList<app.croptop.core.model.SamplingStroke>()) }
    var toNewLayer by remember { mutableStateOf(true) }
    var showSampling by remember { mutableStateOf(false) }
    var caContext by remember { mutableStateOf<ContentAwareContext?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(showSampling) {
        if (showSampling && caContext == null) caContext = contentAwareContext?.invoke()
    }
    val latestEngineJob by rememberUpdatedState(activeEngineJob)
    DisposableEffect(Unit) {
        onDispose { latestEngineJob?.cancel() }
    }
    val options = FillContents.entries.filter { it != FillContents.CONTENT_AWARE || hasSelection }
    fun source(): FillSource = when (contents) {
        FillContents.FOREGROUND -> FillSource.Solid(foreground)

        FillContents.BACKGROUND -> FillSource.Solid(background)

        FillContents.COLOR -> FillSource.Solid(color)

        FillContents.CONTENT_AWARE -> FillSource.ContentAware(
            app.croptop.core.model.ContentAwareOptions(
                rotation = rotation,
                mirror = mirror,
                colorAdaptation = colorAdaptation,
                samplingOnlyPainted = onlyPainted && strokes.any { it.add },
                strokes = strokes,
                newLayerId = if (toNewLayer) newLayerId() else null,
            ),
        )

        FillContents.BLACK -> FillSource.Solid(RgbaColor.Black)

        FillContents.GRAY -> FillSource.Solid(RgbaColor(0.5f, 0.5f, 0.5f))

        FillContents.WHITE -> FillSource.Solid(RgbaColor.White)
    }
    val apply = {
        if (!applying) {
            applying = true
            cancelRequested = false
            applyError = null
            progress = null
            val requestedSource = source()
            val requestedOpacity = opacity / 100f
            val requestedMode = mode
            val requestedPreserveTransparency = preserve
            val requestedAction = contents.actionLabel
            scope.launch {
                try {
                    withFrameNanos { }
                    val engineJob = withContext(Dispatchers.Default) {
                        onApply(requestedSource, requestedOpacity, requestedMode, requestedPreserveTransparency, requestedAction)
                    }
                    activeEngineJob = engineJob
                    if (cancelRequested) engineJob.cancel()
                    val progressCollector = launch {
                        engineJob.progress.collect { progress = it }
                    }
                    val result = try {
                        withContext(Dispatchers.Default) { engineJob.await() }
                    } finally {
                        progressCollector.cancel()
                    }
                    activeEngineJob = null
                    when (result) {
                        is CommandResult.Accepted -> onDismiss()

                        is CommandResult.Rejected -> {
                            if (cancelRequested && result.code == RejectionCode.CANCELLED) {
                                onDismiss()
                            } else {
                                applyError = result.message
                                applying = false
                                cancelRequested = false
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    applyError = failure.message?.takeIf(String::isNotBlank) ?: "Fill could not be applied"
                    applying = false
                    cancelRequested = false
                    activeEngineJob = null
                }
            }
        }
    }
    val cancelOrDismiss = {
        if (applying) {
            if (!cancelRequested) {
                cancelRequested = true
                activeEngineJob?.cancel()
            }
        } else {
            onCancel()
            onDismiss()
        }
    }
    AeDialog(
        title = "Fill",
        body = if (hasSelection) "Fills the selection on the current layer." else "Fills the whole current layer.",
        onDismiss = cancelOrDismiss,
        onConfirm = apply,
        width = 420.dp,
        actions = {
            AeButton(
                when {
                    cancelRequested -> "Canceling fill…"
                    applying -> "Cancel fill"
                    else -> "Cancel"
                },
                cancelOrDismiss,
                enabled = !cancelRequested,
            )
            AeButton(
                if (applying) "Applying…" else "OK",
                apply,
                style = AeButtonStyle.Primary,
                enabled = !applying && !(contents == FillContents.CONTENT_AWARE && onlyPainted && strokes.none { it.add }),
            )
        },
    ) {
        Column(
            Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (applying) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val currentProgress = progress
                    if (currentProgress == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).semantics {
                                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { currentProgress },
                            modifier = Modifier.size(18.dp).semantics {
                                progressBarRangeInfo = ProgressBarRangeInfo(currentProgress, 0f..1f)
                            },
                            strokeWidth = 2.dp,
                        )
                        AeText("${(currentProgress * 100f).toInt()}%")
                    }
                    AeText(if (cancelRequested) "Canceling fill…" else "Applying fill…")
                }
            }
            applyError?.let { AeText(it, color = Ae.colors.danger) }
            Labeled("Contents") {
                AeDropdown(options, contents, { contents = it }, { it.label }, Modifier.weight(1f), enabled = !applying)
            }
            if (contents == FillContents.COLOR) {
                AeColorPicker(color, { if (!applying) color = it }, previous = foreground)
            }
            Labeled("Mode") {
                AeDropdown(
                    BlendMode.entries.filter { !it.groupOnly },
                    mode,
                    { mode = it },
                    { it.displayName },
                    Modifier.weight(1f),
                    enabled = !applying,
                )
            }
            Labeled("Opacity") {
                app.croptop.core.designsystem.component.AeBarSlider(
                    "Opacity",
                    opacity,
                    { opacity = it },
                    range = 1f..100f,
                    defaultValue = 100f,
                    unit = "%",
                    width = 176.dp,
                    enabled = !applying,
                    contentDescription = "Opacity",
                )
            }
            AeCheckbox(preserve, { preserve = it }, "Preserve Transparency", enabled = !applying)
            if (contents == FillContents.CONTENT_AWARE) {
                Labeled("Rotate") {
                    AeDropdown(
                        app.croptop.core.model.ContentAwareRotation.entries,
                        rotation,
                        { rotation = it },
                        { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                        Modifier.weight(1f),
                        enabled = !applying,
                    )
                }
                AeCheckbox(mirror, { mirror = it }, "Mirror patches", enabled = !applying)
                AeCheckbox(colorAdaptation, { colorAdaptation = it }, "Color adaptation", enabled = !applying)
                AeCheckbox(toNewLayer, { toNewLayer = it }, "Output to a new layer", enabled = !applying)
                AeCheckbox(
                    onlyPainted,
                    {
                        onlyPainted = it
                        if (it) showSampling = true
                    },
                    "Sample only from the areas I paint",
                    enabled = !applying,
                )
                if (contentAwareContext != null) {
                    AeButton(
                        if (showSampling) "Hide sampling area" else "Edit sampling area…",
                        { showSampling = !showSampling },
                        enabled = !applying,
                    )
                    if (showSampling) {
                        val context = caContext
                        if (context == null) {
                            AeText("Loading preview…", color = Ae.colors.textSecondary)
                        } else {
                            SamplingAreaEditor(context, strokes, onlyPainted, { strokes = it })
                        }
                    }
                }
                if (onlyPainted && strokes.none { it.add }) {
                    AeText("Paint at least one Add stroke, or turn off “Sample only from the areas I paint”.", color = Ae.colors.warning)
                }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AeText(label, color = Ae.colors.textSecondary, modifier = Modifier.width(72.dp))
        content()
    }
}

/**
 * Image ▸ Adjustments ▸ … or Filter ▸ …. Built from the same [AdjustmentSpec] as the Properties panel.
 */
@Composable
fun AdjustmentDialog(
    spec: AdjustmentSpec,
    onApply: suspend (Map<String, ParamValue>) -> CommandResult,
    onDismiss: () -> Unit,
    onPreview: (Map<String, ParamValue>) -> Unit = {},
    previewResult: StateFlow<CommandResult?>? = null,
    regionPreview: StateFlow<DocumentRegionPreview?>? = null,
    onCancelPreview: () -> Unit = {},
    isFilter: Boolean = false,
    /** Pixel layers a layer-choice parameter (Displace's map) can use. */
    layerChoices: List<app.croptop.core.model.ChoiceParam.Option> = emptyList(),
) {
    var values by remember(spec.id) { mutableStateOf(spec.defaults()) }
    var applying by remember(spec.id) { mutableStateOf(false) }
    var applyError by remember(spec.id) { mutableStateOf<String?>(null) }
    val asyncPreviewResult by previewResult?.collectAsState() ?: remember { mutableStateOf(null) }
    val asyncRegionPreview by regionPreview?.collectAsState() ?: remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(spec.id) {
        if (isFilter) onPreview(values)
    }
    val apply = {
        if (!applying) {
            applying = true
            applyError = null
            val submitted = values.toMap()
            scope.launch {
                val result = try {
                    onApply(submitted)
                } catch (failure: Exception) {
                    CommandResult.Rejected(
                        app.croptop.core.engine.api.RejectionCode.UNSUPPORTED,
                        failure.message?.takeIf(String::isNotBlank)
                            ?: if (isFilter) "Filter could not be applied" else "Adjustment could not be applied",
                    )
                }
                applying = false
                if (result is CommandResult.Accepted) {
                    onDismiss()
                } else if (result is CommandResult.Rejected) {
                    applyError = "Apply failed (${result.code.name}): ${result.message}"
                }
            }
        }
    }
    val cancel = {
        onCancelPreview()
        onDismiss()
    }
    val confirm = apply
    AeDialog(
        title = spec.name,
        body = if (isFilter) {
            "Preview this filter on the active layer. Smart objects keep it as an editable smart filter."
        } else {
            "Changes the current layer's pixels. For an editable version, use Layer ▸ New Adjustment Layer."
        },
        onDismiss = cancel,
        onConfirm = confirm,
        width = 380.dp,
        actions = {
            AeButton("Cancel", cancel)
            val confirmLabel = when {
                applying -> "Applying…"
                else -> "OK"
            }
            AeButton(
                confirmLabel,
                confirm,
                style = AeButtonStyle.Primary,
                enabled = !applying,
            )
        },
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            if (isFilter) {
                AeText("100% Preview · Center Crop", color = Ae.colors.textSecondary)
                if (asyncRegionPreview != null) {
                    AdjustmentRegionPreview(asyncRegionPreview!!)
                } else if (asyncPreviewResult is CommandResult.Accepted) {
                    AeText("A 100% crop preview is unavailable in this engine build.", color = Ae.colors.textSecondary)
                } else if (asyncPreviewResult is CommandResult.Rejected) {
                    AeText("100% crop unavailable after preview failure.", color = Ae.colors.warning)
                } else {
                    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
            AdjustmentProperties(spec, values, { next, _ ->
                values = next
                onPreview(next)
            }, layerChoices = layerChoices)
            (asyncPreviewResult as? CommandResult.Rejected)?.let { rejected ->
                AeText("Preview failed (${rejected.code.name}): ${rejected.message}", color = Ae.colors.warning)
            }
            applyError?.let { AeText(it, color = Ae.colors.warning) }
        }
    }
}

@Composable
private fun AdjustmentRegionPreview(preview: DocumentRegionPreview) {
    val density = LocalDensity.current
    val bitmap = remember(preview) {
        val pixels = IntArray(preview.region.width * preview.region.height) { index ->
            val offset = index * 4
            ((preview.rgba[offset + 3].toInt() and 255) shl 24) or
                ((preview.rgba[offset].toInt() and 255) shl 16) or
                ((preview.rgba[offset + 1].toInt() and 255) shl 8) or
                (preview.rgba[offset + 2].toInt() and 255)
        }
        Bitmap.createBitmap(pixels, preview.region.width, preview.region.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val imageWidth = with(density) { preview.region.width.toDp() }
    val imageHeight = with(density) { preview.region.height.toDp() }
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = Ae.shapes.control,
        color = Ae.colors.surface3,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 240.dp), contentAlignment = Alignment.Center) {
            Image(
                bitmap = bitmap,
                contentDescription = "Filter preview at 100 percent, center crop",
                modifier = Modifier.size(imageWidth, imageHeight).clip(Ae.shapes.control),
                contentScale = ContentScale.None,
            )
        }
    }
}

/** Catalog entry state is explicit so a visible operation never implies native support. */
sealed interface AdjustmentAvailability {
    data object Supported : AdjustmentAvailability
    data class Disabled(val reason: AdjustmentDisabledReason) : AdjustmentAvailability
}

enum class AdjustmentDisabledReason(val message: String) {
    ENGINE_UNSUPPORTED("Not supported by this engine"),
    PIXEL_LAYER_REQUIRED("Select a pixel layer to apply this adjustment."),
}

/** Image ▸ Adjustments catalog. Unsupported items remain discoverable and explain why they cannot run. */
@Composable
fun AdjustmentCatalogDialog(
    specs: List<AdjustmentSpec>,
    availability: (AdjustmentSpec) -> AdjustmentAvailability,
    onPick: (AdjustmentSpec) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Image Adjustments",
    body: String = "Choose a destructive adjustment for the active pixel layer.",
    groups: List<Pair<String, List<String>>> = emptyList(),
) {
    AeDialog(
        title = title,
        body = body,
        onDismiss = onDismiss,
        width = 400.dp,
        actions = { AeButton("Close", onDismiss) },
    ) {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            val specsById = specs.associateBy(AdjustmentSpec::id)
            val groupedEntries = groups.mapNotNull { (groupName, ids) ->
                val entries = ids.mapNotNull(specsById::get)
                if (entries.isEmpty()) null else groupName to entries
            }
            val groupedIds = groupedEntries.flatMap { (_, entries) -> entries.map(AdjustmentSpec::id) }.toSet()
            groupedEntries.forEach { (groupName, entries) ->
                AeText(
                    "$groupName (${entries.size})",
                    style = Ae.type.label,
                    color = Ae.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 3.dp),
                )
                entries.forEach { spec -> AdjustmentCatalogEntry(spec, availability(spec), onPick) }
            }
            val ungrouped = specs.filterNot { it.id in groupedIds }
            if (ungrouped.isNotEmpty() && groups.isNotEmpty()) {
                AeText(
                    "Other",
                    style = Ae.type.label,
                    color = Ae.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 3.dp),
                )
            }
            ungrouped.forEach { spec ->
                AdjustmentCatalogEntry(spec, availability(spec), onPick)
            }
        }
    }
}

@Composable
private fun AdjustmentCatalogEntry(
    spec: AdjustmentSpec,
    state: AdjustmentAvailability,
    onPick: (AdjustmentSpec) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (state is AdjustmentAvailability.Supported) Modifier.clickable { onPick(spec) } else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AeText(
                spec.name,
                modifier = Modifier.weight(1f),
                color = if (state is AdjustmentAvailability.Supported) Ae.colors.textPrimary else Ae.colors.textSecondary,
            )
            if (state is AdjustmentAvailability.Disabled) AeText("Unavailable", color = Ae.colors.textSecondary)
        }
        if (state is AdjustmentAvailability.Disabled) {
            AeText(state.reason.message, color = Ae.colors.textSecondary)
        }
    }
}

/** The list of adjustments for a new adjustment layer (Layer menu, Layers panel). */
@Composable
fun NewAdjustmentLayerDialog(specs: List<AdjustmentSpec>, onPick: (AdjustmentSpec) -> Unit, onDismiss: () -> Unit) {
    AeDialog(
        title = "New Adjustment Layer",
        body = "Adjustment layers change the look of the layers below without touching their pixels.",
        onDismiss = onDismiss,
        width = 360.dp,
        actions = { AeButton("Cancel", onDismiss) },
    ) {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            specs.forEach { spec ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(Ae.density.rowHeight + 6.dp)
                        .clickable { onPick(spec) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AeText(spec.name, modifier = Modifier.weight(1f))
                        }
            }
        }
    }
}
