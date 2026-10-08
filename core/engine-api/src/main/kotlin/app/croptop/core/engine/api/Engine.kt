package app.croptop.core.engine.api

import android.view.Surface
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.Anchor
import app.croptop.core.model.BlendMode
import app.croptop.core.model.CanvasFill
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
import app.croptop.core.model.HistoryState
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerTree
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ParamValue
import app.croptop.core.model.Resample
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionModify
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.ViewportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Bump on any source or binary incompatible change. */
const val ENGINE_API_VERSION: Int = 57
const val DEFAULT_ENGINE_MEMORY_BUDGET_BYTES: Long = 512L * 1024 * 1024

/** Thread-safe engine entry point. Documents own their command queue and native lifetime. */
interface Engine : AutoCloseable {
    val capabilities: EngineCapabilities

    /** Current native cache/tile budget in bytes. */
    val memoryBudgetBytes: Long

    /** Changes the process-wide pixel/tile cache budget; evicted content is recoverable from scratch. */
    fun setMemoryBudget(bytes: Long)

    /** The adjustments this engine renders; the Properties panel is generated from them. */
    fun adjustmentSpecs(): List<app.croptop.core.model.AdjustmentSpec> = app.croptop.core.model.AdjustmentCatalog.All

    /**
     * The Filter menu this engine runs ([app.croptop.core.model.FilterCatalog] ids). Each one applies like an
     * Image ▸ Adjustments entry: `beginAdjustmentPreview(AdjustmentInfo(filterId, values))`, then commit.
     */
    fun filterSpecs(): List<app.croptop.core.model.AdjustmentSpec> = emptyList()

    /** File parsing may perform I/O; callers must invoke it from a background coroutine. */
    suspend fun open(source: DocumentSource, options: OpenOptions = OpenOptions()): DocumentHandle

    /** Writes the pipeline-supported Develop recipe beside an app-private RAW or a RAW selected from a SAF tree. */
    suspend fun exportRawDevelopSidecar(source: DocumentSource, settings: app.croptop.core.model.DevelopSettings): Long =
        throw UnsupportedOperationException("RAW Develop sidecar export is not supported by this engine")

    /** Creates the small initial document state synchronously; pixel allocation is deferred. */
    fun newDocument(spec: NewDocumentSpec): DocumentHandle

    /**
     * Opens the camera RAW [source] for Develop: it is decoded and demosaiced once, then [DevelopPreviewSession.render] returns
     * bounded previews of the full recipe (tone, color, crop, local masks) at slider speed. Throws when [source] is not a RAW the
     * engine can develop, so the caller can fall back to an approximate preview.
     */
    suspend fun openDevelopPreview(source: DocumentSource): DevelopPreviewSession =
        throw UnsupportedOperationException("Develop previews are not supported by this engine")

    /** The pattern library (persists across sessions). */
    val patterns: StateFlow<List<PatternInfo>> get() = NoPatterns

    /** Removes a pattern from the library; documents that embed it keep their copy. */
    fun deletePattern(id: String): Boolean = false

    /** A preview of pattern [id] at most [maxPx] on its longer side, or null. */
    fun patternThumbnail(id: String, maxPx: Int): LayerThumb? = null

    /** Pixels from the last Copy/Cut in any document; null when empty. */
    val clipboard: StateFlow<ClipboardInfo?> get() = EmptyClipboard

    /** Documents an unexpected exit left unsaved, newest first. Reads disk: call off the main thread. */
    fun recoverableDocuments(): List<RecoverableDocument> = emptyList()

    /**
     * Reopens [id]'s last autosaved state as a modified document. Its recovery data stays until the
     * document is saved or closed, so a second crash loses nothing.
     */
    suspend fun recover(id: String): DocumentHandle = throw UnsupportedOperationException("Crash recovery is not supported by this engine")

    /** Deletes [id]'s recovery data; false if it could not be removed. */
    fun discardRecovery(id: String): Boolean = false

    override fun close()
}

/** Snapshot-driven document API. Pixels are accessed only through renderer, sample and thumbnails. */
interface DocumentHandle : AutoCloseable {
    val id: DocumentId
    val info: StateFlow<DocumentInfo>

    /** Non-fatal issues encountered while opening, such as an unsupported embedded color profile. */
    val openWarnings: List<String> get() = emptyList()
    val layers: StateFlow<LayerTree>
    val history: StateFlow<HistoryState>
    val selection: StateFlow<SelectionInfo>

    /** Accepted work lifecycle. Rendered means a composed buffer is ready, not that the display scanned it out. */
    val events: Flow<EngineEvent> get() = emptyFlow()

    /** Waits for the latest asynchronous viewport composite, when the engine provides one. */
    suspend fun awaitViewportPreview() = Unit

    /** Enqueues a mutation and returns without waiting for rendering or disk work. */
    fun execute(command: EditCommand): CommandResult

    /** Runs several document mutations as one labeled undo step. */
    fun transaction(label: String, block: DocumentHandle.() -> Unit): CommandResult =
        CommandResult.Rejected(RejectionCode.UNSUPPORTED, "Transactions are not supported by this engine")

    /**
     * Groups every edit from now until [endHistoryGroup] into one undo step labeled [label]. Unlike [transaction] no lock is held in
     * between, so long work (AI passes that write block by block) can run between the calls; the caller must not start other edits
     * meanwhile, and ends the group with [endHistoryGroup] or throws it away with [cancelHistoryGroup].
     */
    fun beginHistoryGroup(label: String): CommandResult = unsupported("History groups")

    /** Merges the edits since [beginHistoryGroup] into one undo step (nothing is added when there were none). */
    fun endHistoryGroup(): CommandResult = unsupported("History groups")

    /** Undoes every edit since [beginHistoryGroup] and forgets them, leaving the document and its history as they were. */
    fun cancelHistoryGroup(): CommandResult = unsupported("History groups")

    /** Starts a stroke transaction; all samples are sent in batches to avoid per-point JNI calls. */
    fun beginStroke(spec: StrokeSpec): StrokeSession

    /**
     * Clone, heal, dodge/burn, sponge, blur/sharpen or smudge along a stroke. [spec] supplies the target
     * layer and brush tip; its color is ignored. One history step labeled [RetouchEffect.label].
     */
    fun beginRetouch(spec: StrokeSpec, effect: RetouchEffect): StrokeSession = UnsupportedStrokeSession(effect.label)

    fun undo(): CommandResult
    fun redo(): CommandResult
    fun jumpTo(historyIndex: Int): CommandResult

    /** Saves the current state as a named, non-destructive history snapshot. */
    fun createSnapshot(name: String): CommandResult =
        CommandResult.Rejected(RejectionCode.UNSUPPORTED, "Named snapshots are not supported by this engine")

    /** Renames the named snapshot at [index] in [history].snapshots. */
    fun renameSnapshot(index: Int, name: String): CommandResult =
        CommandResult.Rejected(RejectionCode.UNSUPPORTED, "Named snapshots are not supported by this engine")

    /** Deletes the named snapshot at [index] in [history].snapshots. */
    fun deleteSnapshot(index: Int): CommandResult =
        CommandResult.Rejected(RejectionCode.UNSUPPORTED, "Named snapshots are not supported by this engine")

    /** Restores the named snapshot at [index] as a new undoable history step. */
    fun jumpToSnapshot(index: Int): CommandResult =
        CommandResult.Rejected(RejectionCode.UNSUPPORTED, "Named snapshots are not supported by this engine")

    // ---- engine-api v3 (/007/010/012): typed calls; each commit is one history step. ----
    // Defaults reject with UNSUPPORTED so an engine can adopt them one by one.

    /** Sets the targeted layers ([LayerTree.active]) and the one that receives painting. Not a history step. */
    fun setActiveLayers(ids: Set<LayerId>, primary: LayerId?): CommandResult = unsupported("Selecting layers")

    /** Moves layer content by a pixel offset (Move tool nudges). One history step per call. */
    fun translateLayers(ids: Set<LayerId>, dx: Float, dy: Float): CommandResult = unsupported("Moving layers")

    /** Top-most visible, unlocked layer with content under [point] (Move tool auto-select); null if none. */
    fun layerAt(point: DocPoint): LayerId? = null

    /** Starts an interactive transform; [TransformSession.update] previews, commit is one history step. */
    fun beginTransform(target: TransformTarget): TransformSession = UnsupportedTransformSession("Transforming")

    fun select(shape: SelectionShape, op: SelectionOp = SelectionOp.REPLACE, feather: Float = 0f, antiAlias: Boolean = true): CommandResult =
        unsupported("Making selections")

    fun selectAll(): CommandResult = unsupported("Making selections")

    fun deselect(): CommandResult = unsupported("Making selections")

    fun reselect(): CommandResult = unsupported("Making selections")

    fun invertSelection(): CommandResult = unsupported("Making selections")

    fun modifySelection(kind: SelectionModify, px: Float): CommandResult = unsupported("Making selections")

    /**
     * Starts an interactive selection modification session (Select ▸ Modify ▸ Expand/Contract/Border/Smooth/Feather).
     * [SelectionModifySession.update] previews live on [selection] without adding history;
     * [commit] applies the change as one undoable history step;
     * [cancel] restores the selection from before the session began.
     */
    fun beginModifySelection(kind: SelectionModify): SelectionModifySession = UnsupportedSelectionModifySession(kind.label)

    /** Saved selections (Select ▸ Save Selection / Channels panel), in creation order. */
    val selectionChannels: StateFlow<List<SelectionChannel>> get() = EmptySelectionChannels

    /** The active soft proof (null = off). A view setting: it is not part of history, and exports ignore it. */
    val softProof: StateFlow<app.croptop.core.model.SoftProofSettings?> get() = NoSoftProof

    /** Turns the soft proof / gamut warning on, changes it, or (null) off. */
    fun setSoftProof(settings: app.croptop.core.model.SoftProofSettings?): CommandResult = unsupported("Soft proof")

    /**
     * Select ▸ Save Selection: stores the active selection as channel [name] (blank = the next "Alpha N"),
     * replacing a channel with the same name. Channels are document state outside undo history.
     */
    fun saveSelection(name: String = ""): CommandResult = unsupported("Saving selections")

    /** Select ▸ Load Selection: combines channel [name] with the current selection by [op]. One step. */
    fun loadSelection(name: String, op: SelectionOp = SelectionOp.REPLACE, invert: Boolean = false): CommandResult =
        unsupported("Loading selections")

    /** Red Eye tool: fixes red pupils inside [region] of the primary layer (pupil size and darken 0–1). One step. */
    fun redEye(region: app.croptop.core.model.DocRectF, pupilSize: Float = 0.5f, darken: Float = 0.5f): CommandResult = unsupported("Red Eye")

    /**
     * Patch tool (Source mode): fills the active selection on the primary layer from the same-shaped area
     * [sourceOffset] away, blended to fit; [contentAware] synthesizes instead. One step.
     */
    fun patch(sourceOffset: app.croptop.core.model.DocPoint, contentAware: Boolean = false): CommandResult = unsupported("Patch")

    /** Applies one bounded Liquify pointer gesture to [target], respecting the active selection; commits one history step. */
    fun liquify(target: LayerId, stroke: LiquifyStroke): CommandResult = unsupported("Liquify")

    /** Adds the shared smart-filter mask to smart object [id] (Reveal/Hide All or from the selection); one history step. */
    fun addSmartFilterMask(id: LayerId, fill: MaskFill = MaskFill.REVEAL_ALL): CommandResult = unsupported("Smart filter masks")

    fun setSmartFilterMaskEnabled(id: LayerId, enabled: Boolean): CommandResult = unsupported("Smart filter masks")

    fun deleteSmartFilterMask(id: LayerId): CommandResult = unsupported("Smart filter masks")

    fun invertSmartFilterMask(id: LayerId): CommandResult = unsupported("Smart filter masks")

    /** Paints [points] with a round brush of [radius] document px into the filter mask: [reveal] shows the filters, otherwise hides them. */
    fun paintSmartFilterMask(id: LayerId, points: List<app.croptop.core.model.DocPoint>, radius: Float, reveal: Boolean): CommandResult =
        unsupported("Smart filter masks")

    /**
     * Starts an interactive Liquify workspace on pixel layer [target]: strokes, freeze/thaw and reconstruct update a live preview
     * without history; [LiquifySession.commit] records one step and [LiquifySession.cancel] restores the layer.
     */
    fun beginLiquify(target: LayerId, meshSpacing: Int = 8): LiquifySession = UnsupportedLiquifySession()

    /** Edit ▸ Transform ▸ Warp: re-renders pixel layer [id] through [mesh] (a lattice over the canvas); one history step. */
    fun warpLayer(id: LayerId, mesh: app.croptop.core.model.WarpMesh): CommandResult = unsupported("Warp")

    /** Creates text layer [id] above the primary layer (Text tool commit). One history step. */
    fun createTextLayer(id: LayerId, text: app.croptop.core.model.TextLayerInfo): CommandResult = unsupported("Text layers")

    /** Replaces a text layer's content and styles (re-rendered). One history step. */
    fun setTextLayer(id: LayerId, text: app.croptop.core.model.TextLayerInfo): CommandResult = unsupported("Text layers")

    /**
     * Layer ▸ Layer Style: replaces [id]'s effects (null clears them). Pixel and type layers; one history step.
     * Effects render live in every composite, export and merge.
     */
    fun setLayerStyles(id: LayerId, styles: app.croptop.core.model.LayerStyles?): CommandResult = unsupported("Layer styles")

    /**
     * Shows [blendIf] on [id] in the viewport without a history step (the Blending Options dialog's live preview); null restores
     * the committed value. Any committed edit, undo or redo also ends the preview.
     */
    fun previewBlendIf(id: LayerId, blendIf: app.croptop.core.model.BlendIfSettings?): CommandResult = unsupported("Blend If preview")

    /** Shows fill opacity [fillOpacity] (0..1) on [id] without a history step; null restores the committed value. */
    fun previewFillOpacity(id: LayerId, fillOpacity: Float?): CommandResult = unsupported("Fill opacity preview")

    /**
     * Shows [styles] on [id] in the viewport without a history step (the Layer Style dialog's live preview).
     * Finish with [setLayerStyles] to keep them, or call again with the layer's own styles to cancel.
     */
    fun previewLayerStyles(id: LayerId, styles: app.croptop.core.model.LayerStyles?): CommandResult = unsupported("Layer styles")

    /**
     * Layer ▸ Smart Objects ▸ Convert to Smart Object: embeds [id]'s current pixels as an immutable source.
     * Later transforms re-render from that source in one resample (no quality loss). Pixel and type layers; one history step.
     */
    fun convertToSmartObject(id: LayerId): CommandResult = unsupported("Smart objects")

    /**
     * Replaces smart object [id]'s smart filter stack ([app.croptop.core.model.FilterCatalog] ids, applied in order)
     * and re-renders it from its source. Applying a filter to a smart object (via `beginAdjustmentPreview`) appends one.
     */
    fun setSmartFilters(id: LayerId, filters: List<AdjustmentInfo>): CommandResult = unsupported("Smart filters")

    /**
     * Layer ▸ Smart Objects ▸ Edit Contents: opens smart object [id]'s embedded source as a new document of the same
     * engine (one pixel layer). Edit it, then hand it back with [replaceSmartObjectContents]; closing it is the caller's job.
     */
    fun openSmartObjectContents(id: LayerId): DocumentHandle = throw UnsupportedOperationException("Editing smart object contents isn't available")

    /**
     * Replaces smart object [id]'s source with the flattened visible pixels of [contents] (from [openSmartObjectContents]),
     * keeping its transform and smart filters. One history step.
     */
    fun replaceSmartObjectContents(id: LayerId, contents: DocumentHandle): CommandResult = unsupported("Editing smart object contents")

    /** Layer ▸ Smart Objects ▸ Rasterize: keeps the rendered pixels as an ordinary pixel layer. One history step. */
    fun rasterizeSmartObject(id: LayerId): CommandResult = unsupported("Smart objects")

    /**
     * Re-develops a RAW smart object from its original file with [settings] (one history step; the transform and smart filters
     * are kept). Heavy: call off the main thread. Rejects when the recipe changes the image size.
     */
    fun setRawDevelopSettings(id: LayerId, settings: app.croptop.core.model.DevelopSettings): CommandResult = unsupported("RAW smart objects")

    /**
     * Edit ▸ Define Pattern: adds the visible pixels inside the selection's bounds (the whole canvas without a
     * selection) to the pattern library as [name]. Not a history step.
     */
    fun definePattern(name: String): CommandResult = unsupported("Patterns")

    /** Guides and grid geometry saved with the document. */
    val documentGuides: StateFlow<DocumentGuides> get() = NoDocumentGuides

    /** Replaces the document's guides/grid geometry. Not a history step; marks the document modified. */
    fun setDocumentGuides(value: DocumentGuides): CommandResult = unsupported("Saving guides")

    /** True while Quick Mask mode is on. */
    val quickMask: StateFlow<Boolean> get() = QuickMaskOff

    /** Quick Mask rubylith color for this document; default is Photoshop-style half-red. Not saved in document history. */
    val quickMaskOverlayColor: StateFlow<RgbaColor> get() = DefaultQuickMaskOverlayColor

    /** Changes the document's Quick Mask display color. All finite RGBA channels must be in 0..1. */
    fun setQuickMaskOverlayColor(color: RgbaColor): CommandResult = unsupported("Quick Mask overlay color")

    /**
     * Select ▸ Edit in Quick Mask Mode (Q). While on, [beginMaskStroke] paints into the selection itself
     * (white selects, black deselects, one step per stroke) whatever its target layer; turning it off keeps
     * the painted selection. Not a history step.
     */
    fun setQuickMask(enabled: Boolean): CommandResult = unsupported("Quick Mask")

    /** The layer whose mask the viewport shows alone, or null. */
    val maskView: StateFlow<LayerId?> get() = NoMaskView

    /** Alt-click a mask thumbnail: shows [id]'s mask alone as grayscale (null = normal view). Not a history step. */
    fun setMaskView(id: LayerId?): CommandResult = unsupported("Viewing masks")

    /** Magnetic Lasso starting at [start] on the primary pixel layer. See [MagneticLassoSession]. */
    fun beginMagneticLasso(start: app.croptop.core.model.DocPoint, spec: MagneticLassoSpec = MagneticLassoSpec()): MagneticLassoSession =
        UnsupportedMagneticLassoSession()

    /** Select ▸ Select and Mask; needs an active selection. See [RefineEdgeSession]. */
    fun beginRefineEdge(): RefineEdgeSession = UnsupportedRefineEdgeSession()

    /** Deletes channel [name] (outside undo history, like saving). */
    fun deleteSelectionChannel(name: String): CommandResult = unsupported("Selection channels")

    /** Crops the canvas to [rect], rotated by [angleDegrees] about its center; pixels outside are deleted or kept. */
    fun crop(rect: DocRectF, angleDegrees: Float = 0f, deleteCropped: Boolean = true): CommandResult = unsupported("Cropping")

    /** Destructively maps the source quadrilateral to its perspective-rectified output bounds. */
    fun cropPerspective(quad: CropQuad, deleteCropped: Boolean = true): CommandResult = unsupported("Perspective cropping")

    fun resizeImage(size: DocSize, resample: Resample = Resample.AUTOMATIC, resolutionPpi: Float? = null): CommandResult = unsupported("Image Size")

    fun resizeCanvas(size: DocSize, anchor: Anchor = Anchor.CENTER, fill: CanvasFill = CanvasFill.Transparent): CommandResult = unsupported(
        "Canvas Size",
    )

    /** Rotates the whole canvas; multiples of 90° are lossless. */
    fun rotateCanvas(degrees: Float, fill: CanvasFill = CanvasFill.Transparent): CommandResult = unsupported("Rotating the canvas")

    fun flipCanvas(horizontal: Boolean): CommandResult = unsupported("Flipping the canvas")

    // ---- Layer masks and clipping (engine-api v4) ----

    /** Adds a pixel mask to [id]; the selection variants need a selection. One "Add Layer Mask" step. */
    fun addLayerMask(id: LayerId, fill: MaskFill = MaskFill.REVEAL_ALL): CommandResult = unsupported("Layer masks")

    /** Removes [id]'s mask, first multiplying it into the pixels when [apply] (pixel layers only). One step. */
    fun removeLayerMask(id: LayerId, apply: Boolean): CommandResult = unsupported("Layer masks")

    /**
     * Mask enabled/linked/density/feather. With [preview], the change shows without a history step (slider
     * drags); the final call without it adds one step.
     */
    fun setLayerMask(id: LayerId, mask: app.croptop.core.model.LayerMaskInfo, preview: Boolean = false): CommandResult = unsupported("Layer masks")

    /** Applies depth-aware Lens Blur to an editable pixel layer as one undoable operation. */
    fun applyLensBlur(
        id: LayerId,
        spec: LensBlurSpec = LensBlurSpec(),
        depth: LensBlurDepthSource? = null,
    ): CommandResult = unsupported("Lens Blur")

    /** Starts Lens Blur off the calling thread when supported; cancellation is linearized before its undoable commit. */
    fun applyLensBlurJob(
        id: LayerId,
        spec: LensBlurSpec = LensBlurSpec(),
        depth: LensBlurDepthSource? = null,
    ): EngineJob = EngineJob.completed(applyLensBlur(id, spec, depth))

    /** Builds a depth-range mask from a depth layer or its mask and attaches it to an editable pixel layer. */
    fun applyDepthRangeMask(id: LayerId, depth: LensBlurDepthSource, range: DepthRangeSpec): CommandResult =
        unsupported("Depth-range masks")

    /**
     * Paints into [StrokeSpec.target]'s layer mask: the color's luminance is the mask value (white
     * reveals, black hides); [StrokeSpec.erase] paints black. One "Brush Tool"/"Eraser" step.
     */
    fun beginMaskStroke(spec: StrokeSpec): StrokeSession = UnsupportedStrokeSession("Painting on masks")

    /** Emits rendered mask thumbnails for the requested layers, separately from pixel thumbnails. */
    fun maskThumbnails(layerIds: List<LayerId>, px: Int): Flow<LayerThumb> = kotlinx.coroutines.flow.emptyFlow()

    /** Inverts [id]'s mask. One "Invert" step. */
    fun invertLayerMask(id: LayerId): CommandResult = unsupported("Layer masks")

    /** Ctrl-click on a mask: its luminance becomes the selection, combined by [op]. One "Load Selection" step. */
    fun maskToSelection(id: LayerId, op: SelectionOp = SelectionOp.REPLACE): CommandResult = unsupported("Layer masks")

    /** Clips [id] to the layer below (Ctrl+Alt+G), or releases it. One step. */
    fun setClipped(id: LayerId, clipped: Boolean): CommandResult = unsupported("Clipping masks")

    /** Creates an editable vector shape layer. Geometry is document truth; rendered pixels are a rebuildable cache. */
    fun createShapeLayer(id: LayerId, name: String, paths: List<VectorPathDraft>): CommandResult = unsupported("Vector shape layers")

    /** Replaces the editable paths on a shape layer as one history step. */
    fun setShapePaths(id: LayerId, paths: List<VectorPathDraft>): CommandResult = unsupported("Vector shape layers")

    /** Combines two closed paths on a shape layer and replaces them with one editable result in a single history step. */
    fun combineShapePaths(
        layerId: LayerId,
        subjectPath: app.croptop.core.model.VectorPathId,
        clipPath: app.croptop.core.model.VectorPathId,
        operation: ShapeBooleanOperation,
    ): CommandResult = unsupported("Vector path booleans")

    /** Merges [id] into the layer below it. */
    fun mergeDown(id: LayerId): CommandResult = unsupported("Merge Down")

    /** Adds [newId] above the active layer with a merged copy of everything visible. */
    fun stampVisible(newId: LayerId): CommandResult = unsupported("Stamp Visible")

    /** Fills the selection (or the whole layer) of the primary layer. */
    fun fill(source: FillSource, opacity: Float = 1f, blendMode: BlendMode = BlendMode.NORMAL, preserveTransparency: Boolean = false): CommandResult =
        unsupported("Fill")

    /**
     * Starts a cancellable fill operation. Implementations report real progress through [EngineJob.progress],
     * or null when progress cannot be estimated. [EngineJob.await] is the cancellation acknowledgment
     * barrier: it returns [RejectionCode.CANCELLED] after cleanup when cancellation wins before commit.
     */
    fun fillJob(source: FillSource, opacity: Float = 1f, blendMode: BlendMode = BlendMode.NORMAL, preserveTransparency: Boolean = false): EngineJob =
        EngineJob.completed(fill(source, opacity, blendMode, preserveTransparency))

    /** Selects pixels matching the sampled point. One undoable selection step. Tolerance is 0–255. */
    fun magicWand(point: DocPoint, tolerance: Int, contiguous: Boolean, sampleAll: Boolean, op: SelectionOp): CommandResult =
        unsupported("Magic Wand")

    /** Select ▸ Color Range with a live preview; see [ColorRangeSession]. */
    fun beginColorRange(op: SelectionOp = SelectionOp.REPLACE): ColorRangeSession = UnsupportedColorRangeSession()

    /** Quick Selection brush stroke; see [QuickSelectionSession]. */
    fun beginQuickSelection(spec: QuickSelectionSpec): QuickSelectionSession = UnsupportedQuickSelectionSession("Quick Selection")

    /** Object Selection: selects the most prominent object inside [region] (a rectangle or lasso). One undo step. */
    fun selectObject(region: SelectionShape, op: SelectionOp = SelectionOp.REPLACE, sampleAll: Boolean = false): EngineJob =
        EngineJob.completed(unsupported("Object Selection"))

    /** Select ▸ Subject: selects the main subject of the image. One undo step. */
    fun selectSubject(op: SelectionOp = SelectionOp.REPLACE, sampleAll: Boolean = false): EngineJob =
        EngineJob.completed(unsupported("Select Subject"))

    /**
     * Selects from a model's coarse mask: [mask] is upsampled to the image's resolution with its edges following the picture
     * (guided filter) and combined with the current selection by [op]. [sampleAll] guides on the visible composite instead of
     * the primary layer. One undo step labeled [label].
     */
    fun selectFromMask(mask: CoarseMask, op: SelectionOp = SelectionOp.REPLACE, sampleAll: Boolean = true, label: String = "Select by AI"): CommandResult =
        unsupported("Selecting from a model mask")

    /**
     * Adds a pixel layer above the primary one filled with [patch] scaled to the canvas (depth maps, AI output). One undo step
     * labeled [label].
     */
    fun addLayerFromPixels(id: LayerId, name: String, patch: PixelPatch, label: String = "Add layer"): CommandResult =
        unsupported("Adding a layer from pixels")

    /**
     * Writes [patch] over [region] (document pixels) of pixel layer [target], scaled to it; the patch's coverage decides how much
     * of each new pixel replaces the old (AI Remove, denoise). One undo step labeled [label].
     */
    fun applyPixelPatch(target: LayerId, region: DocRect, patch: PixelPatch, label: String): CommandResult =
        unsupported("Writing pixels into a layer")

    /** Flood-fills the primary pixel layer, constrained by the current selection. One undo step. */
    fun paintBucket(point: DocPoint, color: RgbaColor, tolerance: Int, contiguous: Boolean, sampleAll: Boolean, opacity: Float): CommandResult =
        unsupported("Paint Bucket")

    /** Draws a gradient on the primary pixel layer, constrained by the current selection. One undo step. */
    fun drawGradient(
        start: DocPoint,
        end: DocPoint,
        gradient: ColorGradient,
        shape: GradientShape,
        reverse: Boolean,
        dither: Boolean,
        opacity: Float,
    ): CommandResult = unsupported("Gradient")

    /**
     * Edit ▸ Copy: the selected pixels of the primary layer (the whole layer without a selection) go to
     * [Engine.clipboard]. With [merged], everything visible is copied. Not a history step.
     */
    fun copy(merged: Boolean = false): CommandResult = unsupported(if (merged) "Copy Merged" else "Copy")

    /** Edit ▸ Cut: Copy, then clear the selected pixels. One history step. */
    fun cut(): CommandResult = unsupported("Cut")

    /**
     * Edit ▸ Paste: the clipboard as a new pixel layer [newId] above the primary layer, centered on [at]
     * (the view center) or, when null, at its original position (Paste in Place). One history step.
     */
    fun paste(newId: LayerId, at: app.croptop.core.model.DocPoint? = null): CommandResult = unsupported("Paste")

    /** Delete with a selection: erases the selected pixels of the primary layer. */
    fun clearSelection(): CommandResult = unsupported("Clearing pixels")

    /** Ctrl+J: the selected pixels (or, with no selection, the whole primary layer) as a new layer [newId] above it. */
    fun layerViaCopy(newId: LayerId): CommandResult = unsupported("Layer via Copy")

    /** Ctrl+Shift+J: like [layerViaCopy] but removes the pixels from the source layer. */
    fun layerViaCut(newId: LayerId): CommandResult = unsupported("Layer via Cut")

    /** Merges all visible layers into one. */
    fun mergeVisible(): CommandResult = unsupported("Merge Visible")

    /** Moves a group's children to its place and removes the group. */
    fun ungroup(id: LayerId): CommandResult = unsupported("Ungroup")

    /** Adds an adjustment layer [id] above the primary layer. */
    fun addAdjustmentLayer(id: LayerId, adjustment: AdjustmentInfo): CommandResult = unsupported("Adjustment layers")

    /** Layer ▸ New Fill Layer: adds fill layer [id] above the primary layer (one history step). */
    fun addFillLayer(id: LayerId, fill: app.croptop.core.model.FillLayerInfo): CommandResult = unsupported("Fill layers")

    /** Replaces fill layer [id]'s settings (one history step). */
    fun setFillLayer(id: LayerId, fill: app.croptop.core.model.FillLayerInfo): CommandResult = unsupported("Fill layers")

    /**
     * Changes an adjustment layer's values. With [preview] = true it only updates what's shown (slider
     * drags, no history); the final call with preview = false records one history step.
     */
    fun setAdjustment(id: LayerId, values: Map<String, ParamValue>, preview: Boolean = false): CommandResult = unsupported("Adjustment layers")

    /** Image ▸ Adjustments: applies an adjustment destructively to the primary layer (one step). */
    fun applyAdjustment(adjustment: AdjustmentInfo): CommandResult = unsupported("Adjustments")

    /** True only when both preview and destructive commit are implemented for this adjustment. */
    fun supportsDestructiveAdjustment(specId: String): Boolean = false

    /** Starts a destructive-adjustment preview that does not enter history until [AdjustmentPreviewSession.commit]. */
    fun beginAdjustmentPreview(adjustment: AdjustmentInfo): AdjustmentPreviewSession =
        throw UnsupportedOperationException("Adjustment previews are not supported")

    /** Eyedropper with an averaging box of [size]×[size] px (1, 3, 5, 11, 31, 51, 101). */
    fun sample(point: DocPoint, mode: SampleMode, size: Int): ColorSample = sample(point, mode)

    /** Samples the visible composite strictly below [layerId], without applying that layer's effect. */
    fun sampleBelow(layerId: LayerId, point: DocPoint, size: Int = 1): ColorSample =
        throw UnsupportedOperationException("Sampling below a layer is not supported")

    /** Converts one document-encoded sample to Lab D50 and the engine's generic CMYK readout. */
    fun colorReadout(color: RgbaColor): ColorReadout = throw UnsupportedOperationException("Color readouts are not supported")

    /** Assigns a working profile or converts RGB pixel layers into it as one undoable document edit. */
    fun applyColorProfile(profile: ColorProfileRef, iccProfile: ByteArray? = null, convertPixels: Boolean = false): CommandResult =
        unsupported("Changing the document color profile")

    /** Runs profile conversion away from the caller thread and acknowledges cancellation before commit. */
    fun applyColorProfileJob(profile: ColorProfileRef, iccProfile: ByteArray? = null, convertPixels: Boolean = false): EngineJob =
        EngineJob.completed(applyColorProfile(profile, iccProfile, convertPixels))

    /**
     * UI-only best-effort version of [sampleBelow]. Implementations must return immediately and
     * must not delegate to a potentially blocking sampling path. The default means unavailable.
     */
    fun trySampleBelow(layerId: LayerId, point: DocPoint, size: Int = 1): ColorSample? = null

    /**
     * True once [attachViewport] draws this document's pixels. The full-flavor host then gives the
     * document its own passive Surface (no diagnostic renderer on it); until then it keeps the old path.
     */
    val rendersViewport: Boolean get() = false

    fun attachViewport(surface: Surface, viewport: ViewportState): Viewport
    suspend fun save(target: SaveTarget, options: SaveOptions = SaveOptions())
    suspend fun export(target: ExportTarget, options: ExportOptions = ExportOptions()): ExportResult
    fun sample(point: DocPoint, mode: SampleMode): ColorSample

    /**
     * UI-only best-effort sample. Implementations must return immediately and must not delegate
     * to a potentially blocking sampling path. The default means unavailable.
     */
    fun trySample(point: DocPoint, mode: SampleMode, size: Int = 1): ColorSample? = null
    fun histogram(scope: HistogramScope): Flow<Histogram>
    fun thumbnails(layerIds: List<LayerId>, px: Int): Flow<LayerThumb>

    /** Returns a one-shot merged-canvas snapshot whose longest side is at most [px], or null if unavailable. */
    suspend fun preview(px: Int): DocumentPreview? = null

    /** Native-resolution merged pixels of [region] (inside the canvas, at most [DocumentRegionPreview.MAX_DIMENSION] per side), or null. */
    suspend fun renderRegion(region: DocRect): DocumentRegionPreview? = null

    /** The selection's coverage (0–255, row by row, 0 outside it) over [region] at native resolution; null when nothing is selected. */
    fun selectionCoverage(region: DocRect): ByteArray? = null

    override fun close()
}

/** Reversible, single-document preview transaction for Image ▸ Adjustments. */
interface AdjustmentPreviewSession : AutoCloseable {
    /** Replaces the current preview values; repeated calls do not add history entries. */
    fun preview(values: Map<String, ParamValue>): CommandResult

    /** Renders a native-resolution merged crop of the current preview, if supported. */
    fun previewRegion(region: DocRect): DocumentRegionPreview? = null

    /** Commits the current preview as one undoable edit. */
    fun commit(): CommandResult

    /** Restores the pre-preview document without adding history. */
    fun cancel()

    /** Cancels an active preview; closing after commit/cancel is a no-op. */
    override fun close() = cancel()
}

/** One viewport may be attached to each Surface. detach() does not close the document. */
interface Viewport : AutoCloseable {
    fun update(state: ViewportState)
    fun setOverlays(overlays: OverlayState)
    fun detach()
    override fun close() = detach()
}

/** Stroke methods are single-session calls and must not block on GPU completion. */
interface StrokeSession {
    /** Packed records follow [StrokeSampleLayout]; length must be a multiple of [StrokeSampleLayout.STRIDE]. */
    fun add(samples: FloatArray)
    fun end(): CommandResult
    fun cancel()
}

/** Lab D50 and generic process-CMYK channel percentages for a document-encoded RGB sample. */
data class ColorReadout(
    val lab: List<Float>,
    val cmykPercent: List<Float>,
    val outOfGamut: Boolean,
) {
    init {
        require(lab.size == 3 && lab.all(Float::isFinite) && lab[0] in 0f..100f) {
            "Lab readout requires finite L*, a* and b* values"
        }
        require(cmykPercent.size == 4 && cmykPercent.all { it.isFinite() && it in 0f..100f }) {
            "CMYK readout requires four finite percentages in 0–100"
        }
    }
}

/** Reversible Liquify workspace (/): every call updates the preview only; nothing enters history until [commit]. */
interface LiquifySession : AutoCloseable {
    /** Applies one pointer gesture to the displacement mesh and refreshes the preview. */
    fun stroke(stroke: LiquifyStroke): CommandResult

    /** Paints a freeze ([freeze] true) or thaw area with a round brush along [points]; frozen pixels resist later strokes. */
    fun paintFreeze(points: List<app.croptop.core.model.DocPoint>, radius: Float, freeze: Boolean): CommandResult

    /** Removes every frozen area. */
    fun clearFreeze(): CommandResult

    /** Relaxes the whole mesh back toward the original by [amount] (0..1; 1 restores the layer). */
    fun reconstructAll(amount: Float): CommandResult

    /** Commits the preview as one undoable "Liquify" step. */
    fun commit(): CommandResult

    /** Restores the layer as it was when the session began. */
    fun cancel()

    override fun close() = cancel()
}

class UnsupportedLiquifySession : LiquifySession {
    override fun stroke(stroke: LiquifyStroke): CommandResult = unsupported("Liquify")

    override fun paintFreeze(points: List<app.croptop.core.model.DocPoint>, radius: Float, freeze: Boolean): CommandResult = unsupported("Liquify")

    override fun clearFreeze(): CommandResult = unsupported("Liquify")

    override fun reconstructAll(amount: Float): CommandResult = unsupported("Liquify")

    override fun commit(): CommandResult = unsupported("Liquify")

    override fun cancel() = Unit
}

/** One upright Develop preview: [argb] is row-major, [width] × [height] (at most the requested bound on the longer side). */
class DevelopPreview(val width: Int, val height: Int, val argb: IntArray, val warnings: List<String> = emptyList()) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "preview pixels do not match its size" }
    }
}

/** A decoded RAW held for Develop previews; close it with the screen. */
interface DevelopPreviewSession : AutoCloseable {
    /** Upright full-resolution size before any crop. */
    val width: Int
    val height: Int

    /** Notes from opening (lens profile missing, ...). */
    val warnings: List<String>

    /** Renders [settings] to at most [maxDimension] px on the longer side; runs off the main thread. */
    suspend fun render(settings: app.croptop.core.model.DevelopSettings, maxDimension: Int): DevelopPreview

    /**
     * The coverage of `settings.masks[maskIndex]` as a translucent red overlay over the frame [render] returns for the same bound
     * (so it can be drawn on top of the preview); null when the engine cannot draw one.
     */
    suspend fun maskOverlay(settings: app.croptop.core.model.DevelopSettings, maskIndex: Int, maxDimension: Int): DevelopPreview? = null
}

internal val NoSoftProof: StateFlow<app.croptop.core.model.SoftProofSettings?> =
    kotlinx.coroutines.flow.MutableStateFlow<app.croptop.core.model.SoftProofSettings?>(null)
