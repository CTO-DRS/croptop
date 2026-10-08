package app.croptop.feature.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import app.croptop.core.agent.AgentStatus
import app.croptop.core.agent.AgentViewModel
import app.croptop.core.designsystem.component.AeAppHeader
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeContextMenuArea
import app.croptop.core.designsystem.component.AeDivider
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeEmptyState
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeInlineSlider
import app.croptop.core.designsystem.component.AeMenu
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AePanel
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.AeToggleIconButton
import app.croptop.core.designsystem.component.LevelsPickTarget
import app.croptop.core.designsystem.component.SearchCommandsButton
import app.croptop.core.designsystem.component.captionInteractive
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.AeSizes
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.input.ActionId
import app.croptop.core.input.ActionScope
import app.croptop.core.input.Actions
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutScope
import app.croptop.core.input.shortcutLabel
import app.croptop.core.input.toolAction
import app.croptop.core.input.windowPanelAction
import app.croptop.core.input.workspaceAction
import app.croptop.core.model.BlendMode
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.DockPanel
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ToolGroup
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewAids
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.core.model.WorkspaceLayout
import app.croptop.feature.editor.canvas.CanvasViewport
import app.croptop.feature.editor.canvas.GridSettingsDialog
import app.croptop.feature.editor.canvas.GuideDragger
import app.croptop.feature.editor.canvas.GuideLayoutDialog
import app.croptop.feature.editor.canvas.NewGuideDialog
import app.croptop.feature.editor.canvas.ViewAidsOverlay
import app.croptop.feature.editor.canvas.WithRulers
import app.croptop.feature.editor.files.ExportDialog
import app.croptop.feature.editor.files.UnsavedChangesDialog
import app.croptop.feature.editor.files.extension
import app.croptop.feature.editor.panels.ColorPanelContent
import app.croptop.feature.editor.panels.DefaultSwatchList
import app.croptop.feature.editor.panels.HistoryPanelContent
import app.croptop.feature.editor.panels.LayersPanelContent
import app.croptop.feature.editor.panels.NavigatorPanelContent
import app.croptop.feature.editor.panels.SwatchesUi
import app.croptop.feature.editor.panels.SwatchesViewModel
import app.croptop.feature.editor.path.PathDraftController
import app.croptop.feature.editor.path.VectorPathToolController
import app.croptop.feature.editor.session.EditorActionFeedbackLabel
import app.croptop.feature.editor.session.EditorState
import app.croptop.feature.editor.session.EditorViewModel
import app.croptop.feature.editor.tabs.documentTabGestures
import app.croptop.feature.editor.tools.EyedropperTool
import app.croptop.feature.editor.tools.HandTool
import app.croptop.feature.editor.tools.ResetRotationButton
import app.croptop.feature.editor.tools.RotateViewTool
import app.croptop.feature.editor.tools.ToolController
import app.croptop.feature.editor.tools.ZoomTool
import app.croptop.feature.editor.workspace.CompactBottomBar
import app.croptop.feature.editor.workspace.DockColumn
import app.croptop.feature.editor.workspace.DockRail
import app.croptop.feature.editor.workspace.DockResizeHandle
import app.croptop.feature.editor.workspace.FloatingPanels
import app.croptop.feature.editor.workspace.PanelContent
import app.croptop.feature.editor.workspace.PanelOverlay
import app.croptop.feature.editor.workspace.SaveWorkspaceDialog
import app.croptop.feature.editor.workspace.WorkspaceViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class PendingLevelsPick(val layerId: LayerId, val target: LevelsPickTarget)
private data class PendingAdjustmentTarget(val layerId: LayerId, val specId: String)

private fun repositoryResult(result: String): app.croptop.feature.editor.session.ActionOutcome = when {
    result.startsWith("Select a custom") ->
        app.croptop.feature.editor.session.ActionOutcome.Rejected(
            app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
            result,
        )

    result.startsWith("Couldn't") || result.startsWith("That file") ->
        app.croptop.feature.editor.session.ActionOutcome.Failed(result)

    else -> app.croptop.feature.editor.session.ActionOutcome.Completed(result)
}

private val LayerKind.displayName: String
    get() = when (this) {
        LayerKind.PIXEL -> "Pixel"
        LayerKind.ADJUSTMENT -> "Adjustment"
        LayerKind.FILL -> "Fill"
        LayerKind.TEXT -> "Text"
        LayerKind.SHAPE -> "Shape"
        LayerKind.GROUP -> "Group"
        LayerKind.SMART_OBJECT -> "Smart object"
    }

/**
 * Editor workspace: menu bar in the caption row, document tab, options bar,
 * toolbar, canvas, right dock (Properties, History, Layers) and status bar.
 */
@Composable
fun EditorScreen(
    route: EditorRoute,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenInNewWindow: ((String) -> Unit)? = null,
    onOpenSmartObjectContents: ((EditorRoute) -> Unit)? = null,
    onOpenDocumentInTab: ((EditorRoute) -> Unit)? = null,
    documentTabs: List<EditorDocumentTab> = emptyList(),
    onDocumentTabInfo: (EditorRoute, String, Boolean) -> Unit = { _, _, _ -> },
    onSelectDocumentTab: (EditorRoute) -> Unit = {},
    onCloseDocumentTab: (EditorRoute) -> Unit = {},
    onMoveDocumentTab: (EditorRoute, EditorRoute) -> Unit = { _, _ -> },
    closeTabOnActivation: Boolean = false,
    onCloseTabRequestConsumed: () -> Unit = {},
) {
    val vm = hiltViewModel<EditorViewModel, EditorViewModel.Factory>(key = route.toString()) { it.create(route) }
    val ws = hiltViewModel<WorkspaceViewModel>()
    val agent = hiltViewModel<AgentViewModel>()
    val agentStatus by agent.status.collectAsStateWithLifecycle()
    val pendingAgentExport by agent.pendingExport.collectAsStateWithLifecycle()
    pendingAgentExport?.let { request ->
        app.croptop.core.designsystem.component.AeDialog(
            title = "Allow this export?",
            body = request.description,
            onDismiss = { agent.answerExport(false) },
            onConfirm = { agent.answerExport(true) },
            actions = {
                app.croptop.core.designsystem.component.AeButton("Deny", { agent.answerExport(false) })
                app.croptop.core.designsystem.component.AeButton(
                    "Allow",
                    { agent.answerExport(true) },
                    style = app.croptop.core.designsystem.component.AeButtonStyle.Primary,
                )
            },
        )
    }
    // This window's document is the one agents act on while it's shown.
    LaunchedEffect(vm) { vm.activate() }
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.activate()
        onPauseOrDispose { }
    }
    val layout by ws.layout.collectAsStateWithLifecycle()
    val custom by ws.custom.collectAsStateWithLifecycle()
    val sw = hiltViewModel<SwatchesViewModel>()
    val swatchList by sw.swatches.collectAsStateWithLifecycle()
    val swatchMessage by sw.message.collectAsStateWithLifecycle()
    val importSwatches = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            vm.reportUiActionCompleted("Swatch import canceled")
        } else {
            vm.dispatchUiAction("Import swatches") {
                repositoryImportResult(RepositoryImportKind.SWATCHES, sw.import(uri).await())
            }
        }
    }
    val exportSwatches = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) {
            vm.reportUiActionCompleted("Swatch export canceled")
        } else {
            vm.dispatchUiAction("Export swatches") { repositoryResult(sw.export(uri).await()) }
        }
    }
    LaunchedEffect(swatchMessage) {
        if (swatchMessage != null) {
            kotlinx.coroutines.delay(4_000)
            sw.message.value = null
        }
    }
    val swatches = SwatchesUi(
        swatchList,
        onAdd = { color ->
            vm.dispatchUiAction("Add swatch") {
                sw.add(color).await()
                val duplicate = swatchList.any { it.color.toArgb() == color.toArgb() }
                app.croptop.feature.editor.session.ActionOutcome.Completed(
                    if (duplicate) "Swatch already exists" else "Swatch added",
                )
            }
        },
        onRemove = { color ->
            vm.dispatchUiAction("Remove swatch") {
                sw.remove(color).await()
                app.croptop.feature.editor.session.ActionOutcome.Completed("Swatch removed")
            }
        },
        onImport = {
            vm.reportUiActionCompleted("Choose a swatch file to import")
            importSwatches.launch(arrayOf("application/octet-stream", "*/*"))
        },
        onExport = {
            vm.reportUiActionCompleted("Choose where to export swatches")
            exportSwatches.launch("Swatches.ase")
        },
        onReset = {
            vm.dispatchUiAction("Reset swatches") {
                sw.reset().await()
                app.croptop.feature.editor.session.ActionOutcome.Completed("Swatches reset")
            }
        },
        message = swatchMessage,
    )
    val typeVm = hiltViewModel<app.croptop.feature.editor.text.TypeViewModel>()
    val type by typeVm.ui.collectAsStateWithLifecycle()
    val brushVm = hiltViewModel<app.croptop.feature.editor.brush.BrushViewModel>()
    val brushState by brushVm.ui.collectAsStateWithLifecycle()
    val stylusVm = hiltViewModel<app.croptop.feature.editor.tools.StylusInputViewModel>()
    val stylusSettings by stylusVm.settings.collectAsStateWithLifecycle()
    val importBrushes = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            vm.reportUiActionCompleted("Brush import canceled")
        } else {
            vm.dispatchUiAction("Import brushes") {
                repositoryImportResult(RepositoryImportKind.BRUSHES, brushVm.import(uri).await())
            }
        }
    }
    val exportBrushes =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) {
                vm.reportUiActionCompleted("Brush export canceled")
            } else {
                vm.dispatchUiAction("Export brushes") { repositoryResult(brushVm.export(uri).await()) }
            }
        }
    val brushMessage by brushVm.message.collectAsStateWithLifecycle()
    LaunchedEffect(brushMessage) {
        if (brushMessage != null) {
            kotlinx.coroutines.delay(4_000)
            brushVm.message.value = null
        }
    }
    val brush = brushState.copy(
        onSettings = { settings ->
            brushState.onSettings(settings)
            vm.reportUiActionCompleted("Update brush settings")
        },
        onSettingsPreview = brushState.onSettings,
        onSettingsFinished = { vm.reportUiActionCompleted("Update brush settings") },
        onSelect = { preset ->
            brushState.onSelect(preset)
            vm.reportUiActionCompleted("Select brush ${preset.name}")
        },
        onSaveNew = { name ->
            vm.dispatchUiAction("Save brush preset") { repositoryResult(brushVm.saveNew(name).await()) }
        },
        onUpdatePreset = {
            vm.dispatchUiAction("Update brush preset") { repositoryResult(brushVm.updatePreset().await()) }
        },
        onFavorite = { preset ->
            vm.dispatchUiAction("Update brush favorite") { repositoryResult(brushVm.setFavorite(preset).await()) }
        },
        onRename = { preset, name ->
            vm.dispatchUiAction("Rename brush") { repositoryResult(brushVm.rename(preset, name).await()) }
        },
        onDelete = { preset ->
            vm.dispatchUiAction("Delete brush") { repositoryResult(brushVm.deletePreset(preset).await()) }
        },
        onImport = {
            vm.reportUiActionCompleted("Choose a brush file to import")
            importBrushes.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        },
        onExport = {
            vm.reportUiActionCompleted("Choose where to export brushes")
            exportBrushes.launch("My Brushes.aebrushes")
        },
        onActionCompleted = vm::reportUiActionCompleted,
    )
    EditorContent(
        vm, route, onClose, modifier,
        layout = layout,
        onLayout = ws::set,
        onOpenInNewWindow = onOpenInNewWindow,
        onOpenSmartObjectContents = onOpenSmartObjectContents,
        onOpenDocumentInTab = onOpenDocumentInTab,
        documentTabs = documentTabs,
        onDocumentTabInfo = onDocumentTabInfo,
        onSelectDocumentTab = onSelectDocumentTab,
        onCloseDocumentTab = onCloseDocumentTab,
        onMoveDocumentTab = onMoveDocumentTab,
        closeTabOnActivation = closeTabOnActivation,
        onCloseTabRequestConsumed = onCloseTabRequestConsumed,
        customWorkspaces = custom,
        onSaveWorkspace = ws::saveCustom,
        agentStatus = agentStatus,
        onDisconnectAgent = agent::disconnect,
        swatches = swatches,
        typeUi = type,
        brushUi = brush,
        inputPreferences = stylusSettings,
    )
}

/** Editor UI for a given session; separated from Hilt so screenshot tests can drive it. */
@Composable
fun EditorContent(
    vm: EditorViewModel,
    route: EditorRoute,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    layout: WorkspaceLayout = WorkspaceLayout.Essentials,
    onLayout: (WorkspaceLayout) -> Deferred<Unit> = { kotlinx.coroutines.CompletableDeferred(Unit) },
    onOpenInNewWindow: ((String) -> Unit)? = null,
    onOpenSmartObjectContents: ((EditorRoute) -> Unit)? = null,
    onOpenDocumentInTab: ((EditorRoute) -> Unit)? = null,
    documentTabs: List<EditorDocumentTab> = emptyList(),
    onDocumentTabInfo: (EditorRoute, String, Boolean) -> Unit = { _, _, _ -> },
    onSelectDocumentTab: (EditorRoute) -> Unit = {},
    onCloseDocumentTab: (EditorRoute) -> Unit = {},
    onMoveDocumentTab: (EditorRoute, EditorRoute) -> Unit = { _, _ -> },
    closeTabOnActivation: Boolean = false,
    onCloseTabRequestConsumed: () -> Unit = {},
    customWorkspaces: List<WorkspaceLayout> = emptyList(),
    onSaveWorkspace: ((String) -> Deferred<Unit>)? = null,
    agentStatus: AgentStatus = AgentStatus(),
    onDisconnectAgent: () -> Unit = {},
    swatches: SwatchesUi? = null,
    typeUi: app.croptop.feature.editor.text.TypeUi = app.croptop.feature.editor.text.TypeUi(families = null),
    brushUi: app.croptop.feature.editor.brush.BrushUi = app.croptop.feature.editor.brush.BrushUi(),
    inputPreferences: app.croptop.core.model.UiPreferences = app.croptop.core.model.UiPreferences(),
) {
    var showSaveWorkspace by remember { mutableStateOf(false) }
    val openError = vm.state.collectAsStateWithLifecycle().value.openError
    if (openError != null) {
        OpenErrorScreen(route.sourceUri?.let { android.net.Uri.decode(it).substringAfterLast('/') } ?: "Document", openError, onClose, modifier)
        return
    }
    val c = Ae.colors
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    // Expanded ≥ 840dp: dock. Medium 600–840dp: icon rail + overlay. Compact < 600dp: bottom strip + sheet.
    val expanded = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val compact = !sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    var overlayPanel by remember { mutableStateOf<DockPanel?>(null) }
    val state by vm.state.collectAsStateWithLifecycle()
    val activeDocument by vm.document.collectAsStateWithLifecycle()
    val patterns by vm.patterns.collectAsStateWithLifecycle()
    val clipboard by vm.clipboard.collectAsStateWithLifecycle()
    val maskTarget by vm.maskTarget.collectAsStateWithLifecycle()
    val canvasEditRecovery by vm.canvasEditRecovery.collectAsStateWithLifecycle()
    val quickMaskFlow = remember(activeDocument) { activeDocument?.quickMask ?: kotlinx.coroutines.flow.MutableStateFlow(false) }
    val editingSelectionMask by quickMaskFlow.collectAsStateWithLifecycle()
    val docSize = state.info?.size ?: route.newSpec?.size ?: DocSize(4000, 3000)
    val density = LocalDensity.current
    // Held as a State so lambdas can capture the holder (stable) instead of the per-frame value.
    val viewportState = remember { mutableStateOf(ViewportState()) }
    var viewport by viewportState
    var spaceHeld by remember { mutableStateOf(false) }
    var levelsPick by remember { mutableStateOf<PendingLevelsPick?>(null) }
    var adjustmentTarget by remember { mutableStateOf<PendingAdjustmentTarget?>(null) }
    var targetedHueChannel by remember { mutableStateOf<app.croptop.core.model.HueChannel?>(null) }

    /** Text tool: where the pending text goes, and the layer being edited (null = new layer). */
    var textEntry by remember { mutableStateOf<app.croptop.feature.editor.tools.TextPlacement?>(null) }
    var textDraft by remember { mutableStateOf("") }
    var textEditorBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var lastUnavailableTap by remember { mutableStateOf<ViewPoint?>(null) }

    /** Select and Mask workspace session, while its workspace panel is open. */
    var refineSession by remember { mutableStateOf<app.croptop.core.engine.api.RefineEdgeSession?>(null) }
    var cancelRefinement by remember { mutableStateOf(false) }
    var refineBrushSpec by remember { mutableStateOf(app.croptop.core.engine.api.RefineEdgeBrushSpec()) }
    fun viewCenter() = ViewPoint(viewport.viewWidth / 2f, viewport.viewHeight / 2f)
    var tool by rememberSaveable { mutableStateOf(ToolId.HAND) }
    var gradientDialog by remember { mutableStateOf(false) }
    val currentGradient = remember(vm) { mutableStateOf<app.croptop.core.model.ColorGradient?>(null) }
    // Read by the Brush/Eraser controllers at stroke time, so they always paint with the current settings.
    val brushSettings = androidx.compose.runtime.rememberUpdatedState(brushUi.settings)
    val stylusPressureCurve = androidx.compose.runtime.rememberUpdatedState(inputPreferences.stylusPressureCurve)
    val pathDrafts = remember(vm) { PathDraftController() }
    var pathDraftLayerId by remember(vm) { mutableStateOf<app.croptop.core.model.LayerId?>(null) }
    fun commitPathDrafts(paths: List<app.croptop.core.model.VectorPathDraft>): Boolean {
        return vm.commitShapePaths(pathDraftLayerId, paths)
    }
    val eyedropperScope = rememberCoroutineScope()
    val eyedropperController = remember(vm, eyedropperScope) {
        app.croptop.feature.editor.tools.EyedropperTool(
            { p, m, size -> vm.sample(p, m, size) },
            vm::setForeground,
            vm::setBackground,
            { color ->
                vm.rememberColor(color)
                vm.reportUiActionCompleted("Pick color")
            },
        ) { background ->
            val colors = vm.colors.value
            if (background) colors.background else colors.foreground
        }.apply {
            useAsyncSampling(eyedropperScope)
            onActionCompleted = vm::reportUiActionCompleted
        }
    }
    DisposableEffect(eyedropperController) {
        onDispose(eyedropperController::close)
    }
    val latestTypeUi by androidx.compose.runtime.rememberUpdatedState(typeUi)

    /** Turns the open on-canvas text draft into a text layer (or updates the one being edited); blank drafts are dropped. */
    fun commitTextDraft() {
        val placement = textEntry ?: return
        val draft = textDraft
        textEntry = null
        textDraft = ""
        if (draft.isBlank()) return
        val existing = placement.editing?.let { id -> vm.state.value.layers.find(id)?.text }
        val info = existing?.copy(text = draft)
            ?: app.croptop.core.model.TextLayerInfo(
                draft,
                latestTypeUi.character,
                latestTypeUi.paragraph,
                kind = if (placement.boxWidth != null) app.croptop.core.model.TextKind.PARAGRAPH else app.croptop.core.model.TextKind.POINT,
                origin = placement.at,
                boxWidth = placement.boxWidth ?: 0f,
            )
        val editingId = placement.editing
        if (existing != null && editingId != null) vm.setTextLayer(editingId, info) else vm.createTextLayer(info)
    }

    val controllers = remember(vm, eyedropperController) {
        listOf(
            HandTool().apply { onActionCompleted = vm::reportUiActionCompleted },
            ZoomTool().apply { onActionCompleted = vm::reportUiActionCompleted },
            RotateViewTool().apply { onActionCompleted = vm::reportUiActionCompleted },
            eyedropperController,
            app.croptop.feature.editor.tools.TextTool(
                textLayerAt = { p ->
                    vm.layerAt(p)?.takeIf { id -> vm.state.value.layers.find(id)?.kind == app.croptop.core.model.LayerKind.TEXT }
                },
                onPlace = { placement ->
                    // A click elsewhere keeps what was typed: commit the open draft, then start the new text.
                    commitTextDraft()
                    textDraft = placement.editing?.let { id -> vm.state.value.layers.find(id)?.text?.text }.orEmpty()
                    textEntry = placement
                },
                editorBounds = { textEditorBounds },
            ).apply { onActionCompleted = vm::reportUiActionCompleted },
            app.croptop.feature.editor.tools.MoveTool(
                layerAt = vm::layerAt,
                selectLayer = { vm.select(it) },
                selectedBounds = {
                    val s = vm.state.value
                    val size = s.info?.size
                    if (size == null) {
                        null
                    } else {
                        app.croptop.feature.editor.tools.selectedLayerBounds(
                            s.selected.mapNotNull { id -> s.layers.find(id) }.map { it.bounds },
                            app.croptop.core.model.DocRect.of(size),
                        )
                    }
                },
                commit = vm::translateSelected,
                viewAids = { vm.viewAids.value },
                otherLayerBounds = {
                    val current = vm.state.value
                    current.layers.flatten().filterNot { it.id in current.selected }.mapNotNull { it.bounds }
                },
                selectionBounds = {
                    (vm.state.value.selection as? app.croptop.core.model.SelectionInfo.Active)?.bounds
                },
            ).apply { onActionCompleted = vm::reportUiActionCompleted },
            app.croptop.feature.editor.tools.MarqueeTool(
                ToolId.RECT_MARQUEE,
                ellipse = false,
                { vm.state.value.hasSelection },
                vm::selectShape,
            ).apply { onActionCompleted = vm::reportUiActionCompleted },
            app.croptop.feature.editor.tools.MarqueeTool(
                ToolId.ELLIPSE_MARQUEE,
                ellipse = true,
                { vm.state.value.hasSelection },
                vm::selectShape,
            ).apply { onActionCompleted = vm::reportUiActionCompleted },
            app.croptop.feature.editor.tools.LassoTool(ToolId.LASSO, polygonal = false, vm::selectShape).apply {
                onActionCompleted = vm::reportUiActionCompleted
            },
            app.croptop.feature.editor.tools.LassoTool(ToolId.POLYGONAL_LASSO, polygonal = true, vm::selectShape).apply {
                onActionCompleted = vm::reportUiActionCompleted
            },
            app.croptop.feature.editor.tools.MagneticLassoTool(vm::beginMagneticLasso) { vm.reportUiCommandResult("Magnetic Lasso", it) },
            app.croptop.feature.editor.tools.ObjectSelectTool(
                { vm.state.value.hasSelection },
                vm::selectObject,
                vm::deselect,
                vm::selectSubject,
                { point, op -> vm.selectObjectAt(point, op) },
            ).apply { onActionCompleted = vm::reportUiActionCompleted },
            app.croptop.feature.editor.tools.QuickSelectionTool(
                vm::beginQuickSelection,
                vm::quickSelectionResult,
                vm::selectSubject,
                vm::cancelQuickSelection,
            ).apply { onActionCompleted = vm::reportUiActionCompleted },
            app.croptop.feature.editor.tools.MagicWandTool(
                onActionCompleted = vm::reportUiActionCompleted,
                apply = vm::magicWand,
            ),
            app.croptop.feature.editor.tools.PaintBucketTool(
                foreground = { vm.colors.value.foreground },
                onActionCompleted = vm::reportUiActionCompleted,
                apply = vm::paintBucket,
            ),
            app.croptop.feature.editor.tools.GradientTool(
                { vm.colors.value.foreground to vm.colors.value.background },
                vm::drawGradient,
                { currentGradient.value },
                {
                    gradientDialog = true
                    vm.reportUiActionCompleted("Open gradient editor")
                },
                vm::reportUiActionCompleted,
            ),
            app.croptop.feature.editor.tools.LiquifyToolController(
                beginSession = vm::beginLiquify,
                onResult = vm::reportUiCommandResult,
                rejectStroke = { detail ->
                    vm.reportUiActionRejected(
                        "Liquify stroke",
                        app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                        detail,
                    )
                },
            ),
            app.croptop.feature.editor.tools.BrushTool(
                tool = ToolId.BRUSH,
                erase = false,
                settings = { brushSettings.value },
                color = { vm.colors.value.foreground },
                begin = vm::beginStroke,
                onResult = { vm.strokeResult("Brush stroke", it) },
                pressureCurve = { stylusPressureCurve.value },
                onCancel = { vm.cancelStroke("Brush stroke") },
            ).apply {
                endStrokeOffMain = vm::finishStrokeOffMain
            },
            app.croptop.feature.editor.tools.BrushTool(
                tool = ToolId.ERASER,
                erase = true,
                settings = { brushSettings.value },
                color = { vm.colors.value.foreground },
                begin = vm::beginStroke,
                onResult = { vm.strokeResult("Erase stroke", it) },
                pressureCurve = { stylusPressureCurve.value },
                onCancel = { vm.cancelStroke("Erase stroke") },
            ).apply {
                endStrokeOffMain = vm::finishStrokeOffMain
            },
            app.croptop.feature.editor.tools.AiRemoveTool(vm::removeStroke),
            app.croptop.feature.editor.tools.RedEyeTool(vm::redEye),
            app.croptop.feature.editor.tools.PatchTool(
                { vm.state.value.hasSelection },
                vm::patch,
                { vm.reportUiActionRejected("Patch", app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT, "Select the area to patch first.") },
            ),
            *app.croptop.feature.editor.tools.RetouchTools.filter {
                it != ToolId.PATCH && it != ToolId.RED_EYE
            }.map { id ->
                val action = "${id.displayName} stroke"
                app.croptop.feature.editor.tools.RetouchTool(
                    id,
                    { size, hardness, effect -> vm.beginRetouch(size, hardness, effect, action) },
                    { vm.strokeResult(action, it) },
                    vm::notify,
                    { vm.cancelStroke(action) },
                    { vm.state.value.history.entries.map { it.label } },
                    { vm.colors.value.foreground },
                    { vm.colors.value.background },
                ).apply {
                    onActionCompleted = vm::reportUiActionCompleted
                    onActionUnsupported = vm::reportUiActionUnsupported
                }
            }.toTypedArray(),
            *listOf(ToolId.CROP, ToolId.PERSPECTIVE_CROP).map { cropTool ->
                app.croptop.feature.editor.tools.CropTool(
                    { vm.state.value.info?.size },
                    { r, delete -> vm.crop(r, deleteCropped = delete) },
                    { quad, delete -> vm.cropPerspective(quad, deleteCropped = delete) },
                    { r, angle, delete -> vm.crop(r, angle = angle, deleteCropped = delete) },
                    cropTool,
                ).apply { onActionCompleted = vm::reportUiActionCompleted }
            }.toTypedArray(),
            *listOf(
                ToolId.PEN,
                ToolId.RECTANGLE,
                ToolId.ELLIPSE,
                ToolId.POLYGON,
                ToolId.LINE,
                ToolId.PATH_SELECT,
                ToolId.DIRECT_SELECT,
            ).map { pathTool ->
                VectorPathToolController(pathTool, pathDrafts, ::commitPathDrafts).apply {
                    onActionCompleted = vm::reportUiActionCompleted
                }
            }.toTypedArray(),
        ).associateBy { it.tool }
    }
    var hover by remember { mutableStateOf<ViewPoint?>(null) }
    var hoverPointerKind by remember { mutableStateOf<app.croptop.core.input.PointerKind?>(null) }
    var hoverModifiers by remember { mutableStateOf(app.croptop.core.input.Modifiers.None) }
    val aids by vm.viewAids.collectAsStateWithLifecycle()
    val openDocument by vm.document.collectAsStateWithLifecycle()
    val noSoftProof = remember { kotlinx.coroutines.flow.MutableStateFlow<app.croptop.core.model.SoftProofSettings?>(null) }
    val softProof by (openDocument?.softProof ?: noSoftProof).collectAsStateWithLifecycle()
    LaunchedEffect(openDocument?.id, state.primaryLayer?.id, state.primaryLayer?.vectorPaths) {
        val shapeLayer = state.primaryLayer?.takeIf { it.kind == app.croptop.core.model.LayerKind.SHAPE }
        pathDraftLayerId = shapeLayer?.id
        pathDrafts.loadPersistedPaths(shapeLayer?.vectorPaths.orEmpty())
    }
    var aidsDialog by remember { mutableStateOf<String?>(null) }
    val currentTool by rememberUpdatedState(tool)
    val currentDoc by rememberUpdatedState(docSize)
    val guideDragger = remember(vm) { GuideDragger({ vm.viewAids.value }, { vm.viewAids.value = it }, { currentTool }, { currentDoc }) }
    fun toggleAids(action: String = "Update view aids", f: (ViewAids) -> ViewAids) {
        vm.viewAids.update(f)
        vm.reportUiActionCompleted(action)
    }
    // Last-used tool per group, so pressing a group key restores it (Photoshop behavior).
    var lastInGroup by rememberSaveable { mutableStateOf(mapOf<ToolGroup, ToolId>()) }
    fun select(t: ToolId) {
        tool = t
        lastInGroup = lastInGroup + (t.group to t)
        vm.reportUiActionCompleted("Select ${t.displayName} tool")
    }
    fun setOverlayPanel(panel: DockPanel?) {
        overlayPanel = panel
        vm.reportUiActionCompleted(panel?.let { "Open ${it.title} panel" } ?: "Close panel")
    }
    fun setWorkspace(next: WorkspaceLayout, action: String = "Change workspace") {
        vm.dispatchUiAction(action) {
            onLayout(next).await()
            app.croptop.feature.editor.session.ActionOutcome.Completed("$action completed")
        }
    }
    // ---- Files: Save / Save As / Export and close guard ----
    var confirmClose by remember { mutableStateOf(false) }
    var sizeDialog by remember { mutableStateOf<String?>(null) }
    // Free Transform / Transform Selection: while active it takes over pointer input and the options bar.
    var transform by remember { mutableStateOf<app.croptop.feature.editor.tools.TransformTool?>(null) }
    var colorRange by remember { mutableStateOf<app.croptop.feature.editor.tools.ColorRangeMode?>(null) }
    val refineBrushTool = remember(refineSession) {
        refineSession?.let { session ->
            app.croptop.feature.editor.tools.RefineEdgeBrushTool(
                app.croptop.feature.editor.tools.RefineEdgeBrushController(session, refineBrushSpec),
            ) { vm.reportUiCommandResult("Select and Mask brush", it) }
        }
    }
    val filterMaskTarget by vm.filterMaskPaintTarget.collectAsStateWithLifecycle()
    val filterMaskTool = remember(filterMaskTarget) {
        filterMaskTarget?.let { layer ->
            app.croptop.feature.editor.tools.FilterMaskBrushTool(
                paint = { points, radius, reveal -> vm.paintSmartFilterMask(layer, points, radius, reveal) },
                onDone = vm::endFilterMaskPaint,
            )
        }
    }
    val activeController: ToolController? = transform ?: colorRange ?: refineBrushTool ?: filterMaskTool ?: controllers[tool]
    DisposableEffect(activeController) {
        onDispose {
            (activeController as? app.croptop.feature.editor.tools.MagneticLassoTool)?.cancel()
            (activeController as? app.croptop.feature.editor.tools.RefineEdgeBrushTool)?.onFocusLost()
        }
    }

    /** Whether the last committed Free Transform targeted the selection, and its box. */
    val percentKeys = remember { app.croptop.feature.editor.tools.PercentKeys() }
    val flowKeys = remember { app.croptop.feature.editor.tools.PercentKeys() }
    var lastTransform by remember { mutableStateOf<Pair<Boolean, app.croptop.feature.editor.tools.TransformBox>?>(null) }
    fun startTransform(target: app.croptop.core.engine.api.TransformTarget, box: app.croptop.core.model.DocRect?, label: String, warp: Boolean = false) {
        if (transform != null) {
            vm.reportUiActionRejected(
                label,
                app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                "Another transform is already active",
            )
            return
        }
        if (box == null || box.isEmpty) {
            vm.reportUiActionRejected(
                label,
                app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                "There is no non-empty selection or layer to transform",
            )
            return
        }
        val session = vm.beginTransform(target) ?: run {
            vm.reportUiActionFailed(label, "The transform could not be started")
            return
        }
        transform = app.croptop.feature.editor.tools.TransformTool(
            session,
            app.croptop.core.model.DocRectF.of(box),
            label,
            onCommit = { vm.commitTransform(it, label) },
            onEnd = { transform = null },
            onCommitted = { lastTransform = (target is app.croptop.core.engine.api.TransformTarget.Selection) to it },
            canvas = state.info?.size?.takeIf { target is app.croptop.core.engine.api.TransformTarget.Layers && target.ids.size == 1 },
            onWarpCommit = { mesh -> (target as? app.croptop.core.engine.api.TransformTarget.Layers)?.ids?.singleOrNull()?.let { vm.warpLayer(it, mesh) } == true },
            startInWarp = warp,
        ).apply {
            onActionCompleted = vm::reportUiActionCompleted
            viewAidsForSnap = { vm.viewAids.value }
            otherLayerBounds = {
                val movedIds = (target as? app.croptop.core.engine.api.TransformTarget.Layers)?.ids.orEmpty()
                vm.state.value.layers.flatten().filterNot { it.id in movedIds }.mapNotNull { it.bounds }
            }
            selectionBoundsForSnap = {
                if (target is app.croptop.core.engine.api.TransformTarget.Layers) {
                    (vm.state.value.selection as? app.croptop.core.model.SelectionInfo.Active)?.bounds
                } else {
                    null
                }
            }
        }
        vm.reportUiActionCompleted("Start $label")
    }

    /** Edit ▸ Transform Again: repeats the last committed transform on the current layers or selection. */
    fun transformAgain(target: app.croptop.core.engine.api.TransformTarget, box: app.croptop.core.model.DocRect?) {
        val last = lastTransform?.second ?: run {
            vm.reportUiActionRejected(
                "Transform Again",
                app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                "There is no previous transform to repeat",
            )
            return
        }
        if (box == null || box.isEmpty) {
            vm.reportUiActionRejected(
                "Transform Again",
                app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                "There is no non-empty selection or layer to transform",
            )
            return
        }
        val session = vm.beginTransform(target) ?: run {
            vm.reportUiActionFailed("Transform Again", "The transform could not be started")
            return
        }
        session.update(last.repeatOn(app.croptop.core.model.DocRectF.of(box)).matrix)
        vm.commitTransform(session, "Transform Again")
    }
    var modifyDialog by remember { mutableStateOf<app.croptop.core.model.SelectionModify?>(null) }
    var fillDialog by remember { mutableStateOf(false) }
    var fillInitial by remember { mutableStateOf(app.croptop.feature.editor.dialogs.FillContents.FOREGROUND) }
    var fillLayerTarget by remember { mutableStateOf<LayerId?>(null) }
    var fillLayerInitial by remember { mutableStateOf<app.croptop.core.model.FillLayerInfo?>(null) }
    var replaceSmartObjectTarget by remember { mutableStateOf<LayerId?>(null) }
    var definePatternDialog by remember { mutableStateOf(false) }
    var patternLibraryDialog by remember { mutableStateOf(false) }
    var colorProfileConvert by remember { mutableStateOf<Boolean?>(null) }
    var pendingOpenWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    val dismissedWarningDocuments = remember { mutableSetOf<Long>() }
    LaunchedEffect(activeDocument?.id, activeDocument?.openWarnings) {
        val documentId = activeDocument?.id?.value
        val warnings = activeDocument?.openWarnings.orEmpty()
        if (documentId != null && warnings.isNotEmpty() && dismissedWarningDocuments.add(documentId)) {
            pendingOpenWarnings = warnings
        }
    }

    /** Tab on the canvas / View ▸ Hide Panels: tools, options bar and panels step aside for the image. */
    var panelsHidden by rememberSaveable { mutableStateOf(false) }

    /** F cycles Standard → Full Screen (system bars hidden) → Full Screen without panels. */
    var screenMode by rememberSaveable { mutableStateOf(0) }
    var canvasFocused by remember { mutableStateOf(false) }
    var canvasCanFocus by remember { mutableStateOf(false) }
    val canvasFocusRequester = remember { FocusRequester() }
    LaunchedEffect(canvasCanFocus) {
        if (canvasCanFocus) canvasFocusRequester.requestFocus()
    }
    LaunchedEffect(refineSession) {
        if (refineSession != null) canvasCanFocus = true
    }
    val moveNudgeBurst = remember(vm) {
        app.croptop.feature.editor.tools.MoveNudgeBurst(vm::translateSelected)
    }
    LaunchedEffect(tool) {
        if (tool != ToolId.MOVE) moveNudgeBurst.flush()
        // Leaving Liquify applies the previewed warp, like pressing OK.
        if (tool != ToolId.LIQUIFY) (controllers[ToolId.LIQUIFY] as? app.croptop.feature.editor.tools.LiquifyToolController)?.commit()
    }
    DisposableEffect(vm) {
        onDispose(moveNudgeBurst::flush)
    }
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(screenMode) {
        val controller = hostView.findActivityWindow()?.insetsController
        if (screenMode == 0) {
            controller?.show(android.view.WindowInsets.Type.systemBars())
        } else {
            controller?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(android.view.WindowInsets.Type.systemBars())
        }
        // Leaving the editor always restores the system bars.
        onDispose { controller?.show(android.view.WindowInsets.Type.systemBars()) }
    }
    var adjustmentDialog by remember { mutableStateOf<app.croptop.core.model.AdjustmentSpec?>(null) }
    var adjustmentCatalogDialog by remember { mutableStateOf(false) }
    var filterCatalogDialog by remember { mutableStateOf(false) }
    var lastFilterId by rememberSaveable { mutableStateOf<String?>(null) }
    var lensBlurDialog by remember { mutableStateOf(false) }
    var aiRestoreDialog by remember { mutableStateOf<app.croptop.core.ai.AiFeature?>(null) }
    var proofSetupDialog by remember { mutableStateOf(false) }
    var newAdjustmentDialog by remember { mutableStateOf(false) }
    var adjustmentPreview by remember { mutableStateOf<app.croptop.feature.editor.dialogs.AdjustmentPreviewController?>(null) }
    androidx.compose.runtime.DisposableEffect(adjustmentPreview) {
        // Capture this key's controller: reading the state inside onDispose would see the *new* controller
        // (the effect for the old key is disposed after the state changed) and close it immediately.
        val controller = adjustmentPreview
        onDispose { controller?.close() }
    }
    fun addAdjustment(spec: app.croptop.core.model.AdjustmentSpec) {
        vm.addAdjustmentLayer(spec.id)
    }
    fun openAdjustment(id: String) {
        val spec = (vm.adjustmentSpecs + vm.filterSpecs).firstOrNull { it.id == id } ?: run {
            vm.reportUiActionFailed("Open adjustment", "Unknown adjustment: $id")
            return
        }
        val isFilter = vm.filterSpecs.any { it.id == spec.id }
        val itemType = if (isFilter) "filter" else "adjustment"
        if (!vm.supportsDestructiveAdjustment(spec.id)) {
            vm.reportUiActionUnsupported(spec.name, "This engine build does not support this $itemType")
            return
        }
        val open = {
            val previewRegion = if (isFilter && docSize.width > 0 && docSize.height > 0) {
                val side = minOf(docSize.width, docSize.height, with(density) { 240.dp.roundToPx() }, 512)
                val left = (docSize.width - side) / 2
                val top = (docSize.height - side) / 2
                app.croptop.core.model.DocRect(left, top, left + side, top + side)
            } else {
                null
            }
            adjustmentPreview = vm.beginDestructiveAdjustmentPreview(spec.id)?.let {
                app.croptop.feature.editor.dialogs.AdjustmentPreviewController(it, previewRegion)
            }
            if (adjustmentPreview != null) {
                adjustmentDialog = spec
                vm.reportUiActionCompleted("Open ${spec.name} $itemType")
            } else {
                vm.reportUiActionFailed(spec.name, "The $itemType preview could not be started")
            }
        }
        open()
    }
    fun openLensBlur() {
        when {
            state.primaryLayer == null -> vm.reportUiActionRejected(
                "Open Lens Blur",
                app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                "Select a pixel layer first",
            )

            state.primaryLayer?.kind != app.croptop.core.model.LayerKind.PIXEL -> vm.reportUiActionRejected(
                "Open Lens Blur",
                app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                "Lens Blur requires a pixel layer",
            )

            else -> lensBlurDialog = true
        }
    }
    var showExport by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportOptions?>(null) }
    var closeAfterSave by remember { mutableStateOf(false) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CROPTOP_MIME)) { uri ->
        val thenClose = closeAfterSave
        if (uri != null) {
            // Keep access across restarts so the project reopens from Recent (and later Saves can write it).
            runCatching {
                hostView.context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { android.util.Log.w("CropTopSave", "Could not persist access to $uri", it) }
            vm.save(uri.toString()) { if (thenClose) onClose() }
        } else {
            vm.reportUiActionCompleted("Save As canceled")
        }
        closeAfterSave = false
    }
    val replaceSmartObjectSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = replaceSmartObjectTarget
        replaceSmartObjectTarget = null
        if (uri == null || target == null) {
            vm.reportUiActionCompleted("Replace Smart Object Contents canceled")
        } else {
            runCatching {
                hostView.context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.smartObjectReplacementRoute(target, uri.toString())?.let { route ->
                onOpenSmartObjectContents?.invoke(route)
            }
        }
    }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val opts = pendingExport
        if (uri != null && opts != null) {
            vm.export(uri.toString(), opts)
        } else {
            vm.reportUiActionCompleted("Export canceled")
        }
        pendingExport = null
    }
    val quickExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            vm.export(uri.toString(), ExportOptions(ExportFormat.PNG))
        } else {
            vm.reportUiActionCompleted("Quick export canceled")
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val baseName = (state.title ?: state.info?.name ?: "Untitled").substringBeforeLast('.')
    fun save(thenClose: Boolean = false) {
        if (route.smartObjectParentDocumentId != null && route.smartObjectParentLayerId != null) {
            vm.saveSmartObjectContents(onDone = onClose)
            return
        }
        val target = state.saveTarget
        if (target != null) {
            vm.save(target) { if (thenClose) onClose() }
        } else {
            closeAfterSave = thenClose
            vm.reportUiActionCompleted("Choose Save As location")
            saveAs.launch("$baseName.croptop")
        }
    }
    fun requestClose() {
        if (state.dirty) {
            confirmClose = true
            vm.reportUiActionCompleted("Review unsaved changes before closing")
        } else {
            onClose()
        }
    }
    LaunchedEffect(closeTabOnActivation) {
        if (closeTabOnActivation) {
            onCloseTabRequestConsumed()
            requestClose()
        }
    }

    /** Hands the document to a new window (multi-instance); unsaved work must be saved first. */
    fun moveToNewWindow() {
        val uri = state.saveTarget ?: route.sourceUri
        when {
            onOpenInNewWindow == null -> vm.reportUiActionUnsupported(
                "Move to New Window",
                "Opening a document in a new window is unavailable here",
            )

            state.dirty || uri == null -> {
                val detail = "Save the document first to move it to a new window"
                vm.reportUiActionRejected("Move to New Window", app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT, detail)
            }

            else -> {
                onOpenInNewWindow(uri)
                vm.reportUiActionCompleted("Move to New Window")
                onClose()
            }
        }
    }
    // Some Android builds map a mouse right-click to system Back. If a mouse was last over the image,
    // consume that fallback so a context click cannot close a clean document.
    val mouseHoveringOnCanvas = hover != null &&
        hoverPointerKind in setOf(
            app.croptop.core.input.PointerKind.MOUSE,
            app.croptop.core.input.PointerKind.TOUCHPAD,
        )
    BackHandler(enabled = state.dirty || mouseHoveringOnCanvas) {
        if (state.dirty) {
            confirmClose = true
            vm.reportUiActionCompleted("Review unsaved changes before closing")
        } else {
            val detail = "This device maps right-click to Back; enable direct mouse input for canvas context menus"
            vm.showMessage(detail)
            vm.reportUiActionUnsupported("Open canvas context menu", detail)
        }
    }

    ActionScope(ShortcutScope.EDITOR) {
        on("app.save") { save() }
        on("app.saveAs") {
            if (route.smartObjectParentDocumentId != null) {
                save()
            } else {
                vm.reportUiActionCompleted("Choose Save As location")
                saveAs.launch("$baseName.croptop")
            }
        }
        on("app.exportAs") {
            showExport = true
            vm.reportUiActionCompleted("Open export options")
        }
        on("app.quickExport") {
            vm.reportUiActionCompleted("Choose quick export location")
            quickExport.launch("$baseName.png")
        }
        on("app.share") {
            scope.launch {
                val uri = vm.exportForSharing("Share image") ?: return@launch
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri.toUri())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(Intent.createChooser(send, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    vm.reportUiActionCompleted("Share options opened")
                } catch (failure: android.content.ActivityNotFoundException) {
                    val detail = "No installed app can share this image"
                    vm.showMessage(detail)
                    vm.reportUiActionFailed("Share image", detail)
                }
            }
        }
        fun resizeBrush(up: Boolean) {
            val action = if (up) "Increase brush size" else "Decrease brush size"
            val changed = when (val c = controllers[tool]) {
                is app.croptop.feature.editor.tools.BrushTool -> brushSettings.value.let {
                    val next = app.croptop.feature.editor.tools.stepBrushSize(it.size, up)
                    if (next == it.size) {
                        false
                    } else {
                        brushUi.onSettingsPreview(it.copy(size = next))
                        true
                    }
                }

                is app.croptop.feature.editor.tools.QuickSelectionTool -> {
                    val next = app.croptop.feature.editor.tools.stepBrushSize(c.size, up)
                    (next != c.size).also { if (it) c.size = next }
                }

                is app.croptop.feature.editor.tools.RetouchTool -> {
                    val next = app.croptop.feature.editor.tools.stepBrushSize(c.size, up)
                    (next != c.size).also { if (it) c.size = next }
                }

                else -> {
                    vm.reportUiActionUnsupported(action, "The active tool does not have a brush size")
                    return
                }
            }
            if (changed) {
                vm.reportUiActionCompleted(action)
            } else {
                vm.reportUiActionCompleted("Brush size is already at its limit")
            }
        }
        fun setPercent(flow: Boolean, pct: Int) {
            val v = pct / 100f
            when (val c = controllers[tool]) {
                is app.croptop.feature.editor.tools.BrushTool -> brushSettings.value.let {
                    brushUi.onSettingsPreview(if (flow) it.copy(flow = v) else it.copy(opacity = v))
                }

                is app.croptop.feature.editor.tools.RetouchTool -> if (!flow) c.strength = v

                is app.croptop.feature.editor.tools.PaintBucketTool -> if (!flow) c.opacity = v

                is app.croptop.feature.editor.tools.GradientTool -> if (!flow) c.opacity = v

                else -> {
                    vm.reportUiActionUnsupported(if (flow) "Change flow" else "Change opacity", "The active tool does not support this setting")
                    return
                }
            }
            vm.showMessage("${if (flow) "Flow" else "Opacity"} $pct%")
            vm.reportUiActionCompleted("Set ${if (flow) "flow" else "opacity"} to $pct%")
        }
        for (pct in 10..100 step 10) {
            val digit = pct / 10 % 10
            on("brush.opacity$pct") { setPercent(flow = false, percentKeys.press(digit, System.currentTimeMillis())) }
            on("brush.flow$pct") { setPercent(flow = true, flowKeys.press(digit, System.currentTimeMillis())) }
        }
        on("brush.sizeDown") { resizeBrush(up = false) }
        on("brush.sizeUp") { resizeBrush(up = true) }
        on("edit.copy") { vm.copy() }
        on("edit.cut") { vm.cut() }
        if (clipboard != null) {
            on("edit.paste") { vm.paste(viewport.viewToDoc(viewCenter())) }
            on("edit.pasteInPlace") { vm.paste(null) }
        }
        on("edit.copyMerged") {
            scope.launch {
                val cm = context.getSystemService(ClipboardManager::class.java)
                if (cm == null) {
                    vm.reportUiActionFailed("Copy merged image to clipboard", "Clipboard service is unavailable")
                    return@launch
                }
                val copied = app.croptop.feature.editor.session.copyMergedImageToClipboard(
                    prepareImage = { vm.exportForSharing("Copy merged image to clipboard") },
                    copyMerged = { vm.copy(merged = true, quietSuccess = true) },
                    publishToPlatformClipboard = { uri ->
                        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, baseName, uri.toUri()))
                    },
                )
                if (copied) {
                    vm.showMessage("Copied to clipboard")
                    vm.reportUiActionCompleted("Copy merged image to clipboard")
                }
            }
        }
        ToolGroup.entries.forEach { g -> on(toolAction(g)) { select(lastInGroup[g] ?: ToolId.inGroup(g).first()) } }
        onCycleTool { g -> select(ToolId.cycle(lastInGroup[g] ?: ToolId.inGroup(g).first())) }
        on("app.close") { requestClose() }
        on("view.zoomIn") {
            viewport = viewport.stepZoom(viewCenter(), zoomIn = true)
            vm.reportUiActionCompleted("Zoom in")
        }
        on("view.zoomOut") {
            viewport = viewport.stepZoom(viewCenter(), zoomIn = false)
            vm.reportUiActionCompleted("Zoom out")
        }
        on("view.fit") {
            viewport = viewport.fit(docSize)
            vm.reportUiActionCompleted("Fit image")
        }
        on("view.actualPixels") {
            viewport = viewport.actualSize(docSize)
            vm.reportUiActionCompleted("Show actual pixels")
        }
        on("view.proofColors") { vm.toggleProofColors() }
        on("view.gamutWarning") { vm.toggleGamutWarning() }
        on("view.proofSetup") { proofSetupDialog = true }
        on("view.rulers") { toggleAids("Toggle rulers") { it.copy(rulers = !it.rulers) } }
        on("view.grid") { toggleAids("Toggle grid") { it.copy(grid = !it.grid) } }
        on("view.guides") { toggleAids("Toggle guides") { it.copy(showGuides = !it.showGuides) } }
        on("view.snap") { toggleAids("Toggle snapping") { it.copy(snap = !it.snap) } }
        on("view.lockGuides") { toggleAids("Toggle guide lock") { it.copy(lockGuides = !it.lockGuides) } }
        on("view.clearGuides") { toggleAids("Clear guides") { it.copy(guides = emptyList()) } }
        on("view.newGuide") {
            aidsDialog = "guide"
            vm.reportUiActionCompleted("Open new guide")
        }
        on("view.newGuideLayout") {
            aidsDialog = "layout"
            vm.reportUiActionCompleted("Open guide layout")
        }
        on("view.gridSettings") {
            aidsDialog = "grid"
            vm.reportUiActionCompleted("Open grid settings")
        }
        on("edit.undo") { vm.undo() }
        on("edit.definePattern") {
            definePatternDialog = true
            vm.reportUiActionCompleted("Open Define Pattern")
        }
        on("edit.patternLibrary") {
            patternLibraryDialog = true
            vm.reportUiActionCompleted("Open Pattern Library")
        }
        on("edit.redo") { vm.redo() }
        on("edit.stepBack") { vm.undo() }
        on("layer.new") { vm.newLayer() }
        on("layer.group") { vm.groupSelected() }
        on("layer.delete") { vm.deleteOrClear() }
        on("layer.viaCut") { vm.layerViaCut() }
        on("layer.ungroup") { vm.ungroup() }
        on("layer.selectBelow") { vm.selectAdjacent(below = true) }
        on("layer.selectAbove") { vm.selectAdjacent(below = false) }
        on("layer.moveUp") { vm.restack(-1) }
        on("layer.moveDown") { vm.restack(1) }
        on("layer.toTop") { vm.restack(-1, toEnd = true) }
        on("layer.toBottom") { vm.restack(1, toEnd = true) }
        on("layer.newFillSolid") {
            fillLayerTarget = null
            fillLayerInitial = vm.newFillLayerInfo(app.croptop.core.model.FillKind.SOLID)
            vm.reportUiActionCompleted("Open Solid Color Fill Layer")
        }
        on("layer.newFillGradient") {
            fillLayerTarget = null
            fillLayerInitial = vm.newFillLayerInfo(app.croptop.core.model.FillKind.GRADIENT)
            vm.reportUiActionCompleted("Open Gradient Fill Layer")
        }
        on("layer.newFillPattern") {
            fillLayerTarget = null
            fillLayerInitial = vm.newFillLayerInfo(app.croptop.core.model.FillKind.PATTERN)
                .copy(patternId = patterns.firstOrNull()?.id)
            vm.reportUiActionCompleted("Open Pattern Fill Layer")
        }
        on("layer.mergeDown") { vm.mergeDown() }
        on("layer.mergeVisible") { vm.mergeVisible() }
        on("layer.duplicate") { vm.layerViaCopy() }
        on("layer.newAdjustment") {
            newAdjustmentDialog = true
            vm.reportUiActionCompleted("Open new adjustment layer")
        }
        on("edit.fill") {
            fillInitial = app.croptop.feature.editor.dialogs.FillContents.FOREGROUND
            fillDialog = true
            vm.reportUiActionCompleted("Open Fill")
        }
        on("edit.contentAwareFill") {
            fillInitial = app.croptop.feature.editor.dialogs.FillContents.CONTENT_AWARE
            fillDialog = true
            vm.reportUiActionCompleted("Open Content-Aware Fill")
        }
        on("image.desaturate") {
            // Photoshop's Desaturate is Hue/Saturation with the master saturation at −100.
            val hs = app.croptop.core.model.HueSaturation(master = app.croptop.core.model.HueShift(saturation = -100f))
            vm.applyAdjustment(
                "hueSaturation",
                mapOf("hueSat" to app.croptop.core.model.ParamValue.HueSatValue(hs)),
                action = "Desaturate",
            )
        }
        on("view.hidePanels") {
            panelsHidden = !panelsHidden
            vm.reportUiActionCompleted(if (panelsHidden) "Hide panels" else "Show panels")
        }
        on("view.screenMode") {
            screenMode = (screenMode + 1) % 3
            vm.reportUiActionCompleted("Change screen mode")
        }
        fun changeHardness(up: Boolean) {
            fun step(h: Float) = ((h + if (up) 0.25f else -0.25f) * 4f).let { kotlin.math.round(it) / 4f }.coerceIn(0f, 1f)
            when (val c = controllers[tool]) {
                is app.croptop.feature.editor.tools.BrushTool -> brushSettings.value.let {
                    brushUi.onSettings(it.copy(hardness = step(it.hardness)))
                    vm.reportUiActionCompleted("Change brush hardness")
                }

                is app.croptop.feature.editor.tools.RetouchTool -> {
                    c.hardness = step(c.hardness)
                    vm.reportUiActionCompleted("Change retouch hardness")
                }

                else -> {
                    vm.reportUiActionUnsupported("Change hardness", "The active tool does not have a hardness setting")
                    return
                }
            }
        }
        on("brush.hardnessDown") { changeHardness(up = false) }
        on("brush.hardnessUp") { changeHardness(up = true) }
        on("edit.fillForeground") { vm.fillWith(foreground = true) }
        on("edit.fillBackground") { vm.fillWith(foreground = false) }
        on("image.levels") { openAdjustment("levels") }
        on("image.curves") { openAdjustment("curves") }
        on("image.hueSat") { openAdjustment("hueSaturation") }
        on("image.colorBalance") { openAdjustment("colorBalance") }
        on("filter.last") {
            lastFilterId?.let(::openAdjustment)
                ?: vm.reportUiActionRejected(
                    "Last Filter",
                    app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                    "Apply a filter first to use Last Filter",
                )
        }
        on("image.invert") { vm.applyAdjustment("invert", emptyMap()) }
        on("layer.stampVisible") { vm.stampVisible() }
        fun addMaskFromSelection(fill: app.croptop.core.engine.api.MaskFill, action: String) {
            val layer = state.primaryLayer
            when {
                layer == null -> vm.reportUiActionRejected(
                    action,
                    app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                    "Select a layer before adding a selection mask",
                )

                !state.hasSelection -> vm.reportUiActionRejected(
                    action,
                    app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                    "Create a selection before adding a mask from it",
                )

                layer.mask != null -> vm.reportUiActionRejected(
                    action,
                    app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                    "Remove the existing layer mask before adding a selection mask",
                )

                else -> vm.addMask(id = layer.id, explicit = fill)
            }
        }
        on("layer.maskRevealSelection") {
            addMaskFromSelection(app.croptop.core.engine.api.MaskFill.REVEAL_SELECTION, "Layer Mask: Reveal Selection")
        }
        on("layer.maskHideSelection") {
            addMaskFromSelection(app.croptop.core.engine.api.MaskFill.HIDE_SELECTION, "Layer Mask: Hide Selection")
        }
        state.primaryLayer?.let { layer ->
            on("layer.clippingMask") { vm.toggleClipping(layer.id) }
            on("layer.maskApply") { vm.deleteMask(layer.id, apply = true) }
            if (layer.kind == LayerKind.PIXEL || layer.kind == LayerKind.TEXT) {
                on("layer.convertSmartObject") { vm.convertToSmartObject() }
            }
            if (layer.kind == LayerKind.SMART_OBJECT) {
                on("layer.rasterizeSmartObject") { vm.rasterizeSmartObject(layer.id) }
            }
            if (layer.mask == null) {
                on("layer.maskRevealAll") { vm.addMask(id = layer.id, explicit = app.croptop.core.engine.api.MaskFill.REVEAL_ALL) }
                on("layer.maskHideAll") { vm.addMask(id = layer.id, explicit = app.croptop.core.engine.api.MaskFill.HIDE_ALL) }
            } else {
                on("layer.maskToggle") { vm.toggleMask(layer.id) }
                on("layer.maskDelete") { vm.deleteMask(layer.id, apply = false) }
            }
        }
        // Select menu: items appear enabled only when they apply (Photoshop behavior).
        on("select.all") { vm.selectAll() }
        on("select.saveSelection") {
            if (state.hasSelection) {
                vm.saveSelection()
            } else {
                vm.reportUiActionRejected(
                    "Save Selection",
                    app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                    "Create a selection before saving it.",
                )
            }
        }
        on("select.loadSelection") { vm.loadSelection() }
        on("select.subject") { vm.selectSubject() }
        on("select.sky") { vm.selectSky() }
        on("edit.removeAi") { vm.removeSelection() }
        on("select.background") { vm.selectBackground() }
        on("select.removeBackground") { vm.removeBackground() }
        on("select.quickMask") { vm.toggleQuickMask() }
        on("select.selectAndMask") { vm.beginRefineEdge()?.let { refineSession = it } }
        if (transform == null && colorRange == null) {
            on("select.colorRange") {
                val session = vm.beginColorRange()
                if (session != null) {
                    colorRange =
                        app.croptop.feature.editor.tools.ColorRangeMode(
                            session,
                            { p -> vm.sample(p) },
                            vm::commitColorRange,
                        ) { colorRange = null }.apply {
                            onActionCompleted = vm::reportUiActionCompleted
                        }
                    vm.reportUiActionCompleted("Start color range selection")
                }
            }
        }
        if (state.hasSelection) {
            on("select.deselect") { vm.deselect() }
            on("select.inverse") { vm.invertSelection() }
            on("image.crop") { vm.cropToSelection() }
            app.croptop.core.model.SelectionModify.entries.forEach { k ->
                on("select.${k.name.lowercase()}") {
                    modifyDialog = k
                    vm.reportUiActionCompleted("Open ${k.name.lowercase()} selection dialog")
                }
            }
        } else {
            on("select.reselect") { vm.reselect() }
        }
        on("image.imageSize") {
            sizeDialog = "image"
            vm.reportUiActionCompleted("Open Image Size")
        }
        on("image.superResolution") { aiRestoreDialog = app.croptop.core.ai.AiFeature.UPSCALE }
        on("filter.denoiseAi") { aiRestoreDialog = app.croptop.core.ai.AiFeature.DENOISE }
        if (state.selected.isNotEmpty()) {
            on("edit.freeTransform") {
                val size = state.info?.size
                val box = size?.let {
                    app.croptop.feature.editor.tools.selectedLayerBounds(
                        state.selected.mapNotNull { id -> state.layers.find(id) }.map { it.bounds },
                        app.croptop.core.model.DocRect.of(it),
                    )
                }
                startTransform(app.croptop.core.engine.api.TransformTarget.Layers(state.selected), box, "Free Transform")
            }
            on("edit.warp") {
                val size = state.info?.size
                val box = size?.let {
                    app.croptop.feature.editor.tools.selectedLayerBounds(
                        state.selected.mapNotNull { id -> state.layers.find(id) }.map { it.bounds },
                        app.croptop.core.model.DocRect.of(it),
                    )
                }
                startTransform(app.croptop.core.engine.api.TransformTarget.Layers(state.selected), box, "Warp", warp = true)
            }
            if (lastTransform?.first == false) {
                on("edit.transformAgain") {
                    val size = state.info?.size
                    val box = size?.let {
                        app.croptop.feature.editor.tools.selectedLayerBounds(
                            state.selected.mapNotNull { id -> state.layers.find(id) }.map { it.bounds },
                            app.croptop.core.model.DocRect.of(it),
                        )
                    }
                    transformAgain(app.croptop.core.engine.api.TransformTarget.Layers(state.selected), box)
                }
            }
        }
        (state.selection as? app.croptop.core.model.SelectionInfo.Active)?.let { sel ->
            on("select.transform") { startTransform(app.croptop.core.engine.api.TransformTarget.Selection, sel.bounds, "Transform Selection") }
            if (lastTransform?.first == true) {
                on("edit.transformAgain") { transformAgain(app.croptop.core.engine.api.TransformTarget.Selection, sel.bounds) }
            }
        }
        on("image.canvasSize") {
            sizeDialog = "canvas"
            vm.reportUiActionCompleted("Open Canvas Size")
        }
        on("image.assignProfile") {
            colorProfileConvert = false
            vm.reportUiActionCompleted("Open Assign Color Profile")
        }
        on("image.convertProfile") {
            colorProfileConvert = true
            vm.reportUiActionCompleted("Open Convert Color Profile")
        }
        on("image.rotate180") { vm.rotateCanvas(180f) }
        on("image.rotate90cw") { vm.rotateCanvas(90f) }
        on("image.rotate90ccw") { vm.rotateCanvas(-90f) }
        on("image.flipH") { vm.flipCanvas(horizontal = true) }
        on("image.flipV") { vm.flipCanvas(horizontal = false) }
        on("color.swap") { vm.swapColors() }
        on("color.default") { vm.defaultColors() }
        DockPanel.entries.forEach { p ->
            on(windowPanelAction(p)) {
                setWorkspace(layout.update(p) { it.copy(visible = !it.visible) }, "Toggle ${p.title} panel")
            }
        }
        WorkspaceLayout.Presets.forEach { w -> on(workspaceAction(w.name)) { setWorkspace(w, "Use ${w.name} workspace") } }
        if (onSaveWorkspace != null) {
            on("window.saveWorkspace") {
                showSaveWorkspace = true
                vm.reportUiActionCompleted("Open save workspace")
            }
        }
        customWorkspaces.forEach { w -> on(workspaceAction(w.name)) { setWorkspace(w, "Use ${w.name} workspace") } }
        on("window.resetWorkspace") {
            setWorkspace(
                WorkspaceLayout.Presets.firstOrNull { it.name == layout.name } ?: WorkspaceLayout.Essentials,
                "Reset workspace layout",
            )
        }
    }
    val docName = state.title ?: state.info?.name ?: route.newSpec?.name ?: "Untitled"
    androidx.compose.runtime.SideEffect { onDocumentTabInfo(route, docName, state.dirty) }

    val colorsForFill by vm.colors.collectAsStateWithLifecycle()
    val documentFill = when (route.newSpec?.background) {
        app.croptop.core.model.BackgroundContents.WHITE -> androidx.compose.ui.graphics.Color.White

        app.croptop.core.model.BackgroundContents.BLACK -> androidx.compose.ui.graphics.Color.Black

        app.croptop.core.model.BackgroundContents.BACKGROUND_COLOR -> colorsForFill.background.let {
            androidx.compose.ui.graphics.Color(it.r, it.g, it.b)
        }

        else -> null
    }
    CompositionLocalProvider(app.croptop.feature.editor.canvas.LocalDocumentFill provides documentFill) {
        Column(modifier.fillMaxSize().background(c.app)) {
            MenuBar(
                checked = { id -> isChecked(id, layout) || isAidChecked(id, aids) || isProofChecked(id, softProof) },
                extra = { menu -> if (menu == "Window") customWorkspaces.map { workspaceAction(it.name) } else emptyList() },
                onAdjustmentCatalog = {
                    adjustmentCatalogDialog = true
                    vm.reportUiActionCompleted("Open adjustment catalog")
                },
                onFilterCatalog = {
                    filterCatalogDialog = true
                    vm.reportUiActionCompleted("Open filters")
                },
                onLensBlur = ::openLensBlur,
            )
            AeDivider()
            val closeLabel = shortcutLabel("app.close")
            DocumentTabs(
                tabs = documentTabs,
                currentRoute = route,
                currentName = docName,
                currentDirty = state.dirty,
                onSelect = onSelectDocumentTab,
                onClose = onCloseDocumentTab,
                onMove = onMoveDocumentTab,
                onDragExport = {
                    scope.launch {
                        val uri = vm.exportForSharing("Drag image to another app") ?: return@launch
                        val clip = ClipData.newUri(context.contentResolver, baseName, uri.toUri())
                        val started = hostView.startDragAndDrop(
                            clip,
                            android.view.View.DragShadowBuilder(hostView),
                            null,
                            android.view.View.DRAG_FLAG_GLOBAL or android.view.View.DRAG_FLAG_GLOBAL_URI_READ,
                        )
                        if (!started) {
                            val detail = "Couldn't start image drag"
                            vm.showMessage(detail)
                            vm.reportUiActionFailed("Drag image to another app", detail)
                        } else {
                            vm.reportUiActionCompleted("Drag image to another app")
                        }
                    }
                },
            ) { tabRoute ->
                if (tabRoute == route) {
                    listOf(
                        AeMenuEntry.Item("Move to New Window", ::moveToNewWindow),
                        AeMenuEntry.Item("Close", ::requestClose, closeLabel),
                    )
                } else {
                    listOf(AeMenuEntry.Item("Close", { onCloseDocumentTab(tabRoute) }))
                }
            }
            val chromeHidden = panelsHidden || screenMode == 2
            if (!chromeHidden) AeDivider()
            if (!chromeHidden) {
                OptionsBar(
                    tool,
                    activeController,
                    viewport,
                    typeUi,
                    brushUi,
                    transforming = transform != null || colorRange != null,
                    paintingMask = maskTarget != null && maskTarget == state.primary && state.primaryLayer?.mask != null,
                    onActionCompleted = vm::reportUiActionCompleted,
                    onViewport = { viewport = it },
                )
            }
            if (!chromeHidden) {
                val target = state.primaryLayer
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Ae.density.controlHeight + 8.dp).background(Ae.colors.surface2).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val targetLabel = when {
                        editingSelectionMask -> "Editing selection mask"
                        target == null -> "No layer selected"
                        maskTarget == target.id && target.mask != null -> "Editing mask · ${target.name}"
                        target.kind == app.croptop.core.model.LayerKind.PIXEL -> "Editing pixels · ${target.name}"
                        target.kind == app.croptop.core.model.LayerKind.SMART_OBJECT -> "Embedded content · ${target.name}"
                        else -> "${target.kind.name.lowercase().replaceFirstChar(Char::uppercase)} · ${target.name}"
                    }
                    app.croptop.core.designsystem.component.AeText(
                        targetLabel, style = Ae.type.caption, modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    if (!editingSelectionMask && target != null && maskTarget == target.id && target.mask != null) {
                        app.croptop.core.designsystem.component.AeButton("Edit pixels", { vm.targetPixels(target.id) })
                    }
                }
            }
            if (!chromeHidden) AeDivider()
            val panelContent: PanelContent = { p ->
                PanelBody(
                    p,
                    state,
                    vm,
                    viewportState,
                    docSize,
                    swatches,
                    typeUi,
                    brushUi,
                    pathDrafts,
                    onCommitPaths = ::commitPathDrafts,
                    onPickLevels = { layerId, target ->
                        adjustmentTarget = null
                        levelsPick = PendingLevelsPick(layerId, target)
                        vm.showMessage("Click the image to set ${target.label.lowercase()} input")
                        vm.reportUiActionCompleted("Pick ${target.label.lowercase()} input from canvas")
                    },
                    onTargetAdjustment = { layerId, specId ->
                        levelsPick = null
                        adjustmentTarget = PendingAdjustmentTarget(layerId, specId)
                        if (specId == "hueSaturation") targetedHueChannel = null
                        vm.showMessage("Click the image to target ${if (specId == "curves") "a tone" else "a color"}")
                        vm.reportUiActionCompleted("Target ${if (specId == "curves") "tone" else "color"} from canvas")
                    },
                    onEditFillLayer = { id ->
                        state.layers.find(id)?.fill?.let { fill ->
                            fillLayerTarget = id
                            fillLayerInitial = fill
                        }
                    },
                    onEditSmartObjectContents = { id ->
                        when {
                            onOpenSmartObjectContents == null -> vm.reportUiActionUnsupported(
                                "Edit Smart Object Contents",
                                "Opening smart object contents in another tab is unavailable here",
                            )

                            else -> vm.openSmartObjectContents(id)?.let(onOpenSmartObjectContents)
                        }
                    },
                    onReplaceSmartObjectContents = { id ->
                        when {
                            onOpenSmartObjectContents == null -> vm.reportUiActionUnsupported(
                                "Replace Smart Object Contents",
                                "Opening replacement contents in another tab is unavailable here",
                            )

                            else -> {
                                replaceSmartObjectTarget = id
                                vm.reportUiActionCompleted("Choose replacement contents for Smart Object")
                                replaceSmartObjectSource.launch(arrayOf("*/*"))
                            }
                        }
                    },
                    targetedHueChannel = targetedHueChannel,
                    onViewport = { viewport = it },
                )
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (!compact && !chromeHidden) {
                    Toolbar(tool, lastInGroup, onSelect = ::select)
                    AeDivider(Modifier.fillMaxHeight(), vertical = true)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    WithRulers(aids.rulers, viewport, { hover }, guideDragger, Modifier.fillMaxSize()) {
                        CanvasViewport(
                            viewport = viewport,
                            onViewportChange = { viewport = it },
                            document = docSize,
                            background = c.canvas,
                            panMode = spaceHeld,
                            tool = activeController,
                            eraserTool = controllers[ToolId.ERASER],
                            eyedropperTool = controllers[ToolId.EYEDROPPER],
                            stylusBarrelAction = inputPreferences.stylusBarrelAction,
                            brushHudSettings = brushSettings.value.takeIf { activeController is app.croptop.feature.editor.tools.BrushTool },
                            onBrushHudSettingsChange = brushUi.onSettings,
                            onHover = {
                                hover = it
                                if (it == null) (controllers[ToolId.EYEDROPPER] as? EyedropperTool)?.clearRing()
                            },
                            onHoverPointerKind = { hoverPointerKind = it },
                            onHoverModifiers = { hoverModifiers = it },
                            interceptor = guideDragger,
                            handle = openDocument,
                            engineOverlays = app.croptop.core.engine.api.OverlayState(
                                selectionBounds = listOfNotNull(
                                    (state.selection as? app.croptop.core.model.SelectionInfo.Active)?.bounds,
                                ),
                                guides = aids.guides,
                                showSelection = state.selection is app.croptop.core.model.SelectionInfo.Active,
                                showGuides = aids.showGuides,
                            ),
                            onToolUnavailableAt = { lastUnavailableTap = it },
                            onToolUnavailable = {
                                vm.reportUiCommandResult(
                                    "Use ${tool.displayName} tool",
                                    app.croptop.core.engine.api.CommandResult.Rejected(
                                        app.croptop.core.engine.api.RejectionCode.UNSUPPORTED,
                                        unavailableMessage(tool),
                                    ),
                                )
                            },
                            onCanvasPick = when {
                                levelsPick != null -> {
                                    val request = levelsPick!!
                                    { point ->
                                        if (vm.sampleLevelsPick(request.layerId, request.target, point)) levelsPick = null
                                    }
                                }

                                adjustmentTarget != null -> {
                                    val request = adjustmentTarget!!
                                    { point ->
                                        val targetResult = vm.targetAdjustmentFromCanvas(request.layerId, request.specId, point)
                                        if (targetResult.accepted) {
                                            if (request.specId == "hueSaturation") targetedHueChannel = targetResult.hueChannel
                                            adjustmentTarget = null
                                        }
                                    }
                                }

                                else -> null
                            },
                            onActionCompleted = vm::reportUiActionCompleted,
                            overlay = {
                                ViewAidsOverlay(
                                    viewport,
                                    docSize,
                                    aids.copy(showGuides = aids.showGuides && openDocument?.rendersViewport != true),
                                    guideDragger.indicator,
                                )
                                val outline by vm.selectionShape.collectAsStateWithLifecycle()
                                app.croptop.feature.editor.tools.SelectionOverlay(
                                    (state.selection as? app.croptop.core.model.SelectionInfo.Active)?.bounds,
                                    outline,
                                    viewport,
                                )
                                val quickSelection = activeController as? app.croptop.feature.editor.tools.QuickSelectionTool
                                if (quickSelection != null) {
                                    quickSelection.Overlay(viewport, hover, hoverModifiers)
                                } else {
                                    activeController?.Overlay(viewport, hover)
                                }
                                app.croptop.feature.editor.tools.SelectionModeCursorOverlay(activeController?.tool, hover, hoverModifiers)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(canvasFocusRequester)
                                .focusProperties { canFocus = canvasCanFocus }
                                .onFocusChanged {
                                    canvasFocused = it.isFocused
                                    if (!it.isFocused) {
                                        canvasCanFocus = false
                                        spaceHeld = false
                                        activeController?.onFocusLost()
                                        moveNudgeBurst.flush()
                                    }
                                }
                                .focusable()
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        canvasCanFocus = true
                                    }
                                }
                                .onPreviewKeyEvent { e ->
                                    // Space = temporary Hand while held.
                                    val lasso = controllers[tool] as? app.croptop.feature.editor.tools.LassoTool
                                    val magneticLasso = controllers[tool] as? app.croptop.feature.editor.tools.MagneticLassoTool
                                    val crop = controllers[tool] as? app.croptop.feature.editor.tools.CropTool
                                    val liquify = controllers[tool] as? app.croptop.feature.editor.tools.LiquifyToolController
                                    val refineBrush = activeController as? app.croptop.feature.editor.tools.RefineEdgeBrushTool
                                    val xf = transform
                                    val marquee = controllers[tool] as? app.croptop.feature.editor.tools.MarqueeTool
                                    when {
                                        // While the text editor is open its field owns the keyboard (typing, Space, Ctrl+Enter, Esc).
                                        textEntry != null -> false

                                        e.key == Key.CapsLock -> {
                                            listOf(ToolId.BRUSH, ToolId.ERASER)
                                                .mapNotNull { controllers[it] as? app.croptop.feature.editor.tools.BrushTool }
                                                .any { it.handlePrecisionCursorKey(e.key, e.type) }
                                        }

                                        // Space during a marquee drag moves the box instead of panning.
                                        e.key == Key.Spacebar && marquee != null && marquee.dragging -> {
                                            marquee.repositioning = e.type == KeyEventType.KeyDown
                                            true
                                        }

                                        e.key == Key.Spacebar -> {
                                            spaceHeld = e.type == KeyEventType.KeyDown
                                            true
                                        }

                                        // Tab with the canvas focused hides the panels (Photoshop); elsewhere Tab moves focus.
                                        shouldTogglePanelsOnTab(
                                            canvasFocused,
                                            e.key == Key.Tab,
                                            e.type == KeyEventType.KeyDown,
                                            e.isShiftPressed,
                                            e.isCtrlPressed,
                                            e.isAltPressed,
                                            e.isMetaPressed,
                                        ) -> {
                                            panelsHidden = !panelsHidden
                                            vm.reportUiActionCompleted(if (panelsHidden) "Hide panels" else "Show panels")
                                            true
                                        }

                                        xf != null && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.Escape) ->
                                            xf.onKey(enter = e.key == Key.Enter)

                                        colorRange != null && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.Escape) ->
                                            colorRange!!.onKey(enter = e.key == Key.Enter)

                                        refineBrush != null && e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                                            if (refineSession != null) {
                                                cancelRefinement = true
                                            }
                                            true
                                        }

                                        liquify != null && e.type == KeyEventType.KeyDown && e.key in setOf(
                                            Key.W,
                                            Key.R,
                                            Key.S,
                                            Key.C,
                                            Key.P,
                                            Key.B,
                                        ) -> {
                                            liquify.freezeMode = null
                                            liquify.operation = when (e.key) {
                                                Key.W -> app.croptop.core.engine.api.LiquifyTool.FORWARD_WARP
                                                Key.R -> app.croptop.core.engine.api.LiquifyTool.RECONSTRUCT
                                                Key.S -> app.croptop.core.engine.api.LiquifyTool.SMOOTH
                                                Key.C -> app.croptop.core.engine.api.LiquifyTool.TWIRL_CLOCKWISE
                                                Key.P -> app.croptop.core.engine.api.LiquifyTool.PUCKER
                                                else -> app.croptop.core.engine.api.LiquifyTool.BLOAT
                                            }
                                            vm.reportUiActionCompleted("Select ${liquify.operation.name.lowercase().replace('_', ' ')} Liquify tool")
                                            true
                                        }

                                        liquify != null && e.type == KeyEventType.KeyDown && (e.key == Key.F || e.key == Key.D) -> {
                                            liquify.freezeMode = if (e.key == Key.F) {
                                                app.croptop.feature.editor.tools.LiquifyFreezeMode.FREEZE
                                            } else {
                                                app.croptop.feature.editor.tools.LiquifyFreezeMode.THAW
                                            }
                                            vm.reportUiActionCompleted("Select ${liquify.freezeMode?.label} Liquify mask brush")
                                            true
                                        }

                                        filterMaskTool != null && e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                                            vm.endFilterMaskPaint()
                                            true
                                        }

                                        liquify != null && liquify.active && e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                                            liquify.cancel()
                                            vm.reportUiActionCompleted("Cancel Liquify")
                                            true
                                        }

                                        liquify != null && liquify.active && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                                            liquify.commit()
                                            true
                                        }

                                        e.type == KeyEventType.KeyDown && e.key == Key.Escape && vm.cancelSelectionJob() -> true

                                        // Group repeats until all arrow keys are released into one history step.
                                        tool == ToolId.MOVE && e.type == KeyEventType.KeyDown -> moveNudgeBurst.keyDown(e.key, e.isShiftPressed)

                                        tool == ToolId.MOVE && e.type == KeyEventType.KeyUp -> moveNudgeBurst.keyUp(e.key)

                                        lasso != null && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.Escape) ->
                                            lasso.onKey(enter = e.key == Key.Enter)

                                        lasso != null && e.type == KeyEventType.KeyDown && e.key == Key.Backspace -> lasso.removeLastPoint()

                                        magneticLasso != null && e.type == KeyEventType.KeyDown &&
                                            (e.key == Key.Enter || e.key == Key.Escape) ->
                                            magneticLasso.onKey(enter = e.key == Key.Enter)

                                        magneticLasso != null && e.type == KeyEventType.KeyDown && e.key == Key.Backspace ->
                                            magneticLasso.removeLastPoint()

                                        crop != null && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.Escape) ->
                                            crop.onKey(enter = e.key == Key.Enter)

                                        crop != null && e.type == KeyEventType.KeyDown && e.key == Key.X -> {
                                            crop.swapOrientation()
                                            true
                                        }

                                        crop != null && e.type == KeyEventType.KeyDown && e.key == Key.O -> {
                                            crop.cycleOverlay()
                                            true
                                        }

                                        else -> false
                                    }
                                },
                        )
                        textEntry?.let { placement ->
                            val actionDispatcher = app.croptop.core.input.LocalActionDispatcher.current
                            val existing = placement.editing?.let { id -> state.layers.find(id)?.text }
                            app.croptop.feature.editor.text.InlineTextEditor(
                                text = textDraft,
                                onTextChange = { textDraft = it },
                                viewport = viewport,
                                origin = existing?.origin ?: placement.at,
                                boxWidth = existing?.boxWidth?.takeIf { it > 0f } ?: placement.boxWidth,
                                ppi = state.info?.resolutionPpi ?: 72f,
                                character = existing?.character ?: typeUi.character,
                                paragraph = existing?.paragraph ?: typeUi.paragraph,
                                editing = existing != null,
                                onCommit = { commitTextDraft() },
                                onCancel = {
                                    textEntry = null
                                    textDraft = ""
                                    vm.reportUiActionCompleted("Cancel text entry")
                                },
                                onTyping = { actionDispatcher.textInputActive = it },
                                onBounds = { textEditorBounds = it },
                                fontFile = (existing?.character ?: typeUi.character)
                                    .let { c -> typeUi.family?.takeIf { it.name == c.family }?.face(c.style)?.path }
                                    ?.let { path -> java.io.File(path) },
                            )
                        }
                    }
                    if (expanded && !chromeHidden) {
                        FloatingPanels(
                            layout = layout,
                            onLayout = { onLayout(it) },
                            content = panelContent,
                            onWorkspaceAction = ::setWorkspace,
                        )
                    }
                    val open = overlayPanel
                    if (!expanded && open != null) {
                        PanelOverlay(
                            open,
                            onClose = { setOverlayPanel(null) },
                            content = panelContent,
                            modifier = if (compact) {
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.55f).padding(8.dp)
                            } else {
                                Modifier.align(Alignment.TopEnd).width(300.dp).fillMaxHeight().padding(8.dp)
                            },
                        )
                    }
                }
                when {
                    chromeHidden -> Unit

                    expanded -> {
                        DockResizeHandle(
                            layout = layout,
                            onLayout = { onLayout(it) },
                            onWorkspaceAction = ::setWorkspace,
                        )
                        DockColumn(
                            layout = layout,
                            onLayout = { onLayout(it) },
                            content = panelContent,
                            modifier = Modifier.width(layout.dockWidthDp.dp).fillMaxHeight(),
                            onWorkspaceAction = ::setWorkspace,
                        )
                    }

                    !compact -> {
                        AeDivider(Modifier.fillMaxHeight(), vertical = true)
                        DockRail(layout, overlayPanel, onOpen = ::setOverlayPanel)
                    }
                }
            }
            if (compact && !chromeHidden) {
                AeDivider()
                CompactBottomBar(
                    tools = { CompactToolStrip(tool, lastInGroup, onSelect = ::select) },
                    layout = layout,
                    openPanel = overlayPanel,
                    onOpen = ::setOverlayPanel,
                )
            }
            AeDivider()
            StatusBar(
                state, docSize, viewport, { hover }, sample = vm::sample, onViewport = { viewport = it }, onDismissMessage = vm::dismissMessage,
                agentStatus = agentStatus,
                onDisconnectAgent = onDisconnectAgent,
                onCancelExport = vm::cancelExport,
                onCancelAction = vm::cancelAction,
                onDismissAction = vm::dismissActionFeedback,
            )
        }
    }
    if (confirmClose) {
        UnsavedChangesDialog(
            name = docName,
            onSave = {
                confirmClose = false
                save(thenClose = true)
            },
            onDiscard = {
                confirmClose = false
                onClose()
            },
            onCancel = {
                confirmClose = false
                vm.reportUiActionCompleted("Keep editing")
            },
        )
    }
    when (aidsDialog) {
        "guide" -> NewGuideDialog(docSize, onAdd = { g ->
            toggleAids("Add guide") { it.copy(showGuides = true).addGuide(g) }
            aidsDialog = null
        }, onDismiss = {
            aidsDialog = null
            vm.reportUiActionCompleted("Cancel new guide")
        })

        "layout" -> GuideLayoutDialog(onApply = { l ->
            toggleAids("Apply guide layout") { l.applyTo(it, docSize) }
            aidsDialog = null
        }, onDismiss = {
            aidsDialog = null
            vm.reportUiActionCompleted("Cancel guide layout")
        })

        "grid" -> GridSettingsDialog(aids.gridSettings, onApply = { g ->
            toggleAids("Apply grid settings") { it.copy(gridSettings = g, grid = true) }
            aidsDialog = null
        }, onDismiss = {
            aidsDialog = null
            vm.reportUiActionCompleted("Cancel grid settings")
        })
    }
    if (proofSetupDialog) {
        app.croptop.feature.editor.dialogs.ProofSetupDialog(
            initial = vm.proofSetupDefaults(),
            onApply = {
                proofSetupDialog = false
                vm.setProofSetup(it)
            },
            onDismiss = { proofSetupDialog = false },
        )
    }
    aiRestoreDialog?.let { feature ->
        app.croptop.feature.editor.dialogs.AiRestoreDialog(
            feature = feature,
            hasSelection = state.selection is app.croptop.core.model.SelectionInfo.Active,
            sourceWidth = docSize.width,
            sourceHeight = docSize.height,
            onStart = { strength, factor ->
                aiRestoreDialog = null
                vm.runAiRestoration(feature, strength, factor)
            },
            onDismiss = { aiRestoreDialog = null },
        )
    }
    val aiPrompt by vm.aiPrompt.collectAsStateWithLifecycle()
    val aiModels by vm.aiModels.collectAsStateWithLifecycle()
    aiPrompt?.let { prompt ->
        app.croptop.core.designsystem.component.AiDownloadDialog(
            featureName = prompt.feature.label,
            modelIds = prompt.models.map { it.id },
            state = aiModels,
            onInstall = vm::installAiModel,
            onCancel = vm::cancelAiModel,
            onDismiss = { vm.dismissAiPrompt() },
            onReady = {
                vm.dismissAiPrompt()
                prompt.retry()
            },
            onBasic = prompt.basic?.let { { vm.dismissAiPrompt(useBasic = true) } },
        )
    }
    canvasEditRecovery?.let { recovery ->
        val actionLabel = if (recovery.editPixels) "Edit pixels" else "Add pixel layer"
        app.croptop.core.designsystem.component.AeDialog(
            title = "${recovery.action}: choose an editing target",
            body = recovery.detail + if (recovery.editPixels) {
                " Switch to the layer's pixels, then try the tool again."
            } else {
                " You can add a pixel layer and try the tool again. Your existing layer will be preserved." +
                    if (recovery.canRasterize) " Rasterize layer converts the embedded content to pixels; this conversion can be undone." else ""
            },
            onDismiss = vm::dismissCanvasEditRecovery,
            onConfirm = vm::recoverCanvasEditing,
            actions = {
                app.croptop.core.designsystem.component.AeButton("Cancel", vm::dismissCanvasEditRecovery)
                if (recovery.canRasterize) {
                    app.croptop.core.designsystem.component.AeButton("Rasterize layer", vm::rasterizeCanvasEditing)
                }
                app.croptop.core.designsystem.component.AeButton(
                    actionLabel, vm::recoverCanvasEditing, style = app.croptop.core.designsystem.component.AeButtonStyle.Primary,
                )
            },
        )
    }
    refineSession?.let { session ->
        app.croptop.feature.editor.dialogs.SelectAndMaskDialog(
            session = session,
            cancelRequested = cancelRefinement,
            onDone = { result, output ->
                cancelRefinement = false
                refineSession = null
                if (result != null) {
                    vm.reportUiCommandResult("Select and Mask", result)
                    if (result is app.croptop.core.engine.api.CommandResult.Accepted &&
                        output == app.croptop.core.engine.api.RefineEdgeOutput.NEW_DOCUMENT
                    ) {
                        if (onOpenDocumentInTab == null) {
                            vm.reportUiActionUnsupported("Open Select and Mask output", "Opening a new document tab is unavailable here.")
                        } else {
                            vm.refineOutputDocumentRoute(session)?.let(onOpenDocumentInTab)
                        }
                    }
                } else {
                    vm.reportUiActionCompleted("Cancel Select and Mask")
                }
            },
            onViewModeResult = { vm.reportUiCommandResult("Set Select and Mask view", it) },
            brushSpec = refineBrushSpec,
            onBrushSpecChange = { spec ->
                refineBrushSpec = spec
                refineBrushTool?.setSpec(spec)
            },
        )
    }
    if (showSaveWorkspace && onSaveWorkspace != null) {
        SaveWorkspaceDialog(
            initial = if (layout.name in WorkspaceLayout.Presets.map { it.name }) "My Workspace" else layout.name,
            onSave = { name ->
                showSaveWorkspace = false
                vm.dispatchUiAction("Save workspace") {
                    onSaveWorkspace(name).await()
                    app.croptop.feature.editor.session.ActionOutcome.Completed("Workspace saved")
                }
            },
            onCancel = {
                showSaveWorkspace = false
                vm.reportUiActionCompleted("Cancel save workspace")
            },
        )
    }
    when (sizeDialog) {
        "image" -> state.info?.let { info ->
            // Dialogs stay open when the engine rejects the change, so the values can be fixed and retried.
            app.croptop.feature.editor.dialogs.ImageSizeDialog(info.size, info.resolutionPpi, info.depth.bitsPerChannel / 8, onApply = { s, r ->
                if (vm.resizeImage(s, r)) sizeDialog = null
            }, onDismiss = {
                sizeDialog = null
                vm.reportUiActionCompleted("Dismiss Image Size dialog")
            }, onActionCompleted = vm::reportUiActionCompleted)
        }

        "canvas" -> state.info?.let { info ->
            app.croptop.feature.editor.dialogs.CanvasSizeDialog(info.size, info.resolutionPpi, onApply = { r, fill ->
                if (vm.resizeCanvas(r, fill)) sizeDialog = null
            }, onDismiss = {
                sizeDialog = null
                vm.reportUiActionCompleted("Dismiss Canvas Size dialog")
            }, onActionCompleted = vm::reportUiActionCompleted)
        }
    }
    if (fillDialog) {
        val cs = vm.colors.collectAsStateWithLifecycle().value
        app.croptop.feature.editor.dialogs.FillDialog(
            cs.foreground,
            cs.background,
            state.hasSelection,
            onApply = { src, op, mode, keep, action ->
                vm.fillJob(src, op, mode, keep, action = action)
            },
            onDismiss = { fillDialog = false },
            onCancel = { vm.reportUiActionCompleted("Cancel Fill") },
            initial = fillInitial,
            contentAwareContext = { vm.contentAwareContext() },
            newLayerId = vm::newLayerIdForOutput,
        )
    }
    fillLayerInitial?.let { initial ->
        app.croptop.feature.editor.dialogs.FillLayerDialog(
            layerId = fillLayerTarget,
            initial = initial,
            patterns = patterns,
            patternThumbnail = vm::patternThumbnail,
            onApply = { id, fill -> if (id == null) vm.addFillLayer(fill) else vm.setFillLayer(id, fill) },
            onDismiss = {
                fillLayerTarget = null
                fillLayerInitial = null
            },
        )
    }
    colorProfileConvert?.let { convert ->
        app.croptop.feature.editor.dialogs.ColorProfileDialog(
            initial = state.info?.profile ?: app.croptop.core.model.ColorProfileRef.DisplayP3,
            convertPixels = convert,
            onApply = vm::applyColorProfileJob,
            onDismiss = { colorProfileConvert = null },
        )
    }
    if (pendingOpenWarnings.isNotEmpty()) {
        app.croptop.core.designsystem.component.AeDialog(
            title = "Document Opened with Warnings",
            body = "Some document settings could not be used. Review the details before editing or exporting.",
            onDismiss = { pendingOpenWarnings = emptyList() },
            onConfirm = { pendingOpenWarnings = emptyList() },
            actions = {
                app.croptop.core.designsystem.component.AeButton("Done", { pendingOpenWarnings = emptyList() })
            },
        ) {
            Column(
                Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pendingOpenWarnings.forEachIndexed { index, warning ->
                    app.croptop.core.designsystem.component.AeText("${index + 1}. $warning", maxLines = 8)
                }
            }
        }
    }
    if (definePatternDialog) {
        app.croptop.feature.editor.dialogs.DefinePatternDialog(
            onDefine = vm::definePattern,
            onDismiss = { definePatternDialog = false },
        )
    }
    if (patternLibraryDialog) {
        app.croptop.feature.editor.dialogs.PatternLibraryDialog(
            patterns = patterns,
            patternThumbnail = vm::patternThumbnail,
            onDelete = { vm.deletePattern(it) },
            onDismiss = { patternLibraryDialog = false },
        )
    }
    state.exportWarning?.let { notice ->
        app.croptop.feature.editor.files.ExportCompatibilityDialog(
            format = notice.format,
            warnings = notice.warnings,
            onDismiss = {
                vm.dismissExportWarning()
                vm.reportUiActionCompleted("Dismiss export notes")
            },
        )
    }
    adjustmentDialog?.let { spec ->
        val isFilter = vm.filterSpecs.any { it.id == spec.id }
        val itemType = if (isFilter) "filter" else "adjustment"
        app.croptop.feature.editor.dialogs.AdjustmentDialog(
            spec = spec,
            layerChoices = state.layers.flatten()
                .filter { it.kind == app.croptop.core.model.LayerKind.PIXEL && it.id != state.primaryLayer?.id }
                .map { app.croptop.core.model.ChoiceParam.Option(app.croptop.core.model.ChoiceParam.LAYER_PREFIX + it.id.value, it.name) },
            onApply = { values ->
                val session = adjustmentPreview ?: return@AdjustmentDialog app.croptop.core.engine.api.CommandResult.Rejected(
                    app.croptop.core.engine.api.RejectionCode.CLOSED,
                    "Adjustment preview is no longer available",
                )
                val result = session.apply(values)
                vm.reportUiCommandResult("Apply ${spec.name} $itemType", result)
                if (result is app.croptop.core.engine.api.CommandResult.Accepted) {
                    adjustmentPreview = null
                    if (vm.filterSpecs.any { it.id == spec.id }) lastFilterId = spec.id
                }
                result
            },
            onDismiss = { adjustmentDialog = null },
            onPreview = { values -> adjustmentPreview?.preview(values) },
            previewResult = adjustmentPreview?.previewResult,
            regionPreview = adjustmentPreview?.regionPreview,
            onCancelPreview = {
                val canceled = adjustmentPreview != null
                adjustmentPreview?.cancel()
                adjustmentPreview = null
                if (canceled) {
                    vm.reportUiActionCompleted("Cancel ${spec.name} $itemType preview")
                } else {
                    vm.reportUiActionCompleted("Cancel ${spec.name} $itemType")
                }
            },
            isFilter = isFilter,
        )
    }
    if (lensBlurDialog) {
        val target = state.primaryLayer?.takeIf { it.kind == app.croptop.core.model.LayerKind.PIXEL }
        if (target == null) {
            lensBlurDialog = false
        } else {
            app.croptop.feature.editor.dialogs.LensBlurDialog(
                target = target.id,
                layers = state.layers.flatten(),
                onApply = vm::lensBlurJob,
                onDepthRange = vm::applyDepthRangeMask,
                onEstimateDepth = vm::estimateDepth,
                onDismiss = { lensBlurDialog = false },
            )
        }
    }
    if (filterCatalogDialog) {
        app.croptop.feature.editor.dialogs.AdjustmentCatalogDialog(
            specs = vm.filterSpecs,
            title = "Filters",
            body = "Choose a filter for the active pixel layer (inside the selection), or a smart object (as an editable smart filter).",
            groups = app.croptop.core.model.FilterCatalog.groups,
            availability = { spec ->
                when {
                    state.primaryLayer?.kind != app.croptop.core.model.LayerKind.PIXEL &&
                        state.primaryLayer?.kind != app.croptop.core.model.LayerKind.SMART_OBJECT ->
                        app.croptop.feature.editor.dialogs.AdjustmentAvailability.Disabled(
                            app.croptop.feature.editor.dialogs.AdjustmentDisabledReason.PIXEL_LAYER_REQUIRED,
                        )

                    !vm.supportsDestructiveAdjustment(spec.id) ->
                        app.croptop.feature.editor.dialogs.AdjustmentAvailability.Disabled(
                            app.croptop.feature.editor.dialogs.AdjustmentDisabledReason.ENGINE_UNSUPPORTED,
                        )

                    else -> app.croptop.feature.editor.dialogs.AdjustmentAvailability.Supported
                }
            },
            onPick = { spec ->
                filterCatalogDialog = false
                openAdjustment(spec.id)
            },
            onDismiss = { filterCatalogDialog = false },
        )
    }
    if (adjustmentCatalogDialog) {
        app.croptop.feature.editor.dialogs.AdjustmentCatalogDialog(
            specs = vm.adjustmentSpecs,
            availability = { spec ->
                when {
                    state.primaryLayer?.kind != app.croptop.core.model.LayerKind.PIXEL ->
                        app.croptop.feature.editor.dialogs.AdjustmentAvailability.Disabled(
                            app.croptop.feature.editor.dialogs.AdjustmentDisabledReason.PIXEL_LAYER_REQUIRED,
                        )

                    !vm.supportsDestructiveAdjustment(spec.id) ->
                        app.croptop.feature.editor.dialogs.AdjustmentAvailability.Disabled(
                            app.croptop.feature.editor.dialogs.AdjustmentDisabledReason.ENGINE_UNSUPPORTED,
                        )

                    else -> app.croptop.feature.editor.dialogs.AdjustmentAvailability.Supported
                }
            },
            onPick = { spec ->
                adjustmentCatalogDialog = false
                openAdjustment(spec.id)
            },
            onDismiss = {
                adjustmentCatalogDialog = false
                vm.reportUiActionCompleted("Close adjustment catalog")
            },
        )
    }
    if (newAdjustmentDialog) {
        app.croptop.feature.editor.dialogs.NewAdjustmentLayerDialog(vm.adjustmentSpecs, onPick = {
            newAdjustmentDialog = false
            addAdjustment(it)
        }, onDismiss = {
            newAdjustmentDialog = false
            vm.reportUiActionCompleted("Cancel new adjustment layer")
        })
    }
    if (gradientDialog) {
        val colors = vm.colors.collectAsStateWithLifecycle().value
        val default = app.croptop.core.model.ColorGradient(
            listOf(
                app.croptop.core.model.GradientStop(0f, colors.foreground),
                app.croptop.core.model.GradientStop(1f, colors.background),
            ),
        )
        app.croptop.feature.editor.tools.GradientPresetDialog(
            initial = currentGradient.value ?: default,
            onApply = {
                currentGradient.value = it
                vm.reportUiActionCompleted("Set gradient preset")
            },
            onDismiss = { gradientDialog = false },
            onCancel = { vm.reportUiActionCompleted("Cancel gradient edit") },
            onActionCompleted = vm::reportUiActionCompleted,
            onActionFailed = vm::reportUiActionFailed,
        )
    }
    modifyDialog?.let { kind ->
        // One preview session per dialog: previews never enter history, Cancel restores the selection, OK commits one step.
        val session = remember(kind) { vm.beginModifySelection(kind) }
        app.croptop.feature.editor.dialogs.ModifySelectionDialog(kind, onApply = { px ->
            val applied = if (session != null) vm.commitModifySelection(session, kind, px) else vm.modifySelection(kind, px)
            if (applied) modifyDialog = null
        }, onDismiss = {
            modifyDialog = null
            vm.reportUiActionCompleted("Cancel ${kind.label.lowercase()} selection modification")
        }, onActionCompleted = vm::reportUiActionCompleted, onPreview = session?.let { s -> { px -> s.update(px) } }, onCancelPreview = { session?.cancel() })
    }
    if (showExport) {
        ExportDialog(
            document = docSize,
            onExport = { opts ->
                showExport = false
                pendingExport = opts
                exportTo.launch("$baseName.${opts.format.extension}")
            },
            onCancel = {
                showExport = false
                vm.reportUiActionCompleted("Cancel export options")
            },
            onActionCompleted = vm::reportUiActionCompleted,
        )
    }
}

@Composable
private fun MenuBar(
    checked: (ActionId) -> Boolean = { false },
    extra: (String) -> List<ActionId> = { emptyList() },
    onAdjustmentCatalog: () -> Unit = {},
    onFilterCatalog: () -> Unit = {},
    onLensBlur: () -> Unit = {},
) {
    val actions = LocalActionDispatcher.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    AeAppHeader(minHeight = maxOf(32.dp, Ae.density.controlHeight), background = Ae.colors.surface2, startPadding = 4.dp) {
        menus.forEach { (name, ids) ->
            Box {
                Box(
                    Modifier
                        .heightIn(min = Ae.density.controlHeight)
                        .background(if (open == name) Ae.colors.surface4 else Color.Transparent, Ae.shapes.chip)
                        .captionInteractive()
                        .clickableNoIndication { open = name }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AeText(name, style = Ae.type.label, color = if (open == name) Ae.colors.textPrimary else Ae.colors.textSecondary)
                }
                AeMenu(
                    expanded = open == name,
                    onDismiss = { open = null },
                    entries = (ids + extra(name).map { it.id }.let { e -> if (e.isEmpty()) e else listOf("-") + e }).map { id ->
                        if (id == "-") {
                            AeMenuEntry.Divider
                        } else if (id == ADJUSTMENTS_CATALOG_MENU_ID) {
                            AeMenuEntry.Item(
                                label = "Adjustments…",
                                onClick = {
                                    open = null
                                    onAdjustmentCatalog()
                                },
                            )
                        } else if (id == FILTER_CATALOG_MENU_ID) {
                            AeMenuEntry.Item(
                                label = "Filters…",
                                onClick = {
                                    open = null
                                    onFilterCatalog()
                                },
                            )
                        } else if (id == LENS_BLUR_MENU_ID) {
                            AeMenuEntry.Item(
                                label = "Lens Blur…",
                                onClick = {
                                    open = null
                                    onLensBlur()
                                },
                            )
                        } else {
                            val a = ActionId(id)
                            AeMenuEntry.Item(
                                label =
                                    Actions[a]?.menuLabel ?: if (id.startsWith("window.ws.")) "Workspace: ${id.removePrefix("window.ws.")}" else id,
                                onClick = { actions.perform(a) },
                                shortcut = actions.shortcuts.label(a),
                                enabled = actions.canPerform(a),
                                checked = checked(a),
                            )
                        }
                    },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        SearchCommandsButton()
    }
}

/** MIME for the native format; registered in the app manifest's VIEW filter. */
private const val CROPTOP_MIME = "application/x-croptop"

/** Menu bar content by action id; only actions with a live handler are enabled. */
private val menus: List<Pair<String, List<String>>> = listOf(
    "File" to listOf(
        "app.new", "app.open", "-", "app.save", "app.saveAs", "-", "app.quickExport", "app.exportAs", "app.share", "-", "app.newWindow", "app.close",
    ),
    "Edit" to listOf(
        "edit.undo", "edit.redo", "-", "edit.cut", "edit.copy", "edit.copyMerged", "edit.paste", "edit.pasteInPlace", "-",
        "edit.freeTransform", "edit.warp", "edit.transformAgain", "edit.fill", "edit.removeAi",
        "edit.definePattern",
        "edit.patternLibrary",
    ),
    "Image" to listOf(
        "image.imageSize", "image.canvasSize", "image.superResolution", "-", "image.rotate180", "image.rotate90cw", "image.rotate90ccw", "image.flipH", "image.flipV",
        "-", "image.crop", "-", "image.assignProfile", "image.convertProfile", "-", ADJUSTMENTS_CATALOG_MENU_ID, LENS_BLUR_MENU_ID,
    ),
    "Layer" to listOf(
        "layer.new", "layer.newAdjustment", "layer.newFillSolid", "layer.newFillGradient", "layer.newFillPattern",
        "layer.duplicate", "layer.viaCut", "layer.delete", "-",
        "layer.group", "layer.ungroup", "-",
        "layer.toTop", "layer.moveUp", "layer.moveDown", "layer.toBottom", "-",
        "layer.maskRevealAll", "layer.maskHideAll", "layer.maskRevealSelection", "layer.maskHideSelection",
        "layer.maskToggle", "layer.maskApply", "layer.maskDelete", "layer.clippingMask", "-",
        "layer.mergeDown", "layer.mergeVisible", "layer.stampVisible", "-",
        "layer.convertSmartObject", "layer.rasterizeSmartObject",
    ),
    "Filter" to listOf(FILTER_CATALOG_MENU_ID, "filter.last", "-", LENS_BLUR_MENU_ID, "filter.denoiseAi"),
    "Select" to listOf(
        "select.all", "select.deselect", "select.reselect", "select.inverse", "-", "select.saveSelection", "select.loadSelection", "-",
        "select.subject", "select.sky", "select.background", "select.removeBackground", "select.colorRange", "-",
        "select.expand", "select.contract", "select.border", "select.smooth", "select.feather", "-", "select.transform", "-", "select.selectAndMask",
    ),
    "View" to listOf(
        "view.zoomIn", "view.zoomOut", "view.fit", "view.actualPixels", "-",
        "view.proofColors", "view.gamutWarning", "view.proofSetup", "-",
        "view.rulers", "view.grid", "view.guides", "view.snap", "-",
        "view.lockGuides", "view.clearGuides", "view.newGuide", "view.newGuideLayout", "view.gridSettings", "-",
        "view.hidePanels", "view.screenMode",
    ),
    "Window" to DockPanel.entries.map { windowPanelAction(it).id } + "-" +
        WorkspaceLayout.Presets.map { workspaceAction(it.name).id } + "-" + "window.saveWorkspace" + "window.resetWorkspace",
)

private const val ADJUSTMENTS_CATALOG_MENU_ID = "__image_adjustments_catalog"
private const val LENS_BLUR_MENU_ID = "__image_lens_blur"
private const val FILTER_CATALOG_MENU_ID = "__filter_catalog"

private fun isProofChecked(id: ActionId, proof: app.croptop.core.model.SoftProofSettings?): Boolean = when (id.id) {
    "view.proofColors" -> proof != null
    "view.gamutWarning" -> proof?.gamutWarning == true
    else -> false
}

private fun isAidChecked(id: ActionId, aids: ViewAids): Boolean = when (id.id) {
    "view.rulers" -> aids.rulers
    "view.grid" -> aids.grid
    "view.guides" -> aids.showGuides
    "view.snap" -> aids.snap
    "view.lockGuides" -> aids.lockGuides
    else -> false
}

private fun isChecked(id: ActionId, layout: WorkspaceLayout): Boolean = when {
    id.id.startsWith("window.panel.") -> DockPanel.entries.firstOrNull { windowPanelAction(it) == id }?.let { layout.state(it).visible } ?: false
    id.id.startsWith("window.ws.") -> id == workspaceAction(layout.name)
    else -> false
}

private val app.croptop.core.input.ActionDef.menuLabel: String get() = label

@Composable
private fun DocumentTabs(
    tabs: List<EditorDocumentTab>,
    currentRoute: EditorRoute,
    currentName: String,
    currentDirty: Boolean,
    onSelect: (EditorRoute) -> Unit,
    onClose: (EditorRoute) -> Unit,
    onMove: (EditorRoute, EditorRoute) -> Unit,
    onDragExport: () -> Unit,
    menu: (EditorRoute) -> List<AeMenuEntry>,
) {
    val c = Ae.colors
    val entries = if (tabs.any { it.route == currentRoute }) tabs else listOf(EditorDocumentTab(currentRoute, currentName, currentDirty))
    val latestEntries by rememberUpdatedState(entries)
    val reorderThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    Row(
        Modifier.fillMaxWidth().height(maxOf(32.dp, Ae.density.controlHeight)).background(c.surface1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            entries.forEachIndexed { index, tab ->
                val active = tab.route == currentRoute
                AeContextMenuArea(entries = { menu(tab.route) }) {
                    Row(
                        Modifier
                            .fillMaxHeight()
                            .background(if (active) c.app else Color.Transparent)
                            .captionInteractive()
                            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab.route) })
                            .documentTabGestures(
                                tabKey = tab.route,
                                tabPosition = { latestEntries.indexOfFirst { it.route == tab.route } },
                                tabCount = { latestEntries.size },
                                reorderThresholdPx = reorderThresholdPx,
                                onMiddleClick = { onClose(tab.route) },
                                onMoveBy = { delta ->
                                    val position = latestEntries.indexOfFirst { it.route == tab.route }
                                    val target = position + delta
                                    if (position >= 0 && target in latestEntries.indices) {
                                        onMove(tab.route, latestEntries[target].route)
                                    }
                                },
                            )
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AeText(tab.title, style = Ae.type.label)
                        if (tab.dirty) {
                            Box(Modifier.size(6.dp).background(Ae.colors.textSecondary, androidx.compose.foundation.shape.CircleShape))
                        }
                        if (active) AeIconButton(AeIcons.Export, "Drag merged image to another app", onDragExport)
                        AeIconButton(
                            AeIcons.Close,
                            "Close ${tab.title}",
                            { onClose(tab.route) },
                            shortcut = if (active) shortcutLabel("app.close") else null,
                        )
                    }
                }
                if (index < entries.lastIndex) AeDivider(vertical = true)
            }
        }
    }
}

@Composable
private fun OptionsBar(
    tool: ToolId,
    controller: ToolController?,
    viewport: ViewportState,
    type: app.croptop.feature.editor.text.TypeUi,
    brush: app.croptop.feature.editor.brush.BrushUi,
    transforming: Boolean = false,
    paintingMask: Boolean = false,
    onActionCompleted: (String) -> Unit,
    onViewport: (ViewportState) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(Ae.colors.surface2).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!transforming) AeText(tool.displayName, style = Ae.type.label, modifier = Modifier.width(140.dp))
        // Options-bar sliders take Photoshop's compact form: a scrubby label, a number box and a popover bar.
        androidx.compose.runtime.CompositionLocalProvider(app.croptop.core.designsystem.component.LocalAeCompactSliders provides true) {
        when {
            transforming -> controller?.Options()

            tool == ToolId.BRUSH || tool == ToolId.ERASER -> {
                if (paintingMask) {
                    AeText(
                        "Mask",
                        style = Ae.type.caption,
                        color = Ae.colors.onAccent,
                        modifier = Modifier.background(Ae.colors.accent, Ae.shapes.chip).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                app.croptop.feature.editor.brush.BrushOptions(brush)
            }

            tool == ToolId.ROTATE_VIEW -> ResetRotationButton(viewport, onViewport) {
                onActionCompleted("Reset canvas rotation")
            }

            tool == ToolId.TEXT -> app.croptop.feature.editor.text.TextOptions(type, onActionCompleted)

            controller != null -> controller.Options()

            else -> AeText("This tool is coming soon.", style = Ae.type.caption, color = Ae.colors.textTertiary)
        }
        }
    }
}

/** Horizontal tool strip for compact windows. */
@Composable
private fun CompactToolStrip(selected: ToolId, lastInGroup: Map<ToolGroup, ToolId>, onSelect: (ToolId) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ToolGroup.entries.forEach { group ->
            val tool = if (selected.group == group) selected else lastInGroup[group] ?: ToolId.inGroup(group).first()
            AeToggleIconButton(tool.icon, tool.displayName, checked = selected == tool, onCheckedChange = { onSelect(tool) })
        }
    }
}

@Composable
private fun Toolbar(selected: ToolId, lastInGroup: Map<ToolGroup, ToolId>, onSelect: (ToolId) -> Unit) {
    // One slot per group; the slot shows the group's active tool.
    Column(
        Modifier.width(
            AeSizes.toolbarWidth,
        ).fillMaxHeight().background(Ae.colors.surface1).verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolGroup.entries.forEach { group ->
            val tool = if (selected.group == group) selected else lastInGroup[group] ?: ToolId.inGroup(group).first()
            AeToggleIconButton(
                icon = tool.icon,
                contentDescription = tool.displayName,
                checked = selected == tool,
                onCheckedChange = { onSelect(tool) },
                shortcut = tool.key?.toString(),
            )
        }
    }
}

/** Shown when a document can't be opened: explain the problem and offer one action. */
@Composable
private fun OpenErrorScreen(fileName: String, message: String, onClose: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().background(Ae.colors.app)) {
        AeAppHeader { AeText(fileName, style = Ae.type.title) }
        AeDivider()
        AeEmptyState(
            title = "Couldn't open the document",
            body = message,
            icon = AeIcons.Image,
            modifier = Modifier.fillMaxSize(),
            action = { AeButton("Back to Home", onClose, style = AeButtonStyle.Primary) },
        )
    }
}

/** Content of each dockable panel. */
@Composable
private fun ColumnScope.PanelBody(
    panel: DockPanel,
    state: EditorState,
    vm: EditorViewModel,
    viewport: androidx.compose.runtime.State<ViewportState>,
    document: DocSize,
    swatches: SwatchesUi?,
    type: app.croptop.feature.editor.text.TypeUi,
    brush: app.croptop.feature.editor.brush.BrushUi,
    pathDrafts: PathDraftController,
    onCommitPaths: (List<app.croptop.core.model.VectorPathDraft>) -> Boolean,
    onPickLevels: (app.croptop.core.model.LayerId, LevelsPickTarget) -> Unit,
    onTargetAdjustment: (app.croptop.core.model.LayerId, String) -> Unit,
    onEditFillLayer: (app.croptop.core.model.LayerId) -> Unit,
    onEditSmartObjectContents: (app.croptop.core.model.LayerId) -> Unit,
    onReplaceSmartObjectContents: (app.croptop.core.model.LayerId) -> Unit,
    targetedHueChannel: app.croptop.core.model.HueChannel?,
    onViewport: (ViewportState) -> Unit,
) {
    when (panel) {
        DockPanel.HISTOGRAM -> {
            val availableScopes = buildList {
                add(app.croptop.core.engine.api.HistogramScope.MERGED)
                if (state.primaryLayer?.kind == app.croptop.core.model.LayerKind.PIXEL) {
                    add(app.croptop.core.engine.api.HistogramScope.ACTIVE_LAYER)
                }
                if (state.hasSelection) add(app.croptop.core.engine.api.HistogramScope.SELECTION)
            }
            var requestedScope by rememberSaveable(vm) {
                mutableStateOf(app.croptop.core.engine.api.HistogramScope.MERGED)
            }
            val scope = requestedScope.takeIf { it in availableScopes }
                ?: app.croptop.core.engine.api.HistogramScope.MERGED
            val histogramFlow = remember(vm, scope) { vm.histogram(scope) }
            val histogram by histogramFlow.collectAsStateWithLifecycle(initialValue = null)
            app.croptop.feature.editor.panels.HistogramPanelContent(
                histogram = histogram,
                scope = scope,
                availableScopes = availableScopes,
                onScopeChange = { requestedScope = it },
            )
        }

        DockPanel.NAVIGATOR -> {
            val previewFlow = remember(vm) { vm.navigatorPreview() }
            val preview by previewFlow.collectAsStateWithLifecycle(initialValue = null)
            val bitmap = remember(preview) { preview?.toImageBitmap() }
            NavigatorPanelContent(viewport.value, document, onViewport, vm::reportUiActionCompleted, preview = bitmap)
        }

        DockPanel.COLOR -> {
            val colors by vm.colors.collectAsStateWithLifecycle()
            ColorPanelContent(
                colors,
                vm::setForeground,
                vm::setBackground,
                vm::rememberColor,
                onSwap = { vm.swapColors(reportCompletion = false) },
                onDefaults = { vm.defaultColors(reportCompletion = false) },
                swatches = swatches ?: SwatchesUi(DefaultSwatchList),
                onActionComplete = vm::reportUiActionCompleted,
            )
        }

        DockPanel.PROPERTIES -> {
            val layer = state.primaryLayer
            val adjustment = layer?.adjustment
            val spec = adjustment?.let { a -> vm.adjustmentSpecs.firstOrNull { it.id == a.specId } }
            if (layer == null) {
                AeEmptyState("No layer selected", body = "Pick a layer in the Layers panel.", modifier = Modifier.fillMaxWidth())
            } else {
                if (spec != null) {
                    // Adjustment layers: the panel generated from the engine's AdjustmentSpec.
                    app.croptop.feature.editor.properties.AdjustmentProperties(
                        spec,
                        adjustment.values,
                        { values, action -> vm.editAdjustment(layer.id, values, action) },
                        onLevelsPick = { target -> onPickLevels(layer.id, target) },
                        onCanvasTarget = { onTargetAdjustment(layer.id, spec.id) },
                        targetedHueChannel = targetedHueChannel,
                        onActionCompleted = vm::reportUiActionCompleted,
                    )
                } else {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AeText(layer.name, style = Ae.type.title)
                        AeText(
                            "${layer.kind.displayName} layer",
                            style = Ae.type.caption,
                            color = Ae.colors.textSecondary,
                        )
                    }
                    if (layer.kind == app.croptop.core.model.LayerKind.PIXEL) QuickActions(vm, layer.mask == null)
                }
                val mask = layer.mask
                val background = layer.name == "Background" && layer.locks.position && layer.locks.transparency
                if (mask != null) {
                    AeDivider()
                    app.croptop.feature.editor.properties.MaskProperties(
                        mask,
                        app.croptop.feature.editor.properties.MaskActions(
                            onChange = { vm.editMask(layer.id, it) },
                            onToggle = { vm.toggleMask(layer.id) },
                            onInvert = { vm.invertMask(layer.id) },
                            onLoadSelection = { vm.maskToSelection(layer.id) },
                            onApply = if (layer.kind == app.croptop.core.model.LayerKind.PIXEL) ({ vm.deleteMask(layer.id, apply = true) }) else null,
                            onDelete = { vm.deleteMask(layer.id, apply = false) },
                        ),
                    )
                } else if (!background) {
                    app.croptop.feature.editor.properties.AddMaskButton({ vm.addMask(id = layer.id) })
                }
            }
        }

        DockPanel.HISTORY -> HistoryPanelContent(
            state.history,
            onJump = { index ->
                val label = state.history.entries.getOrNull(index)?.label
                vm.jumpTo(index, action = label?.let { "Jump to $it" } ?: "Jump to history step")
            },
            snapshots = app.croptop.feature.editor.panels.SnapshotActions(
                onCreate = {
                    val name = app.croptop.feature.editor.panels.nextSnapshotName(state.history)
                    vm.createSnapshot(name, action = "Create $name")
                },
                onRestore = { index ->
                    val name = state.history.snapshots.firstOrNull { it.index == index }?.name
                    vm.jumpToSnapshot(index, action = name?.let { "Restore $it" } ?: "Restore snapshot")
                },
                onNewDocumentFromState = {
                    vm.reportUiActionUnsupported(
                        "Create document from history",
                        "Creating a new document from a history state is not available yet.",
                    )
                },
                onRename = { i, n ->
                    val name = state.history.snapshots.firstOrNull { it.index == i }?.name
                    vm.renameSnapshot(i, n, action = name?.let { "Rename $it" } ?: "Rename snapshot")
                },
                onDelete = { index ->
                    val name = state.history.snapshots.firstOrNull { it.index == index }?.name
                    vm.deleteSnapshot(index, action = name?.let { "Delete $it" } ?: "Delete snapshot")
                },
                onActionCompleted = vm::reportUiActionCompleted,
            ),
        )

        DockPanel.LAYERS -> LayersPanelContent(
            state,
            vm,
            onEditFillLayer = onEditFillLayer,
            onEditSmartObjectContents = onEditSmartObjectContents,
            onReplaceSmartObjectContents = onReplaceSmartObjectContents,
        )

        DockPanel.CHANNELS -> {
            val channelsFlow = remember(vm) { vm.selectionChannels() }
            val channels by channelsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            val quickMaskColorFlow = remember(vm) { vm.quickMaskOverlayColor() }
            val quickMaskColor by quickMaskColorFlow.collectAsStateWithLifecycle(
                initialValue = app.croptop.core.model.RgbaColor(1f, 0f, 0f, 0.5f),
            )
            app.croptop.feature.editor.panels.ChannelsPanelContent(
                channels = channels,
                hasSelection = state.hasSelection,
                onSave = { vm.saveSelection() },
                onLoad = vm::loadSelection,
                onDelete = vm::deleteSelectionChannel,
                quickMaskOverlayColor = quickMaskColor,
                onQuickMaskOverlayColorChange = vm::setQuickMaskOverlayColor,
                onQuickMaskOverlayColorEditComplete = { vm.reportUiActionCompleted("Set Quick Mask color") },
            )
        }

        DockPanel.CHARACTER -> app.croptop.feature.editor.text.CharacterPanelContent(type, vm::reportUiActionCompleted)

        DockPanel.PARAGRAPH -> app.croptop.feature.editor.text.ParagraphPanelContent(type, vm::reportUiActionCompleted)

        DockPanel.BRUSHES -> app.croptop.feature.editor.brush.BrushesPanelContent(brush)

        DockPanel.BRUSH_SETTINGS -> app.croptop.feature.editor.brush.BrushSettingsPanelContent(brush)

        DockPanel.PATHS -> app.croptop.feature.editor.path.PathsPanelContent(
            controller = pathDrafts,
            onPathsChanged = onCommitPaths,
            onPathBoolean = { subject, clip, operation ->
                val shapeLayer = state.primaryLayer?.takeIf { it.kind == app.croptop.core.model.LayerKind.SHAPE }
                if (shapeLayer == null) {
                    vm.reportUiActionFailed("${operation.name.lowercase()} paths", "Select a vector shape layer first")
                    false
                } else {
                    vm.combineShapePaths(shapeLayer.id, subject, clip, operation)
                }
            },
            onPathToSelection = { shape ->
                vm.selectShape(shape, app.croptop.core.model.SelectionOp.REPLACE, action = "Path to selection")
            },
            onActionCompleted = vm::reportUiActionCompleted,
            onActionFailed = vm::reportUiActionFailed,
        )
    }
}

@Composable
private fun StatusBar(
    state: EditorState,
    doc: DocSize,
    viewport: ViewportState,
    /** Read lazily: pointer moves then recompose only the status bar, not the whole editor. */
    hover: () -> ViewPoint?,
    sample: (DocPoint) -> RgbaColor?,
    onViewport: (ViewportState) -> Unit,
    onDismissMessage: () -> Unit,
    agentStatus: AgentStatus = AgentStatus(),
    onDisconnectAgent: () -> Unit = {},
    onCancelExport: () -> Unit = {},
    onCancelAction: () -> Boolean = { false },
    onDismissAction: () -> Unit = {},
) {
    val msg = state.message
    val feedback = state.actionFeedback
    val feedbackPhase = feedback?.phase
    val feedbackIsTerminal = feedbackPhase in setOf(
        app.croptop.feature.editor.session.ActionFeedbackPhase.COMPLETED,
        app.croptop.feature.editor.session.ActionFeedbackPhase.REJECTED,
        app.croptop.feature.editor.session.ActionFeedbackPhase.FAILED,
        app.croptop.feature.editor.session.ActionFeedbackPhase.CANCELED,
    )
    LaunchedEffect(msg) {
        if (msg != null) {
            kotlinx.coroutines.delay(4000)
            onDismissMessage()
        }
    }
    LaunchedEffect(feedback?.id, feedbackPhase) {
        if (feedbackIsTerminal) {
            kotlinx.coroutines.delay(4000)
            onDismissAction()
        }
    }
    Row(
        Modifier.fillMaxWidth().height(
            if (Ae.density ==
                AeDensity.Touch
            ) {
                44.dp
            } else {
                24.dp
            },
        ).background(Ae.colors.surface2).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val t = Ae.type.caption
        val col = Ae.colors.textSecondary
        // Editable zoom (type 150 or 300*2 and press Enter).
        AeNumberField(
            viewport.zoomPercent,
            { pct -> onViewport(viewport.zoomAt(ViewPoint(viewport.viewWidth / 2f, viewport.viewHeight / 2f), (pct / 100f) / viewport.zoom)) },
            range = 1f..6400f,
            decimals = if (viewport.zoomPercent < 10f) 1 else 0,
            unit = "%",
            width = 64.dp,
            contentDescription = "Zoom percent",
            modifier = if (Ae.density == AeDensity.Touch) Modifier else Modifier.height(20.dp),
        )
        val info = state.info
        AeText(
            "${doc.width} × ${doc.height} · ${info?.depth?.label ?: "8-bit"} · ${info?.profile?.displayName ?: "Display P3"}",
            style = t,
            color = col,
        )
        val p = hover()?.let(viewport::viewToDoc)
        if (p != null && p.x >= 0 && p.y >= 0 && p.x < doc.width && p.y < doc.height) {
            AeText("X ${p.x.toInt()}  Y ${p.y.toInt()}", style = t, color = col)
            sample(p)?.let { rgba ->
                Box(Modifier.size(10.dp).background(Color(rgba.toArgb()), Ae.shapes.chip).border(1.dp, Ae.colors.lineStrong, Ae.shapes.chip))
                val (r, g, b) = listOf(rgba.r, rgba.g, rgba.b).map { (it * 255f + 0.5f).toInt() }
                AeText(if (rgba.a == 0f) "Transparent" else "R $r  G $g  B $b  #${rgba.hex}", style = t, color = col)
            }
        }
        if (viewport.rotationDegrees != 0f) AeText("${viewport.rotationDegrees.toInt()}°", style = t, color = col)
        Spacer(Modifier.weight(1f))
        // "Agent connected" chip: who is editing, last action, one-click disconnect.
        if (agentStatus.running && agentStatus.client != null) {
            Row(
                Modifier.background(Ae.colors.accentMuted, Ae.shapes.chip).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(AeIcons.Sparkle, null, tint = Ae.colors.accent, modifier = Modifier.size(12.dp))
                AeText("Agent: ${agentStatus.client}" + (agentStatus.lastTool?.let { " · $it" } ?: ""), style = t, color = Ae.colors.textPrimary)
                AeIconButton(AeIcons.Close, "Disconnect agent", onDisconnectAgent, modifier = Modifier.size(40.dp))
            }
        }
        val busy = state.busy
        when {
            feedback != null -> {
                EditorActionFeedbackLabel(feedback)
                if (msg != null && msg != feedback.statusText && msg != feedback.concurrentNotice) {
                    AeText(msg, style = t, color = Ae.colors.warning)
                }
                when {
                    feedback.cancelable -> AeIconButton(
                        AeIcons.Close,
                        "Cancel ${feedback.action.lowercase()}",
                        { onCancelAction() },
                        modifier = Modifier.size(40.dp),
                    )

                    feedbackIsTerminal || feedbackPhase == app.croptop.feature.editor.session.ActionFeedbackPhase.ACCEPTED -> AeIconButton(
                        AeIcons.Close,
                        if (feedbackIsTerminal) "Dismiss action result" else "Dismiss queued action status",
                        onDismissAction,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            busy != null -> {
                AeText(busy, style = t, color = col)
                if (busy == "Exporting…" || busy == "Preparing…") {
                    AeIconButton(AeIcons.Close, "Cancel export", onCancelExport, modifier = Modifier.size(40.dp))
                }
            }

            msg != null -> AeText(
                msg,
                style = t,
                color = if (msg == "Saved" || msg.startsWith("Exported") ||
                    msg.startsWith("Copied")
                ) {
                    col
                } else {
                    Ae.colors.warning
                },
            )
        }
    }
}

/** Why a tool can't touch the canvas yet; shown in the status bar when you try. */
internal fun unavailableMessage(tool: ToolId): String = when (tool) {
    else -> "${tool.displayName} isn't available yet."
}

internal fun shouldTogglePanelsOnTab(
    canvasFocused: Boolean,
    isTab: Boolean,
    isKeyDown: Boolean,
    shiftPressed: Boolean,
    ctrlPressed: Boolean,
    altPressed: Boolean,
    metaPressed: Boolean,
): Boolean = canvasFocused && isTab && isKeyDown && !shiftPressed && !ctrlPressed && !altPressed && !metaPressed

/** The Activity window hosting [this] view (the context may be a wrapper). */
private fun android.view.View.findActivityWindow(): android.view.Window? {
    var c = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c.window
        c = c.baseContext
    }
    return null
}


/** Properties ▸ Quick actions for a pixel layer: one-tap AI selections and Remove Background. */
@Composable
private fun QuickActions(vm: EditorViewModel, canMask: Boolean) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AeText("Quick actions", style = Ae.type.label, color = Ae.colors.textSecondary)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AeButton("Select Subject", { vm.selectSubject() }, style = AeButtonStyle.Secondary)
            AeButton("Select Sky", { vm.selectSky() }, style = AeButtonStyle.Secondary)
            if (canMask) {
                AeButton("Remove Background", { vm.removeBackground() }, style = AeButtonStyle.Primary)
            }
        }
    }
}

/** Straight-alpha RGBA bytes to an [androidx.compose.ui.graphics.ImageBitmap] for drawing. */
private fun app.croptop.core.engine.api.DocumentPreview.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val pixels = IntArray(width * height) { i ->
        val o = i * 4
        ((rgba[o + 3].toInt() and 0xFF) shl 24) or ((rgba[o].toInt() and 0xFF) shl 16) or ((rgba[o + 1].toInt() and 0xFF) shl 8) or (rgba[o + 2].toInt() and 0xFF)
    }
    return android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
