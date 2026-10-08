package app.croptop.core.engine.fake

import android.view.Surface
import app.croptop.core.engine.api.AdjustmentPreviewSession
import app.croptop.core.engine.api.ClipboardInfo
import app.croptop.core.engine.api.ColorRangeSession
import app.croptop.core.engine.api.ColorRangeSpec
import app.croptop.core.engine.api.ColorRangeTarget
import app.croptop.core.engine.api.ColorReadout
import app.croptop.core.engine.api.ColorSample
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DEFAULT_ENGINE_MEMORY_BUDGET_BYTES
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.DocumentPreview
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.DepthRangeSpec
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.EngineCapabilities
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.EngineFeature
import app.croptop.core.engine.api.EngineJob
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportResult
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.Histogram
import app.croptop.core.engine.api.HistogramScope
import app.croptop.core.engine.api.LayerThumb
import app.croptop.core.engine.api.LensBlurDepthSource
import app.croptop.core.engine.api.LensBlurSpec
import app.croptop.core.engine.api.LiquifyStroke
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.OverlayState
import app.croptop.core.engine.api.QuickSelectionSession
import app.croptop.core.engine.api.QuickSelectionSpec
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.RetouchEffect
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SaveOptions
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.engine.api.SelectionModifySession
import app.croptop.core.engine.api.StrokeSampleLayout
import app.croptop.core.engine.api.StrokeSession
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.engine.api.UnsupportedSelectionModifySession
import app.croptop.core.engine.api.TransformSession
import app.croptop.core.engine.api.TransformTarget
import app.croptop.core.engine.api.Viewport
import app.croptop.core.model.AdjustmentCatalog
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.Anchor
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BlendMode
import app.croptop.core.model.CanvasFill
import app.croptop.core.model.CanvasResize
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.CropQuad
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.DocumentId
import app.croptop.core.model.DocumentInfo
import app.croptop.core.model.FillSource
import app.croptop.core.model.GradientShape
import app.croptop.core.model.HistoryEntry
import app.croptop.core.model.HistorySnapshot
import app.croptop.core.model.HistoryState
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerLocks
import app.croptop.core.model.LayerMaskInfo
import app.croptop.core.model.LayerNode
import app.croptop.core.model.LayerTree
import app.croptop.core.model.Matrix3
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ParamValue
import app.croptop.core.model.Resample
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionBounds
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionModify
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.ViewportState
import app.croptop.core.model.translate
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

private fun srgbToLinear(value: Float): Double {
    val channel = value.coerceIn(0f, 1f).toDouble()
    return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
}

private fun linearToSrgb(value: Double): Float {
    val channel = value.coerceIn(0.0, 1.0)
    return (if (channel <= 0.0031308) channel * 12.92 else 1.055 * channel.pow(1.0 / 2.4) - 0.055).toFloat()
}

/**
 * Pure-Kotlin fake of the engine contract. No pixels: layers are metadata, history is
 * snapshot-based, and commands apply synchronously so UI tests are deterministic.
 * Validation mirrors what the real engine must reject (missing ids, bad indices, cycles, locks).
 */
class FakeEngine internal constructor(
    /** Optional deterministic pixel source for tests; production fake documents remain pixel-free. */
    private val samplePixel: ((Int, Int, SampleMode) -> RgbaColor)? = null,
) : Engine {
    /** Listed so the Filter menu is discoverable; the fake can't run them (they show as unsupported). */
    override fun filterSpecs(): List<app.croptop.core.model.AdjustmentSpec> = app.croptop.core.model.FilterCatalog.All

    constructor() : this(null)

    private val ids = AtomicLong(1)
    private val docs = mutableListOf<FakeDocument>()
    private var closed = false
    private val sharedClipboard = MutableStateFlow<ClipboardInfo?>(null)

    override val clipboard: StateFlow<ClipboardInfo?> = sharedClipboard.asStateFlow()

    @Volatile private var configuredMemoryBudgetBytes = DEFAULT_ENGINE_MEMORY_BUDGET_BYTES

    /** Last open contract, exposed for deterministic parity assertions in fake-engine tests. */
    var lastOpenOptions: OpenOptions? = null
        private set

    /** Records recipe delivery only for RAW sources; the fake does not claim to render RAW pixels. */
    var lastRawDevelopSettings: app.croptop.core.model.DevelopSettings? = null
        private set

    /** Options of every export any document of this engine was asked for, in order (tests read what reached the engine). */
    val exportedOptions = mutableListOf<ExportOptions>()

    override val memoryBudgetBytes: Long get() = configuredMemoryBudgetBytes

    override fun setMemoryBudget(bytes: Long) {
        require(bytes > 0L) { "Memory budget must be positive" }
        configuredMemoryBudgetBytes = bytes
    }

    override val capabilities = EngineCapabilities(
        engineVersion = "fake-1",
        abi = System.getProperty("os.arch") ?: "jvm",
        supportedDepthBits = setOf(8, 16, 32),
        features = setOf(EngineFeature.LAYERS),
    )

    override suspend fun open(source: DocumentSource, options: OpenOptions): DocumentHandle {
        check(!closed) { "Engine closed" }
        lastOpenOptions = options
        val name = when (source) {
            is DocumentSource.ContentUri -> source.value.substringAfterLast('/').substringAfterLast("%2F").ifBlank { "Image" }
            is DocumentSource.AppFile -> File(source.path).name
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        lastRawDevelopSettings = if (extension in RAW_EXTENSIONS) options.rawDevelopSettings else null
        val document = create(NewDocumentSpec(name = name, size = DocSize(4000, 3000)), openedLabel = "Open")
        if (extension in RAW_EXTENSIONS && options.rawAsSmartObject) {
            document.openAsRawSmartObject(options.rawDevelopSettings ?: app.croptop.core.model.DevelopSettings())
        }
        return document
    }

    /** Develop previews of the fake: a gradient scaled by exposure and local-mask lift, so UI tests can see settings reach the renderer. */
    override suspend fun openDevelopPreview(source: DocumentSource): app.croptop.core.engine.api.DevelopPreviewSession {
        val name = when (source) {
            is DocumentSource.ContentUri -> source.value.substringAfterLast('/')
            is DocumentSource.AppFile -> File(source.path).name
        }
        if (name.substringAfterLast('.', "").lowercase() !in RAW_EXTENSIONS) throw UnsupportedOperationException("Not a camera RAW file")
        return object : app.croptop.core.engine.api.DevelopPreviewSession {
            override val width = 6000
            override val height = 4000
            override val warnings = emptyList<String>()
            var renders = 0

            override suspend fun render(settings: app.croptop.core.model.DevelopSettings, maxDimension: Int): app.croptop.core.engine.api.DevelopPreview {
                renders++
                val w = maxDimension.coerceIn(8, 64)
                val h = (w * 2) / 3
                val gain = Math.pow(2.0, settings.basic.exposure.toDouble())
                val lift = settings.masks.filter { it.enabled }.sumOf { it.adjust.exposure.toDouble() * it.amount }
                val argb = IntArray(w * h) { i ->
                    val x = i % w
                    val base = (255.0 * x / w).coerceIn(0.0, 255.0)
                    val v = (base * gain * Math.pow(2.0, lift)).toInt().coerceIn(0, 255)
                    0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
                }
                return app.croptop.core.engine.api.DevelopPreview(w, h, argb)
            }

            override suspend fun maskOverlay(settings: app.croptop.core.model.DevelopSettings, maskIndex: Int, maxDimension: Int): app.croptop.core.engine.api.DevelopPreview? {
                if (maskIndex !in settings.masks.indices) return null
                val w = maxDimension.coerceIn(8, 64)
                val h = (w * 2) / 3
                return app.croptop.core.engine.api.DevelopPreview(w, h, IntArray(w * h) { 0x99E5484D.toInt() })
            }

            override fun close() = Unit
        }
    }

    override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
        check(!closed) { "Engine closed" }
        return create(spec, openedLabel = "New")
    }

    private fun create(spec: NewDocumentSpec, openedLabel: String): FakeDocument {
        val background = LayerNode(
            id = LayerId(ids.getAndIncrement()),
            name = "Background",
            kind = LayerKind.PIXEL,
            locks = if (spec.background == BackgroundContents.TRANSPARENT) LayerLocks.None else LayerLocks(transparency = true, position = true),
            bounds = app.croptop.core.model.DocRect.of(spec.size),
        )
        val tree = LayerTree(listOf(background), active = setOf(background.id), primary = background.id)
        val info = DocumentInfo(DocumentId(ids.getAndIncrement()), spec.name, spec.size, spec.depth, spec.profile, spec.resolutionPpi)
        return FakeDocument(
            info,
            tree,
            openedLabel,
            spec.background,
            sharedClipboard,
            samplePixel,
            createContents = { name, size -> create(NewDocumentSpec(name = name, size = size), openedLabel = "Smart Object Contents") },
            onExport = { exportedOptions += it },
        ) { docs.remove(it) }.also { docs += it }
    }

    override fun close() {
        closed = true
        docs.toList().forEach { it.close() }
    }
}

/** One open document. Public so tests can inspect [sequence]. */
class FakeDocument internal constructor(
    info: DocumentInfo,
    tree: LayerTree,
    openedLabel: String,
    private val background: BackgroundContents,
    private val clipboard: MutableStateFlow<ClipboardInfo?> = MutableStateFlow(null),
    private val samplePixel: ((Int, Int, SampleMode) -> RgbaColor)?,
    private val createContents: (String, DocSize) -> DocumentHandle,
    private val onExport: (ExportOptions) -> Unit = {},
    private val onClose: (FakeDocument) -> Unit,
) : DocumentHandle {

    /** One history state: layers plus the document-level state canvas edits change. */
    private data class Snapshot(
        val label: String,
        val tree: LayerTree,
        val size: DocSize,
        val ppi: Float,
        val selection: SelectionInfo,
        /** Fake mask content per layer: the revealed area's bounds, null when all hidden. */
        val maskCoverage: Map<LayerId, DocRect?> = emptyMap(),
        val profile: ColorProfileRef,
    )
    private data class NamedSnapshot(val name: String, val state: Snapshot)

    private val snapshots = mutableListOf(Snapshot(openedLabel, tree, info.size, info.resolutionPpi, SelectionInfo.None, profile = info.profile))

    /** Select ▸ Reselect restores the last selection that was dropped. */
    private var lastSelection: SelectionInfo.Active? = null
    private val namedSnapshots = mutableListOf<NamedSnapshot>()
    /** Committed style values while `_layers` exposes temporary Layer Style dialog previews. */
    private val layerStylePreviewBase = mutableMapOf<LayerId, app.croptop.core.model.LayerStyles?>()
    private var cursor = 0
    private var closed = false
    private var seq = 0L
    private var activeTransactionStartCursor: Int? = null
    private val cleanIndex = MutableStateFlow(0)

    private val _info = MutableStateFlow(info)
    private val _layers = MutableStateFlow(tree)
    private val _history = MutableStateFlow(historyState())
    private val _selection = MutableStateFlow<SelectionInfo>(SelectionInfo.None)
    private val mutableEvents = MutableSharedFlow<EngineEvent>(
        replay = 32,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val maskThumbnailRevision = MutableStateFlow(0L)

    override val id: DocumentId = info.id
    override val info: StateFlow<DocumentInfo> = _info.asStateFlow()
    override val layers: StateFlow<LayerTree> = _layers.asStateFlow()
    override val history: StateFlow<HistoryState> = _history.asStateFlow()
    override val selection: StateFlow<SelectionInfo> = _selection.asStateFlow()
    override val events: Flow<EngineEvent> = mutableEvents

    val sequence: Long get() = seq

    override fun execute(command: EditCommand): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        return when (command) {
            is EditCommand.Undo -> undo()

            is EditCommand.Redo -> redo()

            is EditCommand.AddLayer -> {
                if (tree.find(command.id) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${command.id.value} already exists")
                val node = LayerNode(command.id, command.name, command.kind)
                val next = LayerTreeOps.insert(tree, node, command.parentId, command.index)
                    ?: return reject(RejectionCode.INVALID_ARGUMENT, "Bad parent/index for new layer")
                commit("New ${command.kind.label}", next.copy(active = setOf(command.id), primary = command.id))
            }

            is EditCommand.DeleteLayer -> {
                if (tree.find(command.id) == null) return notFound(command.id)
                if (tree.size == 1) return reject(RejectionCode.INVALID_ARGUMENT, "A document needs at least one layer")
                val (next, removed) = LayerTreeOps.remove(tree, command.id)!!
                commit("Delete ${removed.name}", next)
            }

            is EditCommand.MoveLayer -> {
                val node = tree.find(command.id) ?: return notFound(command.id)
                if (node.locks.position && tree.parentOf(command.id)?.id != command.parentId) {
                    return reject(RejectionCode.INVALID_ARGUMENT, "Layer position is locked")
                }
                val newParent = command.parentId
                if (newParent != null && LayerTreeOps.isWithin(tree, newParent, command.id)) {
                    return reject(RejectionCode.INVALID_ARGUMENT, "Cannot move a group into itself")
                }
                val (without, removed) = LayerTreeOps.remove(tree, command.id)!!
                val next = LayerTreeOps.insert(without.copy(active = tree.active, primary = tree.primary), removed, command.parentId, command.index)
                    ?: return reject(RejectionCode.INVALID_ARGUMENT, "Bad parent/index for move")
                commit("Move ${node.name}", next)
            }

            is EditCommand.SetLayerProps -> {
                val node = tree.find(command.id) ?: return notFound(command.id)
                val p = command.properties
                if (p.blendMode?.groupOnly == true && !node.isGroup) {
                    return reject(RejectionCode.INVALID_ARGUMENT, "Pass Through applies to groups only")
                }
                val next = LayerTreeOps.update(tree, command.id) {
                    it.copy(
                        name = p.name ?: it.name,
                        visible = p.visible ?: it.visible,
                        opacity = p.opacity ?: it.opacity,
                        fillOpacity = p.fillOpacity ?: it.fillOpacity,
                        blendMode = p.blendMode ?: it.blendMode,
                        blendIf = p.blendIf ?: it.blendIf,
                    )
                }
                commit(propsLabel(p.name, p.visible, p.opacity, p.fillOpacity, p.blendMode, p.blendIf), next)
            }

            is EditCommand.SetBlendMode -> {
                val node = tree.find(command.id) ?: return notFound(command.id)
                if (command.blendMode.groupOnly && !node.isGroup) return reject(RejectionCode.INVALID_ARGUMENT, "Pass Through applies to groups only")
                commit("Blending Change", LayerTreeOps.update(tree, command.id) { it.copy(blendMode = command.blendMode) })
            }
        }
    }

    override fun transaction(label: String, block: DocumentHandle.() -> Unit): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (label.isBlank()) return reject(RejectionCode.INVALID_ARGUMENT, "Transaction label must not be blank")
        if (activeTransactionStartCursor != null) return reject(RejectionCode.INVALID_ARGUMENT, "Nested transactions are not supported")
        val start = cursor
        activeTransactionStartCursor = start
        try {
            block(this)
            return mergeSince(start, label)
        } finally {
            activeTransactionStartCursor = null
        }
    }

    private fun mergeSince(start: Int, label: String): CommandResult {
        val end = cursor
        if (end <= start) return CommandResult.Accepted(seq)
        val final = snapshots[end].copy(label = label)
        snapshots.subList(start + 1, end + 1).clear()
        snapshots.add(final)
        cursor = start + 1
        publish()
        return CommandResult.Accepted(++seq)
    }

    private var historyGroupLabel: String? = null

    override fun beginHistoryGroup(label: String): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (label.isBlank()) return reject(RejectionCode.INVALID_ARGUMENT, "A history group needs a label")
        if (activeTransactionStartCursor != null) return reject(RejectionCode.INVALID_ARGUMENT, "A history group or transaction is already open")
        activeTransactionStartCursor = cursor
        historyGroupLabel = label
        return CommandResult.Accepted(seq)
    }

    override fun endHistoryGroup(): CommandResult {
        val start = activeTransactionStartCursor ?: return reject(RejectionCode.INVALID_ARGUMENT, "No history group is open")
        val label = historyGroupLabel ?: return reject(RejectionCode.INVALID_ARGUMENT, "No history group is open")
        activeTransactionStartCursor = null
        historyGroupLabel = null
        return mergeSince(start, label)
    }

    override fun cancelHistoryGroup(): CommandResult {
        val start = activeTransactionStartCursor ?: return reject(RejectionCode.INVALID_ARGUMENT, "No history group is open")
        activeTransactionStartCursor = null
        historyGroupLabel = null
        cursor = start
        while (snapshots.lastIndex > cursor) snapshots.removeAt(snapshots.lastIndex)
        publish()
        return CommandResult.Accepted(seq)
    }

    override fun beginStroke(spec: StrokeSpec): StrokeSession = stroke(spec, if (spec.erase) "Eraser" else "Brush Tool") { null }

    override fun beginRetouch(spec: StrokeSpec, effect: RetouchEffect): StrokeSession = stroke(spec, effect.label) {
        val source = (effect as? RetouchEffect.Clone)?.source ?: (effect as? RetouchEffect.Heal)?.source
        val historyState = (effect as? RetouchEffect.HistoryPaint)?.stateIndex
        when {
            source != null && !validPoint(source) -> reject(RejectionCode.INVALID_ARGUMENT, "Alt-click inside the canvas to set a source")
            historyState != null && historyState !in history.value.entries.indices ->
                reject(RejectionCode.INVALID_ARGUMENT, "That history state no longer exists")
            else -> null
        }
    }

    override fun beginMaskStroke(spec: StrokeSpec): StrokeSession = stroke(spec, if (spec.erase) "Eraser" else "Brush Tool") {
        val node = _layers.value.find(spec.target)
        when {
            quickMaskFlow.value -> null
            node == null -> notFound(spec.target)
            node.mask == null -> reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” has no mask to paint on")
            else -> null
        }
    }

    /** No pixels in the fake: a stroke is a history step with an unchanged tree. [check] vetoes at the end. */
    private fun stroke(spec: StrokeSpec, label: String, check: () -> CommandResult?): StrokeSession {
        val target = _layers.value.find(spec.target)
        return object : StrokeSession {
            private var points = 0
            private var done = false

            override fun add(samples: FloatArray) {
                require(samples.size % StrokeSampleLayout.STRIDE == 0) {
                    "samples must be a multiple of ${StrokeSampleLayout.STRIDE}"
                }
                check(!done) { "Stroke already finished" }
                points += samples.size / StrokeSampleLayout.STRIDE
            }

            override fun end(): CommandResult {
                check(!done) { "Stroke already finished" }
                done = true
                if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
                if (target == null) return notFound(spec.target)
                if (target.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "Layer pixels are locked")
                if (points == 0) return reject(RejectionCode.INVALID_ARGUMENT, "Empty stroke")
                check()?.let { return it }
                return commit(label, _layers.value)
            }

            override fun cancel() {
                done = true
            }
        }
    }

    override fun undo(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (cursor == 0) return reject(RejectionCode.INVALID_ARGUMENT, "Nothing to undo")
        return moveTo(cursor - 1)
    }

    override fun redo(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (cursor == snapshots.lastIndex) return reject(RejectionCode.INVALID_ARGUMENT, "Nothing to redo")
        return moveTo(cursor + 1)
    }

    override fun jumpTo(historyIndex: Int): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (historyIndex !in snapshots.indices) return reject(RejectionCode.INVALID_ARGUMENT, "No history state $historyIndex")
        return moveTo(historyIndex)
    }

    override fun createSnapshot(name: String): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val normalized = name.trim()
        if (!validSnapshotName(
                normalized,
            )
        ) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Snapshot name must be 1 to $MAX_SNAPSHOT_NAME_LENGTH characters")
        }
        if (namedSnapshots.any { it.name.equals(normalized, ignoreCase = true) }) {
            return reject(RejectionCode.INVALID_ARGUMENT, "A snapshot with that name already exists")
        }
        namedSnapshots += NamedSnapshot(normalized, snapshots[cursor])
        publishHistory()
        return CommandResult.Accepted(++seq)
    }

    override fun renameSnapshot(index: Int, name: String): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val current = namedSnapshots.getOrNull(index)
            ?: return reject(RejectionCode.INVALID_ARGUMENT, "No named snapshot at index $index")
        val normalized = name.trim()
        if (!validSnapshotName(
                normalized,
            )
        ) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Snapshot name must be 1 to $MAX_SNAPSHOT_NAME_LENGTH characters")
        }
        if (namedSnapshots.withIndex().any { (other, snapshot) -> other != index && snapshot.name.equals(normalized, ignoreCase = true) }) {
            return reject(RejectionCode.INVALID_ARGUMENT, "A snapshot with that name already exists")
        }
        namedSnapshots[index] = current.copy(name = normalized)
        publishHistory()
        return CommandResult.Accepted(++seq)
    }

    override fun deleteSnapshot(index: Int): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (namedSnapshots.getOrNull(index) == null) return reject(RejectionCode.INVALID_ARGUMENT, "No named snapshot at index $index")
        namedSnapshots.removeAt(index)
        publishHistory()
        return CommandResult.Accepted(++seq)
    }

    override fun jumpToSnapshot(index: Int): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val snapshot = namedSnapshots.getOrNull(index)
            ?: return reject(RejectionCode.INVALID_ARGUMENT, "No named snapshot at index $index")
        while (snapshots.lastIndex > cursor) snapshots.removeAt(snapshots.lastIndex)
        if (cleanIndex.value > cursor) cleanIndex.value = -1
        snapshots += snapshot.state.copy(label = "Restore snapshot: ${snapshot.name}")
        cursor = snapshots.lastIndex
        publish()
        return CommandResult.Accepted(++seq)
    }

    // ---- engine-api v3 ----

    private val size: DocSize get() = snapshots[cursor].size
    private val currentSelection: DocRect? get() = (snapshots[cursor].selection as? SelectionInfo.Active)?.bounds

    override fun setActiveLayers(ids: Set<LayerId>, primary: LayerId?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        ids.firstOrNull { tree.find(it) == null }?.let { return notFound(it) }
        if (primary != null && primary !in ids) return reject(RejectionCode.INVALID_ARGUMENT, "The primary layer must be one of the selected layers")
        // View state only: no history step, no dirty flag (same as the native engine).
        // Matches NativeEngine: a null primary stays null; an empty set clears the targets.
        val next = tree.copy(active = ids, primary = primary)
        snapshots[cursor] = snapshots[cursor].copy(tree = next)
        _layers.value = next
        return CommandResult.Accepted(++seq)
    }

    override fun createShapeLayer(id: LayerId, name: String, paths: List<VectorPathDraft>): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (id.value <= 0L) return reject(RejectionCode.INVALID_ARGUMENT, "Shape layer id must be positive")
        if (_layers.value.find(id) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${id.value} already exists")
        if (name.isBlank()) return reject(RejectionCode.INVALID_ARGUMENT, "Shape layer name cannot be blank")
        validateVectorPaths(paths)?.let { return reject(RejectionCode.INVALID_ARGUMENT, it) }
        if (paths.isEmpty()) return reject(RejectionCode.INVALID_ARGUMENT, "A shape layer needs at least one path")
        val node = LayerNode(id, name, LayerKind.SHAPE, vectorPaths = paths)
        val next = LayerTreeOps.insert(_layers.value, node, parentId = null, index = 0)
            ?: return reject(RejectionCode.INVALID_ARGUMENT, "Could not place shape layer")
        return commit("New Shape Layer", next.copy(active = setOf(id), primary = id))
    }

    override fun setShapePaths(id: LayerId, paths: List<VectorPathDraft>): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        validateVectorPaths(paths)?.let { return reject(RejectionCode.INVALID_ARGUMENT, it) }
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.kind != LayerKind.SHAPE) return reject(RejectionCode.INVALID_ARGUMENT, "Vector paths belong to shape layers")
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        if (node.vectorPaths == paths) return reject(RejectionCode.INVALID_ARGUMENT, "The shape geometry has not changed")
        return commit("Edit Shape", LayerTreeOps.update(_layers.value, id) { it.copy(vectorPaths = paths) })
    }

    override fun combineShapePaths(
        layerId: LayerId,
        subjectPath: app.croptop.core.model.VectorPathId,
        clipPath: app.croptop.core.model.VectorPathId,
        operation: app.croptop.core.engine.api.ShapeBooleanOperation,
    ): CommandResult = reject(RejectionCode.UNSUPPORTED, "Vector path booleans require the native engine")

    private fun validateVectorPaths(paths: List<VectorPathDraft>): String? {
        val pathIds = mutableSetOf<Long>()
        val anchorIds = mutableSetOf<Long>()
        for (path in paths) {
            if (path.id.value <= 0L || !pathIds.add(path.id.value)) return "Path ids must be positive and unique"
            for (subpath in path.subpaths) {
                if (subpath.anchors.size > 100_000) return "A path has too many anchors"
                for (anchor in subpath.anchors) {
                    if (anchor.id.value <= 0L || !anchorIds.add(anchor.id.value)) return "Anchor ids must be positive and unique"
                    val points = listOfNotNull(anchor.point, anchor.incoming, anchor.outgoing)
                    if (points.any {
                            !it.x.isFinite() || !it.y.isFinite() || kotlin.math.abs(it.x) > MAX_COORDINATE ||
                                kotlin.math.abs(it.y) > MAX_COORDINATE
                        }
                    ) {
                        return "Path coordinates must be finite and within the canvas range"
                    }
                }
            }
        }
        return null
    }

    override fun translateLayers(ids: Set<LayerId>, dx: Float, dy: Float): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!dx.isFinite() || !dy.isFinite() || kotlin.math.abs(dx) > MAX_COORDINATE || kotlin.math.abs(dy) > MAX_COORDINATE) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Offsets must be finite and within the canvas range")
        }
        if (ids.isEmpty()) return reject(RejectionCode.INVALID_ARGUMENT, "No layers to move")
        val tree = _layers.value
        ids.forEach { id ->
            val node = tree.find(id) ?: return notFound(id)
            if (node.locks.position) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked. Unlock its position to move it.")
        }
        // Same as NativeEngine: a zero move is accepted without a history step.
        if (dx == 0f && dy == 0f) return CommandResult.Accepted(++seq)
        val ix = dx.roundToInt()
        val iy = dy.roundToInt()
        // Moving a group moves everything inside it.
        val moving = buildSet { ids.forEach { id -> tree.find(id)?.let { LayerTree(listOf(it)).flatten().forEach { n -> add(n.id) } } } }
        val next = LayerTreeOps.mapAll(tree) { n -> if (n.id in moving) n.copy(bounds = n.bounds?.translate(ix, iy)) else n }
        return commit("Move", next)
    }

    override fun layerAt(point: DocPoint): LayerId? {
        if (closed || !point.x.isFinite() || !point.y.isFinite()) return null
        if (!DocRect.of(size).contains(point)) return null
        // Top-most pixel layer under the point, including layers inside groups (hidden groups hide their children).
        fun visibleLeaves(
            nodes: List<LayerNode>,
        ): List<LayerNode> = nodes.filter { it.visible }.flatMap { if (it.isGroup) visibleLeaves(it.children) else listOf(it) }
        return visibleLeaves(_layers.value.layers).firstOrNull { n ->
            n.kind == LayerKind.PIXEL && !n.locks.position && (n.bounds?.contains(point) ?: true)
        }?.id
    }

    override fun beginTransform(target: TransformTarget): TransformSession = object : TransformSession {
        private var matrix = Matrix3.Identity
        private var done = false

        override fun update(matrix: Matrix3) {
            if (!done) this.matrix = matrix
        }

        override fun commit(): CommandResult {
            if (done) return reject(RejectionCode.INVALID_ARGUMENT, "Transform already finished")
            done = true
            if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
            return when (target) {
                is TransformTarget.Layers -> {
                    val tree = _layers.value
                    target.ids.firstOrNull { tree.find(it) == null }?.let { return notFound(it) }
                    val next = LayerTreeOps.mapAll(tree) { n ->
                        if (n.id in
                            target.ids
                        ) {
                            n.copy(bounds = n.bounds?.let { mapRect(it, matrix) })
                        } else {
                            n
                        }
                    }
                    commit(if (matrix.isTranslation) "Move" else "Free Transform", next)
                }

                TransformTarget.Selection -> {
                    val cur = currentSelection ?: return reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
                    commit("Transform Selection", _layers.value, selection = SelectionInfo.Active(mapRect(cur, matrix)))
                }
            }
        }

        override fun cancel() {
            done = true
        }
    }

    override fun select(shape: SelectionShape, op: SelectionOp, feather: Float, antiAlias: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!feather.isFinite() || feather < 0f) return reject(RejectionCode.INVALID_ARGUMENT, "Feather must be 0 or more")
        if (!inRange(shape.bounds)) return reject(RejectionCode.INVALID_ARGUMENT, "The selection is too far outside the canvas")
        val box = shape.bounds.toDocRect()
        // A click without a drag (empty shape) deselects, like Photoshop.
        if (box.isEmpty && op == SelectionOp.REPLACE) return deselect()
        val bounds = SelectionBounds.combine(currentSelection, box, op, size)
        val label = when (shape) {
            is SelectionShape.Rect -> "Rectangular Marquee"
            is SelectionShape.Ellipse -> "Elliptical Marquee"
            is SelectionShape.Polygon -> "Lasso"
        }
        return setSelection(label, bounds, feathered = feather > 0f)
    }

    override fun selectAll(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        return setSelection("Select Canvas", DocRect.of(size), feathered = false)
    }

    override fun deselect(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val cur = snapshots[cursor].selection as? SelectionInfo.Active ?: return reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
        lastSelection = cur
        return setSelection("Deselect", null, feathered = false)
    }

    override fun reselect(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val last = lastSelection ?: return reject(RejectionCode.INVALID_ARGUMENT, "There is no selection to restore")
        return setSelection("Reselect", last.bounds, last.feathered)
    }

    override fun invertSelection(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val cur = currentSelection ?: return reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
        // Bounds of the inverse: the whole canvas unless the selection already covers it.
        val all = DocRect.of(size)
        return setSelection("Select Inverse", if (cur == all) null else all, feathered = false)
    }

    private val channels = linkedMapOf<String, DocRect>()
    private val channelFlow = MutableStateFlow<List<app.croptop.core.engine.api.SelectionChannel>>(emptyList())
    override val selectionChannels: StateFlow<List<app.croptop.core.engine.api.SelectionChannel>> get() = channelFlow

    private fun publishChannels() {
        channelFlow.value = channels.map { (name, bounds) -> app.croptop.core.engine.api.SelectionChannel(name, bounds) }
    }

    override fun createTextLayer(id: LayerId, text: app.croptop.core.model.TextLayerInfo): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        if (tree.find(id) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${id.value} already exists")
        if (text.text.isBlank()) return reject(RejectionCode.INVALID_ARGUMENT, "Type some text first")
        val anchor = tree.primary ?: tree.layers.firstOrNull()?.id
        val parent = anchor?.let { tree.parentOf(it)?.id }
        val index = anchor?.let { a -> LayerTreeOps.siblingsOf(tree, a).indexOfFirst { it.id == a }.coerceAtLeast(0) } ?: 0
        val name = text.text.lineSequence().first().trim().take(40).ifEmpty { "Text" }
        val node = LayerNode(id, name, LayerKind.TEXT, bounds = fakeTextBounds(text), text = text)
        val next = LayerTreeOps.insert(tree, node, parent, index) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Can't place the text layer")
        return commit("Type Tool", next.copy(active = setOf(id), primary = id))
    }

    override fun setTextLayer(id: LayerId, text: app.croptop.core.model.TextLayerInfo): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.kind != LayerKind.TEXT) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a text layer")
        return commit("Edit Type Layer", LayerTreeOps.update(_layers.value, id) { it.copy(text = text, bounds = fakeTextBounds(text)) })
    }

    override fun addFillLayer(id: LayerId, fill: app.croptop.core.model.FillLayerInfo): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        if (tree.find(id) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${id.value} already exists")
        val name = when (fill.kind) {
            app.croptop.core.model.FillKind.SOLID -> "Color Fill"
            app.croptop.core.model.FillKind.GRADIENT -> "Gradient Fill"
            app.croptop.core.model.FillKind.PATTERN -> "Pattern Fill"
        }
        val anchor = tree.primary
        val parent = anchor?.let { tree.parentOf(it)?.id }
        val siblings = anchor?.let { tree.parentOf(it)?.children } ?: tree.layers
        val index = siblings.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
        val next = LayerTreeOps.insert(tree, LayerNode(id, name, LayerKind.FILL, fill = fill), parent, index)
            ?: return reject(RejectionCode.INVALID_ARGUMENT, "Can't place the fill layer")
        return commit("New Fill Layer", next.copy(active = setOf(id), primary = id))
    }

    override fun setFillLayer(id: LayerId, fill: app.croptop.core.model.FillLayerInfo): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.kind != LayerKind.FILL) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a fill layer")
        return commit("Edit Fill Layer", LayerTreeOps.update(_layers.value, id) { it.copy(fill = fill) })
    }

    /** Marks the freshly opened primary layer as a RAW smart object (the fake keeps no file, only the recipe). */
    internal fun openAsRawSmartObject(settings: app.croptop.core.model.DevelopSettings) {
        val id = _layers.value.primary ?: return
        val info = app.croptop.core.model.SmartObjectInfo(
            sourceKey = "fake-raw-${id.value}",
            sourceWidth = info.value.size.width,
            sourceHeight = info.value.size.height,
            raw = app.croptop.core.model.RawSourceInfo("fake-raw-file-${id.value}", settings),
        )
        commit("Open RAW as Smart Object", LayerTreeOps.update(_layers.value, id) { it.copy(kind = LayerKind.SMART_OBJECT, smartObject = info) })
    }

    /** The Blend If value last passed to [previewBlendIf] (null once cleared); the fake renders nothing. */
    var previewedBlendIf: app.croptop.core.model.BlendIfSettings? = null
        private set

    var previewedFillOpacity: Float? = null
        private set

    override fun previewFillOpacity(id: LayerId, fillOpacity: Float?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        _layers.value.find(id) ?: return notFound(id)
        if (fillOpacity != null && (!fillOpacity.isFinite() || fillOpacity !in 0f..1f)) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Fill opacity must be between 0 and 1")
        }
        previewedFillOpacity = fillOpacity
        return CommandResult.Accepted(seq)
    }

    override fun previewBlendIf(id: LayerId, blendIf: app.croptop.core.model.BlendIfSettings?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        _layers.value.find(id) ?: return notFound(id)
        previewedBlendIf = blendIf
        return CommandResult.Accepted(seq)
    }

    override fun setRawDevelopSettings(id: LayerId, settings: app.croptop.core.model.DevelopSettings): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        val info = node.smartObject ?: return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a smart object")
        val raw = info.raw ?: return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a RAW smart object")
        return commit(
            "Develop Settings",
            LayerTreeOps.update(_layers.value, id) { it.copy(smartObject = info.copy(raw = raw.copy(settings = settings))) },
        )
    }

    private fun smartInfo(id: LayerId): Pair<LayerNode, app.croptop.core.model.SmartObjectInfo>? {
        val node = _layers.value.find(id) ?: return null
        return node.smartObject?.let { node to it }
    }

    private fun updateSmart(id: LayerId, label: String, transform: (app.croptop.core.model.SmartObjectInfo) -> app.croptop.core.model.SmartObjectInfo) =
        commit(label, LayerTreeOps.update(_layers.value, id) { it.copy(smartObject = it.smartObject?.let(transform)) })

    override fun addSmartFilterMask(id: LayerId, fill: app.croptop.core.engine.api.MaskFill): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val (node, info) = smartInfo(id) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object to add a filter mask")
        if (info.filterMask != null) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” already has a filter mask")
        if (fill.needsSelection && currentSelection == null) return reject(RejectionCode.INVALID_ARGUMENT, "${fill.label} needs a selection")
        return updateSmart(id, "Add Smart Filter Mask") { it.copy(filterMask = app.croptop.core.model.SmartFilterMask("fake-fmask-${seq + 1}")) }
    }

    override fun setSmartFilterMaskEnabled(id: LayerId, enabled: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val (_, info) = smartInfo(id) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object")
        val mask = info.filterMask ?: return reject(RejectionCode.INVALID_ARGUMENT, "The smart object has no filter mask")
        if (mask.enabled == enabled) return CommandResult.Accepted(seq)
        return updateSmart(id, if (enabled) "Enable Smart Filter Mask" else "Disable Smart Filter Mask") { it.copy(filterMask = mask.copy(enabled = enabled)) }
    }

    override fun deleteSmartFilterMask(id: LayerId): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val (_, info) = smartInfo(id) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object")
        if (info.filterMask == null) return reject(RejectionCode.INVALID_ARGUMENT, "The smart object has no filter mask")
        return updateSmart(id, "Delete Smart Filter Mask") { it.copy(filterMask = null) }
    }

    override fun invertSmartFilterMask(id: LayerId): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val (_, info) = smartInfo(id) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object")
        val mask = info.filterMask ?: return reject(RejectionCode.INVALID_ARGUMENT, "The smart object has no filter mask")
        return updateSmart(id, "Invert Smart Filter Mask") { it.copy(filterMask = mask.copy(key = "fake-fmask-${seq + 1}")) }
    }

    override fun paintSmartFilterMask(id: LayerId, points: List<DocPoint>, radius: Float, reveal: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val (_, info) = smartInfo(id) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object")
        val mask = info.filterMask ?: return reject(RejectionCode.INVALID_ARGUMENT, "Add a filter mask first")
        if (points.isEmpty() || !radius.isFinite() || radius <= 0f) return reject(RejectionCode.INVALID_ARGUMENT, "Invalid mask stroke")
        return updateSmart(id, if (reveal) "Paint Smart Filter Mask (Reveal)" else "Paint Smart Filter Mask (Hide)") {
            it.copy(filterMask = mask.copy(key = "fake-fmask-${seq + 1}"))
        }
    }

    private var liquifyActive = false

    override fun beginLiquify(target: LayerId, meshSpacing: Int): app.croptop.core.engine.api.LiquifySession {
        check(!closed) { "Document is closed" }
        check(!liquifyActive) { "Finish the active preview first" }
        val node = _layers.value.find(target) ?: error("No layer ${target.value}")
        check(node.kind == LayerKind.PIXEL) { "Liquify currently edits pixel layers" }
        liquifyActive = true
        return object : app.croptop.core.engine.api.LiquifySession {
            private var touched = false
            private var done = false
            private var frozen = false

            private fun open(): CommandResult.Rejected? = if (done || closed) reject(RejectionCode.CLOSED, "Liquify session is closed") else null

            override fun stroke(stroke: app.croptop.core.engine.api.LiquifyStroke): CommandResult =
                open() ?: run { touched = true; CommandResult.Accepted(seq) }

            override fun paintFreeze(points: List<DocPoint>, radius: Float, freeze: Boolean): CommandResult =
                open() ?: run { frozen = freeze; CommandResult.Accepted(seq) }

            override fun clearFreeze(): CommandResult = open() ?: run { frozen = false; CommandResult.Accepted(seq) }

            override fun reconstructAll(amount: Float): CommandResult =
                open() ?: if (!amount.isFinite() || amount !in 0f..1f) {
                    reject(RejectionCode.INVALID_ARGUMENT, "Reconstruct amount must be 0-1")
                } else {
                    if (amount >= 1f) touched = false
                    CommandResult.Accepted(seq)
                }

            override fun commit(): CommandResult {
                open()?.let { return it }
                done = true
                liquifyActive = false
                return if (touched) commit("Liquify", _layers.value) else CommandResult.Accepted(seq)
            }

            override fun cancel() {
                if (done) return
                done = true
                liquifyActive = false
            }
        }
    }

    override fun warpLayer(id: LayerId, mesh: app.croptop.core.model.WarpMesh): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.kind != LayerKind.PIXEL) return reject(RejectionCode.UNSUPPORTED, "Warp currently edits pixel layers")
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        if (mesh.isIdentity(size.width, size.height)) return CommandResult.Accepted(seq)
        return commit("Warp", LayerTreeOps.update(_layers.value, id) { it.copy(bounds = null) })
    }

    override fun convertToSmartObject(id: LayerId): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.kind != LayerKind.PIXEL && node.kind != LayerKind.TEXT) {
            return reject(RejectionCode.UNSUPPORTED, "Only pixel and type layers can become smart objects")
        }
        val info = app.croptop.core.model.SmartObjectInfo(sourceKey = "fake-source-${id.value}-$seq")
        return commit("Convert to Smart Object", LayerTreeOps.update(_layers.value, id) { it.copy(kind = LayerKind.SMART_OBJECT, smartObject = info, text = null) })
    }

    override fun setSmartFilters(id: LayerId, filters: List<app.croptop.core.model.AdjustmentInfo>): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        val info = node.smartObject ?: return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a smart object")
        if (filters.any { app.croptop.core.model.FilterCatalog[it.specId] == null }) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Only Filter menu filters can be smart filters")
        }
        if (filters.any { it.blendMode == app.croptop.core.model.BlendMode.PASS_THROUGH }) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Pass Through is not a smart-filter blend mode")
        }
        return commit("Smart Filters", LayerTreeOps.update(_layers.value, id) { it.copy(smartObject = info.copy(filters = filters)) })
    }

    override fun rasterizeSmartObject(id: LayerId): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.kind != LayerKind.SMART_OBJECT) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a smart object")
        return commit("Rasterize Layer", LayerTreeOps.update(_layers.value, id) { it.copy(kind = LayerKind.PIXEL, smartObject = null) })
    }

    override fun openSmartObjectContents(id: LayerId): DocumentHandle {
        val node = _layers.value.find(id) ?: throw IllegalArgumentException("No layer ${id.value}")
        val smart = node.smartObject ?: throw IllegalArgumentException("“${node.name}” isn't a smart object")
        val size = DocSize(
            smart.sourceWidth.takeIf { it > 0 } ?: _info.value.size.width,
            smart.sourceHeight.takeIf { it > 0 } ?: _info.value.size.height,
        )
        return createContents("${node.name}.psb", size)
    }

    override fun replaceSmartObjectContents(id: LayerId, contents: DocumentHandle): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        val smart = node.smartObject ?: return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a smart object")
        return commit("Edit Smart Object Contents", LayerTreeOps.update(_layers.value, id) {
            it.copy(
                smartObject = smart.withReplacedSource(
                    "fake-source-${contents.id.value}-${seq + 1}",
                    contents.info.value.size.width,
                    contents.info.value.size.height,
                    size.width,
                    size.height,
                ),
            )
        })
    }

    override fun setLayerStyles(id: LayerId, styles: app.croptop.core.model.LayerStyles?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = snapshots[cursor].tree.find(id) ?: return notFound(id)
        if (node.kind != LayerKind.PIXEL && node.kind != LayerKind.TEXT) {
            return reject(RejectionCode.UNSUPPORTED, "Layer styles apply to pixel and type layers")
        }
        if (node.styles == styles) {
            layerStylePreviewBase.remove(id)
            _layers.value = LayerTreeOps.update(_layers.value, id) {
                it.copy(styles = styles, hasStyles = styles?.anyEnabled == true)
            }
            return CommandResult.Accepted(seq)
        }
        val label = if (node.styles == null) "Layer Style" else "Edit Layer Style"
        val next = LayerTreeOps.update(_layers.value, id) { it.copy(styles = styles, hasStyles = styles?.anyEnabled == true) }
        layerStylePreviewBase.remove(id)
        return commit(label, next)
    }

    override fun previewLayerStyles(id: LayerId, styles: app.croptop.core.model.LayerStyles?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val committed = snapshots[cursor].tree.find(id) ?: return notFound(id)
        if (committed.kind != LayerKind.PIXEL && committed.kind != LayerKind.TEXT) {
            return reject(RejectionCode.UNSUPPORTED, "Layer styles apply to pixel and type layers")
        }
        val live = _layers.value
        if (live.find(id) == null) return notFound(id)

        val base = layerStylePreviewBase.getOrPut(id) { committed.styles }
        _layers.value = LayerTreeOps.update(live, id) {
            it.copy(styles = styles, hasStyles = styles?.anyEnabled == true)
        }
        if (styles == base) layerStylePreviewBase.remove(id)
        return CommandResult.Accepted(seq)
    }

    /** Rough text bounds for the fake: 0.6 em per character, 1.2 em per line, at 72 ppi. */
    private fun fakeTextBounds(text: app.croptop.core.model.TextLayerInfo): DocRect {
        val size = text.character.sizePt
        val lines = text.text.lines()
        val width = if (text.boxWidth > 0f) text.boxWidth else (lines.maxOf { it.length } * size * 0.6f)
        val top = if (text.kind == app.croptop.core.model.TextKind.POINT) text.origin.y - size * 0.8f else text.origin.y
        return DocRect(text.origin.x.toInt(), top.toInt(), (text.origin.x + width).toInt() + 1, (top + lines.size * size * 1.2f).toInt() + 1)
    }

    override fun redEye(region: app.croptop.core.model.DocRectF, pupilSize: Float, darken: Float): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val (node, error) = primaryPixelLayer("Red Eye")
        if (node == null) return error!!
        return commit("Red Eye", _layers.value)
    }

    override fun patch(sourceOffset: DocPoint, contentAware: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        currentSelection ?: return reject(RejectionCode.INVALID_ARGUMENT, "Select the area to patch first")
        val (node, error) = primaryPixelLayer("Patch")
        if (node == null) return error!!
        return commit("Patch Tool", _layers.value)
    }

    override fun liquify(target: LayerId, stroke: LiquifyStroke): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(target) ?: return notFound(target)
        if (node.kind != LayerKind.PIXEL) return reject(RejectionCode.UNSUPPORTED, "Liquify currently edits pixel layers")
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        return commit("Liquify", _layers.value)
    }

    private val quickMaskFlow = MutableStateFlow(false)
    override val quickMask: StateFlow<Boolean> get() = quickMaskFlow

    private val quickMaskOverlayColorFlow = MutableStateFlow(RgbaColor(1f, 0f, 0f, 0.5f))
    override val quickMaskOverlayColor: StateFlow<RgbaColor> get() = quickMaskOverlayColorFlow

    private val maskViewFlow = MutableStateFlow<LayerId?>(null)
    override val maskView: StateFlow<LayerId?> get() = maskViewFlow

    override fun setMaskView(id: LayerId?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (id != null && _layers.value.find(id)?.mask == null) return reject(RejectionCode.INVALID_ARGUMENT, "That layer has no mask to view")
        maskViewFlow.value = id
        return CommandResult.Accepted(seq)
    }

    private val documentGuidesFlow = MutableStateFlow(app.croptop.core.engine.api.DocumentGuides())
    override val documentGuides: StateFlow<app.croptop.core.engine.api.DocumentGuides> get() = documentGuidesFlow

    override fun setDocumentGuides(value: app.croptop.core.engine.api.DocumentGuides): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        documentGuidesFlow.value = value
        return CommandResult.Accepted(seq)
    }

    override fun setQuickMask(enabled: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        quickMaskFlow.value = enabled
        return CommandResult.Accepted(seq)
    }

    override fun setQuickMaskOverlayColor(color: RgbaColor): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (listOf(color.r, color.g, color.b, color.a).any { !it.isFinite() || it !in 0f..1f }) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Quick Mask color channels must be finite values from 0 to 1")
        }
        quickMaskOverlayColorFlow.value = color
        return CommandResult.Accepted(++seq)
    }

    override fun beginMagneticLasso(start: DocPoint, spec: app.croptop.core.engine.api.MagneticLassoSpec): app.croptop.core.engine.api.MagneticLassoSession {
        if (closed) return app.croptop.core.engine.api.UnsupportedMagneticLassoSession()
        // Fake: straight segments between anchors (no edge snapping); close selects the polygon.
        return object : app.croptop.core.engine.api.MagneticLassoSession {
            private val anchors = mutableListOf(start)
            private var finished = false

            override fun move(point: DocPoint): List<DocPoint> = if (finished) emptyList() else listOf(anchors.last(), point)

            override fun anchor(point: DocPoint) {
                if (!finished) anchors += point
            }

            override fun undoAnchor(): Boolean = if (!finished && anchors.size > 1) {
                anchors.removeAt(anchors.lastIndex)
                true
            } else {
                false
            }

            override fun outline(): List<DocPoint> = if (finished) emptyList() else anchors.toList()

            override fun close(): CommandResult {
                if (finished) return reject(RejectionCode.INVALID_ARGUMENT, "This Magnetic Lasso outline already ended")
                finished = true
                if (anchors.size < 3) return reject(RejectionCode.INVALID_ARGUMENT, "The outline is too small to select")
                return select(app.croptop.core.model.SelectionShape.Polygon(anchors.toList()), spec.op, spec.feather, spec.antiAlias)
            }

            override fun cancel() {
                finished = true
            }
        }
    }

    override fun beginRefineEdge(): app.croptop.core.engine.api.RefineEdgeSession {
        if (closed || currentSelection == null) return app.croptop.core.engine.api.UnsupportedRefineEdgeSession()
        return object : app.croptop.core.engine.api.RefineEdgeSession {
            private var finished = false
            private val viewModeFlow = MutableStateFlow(app.croptop.core.engine.api.RefineEdgeViewMode.OVERLAY)
            override val viewMode: StateFlow<app.croptop.core.engine.api.RefineEdgeViewMode> get() = viewModeFlow
            override var outputDocument: DocumentHandle? = null
                private set

            override fun setViewMode(mode: app.croptop.core.engine.api.RefineEdgeViewMode): CommandResult {
                if (finished) return reject(RejectionCode.CLOSED, "Select and Mask has finished")
                viewModeFlow.value = mode
                return CommandResult.Accepted(++seq)
            }

            override fun beginBrushStroke(
                spec: app.croptop.core.engine.api.RefineEdgeBrushSpec,
            ): app.croptop.core.engine.api.RefineEdgeBrushSession {
                if (finished) return app.croptop.core.engine.api.UnsupportedRefineEdgeBrushSession()
                return object : app.croptop.core.engine.api.RefineEdgeBrushSession {
                    private var strokeFinished = false
                    private var sampleCount = 0

                    override fun add(samples: FloatArray) {
                        check(!strokeFinished) { "Refine brush stroke is already finished" }
                        require(samples.size % app.croptop.core.engine.api.StrokeSampleLayout.STRIDE == 0)
                        require(samples.all(Float::isFinite))
                        sampleCount += samples.size / app.croptop.core.engine.api.StrokeSampleLayout.STRIDE
                    }

                    override fun end(): CommandResult {
                        if (strokeFinished) return reject(RejectionCode.INVALID_ARGUMENT, "Refine brush stroke is already finished")
                        strokeFinished = true
                        if (sampleCount == 0) return reject(RejectionCode.INVALID_ARGUMENT, "Empty refine brush stroke")
                        if (finished) return reject(RejectionCode.CLOSED, "Select and Mask has finished")
                        return CommandResult.Accepted(++seq)
                    }

                    override fun cancel() {
                        strokeFinished = true
                    }
                }
            }

            // Bounds-only fake: refinement keeps the selection's bounds; outputs follow the native contract.
            override fun update(settings: app.croptop.core.engine.api.RefineEdgeSettings): CommandResult =
                if (finished) reject(RejectionCode.INVALID_ARGUMENT, "Select and Mask has finished") else CommandResult.Accepted(seq)

            override fun commit(output: app.croptop.core.engine.api.RefineEdgeOutput): CommandResult {
                if (finished) return reject(RejectionCode.INVALID_ARGUMENT, "Select and Mask already finished")
                finished = true
                return when (output) {
                    app.croptop.core.engine.api.RefineEdgeOutput.SELECTION -> CommandResult.Accepted(seq)
                    app.croptop.core.engine.api.RefineEdgeOutput.LAYER_MASK -> _layers.value.primary?.let { pid ->
                        // Native: Output to Layer Mask auto-converts the Background to a regular layer.
                        val node = _layers.value.find(pid)
                        if (node != null && isBackground(node)) {
                            val unlocked = LayerTreeOps.update(_layers.value, pid) { it.copy(locks = LayerLocks.None) }
                            _layers.value = unlocked
                        }
                        addLayerMask(pid, app.croptop.core.engine.api.MaskFill.REVEAL_ALL)
                    } ?: reject(RejectionCode.INVALID_ARGUMENT, "Select a layer to mask")
                    app.croptop.core.engine.api.RefineEdgeOutput.NEW_LAYER -> layerViaCopy(LayerId(kotlin.random.Random.nextLong(1L shl 40, Long.MAX_VALUE)))
                    app.croptop.core.engine.api.RefineEdgeOutput.NEW_DOCUMENT -> {
                        outputDocument = createContents("${_info.value.name} Selection", _info.value.size)
                        CommandResult.Accepted(++seq)
                    }
                }
            }

            override fun cancel() {
                finished = true
            }
        }
    }

    override fun saveSelection(name: String): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val bounds = currentSelection ?: return reject(RejectionCode.INVALID_ARGUMENT, "Create a selection before saving it")
        val channelName = name.trim().ifEmpty {
            val used = channels.keys.mapNotNull { Regex("Alpha (\\d+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
            "Alpha ${used + 1}"
        }
        channels[channelName] = bounds
        publishChannels()
        _info.value = _info.value.copy(dirty = true)
        return CommandResult.Accepted(++seq)
    }

    override fun loadSelection(name: String, op: SelectionOp, invert: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val stored = channels[name] ?: return reject(RejectionCode.NOT_FOUND, "No saved selection named “$name”")
        // Bounds-only fake: an inverted channel covers the whole canvas.
        val shape = if (invert) DocRect.of(size) else stored
        return setSelection("Load Selection", app.croptop.core.model.SelectionBounds.combine(currentSelection, shape, op, size), feathered = false)
    }

    override fun deleteSelectionChannel(name: String): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        channels.remove(name) ?: return reject(RejectionCode.NOT_FOUND, "No saved selection named “$name”")
        publishChannels()
        _info.value = _info.value.copy(dirty = true)
        return CommandResult.Accepted(++seq)
    }

    override fun modifySelection(kind: SelectionModify, px: Float): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val cur = currentSelection ?: return reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
        if (!px.isFinite() || px <= 0f ||
            px > kind.maxPx
        ) {
            return reject(RejectionCode.INVALID_ARGUMENT, "${kind.label} must be between 1 and ${kind.maxPx.toInt()} px")
        }
        return setSelection(kind.label, SelectionBounds.modify(cur, kind, px, size), feathered = kind == SelectionModify.FEATHER)
    }

    override fun beginModifySelection(kind: SelectionModify): SelectionModifySession {
        if (closed) return UnsupportedSelectionModifySession("Document is closed")
        val baseSelection = currentSelection
            ?: return object : SelectionModifySession {
                override fun update(px: Float): CommandResult = reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
                override fun commit(): CommandResult = reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
                override fun cancel() = Unit
            }
        val baseInfo = _selection.value

        return object : SelectionModifySession {
            private var finished = false
            private var currentBounds: DocRect? = baseSelection
            private var hasUpdate = false

            override fun update(px: Float): CommandResult {
                if (closed || finished) return reject(RejectionCode.CLOSED, "Document is closed or session finished")
                if (!px.isFinite() || px <= 0f || px > kind.maxPx) {
                    return reject(RejectionCode.INVALID_ARGUMENT, "${kind.label} must be between 1 and ${kind.maxPx.toInt()} px")
                }
                hasUpdate = true
                val modified = SelectionBounds.modify(baseSelection, kind, px, size)
                currentBounds = modified
                _selection.value = if (modified == null) SelectionInfo.None else SelectionInfo.Active(modified, feathered = (kind == SelectionModify.FEATHER))
                return CommandResult.Accepted(++seq)
            }

            override fun commit(): CommandResult {
                if (closed || finished) return reject(RejectionCode.CLOSED, "Document is closed or session finished")
                finished = true
                val targetBounds = if (hasUpdate) currentBounds else baseSelection
                val targetInfo = if (targetBounds == null) SelectionInfo.None else SelectionInfo.Active(targetBounds, feathered = (kind == SelectionModify.FEATHER))
                _selection.value = targetInfo
                return commit(kind.label, _layers.value, selection = targetInfo)
            }

            override fun cancel() {
                if (finished) return
                finished = true
                _selection.value = baseInfo
            }
        }
    }

    private fun setSelection(label: String, bounds: DocRect?, feathered: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val next = if (bounds == null) SelectionInfo.None else SelectionInfo.Active(bounds, feathered)
        if (next is SelectionInfo.None) (snapshots[cursor].selection as? SelectionInfo.Active)?.let { lastSelection = it }
        return commit(label, _layers.value, selection = next)
    }

    override fun crop(rect: DocRectF, angleDegrees: Float, deleteCropped: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!angleDegrees.isFinite()) return reject(RejectionCode.INVALID_ARGUMENT, "Angle must be finite")
        if (!inRange(rect) || rect.width > MAX_DIMENSION || rect.height > MAX_DIMENSION) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Crops can be at most $MAX_DIMENSION px wide or tall")
        }
        val box = rect.toDocRect()
        if (box.isEmpty) return reject(RejectionCode.INVALID_ARGUMENT, "The crop area is empty")
        val newSize = DocSize(box.width, box.height)
        val canvas = DocRect.of(newSize)
        val next = LayerTreeOps.mapAll(_layers.value) { n ->
            val moved = n.bounds?.translate(-box.left, -box.top)
            n.copy(bounds = if (deleteCropped) moved?.intersect(canvas)?.takeUnless { it.isEmpty } else moved)
        }
        return commit("Crop", next, size = newSize, selection = SelectionInfo.None)
    }

    override fun cropPerspective(quad: CropQuad, deleteCropped: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!deleteCropped) {
            return reject(RejectionCode.UNSUPPORTED, "Keeping cropped pixels is not supported until hidden pixel storage is available")
        }
        val source = size
        if (quad.points.any { it.x < 0f || it.y < 0f || it.x > source.width || it.y > source.height }) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Perspective crop corners must be inside the canvas")
        }
        val output = app.croptop.core.model.PerspectiveCropGeometry.outputSize(quad)
            ?: return reject(RejectionCode.INVALID_ARGUMENT, "Perspective crop corners must form a finite convex quadrilateral")
        if (output.width > MAX_DIMENSION || output.height > MAX_DIMENSION) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Perspective crop dimensions exceed $MAX_DIMENSION pixels per side")
        }
        val matrix = app.croptop.core.model.PerspectiveCropGeometry.homography(quad, output)
            ?: return reject(RejectionCode.INVALID_ARGUMENT, "Could not calculate perspective crop geometry")
        val canvas = DocRect.of(output)
        val next = LayerTreeOps.mapAll(_layers.value) { node ->
            val mappedBounds = node.bounds?.let { bounds ->
                val corners = listOf(
                    DocPoint(bounds.left.toFloat(), bounds.top.toFloat()),
                    DocPoint(bounds.right.toFloat(), bounds.top.toFloat()),
                    DocPoint(bounds.right.toFloat(), bounds.bottom.toFloat()),
                    DocPoint(bounds.left.toFloat(), bounds.bottom.toFloat()),
                ).mapNotNull { app.croptop.core.model.PerspectiveCropGeometry.map(matrix, it) }
                if (corners.size != 4) null else {
                    DocRect(
                        kotlin.math.floor(corners.minOf { it.x }.toDouble()).toInt(),
                        kotlin.math.floor(corners.minOf { it.y }.toDouble()).toInt(),
                        kotlin.math.ceil(corners.maxOf { it.x }.toDouble()).toInt(),
                        kotlin.math.ceil(corners.maxOf { it.y }.toDouble()).toInt(),
                    ).intersect(canvas).takeUnless { it.isEmpty }
                }
            }
            node.copy(bounds = mappedBounds)
        }
        return commit("Perspective Crop", next, size = output, selection = SelectionInfo.None)
    }

    override fun resizeImage(size: DocSize, resample: Resample, resolutionPpi: Float?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (size.width > MAX_DIMENSION ||
            size.height > MAX_DIMENSION
        ) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Images can be at most $MAX_DIMENSION px wide or tall")
        }
        if (resolutionPpi != null &&
            (!resolutionPpi.isFinite() || resolutionPpi <= 0f)
        ) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Resolution must be positive")
        }
        val old = this.size
        val sx = size.width.toDouble() / old.width
        val sy = size.height.toDouble() / old.height
        val next = LayerTreeOps.mapAll(_layers.value) { n -> n.copy(bounds = n.bounds?.let { FakeGeometry.scale(it, sx, sy) }) }
        val sel = currentSelection?.let { SelectionInfo.Active(FakeGeometry.scale(it, sx, sy)) } ?: SelectionInfo.None
        return commit("Image Size", next, size = size, ppi = resolutionPpi ?: snapshots[cursor].ppi, selection = sel)
    }

    override fun resizeCanvas(size: DocSize, anchor: Anchor, fill: CanvasFill): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (size.width > MAX_DIMENSION ||
            size.height > MAX_DIMENSION
        ) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Canvases can be at most $MAX_DIMENSION px wide or tall")
        }
        val r = CanvasResize(this.size, size, anchor)
        val canvas = DocRect.of(size)
        val next = LayerTreeOps.mapAll(_layers.value) { n ->
            // The locked Background grows to the new canvas when it's filled with a color.
            if (n.locks.position && fill is CanvasFill.Color) n.copy(bounds = canvas) else n.copy(bounds = n.bounds?.translate(r.offsetX, r.offsetY))
        }
        val sel = currentSelection?.translate(r.offsetX, r.offsetY)?.intersect(canvas)?.takeUnless { it.isEmpty }
        return commit("Canvas Size", next, size = size, selection = sel?.let { SelectionInfo.Active(it) } ?: SelectionInfo.None)
    }

    override fun rotateCanvas(degrees: Float, fill: CanvasFill): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!degrees.isFinite()) return reject(RejectionCode.INVALID_ARGUMENT, "Angle must be finite")
        val old = size
        val out = FakeGeometry.rotatedSize(old, degrees)
        val next = LayerTreeOps.mapAll(_layers.value) { n -> n.copy(bounds = n.bounds?.let { FakeGeometry.rotate(it, old, degrees, out) }) }
        val label = when (FakeGeometry.quarterTurns(degrees)) {
            1 -> "Rotate 90° Clockwise"
            2 -> "Rotate 180°"
            3 -> "Rotate 90° Counter Clockwise"
            else -> "Rotate Canvas"
        }
        return commit(label, next, size = out, selection = SelectionInfo.None)
    }

    override fun flipCanvas(horizontal: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val sz = size
        val next = LayerTreeOps.mapAll(_layers.value) { n -> n.copy(bounds = n.bounds?.let { FakeGeometry.flip(it, sz, horizontal) }) }
        val sel = currentSelection?.let { SelectionInfo.Active(FakeGeometry.flip(it, sz, horizontal)) } ?: SelectionInfo.None
        return commit(if (horizontal) "Flip Canvas Horizontal" else "Flip Canvas Vertical", next, selection = sel)
    }

    override fun mergeDown(id: LayerId): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        val node = tree.find(id) ?: return notFound(id)
        val siblings = LayerTreeOps.siblingsOf(tree, id)
        val below = siblings.getOrNull(siblings.indexOfFirst { it.id == id } + 1)
            ?: return reject(RejectionCode.INVALID_ARGUMENT, "There's no layer below “${node.name}” to merge into")
        if (node.isGroup || below.isGroup) return reject(RejectionCode.INVALID_ARGUMENT, "Merge Down works on layers, not groups")
        if (below.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${below.name}” is locked")
        val (without, _) = LayerTreeOps.remove(tree, id)!!
        val merged = LayerTreeOps.update(without, below.id) { it.copy(bounds = FakeGeometry.union(it.bounds, node.bounds)) }
        return commit("Merge Down", merged.copy(active = setOf(below.id), primary = below.id))
    }

    override fun stampVisible(newId: LayerId): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        if (tree.find(newId) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${newId.value} already exists")
        val visible = tree.flatten().filter { it.visible && !it.isGroup }
        if (visible.isEmpty()) return reject(RejectionCode.INVALID_ARGUMENT, "Nothing is visible to stamp")
        val bounds = visible.fold(null as DocRect?) { acc, n -> FakeGeometry.union(acc, n.bounds) }
        val anchor = tree.primary ?: tree.layers.first().id
        val parent = tree.parentOf(anchor)?.id
        val index = LayerTreeOps.siblingsOf(tree, anchor).indexOfFirst { it.id == anchor }.coerceAtLeast(0)
        val node = LayerNode(newId, "Layer ${tree.size + 1}", LayerKind.PIXEL, bounds = bounds)
        val next = LayerTreeOps.insert(tree, node, parent, index) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Can't place the stamped layer")
        return commit("Stamp Visible", next.copy(active = setOf(newId), primary = newId))
    }

    // ---- engine-api v3: pixel edits and adjustment layers (fake: bounds and metadata only) ----

    private fun primaryPixelLayer(action: String): Pair<LayerNode?, CommandResult?> {
        val tree = _layers.value
        val id = tree.primary ?: return null to reject(RejectionCode.INVALID_ARGUMENT, "Select a layer first")
        val node = tree.find(id) ?: return null to notFound(id)
        if (node.kind !=
            LayerKind.PIXEL
        ) {
            return null to reject(RejectionCode.INVALID_ARGUMENT, "$action works on pixel layers. “${node.name}” isn't one.")
        }
        return node to null
    }

    override fun fill(source: FillSource, opacity: Float, blendMode: BlendMode, preserveTransparency: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!opacity.isFinite() || opacity !in 0f..1f) return reject(RejectionCode.INVALID_ARGUMENT, "Opacity must be 0–100%")
        val (node, error) = primaryPixelLayer("Fill")
        if (node == null) return error!!
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        if (source is FillSource.ContentAware &&
            currentSelection == null
        ) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Content-Aware Fill needs a selection")
        }
        val area = currentSelection ?: DocRect.of(size)
        val bounds = if (preserveTransparency) node.bounds else FakeGeometry.union(node.bounds, area)
        val newLayerId = (source as? FillSource.ContentAware)?.options?.newLayerId
        if (newLayerId != null) {
            if (_layers.value.find(newLayerId) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${newLayerId.value} already exists")
            val parent = _layers.value.parentOf(node.id)
            val siblings = parent?.children ?: _layers.value.layers
            val inserted = LayerTreeOps.insert(
                _layers.value,
                LayerNode(newLayerId, "Content-Aware Fill", LayerKind.PIXEL, bounds = bounds),
                parent?.id,
                siblings.indexOfFirst { it.id == node.id }.coerceAtLeast(0),
            ) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Can't place the fill layer")
            return commit("Content-Aware Fill", inserted.copy(active = setOf(newLayerId), primary = newLayerId))
        }
        return commit(
            if (source is FillSource.ContentAware) "Content-Aware Fill" else "Fill",
            LayerTreeOps.update(_layers.value, node.id) {
                it.copy(bounds = bounds)
            },
        )
    }

    /** The fake applies fills synchronously to metadata, so its parity job is already complete. */
    override fun fillJob(source: FillSource, opacity: Float, blendMode: BlendMode, preserveTransparency: Boolean): EngineJob =
        EngineJob.completed(fill(source, opacity, blendMode, preserveTransparency))

    private fun validPoint(point: DocPoint) =
        point.x.isFinite() && point.y.isFinite() && point.x >= 0f && point.y >= 0f && point.x < size.width && point.y < size.height

    override fun magicWand(point: DocPoint, tolerance: Int, contiguous: Boolean, sampleAll: Boolean, op: SelectionOp): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!validPoint(point) ||
            tolerance !in 0..255
        ) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Choose a point on the canvas and a tolerance from 0 to 255")
        }
        // The fake has one uniform background and no pixels. Selection geometry is a full-canvas
        // stand-in; actual color matching and connectivity belong to the native engine.
        val bounds = SelectionBounds.combine(currentSelection, DocRect.of(size), op, size)
        return setSelection("Magic Wand", bounds, feathered = false)
    }

    // Quick/Object Selection stand-ins: without pixels there are no edges to find, so the fake selects
    // the painted area (or the region, or the canvas center for Subject). The native engine segments.

    override fun beginColorRange(op: SelectionOp): ColorRangeSession = object : ColorRangeSession {
        private val before = currentSelection
        private var spec: ColorRangeSpec? = null
        private var done = false

        /** Fake stand-in: any sampled color or named range matches the whole canvas; Invert matches nothing. */
        private fun preview(): DocRect? {
            val s = spec ?: return before
            val matches = s.target != ColorRangeTarget.SAMPLED || s.samples.isNotEmpty()
            val area = if (matches != s.invert) DocRect.of(size) else null
            return if (area == null) {
                if (op == SelectionOp.REPLACE || op == SelectionOp.INTERSECT) null else before
            } else {
                SelectionBounds.combine(before, area, op, size)
            }
        }

        override fun update(spec: ColorRangeSpec) {
            check(!done) { "Color Range already finished" }
            if (closed) return
            this.spec = spec
            _selection.value = preview()?.let { SelectionInfo.Active(it) } ?: SelectionInfo.None
        }

        override fun commit(): CommandResult {
            check(!done) { "Color Range already finished" }
            done = true
            if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
            if (spec == null) return reject(RejectionCode.INVALID_ARGUMENT, "Pick colors to select first")
            return setSelection("Color Range", preview(), feathered = false)
        }

        override fun cancel() {
            if (done) return
            done = true
            _selection.value = snapshots[cursor].selection
        }
    }

    override fun beginQuickSelection(spec: QuickSelectionSpec): QuickSelectionSession = object : QuickSelectionSession {
        private val before = currentSelection
        private var painted: DocRect? = null
        private var done = false

        override fun add(points: FloatArray) {
            require(points.size % 2 == 0) { "points must be x,y pairs" }
            check(!done) { "Quick Selection stroke already finished" }
            if (closed) return
            val r = spec.diameter / 2f
            for (i in points.indices step 2) {
                val x = points[i]
                val y = points[i + 1]
                if (!x.isFinite() || !y.isFinite() || maxOf(-x, -y, x, y) > MAX_COORDINATE) continue
                val dab = DocRectF(x - r, y - r, x + r, y + r).toDocRect()
                painted = FakeGeometry.union(painted, dab)
            }
            val preview = preview()
            _selection.value = preview?.let { SelectionInfo.Active(it) } ?: SelectionInfo.None
        }

        private fun preview(): DocRect? = painted?.let { SelectionBounds.combine(before, it, spec.op, size) } ?: before

        override fun end(): CommandResult {
            check(!done) { "Quick Selection stroke already finished" }
            done = true
            if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
            if (painted?.intersect(DocRect.of(size))?.isEmpty != false) {
                restore()
                return reject(RejectionCode.INVALID_ARGUMENT, "Paint over the canvas to select")
            }
            return setSelection("Quick Selection", preview(), feathered = false)
        }

        override fun cancel() {
            if (done) return
            done = true
            restore()
        }

        private fun restore() {
            _selection.value = snapshots[cursor].selection
        }
    }

    override fun selectObject(region: SelectionShape, op: SelectionOp, sampleAll: Boolean): EngineJob = EngineJob.completed(
        objectSelection(region, op),
    )

    private fun objectSelection(region: SelectionShape, op: SelectionOp): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!inRange(region.bounds)) return reject(RejectionCode.INVALID_ARGUMENT, "The region is too far outside the canvas")
        val box = region.bounds.toDocRect().intersect(DocRect.of(size))
        if (box.isEmpty) return reject(RejectionCode.INVALID_ARGUMENT, "Drag around the object you want to select")
        return setSelection("Object Selection", SelectionBounds.combine(currentSelection, box, op, size), feathered = false)
    }

    override fun selectSubject(op: SelectionOp, sampleAll: Boolean): EngineJob = EngineJob.completed(subjectSelection(op))

    private fun subjectSelection(op: SelectionOp): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val w = size.width
        val h = size.height
        val subject = DocRect(w / 5, h / 5, w - w / 5, h - h / 5).takeUnless { it.isEmpty } ?: DocRect.of(size)
        return setSelection("Select Subject", SelectionBounds.combine(currentSelection, subject, op, size), feathered = false)
    }

    override fun selectFromMask(mask: app.croptop.core.engine.api.CoarseMask, op: SelectionOp, sampleAll: Boolean, label: String): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (mask.width > size.width || mask.height > size.height) return reject(RejectionCode.INVALID_ARGUMENT, "The model mask is larger than the image")
        // The bounds of the mask's non-zero area, scaled to the canvas (the fake has no pixels).
        var minX = mask.width
        var minY = mask.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (mask.alpha[y * mask.width + x].toInt() and 0xFF >= 128) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return reject(RejectionCode.INVALID_ARGUMENT, "The model found nothing to select")
        val sx = size.width.toDouble() / mask.width
        val sy = size.height.toDouble() / mask.height
        val area = DocRect(
            (minX * sx).toInt(), (minY * sy).toInt(), ((maxX + 1) * sx).toInt().coerceAtMost(size.width), ((maxY + 1) * sy).toInt().coerceAtMost(size.height),
        )
        return setSelection(label, SelectionBounds.combine(currentSelection, area, op, size), feathered = false)
    }

    override fun addLayerFromPixels(id: LayerId, name: String, patch: app.croptop.core.engine.api.PixelPatch, label: String): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        if (tree.find(id) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${id.value} already exists")
        val anchor = tree.primary ?: tree.layers.firstOrNull()?.id
        val parent = anchor?.let { tree.parentOf(it)?.id }
        val index = anchor?.let { a -> LayerTreeOps.siblingsOf(tree, a).indexOfFirst { it.id == a }.coerceAtLeast(0) } ?: 0
        val next = LayerTreeOps.insert(tree, LayerNode(id, name, LayerKind.PIXEL), parent, index) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Can't place the layer")
        return commit(label, next.copy(active = setOf(id), primary = id))
    }

    override fun applyPixelPatch(target: LayerId, region: DocRect, patch: app.croptop.core.engine.api.PixelPatch, label: String): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(target) ?: return notFound(target)
        if (node.kind != LayerKind.PIXEL) return reject(RejectionCode.UNSUPPORTED, "“${node.name}” is not a pixel layer")
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” has pixel editing locked")
        if (region.isEmpty) return reject(RejectionCode.INVALID_ARGUMENT, "The region is empty")
        return commit(label, _layers.value)
    }

    override fun paintBucket(
        point: DocPoint,
        color: RgbaColor,
        tolerance: Int,
        contiguous: Boolean,
        sampleAll: Boolean,
        opacity: Float,
    ): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!validPoint(point) || tolerance !in 0..255 || !opacity.isFinite() || opacity !in 0f..1f) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Choose a point on the canvas, tolerance 0–255 and opacity 0–100%")
        }
        val (node, error) = primaryPixelLayer("Paint Bucket")
        if (node == null) return error!!
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        val area = currentSelection ?: DocRect.of(size)
        val next = LayerTreeOps.update(_layers.value, node.id) { it.copy(bounds = FakeGeometry.union(it.bounds, area)) }
        return commit("Paint Bucket", next)
    }

    override fun drawGradient(
        start: DocPoint,
        end: DocPoint,
        gradient: ColorGradient,
        shape: GradientShape,
        reverse: Boolean,
        dither: Boolean,
        opacity: Float,
    ): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!validPoint(start) || !end.x.isFinite() || !end.y.isFinite() || !opacity.isFinite() || opacity !in 0f..1f ||
            gradient.stops.any {
                !it.position.isFinite() || it.position !in 0f..1f ||
                    listOf(it.color.r, it.color.g, it.color.b, it.color.a).any { c -> !c.isFinite() || c !in 0f..1f }
            }
        ) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Choose a valid gradient, canvas start point and opacity 0–100%")
        }
        if (start == end) return reject(RejectionCode.INVALID_ARGUMENT, "Drag to set the gradient length")
        val (node, error) = primaryPixelLayer("Gradient")
        if (node == null) return error!!
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        val area = currentSelection ?: DocRect.of(size)
        val next = LayerTreeOps.update(_layers.value, node.id) { it.copy(bounds = FakeGeometry.union(it.bounds, area)) }
        return commit("Gradient", next)
    }

    override fun clearSelection(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        currentSelection ?: return reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
        val (node, error) = primaryPixelLayer("Clear")
        if (node == null) return error!!
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        return commit("Clear", _layers.value)
    }

    // ---- Clipboard (fake: remembers the copied area's geometry, not pixels) ----

    /** The area Copy would take: the selection within the primary layer (or all visible layers), else the whole layer. */
    private fun copyArea(merged: Boolean): Pair<DocRect?, CommandResult?> {
        val canvas = DocRect.of(size)
        val content = if (merged) {
            _layers.value.flatten().filter {
                it.visible && !it.isGroup
            }.fold(null as DocRect?) { acc, n -> FakeGeometry.union(acc, n.bounds ?: canvas) }
                ?: return null to reject(RejectionCode.INVALID_ARGUMENT, "Nothing is visible to copy")
        } else {
            val (node, error) = primaryPixelLayer("Copy")
            if (node == null) return null to error
            node.bounds ?: canvas
        }
        val area = (currentSelection?.let { content.intersect(it) } ?: content).intersect(canvas)
        if (area.isEmpty) return null to reject(RejectionCode.INVALID_ARGUMENT, "The selected area is empty")
        return area to null
    }

    override fun copy(merged: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val (area, error) = copyArea(merged)
        if (area == null) return error!!
        clipboard.value = ClipboardInfo(DocSize(area.width, area.height), area, merged)
        return CommandResult.Accepted(++seq)
    }

    override fun cut(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        currentSelection ?: return reject(RejectionCode.INVALID_ARGUMENT, "Cut needs a selection")
        val (node, error) = primaryPixelLayer("Cut")
        if (node == null) return error!!
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        val (area, areaError) = copyArea(merged = false)
        if (area == null) return areaError!!
        clipboard.value = ClipboardInfo(DocSize(area.width, area.height), area, merged = false)
        return commit("Cut", _layers.value)
    }

    override fun paste(newId: LayerId, at: DocPoint?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val clip = clipboard.value ?: return reject(RejectionCode.INVALID_ARGUMENT, "The clipboard is empty")
        if (at != null && !(at.x.isFinite() && at.y.isFinite())) return reject(RejectionCode.INVALID_ARGUMENT, "Paste point must be finite")
        val tree = _layers.value
        if (tree.find(newId) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${newId.value} already exists")
        val w = clip.size.width
        val h = clip.size.height
        val bounds = if (at == null) {
            clip.origin
        } else {
            val left = (at.x - w / 2f).roundToInt()
            val top = (at.y - h / 2f).roundToInt()
            DocRect(left, top, left + w, top + h)
        }
        val anchor = tree.primary ?: tree.layers.first().id
        val parent = tree.parentOf(anchor)?.id
        val index = LayerTreeOps.siblingsOf(tree, anchor).indexOfFirst { it.id == anchor }.coerceAtLeast(0)
        val number = tree.flatten().mapNotNull { Regex("Layer (\\d+)").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        val node = LayerNode(newId, "Layer ${number + 1}", LayerKind.PIXEL, bounds = bounds)
        val next = LayerTreeOps.insert(tree, node, parent, index) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Can't place the pasted layer")
        return commit("Paste", next.copy(active = setOf(newId), primary = newId))
    }

    override fun layerViaCopy(newId: LayerId): CommandResult = viaCopy(newId, cut = false)

    override fun layerViaCut(newId: LayerId): CommandResult = viaCopy(newId, cut = true)

    private fun viaCopy(newId: LayerId, cut: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        if (tree.find(newId) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${newId.value} already exists")
        val (node, error) = primaryPixelLayer(if (cut) "Layer via Cut" else "Layer via Copy")
        if (node == null) return error!!
        val sel = currentSelection
        if (cut && node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        val bounds = if (sel == null) node.bounds else (node.bounds ?: DocRect.of(size)).intersect(sel).takeUnless { it.isEmpty }
        val name = if (sel == null) "${node.name} copy" else "Layer ${tree.size + 1}"
        val copy = LayerNode(newId, name, LayerKind.PIXEL, bounds = bounds, opacity = node.opacity, blendMode = node.blendMode)
        val parent = tree.parentOf(node.id)?.id
        val index = LayerTreeOps.siblingsOf(tree, node.id).indexOfFirst { it.id == node.id }
        var next = LayerTreeOps.insert(tree, copy, parent, index) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Can't place the new layer")
        // Cut: the source loses the cut area. Bounds are boxes, so they only shrink when the cut covers them.
        if (cut) {
            val src = node.bounds ?: DocRect.of(size)
            if (sel == null || (sel.left <= src.left && sel.top <= src.top && sel.right >= src.right && sel.bottom >= src.bottom)) {
                next = LayerTreeOps.update(next, node.id) { it.copy(bounds = null) }
            }
        }
        val label = when {
            cut -> "Layer via Cut"
            sel == null -> "Duplicate Layer"
            else -> "Layer via Copy"
        }
        return commit(label, next.copy(active = setOf(newId), primary = newId))
    }

    override fun mergeVisible(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        fun leaves(
            nodes: List<LayerNode>,
        ): List<LayerNode> = nodes.filter { it.visible }.flatMap { if (it.isGroup) leaves(it.children) else listOf(it) }
        val visible = leaves(tree.layers)
        if (visible.size < 2) return reject(RejectionCode.INVALID_ARGUMENT, "Merge Visible needs at least two visible layers")
        val target = visible.last() // bottom-most visible layer receives the merge (Photoshop)
        var next = tree
        visible.dropLast(1).forEach { n -> next = LayerTreeOps.remove(next, n.id)?.first ?: next }
        val bounds = visible.fold(null as DocRect?) { acc, n -> FakeGeometry.union(acc, n.bounds ?: DocRect.of(size)) }
        next = LayerTreeOps.update(next, target.id) { it.copy(bounds = bounds, kind = LayerKind.PIXEL) }
        return commit("Merge Visible", next.copy(active = setOf(target.id), primary = target.id))
    }

    override fun ungroup(id: LayerId): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        val group = tree.find(id) ?: return notFound(id)
        if (!group.isGroup) return reject(RejectionCode.INVALID_ARGUMENT, "“${group.name}” isn't a group")
        val parent = tree.parentOf(id)?.id
        val index = LayerTreeOps.siblingsOf(tree, id).indexOfFirst { it.id == id }
        var next = LayerTreeOps.remove(tree, id)!!.first
        group.children.forEachIndexed { i, child -> next = LayerTreeOps.insert(next, child, parent, index + i) ?: next }
        val ids = group.children.map { it.id }.toSet()
        return commit("Ungroup Layers", next.copy(active = ids, primary = ids.firstOrNull()))
    }

    override fun addAdjustmentLayer(id: LayerId, adjustment: AdjustmentInfo): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        if (tree.find(id) != null) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${id.value} already exists")
        val spec = AdjustmentCatalog[adjustment.specId] ?: return reject(RejectionCode.INVALID_ARGUMENT, "Unknown adjustment “${adjustment.specId}”")
        val anchor = tree.primary
        val parent = anchor?.let { tree.parentOf(it)?.id }
        val index = anchor?.let { a -> LayerTreeOps.siblingsOf(tree, a).indexOfFirst { it.id == a }.coerceAtLeast(0) } ?: 0
        val count = tree.flatten().count { it.adjustment?.specId == spec.id } + 1
        val node = LayerNode(id, "${spec.name} $count", LayerKind.ADJUSTMENT, adjustment = AdjustmentInfo(spec.id, spec.normalize(adjustment.values)))
        val next = LayerTreeOps.insert(tree, node, parent, index) ?: return reject(RejectionCode.INVALID_ARGUMENT, "Can't place the adjustment layer")
        return commit("New ${spec.name} Layer", next.copy(active = setOf(id), primary = id))
    }

    override fun setAdjustment(id: LayerId, values: Map<String, ParamValue>, preview: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        val node = tree.find(id) ?: return notFound(id)
        val info = node.adjustment ?: return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't an adjustment layer")
        val spec = AdjustmentCatalog[info.specId] ?: return reject(RejectionCode.INVALID_ARGUMENT, "Unknown adjustment “${info.specId}”")
        val next = LayerTreeOps.update(tree, id) { it.copy(adjustment = info.copy(values = spec.normalize(values))) }
        if (preview) {
            // Live preview during a drag: shown, but not a history step (the final call records one).
            previewBase.getOrPut(id) { info }
            snapshots[cursor] = snapshots[cursor].copy(tree = next)
            _layers.value = next
            return CommandResult.Accepted(++seq)
        }
        // Roll the preview back into the current step so undo returns to the pre-drag values.
        previewBase.remove(id)?.let { base ->
            snapshots[cursor] = snapshots[cursor].let { st -> st.copy(tree = LayerTreeOps.update(st.tree, id) { it.copy(adjustment = base) }) }
        }
        return commit("Modify ${spec.name} Layer", next)
    }

    // ---- Layer masks and clipping (fake: mask metadata only) ----

    private fun isBackground(n: LayerNode) = n.kind == LayerKind.PIXEL && n.locks.transparency && n.locks.position && n.name == "Background"

    /**
     * Fake mask content: the bounds of the revealed (white) area, null when all hidden. Enough for
     * Invert and Load Selection to have observable effects without mask pixels; kept in history.
     */
    private val maskCoverage: Map<LayerId, DocRect?> get() = snapshots[cursor].maskCoverage

    override fun addLayerMask(id: LayerId, fill: MaskFill): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.mask != null) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” already has a mask")
        if (isBackground(node)) {
            return reject(RejectionCode.INVALID_ARGUMENT, "The Background layer can't have a mask. Unlock it to make it a regular layer first.")
        }
        if (fill.needsSelection && currentSelection == null) return reject(RejectionCode.INVALID_ARGUMENT, "${fill.label} needs a selection")
        val canvas = DocRect.of(size)
        val revealed = when (fill) {
            MaskFill.REVEAL_ALL -> canvas

            MaskFill.HIDE_ALL -> null

            MaskFill.REVEAL_SELECTION -> currentSelection

            // The complement of a selection is bounded by the canvas unless the selection covers it.
            MaskFill.HIDE_SELECTION -> canvas.takeUnless { currentSelection == canvas }
        }
        return commit(
            "Add Layer Mask",
            LayerTreeOps.update(_layers.value, id) { it.copy(mask = LayerMaskInfo()) },
            maskCoverage = maskCoverage + (id to revealed),
        )
    }

    override fun removeLayerMask(id: LayerId, apply: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.mask == null) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” has no mask")
        if (apply && node.kind != LayerKind.PIXEL) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Apply Layer Mask works on pixel layers. Delete the mask instead.")
        }
        if (apply && node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        maskPreviewBase.remove(id)
        val coverage = maskCoverage.getOrDefault(id, DocRect.of(size))
        val next = LayerTreeOps.update(_layers.value, id) { n ->
            // Applying crops the layer's content to what the mask revealed.
            if (apply) {
                n.copy(
                    mask = null,
                    bounds = coverage?.let {
                        (n.bounds ?: DocRect.of(size)).intersect(it).takeUnless { r -> r.isEmpty }
                    },
                )
            } else {
                n.copy(mask = null)
            }
        }
        return commit(if (apply) "Apply Layer Mask" else "Delete Layer Mask", next, maskCoverage = maskCoverage - id)
    }

    override fun setLayerMask(id: LayerId, mask: LayerMaskInfo, preview: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        if (!mask.density.isFinite() || mask.density !in 0f..1f || !mask.feather.isFinite() || mask.feather !in 0f..MAX_MASK_FEATHER) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Density must be 0–100% and feather 0–${MAX_MASK_FEATHER.toInt()} px")
        }
        val tree = _layers.value
        val node = tree.find(id) ?: return notFound(id)
        val current = node.mask ?: return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” has no mask")
        val next = LayerTreeOps.update(tree, id) { it.copy(mask = mask) }
        if (preview) {
            maskPreviewBase.getOrPut(id) { current }
            snapshots[cursor] = snapshots[cursor].copy(tree = next)
            _layers.value = next
            return CommandResult.Accepted(++seq)
        }
        val base = maskPreviewBase.remove(id) ?: current
        snapshots[cursor] = snapshots[cursor].let { st -> st.copy(tree = LayerTreeOps.update(st.tree, id) { it.copy(mask = base) }) }
        if (base == mask) return CommandResult.Accepted(++seq)
        val label = when {
            base.enabled != mask.enabled -> if (mask.enabled) "Enable Layer Mask" else "Disable Layer Mask"
            base.linked != mask.linked -> if (mask.linked) "Link Layer Mask" else "Unlink Layer Mask"
            else -> "Mask Properties"
        }
        return commit(label, next)
    }

    override fun invertLayerMask(id: LayerId): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.mask == null) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” has no mask")
        val canvas = DocRect.of(size)
        val inverted = when (maskCoverage.getOrDefault(id, canvas)) {
            null -> canvas
            canvas -> null
            else -> canvas // the complement of a partial mask, bounded by the canvas
        }
        return commit("Invert", _layers.value, maskCoverage = maskCoverage + (id to inverted))
    }

    override fun maskToSelection(id: LayerId, op: SelectionOp): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.mask == null) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” has no mask")
        // Stand-in: the revealed area's bounds; the native engine uses the mask's luminance.
        val area = maskCoverage.getOrDefault(id, DocRect.of(size))
        val bounds = if (area == null) {
            if (op == SelectionOp.REPLACE || op == SelectionOp.INTERSECT) null else currentSelection
        } else {
            SelectionBounds.combine(currentSelection, area, op, size)
        }
        return setSelection("Load Selection", bounds, feathered = bounds != null)
    }

    private fun depthSourceFailure(source: LensBlurDepthSource): CommandResult? {
        val layerId = when (source) {
            is LensBlurDepthSource.LayerPixels -> source.layerId
            is LensBlurDepthSource.LayerMask -> source.layerId
        }
        val node = _layers.value.find(layerId) ?: return notFound(layerId)
        if (node.kind != LayerKind.PIXEL) return reject(RejectionCode.INVALID_ARGUMENT, "Depth source must be a pixel layer")
        if (source is LensBlurDepthSource.LayerMask && node.mask == null) {
            return reject(RejectionCode.INVALID_ARGUMENT, "Depth layer has no layer mask")
        }
        return null
    }

    /** FakeEngine records the command and history but intentionally does not synthesize blurred pixels. */
    override fun applyLensBlur(id: LayerId, spec: LensBlurSpec, depth: LensBlurDepthSource?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.kind != LayerKind.PIXEL || node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "Lens Blur needs an editable pixel layer")
        if (depth != null) depthSourceFailure(depth)?.let { return it }
        return commit("Lens Blur", _layers.value)
    }

    /** FakeEngine exposes the mask attachment/history path; native pixel coverage is verified by NativeEngine tests. */
    override fun applyDepthRangeMask(id: LayerId, depth: LensBlurDepthSource, range: DepthRangeSpec): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val node = _layers.value.find(id) ?: return notFound(id)
        if (node.kind != LayerKind.PIXEL || node.locks.pixels) {
            return reject(RejectionCode.INVALID_ARGUMENT, "A depth-range mask needs an editable pixel target")
        }
        depthSourceFailure(depth)?.let { return it }
        return commit(
            "Depth Range Mask",
            LayerTreeOps.update(_layers.value, id) { it.copy(mask = LayerMaskInfo()) },
            maskCoverage = maskCoverage + (id to DocRect.of(size)),
        )
    }

    override fun setClipped(id: LayerId, clipped: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val tree = _layers.value
        val node = tree.find(id) ?: return notFound(id)
        if (node.clipped == clipped) return CommandResult.Accepted(++seq)
        if (clipped) {
            val siblings = LayerTreeOps.siblingsOf(tree, id)
            if (siblings.last().id == id) return reject(RejectionCode.INVALID_ARGUMENT, "There's no layer below “${node.name}” to clip to")
            if (isBackground(node)) return reject(RejectionCode.INVALID_ARGUMENT, "The Background layer can't be clipped")
        }
        return commit(if (clipped) "Create Clipping Mask" else "Release Clipping Mask", LayerTreeOps.update(tree, id) { it.copy(clipped = clipped) })
    }

    /** Mask settings before a preview drag started (restored into the undo step). */
    private val maskPreviewBase = mutableMapOf<LayerId, LayerMaskInfo>()

    /** Values an adjustment layer had before a preview drag started (restored into the undo step). */
    private val previewBase = mutableMapOf<LayerId, AdjustmentInfo>()

    /** The fake models preview transaction lifecycle, while native pixel changes are tested separately. */
    private var activeDestructiveAdjustmentPreview = false

    override fun applyAdjustment(adjustment: AdjustmentInfo): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val spec = AdjustmentCatalog[adjustment.specId] ?: return reject(RejectionCode.INVALID_ARGUMENT, "Unknown adjustment “${adjustment.specId}”")
        val (node, error) = primaryPixelLayer(spec.name)
        if (node == null) return error!!
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        return commit(spec.name, _layers.value)
    }

    override fun supportsDestructiveAdjustment(specId: String): Boolean = !closed && AdjustmentCatalog[specId] != null

    override fun beginAdjustmentPreview(adjustment: AdjustmentInfo): AdjustmentPreviewSession {
        check(!closed) { "Document is closed" }
        val spec = checkNotNull(AdjustmentCatalog[adjustment.specId]) { "Unknown adjustment ${adjustment.specId}" }
        val (target, error) = primaryPixelLayer(spec.name)
        check(target != null) { (error as? CommandResult.Rejected)?.message ?: "Select a pixel layer first" }
        check(!target.locks.pixels) { "${target.name} is locked" }
        check(!activeDestructiveAdjustmentPreview) { "An adjustment preview is already active" }
        activeDestructiveAdjustmentPreview = true
        var previewValues = spec.normalize(adjustment.values)
        var finished = false
        return object : AdjustmentPreviewSession {
            override fun preview(values: Map<String, ParamValue>): CommandResult {
                if (finished || closed) return reject(RejectionCode.CLOSED, "Adjustment preview is closed")
                previewValues = spec.normalize(values)
                return CommandResult.Accepted(++seq)
            }

            override fun commit(): CommandResult {
                if (finished || closed) return reject(RejectionCode.CLOSED, "Adjustment preview is closed")
                finished = true
                activeDestructiveAdjustmentPreview = false
                if (_layers.value.primary != target.id) {
                    return reject(RejectionCode.INVALID_ARGUMENT, "The active layer changed during adjustment preview")
                }
                return applyAdjustment(AdjustmentInfo(spec.id, previewValues))
            }

            override fun cancel() {
                if (finished) return
                finished = true
                activeDestructiveAdjustmentPreview = false
            }
        }
    }

    override fun colorReadout(color: RgbaColor): ColorReadout {
        require(listOf(color.r, color.g, color.b).all { it.isFinite() && it in 0f..1f }) {
            "Readout RGB channels must be finite and within 0–1"
        }
        fun linear(channel: Float) = if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)
        fun labCurve(value: Float) = if (value > 0.008856452f) value.pow(1f / 3f) else 7.787037f * value + 16f / 116f
        val red = linear(color.r)
        val green = linear(color.g)
        val blue = linear(color.b)
        val x = (0.4360747f * red + 0.3850649f * green + 0.1430804f * blue) / 0.96422f
        val y = 0.2225045f * red + 0.7168786f * green + 0.0606169f * blue
        val z = (0.0139322f * red + 0.0971045f * green + 0.7141733f * blue) / 0.82521f
        val fx = labCurve(x)
        val fy = labCurve(y)
        val fz = labCurve(z)
        val maximum = maxOf(color.r, color.g, color.b)
        val black = 1f - maximum
        val divisor = maximum.coerceAtLeast(1e-6f)
        return ColorReadout(
            lab = listOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz)),
            cmykPercent = listOf(
                (1f - color.r - black) / divisor * 100f,
                (1f - color.g - black) / divisor * 100f,
                (1f - color.b - black) / divisor * 100f,
                black * 100f,
            ).map { it.coerceIn(0f, 100f) },
            outOfGamut = false,
        )
    }

    override fun applyColorProfile(profile: ColorProfileRef, iccProfile: ByteArray?, convertPixels: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        val profileBytes = iccProfile?.copyOf()
        if (profile is ColorProfileRef.Embedded) {
            val bytes = profileBytes ?: return reject(RejectionCode.INVALID_ARGUMENT, "Embedded color profiles require ICC bytes")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (bytes.isEmpty() || digest != profile.hash) return reject(RejectionCode.INVALID_ARGUMENT, "Embedded ICC profile does not match its profile reference")
        } else if (profileBytes != null) {
            return reject(RejectionCode.INVALID_ARGUMENT, "ICC bytes are only accepted with an embedded profile")
        }
        val label = if (convertPixels) "Convert Color Profile" else "Assign Color Profile"
        // FakeEngine has no raster payload; it models the profile/history transition only.
        return commit(label, _layers.value, profile = profile)
    }

    override fun sample(point: DocPoint, mode: SampleMode, size: Int): ColorSample {
        require(size in SAMPLE_SIZES) { "Sample size must be one of $SAMPLE_SIZES" }
        if (samplePixel == null || size == 1) return sample(point, mode)

        val centerX = kotlin.math.floor(point.x).toInt()
        val centerY = kotlin.math.floor(point.y).toInt()
        val radius = size / 2
        val left = maxOf(0, centerX - radius)
        val top = maxOf(0, centerY - radius)
        val right = minOf(_info.value.size.width - 1, centerX + radius)
        val bottom = minOf(_info.value.size.height - 1, centerY + radius)
        if (left > right || top > bottom) return sample(point, mode)

        var alphaSum = 0.0
        var redPremultiplied = 0.0
        var greenPremultiplied = 0.0
        var bluePremultiplied = 0.0
        var count = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val color = samplePixel(x, y, mode)
                val alpha = color.a.coerceIn(0f, 1f).toDouble()
                alphaSum += alpha
                redPremultiplied += srgbToLinear(color.r) * alpha
                greenPremultiplied += srgbToLinear(color.g) * alpha
                bluePremultiplied += srgbToLinear(color.b) * alpha
                count++
            }
        }
        val alpha = alphaSum / count
        val color = if (alphaSum == 0.0) {
            RgbaColor.Transparent
        } else {
            RgbaColor(
                linearToSrgb(redPremultiplied / alphaSum),
                linearToSrgb(greenPremultiplied / alphaSum),
                linearToSrgb(bluePremultiplied / alphaSum),
                alpha.toFloat(),
            )
        }
        return ColorSample(color, point, mode)
    }

    /** Geometry must stay within ±[MAX_COORDINATE] so integer bounds can't overflow. */
    private fun inRange(r: DocRectF) = maxOf(-r.left, -r.top, r.right, r.bottom) <= MAX_COORDINATE

    private fun mapRect(r: DocRect, m: Matrix3): DocRect {
        val pts = listOf(
            DocPoint(r.left.toFloat(), r.top.toFloat()),
            DocPoint(r.right.toFloat(), r.top.toFloat()),
            DocPoint(r.left.toFloat(), r.bottom.toFloat()),
            DocPoint(r.right.toFloat(), r.bottom.toFloat()),
        ).map(m::map)
        return DocRectF(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y }).toDocRect()
    }

    override fun attachViewport(surface: Surface, viewport: ViewportState): Viewport = object : Viewport {
        override fun update(state: ViewportState) = Unit

        override fun setOverlays(overlays: OverlayState) = Unit

        override fun detach() = Unit
    }

    override suspend fun save(target: SaveTarget, options: SaveOptions) {
        check(!closed) { "Document is closed" }
        if (target is SaveTarget.AppFile) File(target.path).writeText("croptop-fake-document\n")
        cleanIndex.value = cursor
        _info.value = _info.value.copy(dirty = false)
    }

    override suspend fun export(target: ExportTarget, options: ExportOptions): ExportResult {
        check(!closed) { "Document is closed" }
        onExport(options)
        val size = _info.value.size
        val w = (size.width * options.scale).toInt().coerceAtLeast(1)
        val h = (size.height * options.scale).toInt().coerceAtLeast(1)
        val bytes = if (target is ExportTarget.AppFile) {
            File(target.path).apply { writeText("croptop-fake-export ${options.format}\n") }.length()
        } else {
            0L
        }
        return ExportResult(w, h, bytes, warnings = listOf("Fake engine: no pixels were rendered"))
    }

    override fun sample(point: DocPoint, mode: SampleMode): ColorSample {
        val color = samplePixel?.invoke(kotlin.math.floor(point.x).toInt(), kotlin.math.floor(point.y).toInt(), mode) ?: when (background) {
            BackgroundContents.WHITE -> RgbaColor.White
            BackgroundContents.BLACK -> RgbaColor.Black
            else -> RgbaColor.Transparent
        }
        return ColorSample(color, point, mode)
    }

    // Fake sampling is a local value lookup and does not wait on rendering or pixel commits.
    override fun trySample(point: DocPoint, mode: SampleMode, size: Int): ColorSample? =
        if (size == 1) sample(point, mode) else sample(point, mode, size)

    override fun histogram(scope: HistogramScope): Flow<Histogram> {
        val empty = List(256) { 0.0 }
        return flowOf(Histogram.fromChannels(empty, empty, empty, empty))
    }

    override fun thumbnails(layerIds: List<LayerId>, px: Int): Flow<LayerThumb> {
        require(px > 0)
        val rgba = ByteArray(px * px * 4) { i -> if (i % 4 == 3) -1 else 0xCC.toByte() }
        return kotlinx.coroutines.flow.flow { layerIds.forEach { emit(LayerThumb(it, px, px, rgba)) } }
    }

    private val _softProof = MutableStateFlow<app.croptop.core.model.SoftProofSettings?>(null)
    override val softProof: StateFlow<app.croptop.core.model.SoftProofSettings?> = _softProof.asStateFlow()

    override fun setSoftProof(settings: app.croptop.core.model.SoftProofSettings?): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        _softProof.value = settings
        return CommandResult.Accepted(seq)
    }

    override suspend fun renderRegion(region: DocRect): app.croptop.core.engine.api.DocumentRegionPreview? {
        if (closed || region.isEmpty || region.left < 0 || region.top < 0 || region.right > size.width || region.bottom > size.height) return null
        val rgba = ByteArray(region.width * region.height * 4)
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                val argb = sample(DocPoint(region.left + x + 0.5f, region.top + y + 0.5f), SampleMode.ALL_LAYERS).color.toArgb()
                val offset = (y * region.width + x) * 4
                rgba[offset] = (argb shr 16).toByte()
                rgba[offset + 1] = (argb shr 8).toByte()
                rgba[offset + 2] = argb.toByte()
                rgba[offset + 3] = (argb ushr 24).toByte()
            }
        }
        return app.croptop.core.engine.api.DocumentRegionPreview(region, rgba)
    }

    /** The fake keeps only the selection's bounds, so coverage is full inside them. */
    override fun selectionCoverage(region: DocRect): ByteArray? {
        val bounds = (_selection.value as? SelectionInfo.Active)?.bounds ?: return null
        if (closed || region.isEmpty) return null
        return ByteArray(region.width * region.height) { i ->
            val x = region.left + i % region.width
            val y = region.top + i / region.width
            if (x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom) -1 else 0
        }
    }

    override suspend fun preview(px: Int): DocumentPreview? {
        require(px in 1..DocumentPreview.MAX_DIMENSION) {
            "Preview size must be between 1 and ${DocumentPreview.MAX_DIMENSION} px"
        }
        if (closed) return null
        val sourceSize = _info.value.size
        val (width, height) = DocumentPreview.dimensionsFor(sourceSize.width, sourceSize.height, px)
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val point = DocPoint(
                    x = ((x + 0.5f) * sourceSize.width / width).coerceAtMost(sourceSize.width.toFloat() - 0.001f),
                    y = ((y + 0.5f) * sourceSize.height / height).coerceAtMost(sourceSize.height.toFloat() - 0.001f),
                )
                val argb = sample(point, SampleMode.ALL_LAYERS).color.toArgb()
                val offset = (y * width + x) * 4
                rgba[offset] = (argb shr 16).toByte()
                rgba[offset + 1] = (argb shr 8).toByte()
                rgba[offset + 2] = argb.toByte()
                rgba[offset + 3] = (argb ushr 24).toByte()
            }
        }
        return DocumentPreview(width, height, rgba)
    }

    override fun maskThumbnails(layerIds: List<LayerId>, px: Int): Flow<LayerThumb> {
        require(px > 0)
        return flow {
            maskThumbnailRevision.collect {
                layerIds.forEach { id ->
                    val node = _layers.value.find(id) ?: return@forEach
                    val mask = node.mask ?: return@forEach
                    val coverage = maskCoverage.getOrDefault(id, DocRect.of(size))
                    val rgba = ByteArray(px * px * 4)
                    for (y in 0 until px) {
                        for (x in 0 until px) {
                            val docX = ((x + 0.5f) * size.width / px).toInt()
                            val docY = ((y + 0.5f) * size.height / px).toInt()
                            val gray = if (coverage?.contains(app.croptop.core.model.DocPoint(docX.toFloat(), docY.toFloat())) == true) 0xFF else 0
                            val offset = (y * px + x) * 4
                            rgba[offset] = gray.toByte()
                            rgba[offset + 1] = gray.toByte()
                            rgba[offset + 2] = gray.toByte()
                            rgba[offset + 3] = 0xFF.toByte()
                        }
                    }
                    emit(LayerThumb(id, px, px, rgba))
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        layerStylePreviewBase.clear()
        namedSnapshots.clear()
        onClose(this)
    }

    private fun commit(
        label: String,
        next: LayerTree,
        size: DocSize = snapshots[cursor].size,
        ppi: Float = snapshots[cursor].ppi,
        selection: SelectionInfo = snapshots[cursor].selection,
        maskCoverage: Map<LayerId, DocRect?> = snapshots[cursor].maskCoverage,
        profile: ColorProfileRef = snapshots[cursor].profile,
    ): CommandResult {
        // A new edit discards the redo branch.
        val stableNext = layerStylePreviewBase.entries.fold(next) { tree, (id, styles) ->
            if (tree.find(id) == null) {
                tree
            } else {
                LayerTreeOps.update(tree, id) {
                    it.copy(styles = styles, hasStyles = styles?.anyEnabled == true)
                }
            }
        }
        layerStylePreviewBase.clear()
        while (snapshots.lastIndex > cursor) snapshots.removeAt(snapshots.lastIndex)
        if (cleanIndex.value > cursor) cleanIndex.value = -1
        snapshots += Snapshot(label, stableNext, size, ppi, selection, maskCoverage, profile)
        cursor = snapshots.lastIndex
        publish()
        return CommandResult.Accepted(++seq)
    }

    private fun moveTo(index: Int): CommandResult {
        layerStylePreviewBase.clear()
        cursor = index
        publish()
        return CommandResult.Accepted(++seq)
    }

    private fun publish() {
        val state = snapshots[cursor]
        _layers.value = state.tree
        _selection.value = state.selection
        maskThumbnailRevision.value++
        publishHistory()
        _info.value = _info.value.copy(size = state.size, resolutionPpi = state.ppi, profile = state.profile, dirty = cursor != cleanIndex.value)
        val acceptedSequence = seq + 1
        mutableEvents.tryEmit(EngineEvent.Applied(acceptedSequence, state.label))
        mutableEvents.tryEmit(EngineEvent.Rendered(acceptedSequence))
    }

    private fun publishHistory() {
        _history.value = historyState()
    }

    private fun historyState() = HistoryState(
        snapshots.map { HistoryEntry(it.label) },
        cursor,
        namedSnapshots.mapIndexed { index, snapshot -> HistorySnapshot(snapshot.name, index) },
    )

    private fun notFound(id: LayerId) = reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")

    private fun reject(code: RejectionCode, message: String) = CommandResult.Rejected(code, message)

    private fun propsLabel(name: String?, visible: Boolean?, opacity: Float?, fill: Float?, blend: BlendMode?, blendIf: app.croptop.core.model.BlendIfSettings? = null): String = when {
        name != null -> "Rename Layer"
        visible != null -> if (visible) "Show Layer" else "Hide Layer"
        opacity != null -> "Opacity Change"
        fill != null -> "Fill Opacity Change"
        blend != null || blendIf != null -> "Blending Change"
        else -> "Layer Properties"
    }

    companion object {
        private const val MAX_SNAPSHOT_NAME_LENGTH = 120
        private const val MAX_DIMENSION = app.croptop.core.model.MAX_DIMENSION
        val SAMPLE_SIZES = setOf(1, 3, 5, 11, 31, 51, 101)

        /** Bound for incoming coordinates (twice the largest canvas). */
        const val MAX_COORDINATE = 2f * MAX_DIMENSION
        const val MAX_MASK_FEATHER = 1000f

        private fun validSnapshotName(name: String) = name.isNotEmpty() && name.length <= MAX_SNAPSHOT_NAME_LENGTH

        // Packed x, y, pressure, tiltX, tiltY, orientation, time records follow StrokeSampleLayout.
    }
}

private val LayerKind.label: String
    get() = when (this) {
        LayerKind.PIXEL -> "Layer"
        LayerKind.ADJUSTMENT -> "Adjustment Layer"
        LayerKind.FILL -> "Fill Layer"
        LayerKind.TEXT -> "Text Layer"
        LayerKind.SHAPE -> "Shape Layer"
        LayerKind.GROUP -> "Group"
        LayerKind.SMART_OBJECT -> "Smart Object"
    }

private val RAW_EXTENSIONS = setOf(
    "3fr", "arw", "cr2", "cr3", "dcr", "dng", "fff", "iiq", "kdc", "mef", "mos", "mrw",
    "nef", "nrw", "orf", "pef", "ptx", "raf", "raw", "rw2", "rwl", "srw", "x3f",
)
