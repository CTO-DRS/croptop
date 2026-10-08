// The AI edits the editor offers, as one interface the editor calls and the app implements with
// real models. Each action renders a preview of the document, runs a model (or the sky detector) off the main thread, and hands the
// result to the engine as an ordinary undoable edit.
package app.croptop.core.ai

import app.croptop.core.engine.api.CoarseMask
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.DocumentPreview
import app.croptop.core.engine.api.DocumentRegionPreview
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.LayerProperties
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.PixelPatch
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.ContentAwareOptions
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class AiFeature(val label: String, val modelIds: List<String>) {
    SELECT_SUBJECT("Select Subject", listOf(SUBJECT_MODEL)),
    SELECT_BACKGROUND("Select Background", listOf(SUBJECT_MODEL)),
    SELECT_SKY("Select Sky", emptyList()),
    OBJECT_SELECT("Object Select", listOf(PROMPT_MODEL)),
    DEPTH_MAP("Depth Map", listOf(DEPTH_MODEL)),
    REMOVE("Remove", listOf(INPAINT_MODEL)),
    REMOVE_BACKGROUND("Remove Background", listOf(CUTOUT_MODEL)),
    DENOISE("Denoise", listOf(DENOISE_MODEL)),
    UPSCALE("Super Resolution", listOf(UPSCALE_MODEL)),
}

/** What a Develop mask can be made from. */
enum class AiMaskSource(val label: String, val feature: AiFeature) {
    SUBJECT("Subject", AiFeature.SELECT_SUBJECT),
    SKY("Sky", AiFeature.SELECT_SKY),
    BACKGROUND("Background", AiFeature.SELECT_BACKGROUND),
}

sealed interface SegmentOutcome {
    class Found(val mask: MaskResult) : SegmentOutcome

    /** The model ran but saw nothing of that kind, or could not run; [message] is for the person. */
    class Failed(val message: String) : SegmentOutcome
}

const val SUBJECT_MODEL = "deeplab-v3"

/** DIS IS-Net: the high-precision cut-out used by Remove Background, and by Select Subject when it is on the device. */
const val CUTOUT_MODEL = "isnet-general"
const val PROMPT_MODEL = "magic-touch"
const val DEPTH_MODEL = "midas-small"
const val INPAINT_MODEL = "lama-dilated"
const val SAM_ENCODER_MODEL = "sam2-encoder"
const val SAM_DECODER_MODEL = "sam2-decoder"
const val DENOISE_MODEL = "nafnet-sidd"
const val UPSCALE_MODEL = "realesr-general-x4v3"

interface AiEditActions {
    /** Live state of the downloadable models (for the first-use download sheet). */
    val models: kotlinx.coroutines.flow.StateFlow<ModelManagerState>

    fun install(id: String)

    fun cancelInstall(id: String)

    /** Whether [feature] can run now, or what has to be downloaded first. */
    fun availability(feature: AiFeature): AiRuntime.Availability

    suspend fun selectSubject(document: DocumentHandle, op: SelectionOp): CommandResult

    suspend fun selectBackground(document: DocumentHandle, op: SelectionOp): CommandResult

    suspend fun selectSky(document: DocumentHandle, op: SelectionOp): CommandResult

    /** Selects the object under the clicks (fractions of the picture, 0–1). */
    suspend fun selectObject(document: DocumentHandle, clicks: List<PromptSegmenter.Click>, op: SelectionOp): CommandResult

    /**
     * One-tap Remove Background: finds the subject and hides everything else with a layer mask on the primary pixel layer (the
     * pixels stay, so the mask can be edited or removed). One undo step.
     */
    suspend fun removeBackground(document: DocumentHandle): CommandResult

    /** Segments [image] (no document involved): the coarse mask of [source], for masks that live outside the editor, such as Develop's. */
    suspend fun segment(source: AiMaskSource, image: RgbaImage): SegmentOutcome

    /** Adds a grayscale "Depth map" layer (0 = nearest, 255 = farthest) that Lens Blur can use as its depth source. */
    suspend fun estimateDepth(document: DocumentHandle): CommandResult

    /** Fills the selected area from its surroundings on a new "Removed" layer (one undo step); the original pixels are untouched. */
    suspend fun removeSelection(document: DocumentHandle): CommandResult

    /** The Remove brush: fills what a stroke of [radius] document pixels through [points] covered, on a new "Removed" layer. */
    suspend fun removeStroke(document: DocumentHandle, points: List<DocPoint>, radius: Float): CommandResult

    /**
     * Denoises the picture (or the selected area) onto a new "Denoised" layer; [strength] 0–1 is how much of the result shows.
     * [progress] receives 0–1 after each block; cancelling the coroutine stops the work without changing the document.
     */
    suspend fun denoise(document: DocumentHandle, strength: Float, progress: (Float) -> Unit = {}): CommandResult

    /** Enlarges the picture [factor]× (2 or 4): the picture is resampled and a "Super Resolution" layer with the network's detail goes on top. */
    suspend fun upscale(document: DocumentHandle, factor: Int, progress: (Float) -> Unit = {}): CommandResult
}

/** Used where no AI runtime is wired (tests, previews): everything is reported as unavailable. */
object UnavailableAi : AiEditActions {
    private const val WHY = "AI features are not available in this build"

    override val models = kotlinx.coroutines.flow.MutableStateFlow(ModelManagerState(emptyList(), 0L))

    override fun install(id: String) = Unit

    override fun cancelInstall(id: String) = Unit

    override fun availability(feature: AiFeature): AiRuntime.Availability = AiRuntime.Availability.Unavailable(WHY)

    override suspend fun selectSubject(document: DocumentHandle, op: SelectionOp) = unsupported()

    override suspend fun selectBackground(document: DocumentHandle, op: SelectionOp) = unsupported()

    override suspend fun selectSky(document: DocumentHandle, op: SelectionOp) = unsupported()

    override suspend fun selectObject(document: DocumentHandle, clicks: List<PromptSegmenter.Click>, op: SelectionOp) = unsupported()

    override suspend fun estimateDepth(document: DocumentHandle) = unsupported()

    override suspend fun segment(source: AiMaskSource, image: RgbaImage): SegmentOutcome = SegmentOutcome.Failed(WHY)

    override suspend fun removeBackground(document: DocumentHandle) = unsupported()

    override suspend fun removeSelection(document: DocumentHandle) = unsupported()

    override suspend fun removeStroke(document: DocumentHandle, points: List<DocPoint>, radius: Float) = unsupported()

    override suspend fun denoise(document: DocumentHandle, strength: Float, progress: (Float) -> Unit) = unsupported()

    override suspend fun upscale(document: DocumentHandle, factor: Int, progress: (Float) -> Unit) = unsupported()

    private fun unsupported(): CommandResult = CommandResult.Rejected(RejectionCode.UNSUPPORTED, WHY)
}

/** The real thing: the models run on [runtime] and the results go to the engine. */
class ModelAiEditActions(private val runtime: ModelRunner, private val downloads: ModelDownloads) : AiEditActions {
    override val models get() = downloads.state

    override fun install(id: String) = downloads.install(id)

    override fun cancelInstall(id: String) = downloads.cancel(id)

    override fun availability(feature: AiFeature): AiRuntime.Availability =
        if (feature.modelIds.isEmpty()) AiRuntime.Availability.Ready else runtime.availability(*feature.modelIds.toTypedArray())

    override suspend fun selectSubject(document: DocumentHandle, op: SelectionOp): CommandResult = select(document, op, "Select Subject", invert = false, precise = cutoutReady()) { image -> subject(image) } ?: nothingFound("subject")

    override suspend fun selectBackground(document: DocumentHandle, op: SelectionOp): CommandResult = select(document, op, "Select Background", invert = true, precise = cutoutReady()) { image -> subject(image) } ?: nothingFound("subject")

    override suspend fun selectSky(document: DocumentHandle, op: SelectionOp): CommandResult =
        select(document, op, "Select Sky", invert = false) { SkyDetector.detect(it) } ?: nothingFound("sky")

    override suspend fun selectObject(document: DocumentHandle, clicks: List<PromptSegmenter.Click>, op: SelectionOp): CommandResult {
        // SAM 2 (optional download) handles a single click: its picture features are kept, so more clicks on the same picture are quick.
        val sam = clicks.size == 1 && runtime.availability(SAM_ENCODER_MODEL, SAM_DECODER_MODEL) is AiRuntime.Availability.Ready
        if (sam) {
            val documentId = document.info.value.id.value
            val result = select(document, op, "Object Select", invert = false) { image ->
                // The features belong to these exact pixels: selecting does not change them, painting does.
                val key = "$documentId:${image.width}x${image.height}:${java.util.zip.CRC32().apply { update(image.rgba) }.value}"
                val cached = samCache?.takeIf { it.first == key }?.second
                val embedding = cached ?: runtime.use(SAM_ENCODER_MODEL) { SamEncoder(it).embed(image) }.also { samCache = key to it }
                runtime.use(SAM_DECODER_MODEL) { SamDecoder(it).decode(embedding, clicks.single()) }
            }
            // A model that cannot run here (memory, delegate) falls back to the smaller click model below.
            if (result != null && !(result is CommandResult.Rejected && result.code == RejectionCode.UNSUPPORTED)) return result
            samCache = null
        }
        return select(document, op, "Object Select", invert = false) { image -> runtime.use(PROMPT_MODEL) { PromptSegmenter(it).segment(image, clicks) } }
            ?: nothingFound("object")
    }

    @Volatile
    private var samCache: Pair<String, SamEmbedding>? = null

    override suspend fun removeBackground(document: DocumentHandle): CommandResult {
        val layer = document.layers.value.primary?.let { document.layers.value.find(it) }
        if (layer == null || layer.kind != LayerKind.PIXEL) return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Select a photo layer first")
        if (layer.mask != null) return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "“${layer.name}” already has a mask; delete it first")
        val stamp = Stamp(document)
        val preview = document.preview(CUTOUT_PX) ?: return CommandResult.Rejected(RejectionCode.CLOSED, "The document is not available")
        val found = segment(AiMaskSource.SUBJECT, RgbaImage(preview.width, preview.height, preview.rgba))
        val subject = when (found) {
            is SegmentOutcome.Found -> found.mask
            is SegmentOutcome.Failed -> return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, found.message)
        }
        val sized = fit(subject, document)
        val precise = cutoutReady()
        if (!stamp.matches(document)) return changedMeanwhile("Remove Background")
        var failure: CommandResult? = null
        val committed = document.transaction("Remove Background") {
            val selected = selectFromMask(CoarseMask(sized.width, sized.height, sized.alpha, refine = !precise), SelectionOp.REPLACE, sampleAll = true, label = "Remove Background")
            if (selected !is CommandResult.Accepted) { failure = selected; return@transaction }
            val masked = addLayerMask(layer.id, MaskFill.REVEAL_SELECTION)
            if (masked !is CommandResult.Accepted) failure = masked
            deselect()
        }
        return failure ?: committed
    }

    override suspend fun segment(source: AiMaskSource, image: RgbaImage): SegmentOutcome {
        val mask = try {
            withContext(Dispatchers.Default) {
                when (source) {
                    AiMaskSource.SKY -> SkyDetector.detect(image)
                    AiMaskSource.SUBJECT -> subject(image)
                    AiMaskSource.BACKGROUND -> runtime.use(SUBJECT_MODEL) { SubjectSegmenter(it).segment(image) }?.inverted()
                }
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return SegmentOutcome.Failed(failure.message ?: "The model could not run")
        }
        return if (mask == null) SegmentOutcome.Failed("Couldn't find a ${source.label.lowercase()} in this picture") else SegmentOutcome.Found(mask)
    }

    private fun cutoutReady() = runtime.availability(CUTOUT_MODEL) is AiRuntime.Availability.Ready

    /**
     * What a model's answer was computed from: the history, layers, selection and size. The answer is written only if they
     * are all still the same; an edit made meanwhile (another window, an agent) would otherwise get a stale result.
     */
    private class Stamp(document: DocumentHandle) {
        private val history = document.history.value
        private val layers = document.layers.value
        private val selection = document.selection.value
        private val size = document.info.value.size

        fun matches(document: DocumentHandle) = document.history.value == history && document.layers.value == layers &&
            document.selection.value == selection && document.info.value.size == size
    }

    private fun changedMeanwhile(label: String) =
        CommandResult.Rejected(RejectionCode.CANCELLED, "The picture changed while $label was working. Nothing was applied; run it again.")

    /** The main subject: IS-Net when it is on the device (a finished outline), else DeepLab, outlined by SAM 2 when that is installed. */
    private fun subject(image: RgbaImage): MaskResult? {
        if (cutoutReady()) {
            return runtime.use(CUTOUT_MODEL) { CutoutSegmenter(it).segment(image) }
        }
        return runtime.use(SUBJECT_MODEL) { SubjectSegmenter(it).segment(image) }?.let { sharpenWithSam(image, it) }
    }

    /**
     * With SAM 2 installed, the coarse subject from DeepLab only says where the subject is: one click at its middle lets SAM
     * draw the real outline. SAM's answer is used only when it agrees with DeepLab about the subject (so a click that landed on a
     * part of it cannot shrink it), and any failure keeps DeepLab's mask.
     */
    private fun sharpenWithSam(image: RgbaImage, coarse: MaskResult): MaskResult {
        if (runtime.availability(SAM_ENCODER_MODEL, SAM_DECODER_MODEL) !is AiRuntime.Availability.Ready) return coarse
        return try {
            val click = coarse.innerPoint() ?: return coarse
            val embedding = runtime.use(SAM_ENCODER_MODEL) { SamEncoder(it).embed(image) }
            val sam = runtime.use(SAM_DECODER_MODEL) { SamDecoder(it).decode(embedding, click) }?.cleaned() ?: return coarse
            if (sam.agreement(coarse.resized(sam.width, sam.height)) >= SAM_AGREEMENT) sam else coarse
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            coarse
        }
    }

    override suspend fun estimateDepth(document: DocumentHandle): CommandResult {
        val stamp = Stamp(document)
        val preview = document.preview(PREVIEW_PX) ?: return CommandResult.Rejected(RejectionCode.CLOSED, "The document is not available")
        val depth = try {
            withContext(Dispatchers.Default) { runtime.use(DEPTH_MODEL) { DepthEstimator(it).estimate(RgbaImage(preview.width, preview.height, preview.rgba)) } }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return CommandResult.Rejected(RejectionCode.UNSUPPORTED, failure.message ?: "The model could not run")
        }
        val rgba = ByteArray(depth.alpha.size * 4)
        for (i in depth.alpha.indices) {
            rgba[i * 4] = depth.alpha[i]
            rgba[i * 4 + 1] = depth.alpha[i]
            rgba[i * 4 + 2] = depth.alpha[i]
            rgba[i * 4 + 3] = -1
        }
        if (!stamp.matches(document)) return changedMeanwhile("Estimate Depth")
        val id = LayerId(kotlin.random.Random.nextLong(1L shl 40, Long.MAX_VALUE))
        return document.addLayerFromPixels(id, "Depth map", PixelPatch(depth.width, depth.height, rgba), "Estimate Depth")
    }

    override suspend fun removeSelection(document: DocumentHandle): CommandResult {
        val bounds = (document.selection.value as? SelectionInfo.Active)?.bounds
            ?: return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Select what to remove first")
        if (holeIsTooLargeForTheNetwork(bounds)) return patchMatchRemove(document, makeSelection = null)
        return removeArea(document, bounds) { window -> document.selectionCoverage(window) }
    }

    override suspend fun removeStroke(document: DocumentHandle, points: List<DocPoint>, radius: Float): CommandResult {
        if (points.isEmpty() || !radius.isFinite() || radius <= 0f) return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Paint over what you want to remove")
        val picture = document.info.value.size
        val reach = radius + 1f
        val left = (points.minOf { it.x } - reach).toInt().coerceIn(0, picture.width - 1)
        val top = (points.minOf { it.y } - reach).toInt().coerceIn(0, picture.height - 1)
        val right = (points.maxOf { it.x } + reach).toInt().plus(1).coerceIn(left + 1, picture.width)
        val bottom = (points.maxOf { it.y } + reach).toInt().plus(1).coerceIn(top + 1, picture.height)
        val bounds = DocRect(left, top, right, bottom)
        if (holeIsTooLargeForTheNetwork(bounds)) return patchMatchRemove(document) { selectStroke(points, radius) }
        return removeArea(document, bounds) { window -> StrokeCoverage.rasterize(points, radius, window) }
    }

    /**
     * Past about two thirds of the network's window the fill would come from a shrunken view and turn into a smear, so big holes go
     * to Content-Aware (PatchMatch) fill, which copies real texture from the rest of the picture.
     */
    private fun holeIsTooLargeForTheNetwork(bounds: DocRect) = max(bounds.width, bounds.height) > LARGE_HOLE_PX

    /** Content-Aware fill of the selection (made by [makeSelection] first, and cleared after) onto a new "Removed" layer, one undo step. */
    private suspend fun patchMatchRemove(document: DocumentHandle, makeSelection: (DocumentHandle.() -> CommandResult)?): CommandResult {
        val id = LayerId(kotlin.random.Random.nextLong(1L shl 40, Long.MAX_VALUE))
        var failure: CommandResult? = null
        val committed = withContext(Dispatchers.Default) {
            document.transaction("AI Remove") {
                makeSelection?.let { make ->
                    val selected = make()
                    if (selected !is CommandResult.Accepted) { failure = selected; return@transaction }
                }
                val filled = fill(FillSource.ContentAware(ContentAwareOptions(newLayerId = id)))
                if (filled !is CommandResult.Accepted) failure = filled else execute(EditCommand.SetLayerProps(id, LayerProperties(name = "Removed")))
                if (makeSelection != null) deselect()
            }
        }
        return failure ?: committed
    }

    /** The stroke as a selection: a disc at every point and a quad between neighbours, added together. */
    private fun DocumentHandle.selectStroke(points: List<DocPoint>, radius: Float): CommandResult {
        val thinned = if (points.size <= MAX_STROKE_SHAPES) points else List(MAX_STROKE_SHAPES) { points[it * (points.size - 1) / (MAX_STROKE_SHAPES - 1)] }
        var first = true
        fun add(shape: SelectionShape): CommandResult = select(shape, if (first) SelectionOp.REPLACE else SelectionOp.ADD, antiAlias = true).also { first = false }
        for (p in thinned) {
            val ring = List(DISC_SIDES) { k ->
                val angle = 2.0 * Math.PI * k / DISC_SIDES
                DocPoint(p.x + radius * kotlin.math.cos(angle).toFloat(), p.y + radius * kotlin.math.sin(angle).toFloat())
            }
            val result = add(SelectionShape.Polygon(ring))
            if (result !is CommandResult.Accepted) return result
        }
        for ((a, b) in thinned.zipWithNext()) {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = kotlin.math.hypot(dx, dy)
            if (length < 0.5f) continue
            val nx = -dy / length * radius
            val ny = dx / length * radius
            val result = add(SelectionShape.Polygon(listOf(DocPoint(a.x + nx, a.y + ny), DocPoint(b.x + nx, b.y + ny), DocPoint(b.x - nx, b.y - ny), DocPoint(a.x - nx, a.y - ny))))
            if (result !is CommandResult.Accepted) return result
        }
        return CommandResult.Accepted(0)
    }

    /** Fills [bounds] (where [coverageOf] says, 0–255 per pixel of the window it is asked for) on a new layer. */
    private suspend fun removeArea(document: DocumentHandle, bounds: DocRect, coverageOf: (DocRect) -> ByteArray?): CommandResult {
        val stamp = Stamp(document)
        val picture = document.info.value.size
        val window = InpaintWindow.window(bounds, picture)
        val coverage = coverageOf(window)
            ?: return CommandResult.Rejected(RejectionCode.UNSUPPORTED, "The selection could not be read")
        val native = window.width <= DocumentRegionPreview.MAX_DIMENSION && window.height <= DocumentRegionPreview.MAX_DIMENSION
        val crop: RgbaImage = if (native) {
            val region = document.renderRegion(window) ?: return CommandResult.Rejected(RejectionCode.CLOSED, "The document is not available")
            RgbaImage(window.width, window.height, region.rgba)
        } else {
            // Too big for the network at full size: it sees the whole picture scaled down and the fill is stretched back up.
            val whole = document.preview(PREVIEW_PX) ?: return CommandResult.Rejected(RejectionCode.CLOSED, "The document is not available")
            cropScaled(whole, window, picture)
        }
        val size = Inpainter.SIZE
        val input = ImageOps.resizeRgb(crop, size, size, 1f / 255f, 0f)
        val fill = InpaintWindow.fillMask(InpaintWindow.resizeCoverage(coverage, window.width, window.height, size, size), size)
        val result = try {
            withContext(Dispatchers.Default) { runtime.use(INPAINT_MODEL) { Inpainter(it).inpaint(input, fill) } }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return CommandResult.Rejected(RejectionCode.UNSUPPORTED, failure.message ?: "The model could not run")
        }
        val rgba = ByteArray(size * size * 4)
        for (p in 0 until size * size) {
            for (c in 0 until 3) rgba[p * 4 + c] = (result.rgb[p * 3 + c] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            rgba[p * 4 + 3] = -1
        }
        val patch = PixelPatch(size, size, rgba, result.coverage)
        if (!stamp.matches(document)) return changedMeanwhile("Remove")
        val id = LayerId(kotlin.random.Random.nextLong(1L shl 40, Long.MAX_VALUE))
        var failure: CommandResult? = null
        val committed = document.transaction("AI Remove") {
            val added = execute(EditCommand.AddLayer(id, "Removed", LayerKind.PIXEL))
            val written = if (added is CommandResult.Accepted) applyPixelPatch(id, window, patch, "AI Remove") else added
            if (written !is CommandResult.Accepted) failure = written
        }
        return failure ?: committed
    }

    override suspend fun denoise(document: DocumentHandle, strength: Float, progress: (Float) -> Unit): CommandResult {
        if (!strength.isFinite() || strength !in 0f..1f) return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Strength is 0–100%")
        val picture = document.info.value.size
        val selected = (document.selection.value as? SelectionInfo.Active)?.bounds
        val area = selected?.let { DocRect(it.left.coerceAtLeast(0), it.top.coerceAtLeast(0), it.right.coerceAtMost(picture.width), it.bottom.coerceAtMost(picture.height)) }
            ?: DocRect(0, 0, picture.width, picture.height)
        if (area.isEmpty) return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Nothing to denoise")
        if (area.width.toLong() * area.height > RestorationLimits.DENOISE_PIXELS) {
            return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "That area is too large for AI Denoise; select a smaller area")
        }
        val blocks = BlockPlan.blocks(area.left, area.top, area.right, area.bottom, DENOISE_BLOCK)
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val begun = document.beginHistoryGroup("AI Denoise")
        if (begun !is CommandResult.Accepted) return begun
        var finished = false
        try {
            val id = LayerId(kotlin.random.Random.nextLong(1L shl 40, Long.MAX_VALUE))
            val added = document.execute(EditCommand.AddLayer(id, "Denoised", LayerKind.PIXEL))
            if (added !is CommandResult.Accepted) return added
            var ours = Stamp(document)
            for ((index, block) in blocks.withIndex()) {
                currentCoroutineContext().ensureActive()
                val region = DocRect(
                    max(0, block.x - DENOISE_MARGIN), max(0, block.y - DENOISE_MARGIN),
                    minOf(picture.width, block.right + DENOISE_MARGIN), minOf(picture.height, block.bottom + DENOISE_MARGIN),
                )
                val shot = document.renderRegion(region) ?: return CommandResult.Rejected(RejectionCode.CLOSED, "The document is not available")
                val clean = withContext(Dispatchers.Default) {
                    runtime.use(DENOISE_MODEL) { Denoiser(ChannelsFirstSession(it, inputChannelsFirst = true, outputChannelsFirst = true)).denoise(shot.rgba, region.width, region.height) { job?.isActive == false } }
                }
                val rect = DocRect(block.x, block.y, block.right, block.bottom)
                val selection = if (selected != null) document.selectionCoverage(rect) else null
                val rgba = ByteArray(block.width * block.height * 4)
                val coverage = ByteArray(block.width * block.height)
                for (y in 0 until block.height) for (x in 0 until block.width) {
                    val from = (block.y - region.top + y) * region.width + (block.x - region.left + x)
                    val to = y * block.width + x
                    for (c in 0 until 3) rgba[to * 4 + c] = Restoration.toByte(clean[from * 3 + c])
                    rgba[to * 4 + 3] = shot.rgba[from * 4 + 3]
                    val inside = if (selection != null) (selection[to].toInt() and 0xFF) / 255f else 1f
                    coverage[to] = Restoration.toByte(inside * strength)
                }
                // Each block goes straight into the new layer, so only one block is ever held in memory. An edit by anyone else since
                // this block was read stops the run, and the history group takes back what was written.
                if (!ours.matches(document)) return changedMeanwhile("AI Denoise")
                val written = document.applyPixelPatch(id, rect, PixelPatch(block.width, block.height, rgba, coverage), "Denoised")
                if (written !is CommandResult.Accepted) return written
                ours = Stamp(document)
                progress((index + 1f) / blocks.size)
            }
            finished = true
            return document.endHistoryGroup()
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return CommandResult.Rejected(RejectionCode.UNSUPPORTED, failure.message ?: "The model could not run")
        } finally {
            // Cancelled or failed half way: the layer and every block written so far disappear, as if it never started.
            if (!finished) withContext(kotlinx.coroutines.NonCancellable) { document.cancelHistoryGroup() }
        }
    }

    override suspend fun upscale(document: DocumentHandle, factor: Int, progress: (Float) -> Unit): CommandResult {
        if (factor != 2 && factor != 4) return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Choose ×2 or ×4")
        val picture = document.info.value.size
        val outWidth = picture.width.toLong() * factor
        val outHeight = picture.height.toLong() * factor
        if (outWidth * outHeight > RestorationLimits.UPSCALE_OUTPUT_PIXELS) {
            val limit = Math.sqrt(RestorationLimits.UPSCALE_OUTPUT_PIXELS.toDouble() / (factor * factor)).toInt()
            return CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "The result would be $outWidth × $outHeight px; ×$factor works up to about $limit px on the longer side of a square picture")
        }
        // The picture is read once up front (the resize below replaces what could be read later); the results are written block by block.
        val stamp = Stamp(document)
        val source = ByteArray(picture.width * picture.height * 4)
        try {
            for (chunk in BlockPlan.blocks(0, 0, picture.width, picture.height, DocumentRegionPreview.MAX_DIMENSION)) {
                currentCoroutineContext().ensureActive()
                val shot = document.renderRegion(DocRect(chunk.x, chunk.y, chunk.right, chunk.bottom))
                    ?: return CommandResult.Rejected(RejectionCode.CLOSED, "The document is not available")
                for (y in 0 until chunk.height) System.arraycopy(shot.rgba, y * chunk.width * 4, source, ((chunk.y + y) * picture.width + chunk.x) * 4, chunk.width * 4)
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return CommandResult.Rejected(RejectionCode.UNSUPPORTED, failure.message ?: "The picture could not be read")
        }
        val blocks = BlockPlan.blocks(0, 0, picture.width, picture.height, Upscaler.BLOCK)
        if (!stamp.matches(document)) return changedMeanwhile("Super Resolution")
        val begun = document.beginHistoryGroup("AI Super Resolution")
        if (begun !is CommandResult.Accepted) return begun
        var finished = false
        try {
            val resized = document.resizeImage(DocSize(picture.width * factor, picture.height * factor))
            if (resized !is CommandResult.Accepted) return resized
            val id = LayerId(kotlin.random.Random.nextLong(1L shl 40, Long.MAX_VALUE))
            val added = document.execute(EditCommand.AddLayer(id, "Super Resolution", LayerKind.PIXEL))
            if (added !is CommandResult.Accepted) return added
            var ours = Stamp(document)
            for ((index, block) in blocks.withIndex()) {
                currentCoroutineContext().ensureActive()
                val big = withContext(Dispatchers.Default) {
                    runtime.use(UPSCALE_MODEL) { Upscaler(ChannelsFirstSession(it, inputChannelsFirst = false, outputChannelsFirst = true)).upscaleBlock(source, picture.width, picture.height, block.x, block.y, block.width, block.height, factor) }
                }
                val w = block.width * factor
                val h = block.height * factor
                val alpha = Upscaler.Alpha.alphaBlock(source, picture.width, picture.height, block.x, block.y, block.width, block.height, factor)
                val rgba = ByteArray(w * h * 4)
                for (p in 0 until w * h) {
                    for (c in 0 until 3) rgba[p * 4 + c] = Restoration.toByte(big[p * 3 + c])
                    rgba[p * 4 + 3] = alpha[p]
                }
                val rect = DocRect(block.x * factor, block.y * factor, block.right * factor, block.bottom * factor)
                if (!ours.matches(document)) return changedMeanwhile("Super Resolution")
                val written = document.applyPixelPatch(id, rect, PixelPatch(w, h, rgba), "Super Resolution")
                if (written !is CommandResult.Accepted) return written
                ours = Stamp(document)
                progress((index + 1f) / blocks.size)
            }
            finished = true
            return document.endHistoryGroup()
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return CommandResult.Rejected(RejectionCode.UNSUPPORTED, failure.message ?: "The model could not run")
        } finally {
            // Cancelled or failed half way: the resize and the new layer are undone, so the picture is exactly as it was.
            if (!finished) withContext(kotlinx.coroutines.NonCancellable) { document.cancelHistoryGroup() }
        }
    }

    private fun cropScaled(whole: DocumentPreview, window: DocRect, picture: DocSize): RgbaImage {
        val sx = whole.width.toFloat() / picture.width
        val sy = whole.height.toFloat() / picture.height
        val x0 = (window.left * sx).roundToInt().coerceIn(0, whole.width - 1)
        val y0 = (window.top * sy).roundToInt().coerceIn(0, whole.height - 1)
        val w = ((window.width * sx).roundToInt()).coerceIn(1, whole.width - x0)
        val h = ((window.height * sy).roundToInt()).coerceIn(1, whole.height - y0)
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) System.arraycopy(whole.rgba, ((y0 + y) * whole.width + x0) * 4, out, y * w * 4, w * 4)
        return RgbaImage(w, h, out)
    }

    private fun nothingFound(what: String) = CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Couldn't find a $what in this picture")

    private suspend fun select(
        document: DocumentHandle,
        op: SelectionOp,
        label: String,
        invert: Boolean,
        precise: Boolean = false,
        produce: (RgbaImage) -> MaskResult?,
    ): CommandResult? {
        val stamp = Stamp(document)
        val preview = document.preview(if (precise) CUTOUT_PX else PREVIEW_PX)
            ?: return CommandResult.Rejected(RejectionCode.CLOSED, "The document is not available")
        val image = RgbaImage(preview.width, preview.height, preview.rgba)
        val mask = try {
            withContext(Dispatchers.Default) { produce(image) }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return CommandResult.Rejected(RejectionCode.UNSUPPORTED, failure.message ?: "The model could not run")
        } ?: return null
        val alpha = if (invert) ByteArray(mask.alpha.size) { (255 - (mask.alpha[it].toInt() and 0xFF)).toByte() } else mask.alpha
        // The engine needs a mask no larger than the document; small documents shrink it (nearest sample of the coarse grid).
        val sized = fit(MaskResult(mask.width, mask.height, alpha), document)
        if (!stamp.matches(document)) return changedMeanwhile(label)
        return document.selectFromMask(CoarseMask(sized.width, sized.height, sized.alpha, refine = !precise), op, sampleAll = true, label = label)
    }

    private fun fit(mask: MaskResult, document: DocumentHandle): MaskResult {
        val size = document.info.value.size
        if (mask.width <= size.width && mask.height <= size.height) return mask
        val scale = minOf(size.width.toFloat() / mask.width, size.height.toFloat() / mask.height)
        return mask.resizedBilinear(max(1, (mask.width * scale).roundToInt()), max(1, (mask.height * scale).roundToInt()))
    }

    private companion object {
        /** Long edge of the preview the models see. */
        const val PREVIEW_PX = 512

        /** IS-Net sees 1024 × 1024, so its picture is the 1024 px preview. */
        const val CUTOUT_PX = 1024
        const val SAM_AGREEMENT = 0.6f
        const val LARGE_HOLE_PX = 340
        const val MAX_STROKE_SHAPES = 48
        const val DISC_SIDES = 20
        const val DENOISE_BLOCK = 480
        const val DENOISE_MARGIN = 16
    }
}
