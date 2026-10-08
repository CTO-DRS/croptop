package app.croptop.feature.develop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.DevelopRepository
import app.croptop.core.engine.api.DevelopPreview
import app.croptop.core.engine.api.DevelopPreviewSession
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.Engine
import app.croptop.core.model.DevelopPreset
import app.croptop.core.model.DevelopSection
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.DevelopSnapshot
import app.croptop.core.model.LocalMask
import app.croptop.core.model.LocalMaskTemplates
import app.croptop.core.model.MaskComponent
import app.croptop.core.ai.AiEditActions
import app.croptop.core.ai.AiMaskSource
import app.croptop.core.ai.AiRuntime
import app.croptop.core.ai.ModelSpec
import app.croptop.core.ai.RgbaImage
import app.croptop.core.ai.SegmentOutcome
import app.croptop.core.ai.UnavailableAi
import app.croptop.core.model.MaskKind
import app.croptop.core.model.MaskStroke
import app.croptop.core.model.Spot
import app.croptop.core.model.UprightGuide
import app.croptop.core.model.VirtualCopy
import app.croptop.core.model.developKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SidecarExportState {
    data object Idle : SidecarExportState
    data object Saving : SidecarExportState
    data class Saved(val bytes: Long) : SidecarExportState
    data class Unsupported(val message: String) : SidecarExportState
    data class Failed(val message: String) : SidecarExportState
}

/** Develop history step. */
data class DevelopStep(val label: String, val settings: DevelopSettings)

/** Copy/paste/sync buffer shared across photos (Ctrl+Shift+C / Ctrl+Shift+V). */
object DevelopClipboard {
    var settings: DevelopSettings? = null
}

@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = DevelopViewModel.Factory::class)
class DevelopViewModel @AssistedInject constructor(
    private val repo: DevelopRepository,
    private val engine: Engine,
    @Assisted val uri: String,
    /** The virtual copy being edited; null edits the original's recipe. */
    @Assisted val copyId: Long? = null,
    /** The model-backed AI features; [UnavailableAi] where no runtime is wired (tests). */
    private val ai: AiEditActions = UnavailableAi,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(uri: String, copyId: Long?): DevelopViewModel
    }

    /** Where this session's recipe is stored: the file's own key or its virtual copy's. */
    private val settingsKey = developKey(uri, copyId)

    /** The photo's virtual copies (the same for the original and every copy). */
    val copies: StateFlow<List<VirtualCopy>> = repo.copies(uri)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Creates a virtual copy from the current recipe and returns its id (the screen then opens it). */
    suspend fun createCopy(): Long = repo.createCopy(uri, _settings.value, "Copy ${copies.value.size + 1}")

    fun deleteCopy(id: Long) = viewModelScope.launch { repo.deleteCopy(uri, id) }

    fun renameCopy(id: Long, name: String) = viewModelScope.launch { repo.renameCopy(id, name) }

    /** Named restore points of this photo's original recipe. */
    val snapshots: StateFlow<List<DevelopSnapshot>> = repo.snapshots(uri)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Stores the current recipe as a snapshot (named "Snapshot N" unless [name] is given). */
    fun createSnapshot(name: String? = null) = viewModelScope.launch {
        repo.createSnapshot(uri, name ?: "Snapshot ${snapshots.value.size + 1}", _settings.value)
    }

    /** Returns to a snapshot's recipe as one undoable history step. */
    fun applySnapshot(snapshot: DevelopSnapshot) = set("Snapshot: ${snapshot.name}") { snapshot.settings }

    fun deleteSnapshot(id: Long) = viewModelScope.launch { repo.deleteSnapshot(uri, id) }

    private val _settings = MutableStateFlow(DevelopSettings())
    val settings: StateFlow<DevelopSettings> = _settings.asStateFlow()

    /** False until the repository's persisted recipe has replaced the neutral startup value. */
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    /** A neutral recipe has no explicit edits, so leave RAW sidecar selection to the engine. */
    val rawSettingsForOpen: DevelopSettings?
        get() = if (_settingsLoaded.value) _settings.value.takeUnless { it.isDefault } else null

    private val _sidecarExport = MutableStateFlow<SidecarExportState>(SidecarExportState.Idle)
    val sidecarExport: StateFlow<SidecarExportState> = _sidecarExport.asStateFlow()

    fun saveXmpSidecar() {
        if (!_settingsLoaded.value || _sidecarExport.value == SidecarExportState.Saving) return
        val scheme = runCatching { URI(uri).scheme }.getOrNull()
        if (scheme != "content") {
            _sidecarExport.value = SidecarExportState.Unsupported("Choose a RAW file from a writable folder to save its XMP sidecar.")
            return
        }
        viewModelScope.launch {
            _sidecarExport.value = SidecarExportState.Saving
            _sidecarExport.value = try {
                val bytes = engine.exportRawDevelopSidecar(DocumentSource.ContentUri(uri), _settings.value)
                SidecarExportState.Saved(bytes)
            } catch (error: UnsupportedOperationException) {
                SidecarExportState.Unsupported(error.message ?: "XMP sidecar export is not supported for this source.")
            } catch (error: IllegalArgumentException) {
                SidecarExportState.Unsupported(error.message ?: "XMP sidecars are available for supported RAW files only.")
            } catch (error: SecurityException) {
                SidecarExportState.Failed("This folder is read-only or its write permission has expired.")
            } catch (error: Exception) {
                SidecarExportState.Failed(error.message?.takeIf(String::isNotBlank) ?: "Could not save the XMP sidecar.")
            }
        }
    }

    /** Non-null while hovering a preset: shown in the preview without committing. */
    private val _hoverPreview = MutableStateFlow<DevelopSettings?>(null)
    val hoverPreview: StateFlow<DevelopSettings?> = _hoverPreview.asStateFlow()

    val showBefore = MutableStateFlow(false)

    private val _history = MutableStateFlow(listOf(DevelopStep("Import", DevelopSettings())))
    val history: StateFlow<List<DevelopStep>> = _history.asStateFlow()
    private val _cursor = MutableStateFlow(0)
    val cursor: StateFlow<Int> = _cursor.asStateFlow()

    /** Groups a slider drag into one history step (committed on release). */
    private var dragBase: DevelopSettings? = null

    /** The engine-rendered preview of the recipe; null until the RAW is decoded (the screen then shows a quick approximation). */
    private val _frame = MutableStateFlow<DevelopPreview?>(null)
    val frame: StateFlow<DevelopPreview?> = _frame.asStateFlow()

    /** True while the original frame is being decoded for real previews. */
    private val _previewOpening = MutableStateFlow(true)
    val previewOpening: StateFlow<Boolean> = _previewOpening.asStateFlow()

    /** Why real previews are unavailable (not a RAW, undecodable), or null. */
    private val _previewUnavailable = MutableStateFlow<String?>(null)
    val previewUnavailable: StateFlow<String?> = _previewUnavailable.asStateFlow()

    /** While the crop frame is being edited the preview shows the uncropped image so the frame can move over it. */
    val cropEditing = MutableStateFlow(false)

    /** While drawing Guided-upright lines the preview shows the frame before Upright and the crop, so the lines sit on the raw picture. */
    val guideEditing = MutableStateFlow(false)

    fun addGuide(guide: UprightGuide) {
        if (_settings.value.uprightGuides.size >= UprightGuide.MAX) return
        set("Add upright guide") { it.copy(uprightGuides = it.uprightGuides + guide) }
    }

    fun deleteGuide(index: Int) {
        if (index !in _settings.value.uprightGuides.indices) return
        set("Delete upright guide") { it.copy(uprightGuides = it.uprightGuides.filterIndexed { i, _ -> i != index }) }
    }

    // ---- Local masks ----

    /** Index of the mask being edited (handles and overlay on the canvas), or null. */
    val selectedMask = MutableStateFlow<Int?>(null)

    // ---- Spot removal ----

    val selectedSpot = MutableStateFlow<Int?>(null)

    /** Selects a mask for editing on the canvas; a mask and a spot are never edited at once. */
    fun selectMask(index: Int?) {
        selectedMask.value = index
        if (index != null) selectedSpot.value = null
    }

    fun selectSpot(index: Int?) {
        selectedSpot.value = index
        if (index != null) selectedMask.value = null
    }

    /** True while taps on the preview place new spots. */
    val spotTool = MutableStateFlow(false)

    fun addSpot(x: Float, y: Float, aspect: Float) {
        if (_settings.value.spots.size >= Spot.MAX_SPOTS) return
        val radius = 0.02f
        val source = SpotGeometry.defaultSource(x, y, radius, aspect)
        set("Add spot") { it.copy(spots = it.spots + Spot(x, y, radius, source.x, source.y)) }
        selectSpot(_settings.value.spots.lastIndex)
    }

    fun deleteSpot(index: Int) {
        if (index !in _settings.value.spots.indices) return
        set("Delete spot") { it.copy(spots = it.spots.filterIndexed { i, _ -> i != index }) }
        selectedSpot.value = null
    }

    fun setSpot(index: Int, label: String, transform: (Spot) -> Spot) =
        set(label) { s -> s.copy(spots = s.spots.mapIndexed { i, sp -> if (i == index) transform(sp) else sp }) }

    fun previewSpot(index: Int, transform: (Spot) -> Spot) =
        preview { s -> s.copy(spots = s.spots.mapIndexed { i, sp -> if (i == index) transform(sp) else sp }) }

    /** Before / After side by side: the original is drawn left of a draggable divider, the edit right of it. */
    val splitView = MutableStateFlow(false)

    private val _splitBefore = MutableStateFlow<DevelopPreview?>(null)

    /** The unedited frame (same crop and upright as the edit, so both align) while [splitView] is on. */
    val splitBefore: StateFlow<DevelopPreview?> = _splitBefore.asStateFlow()

    /** While true, the next tap on the preview sets the white balance from the tapped pixel. */
    val pickingWhiteBalance = MutableStateFlow(false)

    /** Neutralizes the pixel at ([x], [y]) of the current preview frame; returns false when it is not a usable reference. */
    fun pickWhiteBalance(x: Int, y: Int): Boolean {
        val shown = _frame.value ?: return false
        val argb = shown.argb[y.coerceIn(0, shown.height - 1) * shown.width + x.coerceIn(0, shown.width - 1)]
        val solved = app.croptop.feature.develop.WhiteBalancePicker.solve(argb, _settings.value.whiteBalance) ?: return false
        set("White Balance (picker)") { it.copy(whiteBalance = solved) }
        pickingWhiteBalance.value = false
        return true
    }

    /** What a drag or tap on the preview edits while a mask is selected. */
    val maskTool = MutableStateFlow(MaskTool.HANDLES)

    /** Settings of the next brush or erase stroke: size as a fraction of the longer edge, feather and flow 0–1. */
    val brushSize = MutableStateFlow(0.05f)
    val brushFeather = MutableStateFlow(0.5f)
    val brushFlow = MutableStateFlow(1f)

    /** O toggles the red coverage overlay of the selected mask. */
    val maskOverlayVisible = MutableStateFlow(true)

    /** ARGB tint of the mask overlay (red by default, like Lightroom); pick another when the photo is red itself. */
    val maskOverlayColor = MutableStateFlow(MASK_OVERLAY_COLORS.first().second)

    private val _overlay = MutableStateFlow<DevelopPreview?>(null)
    val overlay: StateFlow<DevelopPreview?> = _overlay.asStateFlow()

    fun addMask(kind: MaskKind) {
        if (_settings.value.masks.size >= LocalMask.MAX_MASKS) return
        val number = _settings.value.masks.count { m -> m.components.firstOrNull()?.kind == kind } + 1
        set("Add ${kind.label} mask") { it.copy(masks = it.masks + LocalMaskTemplates.of(kind, number)) }
        selectMask(_settings.value.masks.lastIndex)
    }

    // ---- AI masks (subject, sky, background) ----

    /** What the first-use download sheet waits on, as in the editor: the source, the models to fetch, and what to re-run afterwards. */
    data class AiMaskPrompt(val source: AiMaskSource, val models: List<ModelSpec>, val bytes: Long)

    val aiPrompt = MutableStateFlow<AiMaskPrompt?>(null)

    /** A short note about the last AI mask attempt (nothing found, model failed); cleared on the next attempt. */
    val aiNotice = MutableStateFlow<String?>(null)

    /** True while a model is running for a new AI mask. */
    val aiBusy = MutableStateFlow(false)

    val aiModels get() = ai.models

    fun installAiModel(id: String) = ai.install(id)

    fun cancelAiModel(id: String) = ai.cancelInstall(id)

    fun dismissAiPrompt() {
        aiPrompt.value = null
    }

    fun retryAiPrompt() {
        val source = aiPrompt.value?.source ?: return
        aiPrompt.value = null
        addAiMask(source)
    }

    /**
     * Adds a mask made by a segmentation model from the picture as shown now. The mask follows the frame it was made on, so
     * changing the crop afterwards means adding it again. Asks for the model first when it is not on the device.
     */
    fun addAiMask(source: AiMaskSource) {
        if (_settings.value.masks.size >= LocalMask.MAX_MASKS || aiBusy.value) return
        val shown = _frame.value ?: run {
            aiNotice.value = "Wait for the picture to finish loading"
            return
        }
        aiNotice.value = null
        when (val availability = ai.availability(source.feature)) {
            AiRuntime.Availability.Ready -> Unit
            is AiRuntime.Availability.NeedsDownload -> {
                aiPrompt.value = AiMaskPrompt(source, availability.specs, availability.bytes)
                return
            }
            is AiRuntime.Availability.Unavailable -> {
                aiNotice.value = availability.reason
                return
            }
        }
        aiBusy.value = true
        viewModelScope.launch {
            try {
                val rgba = ByteArray(shown.argb.size * 4)
                shown.argb.forEachIndexed { i, c ->
                    rgba[i * 4] = (c ushr 16).toByte()
                    rgba[i * 4 + 1] = (c ushr 8).toByte()
                    rgba[i * 4 + 2] = c.toByte()
                    rgba[i * 4 + 3] = -1
                }
                when (val outcome = ai.segment(source, RgbaImage(shown.width, shown.height, rgba))) {
                    is SegmentOutcome.Failed -> aiNotice.value = outcome.message
                    is SegmentOutcome.Found -> {
                        val bitmap = AiMaskBitmaps.fit(outcome.mask, source.label)
                        val number = _settings.value.masks.count { m -> m.components.firstOrNull()?.bitmap?.label == source.label } + 1
                        val mask = LocalMask(name = if (number == 1) source.label else "${source.label} $number", components = listOf(MaskComponent.ai(bitmap)))
                        set("Add ${source.label} mask") { it.copy(masks = it.masks + mask) }
                        selectMask(_settings.value.masks.lastIndex)
                    }
                }
            } finally {
                aiBusy.value = false
            }
        }
    }

    fun deleteMask(index: Int) {
        if (index !in _settings.value.masks.indices) return
        set("Delete mask") { it.copy(masks = it.masks.filterIndexed { i, _ -> i != index }) }
        selectedMask.value = selectedMask.value?.let { sel -> if (sel == index) null else if (sel > index) sel - 1 else sel }
    }

    /** One-shot mask change recorded as a history step (toggles, dropdowns, strokes). */
    fun setMask(index: Int, label: String, transform: (LocalMask) -> LocalMask) =
        set(label) { s -> s.copy(masks = s.masks.mapIndexed { i, m -> if (i == index) transform(m) else m }) }

    /** Live mask change while dragging; call [commit] on release. */
    fun previewMask(index: Int, transform: (LocalMask) -> LocalMask) =
        preview { s -> s.copy(masks = s.masks.mapIndexed { i, m -> if (i == index) transform(m) else m }) }

    /** Appends a brush stroke to the first brush component of mask [index] (adding one when the mask has none). */
    fun addStroke(index: Int, stroke: MaskStroke) = setMask(index, if (stroke.erase) "Erase mask stroke" else "Paint mask stroke") { m ->
        val at = m.components.indexOfFirst { it.kind == MaskKind.BRUSH }
        if (at >= 0) {
            val c = m.components[at]
            if (c.strokes.size >= MaskComponent.MAX_STROKES) m else m.copy(components = m.components.toMutableList().also { it[at] = c.copy(strokes = c.strokes + stroke) })
        } else {
            m.copy(components = m.components + MaskComponent.brush(listOf(stroke)))
        }
    }

    private var previewSession: DevelopPreviewSession? = null

    init {
        viewModelScope.launch {
            val session = try {
                engine.openDevelopPreview(DocumentSource.ContentUri(uri))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                _previewUnavailable.value = failure.message?.takeIf(String::isNotBlank) ?: "Real previews are not available for this file"
                _previewOpening.value = false
                return@launch
            }
            previewSession = session
            _previewOpening.value = false
            // Latest wins: a quick pass for responsiveness, then a settled pass once the controls stop moving.
            kotlinx.coroutines.flow.combine(_settings, _hoverPreview, showBefore, cropEditing, guideEditing) { s, hover, before, editingCrop, guides ->
                val recipe = if (before) DevelopSettings() else hover ?: s
                when {
                    guides -> recipe.copy(crop = app.croptop.core.model.CropSettings(), upright = app.croptop.core.model.Upright.OFF)
                    editingCrop -> recipe.copy(crop = app.croptop.core.model.CropSettings())
                    else -> recipe
                }
            }.distinctUntilChanged().collectLatest { recipe ->
                try {
                    _frame.value = session.render(recipe, INTERACTIVE_PX)
                    kotlinx.coroutines.delay(SETTLE_MS)
                    _frame.value = session.render(recipe, SETTLED_PX)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (failure: Throwable) {
                    _previewUnavailable.value = failure.message?.takeIf(String::isNotBlank) ?: "The preview could not be rendered"
                }
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(splitView, _settings.map { it.crop to it.upright }.distinctUntilChanged(), cropEditing) { split, geometry, editingCrop ->
                if (split && !editingCrop) geometry else null
            }.distinctUntilChanged().collectLatest { geometry ->
                if (geometry == null) {
                    _splitBefore.value = null
                    return@collectLatest
                }
                val session = awaitSession() ?: return@collectLatest
                _splitBefore.value = try {
                    session.render(DevelopSettings(crop = geometry.first, upright = geometry.second), SETTLED_PX)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (failure: Throwable) {
                    null
                }
            }
        }
        viewModelScope.launch {
            // The red overlay of the selected mask follows the recipe a moment after the controls settle.
            kotlinx.coroutines.flow.combine(_settings, selectedMask, maskOverlayVisible, cropEditing) { s, sel, visible, editingCrop ->
                if (visible && !editingCrop && sel != null && sel in s.masks.indices) s to sel else null
            }.distinctUntilChanged().collectLatest { request ->
                if (request == null) {
                    _overlay.value = null
                    return@collectLatest
                }
                val session = awaitSession() ?: return@collectLatest
                kotlinx.coroutines.delay(OVERLAY_DELAY_MS)
                _overlay.value = try {
                    session.maskOverlay(request.first, request.second, SETTLED_PX)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (failure: Throwable) {
                    null
                }
            }
        }
        viewModelScope.launch {
            val saved = repo.settings(settingsKey).first()
            _settings.value = saved
            _history.value = listOf(DevelopStep("Import", saved))
            _settingsLoaded.value = true
            // Autosave after edits settle.
            _settings.drop(1).debounce(400).collect { repo.save(settingsKey, it) }
        }
    }

    /** The preview session once it is open, or null when real previews are unavailable. */
    private suspend fun awaitSession(): DevelopPreviewSession? {
        _previewOpening.first { !it }
        return previewSession
    }

    override fun onCleared() {
        previewSession?.close()
        previewSession = null
    }

    /** Live change while dragging; call [commit] on release. */
    fun preview(transform: (DevelopSettings) -> DevelopSettings) {
        if (dragBase == null) dragBase = _settings.value
        _settings.value = transform(_settings.value)
    }

    fun commit(label: String) {
        val base = dragBase
        dragBase = null
        if (base != null && base == _settings.value) return
        push(label, _settings.value)
    }

    /** One-shot change (dropdowns, presets, reset). */
    fun set(label: String, transform: (DevelopSettings) -> DevelopSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        push(label, next)
    }

    private fun push(label: String, s: DevelopSettings) {
        val kept = _history.value.take(_cursor.value + 1)
        _history.value = kept + DevelopStep(label, s)
        _cursor.value = _history.value.lastIndex
    }

    fun undo() = jump(_cursor.value - 1)

    fun redo() = jump(_cursor.value + 1)

    fun jump(index: Int) {
        if (index !in _history.value.indices) return
        _cursor.value = index
        _settings.value = _history.value[index].settings
    }

    fun hoverPreset(p: DevelopPreset?) {
        _hoverPreview.value = p?.applyTo(_settings.value)
    }

    fun applyPreset(p: DevelopPreset) {
        _hoverPreview.value = null
        set("Preset: ${p.name}") { p.applyTo(it) }
    }

    fun copySettings() {
        DevelopClipboard.settings = _settings.value
    }

    fun paste(sections: Set<DevelopSection>) {
        val src = DevelopClipboard.settings ?: return
        set("Paste Settings") { current -> sections.fold(current) { acc, sec -> sec.copy(from = src, to = acc) } }
    }

    /** Outcome of the last [syncTo]: how many photos were updated, or null before any sync. */
    val syncResult = MutableStateFlow<Int?>(null)

    /**
     * Applies [sections] of this photo's recipe to the original recipes of [targets] (the other photos of a selection or the
     * filmstrip). Any pending edit is flushed first so the targets get what is on screen.
     */
    fun syncTo(targets: List<String>, sections: Set<DevelopSection>) {
        val source = _settings.value
        viewModelScope.launch {
            var updated = 0
            for (target in targets.distinct().filter { it != uri }) {
                val current = repo.settings(developKey(target, null)).first()
                val next = sections.fold(current) { acc, sec -> sec.copy(from = source, to = acc) }
                if (next != current) {
                    repo.save(developKey(target, null), next)
                    updated++
                }
            }
            syncResult.value = updated
        }
    }

    fun reset() = set("Reset") { DevelopSettings() }

    companion object {
        /** Selectable overlay colours (name, ARGB without alpha dependence: the overlay's own alpha is kept). */
        val MASK_OVERLAY_COLORS = listOf("Red" to 0xFFFF3B30.toInt(), "Yellow" to 0xFFFFD60A.toInt(), "Green" to 0xFF30D158.toInt(), "Blue" to 0xFF0A84FF.toInt())
        const val INTERACTIVE_PX = 480
        const val SETTLED_PX = 1400
        const val SETTLE_MS = 160L
        const val OVERLAY_DELAY_MS = 120L
    }
}
