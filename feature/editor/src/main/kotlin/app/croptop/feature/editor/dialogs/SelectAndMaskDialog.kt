package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AePanel
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RefineEdgeBrushMode
import app.croptop.core.engine.api.RefineEdgeBrushSpec
import app.croptop.core.engine.api.RefineEdgeOutput
import app.croptop.core.engine.api.RefineEdgeSession
import app.croptop.core.engine.api.RefineEdgeSettings
import app.croptop.core.engine.api.RefineEdgeViewMode

/** Select and Mask workspace. The non-modal panel leaves the canvas available for refine strokes. */
@Composable
fun SelectAndMaskDialog(
    session: RefineEdgeSession,
    onDone: (CommandResult?, RefineEdgeOutput) -> Unit,
    onViewModeResult: (CommandResult) -> Unit = {},
    brushSpec: RefineEdgeBrushSpec = RefineEdgeBrushSpec(),
    onBrushSpecChange: (RefineEdgeBrushSpec) -> Unit = {},
    cancelRequested: Boolean = false,
) {
    var settings by remember(session) { mutableStateOf(RefineEdgeSettings()) }
    var output by remember(session) { mutableStateOf(RefineEdgeOutput.SELECTION) }
    val workspace = remember(session) { RefineEdgeWorkspace(session) }
    val status by workspace.status.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    val viewResult by rememberUpdatedState(onViewModeResult)
    // The window's own size (not the screen's), so the panel fits a resized desktop window too.
    val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowWidthDp = with(density) { windowSize.width.toDp().value.toInt() }
    val windowHeightDp = with(density) { windowSize.height.toDp().value.toInt() }
    val viewMode by session.viewMode.collectAsStateWithLifecycle()
    LaunchedEffect(workspace) { workspace.run({ result, selected -> done(result, selected) }, { viewResult(it) }) }
    LaunchedEffect(settings, workspace) { workspace.update(settings) }
    LaunchedEffect(cancelRequested, workspace) { if (cancelRequested) workspace.cancel() }
    Popup(
        alignment = Alignment.TopEnd,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
    ) {
        val colors = Ae.colors
        AePanel(
            title = "Select and Mask",
            modifier = Modifier.width(minOf(380, windowWidthDp).dp).background(colors.surface2, Ae.shapes.popover)
                .border(1.dp, colors.lineStrong, Ae.shapes.popover).semantics { paneTitle = "Select and Mask" },
            actions = {
                AeButton("Cancel", workspace::cancel, enabled = !status.finishing)
                AeButton(
                    "OK",
                    { if (workspace.canCommit(settings)) workspace.commit(output) },
                    style = AeButtonStyle.Primary,
                    enabled = status.canCommit && workspace.canCommit(settings),
                )
            },
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = (windowHeightDp - 112).coerceIn(120, 760).dp)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AeText(
                    status.error ?: when {
                        status.cancelling -> "Restoring selection…"
                        status.finishing -> "Applying refinement…"
                        status.updating -> "Updating preview…"
                        else -> "Preview ready"
                    },
                    color = if (status.error != null) colors.warning else colors.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    maxLines = 4,
                )
                if (status.error != null && !status.finishing) {
                    AeButton("Retry preview", { workspace.update(settings) })
                }
                AeText("Paint on the canvas to add or remove edge areas.", color = colors.textSecondary, maxLines = 2)
                AeSegmented(
                    options = RefineEdgeBrushMode.entries,
                    selected = brushSpec.mode,
                    onSelect = { onBrushSpecChange(brushSpec.copy(mode = it)) },
                    label = { if (it == RefineEdgeBrushMode.ADD) "Add" else "Subtract" },
                )
                AeSlider(
                    "Brush Size",
                    brushSpec.diameter,
                    { onBrushSpecChange(brushSpec.copy(diameter = it)) },
                    range = 1f..500f,
                    unit = "px",
                )
                AeSegmented(
                    options = RefineEdgeViewMode.entries,
                    selected = viewMode,
                    onSelect = workspace::setViewMode,
                    label = RefineEdgeViewMode::label,
                )
                AeSlider("Radius", settings.radius, { settings = settings.copy(radius = it) }, range = 0f..250f, defaultValue = 10f, unit = "px")
                AeCheckbox(settings.smartRadius, { settings = settings.copy(smartRadius = it) }, "Smart Radius")
                AeSlider("Smooth", settings.smooth, { settings = settings.copy(smooth = it) }, range = 0f..100f)
                AeSlider("Feather", settings.feather, {
                    settings = settings.copy(feather = it)
                }, range = 0f..250f, step = 0.1f, decimals = 1, unit = "px")
                AeSlider("Contrast", settings.contrast, { settings = settings.copy(contrast = it) }, range = 0f..100f, unit = "%")
                AeSlider("Shift Edge", settings.shiftEdge, {
                    settings = settings.copy(shiftEdge = it)
                }, range = -100f..100f, defaultValue = 0f, unit = "%")
                AeCheckbox(settings.decontaminate, { settings = settings.copy(decontaminate = it) }, "Decontaminate Colors")
                if (settings.decontaminate) {
                    AeSlider(
                        "Amount",
                        settings.decontaminateAmount * 100f,
                        { settings = settings.copy(decontaminateAmount = (it / 100f).coerceIn(0f, 1f)) },
                        range = 0f..100f,
                        defaultValue = 100f,
                        unit = "%",
                    )
                }
                AeText("Output", color = colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RefineEdgeOutput.entries.forEach { option ->
                        AeButton(option.label, { output = option }, style = if (option == output) AeButtonStyle.Primary else AeButtonStyle.Secondary)
                    }
                }
            }
        }
    }
}
