package app.croptop.feature.editor.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.agent.OpenDocuments
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.designsystem.component.LevelsPickTarget
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.Histogram
import app.croptop.core.engine.api.HistogramScope
import app.croptop.core.engine.api.LayerProperties
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.engine.api.SelectionChannel
import app.croptop.core.model.BlendMode
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.DocumentInfo
import app.croptop.core.model.HistoryState
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerNode
import app.croptop.core.model.LayerStyles
import app.croptop.core.model.LayerTree
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.files.ShareCache
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class CanvasAdjustmentTargetResult(
    val accepted: Boolean,
    val hueChannel: app.croptop.core.model.HueChannel? = null,
)

private fun RgbaColor.levelsPickCoordinate(target: LevelsPickTarget): Float = when (target) {
    // Scalar Levels transforms each encoded RGB channel independently. The max/min make every
    // channel of the sampled composite land at the selected endpoint.
    LevelsPickTarget.BLACK -> maxOf(r, g, b)

    LevelsPickTarget.GRAY -> (r + g + b) / 3f

    LevelsPickTarget.WHITE -> minOf(r, g, b)
}.coerceIn(0f, 1f)

private val LayerKind.baseName: String
    get() = when (this) {
        LayerKind.GROUP -> "Group"
        LayerKind.ADJUSTMENT -> "Adjustment"
        LayerKind.FILL -> "Fill"
        LayerKind.TEXT -> "Text"
        LayerKind.SHAPE -> "Shape"
        LayerKind.SMART_OBJECT -> "Smart Object"
        LayerKind.PIXEL -> "Layer"
    }

private fun EditCommand.feedbackLabel(): String = when (this) {
    is EditCommand.AddLayer -> "Add $name"
    is EditCommand.DeleteLayer -> "Delete layer"
    is EditCommand.MoveLayer -> "Move layer"
    is EditCommand.SetLayerProps -> "Update layer"
    is EditCommand.SetBlendMode -> "Change blend mode"
    EditCommand.Undo -> "Undo"
    EditCommand.Redo -> "Redo"
}

/** Foreground/background and recent colors (Photoshop D/X behavior). */
data class ColorState(
    val foreground: RgbaColor = RgbaColor.Black,
    val background: RgbaColor = RgbaColor.White,
    val recent: List<RgbaColor> = emptyList(),
)

/** Everything the editor UI renders for one document (MVI state). */
data class EditorState(
    val loading: Boolean = true,
    val info: DocumentInfo? = null,
    val layers: LayerTree = LayerTree.Empty,
    val history: HistoryState = HistoryState(),
    /** Panel selection; mirrors LayerTree.active until gives the engine a writer. */
    val selected: Set<LayerId> = emptySet(),
    val primary: LayerId? = null,
    val message: String? = null,
    /** Where Ctrl+S writes; null until the document has been saved as .croptop. */
    val saveTarget: String? = null,
    /** Non-null while a save/export runs (status bar progress). */
    val busy: String? = null,
    /** Set when the document could not be opened at all; the editor shows an error screen. */
    val openError: String? = null,
    /** The file's display name when known (tab title, Save As default). */
    val title: String? = null,
    /** The pixel selection (marching ants); drives Select-menu enablement. */
    val selection: app.croptop.core.model.SelectionInfo = app.croptop.core.model.SelectionInfo.None,
    val actionFeedback: EditorActionFeedback? = null,
    /** Engine-reported format/metadata compatibility notes for the completed export. */
    val exportWarning: ExportCompatibilityNotice? = null,
) {
    val hasSelection: Boolean get() = selection is app.croptop.core.model.SelectionInfo.Active

    val dirty: Boolean get() = info?.dirty == true

    val primaryLayer: LayerNode? get() = primary?.let(layers::find)
}

data class CanvasEditRecovery(
    val action: String,
    val detail: String,
    val layerId: LayerId?,
    val editPixels: Boolean = false,
    val canRasterize: Boolean = false,
)

data class ExportCompatibilityNotice(val format: ExportFormat, val warnings: List<String>)

/**
 * DocumentSession: owns one [DocumentHandle] for the editor route and routes every edit
 * through [EditCommand]s. Survives configuration changes and window resizes (ViewModel scope).
 */
@HiltViewModel(assistedFactory = EditorViewModel.Factory::class)
class EditorViewModel @AssistedInject constructor(
    private val engine: Engine,
    private val recents: RecentDocumentsRepository,
    private val shareCache: ShareCache,
    private val openDocuments: OpenDocuments,
    private val names: app.croptop.core.data.DocumentNames,
    @Assisted private val route: EditorRoute,
    /** The model-backed AI edits; [app.croptop.core.ai.UnavailableAi] where no runtime is wired (tests). */
    private val ai: app.croptop.core.ai.AiEditActions = app.croptop.core.ai.UnavailableAi,
    /** Monthly free uses of metered features such as Remove Background (Pro is unlimited). */
) : ViewModel() {
    private var activeExportJob: Job? = null

    @AssistedFactory
    interface Factory {
        fun create(route: EditorRoute): EditorViewModel
    }

    private val handle = MutableStateFlow<DocumentHandle?>(null)

    /** Shared engine pattern catalog used by fills and layer styles. */
    val patterns = engine.patterns

    fun patternThumbnail(id: String, maxPx: Int = 96) = engine.patternThumbnail(id, maxPx)

    fun definePattern(name: String): String? {
        val result = handle.value?.definePattern(name)
        if (result == null) {
            val detail = "No document is open"
            actionDispatcher.reportFailed("Define Pattern", detail)
            message.value = detail
            return detail
        }
        if (result is CommandResult.Rejected) {
            actionDispatcher.report("Define Pattern", result)
            message.value = result.message
            return result.message
        }
        actionDispatcher.reportCompleted("Define Pattern")
        return null
    }

    fun deletePattern(id: String): Boolean = engine.deletePattern(id).also {
        if (it) {
            actionDispatcher.reportCompleted("Delete Pattern")
        } else {
            reportUiActionRejected("Delete Pattern", app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT, "Pattern could not be removed")
        }
    }

    /** The open document, for the canvas host to attach its viewport. */
    val document: StateFlow<DocumentHandle?> = handle.asStateFlow()

    /** A single document-bound histogram stream; callers should retain it for the panel lifetime. */
    fun histogram(scope: HistogramScope): Flow<Histogram?> =
        handle.flatMapLatest { document ->
            document?.histogram(scope)?.map { it } ?: flowOf(null)
        }

    /** A small picture of the whole document for the Navigator, redrawn a moment after each edit settles. */
    fun navigatorPreview(px: Int = NAVIGATOR_PREVIEW_PX): Flow<app.croptop.core.engine.api.DocumentPreview?> =
        handle.flatMapLatest { document ->
            document ?: return@flatMapLatest flowOf(null)
            combine(document.history, document.layers, document.info) { _, _, _ -> Unit }
                .debounce(NAVIGATOR_PREVIEW_SETTLE_MILLIS)
                .mapLatest { document.preview(px) }
        }

    /** Saved alpha channels for the Channels panel; switches with the active document. */
    fun selectionChannels(): Flow<List<SelectionChannel>> =
        handle.flatMapLatest { document -> document?.selectionChannels ?: flowOf(emptyList()) }

    /** Configurable Quick Mask tint for the active document; follows document switches. */
    fun quickMaskOverlayColor(): Flow<RgbaColor> =
        handle.flatMapLatest { document ->
            document?.quickMaskOverlayColor ?: flowOf(RgbaColor(1f, 0f, 0f, 0.5f))
        }

    /** Apply a Quick Mask overlay edit without emitting feedback on every picker drag frame. */
    fun setQuickMaskOverlayColor(color: RgbaColor): Boolean {
        val document = handle.value
        if (document == null) {
            reportUiActionFailed("Set Quick Mask color", "No document is open")
            return false
        }
        val result = document.setQuickMaskOverlayColor(color)
        if (result is CommandResult.Rejected) {
            reportUiCommandResult("Set Quick Mask color", result)
            return false
        }
        return result is CommandResult.Accepted
    }

    private val message = MutableStateFlow<String?>(null)

    /** See [maskTarget]; declared early because [select] resets it. */
    private val _maskTarget = MutableStateFlow<LayerId?>(null)
    private val _canvasEditRecovery = MutableStateFlow<CanvasEditRecovery?>(null)
    val canvasEditRecovery: StateFlow<CanvasEditRecovery?> = _canvasEditRecovery.asStateFlow()
    private var recoveryDocument: app.croptop.core.engine.api.DocumentHandle? = null

    private fun offerCanvasRecovery(action: String, detail: String, editPixels: Boolean = false) {
        recoveryDocument = handle.value
        _canvasEditRecovery.value = CanvasEditRecovery(
            action, detail, state.value.primary, editPixels,
            canRasterize = state.value.primaryLayer?.kind == LayerKind.SMART_OBJECT,
        )
    }

    fun dismissCanvasEditRecovery() {
        _canvasEditRecovery.value = null
        recoveryDocument = null
    }

    private fun takeCanvasRecovery(): CanvasEditRecovery? {
        val recovery = _canvasEditRecovery.value ?: return null
        val sameTarget = handle.value === recoveryDocument && state.value.primary == recovery.layerId
        dismissCanvasEditRecovery()
        if (!sameTarget) {
            reportNoOp(recovery.action, "The editing target changed. Try the tool again.")
            return null
        }
        return recovery
    }

    fun recoverCanvasEditing() {
        val recovery = takeCanvasRecovery() ?: return
        if (recovery.editPixels) {
            val document = handle.value ?: return
            if (document.quickMask.value && !result(document.setQuickMask(false), "Edit layer pixels")) return
            recovery.layerId?.let(::targetPixels)
        } else {
            newLayer()
        }
    }

    fun rasterizeCanvasEditing() {
        val recovery = takeCanvasRecovery() ?: return
        if (recovery.canRasterize && state.value.primaryLayer?.kind == LayerKind.SMART_OBJECT) {
            rasterizeSmartObject(recovery.layerId)
        }
    }

    private val saveTarget = MutableStateFlow(route.sourceUri?.takeIf { isCropTopFile(it) })
    private val busy = MutableStateFlow<String?>(null)
    private val actionDispatcher = EditorActionDispatcher(viewModelScope) { yield() }
    private val openError = MutableStateFlow<String?>(null)
    private var pendingShapeCreation: PendingShapeCreation? = null

    /** The file's own name (from the provider), shown in the tab and recents. */
    private val title = MutableStateFlow<String?>(null)
    private val exportWarning = MutableStateFlow<ExportCompatibilityNotice?>(null)

    /** Rulers, guides, grid, snapping. Guides and grid geometry persist with the document. */
    val viewAids = MutableStateFlow(app.croptop.core.model.ViewAids())

    init {
        // Document → view: a project's saved guides/grid replace the view's geometry when it opens.
        viewModelScope.launch {
            handle.flatMapLatest { h -> h?.documentGuides ?: emptyFlow() }.collect { saved ->
                viewAids.update { aids ->
                    if (aids.guides == saved.guides && aids.gridSettings == saved.grid) {
                        aids
                    } else {
                        aids.copy(guides = saved.guides, gridSettings = saved.grid)
                    }
                }
            }
        }
        // View → document: guide drags and grid edits are stored on the document (not undoable, marks it modified).
        viewModelScope.launch {
            viewAids.map { it.guides to it.gridSettings }.distinctUntilChanged().collect { (guides, grid) ->
                val document = handle.value ?: return@collect
                val current = document.documentGuides.value
                if (current.guides != guides || current.grid != grid) {
                    runCatching { app.croptop.core.engine.api.DocumentGuides(guides, grid) }.getOrNull()?.let(document::setDocumentGuides)
                }
            }
        }
    }

    val state: StateFlow<EditorState> = handle.flatMapLatest { h ->
        if (h == null) {
            combine(openError, actionDispatcher.feedback) { error, feedback ->
                EditorState(loading = error == null, openError = error, actionFeedback = feedback)
            }
        } else {
            val meta = combine(
                combine(message, saveTarget, busy, title, h.selection) { m, t, b, n, s ->
                    Meta(m, t, b, n, s, null)
                },
                exportWarning,
            ) { current, warning -> current.copy(exportWarning = warning) }
            combine(h.info, h.layers, h.history, meta, actionDispatcher.feedback) {
                    info,
                    tree,
                    history,
                    (msg, target, work, name, docSel, exportNotice),
                    feedback,
                ->
                val selected = tree.active.filterTo(linkedSetOf()) { tree.find(it) != null }
                val primary = tree.primary?.takeIf { it in selected } ?: selected.firstOrNull()
                EditorState(
                    false,
                    info,
                    tree,
                    history,
                    selected,
                    primary,
                    msg,
                    target,
                    work,
                    title = name,
                    selection = docSel,
                    actionFeedback = feedback,
                    exportWarning = exportNotice,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EditorState())

    init {
        viewModelScope.launch {
            handle.filterNotNull().flatMapLatest { document -> document.events }.collect(actionDispatcher::reportEngineEvent)
        }
        viewModelScope.launch {
            route.openDocumentId?.let { documentId ->
                val document = openDocuments.byId(documentId)
                if (document == null) {
                    openError.value = "This editor tab is no longer available"
                } else {
                    handle.value = document
                    openDocuments.activate(document)
                    title.value = document.info.value.name
                }
                return@launch
            }
            val recoveryId = route.recoveryId
            if (recoveryId != null) {
                actionDispatcher.dispatch(action = "Recover document") {
                    try {
                        val document = engine.recover(recoveryId)
                        handle.value = document
                        openDocuments.register(document)
                        title.value = route.recoveryName
                        ActionOutcome.Completed("Document recovered; save it to keep your changes")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val detail = friendlyOpenError(failure)
                        openError.value = detail
                        ActionOutcome.Failed(detail)
                    }
                }
                return@launch
            }
            val uri = route.sourceUri
            if (uri == null) {
                val document = try {
                    engine.newDocument(route.newSpec ?: NewDocumentSpec(size = DocSize(4000, 3000)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    openError.value = friendlyOpenError(failure)
                    return@launch
                }
                handle.value = document
                openDocuments.register(document)
                return@launch
            }
            actionDispatcher.dispatch(action = "Open document") {
                try {
                    val document = engine.open(
                        DocumentSource.ContentUri(uri),
                        OpenOptions(rawDevelopSettings = route.rawDevelopSettings, rawAsSmartObject = route.rawAsSmartObject),
                    )
                    handle.value = document
                    openDocuments.register(document)
                    try {
                        title.value = withContext(Dispatchers.IO) { names.displayName(uri) }
                        recents.recordOpened(uri, title.value ?: displayName(uri, document.info.value.name), document.info.value.size)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        message.value = "Document opened; Recents couldn't be updated"
                    }
                    ActionOutcome.Completed("Document opened")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val detail = friendlyOpenError(failure)
                    openError.value = detail
                    ActionOutcome.Failed(detail)
                }
            }
        }
    }

    private fun run(command: EditCommand, quietAccepted: Boolean = false, action: String? = null): CommandResult? {
        val label = action ?: command.feedbackLabel()
        val h = handle.value ?: run {
            actionDispatcher.reportFailed(label, "No document is open")
            return null
        }
        val r = h.execute(command)
        if (quietAccepted && r is CommandResult.Accepted) {
            actionDispatcher.reportQuietly(label, r)
        } else {
            actionDispatcher.report(label, r)
        }
        if (r is CommandResult.Rejected) message.value = r.message
        return r
    }

    fun dismissMessage() {
        message.value = null
    }

    fun dismissActionFeedback() {
        actionDispatcher.dismiss()
    }

    fun cancelAction(): Boolean = actionDispatcher.cancel()

    /** Reports a completed editor-local control action without claiming document mutation. */
    fun reportUiActionCompleted(action: String) {
        actionDispatcher.reportCompleted(action)
    }

    fun reportUiActionQueued(action: String) {
        actionDispatcher.reportQueued(action)
    }

    fun reportUiActionFailed(action: String, detail: String) {
        actionDispatcher.reportFailed(action, detail)
    }

    /** Routes a UI-owned engine result through the same sequence-correlated feedback lifecycle. */
    fun reportUiCommandResult(action: String, result: CommandResult) {
        actionDispatcher.report(action, result)
        if (result is CommandResult.Rejected) message.value = result.message
    }

    fun reportUiActionUnsupported(action: String, detail: String) {
        reportUiCommandResult(action, CommandResult.Rejected(RejectionCode.UNSUPPORTED, detail))
    }

    fun reportUiActionRejected(action: String, code: RejectionCode, detail: String) {
        reportUiCommandResult(action, CommandResult.Rejected(code, detail))
    }

    /** Tracks UI-owned asynchronous work without inventing engine progress. */
    fun dispatchUiAction(action: String, operation: suspend () -> ActionOutcome): Job? =
        actionDispatcher.dispatch(
            action = action,
            onBusy = { message.value = "Wait for the current action to finish before ${action.lowercase()}" },
        ) { operation() }

    // ---- Selection ----

    fun select(
        id: LayerId,
        additive: Boolean = false,
        action: String = "Select layer",
        quietSuccess: Boolean = false,
    ): Boolean {
        val document = handle.value ?: run {
            actionDispatcher.reportFailed(action, "No document is open")
            return false
        }
        val cur = state.value.selected
        val next = if (additive) {
            val ids = if (id in cur) cur - id else cur + id
            ids to (if (id in ids) id else ids.firstOrNull())
        } else {
            setOf(id) to id
        }
        // the engine owns LayerTree.active (including an explicitly empty selection).
        val result = document.setActiveLayers(next.first, next.second)
        if (result is CommandResult.Rejected) {
            actionDispatcher.report(action, result)
            message.value = result.message
            return false
        }
        // This is a synchronous view-state write; NativeEngine and FakeEngine intentionally do
        // not emit render lifecycle events for it, so Accepted must not remain visible indefinitely.
        if (!quietSuccess) actionDispatcher.reportCompleted(action, "Layer selected")
        // A rejected engine write leaves both the rendered selection and mask target unchanged.
        _maskTarget.value = null
        return true
    }

    /** Alt+[ / Alt+] — select the layer below/above in panel order. */
    fun selectAdjacent(below: Boolean) {
        val flat = state.value.layers.flatten()
        val i = flat.indexOfFirst { it.id == state.value.primary }
        val direction = if (below) "below" else "above"
        val next = flat.getOrNull(if (below) i + 1 else i - 1) ?: run {
            reportNoOp("Select layer $direction", if (i < 0) "Select a layer first" else "There is no layer $direction")
            return
        }
        select(next.id, action = "Select layer $direction")
    }

    // ---- Layer commands ----

    fun newLayer(kind: LayerKind = LayerKind.PIXEL) {
        val tree = state.value.layers
        val anchor = state.value.primary
        val parent = anchor?.let { tree.parentOf(it) }
        val siblings = parent?.children ?: tree.layers
        val index = siblings.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
        val id = newId()
        val name = nextName(tree, kind.baseName)
        if (run(EditCommand.AddLayer(id, name, kind, parent?.id, index)) is CommandResult.Accepted) {
            select(id, quietSuccess = true)
        }
    }

    /** Creates or updates the engine-owned vector layer associated with the current path draft. */
    fun commitShapePaths(
        sourceLayerId: LayerId?,
        paths: List<app.croptop.core.model.VectorPathDraft>,
    ): Boolean {
        val h = handle.value ?: run {
            reportNoOp("Create shape", "No document is open")
            return false
        }
        val editorState = state.value
        val current = editorState.primaryLayer?.takeIf { it.kind == LayerKind.SHAPE }
        val pending = pendingShapeCreation?.takeUnless { editorState.layers.find(it.id) != null }
        if (pending == null) pendingShapeCreation = null
        val target = when {
            sourceLayerId != null && current?.id == sourceLayerId -> current.id

            sourceLayerId != null && pending?.id == sourceLayerId && editorState.primary == pending.previousPrimary -> pending.id

            sourceLayerId != null -> {
                reportNoOp("Edit shape", "The selected shape changed; reload its paths before editing")
                return false
            }

            current != null -> {
                reportNoOp("Edit shape", "The selected shape paths are still loading")
                return false
            }

            pending != null && editorState.primary == pending.previousPrimary -> pending.id

            pending != null -> {
                reportNoOp("Create shape", "The selected layer changed before the shape was created")
                return false
            }

            paths.isEmpty() -> return true

            else -> null
        }

        if (target != null) {
            if (paths.isEmpty()) {
                val deleted = result(h.execute(EditCommand.DeleteLayer(target)), "Delete shape")
                if (deleted) pendingShapeCreation = null
                return deleted
            }
            return result(h.setShapePaths(target, paths), "Edit shape")
        }

        val id = newId()
        val previousPrimary = editorState.primary
        val created = result(
            h.createShapeLayer(id, nextName(state.value.layers, LayerKind.SHAPE.baseName), paths),
            "Create shape",
        )
        if (created) pendingShapeCreation = PendingShapeCreation(id, previousPrimary)
        return created
    }

    /** Creates an engine-owned vector shape layer for non-interactive callers. */
    fun createShapeLayer(paths: List<app.croptop.core.model.VectorPathDraft>): Boolean = commitShapePaths(null, paths)

    /** Commits one vector-path edit as a single engine history operation. */
    fun setShapePaths(id: LayerId, paths: List<app.croptop.core.model.VectorPathDraft>): Boolean = commitShapePaths(id, paths)

    fun combineShapePaths(
        layerId: LayerId,
        subjectPath: app.croptop.core.model.VectorPathId,
        clipPath: app.croptop.core.model.VectorPathId,
        operation: app.croptop.core.engine.api.ShapeBooleanOperation,
    ): Boolean {
        val action = when (operation) {
            app.croptop.core.engine.api.ShapeBooleanOperation.UNITE -> "Unite paths"
            app.croptop.core.engine.api.ShapeBooleanOperation.SUBTRACT -> "Subtract paths"
            app.croptop.core.engine.api.ShapeBooleanOperation.INTERSECT -> "Intersect paths"
            app.croptop.core.engine.api.ShapeBooleanOperation.EXCLUDE -> "Exclude paths"
        }
        return result(handle.value?.combineShapePaths(layerId, subjectPath, clipPath, operation), action)
    }

    private data class PendingShapeCreation(val id: LayerId, val previousPrimary: LayerId?)

    /** Ctrl+G: wrap the selected layers in a new group placed where the top-most one was. */
    fun groupSelected() {
        val tree = state.value.layers
        val sel = tree.flatten().filter { it.id in state.value.selected }
        if (sel.isEmpty()) {
            reportNoOp("Group layers", "Select at least one layer to group")
            return
        }
        val first = sel.first()
        val parent = tree.parentOf(first.id)
        val index = (parent?.children ?: tree.layers).indexOfFirst { it.id == first.id }
        val group = newId()
        if (
            run(
                EditCommand.AddLayer(group, nextName(tree, "Group"), LayerKind.GROUP, parent?.id, index),
                quietAccepted = true,
            ) !is CommandResult.Accepted
        ) {
            return
        }
        sel.forEachIndexed { i, n ->
            val isLastMove = i == sel.lastIndex
            if (
                run(
                    EditCommand.MoveLayer(n.id, group, i),
                    quietAccepted = !isLastMove,
                    action = if (isLastMove) "Group layers" else null,
                ) !is CommandResult.Accepted
            ) {
                return
            }
        }
        select(group, action = "Group layers", quietSuccess = true)
    }

    fun deleteSelected(): Boolean {
        val selected = state.value.selected
        if (selected.isEmpty()) {
            reportNoOp("Delete layer", "Select a layer to delete")
            return false
        }
        var allAccepted = true
        selected.forEach { id ->
            if (run(EditCommand.DeleteLayer(id)) !is CommandResult.Accepted) allAccepted = false
        }
        return allAccepted
    }

    fun setVisible(id: LayerId, visible: Boolean, quietSuccess: Boolean = false, action: String? = null) =
        run(EditCommand.SetLayerProps(id, LayerProperties(visible = visible)), quietAccepted = quietSuccess, action = action)

    fun rename(id: LayerId, name: String) {
        val trimmed = name.trim()
        val currentName = state.value.layers.find(id)?.name ?: run {
            reportNoOp("Rename layer", "Select a layer to rename")
            return
        }
        if (trimmed.isEmpty()) {
            reportNoOp("Rename layer", "A layer name cannot be empty")
            return
        }
        if (trimmed == currentName) {
            actionDispatcher.reportCompleted("Rename layer", "Layer name is unchanged")
            return
        }
        run(EditCommand.SetLayerProps(id, LayerProperties(name = trimmed)), action = "Rename $currentName")
    }

    fun setOpacity(percent: Float) {
        val id = state.value.primary ?: return
        run(EditCommand.SetLayerProps(id, LayerProperties(opacity = (percent / 100f).coerceIn(0f, 1f))))
    }

    private var opacityEditOpen = false
    private var opacityPreviewAccepted = false
    private var opacityFeedbackAtStart: EditorActionFeedback? = null

    /** Applies live layer-opacity updates while keeping each slider sample out of visible feedback. */
    fun previewLayerOpacity(percent: Float): Boolean {
        if (!opacityEditOpen) {
            opacityEditOpen = true
            opacityFeedbackAtStart = actionDispatcher.feedback.value
        }
        val id = state.value.primary ?: run {
            reportNoOp("Set layer opacity", "Select a layer before changing opacity")
            opacityPreviewAccepted = false
            return false
        }
        val document = handle.value ?: run {
            actionDispatcher.reportFailed("Set layer opacity", "No document is open")
            opacityPreviewAccepted = false
            return false
        }
        val result = document.execute(
            EditCommand.SetLayerProps(id, LayerProperties(opacity = (percent / 100f).coerceIn(0f, 1f))),
        )
        opacityPreviewAccepted = previewResult(result, "Preview layer opacity")
        return opacityPreviewAccepted
    }

    /** Reports one settled action after an opacity slider drag, scrub, or committed number field. */
    fun finishLayerOpacityEdit(percent: Float) {
        if (!opacityEditOpen) return
        val baseline = opacityFeedbackAtStart
        val current = actionDispatcher.feedback.value
        val previewFailed =
            (
                current?.id != baseline?.id && current?.action == "Preview layer opacity" &&
                    current.phase in setOf(ActionFeedbackPhase.REJECTED, ActionFeedbackPhase.FAILED)
                ) ||
                (current?.concurrentNoticePhase == ActionFeedbackPhase.FAILED && current.concurrentNotice != baseline?.concurrentNotice)
        if (opacityPreviewAccepted && !previewFailed) {
            actionDispatcher.reportCompleted("Set layer opacity", "Layer opacity set to ${percent.toInt()}%")
        }
        opacityEditOpen = false
        opacityPreviewAccepted = false
        opacityFeedbackAtStart = null
    }

    fun setBlendMode(mode: BlendMode) {
        val id = state.value.primary ?: run {
            reportNoOp("Set blend mode", "Select a layer before changing its blend mode")
            return
        }
        run(EditCommand.SetBlendMode(id, mode), action = "Set blend mode to ${mode.displayName}")
    }

    /** Re-develops the RAW smart object [id] with [settings] off the main thread; one undo step, reported like any edit. */
    fun setRawDevelopSettings(id: LayerId, settings: app.croptop.core.model.DevelopSettings): kotlinx.coroutines.Job =
        viewModelScope.launch(Dispatchers.Default) {
            val document = handle.value ?: return@launch
            actionDispatcher.report("Develop settings", document.setRawDevelopSettings(id, settings))
        }

    /** Live Blend If preview from the Blending Options dialog; null restores the committed sliders. */
    fun previewBlendIf(id: LayerId, blendIf: app.croptop.core.model.BlendIfSettings?): Boolean =
        handle.value?.previewBlendIf(id, blendIf) is CommandResult.Accepted

    /** Live fill-opacity preview from the Blending Options dialog; null restores the committed value. */
    fun previewFillOpacity(id: LayerId, fillOpacity: Float?): Boolean =
        handle.value?.previewFillOpacity(id, fillOpacity) is CommandResult.Accepted

    // ---- Smart filter mask ----

    /** The smart object whose filter mask is being painted (the canvas tool takes over input while set). */
    val filterMaskPaintTarget = kotlinx.coroutines.flow.MutableStateFlow<LayerId?>(null)

    fun startFilterMaskPaint(id: LayerId) {
        val info = state.value.layers.find(id)?.smartObject
        if (info?.filterMask == null) {
            reportNoOp("Paint filter mask", "Add a filter mask to the smart object first")
            return
        }
        filterMaskPaintTarget.value = id
        reportUiActionCompleted("Start painting the smart filter mask")
    }

    fun endFilterMaskPaint() {
        if (filterMaskPaintTarget.value != null) reportUiActionCompleted("Finish painting the smart filter mask")
        filterMaskPaintTarget.value = null
    }

    fun addSmartFilterMask(id: LayerId, fill: app.croptop.core.engine.api.MaskFill) =
        result(handle.value?.addSmartFilterMask(id, fill), "Add smart filter mask")

    fun setSmartFilterMaskEnabled(id: LayerId, enabled: Boolean) =
        result(handle.value?.setSmartFilterMaskEnabled(id, enabled), if (enabled) "Enable smart filter mask" else "Disable smart filter mask")

    fun deleteSmartFilterMask(id: LayerId): Boolean {
        if (filterMaskPaintTarget.value == id) filterMaskPaintTarget.value = null
        return result(handle.value?.deleteSmartFilterMask(id), "Delete smart filter mask")
    }

    fun invertSmartFilterMask(id: LayerId) = result(handle.value?.invertSmartFilterMask(id), "Invert smart filter mask")

    fun paintSmartFilterMask(id: LayerId, points: List<app.croptop.core.model.DocPoint>, radius: Float, reveal: Boolean) =
        result(handle.value?.paintSmartFilterMask(id, points, radius, reveal), "Paint smart filter mask")

    /** Layer ▸ Blending Options: Fill opacity and Blend If as one undo step. */
    fun setBlendOptions(id: LayerId, fillOpacity: Float, blendIf: app.croptop.core.model.BlendIfSettings): Boolean {
        val result = run(
            EditCommand.SetLayerProps(id, LayerProperties(fillOpacity = fillOpacity, blendIf = blendIf)),
            action = "Change blending options",
        )
        return result is CommandResult.Accepted
    }

    /** Solo: Alt-click the eye — show only this layer, or restore all if it's already solo. */
    fun solo(id: LayerId) {
        val tree = state.value.layers
        val target = tree.find(id) ?: run {
            reportNoOp("Solo layer", "Select a layer before changing visibility")
            return
        }
        val nodes = tree.flatten()
        val targetIds = if (target.isGroup) {
            val descendants = mutableSetOf<LayerId>()
            fun collect(node: LayerNode) {
                descendants += node.id
                node.children.forEach(::collect)
            }
            collect(target)
            descendants
        } else {
            setOf(id)
        }
        val keepVisible = targetIds.toMutableSet()
        var parent = tree.parentOf(id)
        while (parent != null) {
            keepVisible += parent.id
            parent = tree.parentOf(parent.id)
        }
        val alreadySolo = nodes.all { node -> node.visible == (node.id in keepVisible) }
        val layerName = target.name
        val action = if (alreadySolo) "Show all layers" else "Solo $layerName"
        for (node in nodes) {
            val visible = alreadySolo || node.id in keepVisible
            if (node.visible != visible &&
                setVisible(node.id, visible, quietSuccess = true, action = action) !is CommandResult.Accepted
            ) {
                return
            }
        }
        actionDispatcher.reportCompleted(action)
    }

    /** Ctrl+[ / Ctrl+] (one step) and Ctrl+Shift+[ / ] (to bottom/top) within the same parent. */
    fun restack(delta: Int, toEnd: Boolean = false) {
        val id = state.value.primary ?: run {
            reportNoOp("Move layer", "Select a layer to move")
            return
        }
        val tree = state.value.layers
        val parent = tree.parentOf(id)
        val siblings = parent?.children ?: tree.layers
        val i = siblings.indexOfFirst { it.id == id }
        // Panel order is top-first: "up" = lower index.
        val target = when {
            toEnd && delta < 0 -> 0
            toEnd -> siblings.lastIndex
            else -> (i + delta).coerceIn(0, siblings.lastIndex)
        }
        if (target != i) {
            run(EditCommand.MoveLayer(id, parent?.id, target))
        } else {
            val edge = if (delta < 0) "top" else "bottom"
            actionDispatcher.reportCompleted("Move layer", "Layer is already at the $edge")
        }
    }

    /** Drag-and-drop in the panel: place [id] at [index] under [parentId]. */
    fun move(id: LayerId, parentId: LayerId?, index: Int) = run(EditCommand.MoveLayer(id, parentId, index))

    /** Color under the cursor for the status bar / Info readout (merged, 1px). */
    fun sample(point: DocPoint, mode: SampleMode = SampleMode.MERGED, size: Int = 1): RgbaColor? =
        handle.value?.trySample(point, mode, size)?.color

    // ---- Colors ----

    private val _colors = MutableStateFlow(ColorState())
    val colors: StateFlow<ColorState> = _colors.asStateFlow()

    fun setForeground(c: RgbaColor) = _colors.update { it.copy(foreground = c) }

    fun setBackground(c: RgbaColor) = _colors.update { it.copy(background = c) }

    /** Commit a color choice to the recent list (called when a pick "lands", not on every drag frame). */
    fun rememberColor(c: RgbaColor) = _colors.update { s -> s.copy(recent = (listOf(c) + s.recent.filterNot { it.toArgb() == c.toArgb() }).take(12)) }

    fun swapColors(reportCompletion: Boolean = true) {
        _colors.update { it.copy(foreground = it.background, background = it.foreground) }
        if (reportCompletion) actionDispatcher.reportCompleted("Swap colors")
    }

    fun defaultColors(reportCompletion: Boolean = true) {
        _colors.update { it.copy(foreground = RgbaColor.Black, background = RgbaColor.White) }
        if (reportCompletion) actionDispatcher.reportCompleted("Reset colors")
    }

    // ---- Files ----

    /** Ctrl+S / Save As: writes the native .croptop document to [uri]. */
    fun save(uri: String, onDone: () -> Unit = {}) {
        if (actionDispatcher.hasInFlightAction) {
            reportUiCommandResult(
                "Save",
                CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Wait for the current action to finish before saving"),
            )
            return
        }
        val h = handle.value ?: run {
            actionDispatcher.reportFailed("Save", "No document is open")
            return
        }
        actionDispatcher.dispatch(
            action = "Save",
            onBusy = {
                reportUiCommandResult(
                    "Save",
                    CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Wait for the current action to finish before saving"),
                )
            },
        ) {
            busy.value = "Saving…"
            try {
                h.save(SaveTarget.ContentUri(uri))
                // Keep the destination, while retaining dirty state if edits raced this snapshot.
                saveTarget.value = uri
                val fallbackTitle = title.value ?: h.info.value.name
                val providerName = try {
                    withContext(Dispatchers.IO) { names.displayName(uri) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                title.value = providerName?.takeIf { it.isNotBlank() } ?: displayName(uri, fallbackTitle)
                val currentTitle = title.value ?: displayName(uri, h.info.value.name)
                val recentsFailure = try {
                    recents.recordOpened(uri, currentTitle, h.info.value.size)
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failure
                }
                val currentDirty = h.info.value.dirty
                val detail = when {
                    recentsFailure != null && currentDirty -> "Saved; newer edits remain unsaved, and Recents couldn't be updated"
                    recentsFailure != null -> "Saved; Recents couldn't be updated"
                    currentDirty -> "Saved a version; newer edits remain unsaved"
                    else -> "Saved"
                }
                message.value = detail
                if (!currentDirty) onDone()
                ActionOutcome.Completed(detail)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val detail = "Couldn't save: ${failure.message ?: failure::class.simpleName ?: "unknown error"}"
                message.value = detail
                ActionOutcome.Failed(detail)
            } finally {
                busy.value = null
            }
        } ?: return
    }

    fun export(uri: String, options: ExportOptions) {
        if (actionDispatcher.hasInFlightAction) {
            reportUiCommandResult(
                "Export",
                CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Wait for the current action to finish before exporting"),
            )
            return
        }
        val h = handle.value ?: run {
            actionDispatcher.reportFailed("Export", "No document is open")
            return
        }
        if (activeExportJob?.isActive == true) {
            reportUiActionRejected("Export", RejectionCode.INVALID_ARGUMENT, "An export is already running")
            return
        }
        val exportJob = actionDispatcher.dispatch(
            action = "Export",
            cancelable = true,
            onCancel = { activeExportJob?.cancel() },
            onBusy = {
                reportUiCommandResult(
                    "Export",
                    CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Wait for the current action to finish before exporting"),
                )
            },
        ) {
            val job = currentCoroutineContext()[Job]
            activeExportJob = job
            busy.value = "Exporting…"
            exportWarning.value = null
            try {
                val result = h.export(ExportTarget.ContentUri(uri), options)
                exportWarning.value = result.warnings.takeIf { it.isNotEmpty() }?.let { warnings ->
                    ExportCompatibilityNotice(options.format, warnings.toList())
                }
                val warnings = result.warnings.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = " · ") ?: ""
                val detail = "Exported ${result.width} × ${result.height} ${options.format.name}$warnings"
                message.value = detail
                ActionOutcome.Completed(detail)
            } catch (e: kotlinx.coroutines.CancellationException) {
                message.value = "Export canceled"
                throw e
            } catch (e: Exception) {
                val detail = "Couldn't export: ${e.message ?: e::class.simpleName ?: "unknown error"}"
                message.value = detail
                ActionOutcome.Failed(detail)
            } finally {
                if (activeExportJob === job) {
                    activeExportJob = null
                    busy.value = null
                }
            }
        } ?: return
        activeExportJob = exportJob
    }

    fun dismissExportWarning() {
        exportWarning.value = null
    }

    fun cancelExport() {
        if (actionDispatcher.cancel()) return
        activeExportJob?.takeIf(Job::isActive)?.let { job ->
            message.value = "Canceling export…"
            job.cancel()
        }
    }

    /**
     * Renders the merged image to a shareable cache file (PNG) and returns its content:// URI.
     * Used by Share, Copy Merged and drag-out.
     */
    suspend fun exportForSharing(action: String = "Prepare image"): String? {
        if (actionDispatcher.hasInFlightAction) {
            reportUiActionRejected(
                action,
                RejectionCode.INVALID_ARGUMENT,
                "Wait for the current action to finish before preparing the image",
            )
            return null
        }
        val h = handle.value ?: run {
            reportUiCommandResult(action, CommandResult.Rejected(RejectionCode.CLOSED, "No document is open"))
            return null
        }
        if (activeExportJob?.isActive == true) {
            reportUiActionRejected(action, RejectionCode.INVALID_ARGUMENT, "An export is already running")
            return null
        }
        val result = CompletableDeferred<String?>()
        val name = h.info.value.name.substringBeforeLast('.').ifBlank { "CropTop" } + ".png"
        val file = shareCache.newFile(name)
        val operation = actionDispatcher.dispatch(
            action = action,
            cancelable = true,
            onCancel = { activeExportJob?.cancel() },
            onBusy = {
                reportUiActionRejected(
                    action,
                    RejectionCode.INVALID_ARGUMENT,
                    "Wait for the current action to finish before preparing the image",
                )
            },
        ) {
            val job = currentCoroutineContext()[Job]
            activeExportJob = job
            busy.value = "Preparing…"
            try {
                h.export(ExportTarget.AppFile(file.absolutePath), ExportOptions(ExportFormat.PNG))
                val uri = shareCache.uriFor(file)
                result.complete(uri)
                ActionOutcome.Completed("Image ready to share")
            } catch (cancelled: CancellationException) {
                result.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                val detail = "Couldn't prepare the image: ${failure.message ?: failure::class.simpleName ?: "unknown error"}"
                message.value = detail
                result.complete(null)
                ActionOutcome.Failed(detail)
            } finally {
                if (activeExportJob === job) {
                    activeExportJob = null
                    busy.value = null
                }
            }
        } ?: return null
        activeExportJob = operation
        return try {
            result.await()
        } catch (cancelled: CancellationException) {
            operation.cancel()
            throw cancelled
        }
    }

    fun showMessage(text: String) {
        message.value = text
    }

    private fun reportNoOp(action: String, detail: String) {
        message.value = detail
        actionDispatcher.report(action, CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, detail))
    }

    // ---- History ----

    // ---- engine-api v3: selections, move/transform, canvas (rejections explain themselves) ----

    private fun result(r: CommandResult?, action: String = "Edit"): Boolean {
        if (r == null) {
            actionDispatcher.reportFailed(action, "No document is open")
            return false
        }
        actionDispatcher.report(action, r)
        if (r is CommandResult.Rejected) message.value = r.message
        return r is CommandResult.Accepted
    }

    /** Completes synchronous document metadata mutations that intentionally emit no render event. */
    private fun immediateResult(r: CommandResult?, action: String): Boolean {
        if (r == null) {
            actionDispatcher.reportFailed(action, "No document is open")
            return false
        }
        if (r is CommandResult.Rejected) {
            actionDispatcher.report(action, r)
            message.value = r.message
            return false
        }
        actionDispatcher.reportCompleted(action)
        return true
    }

    /** Slider previews can be emitted at pointer frequency; report only failures until the settled commit. */
    private fun previewResult(r: CommandResult?, action: String): Boolean {
        if (r == null) {
            actionDispatcher.reportFailed(action, "No document is open")
            return false
        }
        if (r is CommandResult.Rejected) {
            actionDispatcher.report(action, r)
            message.value = r.message
            return false
        }
        actionDispatcher.reportQuietly(action, r)
        return true
    }

    /**
     * The exact outline of the last REPLACE selection (ellipse/lasso) and the history step it belongs to,
     * so the ants follow the shape only while that step is current (undo/redo can't leave it stale).
     */
    private val outline = MutableStateFlow<Pair<Int, app.croptop.core.model.SelectionShape>?>(null)
    val selectionShape: StateFlow<app.croptop.core.model.SelectionShape?> = combine(outline, state) { o, s ->
        o?.takeIf { it.first == s.history.current }?.second
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun selectShape(
        shape: app.croptop.core.model.SelectionShape,
        op: app.croptop.core.model.SelectionOp,
        feather: Float = 0f,
        action: String = "Select pixels",
    ): Boolean {
        val ok = result(handle.value?.select(shape, op, feather), action)
        // Read the engine's history directly: `state` may not have caught up with the new step yet.
        val step = handle.value?.history?.value?.current
        if (ok) outline.value = if (op == app.croptop.core.model.SelectionOp.REPLACE && step != null) step to shape else null
        return ok
    }

    fun magicWand(point: DocPoint, tolerance: Int, contiguous: Boolean, sampleAll: Boolean, op: app.croptop.core.model.SelectionOp): Boolean =
        boxSelection(handle.value?.magicWand(point, tolerance, contiguous, sampleAll, op), "Magic wand selection")

    /** Null (with the engine's reason in the status bar) when this build can't do Color Range yet. */
    fun beginColorRange(
        op: app.croptop.core.model.SelectionOp = app.croptop.core.model.SelectionOp.REPLACE,
    ): app.croptop.core.engine.api.ColorRangeSession? {
        val h = handle.value ?: run {
            actionDispatcher.reportFailed("Select color range", "No document is open")
            return null
        }
        val session = h.beginColorRange(op)
        if (session is app.croptop.core.engine.api.UnsupportedColorRangeSession) {
            result(session.commit(), "Select color range")
            return null
        }
        return session
    }

    fun commitColorRange(session: app.croptop.core.engine.api.ColorRangeSession): Boolean = boxSelection(session.commit(), "Select color range")

    fun beginQuickSelection(spec: app.croptop.core.engine.api.QuickSelectionSpec): app.croptop.core.engine.api.QuickSelectionSession? =
        handle.value?.beginQuickSelection(spec)?.also {
            actionDispatcher.reportCompleted("Start quick selection", "Quick selection stroke started")
        } ?: run {
            actionDispatcher.reportFailed("Quick selection", "No active document or quick selection session")
            null
        }

    fun cancelQuickSelection() = actionDispatcher.reportCompleted("Cancel quick selection", "Quick selection canceled")

    /** Ends a Quick Selection stroke: the selection is no longer the last marquee/lasso outline. */
    fun quickSelectionResult(r: CommandResult): Boolean = boxSelection(r, "Quick selection")

    fun selectObject(region: app.croptop.core.model.SelectionShape, op: app.croptop.core.model.SelectionOp, sampleAll: Boolean): Boolean =
        runSelectionJob("Selecting object") { it.selectObject(region, op, sampleAll) }

    /** Select ▸ Subject: the model when it is on the device, the first-use download sheet when it is not, the basic selector otherwise. */
    fun selectSubject(sampleAll: Boolean = false): Boolean =
        aiSelect(app.croptop.core.ai.AiFeature.SELECT_SUBJECT) { selectSubjectBasic(sampleAll) }

    private fun selectSubjectBasic(sampleAll: Boolean): Boolean =
        runSelectionJob("Selecting subject") { it.selectSubject(sampleAll = sampleAll) }

    fun selectSky(): Boolean = aiSelect(app.croptop.core.ai.AiFeature.SELECT_SKY)

    fun selectBackground(): Boolean = aiSelect(app.croptop.core.ai.AiFeature.SELECT_BACKGROUND)

    /** Adds a "Depth map" layer estimated from the picture (Lens Blur's depth source); asks for the model first when needed. */
    fun estimateDepth(): Boolean = aiSelect(app.croptop.core.ai.AiFeature.DEPTH_MAP)

    /** The Remove brush (ToolId.AI_REMOVE): fills what the stroke covered from its surroundings, on a new layer; asks for the model first. */
    fun removeStroke(points: List<app.croptop.core.model.DocPoint>, radius: Float): Boolean {
        val doc = handle.value ?: run {
            actionDispatcher.reportFailed("Remove", "No document is open")
            return false
        }
        if (!aiReady(app.croptop.core.ai.AiFeature.REMOVE, { removeStroke(points, radius) }, null)) return false
        return dispatchUiAction("Remove") {
            when (val result = ai.removeStroke(doc, points, radius)) {
                is CommandResult.Accepted -> ActionOutcome.Completed("Remove completed")
                is CommandResult.Rejected -> {
                    message.value = result.message
                    ActionOutcome.Rejected(result.code, result.message)
                }
            }
        } != null
    }

    // ---- Soft proof (View ▸ Proof Colors / Gamut Warning) ----

    /** The proof setup the toggles come back to (target, intent, ink limit); remembered for the session. */
    private var proofSetup = app.croptop.core.model.SoftProofSettings()

    private fun applySoftProof(label: String, settings: app.croptop.core.model.SoftProofSettings?): Boolean {
        val doc = handle.value ?: run {
            actionDispatcher.reportFailed(label, "No document is open")
            return false
        }
        val result = doc.setSoftProof(settings)
        reportUiCommandResult(label, result)
        if (result is CommandResult.Rejected) message.value = result.message
        return result is CommandResult.Accepted
    }

    /** Ctrl+Y: show the picture as the proof device would reproduce it, or go back to normal. */
    fun toggleProofColors(): Boolean {
        val active = handle.value?.softProof?.value
        return if (active != null) applySoftProof("Turn off Proof Colors", null) else applySoftProof("Proof Colors", proofSetup.copy(gamutWarning = false))
    }

    /** Ctrl+Shift+Y: mark colours the proof device cannot print (turns the proof on when it is off). */
    fun toggleGamutWarning(): Boolean {
        val active = handle.value?.softProof?.value
        return when {
            active == null -> applySoftProof("Gamut Warning", proofSetup.copy(gamutWarning = true))
            active.gamutWarning -> applySoftProof("Turn off Gamut Warning", active.copy(gamutWarning = false))
            else -> applySoftProof("Gamut Warning", active.copy(gamutWarning = true))
        }
    }

    /** View ▸ Proof Setup…: the chosen device and rendering intent; applies at once and remembers them. */
    fun setProofSetup(settings: app.croptop.core.model.SoftProofSettings): Boolean {
        proofSetup = settings.copy(gamutWarning = false)
        return applySoftProof("Proof Setup", settings)
    }

    /** What the dialog starts from: the live proof, else the remembered setup. */
    fun proofSetupDefaults(): app.croptop.core.model.SoftProofSettings = handle.value?.softProof?.value ?: proofSetup

    /** Free Remove Background uses left this month (what the button shows for Free users). */
    /** Quick action: hide everything but the subject with a layer mask. */
    fun removeBackground(): Boolean = aiSelect(app.croptop.core.ai.AiFeature.REMOVE_BACKGROUND)

    /** Edit ▸ Remove (AI): fills the selection from its surroundings on a new layer. */
    fun removeSelection(): Boolean = aiSelect(app.croptop.core.ai.AiFeature.REMOVE)

    /** A click with the Object Select tool: the model selects the object under it (a plain click without the model clears). */
    fun selectObjectAt(point: DocPoint, op: app.croptop.core.model.SelectionOp = app.croptop.core.model.SelectionOp.REPLACE): Boolean {
        val size = state.value.info?.size ?: return false
        val click = app.croptop.core.ai.PromptSegmenter.Click(point.x / size.width, point.y / size.height)
        return aiSelect(app.croptop.core.ai.AiFeature.OBJECT_SELECT, op, listOf(click)) { deselect() }
    }

    /** What the first-use download sheet is waiting on: the feature, the models to fetch, and what "Not now" does. */
    data class AiPrompt(
        val feature: app.croptop.core.ai.AiFeature,
        val models: List<app.croptop.core.ai.ModelSpec>,
        val bytes: Long,
        val retry: () -> Unit,
        val basic: (() -> Unit)?,
    )

    val aiPrompt = MutableStateFlow<AiPrompt?>(null)

    /** Live state of the downloadable models, for the sheet. */
    val aiModels get() = ai.models

    fun installAiModel(id: String) = ai.install(id)

    fun cancelAiModel(id: String) = ai.cancelInstall(id)

    fun dismissAiPrompt(useBasic: Boolean = false) {
        val prompt = aiPrompt.value
        aiPrompt.value = null
        if (useBasic) prompt?.basic?.invoke()
    }

    /**
     * True when [feature]'s models are on the device. Otherwise opens the download sheet (re-running [retry] when it finishes) or,
     * where the runtime is unavailable, runs [basic] / reports why.
     */
    private fun aiReady(feature: app.croptop.core.ai.AiFeature, retry: () -> Unit, basic: (() -> Unit)?): Boolean {
        when (val availability = ai.availability(feature)) {
            app.croptop.core.ai.AiRuntime.Availability.Ready -> return true
            is app.croptop.core.ai.AiRuntime.Availability.NeedsDownload -> {
                aiPrompt.value = AiPrompt(feature, availability.specs, availability.bytes, retry, basic)
                return false
            }
            is app.croptop.core.ai.AiRuntime.Availability.Unavailable -> {
                if (basic != null) {
                    basic()
                    return false
                }
                message.value = availability.reason
                actionDispatcher.reportFailed(feature.label, availability.reason)
                return false
            }
        }
    }

    /** Runs AI Denoise ([strength] 0–1) or Super Resolution ([factor] 2 or 4) with progress and Cancel in the action status. */
    fun runAiRestoration(feature: app.croptop.core.ai.AiFeature, strength: Float = 1f, factor: Int = 2): Boolean {
        require(feature == app.croptop.core.ai.AiFeature.DENOISE || feature == app.croptop.core.ai.AiFeature.UPSCALE)
        val doc = handle.value ?: run {
            actionDispatcher.reportFailed(feature.label, "No document is open")
            return false
        }
        if (!aiReady(feature, { runAiRestoration(feature, strength, factor) }, null)) return false
        var job: Job? = null
        job = actionDispatcher.dispatch(
            action = feature.label,
            cancelable = true,
            onCancel = { job?.cancel() },
            onBusy = { message.value = "Wait for the current action to finish before ${feature.label.lowercase()}" },
        ) { report ->
            val result = when (feature) {
                app.croptop.core.ai.AiFeature.DENOISE -> ai.denoise(doc, strength) { report(it) }
                else -> ai.upscale(doc, factor) { report(it) }
            }
            when (result) {
                is CommandResult.Accepted -> ActionOutcome.Completed("${feature.label} completed")
                is CommandResult.Rejected -> {
                    message.value = result.message
                    ActionOutcome.Rejected(result.code, result.message)
                }
            }
        }
        return job != null
    }

    private fun aiSelect(
        feature: app.croptop.core.ai.AiFeature,
        op: app.croptop.core.model.SelectionOp = app.croptop.core.model.SelectionOp.REPLACE,
        clicks: List<app.croptop.core.ai.PromptSegmenter.Click> = emptyList(),
        afterAccepted: () -> Unit = {},
        basic: (() -> Unit)? = null,
    ): Boolean {
        val doc = handle.value ?: run {
            actionDispatcher.reportFailed(feature.label, "No document is open")
            return false
        }
        if (ai.availability(feature) is app.croptop.core.ai.AiRuntime.Availability.Unavailable && basic != null) {
            basic()
            return true
        }
        if (!aiReady(feature, { aiSelect(feature, op, clicks, afterAccepted, basic) }, basic)) return false
        return dispatchUiAction(feature.label) {
            val result = when (feature) {
                app.croptop.core.ai.AiFeature.SELECT_SUBJECT -> ai.selectSubject(doc, op)
                app.croptop.core.ai.AiFeature.SELECT_BACKGROUND -> ai.selectBackground(doc, op)
                app.croptop.core.ai.AiFeature.SELECT_SKY -> ai.selectSky(doc, op)
                app.croptop.core.ai.AiFeature.OBJECT_SELECT -> ai.selectObject(doc, clicks, op)
                app.croptop.core.ai.AiFeature.DEPTH_MAP -> ai.estimateDepth(doc)
                app.croptop.core.ai.AiFeature.REMOVE -> ai.removeSelection(doc)
                app.croptop.core.ai.AiFeature.REMOVE_BACKGROUND -> ai.removeBackground(doc)
                app.croptop.core.ai.AiFeature.DENOISE, app.croptop.core.ai.AiFeature.UPSCALE -> error("${feature.label} runs through runAiRestoration")
            }
            when (result) {
                is CommandResult.Accepted -> {
                    outline.value = null
                    afterAccepted()
                    ActionOutcome.Completed("${feature.label} completed")
                }
                is CommandResult.Rejected -> {
                    message.value = result.message
                    ActionOutcome.Rejected(result.code, result.message)
                }
            }
        } != null
    }

    private var selectionJob: app.croptop.core.engine.api.EngineJob? = null

    /**
     * Runs a model-backed selection off the UI thread with progress in the status bar. Returns
     * false when there is no document or another long-running action is active.
     */
    private fun runSelectionJob(
        label: String,
        start: (app.croptop.core.engine.api.DocumentHandle) -> app.croptop.core.engine.api.EngineJob,
    ): Boolean {
        val h = handle.value ?: run {
            actionDispatcher.reportFailed(label, "No document is open")
            return false
        }
        if (actionDispatcher.hasInFlightAction) {
            message.value = "Wait for the current action to finish before changing the selection"
            return false
        }
        var cancelBeforeStart = false
        actionDispatcher.dispatch(
            action = label,
            cancelable = true,
            onCancel = {
                val job = selectionJob
                if (job == null) cancelBeforeStart = true else job.cancel()
            },
            onBusy = { message.value = "Wait for the current action to finish before changing the selection" },
        ) { reportProgress ->
            val job = start(h)
            selectionJob = job
            if (cancelBeforeStart) job.cancel()
            val progress = viewModelScope.launch { job.progress.collect(reportProgress) }
            try {
                when (val result = job.await()) {
                    is CommandResult.Accepted -> {
                        outline.value = null
                        ActionOutcome.Completed("$label completed")
                    }

                    is CommandResult.Rejected -> {
                        message.value = result.message
                        ActionOutcome.Rejected(result.code, result.message)
                    }
                }
            } finally {
                progress.cancel()
                if (selectionJob === job) selectionJob = null
            }
        } ?: return false
        return true
    }

    /** Esc while a model-backed selection runs. */
    fun cancelSelectionJob(): Boolean {
        if (actionDispatcher.cancel()) return true
        val job = selectionJob ?: return false
        job.cancel()
        return true
    }

    fun paintBucket(point: DocPoint, color: RgbaColor, tolerance: Int, contiguous: Boolean, sampleAll: Boolean, opacity: Float): Boolean =
        result(handle.value?.paintBucket(point, color, tolerance, contiguous, sampleAll, opacity), "Fill area")

    fun drawGradient(
        start: DocPoint,
        end: DocPoint,
        gradient: app.croptop.core.model.ColorGradient,
        shape: app.croptop.core.model.GradientShape,
        reverse: Boolean,
        dither: Boolean,
        opacity: Float,
    ): Boolean = result(handle.value?.drawGradient(start, end, gradient, shape, reverse, dither, opacity), "Draw gradient")

    private fun boxSelection(r: CommandResult?, action: String = "Change selection") = result(r, action).also { if (it) outline.value = null }

    fun selectAll() = boxSelection(handle.value?.selectAll(), "Select all")

    fun deselect() = boxSelection(handle.value?.deselect(), "Deselect")

    fun reselect() = boxSelection(handle.value?.reselect(), "Reselect")

    fun invertSelection() = boxSelection(handle.value?.invertSelection(), "Invert selection")

    /** Alt-click a mask thumbnail: shows that mask alone, or returns to the composite. */
    fun toggleMaskView(id: LayerId): Boolean {
        val document = handle.value ?: return result(null, "View Mask")
        val next = if (document.maskView.value == id) null else id
        return result(document.setMaskView(next), if (next == null) "Show composite" else "View Mask")
    }

    /** Select ▸ Select and Mask: a live refine session on the current selection, or null (with feedback) when there is none. */
    fun beginRefineEdge(): app.croptop.core.engine.api.RefineEdgeSession? {
        val document = handle.value ?: run {
            result(null, "Select and Mask")
            return null
        }
        if (!state.value.hasSelection) {
            reportUiActionRejected("Select and Mask", RejectionCode.INVALID_ARGUMENT, "Make a selection first.")
            return null
        }
        return document.beginRefineEdge()
    }

    /** Registers a committed Select and Mask New Document output and returns its sibling-tab route. */
    fun refineOutputDocumentRoute(session: app.croptop.core.engine.api.RefineEdgeSession): EditorRoute? {
        val output = session.outputDocument ?: run {
            reportUiActionFailed("Open Select and Mask output", "The refined document was not created")
            return null
        }
        openDocuments.register(output)
        return EditorRoute(openDocumentId = output.id.value)
    }

    /** Magnetic Lasso: a live-wire session on the primary layer, or null (with feedback) when there is none. */
    fun beginMagneticLasso(
        start: app.croptop.core.model.DocPoint,
        spec: app.croptop.core.engine.api.MagneticLassoSpec,
    ): app.croptop.core.engine.api.MagneticLassoSession? {
        val document = handle.value ?: run {
            result(null, "Magnetic Lasso")
            return null
        }
        val session = document.beginMagneticLasso(start, spec)
        if (session is app.croptop.core.engine.api.UnsupportedMagneticLassoSession) {
            reportUiActionRejected("Magnetic Lasso", RejectionCode.INVALID_ARGUMENT, "Select a pixel layer to trace.")
            return null
        }
        return session
    }

    fun redEye(region: app.croptop.core.model.DocRectF, pupilSize: Float, darken: Float) =
        result(handle.value?.redEye(region, pupilSize, darken), "Red Eye")

    fun patch(sourceOffset: app.croptop.core.model.DocPoint, contentAware: Boolean) =
        result(handle.value?.patch(sourceOffset, contentAware), "Patch")

    /** Type tool commit: a new text layer above the primary layer. */
    fun createTextLayer(text: app.croptop.core.model.TextLayerInfo) = result(handle.value?.createTextLayer(newId(), text), "Add text")

    /** Type tool edit of an existing text layer. */
    fun setTextLayer(id: LayerId, text: app.croptop.core.model.TextLayerInfo) = result(handle.value?.setTextLayer(id, text), "Edit text")

    /** Update the committed layer-style stack through the document contract. */
    fun setLayerStyles(id: LayerId, styles: LayerStyles?): Boolean =
        result(handle.value?.setLayerStyles(id, styles), "Set layer style")

    /** Update a transient layer-style preview without adding a history step. */
    fun previewLayerStyles(id: LayerId, styles: LayerStyles?): Boolean {
        val document = handle.value ?: return previewResult(null, "Preview layer style")
        return previewResult(document.previewLayerStyles(id, styles), "Preview layer style")
    }

    private var copiedLayerStyles: LayerStyles? = null

    fun copyLayerStyle(styles: LayerStyles) {
        copiedLayerStyles = styles
        actionDispatcher.reportCompleted("Copy layer style")
    }

    fun pastedLayerStyle(): LayerStyles? = copiedLayerStyles ?: run {
        reportNoOp("Paste layer style", "Copy a layer style first")
        null
    }

    /** Select ▸ Edit in Quick Mask Mode (Q): toggles painting into the selection. */
    fun toggleQuickMask(): Boolean {
        val document = handle.value ?: return result(null, "Quick Mask")
        val enable = !document.quickMask.value
        return result(document.setQuickMask(enable), if (enable) "Quick Mask on" else "Quick Mask off")
    }

    /** Select ▸ Save Selection: stores the selection as the next "Alpha N" channel. */
    fun saveSelection(name: String = "") = immediateResult(handle.value?.saveSelection(name), "Save Selection")

    fun deleteSelectionChannel(name: String): Boolean =
        immediateResult(handle.value?.deleteSelectionChannel(name), "Delete $name")

    /** Select ▸ Load Selection: the most recently saved channel, or [name] when given. */
    fun loadSelection(name: String? = null, op: app.croptop.core.model.SelectionOp = app.croptop.core.model.SelectionOp.REPLACE): Boolean {
        val document = handle.value ?: return result(null, "Load Selection")
        val channel = name ?: document.selectionChannels.value.lastOrNull()?.name
        if (channel == null) {
            reportUiActionRejected("Load Selection", app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT, "Save a selection first.")
            return false
        }
        return boxSelection(document.loadSelection(channel, op), "Load Selection")
    }

    /** Starts a live Modify Selection preview (cancel restores the selection; commit is one history step). */
    fun beginModifySelection(kind: app.croptop.core.model.SelectionModify): app.croptop.core.engine.api.SelectionModifySession? =
        handle.value?.beginModifySelection(kind)

    /** Applies [px] through the open preview [session] and commits it as one step; false when the engine rejects either call. */
    fun commitModifySelection(session: app.croptop.core.engine.api.SelectionModifySession, kind: app.croptop.core.model.SelectionModify, px: Float): Boolean {
        val updated = session.update(px)
        if (updated is CommandResult.Rejected) {
            session.cancel()
            return boxSelection(updated, "${kind.label} selection")
        }
        return boxSelection(session.commit(), "${kind.label} selection")
    }

    fun modifySelection(kind: app.croptop.core.model.SelectionModify, px: Float) =
        boxSelection(handle.value?.modifySelection(kind, px), "${kind.label} selection")

    /** Move tool: one history step per gesture or nudge. */
    fun translateSelected(dx: Float, dy: Float): Boolean {
        val ids = state.value.selected.ifEmpty { setOfNotNull(state.value.primary) }
        if (ids.isEmpty()) {
            reportNoOp("Move layer", "Select a layer to move")
            return false
        }
        if (dx == 0f && dy == 0f) return false
        return result(handle.value?.translateLayers(ids, dx, dy), "Move layers")
    }

    fun layerAt(p: DocPoint): LayerId? = handle.value?.layerAt(p)

    /** Edit ▸ Transform ▸ Warp commit: one history step for pixel layer [id]. */
    fun warpLayer(id: LayerId, mesh: app.croptop.core.model.WarpMesh): Boolean =
        result(handle.value?.warpLayer(id, mesh), "Warp")

    fun beginTransform(target: app.croptop.core.engine.api.TransformTarget) = handle.value?.beginTransform(target)

    fun commitTransform(
        session: app.croptop.core.engine.api.TransformSession,
        action: String = "Transform selection",
    ) = result(session.commit(), action)

    fun crop(rect: app.croptop.core.model.DocRectF, angle: Float = 0f, deleteCropped: Boolean = true) = result(
        handle.value?.crop(rect, angle, deleteCropped),
        "Crop",
    )

    fun cropPerspective(quad: app.croptop.core.model.CropQuad, deleteCropped: Boolean = true) = result(
        handle.value?.cropPerspective(quad, deleteCropped),
        "Perspective crop",
    )

    /** Image ▸ Crop: crops to the selection's bounds. */
    fun cropToSelection(): Boolean {
        val sel = state.value.selection as? app.croptop.core.model.SelectionInfo.Active ?: run {
            reportNoOp("Crop to selection", "Create a selection before cropping")
            return false
        }
        return crop(app.croptop.core.model.DocRectF.of(sel.bounds))
    }

    fun resizeImage(sizing: app.croptop.core.model.ImageSizing, resample: app.croptop.core.model.Resample) =
        result(handle.value?.resizeImage(sizing.size, resample, sizing.ppi), "Resize image")

    fun resizeCanvas(resize: app.croptop.core.model.CanvasResize, extension: app.croptop.core.model.BackgroundContents): Boolean {
        val fill = when (extension) {
            app.croptop.core.model.BackgroundContents.TRANSPARENT -> app.croptop.core.model.CanvasFill.Transparent
            app.croptop.core.model.BackgroundContents.WHITE -> app.croptop.core.model.CanvasFill.Color(RgbaColor.White)
            app.croptop.core.model.BackgroundContents.BLACK -> app.croptop.core.model.CanvasFill.Color(RgbaColor.Black)
            app.croptop.core.model.BackgroundContents.BACKGROUND_COLOR -> app.croptop.core.model.CanvasFill.Color(colors.value.background)
        }
        return result(handle.value?.resizeCanvas(resize.size, resize.anchor, fill), "Resize canvas")
    }

    fun rotateCanvas(degrees: Float) = result(handle.value?.rotateCanvas(degrees), "Rotate canvas")

    fun flipCanvas(horizontal: Boolean) = result(
        handle.value?.flipCanvas(horizontal),
        if (horizontal) "Flip canvas horizontally" else "Flip canvas vertically",
    )

    fun mergeDown(): Boolean {
        val id = state.value.primary ?: run {
            reportNoOp("Merge down", "Select a layer to merge down")
            return false
        }
        return result(handle.value?.mergeDown(id), "Merge down")
    }

    fun stampVisible() = result(handle.value?.stampVisible(newId()), "Stamp visible layers")

    /** Layer ▸ Smart Objects ▸ Convert to Smart Object. */
    fun convertToSmartObject(): Boolean {
        val id = state.value.primary ?: run {
            reportNoOp("Convert to Smart Object", "Select a layer to convert")
            return false
        }
        return result(handle.value?.convertToSmartObject(id), "Convert to Smart Object")
    }

    /** Layer ▸ Smart Objects ▸ Rasterize. */
    fun rasterizeSmartObject(id: app.croptop.core.model.LayerId? = state.value.primary): Boolean {
        val layerId = id ?: run {
            reportNoOp("Rasterize Layer", "Select a smart object to rasterize")
            return false
        }
        return result(handle.value?.rasterizeSmartObject(layerId), "Rasterize Layer")
    }

    /** Opens the embedded source as another editor document tab. */
    fun openSmartObjectContents(id: LayerId): EditorRoute? {
        val parent = handle.value ?: run {
            actionDispatcher.reportFailed("Edit Smart Object Contents", "No document is open")
            return null
        }
        return try {
            val contents = parent.openSmartObjectContents(id)
            openDocuments.register(contents)
            actionDispatcher.reportCompleted("Edit Smart Object Contents")
            EditorRoute(
                openDocumentId = contents.id.value,
                smartObjectParentDocumentId = parent.id.value,
                smartObjectParentLayerId = id.value,
            )
        } catch (failure: Exception) {
            actionDispatcher.reportFailed("Edit Smart Object Contents", failure.message ?: "Could not open smart object contents")
            null
        }
    }

    /** Opens a chosen source document as a child route whose Save replaces this smart object. */
    fun smartObjectReplacementRoute(id: LayerId, sourceUri: String): EditorRoute? {
        val parent = handle.value ?: run {
            actionDispatcher.reportFailed("Replace Smart Object Contents", "No document is open")
            return null
        }
        if (state.value.layers.find(id)?.kind != LayerKind.SMART_OBJECT) {
            reportUiActionRejected(
                "Replace Smart Object Contents",
                RejectionCode.INVALID_ARGUMENT,
                "Select a smart object layer first",
            )
            return null
        }
        actionDispatcher.reportCompleted("Open replacement contents in new tab")
        return EditorRoute(
            sourceUri = sourceUri,
            smartObjectParentDocumentId = parent.id.value,
            smartObjectParentLayerId = id.value,
        )
    }

    /** Saves an embedded-source tab back into its parent layer and closes that tab after acceptance. */
    fun saveSmartObjectContents(onDone: () -> Unit = {}): Boolean {
        val action = if (route.sourceUri != null) "Replace Smart Object Contents" else "Save Smart Object Contents"
        val parentId = route.smartObjectParentDocumentId ?: return false
        val layerValue = route.smartObjectParentLayerId ?: return false
        val parent = openDocuments.byId(parentId) ?: run {
            actionDispatcher.reportFailed(action, "The parent document is no longer open")
            return false
        }
        val contents = handle.value ?: run {
            actionDispatcher.reportFailed(action, "No smart object contents are open")
            return false
        }
        val accepted = immediateResult(
            parent.replaceSmartObjectContents(LayerId(layerValue), contents),
            action,
        )
        if (accepted) onDone()
        return accepted
    }

    // ---- Painting (Brush / Eraser) ----

    /** Starts a stroke on the primary layer; null (with a status message) when there's nowhere to paint. */
    fun beginStroke(brush: app.croptop.core.model.BrushSettings, erase: Boolean): app.croptop.core.engine.api.StrokeSession? {
        val action = if (erase) "Erase stroke" else "Brush stroke"
        val h = handle.value ?: run {
            actionDispatcher.reportFailed(action, "No document is open")
            return null
        }
        val layer = state.value.primaryLayer
        // Quick Mask: strokes paint the selection itself, whatever layer is targeted.
        val quickMask = h.quickMask.value
        if (quickMask || (layer != null && isMaskTargeted(layer))) {
            // Painting on the mask: the color's luminance is the mask value.
            val b = brush.normalized()
            val gray = colors.value.foreground.let { c -> (0.2126f * c.r + 0.7152f * c.g + 0.0722f * c.b).coerceIn(0f, 1f) }
            val spec = app.croptop.core.engine.api.StrokeSpec(
                layer?.id ?: LayerId(0),
                if (erase) RgbaColor.Black else RgbaColor(gray, gray, gray),
                diameter = b.size,
                hardness = b.hardness,
                opacity = b.opacity,
                brush = b,
                erase = erase,
            )
            return h.beginMaskStroke(spec).also { actionDispatcher.reportCompleted(action, "$action started") }
        }
        if (layer == null || layer.kind != app.croptop.core.model.LayerKind.PIXEL) {
            val detail = if (layer ==
                null
            ) {
                "Select a layer to paint on"
            } else {
                "“${layer.name}” isn't a pixel layer. Pick or add a pixel layer to paint."
            }
            message.value = detail
            offerCanvasRecovery(action, detail)
            actionDispatcher.reportFailed(action, detail)
            return null
        }
        if (layer.locks.pixels) {
            val detail = "“${layer.name}” is locked. Add or select an editable pixel layer to paint."
            message.value = detail
            offerCanvasRecovery(action, detail)
            actionDispatcher.reportFailed(action, detail)
            return null
        }
        if (erase && layer.locks.transparency) {
            val detail = "“${layer.name}” has transparency locked, so it can't be erased."
            message.value = detail
            offerCanvasRecovery(action, detail)
            actionDispatcher.reportFailed(action, detail)
            return null
        }
        val b = brush.normalized()
        val spec = app.croptop.core.engine.api.StrokeSpec(
            layer.id,
            if (erase) RgbaColor.Transparent else colors.value.foreground,
            diameter = b.size,
            hardness = b.hardness,
            opacity = b.opacity,
            brush = b,
            erase = erase,
        )
        val session = h.beginStroke(spec)
        actionDispatcher.reportCompleted(action, "$action started")
        return session
    }

    // ---- Layer masks and clipping ----

    /**
     * The layer whose mask receives painting (clicked mask thumbnail), or null for pixels. Only
     * meaningful while that layer is primary and still has a mask.
     */
    val maskTarget: StateFlow<LayerId?> = _maskTarget.asStateFlow()

    fun isMaskTargeted(layer: LayerNode): Boolean = _maskTarget.value == layer.id && layer.mask != null && state.value.primary == layer.id

    /** Click on a mask thumbnail: select the layer and paint on its mask. */
    fun targetMask(id: LayerId) {
        if (state.value.layers.find(id)?.mask == null) {
            reportNoOp("Target layer mask", "This layer has no mask")
            return
        }
        if (!select(id, action = "Target layer mask")) return
        _maskTarget.value = id
    }

    /** Click on a layer thumbnail: select it and paint on its pixels. */
    fun targetPixels(id: LayerId) {
        if (state.value.primary != id) {
            if (!select(id, action = "Target layer pixels")) return
        } else {
            // Selecting the already-primary layer may be rejected as a no-op by the engine, but
            // the thumbnail click still changes the active paint target from its mask to pixels.
            actionDispatcher.reportCompleted("Target layer pixels", "Painting on layer pixels")
        }
        _maskTarget.value = null
    }

    /**
     * Adds a mask to the primary layer: reveals the selection when there is one, else everything.
     * [hide] (Alt-click in Photoshop) starts from the opposite.
     */
    fun addMask(
        hide: Boolean = false,
        id: LayerId? = state.value.primary,
        explicit: app.croptop.core.engine.api.MaskFill? = null,
    ): Boolean {
        val target = id ?: run {
            reportNoOp("Add layer mask", "Select a layer before adding a mask")
            return false
        }
        val targetName = state.value.layers.find(target)?.name ?: "layer"
        val selection = state.value.hasSelection
        val fill = explicit ?: when {
            selection && hide -> app.croptop.core.engine.api.MaskFill.HIDE_SELECTION
            selection -> app.croptop.core.engine.api.MaskFill.REVEAL_SELECTION
            hide -> app.croptop.core.engine.api.MaskFill.HIDE_ALL
            else -> app.croptop.core.engine.api.MaskFill.REVEAL_ALL
        }
        // Like Photoshop, a new mask becomes the painting target.
        return result(handle.value?.addLayerMask(target, fill), "Add mask to $targetName").also { if (it) _maskTarget.value = target }
    }

    fun deleteMask(id: LayerId, apply: Boolean): Boolean {
        val targetName = state.value.layers.find(id)?.name ?: "layer"
        return result(handle.value?.removeLayerMask(id, apply), if (apply) "Apply mask on $targetName" else "Delete mask from $targetName")
    }

    /** Shift-click on a mask thumbnail. */
    fun toggleMask(id: LayerId): Boolean {
        val mask = state.value.layers.find(id)?.mask ?: run {
            reportNoOp("Toggle layer mask", "This layer has no mask")
            return false
        }
        val action = if (mask.enabled) {
            "Disable mask on ${state.value.layers.find(
                id,
            )?.name ?: "layer"}"
        } else {
            "Enable mask on ${state.value.layers.find(id)?.name ?: "layer"}"
        }
        return result(handle.value?.setLayerMask(id, mask.copy(enabled = !mask.enabled), preview = false), action)
    }

    fun setMask(id: LayerId, mask: app.croptop.core.model.LayerMaskInfo, preview: Boolean = false) =
        result(handle.value?.setLayerMask(id, mask, preview), "Edit mask on ${state.value.layers.find(id)?.name ?: "layer"}")

    private var maskCommit: kotlinx.coroutines.Job? = null

    /** Properties sliders: previews at once and records one step when the drag settles (like adjustments). */
    fun editMask(id: LayerId, mask: app.croptop.core.model.LayerMaskInfo) {
        val h = handle.value ?: run {
            reportNoOp("Edit layer mask", "No document is open")
            return
        }
        val targetName = state.value.layers.find(id)?.name ?: "layer"
        if (!previewResult(h.setLayerMask(id, mask, preview = true), "Preview mask on $targetName")) return
        maskCommit?.cancel()
        maskCommit = viewModelScope.launch {
            kotlinx.coroutines.delay(ADJUSTMENT_SETTLE_MS)
            result(h.setLayerMask(id, mask, preview = false), "Apply mask on $targetName")
        }
    }

    fun invertMask(id: LayerId) = result(handle.value?.invertLayerMask(id), "Invert mask on ${state.value.layers.find(id)?.name ?: "layer"}")

    /** Ctrl-click on a mask thumbnail (Ctrl+Shift adds, Ctrl+Alt subtracts). */
    fun maskToSelection(id: LayerId, op: app.croptop.core.model.SelectionOp = app.croptop.core.model.SelectionOp.REPLACE) =
        boxSelection(handle.value?.maskToSelection(id, op), "Load ${state.value.layers.find(id)?.name ?: "layer"} mask as selection")

    /** Ctrl+Alt+G: clips the primary layer to the one below, or releases it. */
    fun toggleClipping(id: LayerId? = state.value.primary): Boolean {
        val node = id?.let { state.value.layers.find(it) } ?: run {
            reportNoOp("Toggle clipping mask", "Select a layer first")
            return false
        }
        return result(
            handle.value?.setClipped(node.id, !node.clipped),
            if (node.clipped) "Release clipping mask on ${node.name}" else "Clip ${node.name} to layer below",
        )
    }

    // ---- Clipboard (Edit ▸ Cut/Copy/Paste) ----

    /** Non-null when Edit ▸ Paste has something to add. Shared by every open document. */
    val clipboard: StateFlow<app.croptop.core.engine.api.ClipboardInfo?> = engine.clipboard

    fun copy(merged: Boolean = false, quietSuccess: Boolean = false): Boolean {
        val command = handle.value?.copy(merged)
        val action = if (merged) "Copy merged" else "Copy"
        val ok = if (quietSuccess) previewResult(command, action) else result(command, action)
        if (ok && !quietSuccess) message.value = if (merged) "Copied merged" else "Copied"
        return ok
    }

    fun cut() = result(handle.value?.cut(), "Cut")

    /** Pastes centered on [at] (the view center), or at the copied position when null (Paste in Place). */
    fun paste(at: DocPoint?) = result(handle.value?.paste(newId(), at), "Paste")

    /** Starts a retouch stroke (clone, heal, dodge…) on the primary pixel layer; null (with a message) if there is none. */
    fun beginRetouch(
        size: Float,
        hardness: Float,
        effect: app.croptop.core.engine.api.RetouchEffect,
        action: String = "Retouch",
    ): app.croptop.core.engine.api.StrokeSession? {
        val h = handle.value ?: run {
            reportNoOp("Retouch", "No document is open")
            return null
        }
        val layer = state.value.primaryLayer
        if (layer == null || layer.kind != app.croptop.core.model.LayerKind.PIXEL) {
            val detail = if (layer == null) "Select a layer to retouch" else "“${layer.name}” isn't a pixel layer. Pick a pixel layer to retouch."
            offerCanvasRecovery(action, detail)
            reportNoOp(action, detail)
            return null
        }
        if (isMaskTargeted(layer) || h.quickMask.value) {
            val detail = if (h.quickMask.value) {
                "This tool edits pixels. The selection mask is currently selected."
            } else {
                "Select the layer thumbnail to retouch pixels instead of its mask"
            }
            offerCanvasRecovery(action, detail, editPixels = true)
            reportNoOp(action, detail)
            return null
        }
        val spec = app.croptop.core.engine.api.StrokeSpec(
            layer.id,
            RgbaColor.Transparent,
            diameter = size.coerceIn(1f, 5000f),
            hardness = hardness.coerceIn(0f, 1f),
        )
        val session = h.beginRetouch(spec, effect)
        actionDispatcher.reportCompleted(action, "$action started")
        return session
    }

    fun notify(text: String) {
        message.value = text
    }

    fun strokeResult(action: String, r: CommandResult) {
        actionDispatcher.report(action, r)
        if (r is CommandResult.Rejected) message.value = r.message
    }

    fun cancelStroke(action: String) {
        actionDispatcher.reportCompleted("Cancel $action", "$action canceled")
    }

    /** Native dab rasterization can touch many pixels; never run stroke commit in pointer dispatch. */
    fun finishStrokeOffMain(
        session: app.croptop.core.engine.api.StrokeSession,
        onComplete: (CommandResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    val committed = session.end()
                    if (committed is CommandResult.Accepted) handle.value?.awaitViewportPreview()
                    committed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                CommandResult.Rejected(
                    app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                    failure.message?.takeIf(String::isNotBlank) ?: "Brush stroke could not be applied",
                )
            }
            onComplete(result)
        }
    }

    // ---- Pixel edits (Edit / Layer menus) ----

    fun fill(
        source: app.croptop.core.model.FillSource,
        opacity: Float = 1f,
        mode: BlendMode = BlendMode.NORMAL,
        preserveTransparency: Boolean = false,
        action: String = "Fill layer",
    ) = fillCommand(source, opacity, mode, preserveTransparency, action) is CommandResult.Accepted

    /** Starts the cancellable fill job; callers must await its terminal result off the main thread. */
    fun fillJob(
        source: app.croptop.core.model.FillSource,
        opacity: Float = 1f,
        mode: BlendMode = BlendMode.NORMAL,
        preserveTransparency: Boolean = false,
        action: String = "Fill layer",
    ): app.croptop.core.engine.api.EngineJob {
        val document = handle.value
        val job = document?.fillJob(source, opacity, mode, preserveTransparency)
            ?: app.croptop.core.engine.api.EngineJob.completed(
                CommandResult.Rejected(RejectionCode.CLOSED, "No document is open"),
            )
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        return object : app.croptop.core.engine.api.EngineJob {
            override val progress = job.progress

            override suspend fun await(): CommandResult {
                val result = job.await()
                if (reported.compareAndSet(false, true)) {
                    if (result is CommandResult.Rejected && result.code == RejectionCode.CANCELLED) {
                        actionDispatcher.reportCanceled(action)
                    } else {
                        actionDispatcher.report(action, result)
                        if (result is CommandResult.Rejected) message.value = result.message
                    }
                }
                return result
            }

            override fun cancel() = job.cancel()
        }
    }

    /** Starts the native Lens Blur engine job and reports its terminal result through editor feedback. */
    fun lensBlurJob(
        id: app.croptop.core.model.LayerId,
        spec: app.croptop.core.engine.api.LensBlurSpec,
        depth: app.croptop.core.engine.api.LensBlurDepthSource?,
    ): app.croptop.core.engine.api.EngineJob {
        val job = handle.value?.applyLensBlurJob(id, spec, depth)
            ?: app.croptop.core.engine.api.EngineJob.completed(
                CommandResult.Rejected(RejectionCode.CLOSED, "No document is open"),
            )
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        return object : app.croptop.core.engine.api.EngineJob {
            override val progress = job.progress

            override suspend fun await(): CommandResult {
                val result = job.await()
                if (reported.compareAndSet(false, true)) {
                    if (result is CommandResult.Rejected && result.code == RejectionCode.CANCELLED) {
                        actionDispatcher.reportCanceled("Lens Blur")
                    } else {
                        actionDispatcher.report("Lens Blur", result)
                        if (result is CommandResult.Rejected) message.value = result.message
                    }
                }
                return result
            }

            override fun cancel() = job.cancel()
        }
    }

    /** Starts undoable profile assignment/conversion and exposes engine progress to the editor. */
    fun applyColorProfileJob(
        profile: app.croptop.core.model.ColorProfileRef,
        convertPixels: Boolean,
    ): app.croptop.core.engine.api.EngineJob {
        val action = if (convertPixels) "Convert to ${profile.displayName}" else "Assign ${profile.displayName}"
        val document = handle.value ?: return app.croptop.core.engine.api.EngineJob.completed(
            CommandResult.Rejected(RejectionCode.CLOSED, "No document is open"),
        )
        val result = CompletableDeferred<CommandResult>()
        val progress = MutableStateFlow<Float?>(null)
        var engineJob: app.croptop.core.engine.api.EngineJob? = null
        var cancelRequested = false
        val dispatch = actionDispatcher.dispatch(
            action = action,
            cancelable = true,
            onCancel = { if (engineJob == null) cancelRequested = true else engineJob?.cancel() },
            onBusy = { message.value = "Wait for the current action to finish before ${action.lowercase()}" },
        ) { report ->
            try {
                val job = withContext(Dispatchers.Default) {
                    document.applyColorProfileJob(profile, convertPixels = convertPixels)
                }
                engineJob = job
                if (cancelRequested) job.cancel()
                val terminal = coroutineScope {
                    val progressCollector = launch {
                        job.progress.collect { value ->
                            progress.value = value
                            report(value)
                        }
                    }
                    try {
                        job.await()
                    } finally {
                        progressCollector.cancel()
                    }
                }
                result.complete(terminal)
                when (terminal) {
                    is CommandResult.Accepted -> ActionOutcome.Completed("${profile.displayName} applied")

                    is CommandResult.Rejected -> if (terminal.code == RejectionCode.CANCELLED) {
                        ActionOutcome.Canceled("$action canceled")
                    } else {
                        ActionOutcome.Rejected(terminal.code, terminal.message)
                    }
                }
            } catch (cancelled: CancellationException) {
                result.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                result.completeExceptionally(failure)
                ActionOutcome.Failed(failure.message ?: "$action failed")
            }
        }
        if (dispatch == null) {
            return app.croptop.core.engine.api.EngineJob.completed(
                CommandResult.Rejected(RejectionCode.QUEUE_FULL, "Wait for the current action to finish"),
            )
        }
        return object : app.croptop.core.engine.api.EngineJob {
            override val progress: StateFlow<Float?> = progress.asStateFlow()
            override suspend fun await(): CommandResult = result.await()
            override fun cancel() {
                actionDispatcher.cancel()
            }
        }
    }

    /** Builds and attaches the engine-generated depth-range mask as one undoable command. */
    fun applyDepthRangeMask(
        id: app.croptop.core.model.LayerId,
        depth: app.croptop.core.engine.api.LensBlurDepthSource,
        range: app.croptop.core.engine.api.DepthRangeSpec,
    ): CommandResult {
        val result = handle.value?.applyDepthRangeMask(id, depth, range)
            ?: CommandResult.Rejected(RejectionCode.CLOSED, "No document is open")
        actionDispatcher.report("Create Depth Range Mask", result)
        if (result is CommandResult.Rejected) message.value = result.message
        return result
    }

    /** Returns the engine's typed fill result so dialogs can show the actual rejection reason. */
    fun fillCommand(
        source: app.croptop.core.model.FillSource,
        opacity: Float = 1f,
        mode: BlendMode = BlendMode.NORMAL,
        preserveTransparency: Boolean = false,
        action: String = "Fill layer",
    ): CommandResult {
        val command = handle.value?.fill(source, opacity, mode, preserveTransparency)
            ?: CommandResult.Rejected(RejectionCode.CLOSED, "No document is open")
        actionDispatcher.report(action, command)
        if (command is CommandResult.Rejected) message.value = command.message
        return command
    }

    /** Active layer thumbnail, canvas size and selection bounds for the Content-Aware sampling-area editor. */
    suspend fun contentAwareContext(maxPx: Int = 480): app.croptop.feature.editor.dialogs.ContentAwareContext? {
        val document = handle.value ?: return null
        val size = state.value.info?.size ?: return null
        val primary = state.value.primary
        val thumbnail = primary?.let { id ->
            runCatching { document.thumbnails(listOf(id), maxPx).firstOrNull() }.getOrNull()
        }
        val hole = (document.selection.value as? app.croptop.core.model.SelectionInfo.Active)?.bounds
        return app.croptop.feature.editor.dialogs.ContentAwareContext(thumbnail, size, hole)
    }

    fun fillWith(foreground: Boolean) = fill(
        source = app.croptop.core.model.FillSource.Solid(if (foreground) colors.value.foreground else colors.value.background),
        action = if (foreground) "Fill with foreground color" else "Fill with background color",
    )

    /** Delete: clears the selected pixels when there's a selection, else deletes the selected layers (Photoshop). */
    fun deleteOrClear() = if (state.value.hasSelection) result(handle.value?.clearSelection(), "Clear selection") else deleteSelected()

    /** Ctrl+J: Layer via Copy with a selection, Duplicate Layer without. */
    fun layerViaCopy() = result(handle.value?.layerViaCopy(newId()), "Layer via copy")

    fun layerViaCut() = result(handle.value?.layerViaCut(newId()), "Layer via cut")

    fun mergeVisible() = result(handle.value?.mergeVisible(), "Merge visible layers")

    fun ungroup(): Boolean {
        val group = state.value.primaryLayer?.takeIf { it.isGroup } ?: run {
            reportNoOp("Ungroup", "Select a group to ungroup")
            return false
        }
        return result(handle.value?.ungroup(group.id), "Ungroup")
    }

    // ---- Adjustments ----

    val adjustmentSpecs: List<app.croptop.core.model.AdjustmentSpec> get() = engine.adjustmentSpecs()

    /** Filter menu entries; they run through the same destructive preview session as adjustments. */
    val filterSpecs: List<app.croptop.core.model.AdjustmentSpec> get() = engine.filterSpecs()

    fun supportsDestructiveAdjustment(specId: String): Boolean =
        try {
            handle.value?.supportsDestructiveAdjustment(specId) == true
        } catch (e: Exception) {
            false
        }

    fun beginDestructiveAdjustmentPreview(specId: String): app.croptop.core.engine.api.AdjustmentPreviewSession? {
        val action = "Apply ${app.croptop.core.model.AdjustmentCatalog[specId]?.name ?: specId}"
        val h = handle.value ?: run {
            actionDispatcher.reportFailed(action, "No document is open")
            return null
        }
        if (!h.supportsDestructiveAdjustment(specId)) {
            val detail = "${app.croptop.core.model.AdjustmentCatalog[specId]?.name ?: "This adjustment"} is unavailable in this engine build"
            message.value = detail
            actionDispatcher.report(
                action,
                CommandResult.Rejected(app.croptop.core.engine.api.RejectionCode.UNSUPPORTED, detail),
            )
            return null
        }
        return try {
            h.beginAdjustmentPreview(app.croptop.core.model.AdjustmentInfo(specId))
        } catch (e: Exception) {
            val detail = e.message?.takeIf(String::isNotBlank) ?: "Couldn't start the adjustment preview"
            message.value = detail
            actionDispatcher.reportFailed(action, detail)
            null
        }
    }

    fun newFillLayerInfo(kind: app.croptop.core.model.FillKind): app.croptop.core.model.FillLayerInfo =
        if (kind == app.croptop.core.model.FillKind.GRADIENT) {
            app.croptop.core.model.FillLayerInfo(
                kind = kind,
                gradient = app.croptop.core.model.ColorGradient(
                    listOf(
                        app.croptop.core.model.GradientStop(0f, colors.value.foreground),
                        app.croptop.core.model.GradientStop(1f, colors.value.background),
                    ),
                ),
            )
        } else {
            app.croptop.core.model.FillLayerInfo(kind = kind, color = colors.value.foreground)
        }

    /** Creates a fill layer with its final dialog values in one history step. */
    fun addFillLayer(fill: app.croptop.core.model.FillLayerInfo): Boolean = result(
        handle.value?.addFillLayer(newId(), fill),
        if (fill.kind == app.croptop.core.model.FillKind.GRADIENT) "New Gradient Fill Layer" else "New Color Fill Layer",
    )

    /** Applies fill-content edits as one engine-owned history step. */
    fun setFillLayer(id: LayerId, fill: app.croptop.core.model.FillLayerInfo): Boolean =
        result(handle.value?.setFillLayer(id, fill), "Edit Fill Layer")

    fun setSmartFilters(id: LayerId, filters: List<app.croptop.core.model.AdjustmentInfo>): Boolean =
        result(handle.value?.setSmartFilters(id, filters), "Edit Smart Filters")

    fun addAdjustmentLayer(specId: String) = result(
        handle.value?.addAdjustmentLayer(newId(), app.croptop.core.model.AdjustmentInfo(specId)),
        "Add adjustment layer",
    )

    private var adjustmentCommit: kotlinx.coroutines.Job? = null

    /**
     * Properties panel edits: shown at once as a preview, then recorded as one history step when the
     * values rest for a moment (a slider drag becomes one undoable change).
     */
    fun editAdjustment(
        id: LayerId,
        values: Map<String, app.croptop.core.model.ParamValue>,
        action: String = "Apply adjustment",
    ) {
        previewAdjustment(id, values, action)
    }

    private fun previewAdjustment(
        id: LayerId,
        values: Map<String, app.croptop.core.model.ParamValue>,
        action: String = "Apply adjustment",
    ): Boolean {
        val h = handle.value ?: run {
            reportNoOp("Preview adjustment", "No document is open")
            return false
        }
        if (!previewResult(h.setAdjustment(id, values, preview = true), "Preview adjustment")) return false
        adjustmentCommit?.cancel()
        adjustmentCommit = viewModelScope.launch {
            kotlinx.coroutines.delay(ADJUSTMENT_SETTLE_MS)
            result(h.setAdjustment(id, values, preview = false), action)
        }
        return true
    }

    /** Applies a one-shot Levels point pick to the still-selected adjustment layer. */
    fun sampleLevelsPick(id: LayerId, target: LevelsPickTarget, point: DocPoint): Boolean {
        val stateNow = state.value
        val size = stateNow.info?.size
        if (stateNow.primary != id || stateNow.layers.find(id)?.adjustment?.specId != "levels") {
            reportNoOp("Sample Levels", "Select the Levels adjustment layer before sampling")
            return false
        }
        if (size == null || !point.x.isFinite() || !point.y.isFinite() || point.x < 0f || point.y < 0f || point.x >= size.width ||
            point.y >= size.height
        ) {
            reportNoOp("Sample Levels", "Pick a point inside the image")
            return false
        }

        val sampled = try {
            handle.value?.trySampleBelow(id, point, 1)?.color
        } catch (e: Exception) {
            val detail = "Couldn't sample the canvas: ${e.message?.takeIf(String::isNotBlank) ?: "sampling is unavailable"}"
            message.value = detail
            actionDispatcher.reportFailed("Sample Levels", detail)
            return false
        }
        if (sampled == null && handle.value != null) {
            reportNoOp("Sample Levels", "A stroke is still being applied. Try the point pick again in a moment.")
            return false
        }
        if (sampled == null || sampled.a <= 0f) {
            reportNoOp("Sample Levels", "No visible color at the sampled point")
            return false
        }

        val layer = state.value.layers.find(id) ?: run {
            reportNoOp("Sample Levels", "The Levels layer is no longer available")
            return false
        }
        val adjustment = layer.adjustment ?: run {
            reportNoOp("Sample Levels", "The selected layer is no longer a Levels adjustment")
            return false
        }
        val current = adjustment.values["levels"] as? app.croptop.core.model.ParamValue.LevelsValue ?: run {
            reportNoOp("Sample Levels", "The Levels adjustment has invalid input values")
            return false
        }
        val input = sampled.levelsPickCoordinate(target)
        val updated = when (target) {
            LevelsPickTarget.BLACK -> {
                if (input > current.v.inWhite - 0.01f) {
                    reportNoOp("Sample Levels", "Picked black point must be below the white input point")
                    return false
                }
                current.v.copy(inBlack = input)
            }

            LevelsPickTarget.GRAY -> {
                if (input <= current.v.inBlack || input >= current.v.inWhite) {
                    reportNoOp("Sample Levels", "Picked gray point must be between the black and white input points")
                    return false
                }
                current.v.withGrayPosition(input)
            }

            LevelsPickTarget.WHITE -> {
                if (input < current.v.inBlack + 0.01f) {
                    reportNoOp("Sample Levels", "Picked white point must be above the black input point")
                    return false
                }
                current.v.copy(inWhite = input)
            }
        }
        if (!previewAdjustment(id, adjustment.values + ("levels" to app.croptop.core.model.ParamValue.LevelsValue(updated)))) return false
        message.value = "Levels ${target.label.lowercase()} point set"
        return true
    }

    /** Samples below a Curves/Hue-Saturation layer for its on-canvas targeting control. */
    fun targetAdjustmentFromCanvas(id: LayerId, specId: String, point: DocPoint): CanvasAdjustmentTargetResult {
        val stateNow = state.value
        val layer = stateNow.layers.find(id)
        val adjustment = layer?.adjustment
        if (
            stateNow.primary != id || adjustment?.specId != specId ||
            specId !in setOf("curves", "hueSaturation")
        ) {
            reportNoOp("Target adjustment", "Select the Curves or Hue/Saturation layer before sampling")
            return CanvasAdjustmentTargetResult(accepted = false)
        }
        val size = stateNow.info?.size
        if (
            size == null || !point.x.isFinite() || !point.y.isFinite() || point.x < 0f || point.y < 0f ||
            point.x >= size.width || point.y >= size.height
        ) {
            reportNoOp("Target adjustment", "Pick a point inside the image")
            return CanvasAdjustmentTargetResult(accepted = false)
        }
        val document = handle.value
        val color = try {
            document?.trySampleBelow(id, point, 1)?.color
        } catch (e: Exception) {
            reportNoOp("Target adjustment", e.message?.takeIf(String::isNotBlank) ?: "Canvas sampling is unavailable")
            return CanvasAdjustmentTargetResult(accepted = false)
        }
        if (color == null && document != null) {
            reportNoOp("Target adjustment", "A stroke is still being applied. Try the point pick again in a moment.")
            return CanvasAdjustmentTargetResult(accepted = false)
        }
        if (color == null || color.a <= 0f) {
            reportNoOp("Target adjustment", "No visible color at the sampled point")
            return CanvasAdjustmentTargetResult(accepted = false)
        }
        if (specId == "hueSaturation") {
            val hsv = app.croptop.core.model.ColorMath.toHsv(color)
            val hueValues = (adjustment.values["hueSat"] as? app.croptop.core.model.ParamValue.HueSatValue)?.v
                ?: app.croptop.core.model.HueSaturation()
            val channel = if (hsv.s < 0.01f) {
                app.croptop.core.model.HueChannel.MASTER
            } else {
                app.croptop.core.model.HueChannel.entries
                    .filter { it != app.croptop.core.model.HueChannel.MASTER }
                    .maxByOrNull { hueValues.range(it)?.weight(hsv.h) ?: 0f }
                    ?: app.croptop.core.model.HueChannel.MASTER
            }
            message.value = "Targeted ${channel.label.lowercase()} on canvas"
            actionDispatcher.reportCompleted("Target ${channel.label.lowercase()} color")
            return CanvasAdjustmentTargetResult(accepted = true, hueChannel = channel)
        }
        val curves = when (val current = adjustment.values["curve"]) {
            is app.croptop.core.model.ParamValue.CurveChannelsValue -> current.v
            is app.croptop.core.model.ParamValue.CurveValue -> app.croptop.core.model.CurveChannels(rgb = current.v)
            else -> app.croptop.core.model.CurveChannels()
        }
        val input = (0.2126f * color.r + 0.7152f * color.g + 0.0722f * color.b).coerceIn(0f, 1f)
        val (targeted, _) = curves.rgb.withPoint(app.croptop.core.model.CurvePoint(input, curves.rgb.evaluate(input)))
        val values = adjustment.values + ("curve" to app.croptop.core.model.ParamValue.CurveChannelsValue(curves.copy(rgb = targeted)))
        if (previewAdjustment(id, values, "Target Curves tone")) {
            message.value = "Curves point targeted from canvas"
            return CanvasAdjustmentTargetResult(accepted = true)
        }
        return CanvasAdjustmentTargetResult(accepted = false)
    }

    fun applyAdjustment(
        specId: String,
        values: Map<String, app.croptop.core.model.ParamValue>,
        action: String = "Apply $specId",
    ) = result(handle.value?.applyAdjustment(app.croptop.core.model.AdjustmentInfo(specId, values)), action)

    /** Opens the Liquify workspace on the active pixel layer (null with typed feedback when it cannot start). */
    fun beginLiquify(): app.croptop.core.engine.api.LiquifySession? {
        val layer = state.value.primaryLayer
        if (layer == null || layer.kind != LayerKind.PIXEL) {
            offerCanvasRecovery("Liquify", "Select a pixel layer to use Liquify")
            reportUiActionRejected("Liquify", app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT, "Select a pixel layer to use Liquify")
            return null
        }
        return try {
            handle.value?.beginLiquify(layer.id)
        } catch (failure: RuntimeException) {
            reportUiActionFailed("Liquify", failure.message ?: "Liquify could not start")
            null
        }
    }

    fun liquify(stroke: app.croptop.core.engine.api.LiquifyStroke): Boolean {
        val layer = state.value.primaryLayer
        if (layer == null || layer.kind != LayerKind.PIXEL) {
            reportUiActionRejected(
                "Liquify stroke",
                app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT,
                "Select a pixel layer to use Liquify",
            )
            return false
        }
        return result(handle.value?.liquify(layer.id, stroke), "Liquify stroke")
    }

    // ---- Named snapshots (History panel) ----

    fun createSnapshot(name: String, action: String = "Create snapshot") = immediateResult(handle.value?.createSnapshot(name), action)

    fun renameSnapshot(index: Int, name: String, action: String = "Rename snapshot") = immediateResult(
        handle.value?.renameSnapshot(index, name),
        action,
    )

    fun deleteSnapshot(index: Int, action: String = "Delete snapshot") = immediateResult(handle.value?.deleteSnapshot(index), action)

    fun jumpToSnapshot(index: Int, action: String = "Restore snapshot") = result(handle.value?.jumpToSnapshot(index), action)

    fun undo(): CommandResult? = reportHistoryResult("Undo", handle.value?.undo())

    fun redo(): CommandResult? = reportHistoryResult("Redo", handle.value?.redo())

    fun jumpTo(index: Int, action: String = "Jump to history step"): CommandResult? = reportHistoryResult(action, handle.value?.jumpTo(index))

    private fun reportHistoryResult(action: String, result: CommandResult?): CommandResult? {
        if (result == null) {
            actionDispatcher.reportFailed(action, "No document is open")
            return null
        }
        actionDispatcher.report(action, result)
        if (result is CommandResult.Rejected) message.value = result.message
        return result
    }

    /** Marks this document as the one agents act on (window focused / tab shown). */
    fun activate() {
        handle.value?.let(openDocuments::activate)
    }

    override fun onCleared() {
        handle.value?.let(openDocuments::unregister)
        handle.value?.close()
    }

    private fun friendlyOpenError(e: Exception): String = when (e) {
        is UnsupportedOperationException -> "This file type can't be opened yet."
        is SecurityException -> "CropTop no longer has permission to read this file. Open it again from Files."
        is java.io.FileNotFoundException -> "The file was moved or deleted."
        else -> "The file couldn't be opened${e.message?.let { ": $it" } ?: "."}"
    }

    private fun isCropTopFile(uri: String) = uri.substringBefore('?').lowercase().endsWith(".croptop")

    private fun displayName(uri: String, fallback: String): String {
        val decoded = runCatching {
            java.net.URLDecoder.decode(uri.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrNull() ?: return fallback
        val candidate = decoded.substringBefore('?').substringAfterLast('/').substringAfterLast(':')
        // SAF document IDs can be opaque numbers. Do not expose one as the tab/Recent name.
        return candidate.takeIf { it.endsWith(".croptop", ignoreCase = true) } ?: fallback
    }

    /** Photoshop naming: "Layer 1", "Layer 2"… — one past the highest existing number for [base]. */
    private fun nextName(tree: LayerTree, base: String): String {
        val pattern = Regex("^${Regex.escape(base)} (\\d+)$")
        val max = tree.flatten().mapNotNull { pattern.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "$base ${max + 1}"
    }

    /** Client-generated ids (engine-api v1 takes the id in AddLayer). */
    internal fun newLayerIdForOutput() = newId()

    private fun newId() = LayerId(Random.nextLong(1L shl 40, Long.MAX_VALUE))
}

private data class Meta(
    val message: String?,
    val saveTarget: String?,
    val busy: String?,
    val title: String?,
    val selection: app.croptop.core.model.SelectionInfo,
    val exportWarning: ExportCompatibilityNotice?,
)

private const val ADJUSTMENT_SETTLE_MS = 400L
private const val NAVIGATOR_PREVIEW_PX = 320
private const val NAVIGATOR_PREVIEW_SETTLE_MILLIS = 300L
