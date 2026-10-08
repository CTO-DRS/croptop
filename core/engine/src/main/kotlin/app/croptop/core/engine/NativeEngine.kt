package app.croptop.core.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.Choreographer
import app.croptop.core.engine.api.AdjustmentPreviewSession
import app.croptop.core.engine.api.ColorReadout
import app.croptop.core.engine.api.ColorSample
import app.croptop.core.engine.api.ClipboardInfo
import app.croptop.core.engine.api.ColorRangeSession
import app.croptop.core.engine.api.ColorRangeSpec
import app.croptop.core.engine.api.QuickSelectionSession
import app.croptop.core.engine.api.QuickSelectionSpec
import app.croptop.core.engine.api.CoarseMask
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.MagneticLassoSpec
import app.croptop.core.engine.api.MagneticLassoSession
import app.croptop.core.engine.api.RefineEdgeSettings
import app.croptop.core.engine.api.RefineEdgeSession
import app.croptop.core.engine.api.RefineEdgeOutput
import app.croptop.core.engine.api.RefineEdgeViewMode
import app.croptop.core.engine.api.RefineEdgeBrushMode
import app.croptop.core.engine.api.RefineEdgeBrushSpec
import app.croptop.core.engine.api.RefineEdgeBrushSession
import app.croptop.core.engine.api.SelectionChannel
import app.croptop.core.engine.api.SelectionModifySession
import app.croptop.core.engine.api.UnsupportedSelectionModifySession
import app.croptop.core.engine.api.DEFAULT_ENGINE_MEMORY_BUDGET_BYTES
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.DocumentPreview
import app.croptop.core.engine.api.DocumentRegionPreview
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
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.OverlayState
import app.croptop.core.engine.api.PixelPatch
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.RetouchEffect
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SaveOptions
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.engine.api.StrokeSampleLayout
import app.croptop.core.engine.api.StrokeSession
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.engine.api.TransformSession
import app.croptop.core.engine.api.TransformTarget
import app.croptop.core.engine.api.UnsupportedTransformSession
import app.croptop.core.engine.api.Viewport
import app.croptop.core.io.CroptopCanvas
import app.croptop.core.io.CroptopManifest
import app.croptop.core.io.CroptopManifestCodec
import app.croptop.core.io.CroptopManifestException
import app.croptop.core.io.CroptopSelectionChannel
import app.croptop.core.io.CroptopPixelFormat
import app.croptop.core.io.CroptopProfile
import app.croptop.core.io.ImageFormat
import app.croptop.core.io.RasterMetadata
import app.croptop.core.io.RasterMetadataReader
import app.croptop.core.model.AdjustmentCatalog
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.Anchor
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BlendMode
import app.croptop.core.model.CanvasFill
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.CanvasResize
import app.croptop.core.model.BrushSettings
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.CropQuad
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.DocumentId
import app.croptop.core.model.DocumentInfo
import app.croptop.core.model.Dynamics
import app.croptop.core.model.DynamicsInput
import app.croptop.core.model.FillSource
import app.croptop.core.model.GradientShape
import app.croptop.core.model.GuideAxis
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
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionModify
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorSourceBox
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.core.model.WorkingSpace
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private class BrushStreamFailure(message: String) : IllegalStateException(message)

internal fun imageSizeBounds(bounds: DocRect, source: DocSize, target: DocSize): DocRect? {
    val sx = target.width.toDouble() / source.width
    val sy = target.height.toDouble() / source.height
    val left = floor(bounds.left * sx).toInt().coerceAtLeast(0)
    val top = floor(bounds.top * sy).toInt().coerceAtLeast(0)
    val right = ceil(bounds.right * sx).toInt().coerceAtMost(target.width)
    val bottom = ceil(bounds.bottom * sy).toInt().coerceAtMost(target.height)
    return DocRect(left, top, right, bottom).takeUnless { it.isEmpty }
}

private fun histogramMipLevel(width: Int, height: Int): Int {
    var samples = width.toLong() * height.toLong()
    var level = 0
    while (samples > 1_000_000L && level < 16) {
        samples = (samples + 3L) / 4L
        level++
    }
    return level
}

/** Cancellation is linearized against the history commit gate. */
private class NativeFillJob(
    private val scope: CoroutineScope,
    private val onFinished: (NativeFillJob) -> Unit,
    operation: (progress: (Float) -> Boolean, isActive: () -> Boolean, beginCommit: () -> Boolean) -> CommandResult,
) : EngineJob {
    private val terminal = AtomicInteger(RUNNING)
    private val finished = CountDownLatch(1)
    private val mutableProgress = MutableStateFlow<Float?>(null)
    private val result = CompletableDeferred<CommandResult>()
    override val progress: StateFlow<Float?> = mutableProgress.asStateFlow()

    init {
        val worker = scope.launch(Dispatchers.Default) {
            val outcome = try {
                operation(
                    { fraction ->
                        if (terminal.get() != RUNNING) {
                            false
                        } else {
                            mutableProgress.value = fraction.coerceIn(0f, 1f)
                            terminal.get() == RUNNING
                        }
                    },
                    { terminal.get() == RUNNING },
                    { terminal.compareAndSet(RUNNING, COMMIT_STARTED) },
                )
            } catch (failure: Throwable) {
                CommandResult.Rejected(RejectionCode.UNSUPPORTED, failure.message ?: "Fill failed")
            }
            terminal.set(FINISHED)
            if (outcome is CommandResult.Accepted) mutableProgress.value = 1f
            result.complete(outcome)
            onFinished(this@NativeFillJob)
        }
        worker.invokeOnCompletion { cause ->
            if (cause != null && result.complete(CommandResult.Rejected(RejectionCode.CLOSED, "Document closed before fill started"))) {
                terminal.set(FINISHED)
                onFinished(this@NativeFillJob)
            }
            finished.countDown()
        }
    }

    override suspend fun await(): CommandResult = result.await()

    fun awaitFinished() {
        finished.await()
    }

    override fun cancel() {
        terminal.compareAndSet(RUNNING, CANCEL_REQUESTED)
    }

    private companion object {
        const val RUNNING = 0
        const val CANCEL_REQUESTED = 1
        const val COMMIT_STARTED = 2
        const val FINISHED = 3
    }
}

/** Versioned layout consumed by `jni_brush.cpp::decodeSettings`. */
private fun BrushSettings.nativeEncoding(): FloatArray = buildList<Float> {
    add(4f)
    add(this@nativeEncoding.size)
    add(this@nativeEncoding.hardness)
    add(this@nativeEncoding.opacity)
    add(this@nativeEncoding.flow)
    add(this@nativeEncoding.spacing)
    add(this@nativeEncoding.angle)
    add(this@nativeEncoding.roundness)
    add(this@nativeEncoding.sizeJitter)
    add(this@nativeEncoding.angleJitter)
    add(this@nativeEncoding.scatter)
    add(if (this@nativeEncoding.scatterBothAxes) 1f else 0f)
    add(this@nativeEncoding.count.toFloat())
    add(if (this@nativeEncoding.buildUp) 1f else 0f)
    add(this@nativeEncoding.smoothing)
    add(this@nativeEncoding.smoothingMode.ordinal.toFloat())
    add(if (this@nativeEncoding.wetEdges) 1f else 0f)

    fun appendDynamics(dynamics: Dynamics) {
        add(dynamics.input.ordinal.toFloat())
        add(dynamics.minimum)
        add(dynamics.curve.points.size.toFloat())
        dynamics.curve.points.forEach { point ->
            add(point.x)
            add(point.y)
        }
    }
    appendDynamics(this@nativeEncoding.sizeDynamics)
    appendDynamics(this@nativeEncoding.opacityDynamics)
    appendDynamics(this@nativeEncoding.flowDynamics)
}.toFloatArray()

private const val CROPTOP_TILE_FORMAT_A16 = 4

/** Native C++ engine entry point and document factory. */
@Suppress("DEPRECATION")
class NativeEngine(private val applicationContext: Context? = null) : Engine, ComponentCallbacks2 {
    override fun filterSpecs(): List<app.croptop.core.model.AdjustmentSpec> = app.croptop.core.model.FilterCatalog.All

    companion object {
        private const val DEFAULT_MEMORY_BUDGET_RATIO = 0.4
        private const val MIN_MEMORY_BUDGET_BYTES = 64L * 1024 * 1024
        private const val MAX_MEMORY_BUDGET_BYTES = 1024L * 1024 * 1024
        private const val MAX_ENCODED_IMAGE_BYTES = 128L * 1024 * 1024
        init {
            System.loadLibrary("croptop")
        }

        val info: String get() = NativeEngine().let { engine -> engine.nativeInfo().also { engine.close() } }
    }

    private val ids = AtomicLong(1)
    private val documents = linkedSetOf<NativeDocument>()

    // Edit ▸ Copy/Cut keep a canvas-sized native copy of the source pixels; Paste places it into any document.
    private val clipboardLock = Any()
    private val clipboardState = MutableStateFlow<ClipboardInfo?>(null)
    private var clipboardStore = 0L
    override val clipboard: StateFlow<ClipboardInfo?> get() = clipboardState.asStateFlow()

    /** Takes ownership of [store] (canvas-sized in its source document) as the clipboard content. */
    internal fun setClipboard(store: Long, info: ClipboardInfo) = synchronized(clipboardLock) {
        if (clipboardStore != 0L) destroyTileStore(clipboardStore)
        clipboardStore = store
        clipboardState.value = info
    }

    /** A caller-owned copy of the clipboard store and its info, or null when the clipboard is empty. */
    internal fun clipboardCopy(): Pair<Long, ClipboardInfo>? = synchronized(clipboardLock) {
        val info = clipboardState.value ?: return null
        val copy = cloneTileStore(clipboardStore)
        if (copy == 0L) null else copy to info
    }
    @Volatile private var closed = false
    private val currentMemoryBudgetBytes = AtomicLong(defaultMemoryBudgetBytes())
    private val nativeMemoryBudget: Long

    override val memoryBudgetBytes: Long
        get() = currentMemoryBudgetBytes.get()

    init {
        val scratchParent = applicationContext?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        val scratchDirectory = File(scratchParent, "croptop/scratch")
        nativeMemoryBudget = nativeCreateMemoryBudget(scratchDirectory.absolutePath, currentMemoryBudgetBytes.get())
        check(nativeMemoryBudget != 0L) { "Could not initialize native memory budget" }
        applicationContext?.registerComponentCallbacks(this)
    }

    // ---- Pattern library: filesDir/croptop/patterns/<id>.png plus index.json ----

    private val patternStores = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val patternList = MutableStateFlow<List<app.croptop.core.engine.api.PatternInfo>>(emptyList())
    override val patterns: StateFlow<List<app.croptop.core.engine.api.PatternInfo>> get() = patternList.asStateFlow()
    private val patternDir: File? get() = applicationContext?.let { File(it.filesDir, "croptop/patterns") }
    private val rawLensProfileLock = Any()
    private val rawLensProfileDatabaseDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        applicationContext?.let(NativeLensProfileBridge::fromAssets)
    }
    private val rawLensProfileDatabase: NativeLensProfileBridge.Database? by rawLensProfileDatabaseDelegate

    init {
        // After the library's properties exist (initializers run in declaration order).
        NativeLayerStylesBridge.patternResolver = { id -> patternStores[id] ?: 0L }
        runCatching { loadPatternLibrary() }
    }

    internal fun patternStore(id: String): Long = patternStores[id] ?: 0L

    private fun loadPatternLibrary() {
        val dir = patternDir ?: return
        val index = File(dir, "index.json").takeIf { it.isFile } ?: return
        val array = org.json.JSONArray(index.readText())
        val loaded = mutableListOf<app.croptop.core.engine.api.PatternInfo>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val info = app.croptop.core.engine.api.PatternInfo(o.getString("id"), o.getString("name"), o.getInt("width"), o.getInt("height"))
            val png = File(dir, "${info.id}.png").takeIf { it.isFile } ?: continue
            val bytes = png.readBytes()
            val store = createTileStore(info.width, info.height, 0)
            if (store == 0L) continue
            if (decodeToTileStore(bytes, bytes.size, store, 1) != null) {
                destroyTileStore(store)
                continue
            }
            patternStores[info.id] = store
            loaded += info
        }
        patternList.value = loaded
    }

    private fun savePatternIndex() {
        val dir = patternDir ?: return
        dir.mkdirs()
        val array = org.json.JSONArray()
        patternList.value.forEach { p ->
            array.put(org.json.JSONObject().put("id", p.id).put("name", p.name).put("width", p.width).put("height", p.height))
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(File(dir, "index.json"))
    }

    /** Adds [store] (taken over) to the library as [name]; persisted as PNG. */
    internal fun addPattern(name: String, store: Long, width: Int, height: Int, id: String = CroptopManifest.newLayerUuid()): app.croptop.core.engine.api.PatternInfo {
        val info = app.croptop.core.engine.api.PatternInfo(id, name.ifBlank { "Pattern ${patternList.value.size + 1}" }, width, height)
        patternStores.put(id, store)?.let(::destroyTileStore)
        synchronized(patternList) {
            patternList.value = patternList.value.filterNot { it.id == id } + info
            runCatching {
                val dir = patternDir ?: return@runCatching
                dir.mkdirs()
                File(dir, "$id.png").outputStream().buffered().use { out -> encodePngToStream(store, out, 6, null, null) }
                savePatternIndex()
            }
        }
        return info
    }

    override fun deletePattern(id: String): Boolean = synchronized(patternList) {
        if (patternList.value.none { it.id == id }) return@synchronized false
        patternList.value = patternList.value.filterNot { it.id == id }
        patternStores.remove(id)?.let(::destroyTileStore)
        runCatching {
            patternDir?.let { File(it, "$id.png").delete() }
            savePatternIndex()
        }
        true
    }

    override fun patternThumbnail(id: String, maxPx: Int): app.croptop.core.engine.api.LayerThumb? {
        val info = patternList.value.firstOrNull { it.id == id } ?: return null
        val store = patternStores[id] ?: return null
        val bitmap = renderCompositePreview(info.width, info.height, listOf(NativeRenderLayer(store, 1, 0, 255, 255, true)), maxPx.coerceAtLeast(1))
            ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val rgba = ByteArray(pixels.size * 4)
            pixels.forEachIndexed { i, argb ->
                rgba[i * 4] = (argb shr 16).toByte()
                rgba[i * 4 + 1] = (argb shr 8).toByte()
                rgba[i * 4 + 2] = argb.toByte()
                rgba[i * 4 + 3] = (argb ushr 24).toByte()
            }
            app.croptop.core.engine.api.LayerThumb(LayerId(0), bitmap.width, bitmap.height, rgba)
        } finally {
            bitmap.recycle()
        }
    }

    val info: String get() = nativeInfo()

    @Volatile
    override var capabilities = EngineCapabilities(
        engineVersion = "native-0.1.0",
        abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        supportedDepthBits = setOf(8, 16, 32),
        features = setOf(EngineFeature.TILES, EngineFeature.LAYERS, EngineFeature.VULKAN),
    )
        private set

    internal fun updateVulkanCapabilities(gpuName: String, apiVersion: Int) {
        if (gpuName.isBlank() || apiVersion <= 0) return
        capabilities = capabilities.copy(gpuName = gpuName, vulkanApiVersion = apiVersion)
    }

    override suspend fun open(source: DocumentSource, options: OpenOptions): DocumentHandle =
        openSource(source, options).also { document ->
            (document as? NativeDocument)?.recoverySourceUri = when (source) {
                is DocumentSource.ContentUri -> source.value
                is DocumentSource.AppFile -> source.path
            }
        }

    override suspend fun exportRawDevelopSidecar(
        source: DocumentSource,
        settings: app.croptop.core.model.DevelopSettings,
    ): Long = withContext(Dispatchers.IO) {
        val context = applicationContext ?: throw IllegalStateException("NativeEngine needs Context to export a RAW sidecar")
        val sourceName = when (source) {
            is DocumentSource.AppFile -> {
                val image = File(source.path).canonicalFile
                val roots = listOf(context.filesDir.canonicalPath, context.cacheDir.canonicalPath)
                require(roots.any { image.path.startsWith("$it/") }) { "AppFile must be inside app-private storage" }
                image.name
            }

            is DocumentSource.ContentUri -> {
                val uri = Uri.parse(source.value)
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment.orEmpty()
            }
        }
        val extension = sourceName.substringAfterLast('.', "").lowercase()
        require(extension in RAW_SIDE_CAR_EXTENSIONS) { "Develop XMP sidecars can only be exported for supported RAW files" }
        val encoded = readEncodedSource(source)
        val raw = try {
            NativeRawBridge.probe(encoded.bytes, encoded.size)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Develop XMP sidecars can only be exported for supported RAW files", error)
        }
        if (!raw.decodable) {
            throw UnsupportedOperationException(raw.unsupportedReason.ifBlank { "This RAW format cannot be developed by CropTop" })
        }
        val xmp = NativeRawBridge.writeSidecar(settings).toByteArray(Charsets.UTF_8)
        require(xmp.size <= MAX_RAW_SIDECAR_BYTES) { "Generated RAW XMP sidecar exceeds the 16 MiB limit" }
        when (source) {
            is DocumentSource.AppFile -> {
                val image = File(source.path).canonicalFile
                val sidecar = File(image.parentFile ?: throw IOException("RAW source has no parent directory"),
                    "${image.nameWithoutExtension}.xmp")
                writeExport(ExportTarget.AppFile(sidecar.absolutePath)) { output ->
                    output.write(xmp)
                    xmp.size.toLong()
                }
            }

            is DocumentSource.ContentUri -> writeTreeRawSidecar(Uri.parse(source.value), xmp)
        }
    }

    override suspend fun openDevelopPreview(source: DocumentSource): app.croptop.core.engine.api.DevelopPreviewSession = withContext(Dispatchers.IO) {
        val encoded = readEncodedSource(source)
        val probe = NativeRawBridge.probe(encoded.bytes, encoded.size)
        if (!probe.decodable) throw UnsupportedOperationException(probe.unsupportedReason.ifBlank { "This RAW file cannot be developed" })
        val session = synchronized(rawLensProfileLock) {
            check(!closed) { "Engine is closed" }
            NativeRawBridge.openSession(
                encoded.bytes, encoded.size,
                workerThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                lensDatabase = rawLensProfileDatabase,
            )
        }
        object : app.croptop.core.engine.api.DevelopPreviewSession {
            override val width = session.width
            override val height = session.height
            override val warnings = session.warnings

            override suspend fun render(settings: app.croptop.core.model.DevelopSettings, maxDimension: Int) = withContext(Dispatchers.Default) {
                val preview = session.render(settings, maxDimension, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                app.croptop.core.engine.api.DevelopPreview(preview.width, preview.height, preview.pixels, preview.warnings)
            }

            override suspend fun maskOverlay(settings: app.croptop.core.model.DevelopSettings, maskIndex: Int, maxDimension: Int) =
                withContext(Dispatchers.Default) {
                    val overlay = session.maskOverlay(settings, maskIndex, maxDimension, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                    app.croptop.core.engine.api.DevelopPreview(overlay.width, overlay.height, overlay.pixels, overlay.warnings)
                }

            override fun close() = session.close()
        }
    }

    /** Develops [bytes] with [settings] into a new store (caller owns it). Blocking. */
    internal fun developRaw(bytes: ByteArray, settings: app.croptop.core.model.DevelopSettings): NativeRawBridge.Opened =
        synchronized(rawLensProfileLock) {
            check(!closed) { "Engine is closed" }
            NativeRawBridge.open(
                bytes, bytes.size, sixteenBit = true, applyOrientation = true,
                settings = settings, lensDatabase = rawLensProfileDatabase,
            )
        }

    private suspend fun openSource(source: DocumentSource, options: OpenOptions): DocumentHandle = withContext(Dispatchers.IO) {
        if (isCroptopSource(source)) return@withContext openCroptop(source)
        val encoded = readEncodedSource(source)
        val rawProbe = try {
            NativeRawBridge.probe(encoded.bytes, encoded.size)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (rawProbe != null) {
            if (!rawProbe.decodable) {
                throw UnsupportedOperationException(rawProbe.unsupportedReason.ifBlank { "This RAW file cannot be developed" })
            }
            val sidecar = if (options.rawDevelopSettings == null) readRawSidecar(source) else null
            val opened = synchronized(rawLensProfileLock) {
                check(!closed) { "Engine is closed" }
                when {
                    options.rawDevelopSettings != null -> NativeRawBridge.open(
                        encoded.bytes, encoded.size, sixteenBit = true, applyOrientation = true,
                        settings = options.rawDevelopSettings,
                        lensDatabase = rawLensProfileDatabase,
                    )
                    sidecar != null -> NativeRawBridge.openWithSidecar(
                        encoded.bytes, sidecar, encoded.size, sixteenBit = true, applyOrientation = true,
                        lensDatabase = rawLensProfileDatabase,
                    )
                    else -> NativeRawBridge.open(
                        encoded.bytes, encoded.size, sixteenBit = true, applyOrientation = true,
                        lensDatabase = rawLensProfileDatabase,
                    )
                }
            }
            val depth = when (opened.bitDepth) {
                8 -> app.croptop.core.model.ColorDepth.U8
                16 -> app.croptop.core.model.ColorDepth.U16
                32 -> app.croptop.core.model.ColorDepth.F32
                else -> {
                    NativeSelectionBridge.destroyStore(opened.storeHandle)
                    throw UnsupportedOperationException("Unsupported RAW output depth ${opened.bitDepth}")
                }
            }
            require(opened.width in 1..32_768 && opened.height in 1..32_768 && opened.width.toLong() * opened.height <= 500_000_000L) {
                NativeSelectionBridge.destroyStore(opened.storeHandle)
                "RAW dimensions exceed the supported limit"
            }
            val name = documentName(source, "RAW image")
            val document = newDocument(
                NewDocumentSpec(
                    name = name,
                    size = DocSize(opened.width, opened.height),
                    depth = depth,
                    profile = app.croptop.core.model.ColorProfileRef.BuiltIn(WorkingSpace.SRGB),
                    background = BackgroundContents.TRANSPARENT,
                ),
            ) as NativeDocument
            var adopted = false
            try {
                val rawSmart = if (options.rawAsSmartObject) {
                    val used = options.rawDevelopSettings
                        ?: sidecar?.let { runCatching { NativeRawBridge.readDevelopSidecar(it) }.getOrNull() }
                        ?: app.croptop.core.model.DevelopSettings()
                    encoded.bytes.copyOf(encoded.size) to used
                } else {
                    null
                }
                document.loadRaw(opened, rawSmart)
                adopted = true
                NativeRawBridge.embeddedPreview(encoded.bytes, encoded.size)?.let { previewBytes ->
                    document.setViewportPreview(
                        decodeViewportPreview(previewBytes, previewBytes.size, opened.width, opened.height, 1),
                        checkNotNull(document.layers.value.primary),
                    )
                }
                return@withContext document
            } catch (failure: Throwable) {
                document.close()
                if (!adopted && opened.storeHandle != 0L) NativeSelectionBridge.destroyStore(opened.storeHandle)
                throw failure
            }
        }
        if (NativeCodecExtraBridge.detect(encoded.bytes, encoded.size) != null) {
            return@withContext openCodecExtra(source, encoded.bytes, encoded.size)
        }
        if (NativeAvifUltraHdrBridge.isAvif(encoded.bytes, encoded.size)) {
            return@withContext openAvif(source, encoded.bytes, encoded.size)
        }
        if (NativeAvifUltraHdrBridge.isUltraHdr(encoded.bytes, encoded.size)) {
            return@withContext openUltraHdr(source, encoded.bytes, encoded.size)
        }
        val format = ImageFormat.detect(encoded.bytes, encoded.size)
        val codec = when (format) {
            ImageFormat.JPEG -> 0
            ImageFormat.PNG -> 1
            ImageFormat.BMP -> 2
            ImageFormat.WEBP -> 3
            ImageFormat.GIF -> 4
            else -> throw UnsupportedOperationException("${format.mimeType} decoding is not available yet")
        }
        val metadata = RasterMetadataReader.read(encoded.bytes, encoded.size, format)
        val exifOrientation = metadata.exifOrientation ?: 1
        val (sourceWidth, sourceHeight) = if (format == ImageFormat.BMP) {
            require(encoded.size >= 26) { "BMP header is truncated" }
            val header = ByteBuffer.wrap(encoded.bytes).order(ByteOrder.LITTLE_ENDIAN)
            val bmpWidth = header.getInt(18)
            val bmpHeight = header.getInt(22)
            bmpWidth to if (bmpHeight < 0) -bmpHeight else bmpHeight
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.size, bounds)
            bounds.outWidth to bounds.outHeight
        }
        val (width, height) = if (exifOrientation in 5..8) sourceHeight to sourceWidth else sourceWidth to sourceHeight
        require(width in 1..32_768 && height in 1..32_768 && width.toLong() * height <= 500_000_000L) {
            "Image dimensions exceed the supported limit"
        }
        val depth = if (format == ImageFormat.PNG && encoded.size > 25 && encoded.bytes[24].toInt() and 0xff == 16) {
            app.croptop.core.model.ColorDepth.U16
        } else {
            app.croptop.core.model.ColorDepth.U8
        }
        val name = documentName(source, "Image")
        val document = newDocument(
            NewDocumentSpec(
                name = name,
                size = DocSize(width, height),
                depth = depth,
                profile = metadata.iccProfile?.let { profile ->
                    app.croptop.core.model.ColorProfileRef.Embedded("Embedded ICC profile", profile.sha256())
                } ?: app.croptop.core.model.ColorProfileRef.BuiltIn(WorkingSpace.SRGB),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        try {
            document.attachImportedRasterMetadata(metadata)
            document.loadRaster(encoded.bytes, encoded.size, codec, exifOrientation)
            document.setViewportPreview(
                decodeViewportPreview(encoded.bytes, encoded.size, sourceWidth, sourceHeight, exifOrientation),
                checkNotNull(document.layers.value.primary),
            )
            document
        } catch (failure: Throwable) {
            document.close()
            throw failure
        }
    }

    /**
     * The file's own name ("dog.jpg"): a content URI's display name from its provider, since its last path segment is often only
     * an id ("186"); [fallback] when neither is known.
     */
    private fun documentName(source: DocumentSource, fallback: String): String = when (source) {
        is DocumentSource.ContentUri -> {
            val uri = Uri.parse(source.value)
            val display = runCatching {
                applicationContext?.contentResolver?.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            display?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/')
        }
        is DocumentSource.AppFile -> File(source.path).name
    }?.takeIf(String::isNotBlank) ?: fallback

    /** AVIF: 8-bit files open as RGBA8 documents, 10/12-bit files as 16-bit ones; the embedded ICC profile is kept as the working profile. */
    private fun openAvif(source: DocumentSource, bytes: ByteArray, length: Int): NativeDocument {
        val info = NativeAvifUltraHdrBridge.probeAvif(bytes, length)
        require(
            info.width in 1..32_768 && info.height in 1..32_768 && info.width.toLong() * info.height <= 500_000_000L,
        ) { "Image dimensions exceed the supported limit" }
        val image = NativeAvifUltraHdrBridge.decodeAvif(bytes, length)
        val depth = if (info.bitDepth > 8) app.croptop.core.model.ColorDepth.U16 else app.croptop.core.model.ColorDepth.U8
        return openDecoded(documentName(source, "AVIF image"), image.storeHandle, info.width, info.height, depth, "Open AVIF")
    }

    /** Ultra HDR JPEG: decoded with its gain map applied into a 32-bit linear document. */
    private fun openUltraHdr(source: DocumentSource, bytes: ByteArray, length: Int): NativeDocument {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, length, bounds)
        require(bounds.outWidth in 1..32_768 && bounds.outHeight in 1..32_768) { "Image dimensions exceed the supported limit" }
        val store = NativeAvifUltraHdrBridge.decodeUltraHdr(bytes, length)
        return openDecoded(
            documentName(source, "Ultra HDR image"), store, bounds.outWidth, bounds.outHeight, app.croptop.core.model.ColorDepth.F32, "Open Ultra HDR",
        )
    }

    private fun openDecoded(
        name: String,
        store: Long,
        width: Int,
        height: Int,
        depth: app.croptop.core.model.ColorDepth,
        label: String,
    ): NativeDocument {
        val document = newDocument(
            NewDocumentSpec(name = name, size = DocSize(width, height), depth = depth, background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        var adopted = false
        try {
            document.loadDecoded(store, width, height, depth.bitsPerChannel, label)
            adopted = true
            return document
        } catch (failure: Throwable) {
            document.close()
            if (!adopted) NativeSelectionBridge.destroyStore(store)
            throw failure
        }
    }

    private fun openCodecExtra(source: DocumentSource, bytes: ByteArray, length: Int): NativeDocument {
        val probe = NativeCodecExtraBridge.probe(bytes, length)
        require(
            probe.width in 1..32_768 && probe.height in 1..32_768 &&
                probe.width.toLong() * probe.height <= 500_000_000L,
        ) { "Image dimensions exceed the supported limit" }
        val depth = when (probe.bitDepth) {
            8 -> app.croptop.core.model.ColorDepth.U8
            16 -> app.croptop.core.model.ColorDepth.U16
            32 -> app.croptop.core.model.ColorDepth.F32
            else -> throw IOException("Unsupported TIFF/PSD channel depth ${probe.bitDepth}")
        }
        val name = documentName(source, if (probe.kind == NativeCodecExtraBridge.Kind.TIFF) "TIFF image" else "PSD image")
        var flattenedFallback = false
        if (probe.kind == NativeCodecExtraBridge.Kind.PSD) {
            // Layered open first; files whose layers can't be read still open flattened below.
            runCatching { openLayeredPsd(name, bytes, length, probe.width, probe.height, depth) }
                .onFailure {
                    flattenedFallback = true
                    android.util.Log.w("CropTopEngine", "Layered PSD import failed; opening flattened", it)
                }
                .getOrNull()?.let { return it }
        }
        val document = newDocument(NewDocumentSpec(name = name, size = DocSize(probe.width, probe.height), depth = depth)) as NativeDocument
        try {
            document.loadCodecExtra(bytes, length)
            if (flattenedFallback) {
                document.importedRasterMetadata = document.importedRasterMetadata?.let { metadata ->
                    metadata.copy(warnings = metadata.warnings + "Opened a flattened image because the layers could not be imported. The original file has not been changed.")
                }
            }
            val layer = checkNotNull(document.layers.value.primary)
            document.setViewportPreview(decodeViewportPreview(bytes, length, probe.width, probe.height, 1), layer)
            return document
        } catch (failure: Throwable) {
            document.close()
            throw failure
        }
    }

    /** A PSD type layer's content as CropTop text (first run's font, size, colour and alignment). */
    private fun psdTextInfo(o: org.json.JSONObject): app.croptop.core.model.TextLayerInfo {
        // PostScript names: "Arial-BoldMT" → Arial / Bold, "MyriadPro-Regular" → Myriad Pro / Regular.
        val postScript = o.optString("font")
        val familyPart = postScript.substringBefore('-').removeSuffix("MT").removeSuffix("PS")
        val family = familyPart.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").ifBlank { "Roboto" }
        val style = postScript.substringAfter('-', "Regular").removeSuffix("MT").replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").ifBlank { "Regular" }
        val color = o.optJSONArray("color")
        val align = when (o.optInt("align")) {
            1 -> app.croptop.core.model.TextAlign.RIGHT
            2 -> app.croptop.core.model.TextAlign.CENTER
            else -> app.croptop.core.model.TextAlign.LEFT
        }
        val boxWidth = o.optDouble("boxWidth", 0.0).toFloat()
        return app.croptop.core.model.TextLayerInfo(
            text = o.getString("text"),
            character = app.croptop.core.model.CharacterStyle(
                family = family,
                style = style,
                sizePt = o.optDouble("size", 12.0).toFloat(),
                color = if (color != null && color.length() == 3) {
                    app.croptop.core.model.RgbaColor(color.getDouble(0).toFloat(), color.getDouble(1).toFloat(), color.getDouble(2).toFloat())
                } else {
                    app.croptop.core.model.RgbaColor.Black
                },
            ).normalized(),
            paragraph = app.croptop.core.model.ParagraphStyle(align = align),
            kind = if (boxWidth > 0f) app.croptop.core.model.TextKind.PARAGRAPH else app.croptop.core.model.TextKind.POINT,
            origin = DocPoint(o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat()),
            boxWidth = boxWidth,
            rotation = ((o.optDouble("rotation", 0.0).toFloat() % 360f) + 360f) % 360f,
        )
    }

    /** Opens a PSD/PSB with its layer tree (groups, modes, opacity, masks, clipping); null when it has one plain layer. */
    private fun openLayeredPsd(name: String, bytes: ByteArray, length: Int, width: Int, height: Int, depth: app.croptop.core.model.ColorDepth): NativeDocument? {
        val format = when (depth) {
            app.croptop.core.model.ColorDepth.U8 -> 0
            app.croptop.core.model.ColorDepth.U16 -> 1
            else -> 2
        }
        val template = createTileStore(width, height, format)
        check(template != 0L) { "Could not allocate layer storage" }
        val holder = try {
            nativeImportPsd(bytes, length, template)
        } finally {
            destroyTileStore(template)
        }
        check(holder != 0L) { nativeImportPsdLastError().ifBlank { "Could not read the PSD layers" } }
        try {
            val json = org.json.JSONObject(checkNotNull(nativeImportPsdJson(holder)))
            val stores = checkNotNull(nativeImportPsdStores(holder)) { nativeImportPsdLastError() }
            val importedIcc = NativeCodecExtraBridge.psdIccProfile(holder)
            val used = BooleanArray(stores.size)
            val adopt = mutableListOf<Pair<LayerId, Long>>()
            val smartSourcesToAdopt = mutableListOf<Pair<String, Long>>()
            try {
                fun blend(key: String): BlendMode = BlendMode.entries.firstOrNull { it.psdKey == key } ?: BlendMode.NORMAL
                fun build(array: org.json.JSONArray): List<LayerNode> = (0 until array.length()).mapNotNull { i ->
                    val o = array.getJSONObject(i)
                    // Adjustment layers map to CropTop adjustments when their settings are understood; others are skipped.
                    val adjustment = if (o.getString("kind") == "adjustment") {
                        PsdAdjustments.parse(o.getString("adjustment"), o.optString("adjustmentData")) ?: return@mapNotNull null
                    } else {
                        null
                    }
                    val id = LayerId(ids.getAndIncrement())
                    val kind = when (o.getString("kind")) {
                        "group" -> LayerKind.GROUP
                        "adjustment" -> LayerKind.ADJUSTMENT
                        // Smart objects embed their rendered pixels as the source (later transforms stay lossless).
                        "smartObject" -> if (o.getInt("pixels") >= 0) LayerKind.SMART_OBJECT else LayerKind.PIXEL
                        // Type keeps Photoshop's rendered pixels until edited; its text and first-run style become editable.
                        "text" -> if (o.optJSONObject("text") != null) LayerKind.TEXT else LayerKind.PIXEL
                        else -> LayerKind.PIXEL
                    }
                    val textInfo = if (kind == LayerKind.TEXT) psdTextInfo(checkNotNull(o.optJSONObject("text"))) else null
                    val pixels = o.getInt("pixels")
                    val mask = o.getInt("mask")
                    var maskInfo: app.croptop.core.model.LayerMaskInfo? = null
                    // Layer effects decoded natively from `lfx2` (pixel, type and smart object layers).
                    val layerStyles = o.optJSONArray("styles")?.takeIf { it.length() > 0 && kind != LayerKind.GROUP && kind != LayerKind.ADJUSTMENT }
                        ?.let { array -> NativeLayerStylesBridge.toModel(FloatArray(array.length()) { array.getDouble(it).toFloat() }) }
                    var smartObject: app.croptop.core.model.SmartObjectInfo? = null
                    var blendIfSettings: app.croptop.core.model.BlendIfSettings? = null
                    if (kind == LayerKind.PIXEL || kind == LayerKind.ADJUSTMENT || kind == LayerKind.SMART_OBJECT || kind == LayerKind.TEXT) {
                        val hasPixels = kind != LayerKind.ADJUSTMENT && pixels >= 0
                        val store = if (hasPixels) stores[pixels].also { used[pixels] = true } else createTileStore(width, height, format)
                        check(store != 0L) { "Could not allocate layer storage" }
                        layerStyles?.let { NativeLayerStylesBridge.attach(store, it) }
                        if (kind == LayerKind.SMART_OBJECT) {
                            val source = cloneTileStore(store)
                            check(source != 0L) { "Could not embed the smart object" }
                            // The source is the bare pixels: mask, clipping and styles stay on the layer.
                            runCatching { NativeLayerMaskBridge.detach(source) }
                            NativeLayerMaskBridge.setClipped(source, false)
                            NativeLayerStylesBridge.attach(source, null)
                            val key = CroptopManifest.newLayerUuid()
                            smartSourcesToAdopt += key to source
                            smartObject = app.croptop.core.model.SmartObjectInfo(sourceKey = key, sourceWidth = width, sourceHeight = height)
                        }
                        if (adjustment != null) {
                            setAdjustmentLayerLut(store, adjustment)?.let { failure ->
                                destroyTileStore(store)
                                error(failure)
                            }
                        }
                        if (o.getBoolean("clipped")) NativeLayerMaskBridge.setClipped(store, true)
                        o.optJSONArray("blendIf")?.takeIf { it.length() == 8 || it.length() == app.croptop.core.model.BlendIfSettings.INTS }?.let { array ->
                            val v = IntArray(array.length()) { array.getInt(it).coerceIn(0, 255) }
                            // Only a valid, non-crossing recipe is kept; Photoshop files can hold values this model rejects.
                            blendIfSettings = app.croptop.core.model.BlendIfSettings.fromInts(v)
                        }
                        blendIfSettings?.let { NativeLayerMaskBridge.setBlendIf(store, it) }
                        if (mask >= 0) {
                            // The mask cache copies the A16 pixels; the imported mask store is freed with the unused ones.
                            val info = app.croptop.core.model.LayerMaskInfo(enabled = !o.getBoolean("maskDisabled"))
                            val cache = NativeLayerMaskBridge.fromStore(stores[mask], o.getInt("maskDefault") / 255f)
                            try {
                                NativeLayerMaskBridge.attach(store, cache, info)
                                maskInfo = info
                            } finally {
                                NativeLayerMaskBridge.destroy(cache)
                            }
                        }
                        adopt += id to store
                    }
                    LayerNode(
                        id = id,
                        name = o.getString("name").ifBlank { if (kind == LayerKind.GROUP) "Group" else "Layer" },
                        kind = kind,
                        visible = o.getBoolean("visible"),
                        opacity = o.getInt("opacity") / 255f,
                        fillOpacity = o.getInt("fill") / 255f,
                        blendMode = blend(o.getString("blend")).let { if (kind != LayerKind.GROUP && it == BlendMode.PASS_THROUGH) BlendMode.NORMAL else it },
                        locks = if (o.getBoolean("lockTransparency")) app.croptop.core.model.LayerLocks(transparency = true) else app.croptop.core.model.LayerLocks.None,
                        clipped = o.getBoolean("clipped"),
                        blendIf = blendIfSettings ?: app.croptop.core.model.BlendIfSettings(),
                        mask = maskInfo,
                        adjustment = adjustment,
                        smartObject = smartObject,
                        styles = layerStyles,
                        hasStyles = layerStyles != null,
                        text = textInfo,
                        children = if (kind == LayerKind.GROUP) build(o.getJSONArray("children")) else emptyList(),
                    )
                }
                val layers = build(json.getJSONArray("layers"))
                if (layers.size <= 1 && layers.none { it.isGroup || it.mask != null || it.kind != LayerKind.PIXEL }) {
                    adopt.forEach { destroyTileStore(it.second) }
                    adopt.clear()
                    return null
                }
                val top = layers.firstOrNull { !it.isGroup }?.id ?: layers.first().id
                val tree = LayerTree(layers = layers, active = setOf(top), primary = top)
                val info = DocumentInfo(
                    id = DocumentId(ids.getAndIncrement()),
                    name = name,
                    size = DocSize(width, height),
                    depth = depth,
                    profile = app.croptop.core.model.ColorProfileRef.BuiltIn(WorkingSpace.SRGB),
                    resolutionPpi = 72f,
                )
                val document = NativeDocument(this, info, tree, BackgroundContents.TRANSPARENT, format, ::releaseDocument)
                try {
                    adopt.forEach { (id, store) -> document.adoptPixelStore(id, store) }
                    adopt.clear()
                    smartSourcesToAdopt.forEach { (key, store) -> document.restoreSmartSource(key, store) }
                    smartSourcesToAdopt.clear()
                    document.installImportedWorkingProfile(importedIcc)
                    document.finishArchiveOpen()
                    synchronized(this) {
                        check(!closed) { "Engine was closed while opening" }
                        documents += document
                    }
                    return document
                } catch (failure: Throwable) {
                    document.close()
                    throw failure
                }
            } finally {
                adopt.forEach { destroyTileStore(it.second) }
                smartSourcesToAdopt.forEach { destroyTileStore(it.second) }
                stores.forEachIndexed { index, handle -> if (!used[index]) destroyTileStore(handle) }
            }
        } finally {
            nativeImportPsdDestroy(holder)
        }
    }

    private fun isCroptopSource(source: DocumentSource): Boolean {
        val context = applicationContext ?: return false
        return when (source) {
            is DocumentSource.AppFile -> source.path.substringBefore('?').endsWith(".croptop", ignoreCase = true)

            is DocumentSource.ContentUri -> {
                val uri = Uri.parse(source.value)
                // Providers often hide the name (".../document/msf:50"): check the MIME type the app saves with
                // (application/x-croptop; vnd.croptop accepted too) and the provider's display name.
                uri.lastPathSegment?.endsWith(".croptop", ignoreCase = true) == true ||
                    context.contentResolver.getType(uri) in CROPTOP_MIME_TYPES ||
                    runCatching {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            cursor.moveToFirst() && cursor.getString(0)?.endsWith(".croptop", ignoreCase = true) == true
                        } == true
                    }.getOrDefault(false)
            }
        }
    }

    /** Stages SAF streams to disk so the ZIP reader can pread tiles without loading a project into heap. */
    private suspend fun openCroptop(source: DocumentSource): NativeDocument = withContext(Dispatchers.IO) {
        val context = applicationContext ?: throw IllegalStateException("NativeEngine needs Context to open CropTop projects")
        val staged = when (source) {
            is DocumentSource.AppFile -> {
                val file = File(source.path).canonicalFile
                val roots = listOf(context.filesDir.canonicalPath, context.cacheDir.canonicalPath)
                require(roots.any { file.path.startsWith("$it/") }) { "AppFile must be inside app-private storage" }
                file
            }

            is DocumentSource.ContentUri -> {
                val input = context.contentResolver.openInputStream(Uri.parse(source.value))
                    ?: throw FileNotFoundException("Could not open CropTop project URI")
                val file = File.createTempFile("croptop-open-", ".croptop", context.cacheDir)
                try {
                    input.use { inputStream ->
                        file.outputStream().buffered().use { output -> inputStream.copyTo(output) }
                    }
                } catch (failure: Throwable) {
                    file.delete()
                    throw failure
                }
                file
            }
        }
        val archive = nativeOpenCroptopArchive(staged.absolutePath)
        if (archive == 0L) {
            if (source is DocumentSource.ContentUri) staged.delete()
            throw IOException("Could not read CropTop project archive")
        }
        try {
            openProject(
                nativeCroptopManifest(archive),
                layerInfo = { uuid -> nativeCroptopLayerInfo(archive, uuid) },
                loadLayer = { uuid, store -> nativeLoadCroptopLayer(archive, uuid, store) },
                workingIcc = nativeCroptopWorkingIcc(archive),
                requireEmbeddedProfile = true,
                embedded = { id, name -> nativeCroptopEmbedded(archive, id, name) },
            )
        } finally {
            nativeCloseCroptopArchive(archive)
            if (source is DocumentSource.ContentUri) staged.delete()
        }
    }

    /**
     * Builds a document from a project manifest and its layer tiles: a .croptop archive or a crash-recovery
     * journal (same manifest and layer table). [layerInfo] returns [format, width, height]; [loadLayer] fills
     * a store and returns an error message or null.
     */
    private fun openProject(
        manifestBytes: ByteArray?,
        layerInfo: (String) -> IntArray?,
        loadLayer: (String, Long) -> String?,
        workingIcc: ByteArray? = null,
        requireEmbeddedProfile: Boolean = false,
        embedded: (String, String) -> ByteArray? = { _, _ -> null },
    ): NativeDocument {
        if (manifestBytes == null) throw IOException("Project manifest is missing or invalid")
        val decoded = try {
            CroptopManifestCodec.decode(manifestBytes)
        } catch (failure: CroptopManifestException) {
            throw IOException("Could not open CropTop project: ${failure.message}", failure)
        }
        val manifest = decoded.manifest
        val profile = manifest.canvas.profile.toModel()
        val embeddedProfile = profile as? app.croptop.core.model.ColorProfileRef.Embedded
        if (embeddedProfile != null) {
            if (workingIcc == null && requireEmbeddedProfile) throw IOException("CropTop project is missing its embedded ICC profile")
            if (workingIcc != null && workingIcc.sha256() != embeddedProfile.hash) {
                throw IOException("CropTop project ICC profile does not match its manifest")
            }
        }
        val (tree, idsByUuid) = manifest.toLayerTree { LayerId(ids.getAndIncrement()) }
        val sourceFormats = manifest.pixelLayers().map { it.second }
        val archivePrecision = sourceFormats.maxOfOrNull { pixelFormat ->
            when (pixelFormat) {
                CroptopPixelFormat.RGBA8, CroptopPixelFormat.A8 -> 8
                CroptopPixelFormat.RGBA16, CroptopPixelFormat.A16 -> 16
                CroptopPixelFormat.RGBA32F -> 32
            }
        } ?: 8
        val depth = when (maxOf(manifest.canvas.depth.bitsPerChannel, archivePrecision)) {
            8 -> app.croptop.core.model.ColorDepth.U8
            16 -> app.croptop.core.model.ColorDepth.U16
            32 -> app.croptop.core.model.ColorDepth.F32
            else -> throw IOException("Unsupported project color depth")
        }
        val format = when (depth.bitsPerChannel) {
            8 -> 0
            16 -> 1
            32 -> 2
            else -> throw IOException("Unsupported project color depth")
        }
        val background = manifest.metadata["croptop.background"]?.let {
            runCatching { BackgroundContents.valueOf(it) }.getOrNull()
        } ?: BackgroundContents.TRANSPARENT
        val info = DocumentInfo(
            id = DocumentId(ids.getAndIncrement()),
            name = manifest.name.ifBlank { "CropTop project" },
            size = DocSize(manifest.canvas.width, manifest.canvas.height),
            depth = depth,
            profile = profile,
            resolutionPpi = manifest.canvas.resolutionPpi,
        )
        val document = NativeDocument(this@NativeEngine, info, tree, background, format, ::releaseDocument)
        return try {
            document.preserveOriginalProjectManifest(decoded.original)
            if (workingIcc != null) document.attachImportedRasterMetadata(RasterMetadata(iccProfile = workingIcc.copyOf()))
            idsByUuid.forEach { (uuid, layerId) -> document.restoreLoadedLayerUuids(uuid, layerId) }
            manifest.pixelLayers().forEach { (uuid, pixelFormat) ->
                val layerId = idsByUuid[uuid] ?: throw IOException("Project references unknown layer $uuid")
                val layerInfo = layerInfo(uuid)
                    ?: throw IOException("Project layer $uuid has invalid tile metadata")
                val sourceFormat = pixelFormat.ordinal
                require(
                    layerInfo.size == 3 && layerInfo[0] == sourceFormat && layerInfo[1] > 0 && layerInfo[2] > 0 &&
                        layerInfo[1] <= manifest.canvas.width && layerInfo[2] <= manifest.canvas.height,
                ) {
                    "Project layer $uuid does not match its manifest"
                }
                val store = document.pixelStore(layerId) ?: throw IOException("Project layer $uuid has no pixel storage")
                if (sourceFormat == format && layerInfo[1] == manifest.canvas.width &&
                    layerInfo[2] == manifest.canvas.height
                ) {
                    loadLayer(uuid, store)?.let { message -> throw IOException(message) }
                } else {
                    val archiveStore = createTileStore(layerInfo[1], layerInfo[2], sourceFormat)
                    if (archiveStore == 0L) throw IOException("Could not allocate archive layer $uuid")
                    try {
                        loadLayer(uuid, archiveStore)?.let { message -> throw IOException(message) }
                        val promoted = promoteTileStore(archiveStore, format, manifest.canvas.width, manifest.canvas.height)
                        if (promoted == 0L) throw IOException("Could not promote archive layer $uuid")
                        document.adoptPixelStore(layerId, promoted)
                    } finally {
                        destroyTileStore(archiveStore)
                    }
                }
            }
            // Masks use a private A16 tile payload keyed independently from the public layer UUID.
            // Older v1 archives have no payload; their masks retain the manifest's empty coverage.
            for (layer in manifest.flatten().filter { it.mask != null }) {
                val id = idsByUuid[layer.uuid] ?: continue
                val sourceStore = document.pixelStore(id) ?: continue
                val maskKey = CroptopManifest.maskPayloadUuid(layer.uuid)
                val payloadInfo = layerInfo(maskKey)
                if (layer.maskPayloadPresent && payloadInfo == null) {
                    throw IOException("Layer mask payload for ${layer.uuid} is missing from the project archive")
                }
                val seed = NativeLayerMaskBridge.create(sourceStore, MaskFill.REVEAL_ALL)
                var rawMaskStore = 0L
                var restoredMask = 0L
                try {
                    rawMaskStore = NativeLayerMaskBridge.pixels(seed)
                    if (payloadInfo != null) {
                        require(payloadInfo.size == 3 && payloadInfo[0] == 4 &&
                            payloadInfo[1] == manifest.canvas.width && payloadInfo[2] == manifest.canvas.height) {
                            "Layer mask payload for ${layer.uuid} has invalid tile metadata"
                        }
                        loadLayer(maskKey, rawMaskStore)?.let { message -> throw IOException(message) }
                    }
                    restoredMask = NativeLayerMaskBridge.fromStore(rawMaskStore, layer.maskDefault)
                    NativeLayerMaskBridge.attach(sourceStore, restoredMask, layer.mask!!)
                } finally {
                    if (restoredMask != 0L) NativeLayerMaskBridge.destroy(restoredMask)
                    if (rawMaskStore != 0L) destroyTileStore(rawMaskStore)
                    NativeLayerMaskBridge.destroy(seed)
                }
            }
            document.restoreAdjustmentLayers()
            document.restoreTextLayers()
            manifest.flatten().flatMap { layer ->
                listOfNotNull(
                    layer.fill?.let { fill -> fill.patternId.takeIf { fill.kind == app.croptop.core.model.FillKind.PATTERN } },
                    layer.styles?.patternOverlay?.takeIf { it.enabled }?.patternId,
                )
            }.toSet().filter { patternStore(it) == 0L }.forEach { patternId ->
                val info = layerInfo(patternId)?.takeIf { it.size == 3 && it[1] > 0 && it[2] > 0 } ?: return@forEach
                val store = createTileStore(info[1], info[2], info[0])
                if (store == 0L) return@forEach
                if (loadLayer(patternId, store) != null) {
                    destroyTileStore(store)
                    return@forEach
                }
                addPattern("Embedded pattern", store, info[1], info[2], patternId)
            }
            for (channel in manifest.selectionChannels) {
                val payloadInfo = layerInfo(channel.payloadKey)
                    ?: throw IOException("Selection channel '${channel.name}' is missing its A16 payload")
                require(payloadInfo.size == 3 && payloadInfo[0] == CROPTOP_TILE_FORMAT_A16 &&
                    payloadInfo[1] == manifest.canvas.width && payloadInfo[2] == manifest.canvas.height) {
                    "Selection channel '${channel.name}' has invalid tile metadata"
                }
                val store = document.createSelectionChannelStore()
                if (store == 0L) throw IOException("Could not allocate selection channel '${channel.name}'")
                loadLayer(channel.payloadKey, store)?.let { message ->
                    destroyTileStore(store)
                    throw IOException(message)
                }
                document.restoreSelectionChannel(channel.name, store)
            }
            manifest.flatten().flatMap { listOfNotNull(it.smartObject?.sourceKey, it.hiddenPixels?.sourceKey) }.toSet().forEach { key ->
                val info = layerInfo(key) ?: throw IOException("Smart object source $key is missing")
                require(info.size == 3 && info[1] > 0 && info[2] > 0) { "Smart object source $key has invalid metadata" }
                val store = createTileStore(info[1], info[2], info[0])
                if (store == 0L) throw IOException("Could not allocate a smart object source")
                loadLayer(key, store)?.let { message ->
                    destroyTileStore(store)
                    throw IOException(message)
                }
                document.restoreSmartSource(key, store)
            }
            manifest.flatten().mapNotNull { it.smartObject?.raw }.distinctBy { it.fileKey }.forEach { raw ->
                embedded(raw.fileKey, "original.raw")?.let { document.restoreRawFile(raw.fileKey, it) }
            }
            manifest.flatten().mapNotNull { it.smartObject?.filterMask?.key }.toSet().forEach { key ->
                val info = layerInfo(key) ?: throw IOException("Smart filter mask $key is missing")
                require(info.size == 3 && info[1] > 0 && info[2] > 0) { "Smart filter mask $key has invalid metadata" }
                val store = document.createSelectionChannelStore()
                if (store == 0L) throw IOException("Could not allocate a smart filter mask")
                loadLayer(key, store)?.let { message ->
                    destroyTileStore(store)
                    throw IOException(message)
                }
                document.restoreFilterMask(key, store)
            }
            document.restoreLayerStyles()
            runCatching {
                app.croptop.core.engine.api.DocumentGuides(manifest.guides, manifest.grid ?: app.croptop.core.model.GridSettings())
            }.getOrNull()?.let(document::restoreDocumentGuides)
            document.finishArchiveOpen()
            synchronized(this@NativeEngine) {
                check(!closed) { "Engine was closed while opening project" }
                documents += document
            }
            document
        } catch (failure: Throwable) {
            document.close()
            throw failure
        }
    }

    // ---- Crash recovery: one journal directory per modified document under filesDir ----

    internal val recoveryRoot: File? = applicationContext?.let { File(it.filesDir, "croptop/recovery") }
    private val liveJournals: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    internal val autosaveTimer: java.util.concurrent.ScheduledExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "croptop-autosave").apply { isDaemon = true } }
    }

    /** Marks a journal as owned by an open document, so it is neither listed nor discarded. */
    internal fun claimJournal(name: String): Boolean = liveJournals.add(name)
    internal fun releaseJournal(name: String) {
        liveJournals.remove(name)
    }

    /** RAW files of RAW smart objects for journal [id]; kept beside, not inside, the native journal directory. */
    internal fun recoveryRawDir(id: String): File? = recoveryRoot?.parentFile?.let { File(it, "recovery-raw/$id") }

    private fun journalDir(id: String): File {
        val root = recoveryRoot ?: throw UnsupportedOperationException("Crash recovery needs an Android Context")
        require(JOURNAL_NAME.matches(id)) { "Invalid recovery id" }
        return File(root, id)
    }

    override fun recoverableDocuments(): List<app.croptop.core.engine.api.RecoverableDocument> {
        val root = recoveryRoot ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        val dirs = NativeRecoveryBridge.nativeList(root.absolutePath) ?: return emptyList()
        return dirs.mapNotNull { path ->
            val name = File(path).name
            if (name in liveJournals || !JOURNAL_NAME.matches(name)) return@mapNotNull null
            val reader = NativeRecoveryBridge.nativeOpen(path)
            if (reader == 0L) return@mapNotNull null
            try {
                val meta = NativeRecoveryBridge.nativeMetadata(reader)?.let(app.croptop.core.io.RecoveryMetadata::decodeOrNull)
                    ?: return@mapNotNull null
                app.croptop.core.engine.api.RecoverableDocument(name, meta.documentName, DocSize(meta.width, meta.height), meta.lastEditEpochMs, meta.sourceUri)
            } finally {
                NativeRecoveryBridge.nativeClose(reader)
            }
        }.sortedByDescending { it.lastEditEpochMs }
    }

    override suspend fun recover(id: String): DocumentHandle = withContext(Dispatchers.IO) {
        val dir = journalDir(id)
        check(claimJournal(id)) { "This document is already open" }
        val document = try {
            val reader = NativeRecoveryBridge.nativeOpen(dir.absolutePath)
            if (reader == 0L) throw IOException("Recovery data is missing or damaged")
            try {
                val meta = NativeRecoveryBridge.nativeMetadata(reader)?.let(app.croptop.core.io.RecoveryMetadata::decodeOrNull)
                openProject(
                    NativeRecoveryBridge.nativeManifest(reader),
                    layerInfo = { uuid -> NativeRecoveryBridge.nativeLayerInfo(reader, uuid) },
                    loadLayer = { uuid, store -> NativeRecoveryBridge.nativeLoadLayer(reader, uuid, store) },
                    workingIcc = NativeRecoveryBridge.nativeWorkingIcc(reader),
                    requireEmbeddedProfile = true,
                ).also {
                    it.adoptRecovery(meta?.sourceUri)
                    recoveryRawDir(id)?.let(it::restoreRawFilesFrom)
                }
            } finally {
                NativeRecoveryBridge.nativeClose(reader)
            }
        } catch (failure: Throwable) {
            releaseJournal(id)
            throw failure
        }
        // The recovered document journals into a fresh directory (reusing the old one could overwrite tile files
        // its last good commit references); the old journal goes once the new one is durable.
        if (document.autosaveNow(flushTimeoutMs = 30_000L) && NativeRecoveryBridge.nativeDiscardDir(dir.absolutePath) == null) {
            releaseJournal(id)
            recoveryRawDir(id)?.deleteRecursively()
        }
        document
    }

    override fun discardRecovery(id: String): Boolean {
        if (id in liveJournals) return false
        val dir = runCatching { journalDir(id) }.getOrNull() ?: return false
        recoveryRawDir(id)?.deleteRecursively()
        return NativeRecoveryBridge.nativeDiscardDir(dir.absolutePath) == null
    }

    /** A bounded display-resolution preview; the editable source remains in native tiles. */
    private fun decodeViewportPreview(bytes: ByteArray, length: Int, width: Int, height: Int, orientation: Int): Bitmap? {
        var sample = 1
        while (maxOf(width, height) / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, length, options) ?: return null
        if (orientation !in 2..8) return decoded
        val w = decoded.width.toFloat()
        val h = decoded.height.toFloat()
        val matrix = Matrix().apply {
            setValues(
                when (orientation) {
                    2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
                    3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
                    4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
                    5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                    6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
                    7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
                    else -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
                },
            )
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    private data class EncodedInput(val bytes: ByteArray, val size: Int)

    /** Reads a neighboring XMP recipe where the source provider exposes sibling documents. */
    private fun readRawSidecar(source: DocumentSource): String? {
        val sidecarBytes = when (source) {
            is DocumentSource.AppFile -> {
                val image = File(source.path).canonicalFile
                val context = applicationContext ?: return null
                val roots = listOf(context.filesDir.canonicalPath, context.cacheDir.canonicalPath)
                require(roots.any { image.path.startsWith("$it/") }) { "AppFile must be inside app-private storage" }
                val sidecar = File(image.parentFile, "${image.nameWithoutExtension}.xmp")
                if (!sidecar.isFile) return null
                require(sidecar.length() <= MAX_RAW_SIDECAR_BYTES) { "RAW XMP sidecar exceeds the 16 MiB limit" }
                sidecar.inputStream().use(::readBoundedRawSidecar)
            }

            is DocumentSource.ContentUri -> readTreeRawSidecar(Uri.parse(source.value)) ?: return null
        }
        return sidecarBytes.toString(Charsets.UTF_8)
    }

    /** SAF sibling lookup is available for tree URIs; single-document grants cannot enumerate a parent. */
    private fun readTreeRawSidecar(imageUri: Uri): ByteArray? {
        val context = applicationContext ?: return null
        if (!android.provider.DocumentsContract.isTreeUri(imageUri)) return null
        val imageName = runCatching {
            context.contentResolver.query(imageUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: imageUri.lastPathSegment ?: return null
        val sidecarName = "${imageName.substringBeforeLast('.', imageName)}.xmp"
        val parentId = runCatching {
            val path = android.provider.DocumentsContract.findDocumentPath(context.contentResolver, imageUri) ?: return null
            if (path.path.size < 2) return null
            path.path[path.path.lastIndex - 1]
        }.getOrNull() ?: return null
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(imageUri, parentId)
        val projection = arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val childId = context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idColumn < 0 || nameColumn < 0) return@use null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn).equals(sidecarName, ignoreCase = true)) return@use cursor.getString(idColumn)
            }
            null
        } ?: return null
        val sidecarUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(imageUri, childId)
        val size = context.contentResolver.query(sidecarUri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
        require(size in 0..MAX_RAW_SIDECAR_BYTES) { "RAW XMP sidecar exceeds the 16 MiB limit" }
        val input = context.contentResolver.openInputStream(sidecarUri) ?: throw FileNotFoundException("Could not open RAW XMP sidecar")
        return input.use(::readBoundedRawSidecar)
    }

    /** Writes or replaces the matching XMP sibling only when this URI carries a SAF tree grant. */
    private fun writeTreeRawSidecar(imageUri: Uri, bytes: ByteArray): Long {
        val context = applicationContext ?: throw IllegalStateException("NativeEngine needs Context to export a SAF RAW sidecar")
        if (!android.provider.DocumentsContract.isTreeUri(imageUri)) {
            throw UnsupportedOperationException("Choose the RAW from a writable SAF folder to export its XMP sidecar")
        }
        val imageName = context.contentResolver.query(
            imageUri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: throw FileNotFoundException("Could not read the RAW filename from its SAF provider")
        val sidecarName = "${imageName.substringBeforeLast('.', imageName)}.xmp"
        val documentPath = android.provider.DocumentsContract.findDocumentPath(context.contentResolver, imageUri)
            ?: throw FileNotFoundException("Could not find the RAW parent folder in its SAF tree")
        if (documentPath.path.size < 2) throw IOException("RAW source has no parent folder in its SAF tree")
        val parentId = documentPath.path[documentPath.path.lastIndex - 1]
        val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(imageUri, parentId)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(imageUri, parentId)
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val existingId = context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idColumn < 0 || nameColumn < 0) return@use null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn).equals(sidecarName, ignoreCase = true)) return@use cursor.getString(idColumn)
            }
            null
        }
        val resolver = context.contentResolver
        val existingUri = existingId?.let { android.provider.DocumentsContract.buildDocumentUriUsingTree(imageUri, it) }
        val destination = existingUri ?: android.provider.DocumentsContract.createDocument(
            resolver,
            parentUri,
            "application/rdf+xml",
            sidecarName,
        ) ?: throw IOException("SAF provider could not create the RAW XMP sidecar")
        try {
            val output = resolver.openOutputStream(destination, if (existingUri != null) "wt" else "w")
                ?: throw FileNotFoundException("SAF provider could not open the RAW XMP sidecar for writing")
            output.use {
                it.write(bytes)
                it.flush()
            }
        } catch (failure: Throwable) {
            if (existingUri == null) runCatching { android.provider.DocumentsContract.deleteDocument(resolver, destination) }
            throw failure
        }
        return bytes.size.toLong()
    }

    private fun readBoundedRawSidecar(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_RAW_SIDECAR_BYTES) { "RAW XMP sidecar exceeds the 16 MiB limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Keeps ByteArrayOutputStream's grown backing array; JNI receives its valid length separately. */
    private class EncodedInputBuffer : ByteArrayOutputStream() {
        fun finish() = EncodedInput(buf, count)
    }

    private suspend fun readEncodedSource(source: DocumentSource): EncodedInput = withContext(Dispatchers.IO) {
        val stream = when (source) {
            is DocumentSource.ContentUri -> {
                val context = applicationContext ?: throw IllegalStateException("NativeEngine needs Context to open SAF URIs")
                context.contentResolver.openInputStream(Uri.parse(source.value))
                    ?: throw FileNotFoundException("Could not open image URI")
            }

            is DocumentSource.AppFile -> {
                val context = applicationContext ?: throw IllegalStateException("NativeEngine needs Context to open app files")
                val file = File(source.path).canonicalFile
                val roots = listOf(context.filesDir.canonicalPath, context.cacheDir.canonicalPath)
                require(roots.any { file.path.startsWith("$it/") }) { "AppFile must be inside app-private storage" }
                file.inputStream()
            }
        }
        stream.use { input ->
            val output = EncodedInputBuffer()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_ENCODED_IMAGE_BYTES) throw IOException("Encoded image exceeds the 128 MiB input limit")
                output.write(buffer, 0, read)
            }
            output.finish()
        }
    }

    internal fun writeExport(target: ExportTarget, write: (OutputStream) -> Long): Long = when (target) {
        is ExportTarget.ContentUri -> {
            val context = applicationContext ?: throw IllegalStateException("NativeEngine needs Context to export SAF URIs")
            val uri = Uri.parse(target.value)
            val resolver = context.contentResolver
            // Encode to app storage first and keep the old bytes aside, so a failed write can't leave a half-written file.
            SafeReplace.replace(
                workDir = File(context.cacheDir, "saving"),
                name = uri.lastPathSegment.orEmpty(),
                encode = write,
                readCurrent = { runCatching { resolver.openInputStream(uri) }.getOrNull() },
                openDestination = {
                    runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: resolver.openOutputStream(uri, "w")
                        ?: throw FileNotFoundException("Could not open export URI")
                },
                sync = { output -> (output as? FileOutputStream)?.let { runCatching { it.fd.sync() } } },
            )
        }

        is ExportTarget.AppFile -> {
            val context = applicationContext ?: throw IllegalStateException("NativeEngine needs Context to export app files")
            val file = File(target.path).canonicalFile
            val roots = listOf(context.filesDir.canonicalPath, context.cacheDir.canonicalPath)
            require(roots.any { file.path.startsWith("$it/") }) { "AppFile must be inside app-private storage" }
            val parent = file.parentFile ?: throw IOException("Export target has no parent directory")
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create export directory")
            val temporary = Files.createTempFile(parent.toPath(), "${file.name}.", ".tmp").toFile()
            try {
                val bytes = FileOutputStream(temporary).use { output ->
                    val written = write(output)
                    output.fd.sync()
                    written
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                bytes
            } finally {
                temporary.delete()
            }
        }
    }

    internal fun writeSave(target: SaveTarget, write: (OutputStream) -> Long): Long = when (target) {
        is SaveTarget.ContentUri -> writeExport(ExportTarget.ContentUri(target.value), write)
        is SaveTarget.AppFile -> writeExport(ExportTarget.AppFile(target.path), write)
    }

    @Synchronized
    override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
        check(!closed) { "Engine is closed" }
        val format = when (spec.depth.bitsPerChannel) {
            8 -> 0

            // RGBA8
            16 -> 1

            // RGBA16
            32 -> 2

            // RGBA32F
            else -> error("Unsupported color depth: ${spec.depth.bitsPerChannel}")
        }
        val background = LayerNode(
            id = LayerId(ids.getAndIncrement()),
            name = "Background",
            kind = LayerKind.PIXEL,
            // The background is a virtual canvas fill, so its sparse pixel layer stays paintable.
            // Position remains locked as in conventional Background layers; alpha is not locked,
            // allowing the eraser to reveal the chosen virtual fill without dense initialization.
            locks = if (spec.background == BackgroundContents.TRANSPARENT) LayerLocks.None else LayerLocks(position = true),
        )
        val tree = LayerTree(listOf(background), active = setOf(background.id), primary = background.id)
        val info = DocumentInfo(
            id = DocumentId(ids.getAndIncrement()),
            name = spec.name,
            size = spec.size,
            depth = spec.depth,
            profile = spec.profile,
            resolutionPpi = spec.resolutionPpi,
        )
        return NativeDocument(this, info, tree, spec.background, format, ::releaseDocument)
            .also { documents += it }
    }

    override fun setMemoryBudget(bytes: Long) {
        require(bytes > 0L) { "Memory budget must be positive" }
        check(!closed) { "Engine is closed" }
        nativeSetMemoryBudget(nativeMemoryBudget, bytes)
        currentMemoryBudgetBytes.set(bytes)
    }

    /** A one-layer document holding [store] (adopted), e.g. a smart object's contents. */
    internal fun documentFromStore(name: String, size: DocSize, depth: app.croptop.core.model.ColorDepth, profile: app.croptop.core.model.ColorProfileRef,
                                   resolutionPpi: Float, format: Int, store: Long): NativeDocument {
        check(!closed) { "Engine is closed" }
        val layer = LayerNode(id = LayerId(ids.getAndIncrement()), name = "Layer 1", kind = LayerKind.PIXEL)
        val tree = LayerTree(listOf(layer), active = setOf(layer.id), primary = layer.id)
        val info = DocumentInfo(id = DocumentId(ids.getAndIncrement()), name = name, size = size, depth = depth, profile = profile, resolutionPpi = resolutionPpi)
        val document = NativeDocument(this, info, tree, BackgroundContents.TRANSPARENT, format, ::releaseDocument)
        try {
            document.adoptPixelStore(layer.id, store)
            document.finishArchiveOpen()
        } catch (failure: Throwable) {
            document.close()
            throw failure
        }
        synchronized(this) { documents += document }
        return document
    }

    @Synchronized
    private fun releaseDocument(document: NativeDocument) {
        documents.remove(document)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        documents.toList().forEach { it.close() }
        synchronized(clipboardLock) {
            if (clipboardStore != 0L) destroyTileStore(clipboardStore)
            clipboardStore = 0L
            clipboardState.value = null
        }
        applicationContext?.unregisterComponentCallbacks(this)
        synchronized(rawLensProfileLock) {
            if (rawLensProfileDatabaseDelegate.isInitialized()) rawLensProfileDatabase?.close()
        }
        nativeDestroyMemoryBudget(nativeMemoryBudget)
    }

    @Deprecated("The Android memory callback is deprecated but retained for older platform versions.")
    override fun onTrimMemory(level: Int) {
        val fraction = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> 0.0
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 0.35
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> 0.75
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 0.25
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 0.5
            else -> return
        }
        trimMemoryTo((memoryBudgetBytes * fraction).toLong())
    }

    private fun trimMemoryTo(targetBytes: Long) {
        val openDocuments = synchronized(this) { documents.toList() }
        openDocuments.forEach(NativeDocument::trimCaches)
        nativeTrimMemoryBudget(nativeMemoryBudget, targetBytes)
    }

    @Deprecated("The Android memory callback is deprecated but retained for older platform versions.")
    override fun onLowMemory() = trimMemoryTo(0L)

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    internal fun createTileStore(width: Int, height: Int, format: Int): Long =
        nativeCreateTileStore(width, height, format, nativeMemoryBudget)

    internal fun promoteTileStore(handle: Long, outputFormat: Int, outputWidth: Int, outputHeight: Int): Long =
        nativePromoteTileStore(handle, outputFormat, outputWidth, outputHeight)

    internal fun layerViaCutStores(source: Long, selection: Long): LongArray? = nativeLayerViaCut(source, selection)

    internal fun layerViaCutError(): String = nativeLayerViaCutLastError()

    internal fun transformTileStore(handle: Long, operation: Int, width: Int, height: Int, dx: Int, dy: Int): Long =
        nativeGeometryTransformTileStore(handle, operation, width, height, dx, dy)

    internal fun resizeTileStore(handle: Long, width: Int, height: Int, filter: Int): Long =
        nativeGeometryTransformTileStore(handle, 4, width, height, filter, 0)

    internal fun cropStraightenGeometry(left: Int, top: Int, width: Int, height: Int, angleDegrees: Double): DoubleArray? =
        nativeGeometryStraightenCrop(left, top, width, height, angleDegrees)

    internal fun perspectiveCropGeometry(width: Int, height: Int, quad: CropQuad): DoubleArray? =
        nativeGeometryPerspectiveCrop(width, height, quad.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }.toDoubleArray())

    internal fun warpTileStore(handle: Long, width: Int, height: Int, matrix: DoubleArray, filter: Int): Long =
        nativeGeometryWarpTileStore(handle, width, height, matrix, filter)

    internal fun geometryError(): String = nativeGeometryLastError()

    internal fun renderShapeLayer(paths: List<app.croptop.core.model.VectorPathDraft>, size: DocSize): ShapeLayerBridgeResult<Long> =
        synchronized(this) {
            if (closed) return@synchronized ShapeLayerBridgeResult.Failure(RejectionCode.CLOSED, "Engine is closed")
            val encoded = when (val result = NativeShapeLayerBridge.encodeBatch(paths)) {
                is ShapeLayerBridgeResult.Success -> result.value
                is ShapeLayerBridgeResult.Failure -> return@synchronized result
            }
            NativeShapeRendererBridge.render(encoded, size.width, size.height, nativeMemoryBudget)
        }

    /** Creates an independent TileStore view whose existing tiles are shared until either view writes. */
    internal fun cloneTileStore(handle: Long): Long = nativeCloneTileStore(handle)

    /** Creates a bilinear reduced-size proxy that keeps the source TileStore's native budget. */
    internal fun resizeTileStore(handle: Long, width: Int, height: Int): Long = nativeResizeTileStore(handle, width, height)

    /** 0 = no Vulkan compute device, 1 = shader/pipeline mismatch, 2 = dispatch matches CPU. */
    internal fun runBlendComputeSmokeTest(): Int = nativeVulkanBlendSmoke()

    internal fun destroyTileStore(handle: Long) = nativeDestroyTileStore(handle)

    internal fun tileStoreResidentBytes(handle: Long): Long = nativeTileStoreResidentBytes(handle)

    internal fun trimTileStoreCaches(handle: Long) = nativeTrimTileStore(handle)

    internal fun translateTileStore(handle: Long, dx: Float, dy: Float): Boolean = nativeTranslateTileStore(handle, dx, dy)

    /** Creates a native incremental brush session; 0 means settings validation/allocation failed. */
    internal fun createBrushSession(settings: BrushSettings, width: Int, height: Int): Long =
        nativeCreateBrushSession(settings.nativeEncoding(), width, height)

    internal fun addBrushSamples(session: Long, samples: FloatArray): String? = nativeAddBrushSamples(session, samples)

    internal fun applyBrushSession(
        baseline: Long,
        store: Long,
        session: Long,
        color: RgbaColor,
        erase: Boolean,
        preserveAlpha: Boolean,
        selectionMask: Long,
    ): String? = nativeApplyBrushSession(
        baseline,
        store,
        session,
        floatArrayOf(color.r, color.g, color.b, color.a),
        erase,
        preserveAlpha,
        selectionMask,
    )

    internal fun applyRetouchSession(
        baseline: Long,
        store: Long,
        session: Long,
        sharpen: Boolean,
        strength: Float,
        protectDetail: Boolean,
        selectionMask: Long,
    ): String? = nativeApplyRetouchSession(baseline, store, session, sharpen, strength, protectDetail, selectionMask)

    internal fun applyToneRetouchSession(
        baseline: Long,
        store: Long,
        session: Long,
        burn: Boolean,
        range: Int,
        exposure: Float,
        protectTones: Boolean,
        selectionMask: Long,
    ): String? = nativeApplyToneRetouchSession(baseline, store, session, burn, range, exposure, protectTones, selectionMask)

    internal fun applySpongeRetouchSession(
        baseline: Long,
        store: Long,
        session: Long,
        saturate: Boolean,
        flow: Float,
        vibrance: Boolean,
        selectionMask: Long,
    ): String? = nativeApplySpongeRetouchSession(baseline, store, session, saturate, flow, vibrance, selectionMask)

    internal fun applySpotRetouchSession(
        source: Long,
        store: Long,
        session: Long,
        kind: Int,
        selectionMask: Long,
    ): String? = nativeApplySpotRetouchSession(source, store, session, kind, selectionMask)

    internal fun applySourceRetouchSession(
        source: Long,
        store: Long,
        session: Long,
        healing: Boolean,
        mergedSource: Boolean,
        point: DocPoint,
        selectionMask: Long,
        samePosition: Boolean = false,
    ): String? = nativeApplySourceRetouchSession(source, store, session, healing, mergedSource, point.x, point.y, selectionMask, samePosition)

    internal fun applyBackgroundEraserSession(
        store: Long,
        session: Long,
        effect: RetouchEffect.BackgroundErase,
        selectionMask: Long,
    ): String? = nativeApplyBackgroundEraserSession(
        store,
        session,
        effect.tolerance,
        effect.contiguous,
        effect.sampling.ordinal,
        effect.swatch?.let { floatArrayOf(it.r, it.g, it.b) },
        effect.protect?.let { floatArrayOf(it.r, it.g, it.b) },
        selectionMask,
    )

    internal fun applySmudgeRetouchSession(
        baseline: Long,
        store: Long,
        session: Long,
        strength: Float,
        fingerPainting: Boolean,
        selectionMask: Long,
    ): String? = nativeApplySmudgeRetouchSession(baseline, store, session, strength, fingerPainting, selectionMask)

    internal fun applyMaskBrushSession(
        baselineMask: Long,
        targetMask: Long,
        emptyCoverage: Float,
        session: Long,
        value: Float,
        colorAlpha: Float,
        erase: Boolean,
        selectionMask: Long,
    ): String? = nativeApplyMaskBrushSession(
        baselineMask, targetMask, emptyCoverage, session, value, colorAlpha, erase, selectionMask,
    )

    internal fun destroyBrushSession(session: Long) {
        if (session != 0L) nativeDestroyBrushSession(session)
    }

    /** The compact native value block for [adjustment] (see adjustment_lut_bridge.h), or the reason it has none. */
    internal fun encodeAdjustmentBlock(adjustment: AdjustmentInfo): Result<FloatArray> {
        val spec = AdjustmentCatalog[adjustment.specId] ?: return Result.failure(IllegalArgumentException("Unknown adjustment: ${adjustment.specId}"))
        val values = spec.normalize(adjustment.values)
        fun number(key: String) = (values[key] as? ParamValue.Num)?.v ?: 0f
        fun bool(key: String) = (values[key] as? ParamValue.Bool)?.v ?: false
        fun choice(key: String) = (values[key] as? ParamValue.Choice)?.v.orEmpty()
        fun color(key: String) = (values[key] as? ParamValue.Color)?.v ?: RgbaColor.Black
        val block = when (adjustment.specId) {
            "brightnessContrast" -> floatArrayOf(
                number("brightness"),
                number("contrast"),
                if ((values["legacy"] as? ParamValue.Bool)?.v == true) 1f else 0f,
            )

            "exposure" -> floatArrayOf(number("exposure"), number("offset"), number("gamma"))

            "vibrance" -> floatArrayOf(number("vibrance"), number("saturation"))

            "invert" -> floatArrayOf()

            "posterize" -> floatArrayOf(number("levels"))

            "threshold" -> floatArrayOf(number("level"))

            "levels" -> {
                val levels = (values["levels"] as? ParamValue.LevelsValue)?.v
                    ?: return Result.failure(IllegalArgumentException("Levels parameters are missing"))
                floatArrayOf(levels.inBlack, levels.inWhite, levels.gamma, levels.outBlack, levels.outWhite)
            }

            "curves" -> {
                val channels = (values["curve"] as? ParamValue.CurveChannelsValue)?.v
                    ?: (values["curveChannels"] as? ParamValue.CurveChannelsValue)?.v
                    ?: return Result.failure(IllegalArgumentException("Curve channels are missing"))
                buildList {
                    listOf(channels.rgb, channels.red, channels.green, channels.blue).forEach { curve ->
                        add(curve.points.size.toFloat())
                        curve.points.forEach { point ->
                            add(point.x)
                            add(point.y)
                        }
                    }
                }.toFloatArray()
            }

            "hueSaturation" -> {
                val hueSat = (values["hueSat"] as? ParamValue.HueSatValue)?.v
                    ?: return Result.failure(IllegalArgumentException("Hue/Saturation parameters are missing"))
                buildList {
                    add(hueSat.master.hue)
                    add(hueSat.master.saturation)
                    add(hueSat.master.lightness)
                    add(if (hueSat.colorize) 1f else 0f)
                    app.croptop.core.model.HueChannel.entries.drop(1).forEach { channel ->
                        val shift = hueSat.shift(channel)
                        add(shift.hue)
                        add(shift.saturation)
                        add(shift.lightness)
                    }
                    app.croptop.core.model.HueChannel.entries.drop(1).forEach { channel ->
                        val range = checkNotNull(hueSat.range(channel))
                        add(range.outerStart)
                        add(range.innerStart)
                        add(range.innerEnd)
                        add(range.outerEnd)
                    }
                }.toFloatArray()
            }

            "colorBalance" -> {
                val balance = (values["balance"] as? ParamValue.BalanceValue)?.v
                    ?: return Result.failure(IllegalArgumentException("Color Balance parameters are missing"))
                buildList {
                    listOf(balance.shadows, balance.midtones, balance.highlights).forEach { shift ->
                        add(shift.cyanRed)
                        add(shift.magentaGreen)
                        add(shift.yellowBlue)
                    }
                    add(if (balance.preserveLuminosity) 1f else 0f)
                }.toFloatArray()
            }

            "blackWhite" -> {
                val tint = color("tintColor")
                floatArrayOf(
                    number("reds"), number("yellows"), number("greens"), number("cyans"), number("blues"), number("magentas"),
                    if (bool("tint")) 1f else 0f, tint.r, tint.g, tint.b,
                )
            }

            "photoFilter" -> {
                val filterColor = color("color")
                val filterIndex = when (choice("filter")) {
                    "warming85" -> 0f
                    "cooling80" -> 1f
                    "sepia" -> 2f
                    else -> 3f
                }
                floatArrayOf(
                    filterIndex,
                    filterColor.r,
                    filterColor.g,
                    filterColor.b,
                    number("density"),
                    if (bool("preserveLuminosity")) 1f else 0f,
                )
            }

            "channelMixer" -> {
                val output = when (choice("output")) {
                    "green" -> 1f
                    "blue" -> 2f
                    else -> 0f
                }
                floatArrayOf(
                    output,
                    number("red"),
                    number("green"),
                    number("blue"),
                    number("constant"),
                    if (bool("monochrome")) 1f else 0f,
                )
            }

            "gradientMap" -> {
                val gradient = (values["gradient"] as? ParamValue.GradientValue)?.v
                    ?: return Result.failure(IllegalArgumentException("Gradient Map parameters are missing"))
                buildList {
                    add(if (bool("reverse")) 1f else 0f)
                    add(if (bool("dither")) 1f else 0f)
                    add(gradient.stops.size.toFloat())
                    gradient.stops.sortedBy { it.position }.forEach { stop ->
                        add(stop.position)
                        add(stop.color.r)
                        add(stop.color.g)
                        add(stop.color.b)
                        add(stop.color.a)
                    }
                }.toFloatArray()
            }

            "selectiveColor" -> {
                val range = listOf("reds", "yellows", "greens", "cyans", "blues", "magentas", "whites", "neutrals", "blacks")
                    .indexOf(choice("colors")).takeIf { it >= 0 } ?: 0
                floatArrayOf(
                    range.toFloat(),
                    number("cyan"),
                    number("magenta"),
                    number("yellow"),
                    number("black"),
                    if (choice("method") == "absolute") 1f else 0f,
                )
            }

            "shadowsHighlights" -> floatArrayOf(number("shadows"), number("highlights"))

            else -> return Result.failure(UnsupportedOperationException("This adjustment is not supported by the native engine"))
        }
        return Result.success(block)
    }

    internal fun applyAdjustmentToTileStore(handle: Long, adjustment: AdjustmentInfo, selectionMask: Long = 0L): String? {
        val block = encodeAdjustmentBlock(adjustment).getOrElse { return it.message }
        return nativeApplyAdjustment(handle, adjustment.specId, block, selectionMask)
    }

    /** Bakes [adjustment] into a LUT attached to [handle] (an adjustment layer's store); null on success. */
    internal fun setAdjustmentLayerLut(handle: Long, adjustment: AdjustmentInfo): String? {
        val block = encodeAdjustmentBlock(adjustment).getOrElse { return it.message }
        return nativeSetAdjustmentLayerLut(handle, adjustment.specId, block)
    }

    internal fun renderCompositePreview(
        width: Int,
        height: Int,
        layers: List<NativeRenderLayer>,
        maxDimension: Int = MAX_VIEWPORT_PREVIEW_DIMENSION,
        backgroundColor: RgbaColor = RgbaColor.Transparent,
        cancellationId: Long = 0L,
        renderSession: Long = 0L,
    ): Bitmap? {
        if (width <= 0 || height <= 0 || layers.isEmpty()) return null
        val mipLevel = viewportPreviewMipLevel(width, height, maxDimension)
        var previewWidth = width
        var previewHeight = height
        repeat(mipLevel) {
            previewWidth = (previewWidth + 1) / 2
            previewHeight = (previewHeight + 1) / 2
        }
        val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val rendered = nativeRenderCompositePreview(
            layers.map(NativeRenderLayer::store).toLongArray(),
            layers.map(NativeRenderLayer::id).toLongArray(),
            layers.map(NativeRenderLayer::blendMode).toIntArray(),
            layers.map(NativeRenderLayer::opacity).toIntArray(),
            layers.map(NativeRenderLayer::fillOpacity).toIntArray(),
            layers.map(NativeRenderLayer::visible).toBooleanArray(),
            floatArrayOf(backgroundColor.r, backgroundColor.g, backgroundColor.b, backgroundColor.a),
            mipLevel,
            0,
            0,
            bitmap,
            renderSession,
            cancellationId,
        )
        if (rendered) return bitmap
        bitmap.recycle()
        return null
    }

    internal fun renderCompositeRegion(
        width: Int,
        height: Int,
        layers: List<NativeRenderLayer>,
        region: app.croptop.core.model.DocRect,
        backgroundColor: RgbaColor,
    ): Bitmap? {
        if (width <= 0 || height <= 0 || layers.isEmpty() || region.left < 0 || region.top < 0 ||
            region.right > width || region.bottom > height || region.isEmpty
        ) return null
        val bitmap = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
        val rendered = nativeRenderCompositePreview(
            layers.map(NativeRenderLayer::store).toLongArray(),
            layers.map(NativeRenderLayer::id).toLongArray(),
            layers.map(NativeRenderLayer::blendMode).toIntArray(),
            layers.map(NativeRenderLayer::opacity).toIntArray(),
            layers.map(NativeRenderLayer::fillOpacity).toIntArray(),
            layers.map(NativeRenderLayer::visible).toBooleanArray(),
            floatArrayOf(backgroundColor.r, backgroundColor.g, backgroundColor.b, backgroundColor.a),
            0,
            region.left,
            region.top,
            bitmap,
            0L,
            0L,
        )
        if (rendered) return bitmap
        bitmap.recycle()
        return null
    }

    internal fun createRenderCancellation(): Long = nativeCreateRenderCancellation()

    internal fun createCompositeRenderSession(): Long = nativeCreateCompositeRenderSession()

    internal fun destroyCompositeRenderSession(session: Long) = nativeDestroyCompositeRenderSession(session)

    internal fun cancelRender(cancellationId: Long) = nativeCancelRender(cancellationId)

    internal fun destroyRenderCancellation(cancellationId: Long) = nativeDestroyRenderCancellation(cancellationId)

    /** Full-resolution flatten of [layers] (bottom to top) into a new store the caller owns; throws on failure. */
    internal fun flattenLayers(layers: List<NativeRenderLayer>, backgroundColor: RgbaColor = RgbaColor.Transparent): Long {
        require(layers.isNotEmpty()) { "There are no visible pixels to flatten" }
        val handle = nativeFlattenLayers(
            layers.map(NativeRenderLayer::store).toLongArray(),
            layers.map(NativeRenderLayer::id).toLongArray(),
            layers.map(NativeRenderLayer::blendMode).toIntArray(),
            layers.map(NativeRenderLayer::opacity).toIntArray(),
            layers.map(NativeRenderLayer::fillOpacity).toIntArray(),
            layers.map(NativeRenderLayer::visible).toBooleanArray(),
            floatArrayOf(backgroundColor.r, backgroundColor.g, backgroundColor.b, backgroundColor.a),
        )
        check(handle != 0L) { nativeFlattenLastError().ifBlank { "Could not flatten the layers" } }
        return handle
    }

    /** viewport mask display on a composed preview: mode 0 solo grayscale, 1 colour over unselected areas. */
    internal fun applyMaskOverlay(bitmap: Bitmap, mask: Long, mode: Int, color: RgbaColor): Boolean =
        nativeApplyMaskOverlay(bitmap, mask, mode, color.r, color.g, color.b, color.a)

    /**
     * Renders [text] into a new store shaped like [template] at [ppi]; returns the handle and the text's pixel
     * bounds, or throws with the native reason.
     */
    internal fun renderTextLayer(template: Long, text: app.croptop.core.model.TextLayerInfo, ppi: Float): Pair<Long, DocRect> {
        val c = text.character.normalized()
        val pxPerPt = (if (ppi.isFinite() && ppi > 0f) ppi else 72f) / 72f
        val sizePx = (c.sizePt * pxPerPt).coerceIn(1f, 4000f)
        val align = when (text.paragraph.align) {
            app.croptop.core.model.TextAlign.LEFT -> 0
            app.croptop.core.model.TextAlign.CENTER -> 1
            app.croptop.core.model.TextAlign.RIGHT -> 2
            app.croptop.core.model.TextAlign.JUSTIFY_ALL -> 4
            else -> 3
        }
        val paragraph = text.kind == app.croptop.core.model.TextKind.PARAGRAPH && text.boxWidth > 0f
        // Point text's origin is the first baseline: move up by the ascent (≈ 0.8 em) to the box top.
        val top = if (paragraph) text.origin.y else text.origin.y - sizePx * 0.8f
        val fonts = NativeFonts.resolve(c.family, c.style)
        require(fonts.isNotEmpty()) { "No fonts are installed" }
        val result = nativeRenderTextLayer(
            template, fonts.toTypedArray(), text.text, sizePx, c.leadingPt?.let { it * pxPerPt } ?: 0f, c.tracking.toFloat(), align,
            if (paragraph) text.boxWidth else 0f, text.origin.x, top, c.color.r, c.color.g, c.color.b, c.color.a,
            c.axes.keys.toTypedArray(), c.axes.values.toFloatArray(),
        ) ?: throw IllegalArgumentException(nativeTextLastError().ifBlank { "Could not render the text" })
        return result[0] to DocRect(result[1].toInt(), result[2].toInt(), result[3].toInt(), result[4].toInt())
    }

    internal fun decodeToTileStore(encoded: ByteArray, size: Int, handle: Long, codec: Int): String? =
        nativeDecodeToTileStore(encoded, size, handle, codec, 1)

    internal fun encodePngToStream(
        handle: Long,
        output: OutputStream,
        compressionLevel: Int,
        iccProfile: ByteArray?,
        exifTiff: ByteArray?,
        backgroundColor: RgbaColor = RgbaColor.Transparent,
    ): Long = nativeEncodePngToStream(
        handle,
        output,
        compressionLevel,
        iccProfile,
        exifTiff,
        floatArrayOf(backgroundColor.r, backgroundColor.g, backgroundColor.b, backgroundColor.a),
    )

    internal fun encodeJpegToStream(
        handle: Long,
        output: OutputStream,
        quality: Int,
        iccProfile: ByteArray?,
        exifTiff: ByteArray?,
        backgroundColor: RgbaColor = RgbaColor.White,
    ): Long = nativeEncodeJpegToStream(
        handle,
        output,
        quality,
        iccProfile,
        exifTiff,
        floatArrayOf(backgroundColor.r, backgroundColor.g, backgroundColor.b, backgroundColor.a),
    )

    internal fun encodeWebpToStream(
        handle: Long,
        output: OutputStream,
        quality: Int,
        iccProfile: ByteArray?,
        exifTiff: ByteArray?,
        backgroundColor: RgbaColor = RgbaColor.Transparent,
    ): Long = nativeEncodeWebpToStream(
        handle,
        output,
        quality,
        currentMemoryBudgetBytes.get(),
        iccProfile,
        exifTiff,
        floatArrayOf(backgroundColor.r, backgroundColor.g, backgroundColor.b, backgroundColor.a),
    )

    internal fun saveCroptop(
        stores: LongArray,
        layerUuids: Array<String>,
        manifestJson: ByteArray,
        workingIcc: ByteArray?,
        output: OutputStream,
        zstdLevel: Int,
        workerThreads: Int,
        embedded: List<EmbeddedFile> = emptyList(),
    ): Long = nativeSaveCroptop(
        stores, layerUuids, manifestJson, workingIcc, output, zstdLevel, workerThreads,
        embedded.map { it.id }.toTypedArray(), embedded.map { it.name }.toTypedArray(), embedded.map { it.bytes }.toTypedArray(),
    )

    /** A file stored verbatim as `embedded/<id>/<name>` in a `.croptop` archive. */
    internal class EmbeddedFile(val id: String, val name: String, val bytes: ByteArray)

    internal fun nativeCroptopEmbedded(handle: Long, id: String, name: String): ByteArray? = nativeCroptopEmbeddedNative(handle, id, name)

    internal fun nativeOpenCroptopArchive(path: String): Long = nativeOpenCroptopArchiveNative(path)
    internal fun nativeCloseCroptopArchive(handle: Long) = nativeCloseCroptopArchiveNative(handle)
    internal fun nativeCroptopManifest(handle: Long): ByteArray? = nativeCroptopManifestNative(handle)
    internal fun nativeCroptopWorkingIcc(handle: Long): ByteArray? = nativeCroptopWorkingIccNative(handle)
    internal fun nativeCroptopLayerInfo(handle: Long, uuid: String): IntArray? = nativeCroptopLayerInfoNative(handle, uuid)
    internal fun nativeLoadCroptopLayer(handle: Long, uuid: String, store: Long): String? =
        nativeLoadCroptopLayerNative(handle, uuid, store)

    internal fun samplePixels(
        layers: List<NativeRenderLayer>,
        mode: SampleMode,
        primaryLayerId: Long?,
        x: Int,
        y: Int,
        size: Int,
        backgroundColor: RgbaColor = RgbaColor.Transparent,
    ): RgbaColor? {
        val rgba = nativeSamplePixels(
            layers.map(NativeRenderLayer::store).toLongArray(),
            layers.map(NativeRenderLayer::id).toLongArray(),
            layers.map(NativeRenderLayer::blendMode).toIntArray(),
            layers.map(NativeRenderLayer::opacity).toIntArray(),
            layers.map(NativeRenderLayer::fillOpacity).toIntArray(),
            layers.map(NativeRenderLayer::visible).toBooleanArray(),
            floatArrayOf(backgroundColor.r, backgroundColor.g, backgroundColor.b, backgroundColor.a),
            mode.ordinal,
            primaryLayerId ?: 0L,
            x,
            y,
            size,
        ) ?: return null
        if (rgba.size != 4) return null
        return RgbaColor(rgba[0], rgba[1], rgba[2], rgba[3])
    }

    internal fun decodeToTileStore(encoded: ByteArray, size: Int, handle: Long, codec: Int, exifOrientation: Int): String? =
        nativeDecodeToTileStore(encoded, size, handle, codec, exifOrientation)

    private external fun nativeInfo(): String
    private external fun nativeCreateRenderCancellation(): Long

    private external fun nativeCreateCompositeRenderSession(): Long

    private external fun nativeDestroyCompositeRenderSession(session: Long)
    private external fun nativeCancelRender(cancellationId: Long)
    private external fun nativeDestroyRenderCancellation(cancellationId: Long)
    private external fun nativeCreateMemoryBudget(scratchPath: String, budgetBytes: Long): Long
    private external fun nativeDestroyMemoryBudget(handle: Long)
    private external fun nativeSetMemoryBudget(handle: Long, budgetBytes: Long)
    private external fun nativeTrimMemoryBudget(handle: Long, targetBytes: Long)
    private external fun nativeCreateTileStore(width: Int, height: Int, format: Int, memoryBudget: Long): Long
    private external fun nativePromoteTileStore(handle: Long, outputFormat: Int, outputWidth: Int, outputHeight: Int): Long
    private external fun nativeLayerViaCut(source: Long, selection: Long): LongArray?
    private external fun nativeLayerViaCutLastError(): String
    private external fun nativeCloneTileStore(handle: Long): Long
    private external fun nativeResizeTileStore(handle: Long, width: Int, height: Int): Long
    private external fun nativeGeometryTransformTileStore(handle: Long, operation: Int, width: Int, height: Int, dx: Int, dy: Int): Long
    private external fun nativeGeometryStraightenCrop(left: Int, top: Int, width: Int, height: Int, angleDegrees: Double): DoubleArray?
    private external fun nativeGeometryPerspectiveCrop(width: Int, height: Int, quad: DoubleArray): DoubleArray?
    private external fun nativeGeometryWarpTileStore(handle: Long, width: Int, height: Int, matrix: DoubleArray, filter: Int): Long
    private external fun nativeGeometryLastError(): String
    private external fun nativeVulkanBlendSmoke(): Int
    private external fun nativeDestroyTileStore(handle: Long)
    private external fun nativeTileStoreResidentBytes(handle: Long): Long
    private external fun nativeTrimTileStore(handle: Long)
    private external fun nativeTranslateTileStore(handle: Long, dx: Float, dy: Float): Boolean
    private external fun nativeCreateBrushSession(settings: FloatArray, width: Int, height: Int): Long
    private external fun nativeAddBrushSamples(session: Long, samples: FloatArray): String?
    private external fun nativeApplyBrushSession(
        baseline: Long,
        store: Long,
        session: Long,
        color: FloatArray,
        erase: Boolean,
        preserveAlpha: Boolean,
        selectionMask: Long,
    ): String?
    private external fun nativeApplyRetouchSession(
        baseline: Long,
        store: Long,
        session: Long,
        sharpen: Boolean,
        strength: Float,
        protectDetail: Boolean,
        selectionMask: Long,
    ): String?
    private external fun nativeApplyToneRetouchSession(
        baseline: Long,
        store: Long,
        session: Long,
        burn: Boolean,
        range: Int,
        exposure: Float,
        protectTones: Boolean,
        selectionMask: Long,
    ): String?
    private external fun nativeApplySpongeRetouchSession(
        baseline: Long,
        store: Long,
        session: Long,
        saturate: Boolean,
        flow: Float,
        vibrance: Boolean,
        selectionMask: Long,
    ): String?
    internal fun recordRetouchDabs(session: Long): String? = nativeRecordRetouchDabs(session)

    private external fun nativeRecordRetouchDabs(session: Long): String?
    private external fun nativeApplySpotRetouchSession(
        source: Long,
        store: Long,
        session: Long,
        kind: Int,
        selectionMask: Long,
    ): String?

    private external fun nativeApplySourceRetouchSession(
        source: Long,
        store: Long,
        session: Long,
        healing: Boolean,
        mergedSource: Boolean,
        sourceX: Float,
        sourceY: Float,
        selectionMask: Long,
        samePosition: Boolean,
    ): String?

    private external fun nativeApplyBackgroundEraserSession(
        store: Long,
        session: Long,
        tolerance: Float,
        contiguous: Boolean,
        sampling: Int,
        swatch: FloatArray?,
        protect: FloatArray?,
        selectionMask: Long,
    ): String?

    private external fun nativeApplySmudgeRetouchSession(
        baseline: Long,
        store: Long,
        session: Long,
        strength: Float,
        fingerPainting: Boolean,
        selectionMask: Long,
    ): String?
    private external fun nativeApplyMaskBrushSession(
        baselineMask: Long,
        targetMask: Long,
        emptyCoverage: Float,
        session: Long,
        value: Float,
        colorAlpha: Float,
        erase: Boolean,
        selectionMask: Long,
    ): String?
    private external fun nativeDestroyBrushSession(session: Long)
    private external fun nativeApplyAdjustment(handle: Long, specId: String, values: FloatArray, selectionMask: Long): String?
    private external fun nativeSetAdjustmentLayerLut(handle: Long, specId: String, values: FloatArray): String?
    private external fun nativeFlattenLayers(
        stores: LongArray,
        layerIds: LongArray,
        blendModes: IntArray,
        opacities: IntArray,
        fillOpacities: IntArray,
        visibility: BooleanArray,
        backgroundColor: FloatArray,
    ): Long
    private external fun nativeFlattenLastError(): String
    private external fun nativeImportPsd(encoded: ByteArray, size: Int, templateStore: Long): Long
    private external fun nativeImportPsdJson(holder: Long): String?
    private external fun nativeImportPsdStores(holder: Long): LongArray?
    private external fun nativeImportPsdDestroy(holder: Long)
    private external fun nativeImportPsdLastError(): String
    private external fun nativeRenderTextLayer(
        template: Long,
        fontPaths: Array<String>,
        text: String,
        sizePx: Float,
        leadingPx: Float,
        tracking: Float,
        align: Int,
        boxWidth: Float,
        x: Float,
        y: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        axisTags: Array<String>,
        axisValues: FloatArray,
    ): LongArray?
    private external fun nativeTextLastError(): String
    private external fun nativeApplyMaskOverlay(bitmap: Bitmap, mask: Long, mode: Int, r: Float, g: Float, b: Float, a: Float): Boolean
    private external fun nativeRenderCompositePreview(
        stores: LongArray,
        layerIds: LongArray,
        blendModes: IntArray,
        opacities: IntArray,
        fillOpacities: IntArray,
        visibility: BooleanArray,
        backgroundColor: FloatArray,
        mipLevel: Int,
        originX: Int,
        originY: Int,
        bitmap: Bitmap,
        renderSession: Long,
        cancellationId: Long,
    ): Boolean
    private external fun nativeDecodeToTileStore(
        encoded: ByteArray,
        size: Int,
        handle: Long,
        codec: Int,
        exifOrientation: Int,
    ): String?
    private external fun nativeEncodePngToStream(
        store: Long,
        output: OutputStream,
        compressionLevel: Int,
        iccProfile: ByteArray?,
        exifTiff: ByteArray?,
        backgroundColor: FloatArray,
    ): Long
    private external fun nativeEncodeJpegToStream(
        store: Long,
        output: OutputStream,
        quality: Int,
        iccProfile: ByteArray?,
        exifTiff: ByteArray?,
        backgroundColor: FloatArray,
    ): Long
    private external fun nativeEncodeWebpToStream(
        store: Long,
        output: OutputStream,
        quality: Int,
        maxWorkingBytes: Long,
        iccProfile: ByteArray?,
        exifTiff: ByteArray?,
        backgroundColor: FloatArray,
    ): Long
    private external fun nativeSaveCroptop(
        stores: LongArray,
        layerUuids: Array<String>,
        manifestJson: ByteArray,
        workingIcc: ByteArray?,
        output: OutputStream,
        zstdLevel: Int,
        workerThreads: Int,
        embeddedIds: Array<String>,
        embeddedNames: Array<String>,
        embeddedBytes: Array<ByteArray>,
    ): Long
    private external fun nativeCroptopEmbeddedNative(handle: Long, id: String, name: String): ByteArray?
    private external fun nativeOpenCroptopArchiveNative(path: String): Long
    private external fun nativeCloseCroptopArchiveNative(handle: Long)
    private external fun nativeCroptopManifestNative(handle: Long): ByteArray?
    private external fun nativeCroptopWorkingIccNative(handle: Long): ByteArray?
    private external fun nativeCroptopLayerInfoNative(handle: Long, uuid: String): IntArray?
    private external fun nativeLoadCroptopLayerNative(handle: Long, uuid: String, store: Long): String?
    private external fun nativeSamplePixels(
        stores: LongArray,
        layerIds: LongArray,
        blendModes: IntArray,
        opacities: IntArray,
        fillOpacities: IntArray,
        visibility: BooleanArray,
        backgroundColor: FloatArray,
        mode: Int,
        primaryLayerId: Long,
        x: Int,
        y: Int,
        size: Int,
    ): FloatArray?

    private fun defaultMemoryBudgetBytes(): Long {
        val manager = applicationContext?.getSystemService(ActivityManager::class.java)
            ?: return DEFAULT_ENGINE_MEMORY_BUDGET_BYTES
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        return (memory.totalMem * DEFAULT_MEMORY_BUDGET_RATIO).toLong().coerceAtMost(MAX_MEMORY_BUDGET_BYTES)
            .coerceAtLeast(MIN_MEMORY_BUDGET_BYTES)
    }
}

internal data class NativeRenderLayer(
    val store: Long,
    val id: Long,
    val blendMode: Int,
    val opacity: Int,
    val fillOpacity: Int,
    val visible: Boolean,
)

private const val MAX_VIEWPORT_PREVIEW_DIMENSION = 2048

/** How long the view must rest before its visible region is re-rendered at the document's own resolution. */
private const val VIEWPORT_REGION_IDLE_MILLIS = 150L

/** After an edit the sharp region was dropped as stale, so it comes back sooner than after a pan. */
private const val VIEWPORT_REGION_AFTER_EDIT_MILLIS = 30L

/** Guided-filter window (at the model mask's resolution) and edge threshold used when a model mask becomes a selection. */
private const val GUIDED_MASK_RADIUS = 2
private const val GUIDED_MASK_EPSILON = 4e-4f
private const val MAX_RAW_SIDECAR_BYTES = 16 * 1024 * 1024
private val RAW_SIDE_CAR_EXTENSIONS = setOf(
    "3fr", "arw", "cr2", "cr3", "dcr", "dng", "fff", "iiq", "kdc", "mef", "mos", "mrw",
    "nef", "nrw", "orf", "pef", "ptx", "raf", "raw", "rw2", "rwl", "srw", "x3f",
)
private const val AUTOSAVE_IDLE_MS = 5_000L
private val CROPTOP_MIME_TYPES = setOf("application/x-croptop", "application/vnd.croptop")
private const val FILTER_SEED = 1337

/** Nodes per side of the soft-proof LUT the viewport applies (33³ is the usual accuracy/size trade-off). */
private const val SOFT_PROOF_LUT_SIZE = 33
private val STORE_BACKED_KINDS = setOf(LayerKind.PIXEL, LayerKind.TEXT, LayerKind.ADJUSTMENT, LayerKind.SMART_OBJECT, LayerKind.FILL)
private const val AUTOSAVE_MAX_MS = 60_000L
private val JOURNAL_NAME = Regex("[A-Za-z0-9_-]{1,80}")
private const val COARSE_VIEWPORT_PREVIEW_DIMENSION = 512
private const val VIEWPORT_REFINE_IDLE_MILLIS = 150L
private const val VIEWPORT_PREVIEW_COALESCE_MILLIS = 16L

internal fun viewportPreviewMipLevel(width: Int, height: Int, maxDimension: Int = 2048): Int {
    require(width > 0 && height > 0 && maxDimension > 0)
    var w = width
    var h = height
    var level = 0
    while (maxOf(w, h) > maxDimension) {
        w = (w + 1) / 2
        h = (h + 1) / 2
        level++
    }
    return level
}

internal fun viewportUsesNearestNeighbor(zoom: Float): Boolean = zoom > 1f

/**
 * The part of the document to re-render at its own resolution while the viewport rests: what is on screen plus a margin, clamped to
 * the document and cut down around its centre to [maxPixels]. Null when the preview already resolves the view (zoom up to
 * [previewScale] screen pixels per document pixel, with a little slack) or there is nothing to show.
 */
internal fun viewportRefineRegion(
    view: ViewportState,
    documentWidth: Int,
    documentHeight: Int,
    previewScale: Float,
    maxPixels: Long = VIEWPORT_REFINE_MAX_PIXELS,
): app.croptop.core.model.DocRect? {
    if (view.viewWidth <= 0 || view.viewHeight <= 0 || documentWidth <= 0 || documentHeight <= 0 || !view.zoom.isFinite() || view.zoom <= 0f) return null
    if (previewScale >= 1f || view.zoom <= previewScale * 1.05f) return null
    val corners = listOf(
        view.viewToDoc(ViewPoint(0f, 0f)), view.viewToDoc(ViewPoint(view.viewWidth.toFloat(), 0f)),
        view.viewToDoc(ViewPoint(0f, view.viewHeight.toFloat())), view.viewToDoc(ViewPoint(view.viewWidth.toFloat(), view.viewHeight.toFloat())),
    )
    var left = corners.minOf { it.x }
    var top = corners.minOf { it.y }
    var right = corners.maxOf { it.x }
    var bottom = corners.maxOf { it.y }
    val marginX = (right - left) * 0.1f
    val marginY = (bottom - top) * 0.1f
    left = (left - marginX).coerceAtLeast(0f)
    top = (top - marginY).coerceAtLeast(0f)
    right = (right + marginX).coerceAtMost(documentWidth.toFloat())
    bottom = (bottom + marginY).coerceAtMost(documentHeight.toFloat())
    if (right - left < 1f || bottom - top < 1f) return null
    var w = right - left
    var h = bottom - top
    if (w.toDouble() * h > maxPixels) {
        // Too much to hold at once: keep the part around the middle of what is visible.
        val shrink = Math.sqrt(maxPixels / (w.toDouble() * h)).toFloat()
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        w *= shrink
        h *= shrink
        left = (cx - w / 2f).coerceIn(0f, documentWidth - w)
        top = (cy - h / 2f).coerceIn(0f, documentHeight - h)
        right = left + w
        bottom = top + h
    }
    val rect = app.croptop.core.model.DocRect(
        kotlin.math.floor(left).toInt().coerceIn(0, documentWidth - 1), kotlin.math.floor(top).toInt().coerceIn(0, documentHeight - 1),
        kotlin.math.ceil(right).toInt().coerceIn(1, documentWidth), kotlin.math.ceil(bottom).toInt().coerceIn(1, documentHeight),
    )
    return rect.takeUnless { it.isEmpty }
}

internal const val VIEWPORT_REFINE_MAX_PIXELS = 8_000_000L

internal fun viewportShowsPixelGrid(zoom: Float, documentWidth: Int, documentHeight: Int, previewWidth: Int, previewHeight: Int): Boolean =
    zoom >= 8f && documentWidth > 0 && documentHeight > 0 &&
        previewWidth == documentWidth && previewHeight == documentHeight

/** Layer metadata and history are immutable snapshots; image pixels live in the native TileStore. */
internal class NativeDocument(
    private val engine: NativeEngine,
    initialInfo: DocumentInfo,
    initialTree: LayerTree,
    private val background: BackgroundContents,
    private val pixelFormat: Int,
    private val onClose: (NativeDocument) -> Unit,
    private val strokeWorkQueue: StrokeWorkQueue = StrokeWorkQueue(),
) : DocumentHandle {
    private data class HistogramRequest(
        val size: DocSize,
        val tree: LayerTree?,
        val pixelStores: Map<LayerId, Long>,
        val activeLayer: LayerId?,
        val selection: SelectionInfo,
        val selectionMaskRevision: Long,
    )

    private data class HistogramCacheEntry(val request: HistogramRequest, val value: Histogram)

    private data class HistogramSourceSnapshot(
        val tree: LayerTree,
        val stores: Map<LayerId, Long>,
        val selectionMask: Long,
        val size: DocSize,
    )

    private data class PixelReadSnapshot(
        val tree: LayerTree,
        /** Null means COW snapshot allocation failed and this read must use the locked fallback. */
        val stores: MutableMap<LayerId, Long>?,
        val size: DocSize,
        val outsideSample: ColorSample?,
    )

    private data class Snapshot(
        val label: String,
        val tree: LayerTree,
        val pixelStores: Map<LayerId, Long>,
        val selectionMask: Long,
        val selectionInfo: SelectionInfo,
        val lastSelectionMask: Long,
        val lastSelectionInfo: SelectionInfo.Active?,
        val documentSize: DocSize,
        val resolutionPpi: Float,
        val profile: ColorProfileRef,
        val workingIcc: ByteArray?,
    )
    private data class NamedSnapshot(
        val name: String,
        val tree: LayerTree,
        val pixelStores: Map<LayerId, Long>,
        val selectionMask: Long,
        val selectionInfo: SelectionInfo,
        val lastSelectionMask: Long,
        val lastSelectionInfo: SelectionInfo.Active?,
        val documentSize: DocSize,
        val resolutionPpi: Float,
        val previewOffset: Pair<Float, Float>,
        val profile: ColorProfileRef,
        val workingIcc: ByteArray?,
    )
    private data class GeometryChange(
        val operation: Int,
        val sourceSize: DocSize,
        val targetSize: DocSize,
        val dx: Int = 0,
        val dy: Int = 0,
        val quarterTurns: Int = 0,
        val targetResolutionPpi: Float? = null,
        val cropMatrix: DoubleArray? = null,
        val cropAngleDegrees: Float = 0f,
        val perspectiveCrop: Boolean = false,
        /** Canvas Size / Rotate fill for the newly exposed area of the bottom pixel layer (null = transparent). */
        val fill: RgbaColor? = null,
        /** Crop with "Delete Cropped Pixels" off: each listed layer's hidden-pixel record after the crop. */
        val keptPixels: Map<LayerId, app.croptop.core.model.HiddenPixelsInfo>? = null,
    )
    private data class RasterExportState(
        val store: Long,
        val size: DocSize,
        val warnings: List<String>,
        val iccProfile: ByteArray?,
        val exifTiff: ByteArray?,
        val backgroundColor: RgbaColor,
        /** True when [store] is a flattened copy made for this export (destroyed afterwards). */
        val ownsStore: Boolean = false,
        /** Layered PSD records (their stores are owned copies, destroyed afterwards); null = flattened output. */
        val psdLayers: List<NativeCodecExtraBridge.PsdRecord>? = null,
    )
    private data class RasterExportSnapshot(
        val tree: LayerTree,
        val stores: MutableMap<LayerId, Long>,
        val size: DocSize,
        val pixelFormat: Int,
        val metadata: RasterMetadata?,
        val workingIcc: ByteArray,
        val backgroundColor: RgbaColor,
    )
    private data class ViewportMaskOverlaySource(
        val store: Long,
        val mode: Int,
        val extractLayerMask: Boolean,
    )
    private data class NativeTransformState(
        val targets: Set<LayerId>,
        val sessions: Map<LayerId, Long>,
        val baseHistoryCursor: Int,
        val baseTree: LayerTree,
        val selectionSource: Long = 0L,
        val selectionStoreAtBegin: Long = 0L,
        val selectionInfoAtBegin: SelectionInfo.Active? = null,
        var matrix: Matrix3 = Matrix3.Identity,
        var generation: Long = 0L,
        var previewFailure: String? = null,
        var finished: Boolean = false,
        var renderJob: Job? = null,
    ) {
        val nativeCallLock = Any()
        val transformsSelection: Boolean get() = selectionSource != 0L
    }

    private val lock = Any()
    private val compositeRenderSession = engine.createCompositeRenderSession()
    private val commandQueue = DocumentCommandQueue()
    private val closeLock = Any()
    private val closing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sampleAdmissionLock = Any()
    private val snapshots = mutableListOf<Snapshot>()
    private val namedSnapshots = mutableListOf<NamedSnapshot>()
    private val viewportOffsets = mutableListOf(0f to 0f)
    private var cursor = 0
    private var sequence = 0L
    private var cleanIndex = 0
    private val layerUuids = mutableMapOf<LayerId, String>()
    private var originalProjectManifest: kotlinx.serialization.json.JsonObject? = null
    private var closed = false
    private var activeAdjustmentPreview: NativeAdjustmentPreview? = null
    private var activeTransform: NativeTransformState? = null
    private var activeSelectionModifySession: SelectionModifySession? = null
    private var activeTransactionStartCursor: Int? = null
    private val pixelStores = mutableMapOf<LayerId, Long>()

    /** Active A16 selection mask; snapshots own independent COW wrappers. */
    private var selectionMask = 0L
    private var lastSelectionMask = 0L
    private var lastSelectionInfo: SelectionInfo.Active? = null
    private val attachedViewports = mutableSetOf<SurfaceDocumentViewport>()
    private val activeBrushSessions = ConcurrentHashMap.newKeySet<StrokeSession>()
    private val activeFillJobs = ConcurrentHashMap.newKeySet<NativeFillJob>()
    private val pendingBrushCommits = AtomicInteger(0)
    private val nativeReadSnapshots = NativeReadSnapshotBarrier()
    private val previewRenderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fillJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var previewRenderJob: Job? = null
    private var previewRenderCancellationId = 0L
    private var previewRenderGeneration = 0L
    private var previewReadyGeneration = 0L
    private var previewReady: CompletableDeferred<Unit>? = null
    private var completedPreviewRenderGeneration = 0L
    private var viewportPreviewIsComposite = false
    private var pendingRenderSequence: Long? = null
    internal var viewportPreview: Bitmap? = null
        private set
    internal val hasCompositeViewportPreview: Boolean get() = synchronized(lock) {
        viewportPreviewIsComposite && completedPreviewRenderGeneration == previewRenderGeneration
    }
    private var viewportPreviewLayerId: LayerId? = null
    internal var importedRasterMetadata: RasterMetadata? = null
    private var cachedBuiltInIcc: Pair<app.croptop.core.model.ColorProfileRef.BuiltIn, ByteArray>? = null
    private var colorReadoutSession: NativeColorManagementBridge.ReadoutSession? = null

    /** Synchronous command entry points share one FIFO writer; render work still runs from owned snapshots. */
    private fun <T> withDocumentCommand(command: () -> T): T {
        if (Thread.holdsLock(lock)) return synchronized(lock, command)
        if (commandQueue.isClosed) return synchronized(lock, command)
        return try {
            commandQueue.run { synchronized(lock, command) }
        } catch (_: DocumentCommandQueueClosedException) {
            synchronized(lock, command)
        }
    }

    internal fun attachImportedRasterMetadata(metadata: RasterMetadata) = synchronized(lock) {
        importedRasterMetadata = metadata
        val profile = mutableInfo.value.profile as? ColorProfileRef.Embedded ?: return@synchronized
        val bytes = metadata.iccProfile ?: return@synchronized
        require(bytes.sha256() == profile.hash) { "Imported ICC profile does not match the document profile" }
        // Open/import establishes the initial state before editing, so attach the exact ICC to every
        // existing history state. Future profile edits capture their own bytes in their snapshot.
        snapshots.indices.forEach { index ->
            snapshots[index] = snapshots[index].copy(workingIcc = bytes.copyOf())
        }
    }

    internal fun preserveOriginalProjectManifest(original: kotlinx.serialization.json.JsonObject) = synchronized(lock) {
        originalProjectManifest = original
    }

    init {
        initialTree.flatten().filter { it.kind == LayerKind.PIXEL || it.kind == LayerKind.SMART_OBJECT || it.kind == LayerKind.FILL }.forEach { layer ->
            val store = engine.createTileStore(initialInfo.size.width, initialInfo.size.height, pixelFormat)
            check(store != 0L) { "Could not allocate native pixels for layer ${layer.id.value}" }
            pixelStores[layer.id] = store
        }
        val allocationLike = pixelStores.values.firstOrNull()
            ?: engine.createTileStore(initialInfo.size.width, initialInfo.size.height, pixelFormat).also {
                check(it != 0L) { "Could not initialize the native selection allocator" }
            }
        selectionMask = NativeSelectionBudgetBridge.emptyA16MaskLike(allocationLike).also {
            if (it == 0L) {
                if (pixelStores.values.none { store -> store == allocationLike }) engine.destroyTileStore(allocationLike)
                error(NativeSelectionBudgetBridge.nativeLastError().ifBlank { "Could not initialize the native selection mask" })
            }
        }
        if (pixelStores.values.none { it == allocationLike }) engine.destroyTileStore(allocationLike)
        snapshots += Snapshot(
            "New",
            initialTree,
            clonePixelStores(pixelStores, initialTree) ?: error("Could not snapshot initial pixels"),
            cloneSelectionMask(selectionMask) ?: error("Could not snapshot initial selection"),
            SelectionInfo.None,
            0L,
            null,
            initialInfo.size,
            initialInfo.resolutionPpi,
            initialInfo.profile,
            null,
        )
    }

    private val mutableInfo = MutableStateFlow(initialInfo)
    private val mutableLayers = MutableStateFlow(initialTree)
    private val mutableHistory = MutableStateFlow(HistoryState())
    private val mutableContentRevision = MutableStateFlow(0L)
    private val mutableSelection = MutableStateFlow<SelectionInfo>(SelectionInfo.None)
    private val mutableSelectionMaskRevision = MutableStateFlow(0L)
    /** At most one result per scope; revisions replace old bins without retaining native stores. */
    private val histogramCache = mutableMapOf<HistogramScope, HistogramCacheEntry>()
    private val virtualBackgroundColor: RgbaColor
        get() = when (background) {
            BackgroundContents.WHITE -> RgbaColor.White
            BackgroundContents.BLACK -> RgbaColor.Black
            BackgroundContents.TRANSPARENT, BackgroundContents.BACKGROUND_COLOR -> RgbaColor.Transparent
        }

    override val id: DocumentId = initialInfo.id
    override val info: StateFlow<DocumentInfo> = mutableInfo.asStateFlow()
    override val openWarnings: List<String> get() = importedRasterMetadata?.warnings.orEmpty()
    override val layers: StateFlow<LayerTree> = mutableLayers.asStateFlow()
    override val history: StateFlow<HistoryState> = mutableHistory.asStateFlow()
    override val selection: StateFlow<SelectionInfo> = mutableSelection.asStateFlow()
    private val mutableEvents = MutableSharedFlow<EngineEvent>(
        replay = 32,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<EngineEvent> = mutableEvents
    override val rendersViewport: Boolean get() = true

    override fun supportsDestructiveAdjustment(specId: String): Boolean =
        specId in NATIVE_DESTRUCTIVE_ADJUSTMENTS || app.croptop.core.model.FilterCatalog[specId] != null

    /**
     * Filter ▸ …: a new store with [specId] applied to [source] inside [mask] (0 = everywhere). Values use the
     * [app.croptop.core.model.FilterCatalog] units and are mapped onto `filter_dispatch.h` parameters here.
     */
    private fun filterStore(source: Long, specId: String, values: Map<String, ParamValue>, mask: Long): Long {
        val spec = checkNotNull(app.croptop.core.model.FilterCatalog[specId]) { "Unknown filter $specId" }
        val v = spec.normalize(values)
        fun num(key: String) = (v[key] as? ParamValue.Num)?.v ?: 0f
        fun whole(key: String) = kotlin.math.round(num(key))
        fun pct(key: String) = num(key) / 100f
        fun choice(key: String) = (v[key] as? ParamValue.Choice)?.v
        fun flag(key: String) = if ((v[key] as? ParamValue.Bool)?.v == true) 1f else 0f
        fun color(key: String) = (v[key] as? ParamValue.Color)?.v ?: app.croptop.core.model.RgbaColor.Black
        if (specId == app.croptop.core.model.FilterCatalog.PREFIX + "lensCorrection") {
            return NativeImageOpsBridge.lensCorrection(
                source,
                NativeImageOpsBridge.LensCorrection(
                    distortion = num("distortion"),
                    redCyan = num("redCyan"),
                    blueYellow = num("blueYellow"),
                    vignetteAmount = num("vignette"),
                    vignetteMidpoint = num("vignetteMidpoint"),
                    verticalPerspective = num("verticalPerspective"),
                    horizontalPerspective = num("horizontalPerspective"),
                    angle = num("angle"),
                    scale = num("scale"),
                    edgeExtend = (v["edgeExtend"] as? ParamValue.Bool)?.v == true,
                ),
                maskHandle = mask,
                workerThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            )
        }
        if (specId == app.croptop.core.model.FilterCatalog.PREFIX + "defringe") {
            return NativeDefringeBridge.apply(
                source,
                NativeDefringeBridge.Spec(
                    purpleAmount = num("purpleAmount"),
                    greenAmount = num("greenAmount"),
                    edgeThreshold = num("edgeThreshold"),
                ),
                selectionHandle = mask,
                workerThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            )
        }
        val size = mutableInfo.value.size
        val cx = size.width / 2f
        val cy = size.height / 2f
        var mapHandle = 0L
        val (kind, params) = when (specId.removePrefix(app.croptop.core.model.FilterCatalog.PREFIX)) {
            "gaussianBlur" -> NativeFiltersBridge.Kind.GAUSSIAN_BLUR to floatArrayOf(num("radius"))
            "boxBlur" -> NativeFiltersBridge.Kind.BOX_BLUR to floatArrayOf(whole("radius"))
            "motionBlur" -> NativeFiltersBridge.Kind.MOTION_BLUR to floatArrayOf(num("angle"), num("distance"))
            "radialBlur" -> NativeFiltersBridge.Kind.RADIAL_BLUR to floatArrayOf(if (choice("method") == "zoom") 1f else 0f, num("amount"), cx, cy)
            "surfaceBlur" -> NativeFiltersBridge.Kind.SURFACE_BLUR to floatArrayOf(whole("radius"), num("threshold"))
            "tiltShift" -> NativeFiltersBridge.Kind.TILT_SHIFT to floatArrayOf(cx, cy, num("angle"), num("focus"), num("transition"), num("blur"))
            "unsharpMask" -> NativeFiltersBridge.Kind.UNSHARP_MASK to floatArrayOf(pct("amount"), num("radius"), num("threshold"))
            "smartSharpen" -> NativeFiltersBridge.Kind.SMART_SHARPEN to floatArrayOf(pct("amount"), num("radius"), pct("reduceNoise"), if (choice("remove") == "lens") 1f else 0f)
            "highPass" -> NativeFiltersBridge.Kind.HIGH_PASS to floatArrayOf(num("radius"))
            "twirl" -> NativeFiltersBridge.Kind.TWIRL to floatArrayOf(num("angle"))
            "pinch" -> NativeFiltersBridge.Kind.PINCH to floatArrayOf(num("amount"))
            "spherize" -> NativeFiltersBridge.Kind.SPHERIZE to floatArrayOf(num("amount"))
            "wave" -> NativeFiltersBridge.Kind.WAVE to floatArrayOf(num("wavelength"), num("amplitude"), flag("horizontal"), flag("vertical"))
            "ripple" -> NativeFiltersBridge.Kind.RIPPLE to floatArrayOf(num("amount"), when (choice("size")) { "small" -> 0f; "large" -> 2f; else -> 1f })
            "polarCoordinates" -> NativeFiltersBridge.Kind.POLAR_COORDINATES to floatArrayOf(if (choice("mode") == "toRect") 1f else 0f)
            "addNoise" -> NativeFiltersBridge.Kind.ADD_NOISE to floatArrayOf(pct("amount"), if (choice("distribution") == "gaussian") 1f else 0f, flag("monochromatic"))
            "filmGrain" -> NativeFiltersBridge.Kind.FILM_GRAIN to floatArrayOf(pct("intensity"), num("size"))
            "median" -> NativeFiltersBridge.Kind.MEDIAN to floatArrayOf(whole("radius"))
            "dustAndScratches" -> NativeFiltersBridge.Kind.DUST_AND_SCRATCHES to floatArrayOf(whole("radius"), num("threshold"))
            "reduceNoise" -> NativeFiltersBridge.Kind.REDUCE_NOISE to floatArrayOf(num("strength"), pct("preserve"))
            "mosaic" -> NativeFiltersBridge.Kind.MOSAIC to floatArrayOf(whole("cell"))
            "crystallize" -> NativeFiltersBridge.Kind.CRYSTALLIZE to floatArrayOf(whole("cell"))
            "colorHalftone" -> NativeFiltersBridge.Kind.COLOR_HALFTONE to floatArrayOf(whole("radius"), num("angleR"), num("angleG"), num("angleB"))
            "emboss" -> NativeFiltersBridge.Kind.EMBOSS to floatArrayOf(num("angle"), num("height"), pct("amount").coerceAtLeast(0.01f))
            "findEdges" -> NativeFiltersBridge.Kind.FIND_EDGES to floatArrayOf()
            "glowingEdges" -> NativeFiltersBridge.Kind.GLOWING_EDGES to floatArrayOf(whole("width"), num("brightness"), whole("smoothness"))
            "oilPaint" -> NativeFiltersBridge.Kind.OIL_PAINT to floatArrayOf(num("stylization"), num("cleanliness"))
            "diffuseGlow" -> NativeFiltersBridge.Kind.DIFFUSE_GLOW to floatArrayOf(num("graininess"), num("glow"), num("clear"))
            "clouds" -> {
                val fg = color("foreground")
                val bg = color("background")
                NativeFiltersBridge.Kind.CLOUDS to floatArrayOf(fg.r, fg.g, fg.b, fg.a, bg.r, bg.g, bg.b, bg.a)
            }
            "displace" -> {
                // The map is another pixel layer of this document, named by a `layer:<id>` choice.
                val ref = (v["map"] as? ParamValue.Choice)?.v.orEmpty()
                val mapId = ref.removePrefix(app.croptop.core.model.ChoiceParam.LAYER_PREFIX).toLongOrNull()?.let(::LayerId)
                    ?: error("Choose a layer to use as the displacement map")
                val mapStore = pixelStores[mapId] ?: error("The displacement layer is no longer in the document")
                mapHandle = engine.cloneTileStore(mapStore).also { check(it != 0L) { "Could not read the displacement layer" } }
                NativeFiltersBridge.Kind.DISPLACE to floatArrayOf(num("h"), num("v"), if (choice("fit") == "tile") 1f else 0f)
            }
            "lensFlare" -> NativeFiltersBridge.Kind.LENS_FLARE to floatArrayOf(
                pct("x"),
                pct("y"),
                pct("brightness").coerceIn(0.1f, 3f),
                when (choice("lens")) {
                    "prime35" -> 1f
                    "prime105" -> 2f
                    "movie" -> 3f
                    else -> 0f
                },
            )
            "vignette" -> NativeFiltersBridge.Kind.VIGNETTE to floatArrayOf(pct("amount"), pct("midpoint"), pct("feather").coerceAtLeast(0.01f))
            "chromaticAberration" -> NativeFiltersBridge.Kind.CHROMATIC_ABERRATION to floatArrayOf(num("red"), num("blue"))
            else -> error("Unknown filter $specId")
        }
        // A fixed seed keeps noise-like filters identical between preview and commit.
        try {
            return NativeFiltersBridge.apply(
                source, kind, *params, maskHandle = mask, mapHandle = mapHandle, seed = FILTER_SEED,
                workerThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            )
        } finally {
            if (mapHandle != 0L) engine.destroyTileStore(mapHandle)
        }
    }

    override fun select(shape: SelectionShape, op: SelectionOp, feather: Float, antiAlias: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (!feather.isFinite() || feather < 0f || feather > SelectionModify.FEATHER.maxPx) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Feather must be between 0 and ${SelectionModify.FEATHER.maxPx.toInt()} px")
        }
        val bounds = shape.bounds
        val limit = 2f * app.croptop.core.model.MAX_DIMENSION
        if (listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).any { kotlin.math.abs(it) > limit }) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The selection is too far outside the canvas")
        }
        if (shape is SelectionShape.Polygon && shape.points.size > MAX_SELECTION_VERTICES) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "A polygon selection can contain at most $MAX_SELECTION_VERTICES points")
        }
        // Selection previews expose transformed bounds through mutableSelection while
        // retaining the committed mask. New selection actions supersede that preview.
        finishSelectionTransformBeforeMutation()
        if (bounds.isEmpty && op == SelectionOp.REPLACE) return@synchronized deselectLocked()
        var shapeMask = rasterizeSelectionShape(selectionMask, shape, antiAlias)
        if (shapeMask == 0L) return@synchronized selectionFailure("Could not rasterize selection", budgetBridge = true)
        if (feather > 0f) {
            val softened = NativeSelectionBudgetBridge.modifyLike(selectionMask, shapeMask, 0, feather)
            NativeSelectionBridge.destroyStore(shapeMask)
            shapeMask = softened
            if (shapeMask == 0L) return@synchronized selectionFailure("Could not feather selection", budgetBridge = true)
        }
        val nextMask = if (op == SelectionOp.REPLACE) {
            shapeMask
        } else {
            NativeSelectionBudgetBridge.combineLike(selectionMask, selectionMask, shapeMask, op.ordinal).also {
                NativeSelectionBridge.destroyStore(shapeMask)
            }
        }
        if (nextMask == 0L) return@synchronized selectionFailure("Could not combine selection", budgetBridge = true)
        val label = when (shape) {
            is SelectionShape.Rect -> "Rectangular Marquee"
            is SelectionShape.Ellipse -> "Elliptical Marquee"
            is SelectionShape.Polygon -> "Lasso"
        }
        installSelection(nextMask, selectionInfo(nextMask, feather > 0f), label)
    }}

    override fun selectAll(): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val size = mutableInfo.value.size
        val next = NativeSelectionBudgetBridge.rasterizeRectLike(selectionMask, 0.0, 0.0, size.width.toDouble(), size.height.toDouble(), false)
        if (next == 0L) return@synchronized selectionFailure("Could not select the canvas", budgetBridge = true)
        installSelection(next, selectionInfo(next, false), "Select Canvas")
    }}

    override fun deselect(): CommandResult = withDocumentCommand { synchronized(lock) { deselectLocked() }}

    private fun deselectLocked(): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (mutableSelection.value == SelectionInfo.None) return reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
        val previousLast = lastSelectionMask
        val preserved = engine.cloneTileStore(selectionMask)
        if (preserved == 0L) return reject(RejectionCode.UNSUPPORTED, "Could not preserve the previous selection")
        lastSelectionMask = preserved
        lastSelectionInfo = mutableSelection.value as SelectionInfo.Active
        val empty = NativeSelectionBudgetBridge.emptyA16MaskLike(selectionMask)
        if (empty == 0L) {
            engine.destroyTileStore(lastSelectionMask)
            lastSelectionMask = previousLast
            return selectionFailure("Could not clear the selection", budgetBridge = true)
        }
        val result = installSelection(empty, SelectionInfo.None, "Deselect")
        if (result is CommandResult.Accepted) {
            if (previousLast != 0L) engine.destroyTileStore(previousLast)
        } else {
            engine.destroyTileStore(lastSelectionMask)
            lastSelectionMask = previousLast
        }
        return result
    }

    override fun reselect(): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val previous = lastSelectionInfo
        if (lastSelectionMask == 0L || previous == null) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "There is no selection to restore")
        }
        val restored = engine.cloneTileStore(lastSelectionMask)
        if (restored == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not restore the previous selection")
        installSelection(restored, previous, "Reselect")
    }}

    override fun invertSelection(): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val inverted = NativeSelectionBudgetBridge.invertLike(selectionMask, selectionMask)
        if (inverted == 0L) return@synchronized selectionFailure("Could not invert selection", budgetBridge = true)
        installSelection(inverted, selectionInfo(inverted, false), "Select Inverse")
    }}

    override fun modifySelection(kind: SelectionModify, px: Float): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (mutableSelection.value == SelectionInfo.None) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
        if (!px.isFinite() || px <= 0f || px > kind.maxPx) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "${kind.label} must be between 1 and ${kind.maxPx.toInt()} px")
        }
        val operation = when (kind) {
            SelectionModify.FEATHER -> 0
            SelectionModify.EXPAND -> 1
            SelectionModify.CONTRACT -> 2
            SelectionModify.BORDER -> 3
            SelectionModify.SMOOTH -> 4
        }
        val modified = NativeSelectionBudgetBridge.modifyLike(selectionMask, selectionMask, operation, px)
        if (modified == 0L) return@synchronized selectionFailure("Could not ${kind.label.lowercase()} selection", budgetBridge = true)
        installSelection(modified, selectionInfo(modified, kind == SelectionModify.FEATHER), kind.label)
    }}

    override fun beginModifySelection(kind: SelectionModify): SelectionModifySession = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized UnsupportedSelectionModifySession("Document is closed")
        finishSelectionTransformBeforeMutation()
        val currentInfo = mutableSelection.value as? SelectionInfo.Active
        if (currentInfo == null || currentInfo.bounds.isEmpty || selectionMask == 0L) {
            return@synchronized object : SelectionModifySession {
                override fun update(px: Float): CommandResult = reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
                override fun commit(): CommandResult = reject(RejectionCode.INVALID_ARGUMENT, "Nothing is selected")
                override fun cancel() = Unit
            }
        }
        val baseMask = cloneSelectionMask(selectionMask)
            ?: return@synchronized UnsupportedSelectionModifySession("Could not clone selection mask for preview")
        val baseInfo = currentInfo

        var previewMask = 0L
        var finished = false

        val session = object : SelectionModifySession {
            override fun update(px: Float): CommandResult = withDocumentCommand { synchronized(lock) {
                if (closed || finished) return@synchronized reject(RejectionCode.CLOSED, "Document is closed or session finished")
                if (!px.isFinite() || px <= 0f || px > kind.maxPx) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "${kind.label} must be between 1 and ${kind.maxPx.toInt()} px")
                }
                val operation = when (kind) {
                    SelectionModify.FEATHER -> 0
                    SelectionModify.EXPAND -> 1
                    SelectionModify.CONTRACT -> 2
                    SelectionModify.BORDER -> 3
                    SelectionModify.SMOOTH -> 4
                }
                val modified = NativeSelectionBudgetBridge.modifyLike(baseMask, baseMask, operation, px)
                if (modified == 0L) return@synchronized selectionFailure("Could not ${kind.label.lowercase()} selection", budgetBridge = true)

                val oldPreview = previewMask
                previewMask = modified
                val nextInfo = selectionInfo(modified, kind == SelectionModify.FEATHER)
                selectionMask = modified
                mutableSelection.value = nextInfo
                mutableSelectionMaskRevision.value++
                if (oldPreview != 0L && oldPreview != baseMask && oldPreview != modified) {
                    engine.destroyTileStore(oldPreview)
                }
                val acceptedSequence = ++sequence
                mutableEvents.tryEmit(EngineEvent.Applied(acceptedSequence, kind.label))
                queueViewportComposite(mutableLayers.value, acceptedSequence)
                return@synchronized CommandResult.Accepted(acceptedSequence)
            }}

            override fun commit(): CommandResult = withDocumentCommand { synchronized(lock) {
                if (closed || finished) return@synchronized reject(RejectionCode.CLOSED, "Document is closed or session finished")
                finished = true
                if (activeSelectionModifySession === this) activeSelectionModifySession = null
                engine.destroyTileStore(baseMask)
                val finalMask = if (previewMask != 0L) previewMask else selectionMask
                selectionMask = finalMask
                return@synchronized commit(kind.label, mutableLayers.value)
            }}

            override fun cancel() = withDocumentCommand { synchronized(lock) {
                if (finished) return@synchronized
                finished = true
                if (activeSelectionModifySession === this) activeSelectionModifySession = null
                if (previewMask != 0L && previewMask != baseMask) {
                    engine.destroyTileStore(previewMask)
                    previewMask = 0L
                }
                selectionMask = baseMask
                mutableSelection.value = baseInfo
                mutableSelectionMaskRevision.value++
                queueViewportComposite(mutableLayers.value)
            }}
        }
        activeSelectionModifySession = session
        session
    }}

    private fun installSelection(nextMask: Long, info: SelectionInfo, label: String): CommandResult {
        val previousMask = selectionMask
        val previousInfo = mutableSelection.value
        selectionMask = nextMask
        mutableSelection.value = info
        val result = commit(label, mutableLayers.value)
        if (result is CommandResult.Accepted) {
            engine.destroyTileStore(previousMask)
        } else {
            selectionMask = previousMask
            mutableSelection.value = previousInfo
            engine.destroyTileStore(nextMask)
        }
        return result
    }

    private fun selectionInfo(mask: Long, feathered: Boolean): SelectionInfo {
        val bounds = NativeSelectionBridge.bounds(mask)
        if (bounds.size != 4 || bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) return SelectionInfo.None
        return SelectionInfo.Active(DocRect(bounds[0], bounds[1], bounds[2], bounds[3]), feathered)
    }

    private fun rasterizeSelectionShape(allocationLike: Long, shape: SelectionShape, antialias: Boolean): Long = when (shape) {
        is SelectionShape.Rect -> NativeSelectionBudgetBridge.rasterizeRectLike(
            allocationLike,
            shape.rect.left.toDouble(),
            shape.rect.top.toDouble(),
            shape.rect.right.toDouble(),
            shape.rect.bottom.toDouble(),
            antialias,
        )

        is SelectionShape.Ellipse -> NativeSelectionBudgetBridge.rasterizeEllipseLike(
            allocationLike,
            shape.rect.left.toDouble(),
            shape.rect.top.toDouble(),
            shape.rect.right.toDouble(),
            shape.rect.bottom.toDouble(),
            antialias,
        )

        is SelectionShape.Polygon -> {
            val xy = DoubleArray(shape.points.size * 2)
            shape.points.forEachIndexed { index, point ->
                xy[index * 2] = point.x.toDouble()
                xy[index * 2 + 1] = point.y.toDouble()
            }
            NativeSelectionBudgetBridge.rasterizePolygonLike(allocationLike, xy, antialias)
        }
    }

    private fun selectionFailure(fallback: String, budgetBridge: Boolean = false): CommandResult.Rejected {
        val detail = if (budgetBridge) NativeSelectionBudgetBridge.nativeLastError() else NativeSelectionBridge.nativeLastError()
        return reject(RejectionCode.UNSUPPORTED, detail.ifBlank { fallback })
    }

    override fun beginAdjustmentPreview(adjustment: AdjustmentInfo): AdjustmentPreviewSession = synchronized(lock) {
        check(!closed) { "Document is closed" }
        check(supportsDestructiveAdjustment(adjustment.specId)) { "${adjustment.specId} is not supported by the native engine" }
        check(activeAdjustmentPreview == null) { "An adjustment preview is already active" }
        val tree = mutableLayers.value
        val target = tree.primary ?: error("Select a pixel layer before applying an adjustment")
        val node = tree.find(target) ?: error("The active layer does not exist")
        // A filter on a smart object becomes a smart filter (re-editable, applied to the whole layer).
        val smartFilter = node.kind == LayerKind.SMART_OBJECT && node.smartObject != null && app.croptop.core.model.FilterCatalog.isFilter(adjustment.specId)
        check((node.kind == LayerKind.PIXEL || smartFilter) && !node.locks.pixels) { "The active layer must be an editable pixel layer" }
        val baseline = engine.cloneTileStore(pixelStores[target] ?: error("The active layer has no pixel storage"))
        check(baseline != 0L) { "Could not preserve adjustment preview baseline" }
        val selectionBaseline = if (!smartFilter && mutableSelection.value != SelectionInfo.None) engine.cloneTileStore(selectionMask) else 0L
        if (!smartFilter && mutableSelection.value != SelectionInfo.None && selectionBaseline == 0L) {
            engine.destroyTileStore(baseline)
            error("Could not preserve the active selection for adjustment preview")
        }
        NativeAdjustmentPreview(target, baseline, selectionBaseline, adjustment.specId, smartFilter).also { activeAdjustmentPreview = it }
    }

    override fun applyAdjustment(adjustment: AdjustmentInfo): CommandResult = withDocumentCommand {
        if (!supportsDestructiveAdjustment(adjustment.specId)) {
            return@withDocumentCommand reject(RejectionCode.UNSUPPORTED, "This adjustment is not supported by the native engine")
        }
        val session = try {
            beginAdjustmentPreview(adjustment)
        } catch (failure: IllegalStateException) {
            return@withDocumentCommand reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not begin adjustment")
        }
        val result = session.preview(adjustment.values)
        if (result !is CommandResult.Accepted) {
            session.cancel()
            return@withDocumentCommand result
        }
        val committed = session.commit()
        if (committed !is CommandResult.Accepted) session.cancel()
        committed
    }

    override fun fill(source: FillSource, opacity: Float, blendMode: BlendMode, preserveTransparency: Boolean): CommandResult =
        fillInternal(source, opacity, blendMode, preserveTransparency, { true }, { true })

    override fun fillJob(source: FillSource, opacity: Float, blendMode: BlendMode, preserveTransparency: Boolean): EngineJob = withDocumentCommand { synchronized(lock) {
        if (closed || closing.get()) return@synchronized EngineJob.completed(reject(RejectionCode.CLOSED, "Document is closed"))
        NativeFillJob(fillJobScope, { activeFillJobs.remove(it) }) { progress, _, beginCommit ->
            fillInternal(source, opacity, blendMode, preserveTransparency, progress, beginCommit)
        }.also(activeFillJobs::add)
    }}

    private fun fillInternal(
        source: FillSource,
        opacity: Float,
        blendMode: BlendMode,
        preserveTransparency: Boolean,
        progress: (Float) -> Boolean,
        beginCommit: () -> Boolean,
    ): CommandResult {
        data class FillSnapshot(
            val tree: LayerTree,
            val revision: Long,
            val historyCursor: Int,
            val selection: SelectionInfo,
            val selectionMaskRevision: Long,
            val target: LayerId,
            val targetStore: Long,
            val selectionStore: Long,
            val prepared: MutableMap<LayerId, Long>,
        )

        var snapshotFailure: CommandResult? = null
        val snapshot = withDocumentCommand { synchronized(lock) {
            when {
                closed || closing.get() -> {
                    snapshotFailure = reject(RejectionCode.CLOSED, "Document is closed")
                    null
                }
                !opacity.isFinite() || opacity !in 0f..1f -> {
                    snapshotFailure = reject(RejectionCode.INVALID_ARGUMENT, "Fill opacity is out of range")
                    null
                }
                blendMode.groupOnly -> {
                    snapshotFailure = reject(RejectionCode.INVALID_ARGUMENT, "Pass Through cannot be used to fill pixels")
                    null
                }
                else -> {
                    val tree = mutableLayers.value
                    val target = tree.primary?.let(tree::find)
                    when {
                        target == null -> {
                            snapshotFailure = reject(RejectionCode.INVALID_ARGUMENT, "Select a layer before filling")
                            null
                        }
                        target.kind != LayerKind.PIXEL || target.locks.pixels -> {
                            snapshotFailure = reject(RejectionCode.INVALID_ARGUMENT, "Select an editable pixel layer before filling")
                            null
                        }
                        source is FillSource.ContentAware && mutableSelection.value == SelectionInfo.None -> {
                            snapshotFailure = reject(RejectionCode.INVALID_ARGUMENT, "Select an area before using Content-Aware Fill")
                            null
                        }
                        else -> {
                            val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
                            if (prepared == null) {
                                snapshotFailure = reject(RejectionCode.UNSUPPORTED, "Could not preserve document pixels before filling")
                                null
                            } else {
                                val fillHandle = prepared[target.id]
                                val selectionInfo = mutableSelection.value
                                val selectionHandle = if (selectionInfo == SelectionInfo.None) 0L else engine.cloneTileStore(selectionMask)
                                if (fillHandle == null || (selectionInfo != SelectionInfo.None && selectionHandle == 0L)) {
                                    prepared.values.forEach(engine::destroyTileStore)
                                    if (selectionHandle != 0L) engine.destroyTileStore(selectionHandle)
                                    snapshotFailure = reject(
                                        RejectionCode.UNSUPPORTED,
                                        if (fillHandle == null) "The active layer has no pixel storage"
                                        else "Could not preserve the active selection before filling",
                                    )
                                    null
                                } else {
                                    FillSnapshot(
                                        tree = tree,
                                        revision = sequence,
                                        historyCursor = cursor,
                                        selection = selectionInfo,
                                        selectionMaskRevision = mutableSelectionMaskRevision.value,
                                        target = target.id,
                                        targetStore = pixelStores.getValue(target.id),
                                        selectionStore = selectionHandle,
                                        prepared = prepared,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }} ?: return checkNotNull(snapshotFailure)

        var ownsPrepared = true
        fun discardPrepared() {
            if (!ownsPrepared) return
            ownsPrepared = false
            snapshot.prepared.values.forEach(engine::destroyTileStore)
            snapshot.prepared.clear()
        }

        try {
            if (!progress(0f)) return reject(RejectionCode.CANCELLED, "Fill was cancelled")
            val fillHandle = checkNotNull(snapshot.prepared[snapshot.target])
            val failure = when (source) {
                is FillSource.Solid -> NativeFillBridge.solid(
                    fillHandle,
                    floatArrayOf(source.color.r, source.color.g, source.color.b, source.color.a),
                    opacity,
                    blendMode.ordinal - 1,
                    preserveTransparency,
                    snapshot.selectionStore,
                )

                is FillSource.ContentAware -> NativeFillBridge.contentAware(
                    fillHandle,
                    opacity,
                    blendMode.ordinal - 1,
                    preserveTransparency,
                    snapshot.selectionStore,
                    NativeFillProgressListener(progress),
                    source.options,
                )
            }
            if (failure != null) {
                return if (failure.equals("cancelled", ignoreCase = true)) {
                    reject(RejectionCode.CANCELLED, "Fill was cancelled")
                } else {
                    reject(RejectionCode.INVALID_ARGUMENT, failure)
                }
            }
            if (!beginCommit()) return reject(RejectionCode.CANCELLED, "Fill was cancelled")

            return withDocumentCommand { synchronized(lock) {
                if (closed || closing.get() || sequence != snapshot.revision || cursor != snapshot.historyCursor ||
                    mutableLayers.value != snapshot.tree || pixelStores[snapshot.target] != snapshot.targetStore ||
                    mutableSelection.value != snapshot.selection || mutableSelectionMaskRevision.value != snapshot.selectionMaskRevision
                ) {
                    return@synchronized reject(RejectionCode.CANCELLED, "Document changed while Fill was running")
                }
                val newLayerId = (source as? FillSource.ContentAware)?.options?.newLayerId
                if (newLayerId != null) {
                    // Output to a new layer: the filled clone becomes the new layer, the original layer keeps its pixels.
                    val tree = snapshot.tree
                    if (tree.find(newLayerId) != null) {
                        return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer ${newLayerId.value} already exists")
                    }
                    val parentId = tree.parentOf(snapshot.target)?.id
                    val siblings = tree.parentOf(snapshot.target)?.children ?: tree.layers
                    val index = siblings.indexOfFirst { it.id == snapshot.target }
                    val copyNode = LayerNode(id = newLayerId, name = "Content-Aware Fill", kind = LayerKind.PIXEL)
                    val inserted = NativeLayerTreeOps.insert(tree, copyNode, parentId, index.coerceAtLeast(0))
                        ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the fill layer")
                    val nextTree = inserted.copy(active = setOf(newLayerId), primary = newLayerId)
                    val untouched = clonePixelStores(pixelStores, tree)?.toMutableMap()
                        ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve document pixels before filling")
                    untouched[newLayerId] = snapshot.prepared.remove(snapshot.target)!!
                    snapshot.prepared.values.forEach(engine::destroyTileStore)
                    snapshot.prepared.clear()
                    ownsPrepared = false
                    return@synchronized commitPrepared("Content-Aware Fill", nextTree, untouched)
                }
                ownsPrepared = false // commitPrepared takes ownership on both accepted and rejected paths.
                commitPrepared("Fill", snapshot.tree, snapshot.prepared)
            }}
        } finally {
            if (snapshot.selectionStore != 0L) engine.destroyTileStore(snapshot.selectionStore)
            discardPrepared()
        }
    }

    override fun drawGradient(
        start: DocPoint,
        end: DocPoint,
        gradient: ColorGradient,
        shape: GradientShape,
        reverse: Boolean,
        dither: Boolean,
        opacity: Float,
    ): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (!opacity.isFinite() || opacity !in 0f..1f) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Gradient opacity is out of range")
        }
        val tree = mutableLayers.value
        val target = tree.primary?.let(tree::find)
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select a layer before drawing a gradient")
        if (target.kind != LayerKind.PIXEL || target.locks.pixels) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select an editable pixel layer before drawing a gradient")
        }
        if (gradient.stops.any {
                !it.position.isFinite() || it.position !in 0f..1f ||
                    !it.color.r.isFinite() || it.color.r !in 0f..1f || !it.color.g.isFinite() || it.color.g !in 0f..1f ||
                    !it.color.b.isFinite() || it.color.b !in 0f..1f || !it.color.a.isFinite() || it.color.a !in 0f..1f
            }
        ) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Gradient stops must have finite positions and colors in range")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve document pixels before drawing a gradient")
        val store = prepared[target.id]
            ?: run {
                prepared.values.forEach(engine::destroyTileStore)
                return@synchronized reject(RejectionCode.UNSUPPORTED, "The active layer has no pixel storage")
            }
        val failure = NativeGradientBridge.render(
            store,
            start,
            end,
            gradient,
            shape,
            reverse,
            dither,
            opacity,
            if (mutableSelection.value == SelectionInfo.None) 0L else selectionMask,
        )
        if (failure != null) {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure)
        }
        commitPrepared("Gradient", tree, prepared)
    }}

    override fun paintBucket(
        point: DocPoint,
        color: RgbaColor,
        tolerance: Int,
        contiguous: Boolean,
        sampleAll: Boolean,
        opacity: Float,
    ): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (tolerance !in 0..255 || !opacity.isFinite() || opacity !in 0f..1f || !point.x.isFinite() || !point.y.isFinite()) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Paint Bucket point, tolerance, or opacity is invalid")
        }
        val tree = mutableLayers.value
        val target = tree.primary?.let(tree::find)
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select a layer before using Paint Bucket")
        if (target.kind != LayerKind.PIXEL || target.locks.pixels) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select an editable pixel layer before using Paint Bucket")
        }
        val layerSource = pixelStores[target.id]
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "The active layer has no pixel storage")
        // Sample All Layers: the flood region comes from the visible composite; the fill still lands on the layer.
        val sampledComposite = if (sampleAll) {
            try {
                flattenVisible(tree)
            } catch (failure: Throwable) {
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Nothing is visible to sample")
            }
        } else {
            0L
        }
        val source = if (sampleAll) sampledComposite else layerSource
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: run {
                if (sampleAll) engine.destroyTileStore(sampledComposite)
                return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve document pixels before using Paint Bucket")
            }
        val destination = prepared[target.id]
            ?: run {
                prepared.values.forEach(engine::destroyTileStore)
                if (sampleAll) engine.destroyTileStore(sampledComposite)
                return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not clone the active pixel layer")
            }
        val failure = NativeGradientBridge.bucket(
            destination,
            source,
            point,
            color,
            tolerance,
            contiguous,
            opacity,
            if (mutableSelection.value == SelectionInfo.None) 0L else selectionMask,
        )
        if (sampleAll) engine.destroyTileStore(sampledComposite)
        if (failure != null) {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure)
        }
        commitPrepared("Paint Bucket", tree, prepared)
    }}

    override fun addLayerMask(id: LayerId, fill: MaskFill): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.PIXEL) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Layer masks currently require pixel layers")
        }
        if (node.mask != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer already has a mask")
        if (fill.needsSelection && mutableSelection.value == SelectionInfo.None) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "${fill.label} needs an active selection")
        }
        val source = pixelStores[id] ?: return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels before adding the mask")
        val target = prepared[id] ?: run {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
        }
        val mask = try {
            NativeLayerMaskBridge.create(source, fill, if (fill.needsSelection) selectionMask else 0L)
        } catch (failure: Throwable) {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not create the layer mask")
        }
        try {
            NativeLayerMaskBridge.attach(target, mask)
        } catch (failure: Throwable) {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not attach the layer mask")
        } finally {
            NativeLayerMaskBridge.destroy(mask)
        }
        val nextTree = NativeLayerTreeOps.update(tree, id) { it.copy(mask = LayerMaskInfo()) }
        commitPrepared("Add ${fill.label} Layer Mask", nextTree, prepared)
    }}

    override fun removeLayerMask(id: LayerId, apply: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.PIXEL) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Layer masks currently require pixel layers")
        }
        val maskInfo = node.mask ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer has no mask to remove")
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels before removing the mask")
        val target = prepared[id] ?: run {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
        }
        if (apply) {
            val source = pixelStores[id] ?: run {
                prepared.values.forEach(engine::destroyTileStore)
                return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
            }
            val mask = try {
                NativeLayerMaskBridge.fromLayerStore(source)
            } catch (failure: Throwable) {
                prepared.values.forEach(engine::destroyTileStore)
                return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not read the layer mask")
            }
            val applied = try {
                NativeLayerMaskBridge.apply(source, mask, maskInfo)
            } catch (failure: Throwable) {
                prepared.values.forEach(engine::destroyTileStore)
                return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not apply the layer mask")
            } finally {
                NativeLayerMaskBridge.destroy(mask)
            }
            engine.destroyTileStore(target)
            prepared[id] = applied
        } else {
            NativeLayerMaskBridge.detach(target)
        }
        val nextTree = NativeLayerTreeOps.update(tree, id) { it.copy(mask = null) }
        commitPrepared(if (apply) "Apply Layer Mask" else "Remove Layer Mask", nextTree, prepared)
    }}

    override fun invertLayerMask(id: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        val maskInfo = node.mask ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer has no mask to invert")
        if (node.kind != LayerKind.PIXEL) return@synchronized reject(RejectionCode.UNSUPPORTED, "Layer masks currently require pixel layers")
        val source = pixelStores[id] ?: return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
        val mask = try {
            NativeLayerMaskBridge.fromLayerStore(source)
        } catch (failure: Throwable) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not read the layer mask")
        }
        val inverted = try {
            NativeLayerMaskBridge.invert(mask)
        } catch (failure: Throwable) {
            NativeLayerMaskBridge.destroy(mask)
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not invert the layer mask")
        }
        NativeLayerMaskBridge.destroy(mask)
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            NativeLayerMaskBridge.destroy(inverted)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels before inverting the mask")
        }
        val target = prepared[id] ?: run {
            prepared.values.forEach(engine::destroyTileStore)
            NativeLayerMaskBridge.destroy(inverted)
            return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
        }
        try {
            NativeLayerMaskBridge.attach(target, inverted, maskInfo)
        } catch (failure: Throwable) {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not attach the inverted layer mask")
        } finally {
            NativeLayerMaskBridge.destroy(inverted)
        }
        commitPrepared("Invert", tree, prepared)
    }}

    override fun setLayerMask(id: LayerId, mask: app.croptop.core.model.LayerMaskInfo, preview: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (!mask.density.isFinite() || mask.density !in 0f..1f || !mask.feather.isFinite() || mask.feather < 0f) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Mask density must be 0..1 and feather must be non-negative")
        }
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        val previous = node.mask
        if (node.kind != LayerKind.PIXEL || previous == null) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer has no editable pixel mask")
        }
        val source = pixelStores[id] ?: return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
        if (preview) {
            // Density/feather slider drags: re-attach the live mask with the new properties and recompose; no history.
            // History snapshots hold their own mask caches, so the committed state is untouched.
            val live = try {
                NativeLayerMaskBridge.fromLayerStore(source)
            } catch (failure: Throwable) {
                return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not read the layer mask")
            }
            try {
                NativeLayerMaskBridge.attach(source, live, mask)
            } catch (failure: Throwable) {
                return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not preview the layer mask")
            } finally {
                NativeLayerMaskBridge.destroy(live)
            }
            queueViewportComposite(tree)
            return@synchronized CommandResult.Accepted(sequence)
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels before editing the mask")
        val target = prepared[id] ?: run {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
        }
        val current = try {
            NativeLayerMaskBridge.fromLayerStore(source)
        } catch (failure: Throwable) {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not read the layer mask")
        }
        try {
            NativeLayerMaskBridge.attach(target, current, mask)
        } catch (failure: Throwable) {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not update the layer mask")
        } finally {
            NativeLayerMaskBridge.destroy(current)
        }
        val label = when {
            previous.enabled != mask.enabled -> if (mask.enabled) "Enable Layer Mask" else "Disable Layer Mask"
            previous.linked != mask.linked -> if (mask.linked) "Link Layer Mask" else "Unlink Layer Mask"
            else -> "Mask Properties"
        }
        commitPrepared(label, NativeLayerTreeOps.update(tree, id) { it.copy(mask = mask) }, prepared)
    }}

    override fun applyLensBlur(id: LayerId, spec: LensBlurSpec, depth: LensBlurDepthSource?): CommandResult =
        withDocumentCommand { applyLensBlurInternal(id, spec, depth, { true }, { true }) { true } }

    override fun applyLensBlurJob(id: LayerId, spec: LensBlurSpec, depth: LensBlurDepthSource?): EngineJob = withDocumentCommand { synchronized(lock) {
        if (closed || closing.get()) return@synchronized EngineJob.completed(reject(RejectionCode.CLOSED, "Document is closed"))
        NativeFillJob(fillJobScope, { activeFillJobs.remove(it) }) { progress, isActive, beginCommit ->
            applyLensBlurInternal(id, spec, depth, progress, isActive, beginCommit)
        }.also(activeFillJobs::add)
    }}

    private fun applyLensBlurInternal(
        id: LayerId,
        spec: LensBlurSpec,
        depth: LensBlurDepthSource?,
        progress: (Float) -> Boolean,
        isActive: () -> Boolean,
        beginCommit: () -> Boolean,
    ): CommandResult {
        synchronized(lock) { finishSelectionTransformBeforeMutation() }
        data class Snapshot(
            val tree: LayerTree,
            val node: LayerNode,
            val targetHandle: Long,
            val revision: Long,
            val cursorAtSnapshot: Int,
            val prepared: MutableMap<LayerId, Long>,
            val working: Long,
            val depthLayerPixels: Long,
            val depthIsMask: Boolean,
            val depthLayerId: LayerId?,
            val depthSourceHandle: Long?,
            val selectionInfo: SelectionInfo.Active?,
            val selectionHandle: Long,
        )

        val snapshotResult = synchronized(lock) {
            if (closed || closing.get()) return reject(RejectionCode.CLOSED, "Document is closed")
            val selectionInfo = mutableSelection.value as? SelectionInfo.Active
            val tree = mutableLayers.value
            val node = tree.find(id) ?: return reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
            if (node.kind != LayerKind.PIXEL || node.locks.pixels) {
                return reject(RejectionCode.INVALID_ARGUMENT, "Lens Blur needs an editable pixel layer")
            }
            val source = pixelStores[id]
                ?: return reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
            if (!isActive()) return reject(RejectionCode.CANCELLED, "Lens Blur was cancelled")
            val selectionHandle = if (selectionInfo == null) 0L else engine.cloneTileStore(selectionMask).also {
                if (it == 0L) return reject(RejectionCode.UNSUPPORTED, "Could not snapshot the active selection for Lens Blur")
            }
            val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
                ?: run {
                    if (selectionHandle != 0L) engine.destroyTileStore(selectionHandle)
                    return reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels before Lens Blur")
                }
            val working = prepared[id] ?: run {
                prepared.values.forEach(engine::destroyTileStore)
                if (selectionHandle != 0L) engine.destroyTileStore(selectionHandle)
                return reject(RejectionCode.UNSUPPORTED, "Could not clone the Lens Blur target")
            }
            var depthLayerPixels = 0L
            val depthIsMask = depth is LensBlurDepthSource.LayerMask
            try {
                depthLayerPixels = when (depth) {
                    null -> 0L
                    is LensBlurDepthSource.LayerPixels -> {
                        val depthNode = tree.find(depth.layerId)
                            ?: throw IllegalArgumentException("Depth layer ${depth.layerId.value} was not found")
                        if (depthNode.kind != LayerKind.PIXEL) throw IllegalArgumentException("Depth source must be a pixel layer")
                        val depthHandle = pixelStores[depth.layerId]
                            ?: throw IllegalArgumentException("Depth layer pixels are unavailable")
                        engine.cloneTileStore(depthHandle).also {
                            if (it == 0L) throw IllegalStateException("Could not snapshot depth layer pixels")
                        }
                    }
                    is LensBlurDepthSource.LayerMask -> {
                        val depthNode = tree.find(depth.layerId)
                            ?: throw IllegalArgumentException("Depth layer ${depth.layerId.value} was not found")
                        if (depthNode.mask == null) throw IllegalArgumentException("Depth layer has no layer mask")
                        val depthHandle = pixelStores[depth.layerId]
                            ?: throw IllegalArgumentException("Depth layer pixels are unavailable")
                        engine.cloneTileStore(depthHandle).also {
                            if (it == 0L) throw IllegalStateException("Could not snapshot depth layer pixels")
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (depthLayerPixels != 0L) engine.destroyTileStore(depthLayerPixels)
                prepared.values.forEach(engine::destroyTileStore)
                if (selectionHandle != 0L) engine.destroyTileStore(selectionHandle)
                return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Invalid Lens Blur depth source")
            }
            val depthLayerId = when (depth) {
                null -> null
                is LensBlurDepthSource.LayerPixels -> depth.layerId
                is LensBlurDepthSource.LayerMask -> depth.layerId
            }
            Snapshot(
                tree,
                node,
                source,
                sequence,
                cursor,
                prepared,
                working,
                depthLayerPixels,
                depthIsMask,
                depthLayerId,
                depthLayerId?.let(pixelStores::get),
                selectionInfo,
                selectionHandle,
            )
        }
        val snapshot = snapshotResult
        val prepared = snapshot.prepared
        val working = snapshot.working
        var selectionHandle = snapshot.selectionHandle
        fun destroySelectionSnapshot() {
            if (selectionHandle != 0L) {
                engine.destroyTileStore(selectionHandle)
                selectionHandle = 0L
            }
        }
        if (!isActive()) {
            prepared.values.forEach(engine::destroyTileStore)
            if (snapshot.depthLayerPixels != 0L) engine.destroyTileStore(snapshot.depthLayerPixels)
            destroySelectionSnapshot()
            return reject(RejectionCode.CANCELLED, "Lens Blur was cancelled")
        }
        var ownedDepthMask = 0L
        var derivedDepthPixels = 0L
        val depthStore = try {
            if (snapshot.depthIsMask) {
                ownedDepthMask = NativeLayerMaskBridge.fromLayerStore(snapshot.depthLayerPixels)
                derivedDepthPixels = NativeLayerMaskBridge.pixels(ownedDepthMask)
                derivedDepthPixels
            } else {
                snapshot.depthLayerPixels
            }
        } catch (failure: Throwable) {
            if (derivedDepthPixels != 0L) engine.destroyTileStore(derivedDepthPixels)
            if (ownedDepthMask != 0L) NativeLayerMaskBridge.destroy(ownedDepthMask)
            if (snapshot.depthLayerPixels != 0L) engine.destroyTileStore(snapshot.depthLayerPixels)
            prepared.values.forEach(engine::destroyTileStore)
            destroySelectionSnapshot()
            return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not prepare Lens Blur depth source")
        }
        if (!isActive()) {
            if (derivedDepthPixels != 0L) engine.destroyTileStore(derivedDepthPixels)
            if (ownedDepthMask != 0L) NativeLayerMaskBridge.destroy(ownedDepthMask)
            if (snapshot.depthLayerPixels != 0L) engine.destroyTileStore(snapshot.depthLayerPixels)
            prepared.values.forEach(engine::destroyTileStore)
            destroySelectionSnapshot()
            return reject(RejectionCode.CANCELLED, "Lens Blur was cancelled")
        }
        var blurError: String? = null
        val blurred = try {
            NativeLensBlurBridge.lensBlur(
                working,
                depthStore,
                NativeLensBlurBridge.Spec(
                    radius = spec.radius,
                    focalDistance = spec.focalDistance,
                    invertDepth = spec.invertDepth,
                    irisBlades = spec.irisBlades,
                    irisRotationDegrees = spec.irisRotationDegrees,
                    irisCurvature = spec.irisCurvature,
                    specularBrightness = spec.specularBrightness,
                    specularThreshold = spec.specularThreshold,
                    noise = spec.noise,
                    monochromaticNoise = spec.monochromaticNoise,
                    seed = spec.seed,
                    workerThreads = spec.workerThreads,
                ),
                progress = { fraction -> isActive() && progress(fraction) },
            )
        } catch (failure: Throwable) {
            blurError = failure.message ?: "Lens Blur failed"
            0L
        } finally {
            if (derivedDepthPixels != 0L) engine.destroyTileStore(derivedDepthPixels)
            if (ownedDepthMask != 0L) NativeLayerMaskBridge.destroy(ownedDepthMask)
            if (snapshot.depthLayerPixels != 0L) engine.destroyTileStore(snapshot.depthLayerPixels)
        }
        if (blurred == 0L) {
            prepared.values.forEach(engine::destroyTileStore)
            destroySelectionSnapshot()
            return if (isActive()) {
                reject(RejectionCode.INVALID_ARGUMENT, blurError ?: "Lens Blur failed")
            } else {
                reject(RejectionCode.CANCELLED, "Lens Blur was cancelled")
            }
        }
        if (!isActive()) {
            engine.destroyTileStore(blurred)
            prepared.values.forEach(engine::destroyTileStore)
            destroySelectionSnapshot()
            return reject(RejectionCode.CANCELLED, "Lens Blur was cancelled")
        }
        var output = blurred
        if (selectionHandle != 0L) {
            output = try {
                NativeLensBlurBridge.blendSelection(working, blurred, selectionHandle)
            } catch (failure: Throwable) {
                engine.destroyTileStore(blurred)
                prepared.values.forEach(engine::destroyTileStore)
                destroySelectionSnapshot()
                return reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not apply the active selection to Lens Blur")
            }
            engine.destroyTileStore(blurred)
            destroySelectionSnapshot()
        }
        try {
            if (snapshot.node.clipped) NativeLayerMaskBridge.setClipped(output, true)
            snapshot.node.mask?.let { info ->
                val mask = NativeLayerMaskBridge.fromLayerStore(snapshot.targetHandle)
                try {
                    NativeLayerMaskBridge.attach(output, mask, info)
                } finally {
                    NativeLayerMaskBridge.destroy(mask)
                }
            }
        } catch (failure: Throwable) {
            engine.destroyTileStore(output)
            prepared.values.forEach(engine::destroyTileStore)
            destroySelectionSnapshot()
            return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not preserve layer properties")
        }
        engine.destroyTileStore(working)
        prepared[id] = output
        return withDocumentCommand { synchronized(lock) {
            if (closed || closing.get() || sequence != snapshot.revision || cursor != snapshot.cursorAtSnapshot ||
                (mutableSelection.value as? SelectionInfo.Active) != snapshot.selectionInfo ||
                mutableLayers.value != snapshot.tree || pixelStores[id] != snapshot.targetHandle ||
                snapshot.depthLayerId?.let { pixelStores[it] != snapshot.depthSourceHandle } == true
            ) {
                prepared.values.forEach(engine::destroyTileStore)
                destroySelectionSnapshot()
                return@synchronized reject(RejectionCode.CANCELLED, "Document changed while Lens Blur was running")
            }
            if (!isActive() || !beginCommit()) {
                prepared.values.forEach(engine::destroyTileStore)
                destroySelectionSnapshot()
                return@synchronized reject(RejectionCode.CANCELLED, "Lens Blur was cancelled")
            }
            commitPrepared("Lens Blur", snapshot.tree, prepared).also { destroySelectionSnapshot() }
        }}
    }

    override fun applyDepthRangeMask(id: LayerId, depth: LensBlurDepthSource, range: DepthRangeSpec): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val targetNode = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (targetNode.kind != LayerKind.PIXEL || targetNode.locks.pixels) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "A depth-range mask needs an editable pixel target")
        }
        val depthLayerId = when (depth) {
            is LensBlurDepthSource.LayerPixels -> depth.layerId
            is LensBlurDepthSource.LayerMask -> depth.layerId
        }
        val depthNode = tree.find(depthLayerId)
            ?: return@synchronized reject(RejectionCode.NOT_FOUND, "Depth layer ${depthLayerId.value} was not found")
        if (depthNode.kind != LayerKind.PIXEL || (depth is LensBlurDepthSource.LayerMask && depthNode.mask == null)) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Depth source must be a pixel layer or an existing layer mask")
        }
        val depthLayerStore = pixelStores[depthLayerId]
            ?: return@synchronized reject(RejectionCode.NOT_FOUND, "Depth layer pixels are unavailable")
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels before applying a depth-range mask")
        var ownedDepthMask = 0L
        var ownedDepthPixels = 0L
        var rangeMaskStore = 0L
        var rangeMask = 0L
        try {
            val depthStore = when (depth) {
                is LensBlurDepthSource.LayerPixels -> depthLayerStore
                is LensBlurDepthSource.LayerMask -> {
                    ownedDepthMask = NativeLayerMaskBridge.fromLayerStore(depthLayerStore)
                    ownedDepthPixels = NativeLayerMaskBridge.pixels(ownedDepthMask)
                    ownedDepthPixels
                }
            }
            rangeMaskStore = NativeLensBlurBridge.depthRangeMask(depthStore, range.near, range.far, range.feather)
            rangeMask = NativeLayerMaskBridge.fromStore(rangeMaskStore, emptyCoverage = 0f)
            val preparedTarget = prepared[id] ?: throw IllegalStateException("Could not clone the depth-range target")
            NativeLayerMaskBridge.attach(preparedTarget, rangeMask, LayerMaskInfo())
        } catch (failure: Throwable) {
            if (rangeMaskStore != 0L) engine.destroyTileStore(rangeMaskStore)
            if (rangeMask != 0L) NativeLayerMaskBridge.destroy(rangeMask)
            if (ownedDepthPixels != 0L) engine.destroyTileStore(ownedDepthPixels)
            if (ownedDepthMask != 0L) NativeLayerMaskBridge.destroy(ownedDepthMask)
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not apply the depth-range mask")
        }
        engine.destroyTileStore(rangeMaskStore)
        NativeLayerMaskBridge.destroy(rangeMask)
        if (ownedDepthPixels != 0L) engine.destroyTileStore(ownedDepthPixels)
        if (ownedDepthMask != 0L) NativeLayerMaskBridge.destroy(ownedDepthMask)
        commitPrepared("Depth Range Mask", NativeLayerTreeOps.update(tree, id) { it.copy(mask = LayerMaskInfo()) }, prepared)
    }}

    override fun setClipped(id: LayerId, clipped: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.SHAPE && node.kind !in STORE_BACKED_KINDS) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Groups can't be clipped")
        }
        if (node.clipped == clipped) return@synchronized CommandResult.Accepted(++sequence)
        if (clipped) {
            val siblings = tree.parentOf(id)?.children ?: tree.layers
            if (siblings.lastOrNull()?.id == id || node.name == "Background") {
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "There's no layer below “${node.name}” to clip to")
            }
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels before changing clipping")
        // Shape rasters are rendered per read and take the flag from the tree.
        if (node.kind == LayerKind.SHAPE) {
            return@synchronized commitPrepared(
                if (clipped) "Create Clipping Mask" else "Release Clipping Mask",
                NativeLayerTreeOps.update(tree, id) { it.copy(clipped = clipped) },
                prepared,
            )
        }
        val target = prepared[id] ?: run {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
        }
        try {
            NativeLayerMaskBridge.setClipped(target, clipped)
        } catch (failure: Throwable) {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not update clipping")
        }
        commitPrepared(if (clipped) "Create Clipping Mask" else "Release Clipping Mask", NativeLayerTreeOps.update(tree, id) { it.copy(clipped = clipped) }, prepared)
    }}

    override fun maskToSelection(id: LayerId, op: SelectionOp): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val node = mutableLayers.value.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        val info = node.mask ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer has no mask")
        val source = pixelStores[id] ?: return@synchronized reject(RejectionCode.NOT_FOUND, "Layer pixel storage is unavailable")
        val mask = try {
            NativeLayerMaskBridge.fromLayerStore(source).let { maskHandle ->
                try {
                    NativeLayerMaskBridge.toSelection(maskHandle, info.copy(enabled = true))
                } finally {
                    NativeLayerMaskBridge.destroy(maskHandle)
                }
            }
        } catch (failure: Throwable) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not load the mask as a selection")
        }
        val next = NativeSelectionBudgetBridge.combineLike(selectionMask, selectionMask, mask, op.ordinal)
        NativeSelectionBridge.destroyStore(mask)
        if (next == 0L) return@synchronized selectionFailure("Could not combine the mask selection", budgetBridge = true)
        installSelection(next, selectionInfo(next, info.feather > 0f), "Load Layer Mask Selection")
    }}

    override fun layerViaCut(newId: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        if (tree.find(newId) != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer ${newId.value} already exists")
        val primary = tree.primary ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select a layer first")
        val sourceNode = tree.find(primary) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${primary.value}")
        if (sourceNode.kind != LayerKind.PIXEL) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer via Cut works on pixel layers. “${sourceNode.name}” isn't one.")
        }
        if (sourceNode.locks.pixels) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${sourceNode.name}” is locked")
        val sourceStore = pixelStores[primary]
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Primary layer storage is unavailable")
        val activeSelection = mutableSelection.value as? SelectionInfo.Active
        val selectionBounds = if (activeSelection == null) {
            null
        } else {
            val values = NativeSelectionBridge.bounds(selectionMask)
            if (values.size != 4 || values[0] >= values[2] || values[1] >= values[3]) {
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Selection has no pixels to cut")
            }
            DocRect(values[0], values[1], values[2], values[3])
        }
        val destinationBounds = if (selectionBounds == null) {
            sourceNode.bounds
        } else {
            (sourceNode.bounds ?: DocRect.of(mutableInfo.value.size)).intersect(selectionBounds).takeUnless { it.isEmpty }
                ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Selection does not overlap the primary layer")
        }
        val parentId = tree.parentOf(primary)?.id
        val siblings = tree.parentOf(primary)?.children ?: tree.layers
        val sourceIndex = siblings.indexOfFirst { it.id == primary }
        if (sourceIndex < 0) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Primary layer cannot be placed")
        val copyNode = LayerNode(
            id = newId,
            name = if (selectionBounds == null) "${sourceNode.name} copy" else "Layer ${tree.size + 1}",
            kind = LayerKind.PIXEL,
            bounds = destinationBounds,
            opacity = sourceNode.opacity,
            blendMode = sourceNode.blendMode,
            mask = sourceNode.mask,
            clipped = sourceNode.clipped,
        )
        var nextTree = NativeLayerTreeOps.insert(tree, copyNode, parentId, sourceIndex)
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the cut layer")
        val outputs = engine.layerViaCutStores(sourceStore, if (selectionBounds == null) 0L else selectionMask)
            ?: return@synchronized reject(
                RejectionCode.UNSUPPORTED,
                engine.layerViaCutError().ifBlank { "Could not extract selected pixels" },
            )
        if (outputs.size != 3 || outputs[0] == 0L || outputs[1] == 0L) {
            outputs.take(2).filter { it != 0L }.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not allocate Layer via Cut pixel stores")
        }
        val sourceMask = try {
            sourceNode.mask?.let { NativeLayerMaskBridge.fromLayerStore(sourceStore) } ?: 0L
        } catch (failure: Throwable) {
            outputs.take(2).forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not preserve the layer mask")
        }
        try {
            sourceNode.mask?.let { maskInfo ->
                NativeLayerMaskBridge.attach(outputs[0], sourceMask, maskInfo)
                NativeLayerMaskBridge.attach(outputs[1], sourceMask, maskInfo)
            }
            if (sourceNode.clipped) {
                NativeLayerMaskBridge.setClipped(outputs[0], true)
                NativeLayerMaskBridge.setClipped(outputs[1], true)
            }
        } catch (failure: Throwable) {
            outputs.take(2).forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not preserve layer properties after cut")
        } finally {
            if (sourceMask != 0L) NativeLayerMaskBridge.destroy(sourceMask)
        }
        if (outputs[2] != 0L) nextTree = NativeLayerTreeOps.update(nextTree, primary) { it.copy(bounds = null) }
        nextTree = nextTree.copy(active = setOf(newId), primary = newId)
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            outputs.take(2).forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for Layer via Cut undo")
        }
        prepared.remove(primary)?.let(engine::destroyTileStore)
        prepared[primary] = outputs[0]
        prepared[newId] = outputs[1]
        commitPrepared("Layer via Cut", nextTree, prepared)
    }}

    // ---- Layer merge, stamp, clipboard and clear (full-resolution native flatten) ----

    /** Flattens the visible render layers chosen by [pick] (bottom to top); shape layers are rasterized first. */
    private fun flattenVisible(
        tree: LayerTree,
        isolateGroups: Boolean = true,
        stores: Map<LayerId, Long> = pixelStores,
        pick: (List<NativeRenderLayer>) -> List<NativeRenderLayer> = { it },
    ): Long {
        val shapeStores = renderShapeStores(tree)
        var groupStack: NativeRenderLayerStack? = null
        try {
            val collected = if (isolateGroups) {
                collectRenderLayerStack(tree, stores + shapeStores).also { groupStack = it }.layers
            } else {
                collectRenderLayers(tree, stores + shapeStores)
            }
            val layers = pick(collected.filter { it.visible })
            check(layers.isNotEmpty()) { "Nothing is visible" }
            return engine.flattenLayers(layers)
        } finally {
            groupStack?.close()
            shapeStores.values.forEach(engine::destroyTileStore)
        }
    }

    private fun activeSelectionBounds(): DocRect? {
        if (mutableSelection.value !is SelectionInfo.Active) return null
        val values = NativeSelectionBridge.bounds(selectionMask)
        if (values.size != 4 || values[0] >= values[2] || values[1] >= values[3]) return null
        return DocRect(values[0], values[1], values[2], values[3])
    }

    private fun nextLayerName(tree: LayerTree): String {
        val highest = tree.flatten().mapNotNull { Regex("Layer (\\d+)").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "Layer ${highest + 1}"
    }

    override fun mergeDown(id: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        val siblings = tree.parentOf(id)?.children ?: tree.layers
        val below = siblings.getOrNull(siblings.indexOfFirst { it.id == id } + 1)
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "There's no layer below “${node.name}” to merge into")
        val mergeable = setOf(LayerKind.PIXEL, LayerKind.SHAPE)
        if (node.kind !in mergeable || below.kind !in mergeable) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Merge Down works on pixel and shape layers")
        }
        if (below.locks.pixels) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${below.name}” is locked")
        if (below.clipped) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Release the clipping mask on “${below.name}” first")
        var ancestor = tree.parentOf(id)
        while (ancestor != null) {
            if (ancestor.opacity != 1f || ancestor.fillOpacity != 1f) {
                return@synchronized reject(RejectionCode.UNSUPPORTED, "Merge Down in a group with reduced opacity requires isolated group compositing")
            }
            ancestor = tree.parentOf(ancestor.id)
        }
        if (node.blendMode != BlendMode.NORMAL || below.blendMode != BlendMode.NORMAL) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Merge Down with blend modes requires backdrop-aware compositing")
        }
        val merged = try {
            flattenVisible(tree, isolateGroups = false) { layers ->
                // Bake each layer's own opacity once. The parent group still applies after the merge.
                val pair = mapOf(below.id.value to below, node.id.value to node)
                layers.filter { it.id in pair }.map { layer ->
                    val original = checkNotNull(pair[layer.id])
                    layer.copy(
                        opacity = (original.opacity * 255f).roundToInt().coerceIn(0, 255),
                        fillOpacity = (original.fillOpacity * 255f).roundToInt().coerceIn(0, 255),
                    )
                }
            }
        } catch (failure: Throwable) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not merge the layers")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(merged)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for Merge Down undo")
        }
        prepared.remove(id)?.let(engine::destroyTileStore)
        prepared.remove(below.id)?.let(engine::destroyTileStore)
        prepared[below.id] = merged
        val without = NativeLayerTreeOps.remove(tree, id)?.first ?: tree
        val next = NativeLayerTreeOps.update(without, below.id) {
            it.copy(
                kind = LayerKind.PIXEL, bounds = null, mask = null, vectorPaths = emptyList(), hasStyles = false,
                opacity = 1f, fillOpacity = 1f, blendMode = BlendMode.NORMAL, visible = node.visible || below.visible,
            )
        }
        commitPrepared("Merge Down", next.copy(active = setOf(below.id), primary = below.id), prepared)
    }}

    override fun mergeVisible(): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val leaves = mutableListOf<LayerNode>()
        collectNested(tree.layers, leaves)
        if (leaves.size < 2) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Merge Visible needs at least two visible layers")
        val merged = try {
            flattenVisible(tree)
        } catch (failure: Throwable) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not merge the visible layers")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(merged)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for Merge Visible undo")
        }
        // The merged pixels replace every visible layer; they land at the top level where the lowest one sat.
        val target = leaves.last()
        val rootAnchor = tree.layers.firstOrNull { root -> root.id == target.id || root.containsLayer(target.id) } ?: tree.layers.last()
        var next = tree
        leaves.forEach { leaf ->
            next = NativeLayerTreeOps.remove(next, leaf.id)?.first ?: next
            prepared.remove(leaf.id)?.let(engine::destroyTileStore)
        }
        val index = next.layers.indexOfFirst { it.id == rootAnchor.id }.let { if (it < 0) next.layers.size else it + if (rootAnchor.id == target.id) 0 else 1 }
        val mergedNode = LayerNode(target.id, target.name, LayerKind.PIXEL)
        next = NativeLayerTreeOps.insert(next, mergedNode, null, index.coerceIn(0, next.layers.size)) ?: run {
            prepared.values.forEach(engine::destroyTileStore)
            engine.destroyTileStore(merged)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the merged layer")
        }
        prepared[target.id] = merged
        commitPrepared("Merge Visible", next.copy(active = setOf(target.id), primary = target.id), prepared)
    }}

    private fun collectNested(nodes: List<LayerNode>, into: MutableList<LayerNode>) {
        nodes.filter { it.visible }.forEach { node ->
            // Every visible layer is in the flattened result (type, smart objects, fills and adjustments included).
            if (node.isGroup) collectNested(node.children, into) else into += node
        }
    }

    private fun LayerNode.containsLayer(id: LayerId): Boolean = children.any { it.id == id || it.containsLayer(id) }

    override fun stampVisible(newId: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        if (tree.find(newId) != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer ${newId.value} already exists")
        val stamped = try {
            flattenVisible(tree)
        } catch (failure: Throwable) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Nothing is visible to stamp")
        }
        // These pixels already include every group's opacity; insert at the document root.
        val next = NativeLayerTreeOps.insert(tree, LayerNode(newId, nextLayerName(tree), LayerKind.PIXEL), null, 0) ?: run {
            engine.destroyTileStore(stamped)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the stamped layer")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(stamped)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for Stamp Visible undo")
        }
        prepared[newId] = stamped
        commitPrepared("Stamp Visible", next.copy(active = setOf(newId), primary = newId), prepared)
    }}

    /** The primary pixel layer and its store, or the rejection explaining why there is none. */
    private fun primaryPixel(action: String): Pair<Pair<LayerNode, Long>?, CommandResult?> {
        val tree = mutableLayers.value
        val primary = tree.primary ?: return null to reject(RejectionCode.INVALID_ARGUMENT, "Select a layer first")
        val node = tree.find(primary) ?: return null to reject(RejectionCode.NOT_FOUND, "No layer ${primary.value}")
        if (node.kind != LayerKind.PIXEL) return null to reject(RejectionCode.INVALID_ARGUMENT, "$action works on pixel layers. “${node.name}” isn't one.")
        val store = pixelStores[primary] ?: return null to reject(RejectionCode.UNSUPPORTED, "Primary layer storage is unavailable")
        return (node to store) to null
    }

    override fun layerViaCopy(newId: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        if (tree.find(newId) != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer ${newId.value} already exists")
        val (found, error) = primaryPixel("Layer via Copy")
        val (node, source) = found ?: return@synchronized error!!
        val selection = activeSelectionBounds()
        val copy = if (selection == null) {
            engine.cloneTileStore(source).takeIf { it != 0L }
        } else {
            engine.layerViaCutStores(source, selectionMask)?.let { outputs ->
                if (outputs.size >= 1 && outputs[0] != 0L) engine.destroyTileStore(outputs[0])
                outputs.getOrNull(1)?.takeIf { it != 0L }
            }
        } ?: return@synchronized reject(RejectionCode.UNSUPPORTED, engine.layerViaCutError().ifBlank { "Could not copy the selected pixels" })
        val parent = tree.parentOf(node.id)?.id
        val siblings = tree.parentOf(node.id)?.children ?: tree.layers
        val index = siblings.indexOfFirst { it.id == node.id }.coerceAtLeast(0)
        val copyNode = LayerNode(
            id = newId,
            name = if (selection == null) "${node.name} copy" else nextLayerName(tree),
            kind = LayerKind.PIXEL,
            bounds = if (selection == null) node.bounds else (node.bounds ?: DocRect.of(mutableInfo.value.size)).intersect(selection).takeUnless { it.isEmpty },
            opacity = node.opacity,
            blendMode = node.blendMode,
        )
        val next = NativeLayerTreeOps.insert(tree, copyNode, parent, index) ?: run {
            engine.destroyTileStore(copy)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the new layer")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(copy)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for Layer via Copy undo")
        }
        prepared[newId] = copy
        commitPrepared(if (selection == null) "Duplicate Layer" else "Layer via Copy", next.copy(active = setOf(newId), primary = newId), prepared)
    }}

    override fun clearSelection(): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        clearSelectedPixels("Clear")
    }}

    /** Erases the selected pixels of the primary layer as one history step labelled [label]. */
    private fun clearSelectedPixels(label: String): CommandResult {
        activeSelectionBounds() ?: return reject(RejectionCode.INVALID_ARGUMENT, "Make a selection first")
        val (found, error) = primaryPixel(label)
        val (node, source) = found ?: return error!!
        if (node.locks.pixels || node.locks.all) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        val outputs = engine.layerViaCutStores(source, selectionMask)
            ?: return reject(RejectionCode.UNSUPPORTED, engine.layerViaCutError().ifBlank { "Could not clear the selected pixels" })
        outputs.getOrNull(1)?.takeIf { it != 0L }?.let(engine::destroyTileStore)
        val remainder = outputs.getOrNull(0)?.takeIf { it != 0L } ?: return reject(RejectionCode.UNSUPPORTED, "Could not clear the selected pixels")
        val tree = mutableLayers.value
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(remainder)
            return reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        prepared.remove(node.id)?.let(engine::destroyTileStore)
        prepared[node.id] = remainder
        return commitPrepared(label, tree, prepared)
    }

    override fun copy(merged: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        copyToClipboard(merged)
    }}

    private fun copyToClipboard(merged: Boolean): CommandResult {
        val size = mutableInfo.value.size
        val selection = activeSelectionBounds()
        val (source, ownedSource, area) = if (merged) {
            val flat = try {
                flattenVisible(mutableLayers.value)
            } catch (failure: Throwable) {
                return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Nothing is visible to copy")
            }
            Triple(flat, true, selection ?: DocRect.of(size))
        } else {
            val (found, error) = primaryPixel("Copy")
            val (node, store) = found ?: return error!!
            val content = node.bounds ?: DocRect.of(size)
            Triple(store, false, (selection?.let { content.intersect(it) } ?: content).intersect(DocRect.of(size)))
        }
        if (area.isEmpty) {
            if (ownedSource) engine.destroyTileStore(source)
            return reject(RejectionCode.INVALID_ARGUMENT, "The selected area is empty")
        }
        val clip = if (selection == null) {
            engine.cloneTileStore(source).takeIf { it != 0L }
        } else {
            engine.layerViaCutStores(source, selectionMask)?.let { outputs ->
                outputs.getOrNull(0)?.takeIf { it != 0L }?.let(engine::destroyTileStore)
                outputs.getOrNull(1)?.takeIf { it != 0L }
            }
        }
        if (ownedSource) engine.destroyTileStore(source)
        clip ?: return reject(RejectionCode.UNSUPPORTED, engine.layerViaCutError().ifBlank { "Could not copy the pixels" })
        engine.setClipboard(clip, ClipboardInfo(DocSize(area.width, area.height), area, merged))
        return CommandResult.Accepted(sequence)
    }

    override fun cut(): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        activeSelectionBounds() ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Cut needs a selection")
        val copied = copyToClipboard(merged = false)
        if (copied !is CommandResult.Accepted) return@synchronized copied
        clearSelectedPixels("Cut")
    }}

    override fun paste(newId: LayerId, at: DocPoint?): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (at != null && !(at.x.isFinite() && at.y.isFinite())) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Paste point must be finite")
        val tree = mutableLayers.value
        if (tree.find(newId) != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer ${newId.value} already exists")
        val (clip, info) = engine.clipboardCopy() ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The clipboard is empty")
        val size = mutableInfo.value.size
        val (dx, dy) = if (at == null) {
            0 to 0
        } else {
            ((at.x - info.size.width / 2f).roundToInt() - info.origin.left) to ((at.y - info.size.height / 2f).roundToInt() - info.origin.top)
        }
        // Place the canvas-sized clip into this document's canvas at the offset (clips outside are cropped).
        val placed = engine.transformTileStore(clip, 0, size.width, size.height, dx, dy)
        engine.destroyTileStore(clip)
        if (placed == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, engine.geometryError().ifBlank { "Could not paste the pixels" })
        val bounds = DocRect(info.origin.left + dx, info.origin.top + dy, info.origin.right + dx, info.origin.bottom + dy)
            .intersect(DocRect.of(size)).takeUnless { it.isEmpty }
        val anchor = tree.primary ?: tree.layers.firstOrNull()?.id
        val parent = anchor?.let { tree.parentOf(it)?.id }
        val siblings = anchor?.let { tree.parentOf(it)?.children } ?: tree.layers
        val index = siblings.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
        val next = NativeLayerTreeOps.insert(tree, LayerNode(newId, nextLayerName(tree), LayerKind.PIXEL, bounds = bounds), parent, index) ?: run {
            engine.destroyTileStore(placed)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the pasted layer")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(placed)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for Paste undo")
        }
        prepared[newId] = placed
        commitPrepared("Paste", next.copy(active = setOf(newId), primary = newId), prepared)
    }}

    override fun ungroup(id: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val group = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (!group.isGroup) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${group.name}” isn't a group")
        val parent = tree.parentOf(id)?.id
        val siblings = tree.parentOf(id)?.children ?: tree.layers
        val index = siblings.indexOfFirst { it.id == id }
        var next = NativeLayerTreeOps.remove(tree, id)?.first ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't remove the group")
        group.children.forEachIndexed { offset, child -> next = NativeLayerTreeOps.insert(next, child, parent, index + offset) ?: next }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for Ungroup undo")
        val ids = group.children.map { it.id }.toSet()
        commitPrepared("Ungroup Layers", next.copy(active = ids, primary = ids.firstOrNull()), prepared)
    }}

    override fun magicWand(point: DocPoint, tolerance: Int, contiguous: Boolean, sampleAll: Boolean, op: SelectionOp): CommandResult = withDocumentCommand {
        data class WandSnapshot(val tree: LayerTree, val stores: Map<LayerId, Long>)
        data class WandPreparation(val snapshot: WandSnapshot? = null, val result: CommandResult? = null)

        val preparation = synchronized(lock) {
            if (closed) return@synchronized WandPreparation(result = reject(RejectionCode.CLOSED, "Document is closed"))
            finishSelectionTransformBeforeMutation()
            if (!(point.x.isFinite() && point.y.isFinite())) return@synchronized WandPreparation(result = reject(RejectionCode.INVALID_ARGUMENT, "Sample point must be finite"))
            if (tolerance !in 0..255) return@synchronized WandPreparation(result = reject(RejectionCode.INVALID_ARGUMENT, "Tolerance must be 0–255"))
            val size = mutableInfo.value.size
            val x = point.x.toInt()
            val y = point.y.toInt()
            if (x !in 0 until size.width || y !in 0 until size.height) return@synchronized WandPreparation(result = reject(RejectionCode.INVALID_ARGUMENT, "Click inside the canvas"))
            if (!sampleAll) {
                val (found, error) = primaryPixel("Magic Wand")
                val source = (found ?: return@synchronized WandPreparation(result = error!!)).second
                val mask = NativeSelectionBridge.magicWand(source, x.toLong(), y.toLong(), tolerance, contiguous)
                if (mask == 0L) return@synchronized WandPreparation(result = selectionFailure("Could not select by colour"))
                val next = NativeSelectionBudgetBridge.combineLike(selectionMask, selectionMask, mask, op.ordinal)
                NativeSelectionBridge.destroyStore(mask)
                return@synchronized WandPreparation(
                    result = if (next == 0L) selectionFailure("Could not combine the selection", budgetBridge = true)
                    else installSelection(next, selectionInfo(next, false), "Magic Wand"),
                )
            }
            val tree = mutableLayers.value
            val stores = clonePixelStores(pixelStores, tree)?.toMap()
                ?: return@synchronized WandPreparation(result = reject(RejectionCode.UNSUPPORTED, "Could not preserve document pixels for Magic Wand"))
            nativeReadSnapshots.beginRead()
            WandPreparation(snapshot = WandSnapshot(tree, stores))
        }
        preparation.result?.let { return@withDocumentCommand it }
        val snapshot = checkNotNull(preparation.snapshot)

        try {
            val source = try {
                flattenVisible(snapshot.tree, stores = snapshot.stores)
            } catch (failure: Throwable) {
                return@withDocumentCommand synchronized(lock) {
                    reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Nothing is visible to sample")
                }
            }
            try {
                val mask = NativeSelectionBridge.magicWand(source, point.x.toInt().toLong(), point.y.toInt().toLong(), tolerance, contiguous)
                if (mask == 0L) return@withDocumentCommand synchronized(lock) { selectionFailure("Could not select by colour") }
                synchronized(lock) {
                    if (closed) {
                        NativeSelectionBridge.destroyStore(mask)
                        reject(RejectionCode.CLOSED, "Document is closed")
                    } else {
                        val next = NativeSelectionBudgetBridge.combineLike(selectionMask, selectionMask, mask, op.ordinal)
                        NativeSelectionBridge.destroyStore(mask)
                        if (next == 0L) selectionFailure("Could not combine the selection", budgetBridge = true)
                        else installSelection(next, selectionInfo(next, false), "Magic Wand")
                    }
                }
            } finally {
                engine.destroyTileStore(source)
            }
        } finally {
            snapshot.stores.values.forEach(engine::destroyTileStore)
            nativeReadSnapshots.endRead()
        }
    }

    // ---- Red Eye and Patch tools ----

    /** Replaces the primary pixel layer's store with [produce]'s result as one history step. */
    private fun retouchPrimary(label: String, produce: (Long) -> Long): CommandResult {
        val (found, error) = primaryPixel(label)
        val (node, source) = found ?: return error!!
        if (node.locks.pixels) return reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        val result = try {
            produce(source)
        } catch (failure: Throwable) {
            return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "$label failed")
        }
        val tree = mutableLayers.value
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(result)
            return reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        prepared.remove(node.id)?.let(engine::destroyTileStore)
        prepared[node.id] = result
        return commitPrepared(label, tree, prepared)
    }

    override fun redEye(region: app.croptop.core.model.DocRectF, pupilSize: Float, darken: Float): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (!pupilSize.isFinite() || pupilSize !in 0f..1f || !darken.isFinite() || darken !in 0f..1f) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Pupil size and darken must be 0–100 %")
        }
        val mask = NativeSelectionBudgetBridge.rasterizeRectLike(
            selectionMask, region.left.toDouble(), region.top.toDouble(), region.right.toDouble(), region.bottom.toDouble(), false,
        )
        if (mask == 0L) return@synchronized selectionFailure("Could not mark the eye area", budgetBridge = true)
        try {
            retouchPrimary("Red Eye") { source -> NativeImageOpsBridge.redEye(source, mask, pupilSize, darken) }
        } finally {
            NativeSelectionBridge.destroyStore(mask)
        }
    }}

    override fun patch(sourceOffset: DocPoint, contentAware: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (mutableSelection.value !is SelectionInfo.Active) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select the area to patch first")
        if (!sourceOffset.x.isFinite() || !sourceOffset.y.isFinite()) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Patch offset must be finite")
        val op = if (contentAware) NativeImageOpsBridge.RetouchSource.PATCH_CONTENT_AWARE else NativeImageOpsBridge.RetouchSource.PATCH
        retouchPrimary("Patch Tool") { source ->
            NativeImageOpsBridge.retouchFromSource(source, source, selectionMask, op, sourceOffset.x.toDouble(), sourceOffset.y.toDouble())
        }
    }}

    override fun warpLayer(id: LayerId, mesh: app.croptop.core.model.WarpMesh): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.PIXEL) return@synchronized reject(RejectionCode.UNSUPPORTED, "Warp currently edits pixel layers")
        if (node.locks.pixels) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        val source = pixelStores[id] ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "“${node.name}” has no pixels")
        val size = mutableInfo.value.size
        if (mesh.isIdentity(size.width, size.height)) return@synchronized CommandResult.Accepted(++sequence)
        val warped = try {
            val nodes = FloatArray(mesh.nodes.size * 2)
            mesh.nodes.forEachIndexed { i, p ->
                nodes[i * 2] = p.x
                nodes[i * 2 + 1] = p.y
            }
            NativeMeshWarpBridge.apply(source, NativeMeshWarpBridge.Mesh(mesh.columns, mesh.rows, nodes))
        } catch (failure: Exception) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Warp failed")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(warped)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve Warp undo state")
        }
        node.mask?.let { maskInfo ->
            runCatching {
                val mask = NativeLayerMaskBridge.fromLayerStore(source)
                try {
                    NativeLayerMaskBridge.attach(warped, mask, maskInfo)
                } finally {
                    NativeLayerMaskBridge.destroy(mask)
                }
            }
        }
        prepared.remove(id)?.let(engine::destroyTileStore)
        prepared[id] = warped
        // Pixels may now reach anywhere on the canvas.
        commitPrepared("Warp", NativeLayerTreeOps.update(tree, id) { it.copy(bounds = null) }, prepared)
    }}

    private var activeLiquify: NativeLiquifySession? = null

    override fun beginLiquify(target: LayerId, meshSpacing: Int): app.croptop.core.engine.api.LiquifySession = synchronized(lock) {
        check(!closed) { "Document is closed" }
        require(meshSpacing in 1..64) { "Liquify mesh spacing must be 1-64 px" }
        check(activeLiquify == null && activeAdjustmentPreview == null) { "Finish the active preview first" }
        finishSelectionTransformBeforeMutation()
        val node = mutableLayers.value.find(target) ?: error("No layer ${target.value}")
        check(node.kind == LayerKind.PIXEL) { "Liquify currently edits pixel layers" }
        check(!node.locks.pixels) { "“${node.name}” is locked" }
        val live = pixelStores[target] ?: error("“${node.name}” has no pixels")
        val baseline = engine.cloneTileStore(live)
        check(baseline != 0L) { "Could not preserve the layer for Liquify" }
        val size = mutableInfo.value.size
        val selectionBaseline = if (mutableSelection.value is SelectionInfo.Active) engine.cloneTileStore(selectionMask) else 0L
        val spacing = liquifyMeshSpacingForBudget(size.width, size.height, meshSpacing, null)
        val mesh = try {
            NativeImageOpsBridge.LiquifySession(size.width, size.height, spacing)
        } catch (failure: Throwable) {
            engine.destroyTileStore(baseline)
            if (selectionBaseline != 0L) engine.destroyTileStore(selectionBaseline)
            throw failure
        }
        NativeLiquifySession(target, baseline, selectionBaseline, mesh).also { activeLiquify = it }
    }

    private inner class NativeLiquifySession(
        private val target: LayerId,
        private val baseline: Long,
        private val selectionBaseline: Long,
        private val mesh: NativeImageOpsBridge.LiquifySession,
    ) : app.croptop.core.engine.api.LiquifySession {
        private var finished = false
        private var freezeMask = 0L

        private fun closedResult(): CommandResult.Rejected = reject(RejectionCode.CLOSED, "Liquify session is closed")

        /** Re-renders the mesh over the baseline into the layer's live store. Holds [lock]. */
        private fun refresh(): CommandResult {
            val rendered = try {
                mesh.render(baseline, selectionHandle = selectionBaseline)
            } catch (failure: RuntimeException) {
                return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Liquify preview failed")
            }
            val previous = pixelStores.put(target, rendered)
            if (previous == null) {
                pixelStores.remove(target)
                engine.destroyTileStore(rendered)
                return reject(RejectionCode.NOT_FOUND, "The target layer no longer exists")
            }
            engine.destroyTileStore(previous)
            queueViewportComposite(mutableLayers.value)
            return CommandResult.Accepted(++sequence)
        }

        override fun stroke(stroke: app.croptop.core.engine.api.LiquifyStroke): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized closedResult()
            try {
                stroke.dabs.forEach { dab ->
                    mesh.apply(
                        NativeImageOpsBridge.LiquifyTool.entries[stroke.tool.ordinal],
                        dab.x, dab.y, dab.size, dab.density, dab.pressure, dab.rate, dab.dx, dab.dy,
                    )
                }
            } catch (failure: RuntimeException) {
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Liquify stroke failed")
            }
            refresh()
        }}

        override fun paintFreeze(points: List<app.croptop.core.model.DocPoint>, radius: Float, freeze: Boolean): CommandResult =
            withDocumentCommand { synchronized(lock) {
                if (finished || closed) return@synchronized closedResult()
                val stroke = try {
                    app.croptop.core.model.SamplingStroke(points, radius, freeze)
                } catch (failure: IllegalArgumentException) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Invalid freeze stroke")
                }
                try {
                    val next = NativeFillBridge.strokeMask(baseline, freezeMask, listOf(stroke))
                    mesh.setFreezeMask(next)
                    if (freezeMask != 0L) engine.destroyTileStore(freezeMask)
                    freezeMask = next
                } catch (failure: RuntimeException) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Freeze failed")
                }
                CommandResult.Accepted(sequence)
            }}

        override fun clearFreeze(): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized closedResult()
            mesh.setFreezeMask(0L)
            if (freezeMask != 0L) engine.destroyTileStore(freezeMask)
            freezeMask = 0L
            CommandResult.Accepted(sequence)
        }}

        override fun reconstructAll(amount: Float): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized closedResult()
            if (!amount.isFinite() || amount !in 0f..1f) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Reconstruct amount must be 0-1")
            mesh.reconstructAll(amount)
            refresh()
        }}

        override fun commit(): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized closedResult()
            val result = commit("Liquify", mutableLayers.value)
            if (result is CommandResult.Accepted) finish(committed = true)
            result
        }}

        override fun cancel() = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized
            if (!closed) {
                pixelStores.put(target, baseline)?.let(engine::destroyTileStore)
                queueViewportComposite(mutableLayers.value)
            } else {
                engine.destroyTileStore(baseline)
            }
            finish(committed = false)
        }}

        private fun finish(committed: Boolean) {
            if (finished) return
            finished = true
            if (committed) engine.destroyTileStore(baseline)
            if (selectionBaseline != 0L) engine.destroyTileStore(selectionBaseline)
            if (freezeMask != 0L) engine.destroyTileStore(freezeMask)
            freezeMask = 0L
            mesh.close()
            if (activeLiquify === this) activeLiquify = null
        }
    }

    override fun liquify(target: LayerId, stroke: app.croptop.core.engine.api.LiquifyStroke): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val node = tree.find(target) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${target.value}")
        if (node.kind != LayerKind.PIXEL) return@synchronized reject(RejectionCode.UNSUPPORTED, "Liquify currently edits pixel layers")
        if (node.locks.pixels) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” is locked")
        val source = pixelStores[target] ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "“${node.name}” has no pixels")
        val size = mutableInfo.value.size
        val hasSelection = mutableSelection.value is SelectionInfo.Active
        val selectionBounds = if (hasSelection) NativeSelectionBridge.bounds(selectionMask) else null
        val meshSpacing = liquifyMeshSpacingForBudget(size.width, size.height, stroke.meshSpacing, selectionBounds)
        var expandedSelection = 0L
        var freezeMask = 0L
        try {
            // A coarse mesh may have no nodes inside a narrow selection. Enable nearby nodes for
            // field construction, then clip the rendered result with the original exact mask.
            val selectionSpan = selectionBounds?.takeIf { it.size == 4 }
                ?.let { minOf((it[2] - it[0]).coerceAtLeast(1), (it[3] - it[1]).coerceAtLeast(1)) }
            val fieldSelection = if (hasSelection && selectionSpan != null && meshSpacing > selectionSpan) {
                expandedSelection = NativeSelectionBudgetBridge.modifyLike(selectionMask, selectionMask, SelectionModify.EXPAND.ordinal, meshSpacing.toFloat())
                if (expandedSelection == 0L) return@synchronized selectionFailure("Could not prepare Liquify for the active selection", budgetBridge = true)
                expandedSelection
            } else {
                selectionMask
            }
            if (hasSelection) {
                freezeMask = NativeSelectionBudgetBridge.invertLike(selectionMask, fieldSelection)
                if (freezeMask == 0L) return@synchronized selectionFailure("Could not constrain Liquify to the active selection", budgetBridge = true)
            }
            val result = NativeImageOpsBridge.LiquifySession(size.width, size.height, meshSpacing).use { session ->
                if (freezeMask != 0L) session.setFreezeMask(freezeMask)
                stroke.dabs.forEach { dab ->
                    session.apply(
                        tool = NativeImageOpsBridge.LiquifyTool.entries[stroke.tool.ordinal],
                        x = dab.x,
                        y = dab.y,
                        size = dab.size,
                        density = dab.density,
                        pressure = dab.pressure,
                        rate = dab.rate,
                        dx = dab.dx,
                        dy = dab.dy,
                    )
                }
                session.render(source, selectionHandle = if (hasSelection) selectionMask else 0L)
            }
            val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
                engine.destroyTileStore(result)
                return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve Liquify undo state")
            }
            prepared.remove(target)?.let(engine::destroyTileStore)
            prepared[target] = result
            commitPrepared("Liquify", tree, prepared)
        } catch (failure: Exception) {
            reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Liquify failed")
        } finally {
            if (freezeMask != 0L) NativeSelectionBridge.destroyStore(freezeMask)
            if (expandedSelection != 0L) NativeSelectionBridge.destroyStore(expandedSelection)
        }
    }}

    // ---- Text layers: editable TextLayerInfo, rendered into the layer's store ----

    private fun renderTextStore(text: app.croptop.core.model.TextLayerInfo): Pair<Long, DocRect>? {
        val size = mutableInfo.value.size
        val template = engine.createTileStore(size.width, size.height, pixelFormat)
        if (template == 0L) return null
        val rendered = try {
            engine.renderTextLayer(template, text, mutableInfo.value.resolutionPpi)
        } finally {
            engine.destroyTileStore(template)
        } ?: return null
        if (kotlin.math.abs(text.rotation % 360f) < 1e-3f) return rendered
        // Rotated type: render upright, then rotate the pixels about the origin in one bilinear pass.
        val (store, bounds) = rendered
        val radians = Math.toRadians(text.rotation.toDouble())
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        val ox = text.origin.x.toDouble()
        val oy = text.origin.y.toDouble()
        val matrix = doubleArrayOf(cos, -sin, ox - cos * ox + sin * oy, sin, cos, oy - sin * ox - cos * oy, 0.0, 0.0, 1.0)
        val rotated = engine.warpTileStore(store, size.width, size.height, matrix, RESAMPLE_BILINEAR)
        engine.destroyTileStore(store)
        if (rotated == 0L) return null
        return rotated to (transformBounds(bounds, Matrix3(matrix.map(Double::toFloat)), size) ?: bounds)
    }

    /**
     * [text] carried through [m]: origin mapped; uniform scale and rotation change size and rotation; an axis-aligned
     * non-uniform scale changes size (vertical) and box width (horizontal). Null for skew or mirroring.
     */
    private fun transformText(text: app.croptop.core.model.TextLayerInfo, m: Matrix3): app.croptop.core.model.TextLayerInfo? {
        if (m[2, 0] != 0f || m[2, 1] != 0f) return null
        val a = m[0, 0]
        val b = m[0, 1]
        val c = m[1, 0]
        val d = m[1, 1]
        val origin = m.map(text.origin)
        return when {
            kotlin.math.abs(b) < 1e-4f && kotlin.math.abs(c) < 1e-4f && a > 0f && d > 0f -> text.copy(
                origin = origin,
                character = text.character.copy(sizePt = text.character.sizePt * d).normalized(),
                boxWidth = text.boxWidth * a,
            )
            kotlin.math.abs(a - d) < 1e-3f && kotlin.math.abs(b + c) < 1e-3f && (a * d - b * c) > 0f -> {
                val scale = kotlin.math.hypot(a, c)
                val angle = Math.toDegrees(kotlin.math.atan2(c.toDouble(), a.toDouble())).toFloat()
                text.copy(
                    origin = origin,
                    character = text.character.copy(sizePt = text.character.sizePt * scale).normalized(),
                    boxWidth = text.boxWidth * scale,
                    rotation = ((text.rotation + angle) % 360f + 360f) % 360f,
                )
            }
            else -> null
        }
    }

    override fun createTextLayer(id: LayerId, text: app.croptop.core.model.TextLayerInfo): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        if (tree.find(id) != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer ${id.value} already exists")
        if (text.text.isBlank()) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Type some text first")
        val (store, bounds) = try {
            renderTextStore(text) ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not allocate the text layer")
        } catch (failure: Throwable) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not render the text")
        }
        val anchor = tree.primary
        val parent = anchor?.let { tree.parentOf(it)?.id }
        val siblings = anchor?.let { tree.parentOf(it)?.children } ?: tree.layers
        val index = siblings.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
        val name = text.text.lineSequence().first().trim().take(40).ifEmpty { "Text" }
        val next = NativeLayerTreeOps.insert(tree, LayerNode(id, name, LayerKind.TEXT, bounds = bounds, text = text), parent, index) ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the text layer")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        prepared[id] = store
        commitPrepared("Type Tool", next.copy(active = setOf(id), primary = id), prepared)
    }}

    override fun setTextLayer(id: LayerId, text: app.croptop.core.model.TextLayerInfo): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.TEXT) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a text layer")
        val (store, bounds) = try {
            renderTextStore(text) ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not allocate the text layer")
        } catch (failure: Throwable) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not render the text")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        prepared.remove(id)?.let(engine::destroyTileStore)
        prepared[id] = store
        val name = text.text.lineSequence().first().trim().take(40).ifEmpty { node.name }
        commitPrepared("Edit Type Layer", NativeLayerTreeOps.update(tree, id) { it.copy(name = name, bounds = bounds, text = text) }, prepared)
    }}

    // ---- Smart objects: immutable embedded sources, layer pixels are a rendered cache ----

    /** Sources by [SmartObjectInfo.sourceKey]; immutable, shared by history states and duplicates, freed on close. */
    private val smartSources = mutableMapOf<String, Long>()

    /** Smart-filter masks by [app.croptop.core.model.SmartFilterMask.key]: immutable A16 document-sized stores, freed on close. */
    private val filterMasks = mutableMapOf<String, Long>()

    internal fun restoreFilterMask(key: String, store: Long) = synchronized(lock) {
        filterMasks.put(key, store)?.let(engine::destroyTileStore)
    }

    /** Renders [info]'s source through its transform into a new document-sized store (one resample). Holds [lock]. */
    private fun renderSmartObject(info: app.croptop.core.model.SmartObjectInfo, size: DocSize): Long {
        val source = smartSources[info.sourceKey] ?: error("The smart object's embedded source is missing")
        var rendered = NativeSmartObjectBridge.Session(source).use { session ->
            session.setTransforms(listOf(info.transform.map(Float::toDouble).toDoubleArray()))
            session.render(size.width, size.height, NativeSmartObjectBridge.Quality.BICUBIC, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        // The shared filter mask picks between the placement (before any filter) and the finished stack.
        val mask = info.filterMask?.takeIf { it.enabled }?.let { filterMasks[it.key] }
        val activeFilters = info.filters.any { it.enabled && app.croptop.core.model.FilterCatalog.isFilter(it.specId) }
        val placement = if (mask != null && activeFilters) engine.cloneTileStore(rendered).also { check(it != 0L) { "Could not preserve the smart object placement" } } else 0L
        // Smart filters run in order on the transformed pixels.
        for (filter in info.filters) {
            if (!filter.enabled) continue
            if (!app.croptop.core.model.FilterCatalog.isFilter(filter.specId)) continue
            val effect = try {
                filterStore(rendered, filter.specId, filter.values, 0L)
            } catch (failure: RuntimeException) {
                engine.destroyTileStore(rendered)
                throw failure
            }
            val blended = try {
                check(filter.blendMode != BlendMode.PASS_THROUGH) { "Pass Through is not a smart-filter blend mode" }
                NativeSmartObjectBridge.blendFilter(
                    rendered,
                    effect,
                    filter.blendMode.ordinal - 1,
                    filter.opacity,
                    Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                )
            } catch (failure: RuntimeException) {
                engine.destroyTileStore(effect)
                engine.destroyTileStore(rendered)
                throw failure
            }
            engine.destroyTileStore(rendered)
            engine.destroyTileStore(effect)
            rendered = blended
        }
        if (placement != 0L) {
            try {
                val masked = NativeSmartObjectBridge.blendThroughMask(placement, rendered, checkNotNull(mask), Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                engine.destroyTileStore(rendered)
                rendered = masked
            } finally {
                engine.destroyTileStore(placement)
            }
        }
        return rendered
    }

    override fun setSmartFilters(id: LayerId, filters: List<AdjustmentInfo>): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        val info = node.smartObject ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a smart object")
        if (filters.any { app.croptop.core.model.FilterCatalog[it.specId] == null }) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Only Filter menu filters can be smart filters")
        }
        if (filters.any { it.blendMode == BlendMode.PASS_THROUGH }) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Pass Through is not a smart-filter blend mode")
        }
        val updated = info.copy(filters = filters)
        val rendered = try {
            renderSmartObject(updated, mutableInfo.value.size)
        } catch (failure: RuntimeException) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not render the smart filters")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(rendered)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        // Keep the layer's mask on the new cache.
        node.mask?.let { maskInfo ->
            pixelStores[id]?.let { old ->
                runCatching {
                    val mask = NativeLayerMaskBridge.fromLayerStore(old)
                    try {
                        NativeLayerMaskBridge.attach(rendered, mask, maskInfo)
                    } finally {
                        NativeLayerMaskBridge.destroy(mask)
                    }
                }
            }
        }
        prepared.put(id, rendered)?.let(engine::destroyTileStore)
        commitPrepared("Smart Filters", NativeLayerTreeOps.update(tree, id) { it.copy(smartObject = updated) }, prepared)
    }}

    override fun setRawDevelopSettings(id: LayerId, settings: app.croptop.core.model.DevelopSettings): CommandResult {
        class Plan(val info: app.croptop.core.model.SmartObjectInfo, val bytes: ByteArray, val revision: Long, val tree: LayerTree)
        var planFailure: CommandResult? = null
        val plan = withDocumentCommand { synchronized(lock) {
            when {
                closed || closing.get() -> { planFailure = reject(RejectionCode.CLOSED, "Document is closed"); null }
                else -> {
                    val tree = mutableLayers.value
                    val node = tree.find(id)
                    val info = node?.smartObject
                    val raw = info?.raw
                    when {
                        node == null -> { planFailure = reject(RejectionCode.NOT_FOUND, "No layer ${id.value}"); null }
                        raw == null -> { planFailure = reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a RAW smart object"); null }
                        else -> {
                            val bytes = rawFiles[raw.fileKey]
                            if (bytes == null) {
                                planFailure = reject(RejectionCode.UNSUPPORTED, "The original RAW file is no longer stored in this document")
                                null
                            } else {
                                Plan(info, bytes, sequence, tree)
                            }
                        }
                    }
                }
            }
        }} ?: return checkNotNull(planFailure)
        val opened = try {
            engine.developRaw(plan.bytes, settings)
        } catch (failure: Exception) {
            return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not develop the RAW file")
        }
        return withDocumentCommand { synchronized(lock) {
            fun discard(code: RejectionCode, message: String): CommandResult {
                engine.destroyTileStore(opened.storeHandle)
                return reject(code, message)
            }
            when {
                closed || closing.get() -> discard(RejectionCode.CLOSED, "Document is closed")
                sequence != plan.revision || mutableLayers.value != plan.tree ->
                    discard(RejectionCode.CANCELLED, "Document changed while the RAW was developing")
                opened.width != plan.info.sourceWidth || opened.height != plan.info.sourceHeight ->
                    discard(RejectionCode.INVALID_ARGUMENT, "These settings change the image size (crop or geometry); apply them in Develop before opening in the editor")
                else -> commitRawRedevelopment(id, plan.info, opened.storeHandle, settings)
            }
        }}
    }

    /** Holds [lock]. Installs [developed] as the smart object's new source and re-renders its cache in one history step. */
    private fun commitRawRedevelopment(
        id: LayerId,
        info: app.croptop.core.model.SmartObjectInfo,
        developed: Long,
        settings: app.croptop.core.model.DevelopSettings,
    ): CommandResult {
        val tree = mutableLayers.value
        val raw = checkNotNull(info.raw)
        val key = CroptopManifest.newLayerUuid()
        smartSources[key] = developed
        val updated = info.copy(sourceKey = key, raw = raw.copy(settings = settings))
        val rendered = try {
            renderSmartObject(updated, mutableInfo.value.size)
        } catch (failure: RuntimeException) {
            smartSources.remove(key)?.let(engine::destroyTileStore)
            return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not render the developed RAW")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(rendered)
            return reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        tree.find(id)?.mask?.let { maskInfo ->
            pixelStores[id]?.let { old ->
                runCatching {
                    val mask = NativeLayerMaskBridge.fromLayerStore(old)
                    try {
                        NativeLayerMaskBridge.attach(rendered, mask, maskInfo)
                    } finally {
                        NativeLayerMaskBridge.destroy(mask)
                    }
                }
            }
        }
        prepared.put(id, rendered)?.let(engine::destroyTileStore)
        return commitPrepared("Develop Settings", NativeLayerTreeOps.update(tree, id) { it.copy(smartObject = updated) }, prepared)
    }

    /** Re-renders [updated] as smart object [id]'s new cache and commits it as one step. Holds [lock]. */
    private fun commitSmartObjectInfo(id: LayerId, updated: app.croptop.core.model.SmartObjectInfo, label: String): CommandResult {
        val tree = mutableLayers.value
        val rendered = try {
            renderSmartObject(updated, mutableInfo.value.size)
        } catch (failure: RuntimeException) {
            return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not render the smart object")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(rendered)
            return reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        tree.find(id)?.mask?.let { maskInfo ->
            pixelStores[id]?.let { old ->
                runCatching {
                    val mask = NativeLayerMaskBridge.fromLayerStore(old)
                    try {
                        NativeLayerMaskBridge.attach(rendered, mask, maskInfo)
                    } finally {
                        NativeLayerMaskBridge.destroy(mask)
                    }
                }
            }
        }
        prepared.put(id, rendered)?.let(engine::destroyTileStore)
        return commitPrepared(label, NativeLayerTreeOps.update(tree, id) { it.copy(smartObject = updated) }, prepared)
    }

    /** The smart object [id] and its info, or a rejection. Holds [lock]. */
    private fun smartObjectOrReject(id: LayerId): Pair<LayerNode, app.croptop.core.model.SmartObjectInfo>? {
        if (closed) return null
        val node = mutableLayers.value.find(id) ?: return null
        return node.smartObject?.let { node to it }
    }

    override fun addSmartFilterMask(id: LayerId, fill: app.croptop.core.engine.api.MaskFill): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val (node, info) = smartObjectOrReject(id) ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object to add a filter mask")
        if (info.filterMask != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” already has a filter mask")
        if (fill.needsSelection && mutableSelection.value == SelectionInfo.None) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "${fill.label} needs a selection")
        }
        val like = pixelStores[id] ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "“${node.name}” has no pixels")
        val store = newFilterMaskStore(like, fill)
        if (store == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not create the filter mask")
        val key = CroptopManifest.newLayerUuid()
        filterMasks[key] = store
        commitSmartObjectInfo(id, info.copy(filterMask = app.croptop.core.model.SmartFilterMask(key)), "Add Smart Filter Mask")
    }}

    private fun newFilterMaskStore(like: Long, fill: app.croptop.core.engine.api.MaskFill): Long = when (fill) {
        app.croptop.core.engine.api.MaskFill.HIDE_ALL -> NativeSelectionBudgetBridge.emptyA16MaskLike(like)
        app.croptop.core.engine.api.MaskFill.REVEAL_ALL -> NativeSelectionBudgetBridge.emptyA16MaskLike(like).let { empty ->
            if (empty == 0L) 0L else NativeSelectionBudgetBridge.invertLike(like, empty).also { engine.destroyTileStore(empty) }
        }
        app.croptop.core.engine.api.MaskFill.REVEAL_SELECTION -> engine.cloneTileStore(selectionMask)
        app.croptop.core.engine.api.MaskFill.HIDE_SELECTION -> NativeSelectionBudgetBridge.invertLike(like, selectionMask)
    }

    override fun setSmartFilterMaskEnabled(id: LayerId, enabled: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val (_, info) = smartObjectOrReject(id) ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object")
        val mask = info.filterMask ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The smart object has no filter mask")
        if (mask.enabled == enabled) return@synchronized CommandResult.Accepted(++sequence)
        commitSmartObjectInfo(id, info.copy(filterMask = mask.copy(enabled = enabled)), if (enabled) "Enable Smart Filter Mask" else "Disable Smart Filter Mask")
    }}

    override fun deleteSmartFilterMask(id: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val (_, info) = smartObjectOrReject(id) ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object")
        if (info.filterMask == null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The smart object has no filter mask")
        commitSmartObjectInfo(id, info.copy(filterMask = null), "Delete Smart Filter Mask")
    }}

    override fun invertSmartFilterMask(id: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val (_, info) = smartObjectOrReject(id) ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object")
        val mask = info.filterMask ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The smart object has no filter mask")
        val current = filterMasks[mask.key] ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "The filter mask is missing")
        val inverted = NativeSelectionBudgetBridge.invertLike(current, current)
        if (inverted == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not invert the filter mask")
        val key = CroptopManifest.newLayerUuid()
        filterMasks[key] = inverted
        commitSmartObjectInfo(id, info.copy(filterMask = mask.copy(key = key)), "Invert Smart Filter Mask")
    }}

    override fun paintSmartFilterMask(id: LayerId, points: List<DocPoint>, radius: Float, reveal: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val (_, info) = smartObjectOrReject(id) ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select a smart object")
        val mask = info.filterMask ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Add a filter mask first")
        val current = filterMasks[mask.key] ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "The filter mask is missing")
        val like = pixelStores[id] ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "The smart object has no pixels")
        val stroke = try {
            app.croptop.core.model.SamplingStroke(points, radius, reveal)
        } catch (failure: IllegalArgumentException) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Invalid mask stroke")
        }
        val next = try {
            NativeFillBridge.strokeMask(like, current, listOf(stroke))
        } catch (failure: RuntimeException) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not paint the filter mask")
        }
        val key = CroptopManifest.newLayerUuid()
        filterMasks[key] = next
        commitSmartObjectInfo(id, info.copy(filterMask = mask.copy(key = key)), if (reveal) "Paint Smart Filter Mask (Reveal)" else "Paint Smart Filter Mask (Hide)")
    }}

    /** A project or recovery journal supplies a smart object's source (loaded under its key). */
    internal fun restoreSmartSource(key: String, store: Long) = synchronized(lock) {
        smartSources.put(key, store)?.let(engine::destroyTileStore)
    }

    /** Library patterns referenced by fill layers and pattern overlays in [tree]. */
    private fun usedPatternIds(tree: LayerTree): Set<String> = tree.flatten().flatMap { node ->
        listOfNotNull(
            node.fill?.let { fill -> fill.patternId.takeIf { fill.kind == app.croptop.core.model.FillKind.PATTERN } },
            node.styles?.patternOverlay?.takeIf { it.enabled }?.patternId,
        )
    }.filter { JOURNAL_NAME.matches(it) }.toSet()

    /** Smart object sources for saving: key → caller-owned clone. Holds [lock]. */
    private fun cloneSmartSources(tree: LayerTree): Map<String, Long> {
        val keys = tree.flatten().flatMap {
            listOfNotNull(it.smartObject?.sourceKey, it.hiddenPixels?.sourceKey, it.smartObject?.filterMask?.key)
        }.toSet()
        val clones = linkedMapOf<String, Long>()
        for (key in keys) {
            val source = smartSources[key] ?: filterMasks[key] ?: continue
            val clone = engine.cloneTileStore(source)
            if (clone == 0L) {
                clones.values.forEach(engine::destroyTileStore)
                throw IOException("Could not snapshot a smart object's source")
            }
            clones[key] = clone
        }
        return clones
    }

    override fun convertToSmartObject(id: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.PIXEL && node.kind != LayerKind.TEXT) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Only pixel and type layers can become smart objects")
        }
        val live = pixelStores[id] ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "“${node.name}” has no pixels")
        val source = engine.cloneTileStore(live)
        if (source == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not embed the layer's pixels")
        // The source is the bare pixels: mask, clipping and styles stay on the layer.
        runCatching { NativeLayerMaskBridge.detach(source) }
        NativeLayerMaskBridge.setClipped(source, false)
        NativeLayerStylesBridge.attach(source, null)
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(source)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        val key = CroptopManifest.newLayerUuid()
        smartSources[key] = source
        val docSize = mutableInfo.value.size
        val info = app.croptop.core.model.SmartObjectInfo(sourceKey = key, sourceWidth = docSize.width, sourceHeight = docSize.height)
        commitPrepared(
            "Convert to Smart Object",
            NativeLayerTreeOps.update(tree, id) { it.copy(kind = LayerKind.SMART_OBJECT, smartObject = info, text = null) },
            prepared,
        )
    }}

    override fun openSmartObjectContents(id: LayerId): DocumentHandle {
        val (name, size, store) = synchronized(lock) {
            check(!closed) { "Document is closed" }
            val node = checkNotNull(mutableLayers.value.find(id)) { "No layer ${id.value}" }
            val info = checkNotNull(node.smartObject) { "“${node.name}” isn't a smart object" }
            val source = checkNotNull(smartSources[info.sourceKey]) { "The smart object's embedded source is missing" }
            val clone = engine.cloneTileStore(source)
            check(clone != 0L) { "Could not open the smart object's contents" }
            runCatching { NativeLayerMaskBridge.detach(clone) }
            val docSize = mutableInfo.value.size
            val size = if (info.sourceWidth > 0 && info.sourceHeight > 0) DocSize(info.sourceWidth, info.sourceHeight) else docSize
            Triple("${node.name}.psb", size, clone)
        }
        val info = mutableInfo.value
        return try {
            engine.documentFromStore(name, size, info.depth, info.profile, info.resolutionPpi, pixelFormat, store)
        } catch (failure: Throwable) {
            engine.destroyTileStore(store)
            throw failure
        }
    }

    /** Flattened visible pixels of this document (caller owns the store). */
    internal fun flattenedContents(): Long = synchronized(lock) {
        check(!closed) { "The contents document is closed" }
        flattenVisible(mutableLayers.value)
    }

    override fun replaceSmartObjectContents(id: LayerId, contents: DocumentHandle): CommandResult = withDocumentCommand {
        val other = contents as? NativeDocument ?: return@withDocumentCommand reject(RejectionCode.INVALID_ARGUMENT, "Those contents come from a different engine")
        if (other === this) return@withDocumentCommand reject(RejectionCode.INVALID_ARGUMENT, "A document can't be its own smart object contents")
        // Flatten outside this document's lock (the contents document has its own).
        val flat = try {
            other.flattenedContents()
        } catch (failure: RuntimeException) {
            return@withDocumentCommand reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not read the edited contents")
        }
        val contentsSize = other.info.value.size
        synchronized(lock) {
            if (closed) {
                engine.destroyTileStore(flat)
                return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
            }
            finishSelectionTransformBeforeMutation()
            val tree = mutableLayers.value
            val node = tree.find(id)
            val current = node?.smartObject
            if (node == null || current == null) {
                engine.destroyTileStore(flat)
                return@synchronized reject(RejectionCode.NOT_FOUND, "The smart object no longer exists")
            }
            val key = CroptopManifest.newLayerUuid()
            smartSources[key] = flat
            val docSize = mutableInfo.value.size
            val updated = current.withReplacedSource(key, contentsSize.width, contentsSize.height, docSize.width, docSize.height)
            val rendered = try {
                renderSmartObject(updated, mutableInfo.value.size)
            } catch (failure: RuntimeException) {
                return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not render the new contents")
            }
            node.mask?.let { maskInfo ->
                pixelStores[id]?.let { old ->
                    runCatching {
                        val mask = NativeLayerMaskBridge.fromLayerStore(old)
                        try {
                            NativeLayerMaskBridge.attach(rendered, mask, maskInfo)
                        } finally {
                            NativeLayerMaskBridge.destroy(mask)
                        }
                    }
                }
            }
            val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
                engine.destroyTileStore(rendered)
                return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
            }
            prepared.put(id, rendered)?.let(engine::destroyTileStore)
            commitPrepared("Edit Contents", NativeLayerTreeOps.update(tree, id) { it.copy(smartObject = updated) }, prepared)
        }
    }

    override fun rasterizeSmartObject(id: LayerId): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.SMART_OBJECT) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a smart object")
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        commitPrepared("Rasterize Layer", NativeLayerTreeOps.update(tree, id) { it.copy(kind = LayerKind.PIXEL, smartObject = null) }, prepared)
    }}

    // ---- Layer styles (fx): attached to the layer store, drawn by every composite ----

    override fun setLayerStyles(id: LayerId, styles: app.croptop.core.model.LayerStyles?): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.PIXEL && node.kind != LayerKind.TEXT) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Layer styles apply to pixel and type layers")
        }
        if (node.styles == styles) return@synchronized CommandResult.Accepted(sequence)
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        val store = prepared[id] ?: run {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "“${node.name}” has no pixels")
        }
        NativeLayerStylesBridge.attach(store, styles)?.let { failure ->
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure)
        }
        val enabled = styles?.anyEnabled == true
        commitPrepared(
            if (node.styles == null) "Layer Style" else "Edit Layer Style",
            NativeLayerTreeOps.update(tree, id) { it.copy(styles = styles, hasStyles = enabled) },
            prepared,
        )
    }}

    /** Transient Blend If values shown by the viewport until the next committed change; see [previewBlendIf]. */
    private val blendIfPreview = mutableMapOf<LayerId, app.croptop.core.model.BlendIfSettings>()
    private val fillOpacityPreview = mutableMapOf<LayerId, Float>()

    override fun previewFillOpacity(id: LayerId, fillOpacity: Float?): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (fillOpacity != null && (!fillOpacity.isFinite() || fillOpacity !in 0f..1f)) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Fill opacity must be between 0 and 1")
        }
        val tree = mutableLayers.value
        tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (fillOpacity == null) fillOpacityPreview.remove(id) else fillOpacityPreview[id] = fillOpacity
        queueViewportComposite(tree)
        CommandResult.Accepted(sequence)
    }}

    override fun previewBlendIf(id: LayerId, blendIf: app.croptop.core.model.BlendIfSettings?): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (pixelStores[id] == null) return@synchronized reject(RejectionCode.UNSUPPORTED, "“${node.name}” has no pixels")
        if (blendIf == null) blendIfPreview.remove(id) else blendIfPreview[id] = blendIf
        queueViewportComposite(tree)
        CommandResult.Accepted(sequence)
    }}

    override fun previewLayerStyles(id: LayerId, styles: app.croptop.core.model.LayerStyles?): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        val store = pixelStores[id] ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "“${node.name}” has no pixels")
        // The live store only: history snapshots hold their own handles, so undo still shows the committed styles.
        NativeLayerStylesBridge.attach(store, styles)?.let { return@synchronized reject(RejectionCode.INVALID_ARGUMENT, it) }
        queueViewportComposite(tree)
        CommandResult.Accepted(sequence)
    }}

    /** After loading a project: layer styles are attached to their layers' stores again. */
    internal fun restoreLayerStyles() = synchronized(lock) {
        mutableLayers.value.flatten().forEach { node ->
            val styles = node.styles?.takeIf { it.anyEnabled } ?: return@forEach
            pixelStores[node.id]?.let { NativeLayerStylesBridge.attach(it, styles) }
        }
    }

    /** After loading a project: text layers are re-rendered from their saved content. */
    internal fun restoreTextLayers() = synchronized(lock) {
        mutableLayers.value.flatten().filter { it.kind == LayerKind.TEXT && it.text != null }.forEach { node ->
            val rendered = runCatching { renderTextStore(checkNotNull(node.text)) }.getOrNull() ?: return@forEach
            pixelStores.put(node.id, rendered.first)?.let(engine::destroyTileStore)
        }
    }

    // ---- Adjustment layers: an empty document-sized store carrying a baked LUT the compositor applies below ----

    override fun addAdjustmentLayer(id: LayerId, adjustment: AdjustmentInfo): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        if (tree.find(id) != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer ${id.value} already exists")
        val spec = AdjustmentCatalog[adjustment.specId]
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Unknown adjustment: ${adjustment.specId}")
        val normalized = adjustment.copy(values = spec.normalize(adjustment.values))
        val size = mutableInfo.value.size
        val store = engine.createTileStore(size.width, size.height, pixelFormat)
        if (store == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not allocate the adjustment layer")
        engine.setAdjustmentLayerLut(store, normalized)?.let { failure ->
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.UNSUPPORTED, failure)
        }
        val anchorId = tree.primary
        val parent = anchorId?.let { tree.parentOf(it)?.id }
        val siblings = anchorId?.let { tree.parentOf(it)?.children } ?: tree.layers
        val index = siblings.indexOfFirst { it.id == anchorId }.coerceAtLeast(0)
        val node = LayerNode(id, spec.name, LayerKind.ADJUSTMENT, adjustment = normalized)
        val next = NativeLayerTreeOps.insert(tree, node, parent, index) ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the adjustment layer")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        prepared[id] = store
        commitPrepared("New ${spec.name} Layer", next.copy(active = setOf(id), primary = id), prepared)
    }}

    // ---- Patterns ----

    override fun definePattern(name: String): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val size = mutableInfo.value.size
        val area = activeSelectionBounds()?.intersect(DocRect.of(size))?.takeIf { !it.isEmpty } ?: DocRect.of(size)
        if (area.width > 4096 || area.height > 4096) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Patterns are limited to 4096 × 4096 px; select a smaller area")
        }
        val flat = try {
            flattenVisible(mutableLayers.value)
        } catch (failure: RuntimeException) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Nothing is visible")
        }
        val tile = try {
            engine.transformTileStore(flat, GEOMETRY_CANVAS_SIZE, area.width, area.height, -area.left, -area.top)
        } finally {
            engine.destroyTileStore(flat)
        }
        if (tile == 0L) {
            val detail = engine.geometryError().takeIf(String::isNotBlank) ?: "Could not capture the pattern"
            return@synchronized reject(RejectionCode.UNSUPPORTED, detail)
        }
        try {
            engine.addPattern(name, tile, area.width, area.height)
        } catch (failure: RuntimeException) {
            engine.destroyTileStore(tile)
            return@synchronized reject(
                RejectionCode.UNSUPPORTED,
                failure.message?.takeIf(String::isNotBlank) ?: "Could not save the pattern",
            )
        }
        CommandResult.Accepted(sequence)
    }}

    // ---- Fill layers (Solid Color, Gradient, Pattern): rendered across the canvas from their settings ----

    /** A new canvas-sized store painted with [fill]; 0 on failure. Holds [lock]. */
    private fun renderFillStore(fill: app.croptop.core.model.FillLayerInfo, size: DocSize): Long {
        val store = engine.createTileStore(size.width, size.height, pixelFormat)
        if (store == 0L) return 0L
        if (fill.kind == app.croptop.core.model.FillKind.PATTERN) {
            // Tile the pattern by drawing it as a pattern overlay over an opaque layer and keeping that effect.
            val pattern = fill.patternId?.let(engine::patternStore)?.takeIf { it != 0L }
            val opaque = pattern?.let { NativeFillBridge.solid(store, floatArrayOf(1f, 1f, 1f, 1f), 1f, 0, false) }
            if (pattern == null || opaque != null) {
                engine.destroyTileStore(store)
                return 0L
            }
            val effects = runCatching {
                NativeLayerStylesBridge.render(
                    store,
                    NativeLayerStylesBridge.Styles(patternOverlay = NativeLayerStylesBridge.PatternOverlay(true, 0, 1f, fill.scale.coerceIn(0.01f, 10f))),
                    pattern,
                )
            }.getOrNull()
            engine.destroyTileStore(store)
            val tiled = effects?.firstOrNull()?.storeHandle ?: 0L
            effects?.drop(1)?.forEach { engine.destroyTileStore(it.storeHandle) }
            return tiled
        }
        val failure = when (fill.kind) {
            app.croptop.core.model.FillKind.PATTERN -> null
            app.croptop.core.model.FillKind.SOLID ->
                NativeFillBridge.solid(store, floatArrayOf(fill.color.r, fill.color.g, fill.color.b, fill.color.a), 1f, 0, false)
            app.croptop.core.model.FillKind.GRADIENT -> {
                // Photoshop's gradient fill: a line through the canvas centre at [angle], spanning the canvas at scale 1.
                val radians = Math.toRadians(fill.angle.toDouble())
                val dx = kotlin.math.cos(radians).toFloat()
                val dy = -kotlin.math.sin(radians).toFloat()
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radial = fill.shape == app.croptop.core.model.GradientShape.RADIAL || fill.shape == app.croptop.core.model.GradientShape.DIAMOND
                val half = fill.scale.coerceIn(0.1f, 1.5f) * if (radial) {
                    kotlin.math.hypot(size.width.toFloat(), size.height.toFloat()) / 2f
                } else {
                    (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2f
                }
                val start = if (radial || fill.shape == app.croptop.core.model.GradientShape.ANGLE || fill.shape == app.croptop.core.model.GradientShape.REFLECTED) {
                    DocPoint(cx, cy)
                } else {
                    DocPoint(cx - dx * half, cy - dy * half)
                }
                NativeGradientBridge.render(store, start, DocPoint(cx + dx * half, cy + dy * half), fill.gradient, fill.shape, fill.reverse, true, 1f)
            }
        }
        if (failure != null) {
            engine.destroyTileStore(store)
            return 0L
        }
        return store
    }

    override fun addFillLayer(id: LayerId, fill: app.croptop.core.model.FillLayerInfo): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        if (tree.find(id) != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer ${id.value} already exists")
        val store = renderFillStore(fill, mutableInfo.value.size)
        if (store == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not paint the fill layer")
        val anchorId = tree.primary
        val parent = anchorId?.let { tree.parentOf(it)?.id }
        val siblings = anchorId?.let { tree.parentOf(it)?.children } ?: tree.layers
        val index = siblings.indexOfFirst { it.id == anchorId }.coerceAtLeast(0)
        val name = when (fill.kind) {
            app.croptop.core.model.FillKind.SOLID -> "Color Fill"
            app.croptop.core.model.FillKind.GRADIENT -> "Gradient Fill"
            app.croptop.core.model.FillKind.PATTERN -> "Pattern Fill"
        }
        val next = NativeLayerTreeOps.insert(tree, LayerNode(id, name, LayerKind.FILL, fill = fill), parent, index) ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the fill layer")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        prepared[id] = store
        commitPrepared("New Fill Layer", next.copy(active = setOf(id), primary = id), prepared)
    }}

    override fun setFillLayer(id: LayerId, fill: app.croptop.core.model.FillLayerInfo): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.FILL) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't a fill layer")
        val store = renderFillStore(fill, mutableInfo.value.size)
        if (store == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not paint the fill layer")
        // The fresh store takes over the layer's mask (styles and clipping are re-applied by commitPrepared).
        node.mask?.let { info ->
            pixelStores[id]?.let { old ->
                runCatching {
                    val mask = NativeLayerMaskBridge.fromLayerStore(old)
                    try {
                        NativeLayerMaskBridge.attach(store, mask, info)
                    } finally {
                        NativeLayerMaskBridge.destroy(mask)
                    }
                }
            }
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        prepared.put(id, store)?.let(engine::destroyTileStore)
        commitPrepared("Edit Fill Layer", NativeLayerTreeOps.update(tree, id) { it.copy(fill = fill) }, prepared)
    }}

    /** After loading a project: adjustment layers get their (unsaved) store back with the LUT re-baked. */
    internal fun restoreAdjustmentLayers() = synchronized(lock) {
        val size = mutableInfo.value.size
        mutableLayers.value.flatten().filter { it.kind == LayerKind.ADJUSTMENT && it.adjustment != null }.forEach { node ->
            val store = pixelStores[node.id] ?: engine.createTileStore(size.width, size.height, pixelFormat).takeIf { it != 0L }?.also {
                pixelStores[node.id] = it
            } ?: return@forEach
            engine.setAdjustmentLayerLut(store, checkNotNull(node.adjustment))
        }
    }

    override fun setAdjustment(id: LayerId, values: Map<String, ParamValue>, preview: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        val current = node.adjustment
        if (node.kind != LayerKind.ADJUSTMENT || current == null) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${node.name}” isn't an adjustment layer")
        }
        val spec = AdjustmentCatalog[current.specId]
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Unknown adjustment: ${current.specId}")
        val updated = current.copy(values = spec.normalize(current.values + values))
        val live = pixelStores[id] ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Adjustment layer storage is unavailable")
        if (preview) {
            // Slider drags: recolour the live store only; history keeps the last committed LUT.
            engine.setAdjustmentLayerLut(live, updated)?.let { return@synchronized reject(RejectionCode.INVALID_ARGUMENT, it) }
            queueViewportComposite(tree)
            return@synchronized CommandResult.Accepted(sequence)
        }
        finishSelectionTransformBeforeMutation()
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        val target = prepared[id] ?: run {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Adjustment layer storage is unavailable")
        }
        engine.setAdjustmentLayerLut(target, updated)?.let { failure ->
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure)
        }
        commitPrepared("Edit ${AdjustmentCatalog[updated.specId]?.name ?: "Adjustment"}", NativeLayerTreeOps.update(tree, id) { it.copy(adjustment = updated) }, prepared)
    }}

    // ---- Live selection sessions (Quick Selection, Color Range): previews swap the live mask, end/commit adds one step ----

    /** Shows [mask] as the current selection without a history step; frees the previous preview mask. */
    private fun showSelectionPreview(mask: Long, keep: Long) {
        val old = selectionMask
        selectionMask = mask
        mutableSelectionMaskRevision.value++
        mutableSelection.value = selectionInfo(mask, false)
        if (old != keep && old != mask && old != 0L) engine.destroyTileStore(old)
    }

    /** Puts the pre-session selection back (dropping any preview mask). */
    private fun restoreSelectionBefore(previousMask: Long, previousInfo: SelectionInfo) {
        if (selectionMask != previousMask && selectionMask != 0L) engine.destroyTileStore(selectionMask)
        selectionMask = previousMask
        mutableSelectionMaskRevision.value++
        mutableSelection.value = previousInfo
    }

    override fun beginQuickSelection(spec: QuickSelectionSpec): QuickSelectionSession = withDocumentCommand { synchronized(lock) {
        check(!closed) { "Document is closed" }
        finishSelectionTransformBeforeMutation()
        val (source, ownsSource) = if (spec.sampleAll) {
            flattenVisible(mutableLayers.value) to true
        } else {
            val (found, error) = primaryPixel("Quick Selection")
            val store = found?.second ?: return@synchronized app.croptop.core.engine.api.UnsupportedQuickSelectionSession(
                (error as? CommandResult.Rejected)?.message ?: "Select a pixel layer first",
            )
            store to false
        }
        // A new (replace) stroke starts from nothing; add/subtract start from the current selection.
        val before = if (spec.op == SelectionOp.REPLACE) NativeSelectionBudgetBridge.emptyA16MaskLike(selectionMask) else selectionMask
        val session = if (before == 0L) 0L else NativeSelectionBridge.quickBegin(source, before, spec.diameter, spec.op == SelectionOp.SUBTRACT, spec.enhanceEdge)
        if (spec.op == SelectionOp.REPLACE && before != 0L) NativeSelectionBridge.destroyStore(before)
        if (session == 0L) {
            if (ownsSource) engine.destroyTileStore(source)
            return@synchronized app.croptop.core.engine.api.UnsupportedQuickSelectionSession(
                (if (before == 0L) NativeSelectionBudgetBridge.nativeLastError() else NativeSelectionBridge.nativeLastError())
                    .ifBlank { "Quick Selection could not start" },
            )
        }
        NativeQuickSelection(session, source, ownsSource, selectionMask, mutableSelection.value)
    }}

    private inner class NativeQuickSelection(
        private val session: Long,
        private val source: Long,
        private val ownsSource: Boolean,
        private val previousMask: Long,
        private val previousInfo: SelectionInfo,
    ) : QuickSelectionSession {
        private var finished = false

        private fun release() {
            NativeSelectionBridge.quickDestroy(session)
            if (ownsSource) engine.destroyTileStore(source)
        }

        override fun add(points: FloatArray) = withDocumentCommand { synchronized(lock) {
            if (finished || closed || points.isEmpty() || points.size % 2 != 0) return@synchronized
            if (!NativeSelectionBridge.quickAdd(session, points)) return@synchronized
            val current = NativeSelectionBridge.quickCurrent(session)
            if (current != 0L) showSelectionPreview(current, previousMask)
        }}

        override fun end(): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "This Quick Selection stroke already ended")
            finished = true
            if (closed) {
                release()
                return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
            }
            val result = NativeSelectionBridge.quickFinish(session)
            release()
            restoreSelectionBefore(previousMask, previousInfo)
            if (result == 0L) return@synchronized selectionFailure("Quick Selection failed")
            if (selectionInfo(result, false) == SelectionInfo.None) {
                engine.destroyTileStore(result)
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Quick Selection did not select any pixels")
            }
            installSelection(result, selectionInfo(result, false), "Quick Selection")
        }}

        override fun cancel() = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized
            finished = true
            release()
            if (!closed) restoreSelectionBefore(previousMask, previousInfo)
        }}
    }

    override fun beginColorRange(op: SelectionOp): ColorRangeSession = withDocumentCommand { synchronized(lock) {
        check(!closed) { "Document is closed" }
        finishSelectionTransformBeforeMutation()
        // Color Range samples what's visible, like Photoshop's default "Sample Merged".
        val source = flattenVisible(mutableLayers.value)
        NativeColorRange(op, source, selectionMask, mutableSelection.value)
    }}

    private inner class NativeColorRange(
        private val op: SelectionOp,
        private val source: Long,
        private val previousMask: Long,
        private val previousInfo: SelectionInfo,
    ) : ColorRangeSession {
        private var finished = false
        private var latest = 0L  // the last computed range, before combining with the previous selection

        private fun compute(spec: ColorRangeSpec): Long {
            val samples = FloatArray(spec.samples.size * 3)
            spec.samples.forEachIndexed { i, c ->
                samples[i * 3] = c.r
                samples[i * 3 + 1] = c.g
                samples[i * 3 + 2] = c.b
            }
            val range = NativeSelectionBridge.colorRange(source, spec.target.ordinal, samples, spec.fuzziness, spec.invert, 0)
            if (range == 0L) return 0L
            return NativeSelectionBudgetBridge.combineLike(previousMask, previousMask, range, op.ordinal)
                .also { NativeSelectionBridge.destroyStore(range) }
        }

        override fun update(spec: ColorRangeSpec) = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized
            if (spec.target == app.croptop.core.engine.api.ColorRangeTarget.SAMPLED && spec.samples.isEmpty()) return@synchronized
            val next = compute(spec)
            if (next == 0L) return@synchronized
            latest = next
            showSelectionPreview(next, previousMask)
        }}

        override fun commit(): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Color Range already finished")
            finished = true
            engine.destroyTileStore(source)
            if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
            val result = latest
            if (result == 0L) {
                restoreSelectionBefore(previousMask, previousInfo)
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Pick a colour first")
            }
            // Hand the preview mask to history: the live mask goes back to the previous one, then installs.
            selectionMask = previousMask
            mutableSelection.value = previousInfo
            installSelection(result, selectionInfo(result, false), "Color Range")
        }}

        override fun cancel() = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized
            finished = true
            engine.destroyTileStore(source)
            if (!closed) restoreSelectionBefore(previousMask, previousInfo)
        }}
    }

    // ---- Object Selection / Select Subject (colour-model fallback until segmentation models ship) ----

    override fun selectObject(region: SelectionShape, op: SelectionOp, sampleAll: Boolean): EngineJob =
        withDocumentCommand { EngineJob.completed(synchronized(lock) { objectSelection(region, op, sampleAll, subject = false) }) }

    override fun selectSubject(op: SelectionOp, sampleAll: Boolean): EngineJob =
        withDocumentCommand { EngineJob.completed(synchronized(lock) { objectSelection(null, op, sampleAll, subject = true) }) }

    override fun selectFromMask(mask: CoarseMask, op: SelectionOp, sampleAll: Boolean, label: String): CommandResult =
        withDocumentCommand { synchronized(lock) {
            if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
            finishSelectionTransformBeforeMutation()
            val size = mutableInfo.value.size
            if (mask.width > size.width || mask.height > size.height) {
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The model mask is larger than the image")
            }
            val (source, owned) = if (sampleAll) {
                try {
                    flattenVisible(mutableLayers.value) to true
                } catch (failure: Throwable) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Nothing is visible to select from")
                }
            } else {
                val (found, error) = primaryPixel(label)
                (found ?: return@synchronized error!!).second to false
            }
            val upsampled = try {
                if (mask.refine) {
                    NativePixelBridge.guidedUpsample(source, mask.width, mask.height, mask.alpha, GUIDED_MASK_RADIUS, GUIDED_MASK_EPSILON)
                } else {
                    // A precise mask is only resized (radius 0), so its edge stays exactly where the model put it.
                    NativePixelBridge.guidedUpsample(source, mask.width, mask.height, mask.alpha, 0, GUIDED_MASK_EPSILON)
                }
            } finally {
                if (owned) engine.destroyTileStore(source)
            }
            if (upsampled == 0L) {
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, NativePixelBridge.lastError().ifBlank { "Could not build the selection" })
            }
            val next = NativeSelectionBudgetBridge.combineLike(selectionMask, selectionMask, upsampled, op.ordinal)
            NativeSelectionBridge.destroyStore(upsampled)
            if (next == 0L) return@synchronized selectionFailure("Could not combine the selection", budgetBridge = true)
            installSelection(next, selectionInfo(next, false), label)
        } }

    override fun addLayerFromPixels(id: LayerId, name: String, patch: PixelPatch, label: String): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        if (tree.find(id) != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer ${id.value} already exists")
        val size = mutableInfo.value.size
        val store = engine.createTileStore(size.width, size.height, pixelFormat)
        if (store == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not allocate native layer storage")
        if (!NativePixelBridge.writeRegion(store, 0, 0, size.width, size.height, patch.width, patch.height, patch.rgba, patch.coverage)) {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.UNSUPPORTED, NativePixelBridge.lastError().ifBlank { "Could not write the layer pixels" })
        }
        val anchor = tree.primary
        val parent = anchor?.let { tree.parentOf(it)?.id }
        val siblings = anchor?.let { tree.parentOf(it)?.children } ?: tree.layers
        val index = siblings.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
        val next = NativeLayerTreeOps.insert(tree, LayerNode(id, name, LayerKind.PIXEL), parent, index) ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Can't place the layer")
        }
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
            engine.destroyTileStore(store)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
        }
        prepared[id] = store
        commitPrepared(label, next.copy(active = setOf(id), primary = id), prepared)
    } }

    override fun applyPixelPatch(target: LayerId, region: DocRect, patch: PixelPatch, label: String): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val tree = mutableLayers.value
        val layer = tree.find(target) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${target.value}")
        if (layer.kind != LayerKind.PIXEL) return@synchronized reject(RejectionCode.UNSUPPORTED, "“${layer.name}” is not a pixel layer")
        if (layer.locks.pixels) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "“${layer.name}” has pixel editing locked")
        if (region.isEmpty) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The region is empty")
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not prepare layer pixels")
        val store = prepared[target] ?: run {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.NOT_FOUND, "The layer has no pixel storage")
        }
        if (!NativePixelBridge.writeRegion(store, region.left, region.top, region.width, region.height, patch.width, patch.height, patch.rgba, patch.coverage)) {
            prepared.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, NativePixelBridge.lastError().ifBlank { "Could not write the pixels" })
        }
        commitPrepared(label, tree, prepared)
    } }

    private fun objectSelection(region: SelectionShape?, op: SelectionOp, sampleAll: Boolean, subject: Boolean): CommandResult {
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val (source, owned) = if (sampleAll) {
            try {
                flattenVisible(mutableLayers.value) to true
            } catch (failure: Throwable) {
                return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Nothing is visible to select from")
            }
        } else {
            val (found, error) = primaryPixel(if (subject) "Select Subject" else "Object Selection")
            (found ?: return error!!).second to false
        }
        val bounds = region?.bounds
        val mask = try {
            NativeSelectionBridge.selectObject(
                source,
                bounds?.left?.toDouble() ?: 0.0,
                bounds?.top?.toDouble() ?: 0.0,
                bounds?.right?.toDouble() ?: 0.0,
                bounds?.bottom?.toDouble() ?: 0.0,
                subject,
            )
        } finally {
            if (owned) engine.destroyTileStore(source)
        }
        if (mask == 0L) {
            return reject(RejectionCode.INVALID_ARGUMENT, NativeSelectionBridge.objectSelectLastError().ifBlank { "Couldn't find an object to select" })
        }
        val next = NativeSelectionBudgetBridge.combineLike(selectionMask, selectionMask, mask, op.ordinal)
        NativeSelectionBridge.destroyStore(mask)
        if (next == 0L) return selectionFailure("Could not combine the selection", budgetBridge = true)
        return installSelection(next, selectionInfo(next, false), if (subject) "Select Subject" else "Object Selection")
    }

    // ---- Saved selections: named A16 masks, document state outside undo history ----

    private val channelStores = linkedMapOf<String, Long>()
    private val mutableChannels = MutableStateFlow<List<SelectionChannel>>(emptyList())
    override val selectionChannels: StateFlow<List<SelectionChannel>> get() = mutableChannels.asStateFlow()

    private fun publishChannels() {
        mutableChannels.value = channelStores.map { (name, mask) ->
            val b = NativeSelectionBridge.bounds(mask)
            SelectionChannel(name, if (b.size == 4 && b[0] < b[2] && b[1] < b[3]) DocRect(b[0], b[1], b[2], b[3]) else null)
        }
    }

    /** Named selection channels are document data but intentionally stay outside undo history. */
    private fun markSelectionChannelsChanged(label: String): Long {
        val acceptedSequence = ++sequence
        mutableInfo.value = mutableInfo.value.copy(dirty = true)
        scheduleAutosaveLocked()
        // Channel edits are document mutations, but they do not change the rendered
        // viewport. Applied is their terminal lifecycle event; emitting Rendered here
        // would falsely claim that a new frame was produced.
        mutableEvents.tryEmit(EngineEvent.Applied(acceptedSequence, label))
        return acceptedSequence
    }

    override fun saveSelection(name: String): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (mutableSelection.value !is SelectionInfo.Active) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Create a selection before saving it")
        val channelName = name.trim().ifEmpty {
            val used = channelStores.keys.mapNotNull { Regex("Alpha (\\d+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
            "Alpha ${used + 1}"
        }
        if (channelName.length > 255) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Channel names are limited to 255 characters")
        val copy = engine.cloneTileStore(selectionMask)
        if (copy == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not save the selection")
        channelStores.put(channelName, copy)?.let(engine::destroyTileStore)
        publishChannels()
        CommandResult.Accepted(markSelectionChannelsChanged("Save Selection"))
    }}

    override fun loadSelection(name: String, op: SelectionOp, invert: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        val stored = channelStores[name] ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No saved selection named “$name”")
        var mask = engine.cloneTileStore(stored)
        if (mask == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not load the selection")
        if (invert) {
            val inverted = NativeSelectionBudgetBridge.invertLike(selectionMask, mask)
            NativeSelectionBridge.destroyStore(mask)
            if (inverted == 0L) return@synchronized selectionFailure("Could not invert the saved selection", budgetBridge = true)
            mask = inverted
        }
        val next = NativeSelectionBudgetBridge.combineLike(selectionMask, selectionMask, mask, op.ordinal)
        NativeSelectionBridge.destroyStore(mask)
        if (next == 0L) return@synchronized selectionFailure("Could not combine the saved selection", budgetBridge = true)
        installSelection(next, selectionInfo(next, false), "Load Selection")
    }}

    override fun deleteSelectionChannel(name: String): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val removed = channelStores.remove(name) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No saved selection named “$name”")
        engine.destroyTileStore(removed)
        publishChannels()
        CommandResult.Accepted(markSelectionChannelsChanged("Delete Saved Selection"))
    }}

    // ---- Select and Mask: refine-edge previews on the live selection, then output ----

    override fun beginRefineEdge(): RefineEdgeSession = withDocumentCommand { synchronized(lock) {
        check(!closed) { "Document is closed" }
        finishSelectionTransformBeforeMutation()
        if (mutableSelection.value !is SelectionInfo.Active) return@synchronized app.croptop.core.engine.api.UnsupportedRefineEdgeSession()
        val tree = mutableLayers.value
        val primaryStore = tree.primary?.let { id -> tree.find(id)?.takeIf { it.kind == LayerKind.PIXEL }?.let { pixelStores[id] } }
        // The guide is the primary pixel layer, or the visible composite when the target isn't a pixel layer.
        val (guide, ownsGuide) = if (primaryStore != null) primaryStore to false else flattenVisible(tree) to true
        val original = engine.cloneTileStore(selectionMask)
        check(original != 0L) { "Could not start Select and Mask" }
        activeRefinePreviewMode = RefineEdgeViewMode.OVERLAY
        NativeRefineEdge(guide, ownsGuide, original, selectionMask, mutableSelection.value)
    }}

    private inner class NativeRefineEdge(
        private val guide: Long,
        private val ownsGuide: Boolean,
        private val original: Long,  // the selection the refinement starts from (never the live mask)
        private val previousMask: Long,
        private val previousInfo: SelectionInfo,
    ) : RefineEdgeSession {
        private var finished = false
        private var refined = 0L
        private var settings = RefineEdgeSettings()
        private val viewModeState = MutableStateFlow(RefineEdgeViewMode.OVERLAY)
        override val viewMode: StateFlow<RefineEdgeViewMode> get() = viewModeState.asStateFlow()
        override var outputDocument: DocumentHandle? = null
            private set
        private val brushSessions = mutableSetOf<RefineEdgeBrushSession>()

        private fun release() {
            if (ownsGuide) engine.destroyTileStore(guide)
            engine.destroyTileStore(original)
        }

        override fun update(settings: RefineEdgeSettings): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized reject(RejectionCode.CLOSED, "Select and Mask has finished")
            val next = try {
                NativeImageOpsBridge.refineEdge(
                    guide, original, settings.radius, settings.smartRadius, settings.smooth, settings.feather, settings.contrast, settings.shiftEdge,
                )
            } catch (failure: Throwable) {
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Refine Edge failed")
            }
            this.settings = settings
            refined = next
            showSelectionPreview(next, previousMask)
            acceptViewportStateChange("Select and Mask preview")
        }}

        override fun setViewMode(mode: RefineEdgeViewMode): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized reject(RejectionCode.CLOSED, "Select and Mask has finished")
            if (viewModeState.value == mode) return@synchronized acceptAppliedOnly("Select and Mask view")
            viewModeState.value = mode
            activeRefinePreviewMode = mode
            acceptViewportStateChange("Select and Mask view")
        }}

        override fun beginBrushStroke(spec: RefineEdgeBrushSpec): RefineEdgeBrushSession = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized app.croptop.core.engine.api.UnsupportedRefineEdgeBrushSession()
            val brush = BrushSettings(
                size = spec.diameter,
                hardness = spec.hardness,
                opacity = spec.opacity,
                flow = 1f,
                sizeDynamics = Dynamics(DynamicsInput.OFF),
            )
            val handle = engine.createBrushSession(brush, mutableInfo.value.size.width, mutableInfo.value.size.height)
            if (handle == 0L) return@synchronized app.croptop.core.engine.api.UnsupportedRefineEdgeBrushSession()
            var sampleCount = 0
            var done = false
            var streamError: String? = null
            val brushSession = object : RefineEdgeBrushSession {
                override fun add(samples: FloatArray) = withDocumentCommand { synchronized(lock) {
                    check(!done) { "Refine brush stroke is already finished" }
                    require(samples.size % StrokeSampleLayout.STRIDE == 0) {
                        "Stroke samples must be a multiple of ${StrokeSampleLayout.STRIDE}"
                    }
                    require(samples.all(Float::isFinite)) { "Stroke samples must be finite" }
                    val added = samples.size / StrokeSampleLayout.STRIDE
                    require(sampleCount + added <= MAX_STROKE_SAMPLES) { "Stroke exceeds $MAX_STROKE_SAMPLES samples" }
                    if (streamError == null && samples.isNotEmpty()) {
                        streamError = engine.addBrushSamples(handle, samples)
                    }
                    sampleCount += added
                }}

                override fun end(): CommandResult = withDocumentCommand { synchronized(lock) {
                    if (done) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Refine brush stroke is already finished")
                    done = true
                    brushSessions.remove(this)
                    try {
                        if (finished || closed) return@synchronized reject(RejectionCode.CLOSED, "Select and Mask has finished")
                        streamError?.let { return@synchronized reject(RejectionCode.INVALID_ARGUMENT, it) }
                        if (sampleCount == 0) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Empty refine brush stroke")
                        val baseline = selectionMask
                        val working = engine.cloneTileStore(baseline)
                        if (working == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve the refined selection")
                        val subtract = spec.mode == RefineEdgeBrushMode.SUBTRACT
                        val error = engine.applyMaskBrushSession(
                            baseline,
                            working,
                            0f,
                            handle,
                            if (subtract) 0f else 1f,
                            spec.opacity,
                            subtract,
                            0L,
                        )
                        if (error != null) {
                            engine.destroyTileStore(working)
                            return@synchronized reject(RejectionCode.UNSUPPORTED, error)
                        }
                        refined = working
                        showSelectionPreview(working, previousMask)
                        acceptViewportStateChange(if (subtract) "Select and Mask subtract brush" else "Select and Mask add brush")
                    } finally {
                        engine.destroyBrushSession(handle)
                    }
                }}

                override fun cancel() = withDocumentCommand { synchronized(lock) {
                    if (done) return@synchronized
                    done = true
                    brushSessions.remove(this)
                    engine.destroyBrushSession(handle)
                }}
            }
            brushSessions += brushSession
            brushSession
        }}

        private fun cancelBrushStrokes() {
            brushSessions.toList().forEach(RefineEdgeBrushSession::cancel)
        }

        override fun commit(output: RefineEdgeOutput): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Select and Mask already finished")
            finished = true
            cancelBrushStrokes()
            if (closed) {
                release()
                return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
            }
            // Without an update the result is the starting selection.
            val result = if (refined != 0L) refined else engine.cloneTileStore(original)
            if (result == 0L) {
                finishPreview()
                release()
                return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not retain the refined selection")
            }
            if (output == RefineEdgeOutput.NEW_DOCUMENT) {
                val previewMask = selectionMask
                val outputResult = refinedToNewDocument(result)
                finishPreview()
                if (result != previewMask) engine.destroyTileStore(result)
                release()
                return@synchronized outputResult
            }
            selectionMask = previousMask
            mutableSelection.value = previousInfo
            finishPreview(result)
            val selected = installSelection(result, selectionInfo(result, false), "Select and Mask")
            if (selected !is CommandResult.Accepted) {
                release()
                return@synchronized selected
            }
            val outcome = when (output) {
                RefineEdgeOutput.SELECTION -> selected
                RefineEdgeOutput.LAYER_MASK -> {
                    val primary = mutableLayers.value.primary
                    if (primary == null) reject(RejectionCode.INVALID_ARGUMENT, "Select a layer to mask") else addLayerMask(primary, MaskFill.REVEAL_SELECTION)
                }
                RefineEdgeOutput.NEW_LAYER -> refinedToNewLayer()
                RefineEdgeOutput.NEW_DOCUMENT -> error("Handled before selection commit")
            }
            release()
            outcome
        }}

        private fun finishPreview(result: Long? = null) {
            val livePreview = selectionMask
            activeRefinePreviewMode = null
            viewModeState.value = RefineEdgeViewMode.OVERLAY
            if (livePreview != previousMask && livePreview != 0L && livePreview != result) engine.destroyTileStore(livePreview)
            selectionMask = previousMask
            mutableSelectionMaskRevision.value++
            mutableSelection.value = previousInfo
        }

        private fun refinedToNewDocument(mask: Long): CommandResult {
            val tree = mutableLayers.value
            val source = try {
                flattenVisible(tree)
            } catch (failure: RuntimeException) {
                return reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not render selected pixels")
            }
            var working = source
            try {
                if (settings.decontaminate && settings.decontaminateAmount > 0f) {
                    working = NativeImageOpsBridge.decontaminate(source, mask, settings.decontaminateAmount)
                    if (working == 0L) return reject(RejectionCode.UNSUPPORTED, "Could not decontaminate selected pixels")
                }
                val outputs = engine.layerViaCutStores(working, mask)
                    ?: return reject(RejectionCode.UNSUPPORTED, engine.layerViaCutError().ifBlank { "Could not create the refined document" })
                outputs.getOrNull(0)?.takeIf { it != 0L }?.let(engine::destroyTileStore)
                val pixels = outputs.getOrNull(1)?.takeIf { it != 0L }
                    ?: return reject(RejectionCode.UNSUPPORTED, "Could not create the refined document layer")
                outputDocument = try {
                    engine.documentFromStore(
                        "${mutableInfo.value.name} Selection",
                        mutableInfo.value.size,
                        mutableInfo.value.depth,
                        mutableInfo.value.profile,
                        mutableInfo.value.resolutionPpi,
                        pixelFormat,
                        pixels,
                    )
                } catch (failure: Throwable) {
                    engine.destroyTileStore(pixels)
                    return reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not open the refined document")
                }
                return acceptAppliedOnly("Select and Mask: New Document")
            } finally {
                if (working != source) engine.destroyTileStore(working)
                engine.destroyTileStore(source)
            }
        }

        private fun refinedToNewLayer(): CommandResult {
            val tree = mutableLayers.value
            val newId = generateSequence { LayerId(kotlin.random.Random.nextLong(1L shl 40, Long.MAX_VALUE)) }.first { tree.find(it) == null }
            if (!settings.decontaminate || settings.decontaminateAmount <= 0f) return layerViaCopy(newId)
            val (found, error) = primaryPixel("Select and Mask")
            val (node, source) = found ?: return error!!
            val cleaned = try {
                NativeImageOpsBridge.decontaminate(source, selectionMask, settings.decontaminateAmount)
            } catch (failure: Throwable) {
                return reject(RejectionCode.UNSUPPORTED, failure.message ?: "Decontaminate failed")
            }
            val outputs = engine.layerViaCutStores(cleaned, selectionMask)
            engine.destroyTileStore(cleaned)
            outputs?.getOrNull(0)?.takeIf { it != 0L }?.let(engine::destroyTileStore)
            val pixels = outputs?.getOrNull(1)?.takeIf { it != 0L } ?: return reject(RejectionCode.UNSUPPORTED, "Could not create the refined layer")
            val parent = tree.parentOf(node.id)?.id
            val siblings = tree.parentOf(node.id)?.children ?: tree.layers
            val index = siblings.indexOfFirst { it.id == node.id }.coerceAtLeast(0)
            val next = NativeLayerTreeOps.insert(tree, LayerNode(newId, "${node.name} (refined)", LayerKind.PIXEL), parent, index) ?: run {
                engine.destroyTileStore(pixels)
                return reject(RejectionCode.INVALID_ARGUMENT, "Can't place the refined layer")
            }
            val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: run {
                engine.destroyTileStore(pixels)
                return reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels for undo")
            }
            prepared[newId] = pixels
            return commitPrepared("Select and Mask: New Layer", next.copy(active = setOf(newId), primary = newId), prepared)
        }

        override fun cancel() = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized
            finished = true
            cancelBrushStrokes()
            release()
            activeRefinePreviewMode = null
            viewModeState.value = RefineEdgeViewMode.OVERLAY
            if (!closed) restoreSelectionBefore(previousMask, previousInfo)
        }}
    }

    // ---- Magnetic Lasso: live-wire outline on the primary layer, closed into one selection step ----

    override fun beginMagneticLasso(start: DocPoint, spec: MagneticLassoSpec): MagneticLassoSession = withDocumentCommand { synchronized(lock) {
        check(!closed) { "Document is closed" }
        finishSelectionTransformBeforeMutation()
        val (found, _) = primaryPixel("Magnetic Lasso")
        val store = found?.second ?: return@synchronized app.croptop.core.engine.api.UnsupportedMagneticLassoSession()
        val session = NativeMagneticLassoBridge.begin(store, start.x, start.y, spec.width, spec.contrast, spec.frequency)
        if (session == 0L) return@synchronized app.croptop.core.engine.api.UnsupportedMagneticLassoSession()
        NativeMagneticLasso(session, spec)
    }}

    private inner class NativeMagneticLasso(private val session: Long, private val spec: MagneticLassoSpec) : MagneticLassoSession {
        private var finished = false

        private fun FloatArray?.toPoints(): List<DocPoint> = this?.let { xy -> List(xy.size / 2) { DocPoint(xy[it * 2], xy[it * 2 + 1]) } } ?: emptyList()

        override fun move(point: DocPoint): List<DocPoint> = withDocumentCommand { synchronized(lock) {
            if (finished || closed || !point.x.isFinite() || !point.y.isFinite()) return@synchronized emptyList()
            NativeMagneticLassoBridge.update(session, point.x, point.y).toPoints()
        }}

        override fun anchor(point: DocPoint) = withDocumentCommand { synchronized(lock) {
            if (finished || closed || !point.x.isFinite() || !point.y.isFinite()) return@synchronized
            NativeMagneticLassoBridge.fasten(session, point.x, point.y)
            Unit
        }}

        override fun undoAnchor(): Boolean = withDocumentCommand { synchronized(lock) { !finished && NativeMagneticLassoBridge.undoFasten(session) } }

        override fun outline(): List<DocPoint> = synchronized(lock) { if (finished) emptyList() else NativeMagneticLassoBridge.committed(session).toPoints() }

        override fun close(): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "This Magnetic Lasso outline already ended")
            finished = true
            if (closed) {
                NativeMagneticLassoBridge.destroy(session)
                return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
            }
            var mask = NativeMagneticLassoBridge.close(session, spec.antiAlias)
            val failure = NativeMagneticLassoBridge.lastError()
            NativeMagneticLassoBridge.destroy(session)
            if (mask == 0L) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.ifBlank { "The outline is too small to select" })
            if (spec.feather > 0f) {
                val feathered = NativeSelectionBudgetBridge.modifyLike(selectionMask, mask, 0, spec.feather)
                NativeSelectionBridge.destroyStore(mask)
                if (feathered == 0L) return@synchronized selectionFailure("Could not feather the selection", budgetBridge = true)
                mask = feathered
            }
            val next = NativeSelectionBudgetBridge.combineLike(selectionMask, selectionMask, mask, spec.op.ordinal)
            NativeSelectionBridge.destroyStore(mask)
            if (next == 0L) return@synchronized selectionFailure("Could not combine the selection", budgetBridge = true)
            installSelection(next, selectionInfo(next, spec.feather > 0f), "Magnetic Lasso")
        }}

        override fun cancel() = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized
            finished = true
            NativeMagneticLassoBridge.destroy(session)
        }}
    }

    private inner class NativeAdjustmentPreview(
        private val target: LayerId,
        private val baseline: Long,
        private val selectionBaseline: Long,
        private val specId: String,
        /** True when previewing a smart filter: commit appends it to the smart object's filter stack. */
        private val smartFilter: Boolean = false,
    ) : AdjustmentPreviewSession {
        private var finished = false
        private var lastValues: Map<String, ParamValue> = emptyMap()

        override fun previewRegion(region: app.croptop.core.model.DocRect): DocumentRegionPreview? {
            require(!region.isEmpty && region.width <= DocumentRegionPreview.MAX_DIMENSION && region.height <= DocumentRegionPreview.MAX_DIMENSION)
            val snapshot = synchronized(lock) {
                if (finished || closed || region.left < 0 || region.top < 0 ||
                    region.right > mutableInfo.value.size.width || region.bottom > mutableInfo.value.size.height
                ) return@synchronized null
                val tree = mutableLayers.value
                val stores = clonePixelStores(pixelStores, tree) ?: return@synchronized null
                registerNativeReadSnapshot()
                Triple(tree, stores, mutableInfo.value.size)
            } ?: return null
            val (tree, stores, size) = snapshot
            var shapeStores = emptyMap<LayerId, Long>()
            var groupStack: NativeRenderLayerStack? = null
            return try {
                shapeStores = renderShapeStores(tree)
                val layers = collectRenderLayerStack(tree, stores + shapeStores).also { groupStack = it }.layers
                val bitmap = engine.renderCompositeRegion(size.width, size.height, layers, region, virtualBackgroundColor)
                    ?: return null
                try {
                    val argb = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val rgba = ByteArray(argb.size * 4)
                    argb.forEachIndexed { index, color ->
                        val offset = index * 4
                        rgba[offset] = (color ushr 16).toByte()
                        rgba[offset + 1] = (color ushr 8).toByte()
                        rgba[offset + 2] = color.toByte()
                        rgba[offset + 3] = (color ushr 24).toByte()
                    }
                    DocumentRegionPreview(region, rgba)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                groupStack?.close()
                releaseNativeReadSnapshot(stores)
                shapeStores.values.forEach(engine::destroyTileStore)
            }
        }

        override fun preview(values: Map<String, ParamValue>): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized reject(RejectionCode.CLOSED, "Adjustment preview is closed")
            lastValues = values
            val previewStore: Long
            if (app.croptop.core.model.FilterCatalog.isFilter(specId)) {
                // Filters return a new filtered copy of the baseline (limited to the selection).
                previewStore = try {
                    filterStore(baseline, specId, values, selectionBaseline)
                } catch (failure: RuntimeException) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "The filter failed")
                }
            } else {
                previewStore = engine.cloneTileStore(baseline)
                if (previewStore == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not prepare adjustment preview")
                val error = engine.applyAdjustmentToTileStore(previewStore, AdjustmentInfo(specId, values), selectionBaseline)
                if (error != null) {
                    engine.destroyTileStore(previewStore)
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, error)
                }
            }
            val previous = pixelStores.put(target, previewStore)
            if (previous == null) {
                pixelStores.remove(target)
                engine.destroyTileStore(previewStore)
                return@synchronized reject(RejectionCode.NOT_FOUND, "The target pixel layer no longer exists")
            }
            engine.destroyTileStore(previous)
            queueViewportComposite(mutableLayers.value)
            CommandResult.Accepted(++sequence)
        }}

        override fun commit(): CommandResult = withDocumentCommand { synchronized(lock) {
            if (finished || closed) return@synchronized reject(RejectionCode.CLOSED, "Adjustment preview is closed")
            val tree = mutableLayers.value
            val result = if (smartFilter) {
                val spec = app.croptop.core.model.FilterCatalog[specId]
                val filter = AdjustmentInfo(specId, spec?.normalize(lastValues) ?: lastValues)
                commit(
                    "Smart Filter: ${spec?.name ?: specId}",
                    NativeLayerTreeOps.update(tree, target) { node ->
                        node.copy(smartObject = node.smartObject?.let { it.copy(filters = it.filters + filter) })
                    },
                )
            } else {
                // History shows the spec's display name ("Clouds", "Levels"), not its id.
                val name = app.croptop.core.model.FilterCatalog[specId]?.name ?: AdjustmentCatalog[specId]?.name ?: specId
                commit(name, tree)
            }
            if (result is CommandResult.Accepted) finish(committed = true)
            result
        }}

        override fun cancel() = withDocumentCommand { synchronized(lock) {
            if (finished) return@synchronized
            if (!closed) {
                pixelStores.put(target, baseline)?.let(engine::destroyTileStore)
                queueViewportComposite(mutableLayers.value)
            } else {
                engine.destroyTileStore(baseline)
            }
            finish(committed = false)
        }}

        private fun finish(committed: Boolean) {
            if (finished) return
            finished = true
            if (committed) engine.destroyTileStore(baseline)
            if (selectionBaseline != 0L) engine.destroyTileStore(selectionBaseline)
            if (activeAdjustmentPreview === this) activeAdjustmentPreview = null
        }
    }

    override fun setActiveLayers(ids: Set<LayerId>, primary: LayerId?): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        if (ids.any { tree.find(it) == null }) {
            return@synchronized reject(RejectionCode.NOT_FOUND, "One or more selected layers do not exist")
        }
        if (primary != null && primary !in ids) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Primary layer must be part of the active selection")
        }
        val next = tree.copy(active = ids.toSet(), primary = primary)
        snapshots[cursor] = snapshots[cursor].copy(tree = next)
        mutableLayers.value = next
        CommandResult.Accepted(++sequence)
    }}

    override fun createShapeLayer(id: LayerId, name: String, paths: List<app.croptop.core.model.VectorPathDraft>): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        if (id.value <= 0L ||
            tree.find(id) != null
        ) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Shape layer id is invalid or already exists")
        }
        if (name.isBlank()) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Shape layer name cannot be blank")
        if (paths.isEmpty()) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "A shape layer needs at least one path")
        when (val result = NativeShapeLayerBridge.encodeBatch(paths)) {
            is ShapeLayerBridgeResult.Failure -> return@synchronized reject(result.code, result.message)
            is ShapeLayerBridgeResult.Success -> Unit
        }
        val node = LayerNode(id, name.trim(), LayerKind.SHAPE, vectorPaths = paths.toList())
        val nextTree = NativeLayerTreeOps.insert(tree, node, null, 0)
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Could not place the shape layer")
        commit("New Shape Layer", nextTree.copy(active = setOf(id), primary = id))
    }}

    override fun setShapePaths(id: LayerId, paths: List<app.croptop.core.model.VectorPathDraft>): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val node = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        if (node.kind != LayerKind.SHAPE) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Vector paths belong to shape layers")
        if (node.locks.pixels) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "${node.name} is pixel locked")
        when (val result = NativeShapeLayerBridge.encodeBatch(paths)) {
            is ShapeLayerBridgeResult.Failure -> return@synchronized reject(result.code, result.message)
            is ShapeLayerBridgeResult.Success -> Unit
        }
        if (node.vectorPaths == paths) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The shape geometry has not changed")
        commit("Edit Shape", NativeLayerTreeOps.update(tree, id) { it.copy(vectorPaths = paths.toList()) })
    }}

    override fun combineShapePaths(
        layerId: LayerId,
        subjectPath: app.croptop.core.model.VectorPathId,
        clipPath: app.croptop.core.model.VectorPathId,
        operation: app.croptop.core.engine.api.ShapeBooleanOperation,
    ): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val tree = mutableLayers.value
        val node = tree.find(layerId) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${layerId.value}")
        if (node.kind != LayerKind.SHAPE) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Vector paths belong to shape layers")
        if (node.locks.pixels) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "${node.name} is pixel locked")
        if (subjectPath == clipPath) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Choose two different paths")
        val subjectIndex = node.vectorPaths.indexOfFirst { it.id == subjectPath }
        val clipIndex = node.vectorPaths.indexOfFirst { it.id == clipPath }
        if (subjectIndex < 0 || clipIndex < 0) return@synchronized reject(RejectionCode.NOT_FOUND, "One or both paths no longer exist")
        val subject = node.vectorPaths[subjectIndex]
        val clip = node.vectorPaths[clipIndex]
        if (subject.subpaths.isEmpty() || clip.subpaths.isEmpty() || subject.subpaths.any { !it.closed } || clip.subpaths.any { !it.closed }) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Path booleans require closed paths")
        }
        when (val encoded = NativeShapeLayerBridge.encodeBatch(node.vectorPaths)) {
            is ShapeLayerBridgeResult.Failure -> return@synchronized reject(encoded.code, encoded.message)
            is ShapeLayerBridgeResult.Success -> Unit
        }
        try {
            val result = NativePathOpsBridge.combine(
                NativeVectorPathBooleanCodec.encode(subject),
                if (subject.fillRule == app.croptop.core.model.PathFillRule.EVEN_ODD) NativePathOpsBridge.FillRule.EVEN_ODD else NativePathOpsBridge.FillRule.NON_ZERO,
                NativeVectorPathBooleanCodec.encode(clip),
                if (clip.fillRule == app.croptop.core.model.PathFillRule.EVEN_ODD) NativePathOpsBridge.FillRule.EVEN_ODD else NativePathOpsBridge.FillRule.NON_ZERO,
                NativePathOpsBridge.Op.entries[operation.ordinal],
                refit = true,
                refitTolerance = 0.25,
            )
            val combined = NativeVectorPathBooleanCodec.decode(result, subject)
            val nextPaths = node.vectorPaths.toMutableList().apply {
                removeAll { it.id == subjectPath || it.id == clipPath }
                if (combined.subpaths.isNotEmpty()) add(minOf(subjectIndex, clipIndex), combined)
            }
            commit(
                "Path ${operation.name.lowercase().replaceFirstChar(Char::uppercase)}",
                NativeLayerTreeOps.update(tree, layerId) { it.copy(vectorPaths = nextPaths) },
            )
        } catch (failure: Exception) {
            reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Path boolean operation failed")
        }
    }}

    override fun translateLayers(ids: Set<LayerId>, dx: Float, dy: Float): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val maxCoordinate = 2f * app.croptop.core.model.MAX_DIMENSION
        if (ids.isEmpty() || !dx.isFinite() || !dy.isFinite() || kotlin.math.abs(dx) > maxCoordinate || kotlin.math.abs(dy) > maxCoordinate) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Moving layers needs IDs and finite in-range offsets")
        }
        val tree = mutableLayers.value
        val translatedIds = linkedSetOf<LayerId>()
        val pixelIds = linkedSetOf<LayerId>()
        val shapeIds = linkedSetOf<LayerId>()
        fun include(layer: LayerNode, nestedInGroup: Boolean = false): CommandResult.Rejected? {
            // Adjustment and fill layers carry no independent pixel coordinates. A group move
            // should still move its raster children; masks can join this path once they exist.
            if (nestedInGroup && (layer.kind == LayerKind.ADJUSTMENT || layer.kind == LayerKind.FILL)) return null
            if (!translatedIds.add(layer.id)) return null
            if (layer.locks.position) return reject(RejectionCode.INVALID_ARGUMENT, "Layer ${layer.name} is position locked")
            when (layer.kind) {
                LayerKind.PIXEL -> pixelIds += layer.id

                LayerKind.SHAPE -> {
                    if (layer.vectorPaths.isEmpty()) {
                        return reject(RejectionCode.UNSUPPORTED, "${layer.name} has no editable vector geometry")
                    }
                    shapeIds += layer.id
                }

                LayerKind.GROUP -> {
                    for (child in layer.children) {
                        include(child, nestedInGroup = true)?.let { return it }
                    }
                }

                LayerKind.ADJUSTMENT -> return reject(RejectionCode.UNSUPPORTED, "${layer.name} is an adjustment layer and has no pixels to move")

                LayerKind.FILL -> return reject(RejectionCode.UNSUPPORTED, "${layer.name} is a fill layer and has no pixels to move")

                // Text: the rendered cache moves like pixels and the text's origin moves with it (re-render stays put).
                LayerKind.TEXT -> pixelIds += layer.id

                // Smart object: the rendered cache moves like pixels and the move is composed into its transform.
                LayerKind.SMART_OBJECT -> pixelIds += layer.id

                else -> return reject(RejectionCode.UNSUPPORTED, "${layer.name} is not a movable pixel layer or group")
            }
            return null
        }
        for (id in ids) {
            val layer = tree.find(id) ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
            include(layer)?.let { return@synchronized it }
        }
        if (dx == 0f && dy == 0f) return@synchronized CommandResult.Accepted(++sequence)
        if (pixelIds.isEmpty() && shapeIds.isEmpty()) return@synchronized CommandResult.Accepted(++sequence)

        val translatedPaths = shapeIds.associateWith { id ->
            val layer = checkNotNull(tree.find(id))
            layer.vectorPaths.map { path ->
                path.copy(
                    subpaths = path.subpaths.map { subpath ->
                        subpath.copy(
                            anchors = subpath.anchors.map { anchor ->
                                anchor.copy(point = DocPoint(anchor.point.x + dx, anchor.point.y + dy))
                            },
                        )
                    },
                    primitive = path.primitive?.let { primitive ->
                        primitive.copy(
                            sourceBox = primitive.sourceBox?.let { box -> box.copy(x = box.x + dx, y = box.y + dy) },
                        )
                    },
                )
            }
        }
        for (paths in translatedPaths.values) {
            when (val validation = NativeShapeLayerBridge.encodeBatch(paths)) {
                is ShapeLayerBridgeResult.Failure -> return@synchronized reject(validation.code, validation.message)
                is ShapeLayerBridgeResult.Success -> Unit
            }
        }

        val canvasSize = mutableInfo.value.size
        fun translateBounds(nodes: List<LayerNode>): List<LayerNode> = nodes.map { layer ->
            val bounds = if (layer.id in translatedIds) {
                layer.bounds?.let { rect ->
                    val left = kotlin.math.floor(rect.left.toDouble() + dx).coerceIn(0.0, canvasSize.width.toDouble()).toInt()
                    val top = kotlin.math.floor(rect.top.toDouble() + dy).coerceIn(0.0, canvasSize.height.toDouble()).toInt()
                    val right = kotlin.math.ceil(rect.right.toDouble() + dx).coerceIn(0.0, canvasSize.width.toDouble()).toInt()
                    val bottom = kotlin.math.ceil(rect.bottom.toDouble() + dy).coerceIn(0.0, canvasSize.height.toDouble()).toInt()
                    if (left >= right || top >= bottom) null else app.croptop.core.model.DocRect(left, top, right, bottom)
                }
            } else {
                layer.bounds
            }
            layer.copy(
                bounds = bounds,
                vectorPaths = translatedPaths[layer.id] ?: layer.vectorPaths,
                text = layer.text?.let { text ->
                    if (layer.id in translatedIds) text.copy(origin = DocPoint(text.origin.x + dx, text.origin.y + dy)) else text
                },
                smartObject = layer.smartObject?.let { info ->
                    if (layer.id in translatedIds) info.copy(transform = (Matrix3.translate(dx, dy) * info.matrix).values) else info
                },
                children = translateBounds(layer.children),
            )
        }
        val next = tree.copy(layers = translateBounds(tree.layers))

        if (pixelIds.isEmpty()) return@synchronized commit("Move", next)

        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not prepare layer pixels for moving")
        for (id in pixelIds) {
            val store = prepared[id]
            if (store == null || !engine.translateTileStore(store, dx, dy)) {
                prepared.values.forEach(engine::destroyTileStore)
                return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not translate layer pixels")
            }
            // A linked layer mask moves with its layer (whole pixels; uncovered areas keep the mask's default coverage).
            val maskInfo = tree.find(id)?.mask?.takeIf { it.linked } ?: continue
            val moved = runCatching {
                val mask = NativeLayerMaskBridge.fromLayerStore(store)
                try {
                    val pixels = NativeLayerMaskBridge.pixels(mask)
                    val empty = NativeLayerMaskBridge.emptyCoverage(mask)
                    val shifted = engine.transformTileStore(pixels, 0, canvasSize.width, canvasSize.height, dx.roundToInt(), dy.roundToInt())
                    engine.destroyTileStore(pixels)
                    check(shifted != 0L) { engine.geometryError().ifBlank { "Could not move the layer mask" } }
                    val next = NativeLayerMaskBridge.fromStore(shifted, empty)
                    engine.destroyTileStore(shifted)
                    try {
                        NativeLayerMaskBridge.attach(store, next, maskInfo)
                    } finally {
                        NativeLayerMaskBridge.destroy(next)
                    }
                } finally {
                    NativeLayerMaskBridge.destroy(mask)
                }
            }
            if (moved.isFailure) {
                prepared.values.forEach(engine::destroyTileStore)
                return@synchronized reject(RejectionCode.UNSUPPORTED, moved.exceptionOrNull()?.message ?: "Could not move the layer mask")
            }
        }
        // Layers with hidden pixels re-render from their merged source on whole-pixel moves (revealing them).
        var movedTree = next
        for (id in pixelIds) {
            val node = tree.find(id)?.takeIf { it.kind == LayerKind.PIXEL && it.hiddenPixels != null } ?: continue
            val whole = dx == kotlin.math.round(dx) && dy == kotlin.math.round(dy)
            val merged = if (whole && node.mask == null) pixelStores[id]?.let { mergeHiddenPixels(node.hiddenPixels, it, canvasSize) } else null
            if (merged == null) {
                movedTree = NativeLayerTreeOps.update(movedTree, id) { it.copy(hiddenPixels = null) }
                continue
            }
            val (store, info) = merged
            smartSources[info.sourceKey] = store
            val moved = info.copy(offsetX = info.offsetX + dx.toInt(), offsetY = info.offsetY + dy.toInt())
            val revealed = renderHiddenPixels(moved, canvasSize)
            if (revealed != 0L) prepared.put(id, reattachLayerState(node, revealed))?.let(engine::destroyTileStore)
            movedTree = NativeLayerTreeOps.update(movedTree, id) { it.copy(hiddenPixels = moved) }
        }
        val onlyImageLayer = tree.layers.singleOrNull()?.takeIf { layer ->
            ids == setOf(layer.id) && layer.kind == LayerKind.PIXEL && layer.visible &&
                layer.opacity == 1f && layer.fillOpacity == 1f && layer.blendMode == BlendMode.NORMAL &&
                layer.mask == null && pixelIds == setOf(layer.id)
        }
        val currentOffset = viewportOffsets[cursor]
        val nextOffset = if (onlyImageLayer != null) currentOffset.first + dx to currentOffset.second + dy else currentOffset
        commitPrepared("Move", movedTree, prepared, nextOffset)
    }}

    override fun layerAt(point: DocPoint): LayerId? = synchronized(lock) {
        if (closed || !point.x.isFinite() || !point.y.isFinite()) return@synchronized null
        val size = mutableInfo.value.size
        if (point.x < 0f || point.y < 0f || point.x >= size.width || point.y >= size.height) return@synchronized null
        fun hit(nodes: List<LayerNode>): LayerId? {
            for (layer in nodes) {
                if (!layer.visible) continue
                if (layer.kind == LayerKind.GROUP) {
                    hit(layer.children)?.let { return it }
                } else if ((layer.kind == LayerKind.PIXEL || layer.kind == LayerKind.TEXT || layer.kind == LayerKind.SMART_OBJECT) && !layer.locks.position) {
                    val bounds = layer.bounds
                    if (bounds == null || bounds.contains(point)) return layer.id
                } else if (layer.kind == LayerKind.SHAPE && !layer.locks.position && layer.vectorPaths.isNotEmpty()) {
                    val result = NativeShapeLayerBridge.hitTest(
                        layer.vectorPaths,
                        point.x.toDouble(),
                        point.y.toDouble(),
                        MOVE_LAYER_HIT_TOLERANCE,
                    )
                    if (result is ShapeLayerBridgeResult.Success && result.value.part != ShapeHitPart.NONE) return layer.id
                }
            }
            return null
        }
        hit(mutableLayers.value.layers)
    }

    override fun beginTransform(target: TransformTarget): TransformSession = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized UnsupportedTransformSession("Transforming a closed document")
        if (activeTransform != null) return@synchronized UnsupportedTransformSession("Another transform is active")
        if (pendingBrushCommits.get() > 0) return@synchronized UnsupportedTransformSession("Wait for the current brush stroke to finish")
        val selectionInfo = if (target == TransformTarget.Selection) {
            (mutableSelection.value as? SelectionInfo.Active)
                ?.takeIf { !it.bounds.isEmpty }
                ?: return@synchronized UnsupportedTransformSession("Transform Selection requires an active selection")
        } else {
            null
        }
        val tree = mutableLayers.value
        // A group transforms as its descendants.
        fun expand(node: LayerNode): List<LayerId> = if (node.kind == LayerKind.GROUP) node.children.flatMap(::expand) else listOf(node.id)
        val requested = ((target as? TransformTarget.Layers)?.ids ?: emptySet())
            .flatMap { id -> tree.find(id)?.let(::expand) ?: listOf(id) }
            .toSet()
        if (target !is TransformTarget.Selection && requested.isEmpty()) {
            return@synchronized UnsupportedTransformSession("Transforming these layers")
        }
        if (requested.any { id ->
                val node = tree.find(id)
                node == null || (node.kind != LayerKind.PIXEL && node.kind != LayerKind.SMART_OBJECT && node.kind != LayerKind.TEXT) ||
                    node.locks.position || node.locks.pixels
            }
        ) {
            return@synchronized UnsupportedTransformSession("Free Transform of shape, fill or locked layers")
        }
        val sessions = linkedMapOf<LayerId, Long>()
        val selectionSource = if (selectionInfo != null) {
            cloneSelectionMask(selectionMask)
                ?: return@synchronized UnsupportedTransformSession("Could not preserve the selection for preview")
        } else {
            0L
        }
        try {
            requested.sortedBy(LayerId::value).forEach { id ->
                val store = pixelStores[id] ?: error("Pixel layer ${id.value} has no native store")
                sessions[id] = NativeTransformBridge.begin(store)
            }
        } catch (failure: Throwable) {
            sessions.values.forEach(NativeTransformBridge::destroy)
            if (selectionSource != 0L) engine.destroyTileStore(selectionSource)
            return@synchronized UnsupportedTransformSession(failure.message ?: "Transform session could not start")
        }
        val state = NativeTransformState(
            requested.toSet(),
            sessions,
            cursor,
            tree,
            selectionSource = selectionSource,
            selectionStoreAtBegin = selectionMask,
            selectionInfoAtBegin = selectionInfo,
        )
        previewRenderJob?.cancel()
        previewRenderJob = null
        activeTransform = state
        object : TransformSession {
            override fun update(matrix: Matrix3) = withDocumentCommand {
                synchronized(lock) {
                    if (closed || state.finished || activeTransform !== state) return@synchronized
                    state.matrix = matrix
                    state.previewFailure = null
                    val generation = ++state.generation
                    state.renderJob?.cancel()
                    val previousReady = previewReady
                    val viewportGeneration = ++previewRenderGeneration
                    previousReady?.complete(Unit)
                    val ready = CompletableDeferred<Unit>()
                    previewReadyGeneration = viewportGeneration
                    previewReady = ready
                    registerNativeReadSnapshot()
                    val renderJob = previewRenderScope.launch(Dispatchers.Default) {
                        try {
                            if (state.transformsSelection) {
                                renderSelectionTransformPreview(state, matrix, generation)
                            } else {
                                renderTransformPreview(state, matrix, generation, viewportGeneration)
                            }
                        } finally {
                            ready.complete(Unit)
                        }
                    }
                    renderJob.invokeOnCompletion { finishNativeReadSnapshot() }
                    state.renderJob = renderJob
                }
            }

            override fun commit(): CommandResult = withDocumentCommand { synchronized(lock) {
                if (state.finished || activeTransform !== state) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Transform already finished")
                }
                if (closed) {
                    finishTransform(state)
                    return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
                }
                if (cursor != state.baseHistoryCursor || mutableLayers.value != state.baseTree) {
                    val currentTree = mutableLayers.value
                    finishTransform(state)
                    queueViewportComposite(currentTree)
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The document changed during the transform")
                }
                state.previewFailure?.let { failure ->
                    val currentTree = mutableLayers.value
                    finishTransform(state)
                    queueViewportComposite(currentTree)
                    return@synchronized reject(RejectionCode.UNSUPPORTED, "Transform preview failed: $failure")
                }
                if (state.transformsSelection) {
                    return@synchronized commitSelectionTransform(state)
                }
                val tree = mutableLayers.value
                if (state.matrix == Matrix3.Identity) {
                    finishTransform(state)
                    queueViewportComposite(tree)
                    return@synchronized CommandResult.Accepted(++sequence)
                }
                state.finished = true
                state.renderJob?.cancel()
                state.renderJob = null
                val prepared = clonePixelStores(pixelStores, tree)
                    ?.toMutableMap()
                    ?: run {
                        finishTransform(state)
                        return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not prepare transformed history state")
                    }
                val textUpdates = mutableMapOf<LayerId, Pair<app.croptop.core.model.TextLayerInfo, DocRect>>()
                try {
                    synchronized(state.nativeCallLock) {
                        for (id in state.targets.sortedBy(LayerId::value)) {
                            // Smart objects re-render from their source through the composed transform (no quality loss);
                            // with a mask or clipping the transformed cache keeps those attachments instead.
                            // Type scales and moves as text: re-rendered sharp at the new size and origin.
                            val textNode = tree.find(id)?.takeIf { it.kind == LayerKind.TEXT && it.text != null && it.mask == null }
                            if (textNode != null) {
                                val scaled = requireNotNull(transformText(checkNotNull(textNode.text), state.matrix)) {
                                    "Type can be moved, scaled and rotated; to skew or flip it, convert it to a smart object first"
                                }
                                val (store, bounds) = renderTextStore(scaled) ?: error("Could not render the transformed type")
                                prepared.put(id, store)?.let(engine::destroyTileStore)
                                textUpdates[id] = scaled to bounds
                                continue
                            }
                            val smart = tree.find(id)?.takeIf { it.kind == LayerKind.SMART_OBJECT && it.mask == null && !it.clipped }?.smartObject
                            if (smart != null) {
                                val composed = smart.copy(transform = (state.matrix * smart.matrix).values)
                                prepared.put(id, renderSmartObject(composed, mutableInfo.value.size))?.let(engine::destroyTileStore)
                                continue
                            }
                            val session = checkNotNull(state.sessions[id])
                            val result = NativeTransformBridge.commit(
                                session,
                                state.matrix.values.toFloatArray(),
                                NativeTransformBridge.Filter.BICUBIC,
                            )
                            prepared.put(id, result.storeHandle)?.let(engine::destroyTileStore)
                        }
                    }
                } catch (failure: Throwable) {
                    prepared.values.forEach(engine::destroyTileStore)
                    finishTransform(state)
                    queueViewportComposite(tree)
                    return@synchronized reject(RejectionCode.UNSUPPORTED, failure.message ?: "Transform could not be committed")
                }
                val transformedTree = state.targets.fold(tree) { current, id ->
                    NativeLayerTreeOps.update(current, id) { layer ->
                        val textUpdate = textUpdates[id]
                        layer.copy(
                            bounds = textUpdate?.second ?: layer.bounds?.let { transformBounds(it, state.matrix, mutableInfo.value.size) },
                            smartObject = layer.smartObject?.let { it.copy(transform = (state.matrix * it.matrix).values) },
                            text = textUpdate?.first ?: layer.text,
                        )
                    }
                }
                finishTransform(state)
                commitPrepared(if (state.matrix.isTranslation) "Move" else "Free Transform", transformedTree, prepared)
            }}

            override fun cancel() = withDocumentCommand {
                synchronized(lock) {
                    if (state.finished || activeTransform !== state) return@synchronized
                    finishTransform(state)
                    if (!closed) queueViewportComposite(mutableLayers.value)
                }
            }
        }
    }}

    private fun finishTransform(state: NativeTransformState) {
        val restoreSelectionInfo = state.transformsSelection && selectionMask == state.selectionStoreAtBegin
        state.finished = true
        state.renderJob?.cancel()
        state.renderJob = null
        if (activeTransform === state) activeTransform = null
        synchronized(state.nativeCallLock) {
            state.sessions.values.forEach(NativeTransformBridge::destroy)
            if (state.selectionSource != 0L) engine.destroyTileStore(state.selectionSource)
        }
        if (restoreSelectionInfo) state.selectionInfoAtBegin?.let { mutableSelection.value = it }
    }

    /** A committed edit must snapshot the committed mask/info pair, never an ephemeral outline preview. */
    private fun finishSelectionTransformBeforeMutation() {
        activeTransform?.takeIf { it.transformsSelection }?.let(::finishTransform)
        activeSelectionModifySession?.cancel()
    }

    /** Commit a transformed outline as one selection-history step; pixels remain untouched. */
    private fun commitSelectionTransform(state: NativeTransformState): CommandResult {
        if (state.matrix == Matrix3.Identity) {
            finishTransform(state)
            return CommandResult.Accepted(++sequence)
        }
        var transformed = 0L
        var failure = ""
        synchronized(state.nativeCallLock) {
            if (!state.finished) {
                state.finished = true
                state.renderJob?.cancel()
                state.renderJob = null
                transformed = NativeSelectionBridge.transform(
                    state.selectionSource,
                    state.matrix.values.map(Float::toDouble).toDoubleArray(),
                    0,
                )
                if (transformed == 0L) failure = NativeSelectionBridge.nativeLastError()
            }
        }
        if (transformed == 0L) {
            finishTransform(state)
            return reject(RejectionCode.UNSUPPORTED, failure.ifBlank { "Selection transform failed" })
        }
        val nextInfo = selectionInfo(transformed, checkNotNull(state.selectionInfoAtBegin).feathered)
        finishTransform(state)
        return installSelection(transformed, nextInfo, "Transform Selection")
    }

    private fun transformBounds(
        bounds: app.croptop.core.model.DocRect,
        matrix: Matrix3,
        size: DocSize,
    ): app.croptop.core.model.DocRect? {
        val points = listOf(
            bounds.left.toFloat() to bounds.top.toFloat(),
            bounds.right.toFloat() to bounds.top.toFloat(),
            bounds.right.toFloat() to bounds.bottom.toFloat(),
            bounds.left.toFloat() to bounds.bottom.toFloat(),
        )
        if (points.any { (x, y) -> matrix[2, 0] * x + matrix[2, 1] * y + matrix[2, 2] <= 0f }) {
            return app.croptop.core.model.DocRect(0, 0, size.width, size.height)
        }
        val mapped = points.map { (x, y) -> matrix.map(DocPoint(x, y)) }
        val left = kotlin.math.floor(mapped.minOf { it.x }).toInt().coerceIn(0, size.width)
        val top = kotlin.math.floor(mapped.minOf { it.y }).toInt().coerceIn(0, size.height)
        val right = kotlin.math.ceil(mapped.maxOf { it.x }).toInt().coerceIn(0, size.width)
        val bottom = kotlin.math.ceil(mapped.maxOf { it.y }).toInt().coerceIn(0, size.height)
        return if (left < right && top < bottom) app.croptop.core.model.DocRect(left, top, right, bottom) else null
    }

    /** Preview selection-outline bounds from an owned A16 snapshot; the document mask stays committed/original. */
    private suspend fun renderSelectionTransformPreview(state: NativeTransformState, matrix: Matrix3, generation: Long) {
        var transformed = 0L
        try {
            synchronized(state.nativeCallLock) {
                if (!state.finished) {
                    transformed = NativeSelectionBridge.transform(
                        state.selectionSource,
                        matrix.values.map(Float::toDouble).toDoubleArray(),
                        0,
                    )
                }
            }
            if (transformed == 0L) {
                val reason = NativeSelectionBridge.nativeLastError().ifBlank { "Selection preview failed" }
                recordTransformPreviewFailure(state, generation, reason)
                synchronized(lock) {
                    if (!closed && activeTransform === state && state.generation == generation &&
                        cursor == state.baseHistoryCursor && selectionMask == state.selectionStoreAtBegin
                    ) {
                        state.selectionInfoAtBegin?.let { mutableSelection.value = it }
                    }
                }
                return
            }
            val info = selectionInfo(transformed, checkNotNull(state.selectionInfoAtBegin).feathered)
            synchronized(lock) {
                if (!closed && !state.finished && activeTransform === state && state.generation == generation &&
                    cursor == state.baseHistoryCursor && mutableLayers.value == state.baseTree &&
                    selectionMask == state.selectionStoreAtBegin
                ) {
                    mutableSelection.value = info
                }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            recordTransformPreviewFailure(state, generation, failure.message ?: "Selection preview failed")
            synchronized(lock) {
                if (!closed && activeTransform === state && state.generation == generation &&
                    cursor == state.baseHistoryCursor && selectionMask == state.selectionStoreAtBegin
                ) {
                    state.selectionInfoAtBegin?.let { mutableSelection.value = it }
                }
            }
        } finally {
            if (transformed != 0L) engine.destroyTileStore(transformed)
        }
    }

    private suspend fun renderTransformPreview(
        state: NativeTransformState,
        matrix: Matrix3,
        generation: Long,
        viewportGeneration: Long,
    ) {
        val tree: LayerTree
        val size: DocSize
        val originalStores: Map<LayerId, Long>
        synchronized(lock) {
            if (closed || state.finished || activeTransform !== state || state.generation != generation ||
                cursor != state.baseHistoryCursor || mutableLayers.value != state.baseTree ||
                previewRenderGeneration != viewportGeneration
            ) {
                return
            }
            tree = mutableLayers.value
            size = mutableInfo.value.size
            originalStores = clonePixelStores(pixelStores, tree) ?: return
            registerNativeReadSnapshot()
        }
        val shapeStores = mutableMapOf<LayerId, Long>()
        val proxyStores = mutableMapOf<LayerId, Long>()
        var groupStack: NativeRenderLayerStack? = null
        val framePosts = mutableListOf<CompletableDeferred<Boolean>>()
        var preview: Bitmap? = null
        try {
            for (layer in tree.flatten().filter { it.kind == LayerKind.SHAPE && it.vectorPaths.isNotEmpty() }) {
                when (val rendered = engine.renderShapeLayer(layer.vectorPaths, size)) {
                    is ShapeLayerBridgeResult.Success -> shapeStores[layer.id] = rendered.value.also { if (layer.clipped) NativeLayerMaskBridge.setClipped(it, true) }

                    is ShapeLayerBridgeResult.Failure -> {
                        recordTransformPreviewFailure(state, generation, "Could not render shape layer ${layer.name}: ${rendered.message}")
                        return
                    }
                }
            }
            val level = viewportPreviewMipLevel(size.width, size.height, MAX_VIEWPORT_PREVIEW_DIMENSION)
            val divisor = 1 shl level
            val scale = 1f / divisor
            val proxyWidth = (size.width + divisor - 1) / divisor
            val proxyHeight = (size.height + divisor - 1) / divisor
            val sources = originalStores + shapeStores
            for ((id, source) in sources) {
                val handle = if (id in state.targets) {
                    synchronized(state.nativeCallLock) {
                        if (state.finished) {
                            0L
                        } else {
                            NativeTransformBridge.preview(checkNotNull(state.sessions[id]), matrix.values.toFloatArray(), scale)
                        }
                    }
                } else {
                    engine.resizeTileStore(source, proxyWidth, proxyHeight)
                }
                if (handle == 0L) {
                    recordTransformPreviewFailure(state, generation, "Could not allocate a transformed proxy layer")
                    return
                }
                proxyStores[id] = handle
            }
            val layers = collectRenderLayerStack(tree, proxyStores).also { groupStack = it }.layers
            preview = engine.renderCompositePreview(
                proxyWidth,
                proxyHeight,
                layers,
                maxDimension = maxOf(proxyWidth, proxyHeight),
                backgroundColor = virtualBackgroundColor,
                renderSession = compositeRenderSession,
            ) ?: run {
                recordTransformPreviewFailure(state, generation, "Could not compose the transform preview")
                return
            }
            synchronized(lock) {
                if (closed || state.finished || activeTransform !== state || state.generation != generation ||
                    cursor != state.baseHistoryCursor || mutableLayers.value != state.baseTree ||
                    previewRenderGeneration != viewportGeneration
                ) {
                    preview.recycle()
                    preview = null
                    return
                }
                val previous = viewportPreview
                viewportPreview = preview
                viewportPreviewIsComposite = true
                completedPreviewRenderGeneration = viewportGeneration
                val presentation = PreviewPresentation(visible = true, opacity = 1f)
                attachedViewports.toList().forEach { viewport ->
                    val posted = CompletableDeferred<Boolean>()
                    framePosts += posted
                    viewport.setPreview(preview!!) { wasPosted -> posted.complete(wasPosted) }
                    viewport.setPreviewPresentation(presentation)
                }
                if (previous !== preview) previous?.recycle()
                preview = null
            }
            framePosts.forEach { it.await() }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            recordTransformPreviewFailure(state, generation, failure.message ?: "Transform preview failed")
        } finally {
            groupStack?.close()
            preview?.recycle()
            releaseNativeReadSnapshot(originalStores)
            shapeStores.values.forEach(engine::destroyTileStore)
            proxyStores.values.forEach(engine::destroyTileStore)
        }
    }

    private fun recordTransformPreviewFailure(state: NativeTransformState, generation: Long, message: String) {
        synchronized(lock) {
            if (!closed && !state.finished && activeTransform === state && state.generation == generation) {
                state.previewFailure = message
            }
        }
    }

    /** Native pixel allocation, exposed internally so the instrumentation contract can measure it. */
    internal val residentPixelBytes: Long get() = pixelStores.values.sumOf(engine::tileStoreResidentBytes)

    internal fun trimCaches() = synchronized(lock) {
        if (!closed) {
            (pixelStores.values + snapshots.flatMap { it.pixelStores.values } + namedSnapshots.flatMap { it.pixelStores.values })
                .distinct().forEach(engine::trimTileStoreCaches)
        }
    }

    internal fun loadRaster(encoded: ByteArray, size: Int, codec: Int, exifOrientation: Int = 1) = synchronized(lock) {
        check(!closed) { "Document is closed" }
        val layerId = mutableLayers.value.primary ?: error("Imported document has no active pixel layer")
        val store = pixelStores[layerId] ?: error("Active layer has no pixel store")
        val error = engine.decodeToTileStore(encoded, size, store, codec, exifOrientation)
        check(error == null) { error ?: "Image decoder failed" }
        refreshCurrentPixelSnapshot()
    }

    /** Original RAW files of RAW smart objects by [app.croptop.core.model.RawSourceInfo.fileKey]; immutable, freed on close. */
    private val rawFiles = mutableMapOf<String, ByteArray>()

    internal fun restoreRawFile(key: String, bytes: ByteArray) = synchronized(lock) { rawFiles[key] = bytes }

    /** Installs an already decoded raster ([storeHandle], 8/16/32-bit) as the document's only layer (AVIF, Ultra HDR). */
    internal fun loadDecoded(storeHandle: Long, width: Int, height: Int, bitDepth: Int, label: String) {
        loadRaw(NativeRawBridge.Opened(storeHandle, width, height, bitDepth, EMPTY_RAW_METADATA, emptyList()), null, label)
    }

    internal fun loadRaw(
        opened: NativeRawBridge.Opened,
        rawSmart: Pair<ByteArray, app.croptop.core.model.DevelopSettings>? = null,
        historyLabel: String = "Open RAW",
    ) = synchronized(lock) {
        check(!closed) { "Document is closed" }
        require(opened.storeHandle != 0L && opened.width > 0 && opened.height > 0) { "RAW decoder returned an invalid store" }
        val layerId = mutableLayers.value.primary ?: error("Imported RAW has no active pixel layer")
        val newSelection = NativeSelectionBudgetBridge.emptyA16MaskLike(opened.storeHandle)
        check(newSelection != 0L) {
            NativeSelectionBridge.destroyStore(opened.storeHandle)
            NativeSelectionBudgetBridge.nativeLastError().ifBlank { "Could not initialize RAW selection mask" }
        }
        pixelStores.values.forEach(engine::destroyTileStore)
        pixelStores.clear()
        snapshots.forEach(::destroySnapshot)
        snapshots.clear()
        namedSnapshots.forEach(::destroyNamedSnapshot)
        namedSnapshots.clear()
        engine.destroyTileStore(selectionMask)
        engine.destroyTileStore(lastSelectionMask)
        lastSelectionMask = 0L
        lastSelectionInfo = null
        pixelStores[layerId] = opened.storeHandle
        if (rawSmart != null) {
            // Smart-object RAW: the developed pixels are the source, the original file and recipe ride along for re-development.
            val source = engine.cloneTileStore(opened.storeHandle)
            check(source != 0L) { "Could not embed the developed RAW" }
            runCatching { NativeLayerMaskBridge.detach(source) }
            NativeLayerMaskBridge.setClipped(source, false)
            NativeLayerStylesBridge.attach(source, null)
            val sourceKey = CroptopManifest.newLayerUuid()
            val fileKey = CroptopManifest.newLayerUuid()
            smartSources[sourceKey] = source
            rawFiles[fileKey] = rawSmart.first
            val info = app.croptop.core.model.SmartObjectInfo(
                sourceKey = sourceKey,
                sourceWidth = opened.width,
                sourceHeight = opened.height,
                raw = app.croptop.core.model.RawSourceInfo(fileKey, rawSmart.second),
            )
            mutableLayers.value = NativeLayerTreeOps.update(mutableLayers.value, layerId) {
                it.copy(kind = LayerKind.SMART_OBJECT, smartObject = info)
            }
        }
        selectionMask = newSelection
        mutableInfo.value = mutableInfo.value.copy(
            size = DocSize(opened.width, opened.height),
            depth = when (opened.bitDepth) {
                8 -> app.croptop.core.model.ColorDepth.U8
                16 -> app.croptop.core.model.ColorDepth.U16
                else -> app.croptop.core.model.ColorDepth.F32
            },
            profile = app.croptop.core.model.ColorProfileRef.BuiltIn(WorkingSpace.SRGB),
            dirty = false,
        )
        mutableSelection.value = SelectionInfo.None
        snapshots += Snapshot(
            historyLabel,
            mutableLayers.value,
            clonePixelStores(pixelStores, mutableLayers.value) ?: error("Could not snapshot RAW pixels"),
            cloneSelectionMask(selectionMask) ?: error("Could not snapshot RAW selection"),
            SelectionInfo.None,
            0L,
            null,
            mutableInfo.value.size,
            mutableInfo.value.resolutionPpi,
            mutableInfo.value.profile,
            null,
        )
        cursor = 0
        sequence = 0L
        cleanIndex = 0
        viewportOffsets.clear()
        viewportOffsets += 0f to 0f
        importedRasterMetadata = null
        queueViewportComposite(mutableLayers.value)
    }

    internal fun loadCodecExtra(encoded: ByteArray, size: Int) = synchronized(lock) {
        check(!closed) { "Document is closed" }
        val layerId = mutableLayers.value.primary ?: error("Imported document has no active pixel layer")
        val store = pixelStores[layerId] ?: error("Active layer has no pixel store")
        val decoded = NativeCodecExtraBridge.decodeWithProfile(encoded, size, store)
        val installedProfile = installImportedWorkingProfile(decoded.iccProfile)
        importedRasterMetadata = RasterMetadata(
            iccProfile = installedProfile.iccProfile,
            warnings = decoded.warnings + installedProfile.warnings,
        )
        refreshCurrentPixelSnapshot()
        queueViewportComposite(mutableLayers.value)
    }

    /** Installs the decoded source profile into the initial, still-clean document state. */
    internal fun installImportedWorkingProfile(iccProfile: ByteArray?): ImportedProfileInstallation = synchronized(lock) {
        val warnings = mutableListOf<String>()
        var acceptedIcc: ByteArray? = null
        val profile = if (iccProfile == null) {
            ColorProfileRef.BuiltIn(WorkingSpace.SRGB)
        } else if (iccProfile.size > MAX_WORKING_ICC_BYTES) {
            warnings += "Embedded ICC profile exceeds the 16 MiB limit; opened using sRGB without applying that profile"
            ColorProfileRef.BuiltIn(WorkingSpace.SRGB)
        } else {
            try {
                NativeColorManagementBridge.Profile.fromIcc(iccProfile).use { native ->
                    if (native.model != NativeColorManagementBridge.Model.RGB) {
                        warnings += "Embedded ${native.model.name.lowercase()} ICC profile is not supported for the RGB working space; opened using sRGB and the profile was not applied"
                        ColorProfileRef.BuiltIn(WorkingSpace.SRGB)
                    } else {
                        acceptedIcc = iccProfile.copyOf()
                        ColorProfileRef.Embedded(native.description, iccProfile.sha256())
                    }
                }
            } catch (failure: Exception) {
                warnings += "Embedded ICC profile could not be read; opened using sRGB without applying that profile"
                ColorProfileRef.BuiltIn(WorkingSpace.SRGB)
            }
        }
        mutableInfo.value = mutableInfo.value.copy(profile = profile)
        snapshots[cursor] = snapshots[cursor].copy(profile = profile, workingIcc = acceptedIcc?.copyOf())
        importedRasterMetadata = RasterMetadata(iccProfile = acceptedIcc?.copyOf(), warnings = warnings.toList())
        cachedBuiltInIcc = null
        colorReadoutSession?.close()
        colorReadoutSession = null
        ImportedProfileInstallation(acceptedIcc, warnings.toList())
    }

    internal data class ImportedProfileInstallation(val iccProfile: ByteArray?, val warnings: List<String>)

    internal fun pixelStore(id: LayerId): Long? = synchronized(lock) { pixelStores[id] }

    internal fun createSelectionChannelStore(): Long = synchronized(lock) { engine.cloneTileStore(selectionMask) }

    internal fun restoreLoadedLayerUuids(uuid: String, id: LayerId) = synchronized(lock) { layerUuids[id] = uuid }

    internal fun restoreSelectionChannel(name: String, store: Long) = synchronized(lock) {
        require(name !in channelStores) { "Duplicate saved selection '$name'" }
        channelStores[name] = store
        publishChannels()
    }

    /** While opening: makes [handle] the store of layer [id] (replacing any allocated one; takes ownership). */
    internal fun adoptPixelStore(id: LayerId, handle: Long) = synchronized(lock) {
        pixelStores.put(id, handle)?.let(engine::destroyTileStore)
    }

    internal fun finishArchiveOpen() = synchronized(lock) {
        check(!closed) { "Document was closed while opening project" }
        refreshCurrentPixelSnapshot()
        queueViewportComposite(mutableLayers.value)
    }

    override fun execute(command: EditCommand): CommandResult = withDocumentCommand {
        synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        when (command) {
            EditCommand.Undo -> undoLocked()

            EditCommand.Redo -> redoLocked()

            is EditCommand.AddLayer -> {
                val tree = mutableLayers.value
                if (tree.find(command.id) != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer already exists")
                val node = LayerNode(command.id, command.name, command.kind)
                val parentId = command.parentId
                val parent = parentId?.let(tree::find)
                if (parentId != null && parent == null) {
                    return@synchronized reject(RejectionCode.NOT_FOUND, "No parent layer ${parentId.value}")
                }
                if (parent != null && parent.kind != LayerKind.GROUP) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Parent layer must be a group")
                }
                val nextTree = NativeLayerTreeOps.insert(tree, node, parentId, command.index)
                    ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer index is out of range")
                if (node.kind == LayerKind.PIXEL) {
                    val store = engine.createTileStore(mutableInfo.value.size.width, mutableInfo.value.size.height, pixelFormat)
                    if (store == 0L) return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not allocate native layer storage")
                    pixelStores[node.id] = store
                }
                val next = nextTree.copy(
                    active = setOf(command.id),
                    primary = command.id,
                )
                commit("New ${command.kind.name.lowercase()} layer", next)
            }

            is EditCommand.DeleteLayer -> {
                val tree = mutableLayers.value
                val (without, removed) = NativeLayerTreeOps.remove(tree, command.id)
                    ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${command.id.value}")
                if (without.layers.isEmpty()) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "A document needs at least one layer")
                }
                val primary = without.primary ?: without.active.firstOrNull() ?: without.layers.first().id
                val next = without.copy(active = without.active.ifEmpty { setOf(primary) }, primary = primary)
                commit("Delete ${removed.name}", next)
            }

            is EditCommand.MoveLayer -> {
                val tree = mutableLayers.value
                val node = tree.find(command.id)
                    ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${command.id.value}")
                if (node.locks.position && tree.parentOf(command.id)?.id != command.parentId) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer position is locked")
                }
                val parentId = command.parentId
                val parent = parentId?.let(tree::find)
                if (parentId != null && parent == null) {
                    return@synchronized reject(RejectionCode.NOT_FOUND, "No parent layer ${parentId.value}")
                }
                if (parent != null && !parent.isGroup) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Parent layer must be a group")
                }
                if (parentId != null && NativeLayerTreeOps.isWithin(tree, parentId, command.id)) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Cannot move a group into itself")
                }
                val (without, removed) = checkNotNull(NativeLayerTreeOps.remove(tree, command.id))
                val restoredSelection = without.copy(active = tree.active, primary = tree.primary)
                val next = NativeLayerTreeOps.insert(restoredSelection, removed, parentId, command.index)
                    ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Layer index is out of range")
                commit("Move ${node.name}", next)
            }

            is EditCommand.SetLayerProps -> {
                val node = mutableLayers.value.find(command.id)
                    ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${command.id.value}")
                val p = command.properties
                if (p.blendMode?.groupOnly == true && !node.isGroup) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Pass Through applies to groups only")
                }
                updateLayer(command.id) {
                    it.copy(
                        name = p.name ?: it.name,
                        visible = p.visible ?: it.visible,
                        opacity = p.opacity ?: it.opacity,
                        fillOpacity = p.fillOpacity ?: it.fillOpacity,
                        blendMode = p.blendMode ?: it.blendMode,
                        blendIf = p.blendIf ?: it.blendIf,
                    )
                }
            }

            is EditCommand.SetBlendMode -> {
                val node = mutableLayers.value.find(command.id)
                    ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${command.id.value}")
                if (command.blendMode.groupOnly && !node.isGroup) {
                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Pass Through applies to groups only")
                }
                updateLayer(command.id) { it.copy(blendMode = command.blendMode) }
            }
        }
        }
    }

    override fun transaction(label: String, block: DocumentHandle.() -> Unit): CommandResult = withDocumentCommand {
        synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (label.isBlank()) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Transaction label must not be blank")
        if (activeTransactionStartCursor != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Nested transactions are not supported")
        finishSelectionTransformBeforeMutation()
        val start = cursor
        activeTransactionStartCursor = start
        try {
            block(this)
            mergeHistorySince(start, label)
        } finally {
            activeTransactionStartCursor = null
        }
        }
    }

    /** Replaces the history entries after [start] with one entry labeled [label] (the last state keeps its pixels). */
    private fun mergeHistorySince(start: Int, label: String): CommandResult {
        val end = cursor
        if (end <= start) return CommandResult.Accepted(sequence)
        val final = snapshots[end].copy(label = label)
        for (index in end - 1 downTo start + 1) destroySnapshot(snapshots[index])
        snapshots.subList(start + 1, end + 1).clear()
        snapshots.add(final)
        cursor = start + 1
        publish()
        return CommandResult.Accepted(++sequence)
    }

    private var historyGroupLabel: String? = null

    override fun beginHistoryGroup(label: String): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (label.isBlank()) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "A history group needs a label")
        if (activeTransactionStartCursor != null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "A history group or transaction is already open")
        finishSelectionTransformBeforeMutation()
        activeTransactionStartCursor = cursor
        historyGroupLabel = label
        CommandResult.Accepted(sequence)
    } }

    override fun endHistoryGroup(): CommandResult = withDocumentCommand { synchronized(lock) {
        val start = activeTransactionStartCursor ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "No history group is open")
        val label = historyGroupLabel ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "No history group is open")
        activeTransactionStartCursor = null
        historyGroupLabel = null
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        mergeHistorySince(start, label)
    } }

    override fun cancelHistoryGroup(): CommandResult = withDocumentCommand { synchronized(lock) {
        val start = activeTransactionStartCursor ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "No history group is open")
        activeTransactionStartCursor = null
        historyGroupLabel = null
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        while (cursor > start) {
            val undone = undoLocked()
            if (undone !is CommandResult.Accepted) return@synchronized undone
        }
        // Forget what was undone: the group never happened, so there is nothing to redo.
        while (snapshots.lastIndex > cursor) destroySnapshot(snapshots.removeAt(snapshots.lastIndex))
        publish()
        CommandResult.Accepted(sequence)
    } }

    private fun updateLayer(id: LayerId, transform: (LayerNode) -> LayerNode): CommandResult {
        val tree = mutableLayers.value
        if (tree.find(id) == null) return reject(RejectionCode.NOT_FOUND, "No layer ${id.value}")
        return commit("Layer properties", NativeLayerTreeOps.update(tree, id, transform))
    }

    override fun undo(): CommandResult = withDocumentCommand { synchronized(lock) { undoLocked() } }

    private fun undoLocked(): CommandResult {
        activeLiquify?.cancel() // a Liquify preview is not part of history
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (cursor == 0) return reject(RejectionCode.INVALID_ARGUMENT, "Nothing to undo")
        val oldOffset = viewportOffsets[cursor]
        cursor--
        if (!restoreCurrentPixelStores()) {
            cursor++
            return reject(RejectionCode.UNSUPPORTED, "Could not restore document pixels")
        }
        publish()
        updateViewportPreview(oldOffset, viewportOffsets[cursor])
        return CommandResult.Accepted(++sequence)
    }

    override fun redo(): CommandResult = withDocumentCommand { synchronized(lock) { redoLocked() } }

    private fun redoLocked(): CommandResult {
        activeLiquify?.cancel() // a Liquify preview is not part of history
        if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (cursor == snapshots.lastIndex) return reject(RejectionCode.INVALID_ARGUMENT, "Nothing to redo")
        val oldOffset = viewportOffsets[cursor]
        cursor++
        if (!restoreCurrentPixelStores()) {
            cursor--
            return reject(RejectionCode.UNSUPPORTED, "Could not restore document pixels")
        }
        publish()
        updateViewportPreview(oldOffset, viewportOffsets[cursor])
        return CommandResult.Accepted(++sequence)
    }

    override fun jumpTo(historyIndex: Int): CommandResult = withDocumentCommand {
        synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (historyIndex !in snapshots.indices) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "No history state $historyIndex")
        val previousCursor = cursor
        val previousOffset = viewportOffsets[cursor]
        cursor = historyIndex
        if (!restoreCurrentPixelStores()) {
            cursor = previousCursor
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not restore document pixels")
        }
        publish()
        updateViewportPreview(previousOffset, viewportOffsets[cursor])
        CommandResult.Accepted(++sequence)
        }
    }

    override fun createSnapshot(name: String): CommandResult = withDocumentCommand {
        synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > MAX_SNAPSHOT_NAME_LENGTH) {
            return@synchronized reject(
                RejectionCode.INVALID_ARGUMENT,
                "Snapshot name must be 1 to $MAX_SNAPSHOT_NAME_LENGTH characters",
            )
        }
        if (namedSnapshots.any { it.name.equals(normalized, ignoreCase = true) }) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "A snapshot with that name already exists")
        }
        finishSelectionTransformBeforeMutation()
        val savedPixels = clonePixelStores(pixelStores, snapshots[cursor].tree)
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve document pixels for snapshot")
        val savedSelection = cloneSelectionMask(selectionMask)
            ?: run {
                savedPixels.values.forEach(engine::destroyTileStore)
                return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve selection for snapshot")
            }
        val savedLastSelection = cloneOptionalSelection(lastSelectionMask)
            ?: run {
                savedPixels.values.forEach(engine::destroyTileStore)
                engine.destroyTileStore(savedSelection)
                return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not preserve reselect state for snapshot")
            }
        namedSnapshots +=
            NamedSnapshot(
                normalized,
                snapshots[cursor].tree,
                savedPixels,
                savedSelection,
                mutableSelection.value,
                savedLastSelection,
                lastSelectionInfo,
                mutableInfo.value.size,
                mutableInfo.value.resolutionPpi,
                viewportOffsets[cursor],
                snapshots[cursor].profile,
                snapshots[cursor].workingIcc?.copyOf(),
            )
        publishHistory()
        CommandResult.Accepted(++sequence)
        }
    }

    override fun renameSnapshot(index: Int, name: String): CommandResult = withDocumentCommand {
        synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (index !in namedSnapshots.indices) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "No named snapshot at index $index")
        }
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > MAX_SNAPSHOT_NAME_LENGTH) {
            return@synchronized reject(
                RejectionCode.INVALID_ARGUMENT,
                "Snapshot name must be 1 to $MAX_SNAPSHOT_NAME_LENGTH characters",
            )
        }
        if (namedSnapshots.withIndex().any { (other, snapshot) ->
                other != index && snapshot.name.equals(normalized, ignoreCase = true)
            }
        ) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "A snapshot with that name already exists")
        }
        namedSnapshots[index] = namedSnapshots[index].copy(name = normalized)
        publishHistory()
        CommandResult.Accepted(++sequence)
        }
    }

    override fun deleteSnapshot(index: Int): CommandResult = withDocumentCommand {
        synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (index !in namedSnapshots.indices) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "No named snapshot at index $index")
        }
        namedSnapshots.removeAt(index).also(::destroyNamedSnapshot)
        publishHistory()
        CommandResult.Accepted(++sequence)
        }
    }

    override fun jumpToSnapshot(index: Int): CommandResult = withDocumentCommand {
        synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val named = namedSnapshots.getOrNull(index)
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "No named snapshot at index $index")
        finishSelectionTransformBeforeMutation()
        val savedPixels = clonePixelStores(named.pixelStores, named.tree)
            ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not restore named snapshot pixels")
        val activePixels = clonePixelStores(savedPixels, named.tree)
        if (activePixels == null) {
            savedPixels.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not restore named snapshot pixels")
        }
        val savedSelection = cloneSelectionMask(named.selectionMask)
        if (savedSelection == null) {
            savedPixels.values.forEach(engine::destroyTileStore)
            activePixels.values.forEach(engine::destroyTileStore)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not restore named snapshot selection")
        }
        val activeSelection = cloneSelectionMask(savedSelection)
        if (activeSelection == null) {
            savedPixels.values.forEach(engine::destroyTileStore)
            activePixels.values.forEach(engine::destroyTileStore)
            engine.destroyTileStore(savedSelection)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not restore named snapshot selection")
        }
        val savedLastSelection = cloneOptionalSelection(named.lastSelectionMask)
        if (savedLastSelection == null) {
            savedPixels.values.forEach(engine::destroyTileStore)
            activePixels.values.forEach(engine::destroyTileStore)
            engine.destroyTileStore(savedSelection)
            engine.destroyTileStore(activeSelection)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not restore named reselect state")
        }
        val activeLastSelection = cloneOptionalSelection(named.lastSelectionMask)
        if (activeLastSelection == null) {
            savedPixels.values.forEach(engine::destroyTileStore)
            activePixels.values.forEach(engine::destroyTileStore)
            engine.destroyTileStore(savedSelection)
            engine.destroyTileStore(activeSelection)
            engine.destroyTileStore(savedLastSelection)
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not restore named reselect state")
        }
        val previousOffset = viewportOffsets[cursor]
        while (snapshots.lastIndex > cursor) {
            destroySnapshot(snapshots.removeAt(snapshots.lastIndex))
            viewportOffsets.removeAt(viewportOffsets.lastIndex)
        }
        snapshots += Snapshot(
            "Restore snapshot: ${named.name}", named.tree, savedPixels, savedSelection, named.selectionInfo,
            savedLastSelection, named.lastSelectionInfo, named.documentSize, named.resolutionPpi, named.profile,
            named.workingIcc?.copyOf(),
        )
        viewportOffsets += named.previewOffset
        cursor = snapshots.lastIndex
        pixelStores.values.forEach(engine::destroyTileStore)
        pixelStores.clear()
        pixelStores.putAll(activePixels)
        engine.destroyTileStore(selectionMask)
        selectionMask = activeSelection
        engine.destroyTileStore(lastSelectionMask)
        lastSelectionMask = activeLastSelection
        lastSelectionInfo = named.lastSelectionInfo
        mutableSelection.value = named.selectionInfo
        releaseUnreferencedStores()
        publish()
        updateViewportPreview(previousOffset, viewportOffsets[cursor])
        CommandResult.Accepted(++sequence)
        }
    }

    override fun beginStroke(spec: StrokeSpec): StrokeSession = beginStrokeSession(spec, maskTarget = false)

    override fun beginMaskStroke(spec: StrokeSpec): StrokeSession = beginStrokeSession(spec, maskTarget = true)

    override fun beginRetouch(spec: StrokeSpec, effect: RetouchEffect): StrokeSession = when (effect) {
        is RetouchEffect.Smudge -> if (effect.sampleAll || effect.fingerPainting) {
            app.croptop.core.engine.api.UnsupportedStrokeSession("Smudge all-layer sampling and finger painting")
        } else {
            beginStrokeSession(spec, maskTarget = false, retouchEffect = effect)
        }
        is RetouchEffect.Clone, is RetouchEffect.Heal, is RetouchEffect.SpotHeal,
        is RetouchEffect.Focus, is RetouchEffect.Tone, is RetouchEffect.Sponge, is RetouchEffect.HistoryPaint,
        is RetouchEffect.BackgroundErase ->
            beginStrokeSession(spec, maskTarget = false, retouchEffect = effect)
        else -> app.croptop.core.engine.api.UnsupportedStrokeSession(effect.label)
    }

    /** History Brush: restores the stroke area of [target] from the pixels it had in history state [stateIndex]. */
    private fun applyHistoryPaint(stateIndex: Int, target: LayerId, working: Long, session: Long, selection: Long): String? {
        val state = snapshots.getOrNull(stateIndex) ?: return "That history state no longer exists"
        val source = state.pixelStores[target] ?: return "That history state has no copy of this layer"
        return try {
            engine.applySourceRetouchSession(source, working, session, false, false, DocPoint(0f, 0f), selection, samePosition = true)
        } catch (failure: Exception) {
            failure.message ?: "Could not read the history state"
        }
    }

    private fun applySourceRetouch(
        effect: RetouchEffect,
        tree: LayerTree,
        baseline: Long,
        working: Long,
        session: Long,
        selection: Long,
    ): String? {
        val sampleAll = when (effect) {
            is RetouchEffect.Clone -> effect.sampleAll
            is RetouchEffect.Heal -> effect.sampleAll
            is RetouchEffect.SpotHeal -> effect.sampleAll
            else -> false
        }
        var merged = 0L
        return try {
            val source = if (sampleAll) flattenVisible(tree).also { merged = it } else baseline
            when (effect) {
                is RetouchEffect.Clone -> engine.applySourceRetouchSession(source, working, session, false, sampleAll, effect.source, selection)
                is RetouchEffect.Heal -> engine.applySourceRetouchSession(source, working, session, true, sampleAll, effect.source, selection)
                is RetouchEffect.SpotHeal -> engine.applySpotRetouchSession(source, working, session, effect.type.ordinal, selection)
                else -> error("Source retouch needs Clone, Heal or Spot Heal")
            }
        } catch (failure: Exception) {
            failure.message ?: "Could not prepare the retouch source"
        } finally {
            if (merged != 0L) engine.destroyTileStore(merged)
        }
    }

    private fun beginStrokeSession(
        spec: StrokeSpec,
        maskTarget: Boolean,
        retouchEffect: RetouchEffect? = null,
    ): StrokeSession {
        val settings = spec.brush?.normalized() ?: BrushSettings(
            size = spec.diameter,
            hardness = spec.hardness,
            opacity = spec.opacity,
            flow = 1f,
            sizeDynamics = Dynamics(DynamicsInput.OFF),
        )
        val sessionLock = Any()
        val documentSize = mutableInfo.value.size
        var nativeSession = engine.createBrushSession(settings, documentSize.width, documentSize.height)
        var streamError: String? = if (nativeSession == 0L) "Could not initialize the native brush session" else null
        if ((retouchEffect is RetouchEffect.Smudge || retouchEffect is RetouchEffect.BackgroundErase) && nativeSession != 0L) {
            streamError = engine.recordRetouchDabs(nativeSession)
        }
        var queueOverflowed = false
        var sampleCount = 0
        var done = false
        var session: StrokeSession? = null
        var strokeSelectionMask = 0L
        val lane = synchronized(lock) {
            if (closed) {
                null
            } else {
                if (streamError == null && mutableSelection.value != SelectionInfo.None) {
                    strokeSelectionMask = engine.cloneTileStore(selectionMask)
                    if (strokeSelectionMask == 0L) {
                        streamError = "Could not preserve the active selection for this stroke"
                    }
                }
                strokeWorkQueue.newLane {
                    val (handle, selectionHandle) = synchronized(sessionLock) {
                        nativeSession.also { nativeSession = 0L } to
                            strokeSelectionMask.also { strokeSelectionMask = 0L }
                    }
                    engine.destroyBrushSession(handle)
                    if (selectionHandle != 0L) engine.destroyTileStore(selectionHandle)
                    session?.let(activeBrushSessions::remove)
                }
            }
        }
        if (lane == null) {
            engine.destroyBrushSession(nativeSession)
            nativeSession = 0L
            if (strokeSelectionMask != 0L) {
                engine.destroyTileStore(strokeSelectionMask)
                strokeSelectionMask = 0L
            }
            streamError = "Document is closed"
        }
        session = object : StrokeSession {
            override fun add(samples: FloatArray) = synchronized(sessionLock) {
                require(samples.size % StrokeSampleLayout.STRIDE == 0) {
                    "Stroke samples must be a multiple of ${StrokeSampleLayout.STRIDE}"
                }
                check(!done) { "Stroke is already finished" }
                require(samples.all { it.isFinite() }) { "Stroke samples must be finite" }
                val added = samples.size / StrokeSampleLayout.STRIDE
                require(sampleCount + added <= MAX_STROKE_SAMPLES) { "Stroke exceeds $MAX_STROKE_SAMPLES samples" }
                if (streamError == null && samples.isNotEmpty()) {
                    val accepted = checkNotNull(lane).addFloats(samples) { immutableSamples ->
                        val error = engine.addBrushSamples(nativeSession, immutableSamples)
                        if (error != null) throw BrushStreamFailure(error)
                    }
                    if (!accepted) {
                        queueOverflowed = true
                        streamError = "Brush sample queue is full or the stroke is no longer accepting samples"
                    }
                }
                sampleCount += added
            }

            override fun end(): CommandResult {
                val sessionState = synchronized(sessionLock) {
                    check(!done) { "Stroke is already finished" }
                    done = true
                    Triple(nativeSession, streamError, queueOverflowed)
                }
                val handle = sessionState.first
                val error = sessionState.second
                val didOverflow = sessionState.third
                synchronized(sampleAdmissionLock) { pendingBrushCommits.incrementAndGet() }
                return try {
                    val workLane = lane ?: return reject(RejectionCode.CLOSED, "Document is closed")
                    workLane.finish {
                        withDocumentCommand { synchronized(lock) {
                            if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
                            if (error != null) {
                                val code = if (didOverflow) RejectionCode.QUEUE_FULL else RejectionCode.INVALID_ARGUMENT
                                return@synchronized reject(code, error)
                            }
                            if (handle == 0L) {
                                return@synchronized reject(RejectionCode.UNSUPPORTED, "Native brush session is unavailable")
                            }
                            if (sampleCount == 0) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Empty stroke")
                            if (activeAdjustmentPreview != null) {
                                return@synchronized reject(
                                    RejectionCode.INVALID_ARGUMENT,
                                    "Finish the active adjustment preview before painting",
                                )
                            }
                            if (maskTarget && quickMaskState.value) return@synchronized commitQuickMaskStroke(handle, spec)
                            val tree = mutableLayers.value
                            val layer = tree.find(spec.target)
                                ?: return@synchronized reject(RejectionCode.NOT_FOUND, "No layer ${spec.target.value}")
                            if (layer.kind != LayerKind.PIXEL) {
                                return@synchronized reject(RejectionCode.UNSUPPORTED, "${layer.name} is not a pixel layer")
                            }
                            if (maskTarget) {
                                if (layer.mask == null) {
                                    return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "${layer.name} has no mask to paint on")
                                }
                                return@synchronized commitMaskStroke(tree, layer, handle, spec, strokeSelectionMask)
                            }
                            if (layer.locks.pixels) {
                                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "${layer.name} has pixel editing locked")
                            }
                            if ((spec.erase || retouchEffect is RetouchEffect.BackgroundErase) && layer.locks.transparency) {
                                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "${layer.name} has transparency locked")
                            }
                            val sourceRetouch = retouchEffect is RetouchEffect.Clone || retouchEffect is RetouchEffect.Heal ||
                                retouchEffect is RetouchEffect.SpotHeal || retouchEffect is RetouchEffect.HistoryPaint
                            if (sourceRetouch && layer.locks.transparency) {
                                return@synchronized reject(
                                    RejectionCode.UNSUPPORTED,
                                    "Source retouch on a transparency-locked layer is not available",
                                )
                            }
                            val sourcePoint = when (val effect = retouchEffect) {
                                is RetouchEffect.Clone -> effect.source
                                is RetouchEffect.Heal -> effect.source
                                else -> null
                            }
                            val canvasSize = mutableInfo.value.size
                            if (sourcePoint != null &&
                                (sourcePoint.x < 0f || sourcePoint.y < 0f || sourcePoint.x >= canvasSize.width || sourcePoint.y >= canvasSize.height)
                            ) {
                                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Retouch source must be inside the canvas")
                            }
                            val baselineStore = pixelStores[spec.target]
                                ?: return@synchronized reject(
                                    RejectionCode.NOT_FOUND,
                                    "The target pixel layer has no baseline storage",
                                )

                            val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
                                ?: return@synchronized reject(
                                    RejectionCode.UNSUPPORTED,
                                    "Could not prepare layer pixels for painting",
                                )
                            var ownsPrepared = true
                            fun discardPrepared() {
                                if (!ownsPrepared) return
                                ownsPrepared = false
                                prepared.values.forEach(engine::destroyTileStore)
                                prepared.clear()
                            }

                            val workingStore = prepared[spec.target]
                            if (workingStore == null) {
                                discardPrepared()
                                return@synchronized reject(RejectionCode.NOT_FOUND, "The target pixel layer has no pixel storage")
                            }
                            val applyError = when (val effect = retouchEffect) {
                                is RetouchEffect.Clone, is RetouchEffect.Heal, is RetouchEffect.SpotHeal -> applySourceRetouch(
                                    effect, tree, baselineStore, workingStore, handle, strokeSelectionMask,
                                )
                                is RetouchEffect.BackgroundErase -> engine.applyBackgroundEraserSession(
                                    workingStore, handle, effect, strokeSelectionMask,
                                )
                                is RetouchEffect.HistoryPaint -> applyHistoryPaint(effect.stateIndex, spec.target, workingStore, handle, strokeSelectionMask)
                                is RetouchEffect.Focus -> engine.applyRetouchSession(
                                    baselineStore, workingStore, handle, effect.sharpen, effect.strength,
                                    effect.protectDetail, strokeSelectionMask,
                                )
                                is RetouchEffect.Tone -> engine.applyToneRetouchSession(
                                    baselineStore, workingStore, handle, effect.burn, effect.range.ordinal,
                                    effect.exposure, effect.protectTones, strokeSelectionMask,
                                )
                                is RetouchEffect.Sponge -> engine.applySpongeRetouchSession(
                                    baselineStore, workingStore, handle, effect.saturate, effect.flow,
                                    effect.vibrance, strokeSelectionMask,
                                )
                                is RetouchEffect.Smudge -> engine.applySmudgeRetouchSession(
                                    baselineStore, workingStore, handle, effect.strength, effect.fingerPainting,
                                    strokeSelectionMask,
                                )
                                null -> engine.applyBrushSession(
                                    baselineStore, workingStore, handle, spec.color, spec.erase,
                                    layer.locks.transparency, strokeSelectionMask,
                                )
                                else -> error("unsupported retouch effect reached native commit")
                            }
                            if (applyError != null) {
                                discardPrepared()
                                return@synchronized reject(RejectionCode.UNSUPPORTED, applyError)
                            }
                            ownsPrepared = false // commitPrepared now owns these COW wrappers on every path.
                            commitPrepared(retouchEffect?.label ?: if (spec.erase) "Eraser" else "Brush Tool", tree, prepared)
                        }}
                    }
                } catch (failure: Throwable) {
                    when (failure) {
                        is StrokeWorkCancelledException -> reject(RejectionCode.CLOSED, "Stroke was cancelled")
                        is StrokeQueueOverflowException -> reject(RejectionCode.QUEUE_FULL, failure.message.orEmpty())
                        is BrushStreamFailure -> reject(RejectionCode.UNSUPPORTED, failure.message.orEmpty())
                        else -> reject(RejectionCode.UNSUPPORTED, failure.message ?: "Brush stroke failed")
                    }
                } finally {
                    pendingBrushCommits.decrementAndGet()
                }
            }

            override fun cancel() {
                val shouldCancel = synchronized(sessionLock) {
                    if (done) return
                    done = true
                    true
                }
                if (shouldCancel) lane?.cancel()
            }
        }
        val createdSession = checkNotNull(session)
        synchronized(lock) {
            if (closed) {
                createdSession.cancel()
            } else {
                activeBrushSessions.add(createdSession)
            }
        }
        return createdSession
    }

    /** Applies a finished stroke to an isolated copy of the layer mask and commits one history snapshot. */
    // ---- Quick Mask: mask strokes paint into the selection mask ----

    private val quickMaskState = MutableStateFlow(false)
    private val quickMaskOverlayColorState = MutableStateFlow(RgbaColor(1f, 0f, 0f, 0.5f))
    @Volatile private var activeRefinePreviewMode: RefineEdgeViewMode? = null

    // ---- Autosave for crash recovery ----
    private var autosaveHandle = 0L
    private var journalName: String? = null
    internal var recoverySourceUri: String? = null
    private var autosaveTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var firstUnsavedEditMs = 0L

    /** A document rebuilt from a recovery journal starts modified (it has never been saved). */
    internal fun adoptRecovery(sourceUri: String?) = synchronized(lock) {
        recoverySourceUri = sourceUri
        cleanIndex = -1
        mutableInfo.value = mutableInfo.value.copy(dirty = true)
    }

    /** With [lock] held after a published change while modified: due 5 s after the last edit, at most 60 s after the first. */
    private fun scheduleAutosaveLocked() {
        if (engine.recoveryRoot == null || closed) return
        val now = System.currentTimeMillis()
        if (firstUnsavedEditMs == 0L) firstUnsavedEditMs = now
        autosaveTask?.cancel(false)
        val delay = minOf(AUTOSAVE_IDLE_MS, maxOf(0L, firstUnsavedEditMs + AUTOSAVE_MAX_MS - now))
        autosaveTask = runCatching {
            engine.autosaveTimer.schedule({ autosaveNow(flushTimeoutMs = 0L) }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /** Hands a COW snapshot to the journal; with a positive timeout waits until it is durable. True on success. */
    internal fun autosaveNow(flushTimeoutMs: Long): Boolean {
        val handle = synchronized(lock) {
            if (closed || !mutableInfo.value.dirty) return false
            val root = engine.recoveryRoot ?: return false
            try {
                val name = journalName ?: "doc-${System.currentTimeMillis()}-${id.value}".also { name ->
                    if (!engine.claimJournal(name)) return false
                    journalName = name
                }
                if (autosaveHandle == 0L) autosaveHandle = NativeRecoveryBridge.nativeAutosaveCreate(File(root, name).absolutePath)
                if (autosaveHandle == 0L) return false
                val state = captureSaveState()
                try {
                    val metadata = app.croptop.core.io.RecoveryMetadata(
                        documentName = mutableInfo.value.name,
                        width = mutableInfo.value.size.width,
                        height = mutableInfo.value.size.height,
                        lastEditEpochMs = System.currentTimeMillis(),
                        sourceUri = recoverySourceUri,
                        appVersion = engine.capabilities.engineVersion,
                    ).encode()
                    val submitted = NativeRecoveryBridge.nativeAutosaveSubmit(
                        autosaveHandle,
                        metadata,
                        state.manifestJson,
                        state.pixels.map { it.third }.toLongArray(),
                        state.pixels.map { it.second }.toTypedArray(),
                        state.workingIcc,
                    )
                    if (!submitted) return false
                    persistRecoveryRawFiles(name, state.embedded)
                } finally {
                    state.stores.values.forEach(engine::destroyTileStore)
                    state.ownedStores.forEach(engine::destroyTileStore)
                }
                firstUnsavedEditMs = 0L
                autosaveHandle
            } catch (_: Exception) {
                return false // best effort: the next edit schedules another attempt
            }
        }
        return flushTimeoutMs <= 0L || NativeRecoveryBridge.nativeAutosaveFlush(handle, flushTimeoutMs)
    }

    /** Writes each RAW original once next to journal [name] so crash recovery can still re-develop RAW smart objects. */
    private fun persistRecoveryRawFiles(name: String, embedded: List<NativeEngine.EmbeddedFile>) {
        val dir = engine.recoveryRawDir(name) ?: return
        val originals = embedded.filter { it.name == "original.raw" }
        if (originals.isEmpty()) return
        runCatching {
            dir.mkdirs()
            for (file in originals) {
                val target = File(dir, file.id)
                if (target.isFile && target.length() == file.bytes.size.toLong()) continue
                val temp = File(dir, file.id + ".tmp")
                temp.writeBytes(file.bytes)
                if (!temp.renameTo(target)) temp.delete()
            }
        }
    }

    /** Recovery: reloads RAW originals saved by [persistRecoveryRawFiles] for the RAW smart objects in this document. */
    internal fun restoreRawFilesFrom(dir: File) {
        if (!dir.isDirectory) return
        val keys = synchronized(lock) { mutableLayers.value.flatten().mapNotNull { it.smartObject?.raw?.fileKey }.toSet() }
        for (key in keys) {
            if (!JOURNAL_NAME.matches(key)) continue
            val file = File(dir, key)
            if (file.isFile) runCatching { restoreRawFile(key, file.readBytes()) }
        }
    }

    /** After a clean save or on close: the journal is no longer needed. Holds [lock]. */
    private fun discardAutosaveLocked(destroy: Boolean) {
        journalName?.let { engine.recoveryRawDir(it)?.deleteRecursively() }
        autosaveTask?.cancel(false)
        autosaveTask = null
        firstUnsavedEditMs = 0L
        if (autosaveHandle != 0L) {
            NativeRecoveryBridge.nativeAutosaveDiscard(autosaveHandle)
            if (destroy) {
                NativeRecoveryBridge.nativeAutosaveDestroy(autosaveHandle)
                autosaveHandle = 0L
            }
        }
        if (destroy) journalName?.let(engine::releaseJournal)
    }

    // ---- Document guides/grid: saved in .croptop, outside history ----
    private val documentGuidesState = MutableStateFlow(app.croptop.core.engine.api.DocumentGuides())
    private var guidesModified = false
    override val documentGuides: StateFlow<app.croptop.core.engine.api.DocumentGuides> get() = documentGuidesState.asStateFlow()

    override fun setDocumentGuides(value: app.croptop.core.engine.api.DocumentGuides): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (value != documentGuidesState.value) {
            documentGuidesState.value = value
            guidesModified = true
            mutableInfo.value = mutableInfo.value.copy(dirty = true)
            scheduleAutosaveLocked()
        }
        CommandResult.Accepted(sequence)
    }}

    /** Restores guides/grid read from a project manifest (not a modification). */
    internal fun restoreDocumentGuides(value: app.croptop.core.engine.api.DocumentGuides) = synchronized(lock) {
        documentGuidesState.value = value
        guidesModified = false
    }
    override val quickMask: StateFlow<Boolean> get() = quickMaskState.asStateFlow()
    override val quickMaskOverlayColor: StateFlow<RgbaColor> get() = quickMaskOverlayColorState.asStateFlow()

    private val maskViewState = MutableStateFlow<LayerId?>(null)
    override val maskView: StateFlow<LayerId?> get() = maskViewState.asStateFlow()

    override fun setMaskView(id: LayerId?): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (id != null && mutableLayers.value.find(id)?.mask == null) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "That layer has no mask to view")
        if (maskViewState.value == id) return@synchronized acceptAppliedOnly(if (id == null) "Show Composite" else "View Mask")
        maskViewState.value = id
        acceptViewportStateChange(if (id == null) "Show Composite" else "View Mask")
    }}

    /** Captures an owned COW store under [lock]; conversion to A16 mask pixels is deferred to the render worker. */
    private fun viewportMaskOverlaySource(): ViewportMaskOverlaySource? {
        activeRefinePreviewMode?.let { mode ->
            val nativeMode = when (mode) {
                RefineEdgeViewMode.OVERLAY -> 1
                RefineEdgeViewMode.ON_BLACK -> 2
                RefineEdgeViewMode.ON_WHITE -> 3
                RefineEdgeViewMode.BLACK_AND_WHITE -> 0
            }
            return engine.cloneTileStore(selectionMask).takeIf { it != 0L }
                ?.let { ViewportMaskOverlaySource(it, nativeMode, extractLayerMask = false) }
        }
        maskViewState.value?.let { id ->
            val store = pixelStores[id] ?: return null
            return engine.cloneTileStore(store).takeIf { it != 0L }
                ?.let { ViewportMaskOverlaySource(it, mode = 0, extractLayerMask = true) }
        }
        if (quickMaskState.value) {
            return engine.cloneTileStore(selectionMask).takeIf { it != 0L }
                ?.let { ViewportMaskOverlaySource(it, mode = 1, extractLayerMask = false) }
        }
        return null
    }

    /** Produces a caller-owned overlay store from an immutable source snapshot outside the document lock. */
    private fun buildViewportMaskOverlay(source: ViewportMaskOverlaySource): Pair<Long, Int>? {
        if (!source.extractLayerMask) return source.store to source.mode
        val overlay = runCatching {
            val mask = NativeLayerMaskBridge.fromLayerStore(source.store).takeIf { it != 0L } ?: return@runCatching null
            try {
                NativeLayerMaskBridge.pixels(mask).takeIf { it != 0L }
            } finally {
                NativeLayerMaskBridge.destroy(mask)
            }
        }.getOrNull() ?: return null
        return overlay to source.mode
    }

    override fun setQuickMask(enabled: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        finishSelectionTransformBeforeMutation()
        if (quickMaskState.value == enabled) return@synchronized acceptAppliedOnly(if (enabled) "Quick Mask on" else "Quick Mask off")
        quickMaskState.value = enabled
        acceptViewportStateChange(if (enabled) "Quick Mask on" else "Quick Mask off")
    }}

    override fun setQuickMaskOverlayColor(color: RgbaColor): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (listOf(color.r, color.g, color.b, color.a).any { !it.isFinite() || it !in 0f..1f }) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Quick Mask color channels must be finite values from 0 to 1")
        }
        if (quickMaskOverlayColorState.value == color) return@synchronized acceptAppliedOnly("Quick Mask color")
        quickMaskOverlayColorState.value = color
        acceptViewportStateChange("Quick Mask color")
    }}

    /** Accept a UI state change whose result does not alter viewport pixels. */
    private fun acceptAppliedOnly(label: String): CommandResult.Accepted {
        val acceptedSequence = ++sequence
        mutableEvents.tryEmit(EngineEvent.Applied(acceptedSequence, label))
        return CommandResult.Accepted(acceptedSequence)
    }

    /** Correlate transient display-mode changes with the composite they request. Holds [lock]. */
    private fun acceptViewportStateChange(label: String): CommandResult.Accepted {
        val acceptedSequence = ++sequence
        mutableEvents.tryEmit(EngineEvent.Applied(acceptedSequence, label))
        queueViewportComposite(mutableLayers.value, acceptedSequence)
        return CommandResult.Accepted(acceptedSequence)
    }

    private fun commitQuickMaskStroke(brushSession: Long, spec: StrokeSpec): CommandResult {
        val working = engine.cloneTileStore(selectionMask)
        if (working == 0L) return reject(RejectionCode.UNSUPPORTED, "Could not preserve the selection before painting")
        val value = if (spec.erase) 0f else (0.2126f * spec.color.r + 0.7152f * spec.color.g + 0.0722f * spec.color.b).coerceIn(0f, 1f)
        val error = engine.applyMaskBrushSession(selectionMask, working, 0f, brushSession, value, spec.color.a, spec.erase, 0L)
        if (error != null) {
            engine.destroyTileStore(working)
            return reject(RejectionCode.UNSUPPORTED, error)
        }
        return installSelection(working, selectionInfo(working, true), if (spec.erase) "Quick Mask Eraser" else "Quick Mask Brush")
    }

    private fun commitMaskStroke(
        tree: LayerTree,
        layer: LayerNode,
        brushSession: Long,
        spec: StrokeSpec,
        strokeSelectionMask: Long,
    ): CommandResult {
        val maskInfo = layer.mask ?: return reject(RejectionCode.INVALID_ARGUMENT, "${layer.name} has no mask to paint on")
        val baselineLayer = pixelStores[layer.id]
            ?: return reject(RejectionCode.NOT_FOUND, "The target layer has no baseline storage")
        val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
            ?: return reject(RejectionCode.UNSUPPORTED, "Could not prepare layer mask for painting")
        fun discardPrepared() = prepared.values.forEach(engine::destroyTileStore)
        val workingLayer = prepared[layer.id] ?: run {
            discardPrepared()
            return reject(RejectionCode.NOT_FOUND, "The target layer has no writable storage")
        }

        var mask = 0L
        var baselineMaskPixels = 0L
        var workingMaskPixels = 0L
        var paintedMask = 0L
        try {
            mask = NativeLayerMaskBridge.fromLayerStore(baselineLayer)
            val empty = NativeLayerMaskBridge.emptyCoverage(mask)
            baselineMaskPixels = NativeLayerMaskBridge.pixels(mask)
            workingMaskPixels = engine.cloneTileStore(baselineMaskPixels)
            if (workingMaskPixels == 0L) {
                discardPrepared()
                return reject(RejectionCode.UNSUPPORTED, "Could not preserve the layer mask before painting")
            }
            val value = if (spec.erase) 0f else {
                (0.2126f * spec.color.r + 0.7152f * spec.color.g + 0.0722f * spec.color.b).coerceIn(0f, 1f)
            }
            val error = engine.applyMaskBrushSession(
                baselineMaskPixels,
                workingMaskPixels,
                empty,
                brushSession,
                value,
                spec.color.a,
                spec.erase,
                strokeSelectionMask,
            )
            if (error != null) {
                discardPrepared()
                return reject(RejectionCode.UNSUPPORTED, error)
            }
            paintedMask = NativeLayerMaskBridge.fromStore(workingMaskPixels, empty)
            NativeLayerMaskBridge.attach(workingLayer, paintedMask, maskInfo)
            return commitPrepared(if (spec.erase) "Eraser Mask" else "Brush Tool Mask", tree, prepared)
        } catch (failure: Throwable) {
            discardPrepared()
            return reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not paint the layer mask")
        } finally {
            if (paintedMask != 0L) NativeLayerMaskBridge.destroy(paintedMask)
            if (workingMaskPixels != 0L) engine.destroyTileStore(workingMaskPixels)
            if (baselineMaskPixels != 0L) engine.destroyTileStore(baselineMaskPixels)
            if (mask != 0L) NativeLayerMaskBridge.destroy(mask)
        }
    }

    override suspend fun awaitViewportPreview() {
        while (true) {
            val (generation, ready) = synchronized(lock) {
                previewRenderGeneration to previewReady
            }
            ready?.await() ?: return
            if (synchronized(lock) { previewReadyGeneration == generation }) return
        }
    }

    internal fun setViewportPreview(bitmap: Bitmap?, layerId: LayerId) = synchronized(lock) {
        if (closed) {
            bitmap?.recycle()
        } else {
            viewportPreview?.takeIf { it !== bitmap }?.recycle()
            viewportPreview = bitmap
            viewportPreviewLayerId = layerId
            viewportPreviewIsComposite = false
        }
    }

    private val mutableSoftProof = MutableStateFlow<app.croptop.core.model.SoftProofSettings?>(null)
    override val softProof: StateFlow<app.croptop.core.model.SoftProofSettings?> = mutableSoftProof.asStateFlow()

    /** The baked display/proof LUT the viewports apply to their frames (null = untouched). */
    @Volatile private var softProofLut: FloatArray? = null

    override fun setSoftProof(settings: app.croptop.core.model.SoftProofSettings?): CommandResult {
        val icc = synchronized(lock) {
            if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
            if (settings == null) null else workingIccForCurrentProfile()
        }
        var lut: FloatArray? = null
        if (settings != null) {
            try {
                lut = bakeSoftProofLut(icc!!, settings)
            } catch (failure: Exception) {
                return reject(RejectionCode.UNSUPPORTED, failure.message ?: "The soft proof could not be prepared")
            }
        }
        synchronized(lock) {
            if (closed) return reject(RejectionCode.CLOSED, "Document is closed")
            softProofLut = lut
            mutableSoftProof.value = settings
            attachedViewports.toList().forEach { it.setDisplayLut(lut) }
        }
        return CommandResult.Accepted(sequence)
    }

    private fun bakeSoftProofLut(documentIcc: ByteArray, settings: app.croptop.core.model.SoftProofSettings): FloatArray {
        fun intent(i: app.croptop.core.model.ProofIntent) = NativeColorManagementBridge.ConversionIntent.entries[i.ordinal]
        NativeColorManagementBridge.Profile.fromIcc(documentIcc).use { document ->
            NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.SRGB).use { display ->
                val proof = when (settings.target) {
                    app.croptop.core.model.ProofTarget.PRESS_CMYK -> NativeColorManagementBridge.Profile.genericCmyk(settings.inkLimitPercent)
                    app.croptop.core.model.ProofTarget.SRGB -> NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.SRGB)
                    app.croptop.core.model.ProofTarget.ADOBE_RGB -> NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.ADOBE_RGB)
                    app.croptop.core.model.ProofTarget.DISPLAY_P3 -> NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3)
                }
                return proof.use {
                    NativeColorManagementBridge.bakeDisplayLut(
                        document, display, SOFT_PROOF_LUT_SIZE, it, intent(settings.intent), settings.blackPointCompensation,
                        intent(settings.intent), settings.gamutWarning,
                    )
                }
            }
        }
    }

    override fun attachViewport(surface: Surface, viewport: ViewportState): Viewport = synchronized(lock) {
        check(!closed) { "Document is closed" }
        lateinit var attached: SurfaceDocumentViewport
        val previewPresentation = previewPresentation(mutableLayers.value)
        attached = SurfaceDocumentViewport(
            surface,
            mutableInfo.value.size,
            viewportPreview,
            previewPresentation,
            viewport,
            onVulkanReady = engine::updateVulkanCapabilities,
            onViewportChanged = { v, edited -> scheduleRefine(v, if (edited) VIEWPORT_REGION_AFTER_EDIT_MILLIS else VIEWPORT_REGION_IDLE_MILLIS) },
        ) {
            forgetRefine(attached)
            synchronized(lock) { attachedViewports.remove(attached) }
        }
        attachedViewports += attached
        softProofLut?.let { attached.setDisplayLut(it) }
        queueViewportComposite(mutableLayers.value)
        scheduleRefine(attached, VIEWPORT_REGION_AFTER_EDIT_MILLIS)
        attached
    }

    override suspend fun save(target: SaveTarget, options: SaveOptions) {
        require(!options.includeHistory) { "Saving undo history is not supported yet" }
        val state = synchronized(lock) {
            check(!closed) { "Document is closed" }
            captureSaveState().also { registerNativeReadSnapshot() }
        }
        try {
            val bytes = engine.writeSave(target) { output ->
                val written = engine.saveCroptop(
                    state.pixels.map { it.third }.toLongArray(),
                    state.pixels.map { it.second }.toTypedArray(),
                    state.manifestJson,
                    state.workingIcc,
                    output,
                    if (options.compact) 3 else 1,
                    Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
                    state.embedded,
                )
                if (written < 0L) throw IOException("Native .croptop encoder failed")
                output.flush()
                written
            }
            if (bytes <= 0L) throw IOException("Native .croptop encoder produced an empty archive")
            synchronized(lock) {
                if (!closed && sequence == state.sequence) {
                    cleanIndex = cursor
                    guidesModified = false
                    mutableInfo.value = mutableInfo.value.copy(dirty = false)
                    discardAutosaveLocked(destroy = false)
                }
            }
        } finally {
            try {
                state.stores.values.forEach(engine::destroyTileStore)
                state.ownedStores.forEach(engine::destroyTileStore)
            } finally {
                finishNativeReadSnapshot()
            }
        }
    }

    /** [source] drawn with its layer styles onto transparency (keeps the layer mask); 0 when baking fails. */
    private fun bakeStylesForExport(
        source: Long,
        styles: app.croptop.core.model.LayerStyles,
        fillOpacity: Float,
        maskInfo: app.croptop.core.model.LayerMaskInfo?,
        size: DocSize,
        format: Int,
    ): Long {
        val backdrop = engine.createTileStore(size.width, size.height, format)
        if (backdrop == 0L) return 0L
        return try {
            val baked = NativeLayerStylesBridge.flattenModel(source, styles, backdrop, fillOpacity)
            if (maskInfo != null) {
                val masked = runCatching {
                    val mask = NativeLayerMaskBridge.fromLayerStore(source)
                    try {
                        NativeLayerMaskBridge.attach(baked, mask, maskInfo)
                    } finally {
                        NativeLayerMaskBridge.destroy(mask)
                    }
                }
                if (masked.isFailure) {
                    engine.destroyTileStore(baked)
                    return 0L
                }
            }
            baked
        } catch (_: IllegalStateException) {
            0L
        } finally {
            engine.destroyTileStore(backdrop)
        }
    }

    /** Layered-PSD records for [tree] in file order (bottom to top), with owned store copies; adjustments without a PSD equivalent are skipped. */
    private fun psdRecords(
        tree: LayerTree,
        stores: Map<LayerId, Long>,
        size: DocSize,
        format: Int,
    ): List<NativeCodecExtraBridge.PsdRecord> {
        val shapes = renderShapeStores(tree, size)
        val records = mutableListOf<NativeCodecExtraBridge.PsdRecord>()
        try {
            fun byte(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
            fun emit(nodes: List<LayerNode>) {
                for (node in nodes.asReversed()) {
                    when (node.kind) {
                        LayerKind.GROUP -> {
                            records += NativeCodecExtraBridge.PsdRecord(1, 0L, node.name, "norm", 255, 255, true, false)
                            emit(node.children)
                            records += NativeCodecExtraBridge.PsdRecord(
                                2, 0L, node.name, node.blendMode.psdKey, byte(node.opacity), byte(node.fillOpacity), node.visible, false,
                                passThrough = node.blendMode == BlendMode.PASS_THROUGH,
                            )
                        }

                        LayerKind.ADJUSTMENT -> {
                            val (key, data) = node.adjustment?.let(PsdAdjustments::encode) ?: continue
                            // The LUT carrier store only lends its mask to the record.
                            val copy = stores[node.id]?.let(engine::cloneTileStore) ?: 0L
                            records += NativeCodecExtraBridge.PsdRecord(
                                3, copy, node.name, node.blendMode.psdKey, byte(node.opacity), byte(node.fillOpacity), node.visible, node.clipped,
                                extraKey = key, extraData = data,
                            )
                        }

                        LayerKind.PIXEL, LayerKind.TEXT, LayerKind.SHAPE, LayerKind.SMART_OBJECT, LayerKind.FILL -> {
                            val source = shapes[node.id] ?: stores[node.id] ?: continue
                            val styles = node.styles?.takeIf { it.anyEnabled }
                            // Effects travel as an editable `lfx2` block; a pattern overlay (not written) bakes them into the pixels instead.
                            val effects = styles?.let(NativeLayerStylesBridge::psdEffects)
                            val baked = if (styles != null && effects == null) {
                                bakeStylesForExport(source, styles, node.fillOpacity, node.mask, size, format)
                            } else {
                                0L
                            }
                            val copy = if (baked != 0L) baked else engine.cloneTileStore(source)
                            check(copy != 0L) { "Could not snapshot ${node.name} for export" }
                            NativeLayerMaskBridge.setBlendIf(copy, node.blendIf)
                            records += NativeCodecExtraBridge.PsdRecord(
                                0, copy, node.name, node.blendMode.psdKey, byte(node.opacity), if (baked != 0L) 255 else byte(node.fillOpacity),
                                node.visible, node.clipped,
                                extraKey = effects?.let { "lfx2" }, extraData = effects,
                            )
                        }

                        else -> Unit
                    }
                }
            }
            emit(tree.layers)
            return records
        } catch (failure: Throwable) {
            records.forEach { if (it.store != 0L) engine.destroyTileStore(it.store) }
            throw failure
        } finally {
            shapes.values.forEach(engine::destroyTileStore)
        }
    }

    /** COW snapshot of every layer plus the manifest; the caller holds [lock] and destroys [SaveState.stores]. */
    private fun captureSaveState(): SaveState {
        val tree = mutableLayers.value
        val workingIcc = workingIccForCurrentProfile()
        val stores = clonePixelStores(pixelStores, tree)
            ?: throw IOException("Could not snapshot document pixels for saving")
        val ownedStores = mutableListOf<Long>()
        return try {
            val layerNodes = tree.flatten()
            val maskDefaults = mutableMapOf<LayerId, Float>()
            val uuids = layerNodes.associate { layer ->
                layer.id to layerUuids.getOrPut(layer.id, CroptopManifest::newLayerUuid)
            }
            // Archive payloads: pixel layers, smart object caches and fill layers (with their masks). Type re-renders from its text and adjustment
            // layers from their settings, both kept in the manifest.
            val payloadIds = layerNodes.filter { it.kind == LayerKind.PIXEL || it.kind == LayerKind.SMART_OBJECT || it.kind == LayerKind.FILL }
                .mapTo(mutableSetOf()) { it.id }
            val selectionChannels = channelStores.map { (name, store) ->
                val key = CroptopManifest.newLayerUuid()
                val clone = engine.cloneTileStore(store).takeIf { it != 0L }
                    ?: throw IOException("Could not snapshot selection channel '$name'")
                ownedStores += clone
                CroptopSelectionChannel(name, key) to Triple(LayerId(0), key, clone)
            }
            val pixels = layerNodes.filter { it.id in payloadIds }.flatMap { layer ->
                val store = stores[layer.id] ?: return@flatMap emptyList()
                val entries = mutableListOf(Triple(layer.id, uuids.getValue(layer.id), store))
                val maskInfo = layer.mask
                if (maskInfo != null) {
                    val maskKey = CroptopManifest.maskPayloadUuid(uuids.getValue(layer.id))
                    require(maskKey !in uuids.values) { "Layer mask archive key collides with a layer UUID" }
                    val mask = NativeLayerMaskBridge.fromLayerStore(store)
                    try {
                        ownedStores += NativeLayerMaskBridge.pixels(mask)
                        val emptyCoverage = NativeLayerMaskBridge.emptyCoverage(mask)
                        maskDefaults[layer.id] = emptyCoverage
                    } finally {
                        NativeLayerMaskBridge.destroy(mask)
                    }
                    entries += Triple(layer.id, maskKey, ownedStores.last())
                }
                entries
            } + usedPatternIds(tree).mapNotNull { patternId ->
                // Patterns the document uses travel with it (keyed by pattern id) so it opens anywhere.
                val clone = engine.patternStore(patternId).takeIf { it != 0L }?.let(engine::cloneTileStore)?.takeIf { it != 0L }
                    ?: return@mapNotNull null
                ownedStores += clone
                Triple(LayerId(0), patternId, clone)
            } + selectionChannels.map { it.second } + cloneSmartSources(tree).map { (key, clone) ->
                // Smart object sources travel as extra archive payloads under their source key.
                ownedStores += clone
                Triple(LayerId(0), key, clone)
            }
            val manifest = CroptopManifest.fromLayerTree(
                tree = tree,
                canvas = CroptopCanvas(
                    width = mutableInfo.value.size.width,
                    height = mutableInfo.value.size.height,
                    depth = mutableInfo.value.depth,
                    profile = CroptopProfile.of(mutableInfo.value.profile),
                    resolutionPpi = mutableInfo.value.resolutionPpi,
                ),
                uuidOf = { id -> uuids.getValue(id) },
                pixelFormatOf = { id ->
                    if (id in payloadIds && stores.containsKey(id)) {
                        when (pixelFormat) {
                            0 -> CroptopPixelFormat.RGBA8
                            1 -> CroptopPixelFormat.RGBA16
                            2 -> CroptopPixelFormat.RGBA32F
                            else -> error("Unsupported native pixel format $pixelFormat")
                        }
                    } else {
                        null
                    }
                },
                maskDefaultOf = { id -> maskDefaults[id] ?: 1f },
                maskPayloadPresentOf = { id -> id in maskDefaults },
                appVersion = engine.capabilities.engineVersion,
                name = mutableInfo.value.name,
                guides = documentGuidesState.value.guides,
                grid = documentGuidesState.value.grid,
                metadata = mapOf("croptop.background" to background.name),
                selectionChannels = selectionChannels.map { it.first },
            )
            SaveState(
                stores = stores,
                pixels = pixels,
                ownedStores = ownedStores.toList(),
                manifestJson = CroptopManifestCodec.encode(manifest, originalProjectManifest),
                workingIcc = workingIcc,
                sequence = sequence,
                embedded = rawEmbeddedFiles(tree),
            )
        } catch (failure: Throwable) {
            stores.values.forEach(engine::destroyTileStore)
            ownedStores.forEach(engine::destroyTileStore)
            throw failure
        }
    }

    private data class SaveState(
        val stores: Map<LayerId, Long>,
        val pixels: List<Triple<LayerId, String, Long>>,
        val ownedStores: List<Long>,
        val manifestJson: ByteArray,
        val workingIcc: ByteArray?,
        val sequence: Long,
        val embedded: List<NativeEngine.EmbeddedFile> = emptyList(),
    )

    /** `embedded/<fileKey>/original.raw` and `settings.xmp` for every RAW smart object whose file is still held. Holds [lock]. */
    private fun rawEmbeddedFiles(tree: LayerTree): List<NativeEngine.EmbeddedFile> =
        tree.flatten().mapNotNull { it.smartObject?.raw }.distinctBy { it.fileKey }.flatMap { raw ->
            val bytes = rawFiles[raw.fileKey] ?: return@flatMap emptyList()
            listOf(
                NativeEngine.EmbeddedFile(raw.fileKey, "original.raw", bytes),
                NativeEngine.EmbeddedFile(raw.fileKey, "settings.xmp", NativeRawBridge.writeSidecar(raw.settings).toByteArray(Charsets.UTF_8)),
            )
        }

    /** Resolves the profile named by document metadata for archive, recovery, and raster output. */
    private fun workingIccForCurrentProfile(): ByteArray = when (val profile = snapshots[cursor].profile) {
        is app.croptop.core.model.ColorProfileRef.Embedded -> {
            val bytes = snapshots[cursor].workingIcc ?: importedRasterMetadata?.iccProfile
                ?: throw IOException("Document is missing the ICC profile required by its embedded color profile")
            if (bytes.sha256() != profile.hash) throw IOException("Document ICC profile does not match its embedded color profile")
            bytes.copyOf()
        }
        is app.croptop.core.model.ColorProfileRef.BuiltIn -> {
            cachedBuiltInIcc?.takeIf { it.first == profile }?.let { return it.second.copyOf() }
            val space = when (profile.space) {
                WorkingSpace.SRGB -> NativeColorManagementBridge.BuiltInSpace.SRGB
                WorkingSpace.DISPLAY_P3 -> NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3
                WorkingSpace.ADOBE_RGB -> NativeColorManagementBridge.BuiltInSpace.ADOBE_RGB
                WorkingSpace.PROPHOTO_RGB -> NativeColorManagementBridge.BuiltInSpace.PROPHOTO
            }
            try {
                NativeColorManagementBridge.Profile.builtIn(space, profile.linear).use { it.iccBytes() }.also { bytes ->
                    cachedBuiltInIcc = profile to bytes.copyOf()
                }
            } catch (failure: IllegalStateException) {
                throw IOException("Could not create the document working ICC profile", failure)
            }
        }
    }

    override suspend fun export(target: ExportTarget, options: ExportOptions): ExportResult = withContext(Dispatchers.IO) {
        val snapshot = synchronized(lock) {
            check(!closed) { "Document is closed" }
            val tree = mutableLayers.value
            val stores = clonePixelStores(pixelStores, tree)?.toMutableMap()
                ?: throw IOException("Could not snapshot document pixels for export")
            try {
                val metadata = importedRasterMetadata?.let { current ->
                    current.copy(
                        exifBytes = current.exifBytes?.copyOf(),
                        iccProfile = current.iccProfile?.copyOf(),
                        exifTiffForOrientedPixels = current.exifTiffForOrientedPixels?.copyOf(),
                        warnings = current.warnings.toList(),
                    )
                }
                val workingIcc = workingIccForCurrentProfile()
                registerNativeReadSnapshot()
                RasterExportSnapshot(
                    tree = tree,
                    stores = stores,
                    size = mutableInfo.value.size,
                    pixelFormat = pixelFormat,
                    metadata = metadata,
                    workingIcc = workingIcc,
                    backgroundColor = virtualBackgroundColor,
                )
            } catch (failure: Throwable) {
                stores.values.forEach(engine::destroyTileStore)
                throw failure
            }
        }
        var state: RasterExportState? = null
        try {
            val exportState = buildRasterExportState(snapshot, options)
            state = exportState
            val iptc = options.iptc?.takeUnless { options.stripMetadata || it.isEmpty }
            val extraMetadata = iptc?.takeIf { ExportMetadata.supports(options.format) && options.format != app.croptop.core.engine.api.ExportFormat.WEBP }?.let { m ->
                if (options.format == app.croptop.core.engine.api.ExportFormat.JPEG) ExportMetadata.jpegSegments(m) else ExportMetadata.pngChunk(m)
            }
            val webpXmp = iptc?.takeIf { options.format == app.croptop.core.engine.api.ExportFormat.WEBP }?.let(ExportMetadata::webpXmp)
            val metadataWarnings = if (iptc != null && !ExportMetadata.supports(options.format)) {
                listOf("${options.format} export does not carry title, caption or keywords yet; use JPEG, PNG or WebP to keep them")
            } else {
                emptyList()
            }
            val bytesWritten = engine.writeExport(target) { rawStream ->
                val injector = extraMetadata?.let { MetadataInjectingStream(rawStream, options.format, it) }
                val webp = webpXmp?.let { WebpXmpStream(rawStream, it, exportState.size.width, exportState.size.height) }
                val stream: java.io.OutputStream = injector ?: webp ?: rawStream
                val layered = exportState.psdLayers
                val bytes = if (layered != null && layered.isNotEmpty()) {
                    NativeCodecExtraBridge.encodeLayered(layered, exportState.store, stream, exportState.iccProfile)
                } else if (options.format == app.croptop.core.engine.api.ExportFormat.TIFF ||
                    options.format == app.croptop.core.engine.api.ExportFormat.PSD
                ) {
                    NativeCodecExtraBridge.encode(
                        exportState.store,
                        if (options.format == app.croptop.core.engine.api.ExportFormat.TIFF) {
                            NativeCodecExtraBridge.Kind.TIFF
                        } else {
                            NativeCodecExtraBridge.Kind.PSD
                        },
                        stream,
                        exportState.iccProfile,
                    )
                } else if (options.format == app.croptop.core.engine.api.ExportFormat.AVIF) {
                    NativeAvifUltraHdrBridge.encodeAvif(
                        exportState.store,
                        stream,
                        quality = options.quality,
                        bitDepth = if (snapshot.pixelFormat == 0) 8 else 10,
                        iccProfile = exportState.iccProfile,
                        workerThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
                    )
                } else if (options.format == app.croptop.core.engine.api.ExportFormat.ULTRA_HDR) {
                    if (snapshot.pixelFormat != 2) throw IOException("Ultra HDR export needs a 32-bit document (Image ▸ Mode ▸ 32 bits/channel)")
                    NativeAvifUltraHdrBridge.encodeUltraHdr(
                        exportState.store,
                        stream,
                        baseQuality = options.quality,
                        exif = exportState.exifTiff,
                    )
                } else if (options.format == app.croptop.core.engine.api.ExportFormat.PNG) {
                    engine.encodePngToStream(
                        exportState.store,
                        stream,
                        compressionLevel = 6,
                        iccProfile = exportState.iccProfile,
                        exifTiff = exportState.exifTiff,
                        backgroundColor = exportState.backgroundColor,
                    )
                } else if (options.format == app.croptop.core.engine.api.ExportFormat.JPEG) {
                    engine.encodeJpegToStream(
                        exportState.store,
                        stream,
                        options.quality,
                        exportState.iccProfile,
                        exportState.exifTiff,
                        exportState.backgroundColor,
                    )
                } else {
                    engine.encodeWebpToStream(
                        exportState.store,
                        stream,
                        options.quality,
                        exportState.iccProfile,
                        exportState.exifTiff,
                        exportState.backgroundColor,
                    )
                }
                if (bytes < 0) throw IOException("Native ${options.format} encoder failed")
                injector?.finish()
                webp?.finish()
                stream.flush()
                bytes + (injector?.injectedBytes ?: 0L) + (webp?.injectedBytes ?: 0L)
            }
            ExportResult(exportState.size.width, exportState.size.height, bytesWritten, exportState.warnings + metadataWarnings)
        } finally {
            try {
                state?.let { exportState ->
                    if (exportState.ownsStore) engine.destroyTileStore(exportState.store)
                    exportState.psdLayers?.forEach { if (it.store != 0L) engine.destroyTileStore(it.store) }
                }
            } finally {
                releaseNativeReadSnapshot(snapshot.stores)
            }
        }
    }

    private fun buildRasterExportState(snapshot: RasterExportSnapshot, options: ExportOptions): RasterExportState {
        val tree = snapshot.tree
        val single = tree.layers.singleOrNull()?.takeIf { layer ->
            layer.kind == LayerKind.PIXEL && layer.visible && layer.opacity == 1f && layer.fillOpacity == 1f &&
                layer.blendMode == BlendMode.NORMAL && layer.mask == null && !layer.clipped && !layer.hasStyles
        }
        var handle = if (single != null) {
            snapshot.stores[single.id] ?: throw IOException("Pixel layer storage is missing")
        } else {
            val shapeStores = renderShapeStores(tree, snapshot.size)
            var groupStack: NativeRenderLayerStack? = null
            try {
                val renderLayers = collectRenderLayerStack(tree, snapshot.stores + shapeStores)
                    .also { groupStack = it }
                    .layers.filter { it.visible }
                check(renderLayers.isNotEmpty()) { "There are no visible layers to export" }
                engine.flattenLayers(renderLayers).takeIf { it != 0L }
                    ?: throw IOException("Could not flatten document for export")
            } finally {
                groupStack?.close()
                shapeStores.values.forEach(engine::destroyTileStore)
            }
        }
        var ownedExportStore = single == null
        var psdLayers: List<NativeCodecExtraBridge.PsdRecord>? = null
        try {
            var exportSize = snapshot.size
            if (options.scale != 1f) {
                // Export ▸ Scale: Lanczos when shrinking, bicubic when enlarging (Photoshop's Automatic choice).
                val width = (snapshot.size.width * options.scale).roundToInt().coerceIn(1, 65_535)
                val height = (snapshot.size.height * options.scale).roundToInt().coerceIn(1, 65_535)
                val previous = handle
                val resized = engine.resizeTileStore(previous, width, height, if (options.scale < 1f) 3 else 2)
                if (ownedExportStore) engine.destroyTileStore(previous)
                handle = 0L
                check(resized != 0L) { engine.geometryError().ifBlank { "Could not scale the export" } }
                handle = resized
                ownedExportStore = true
                exportSize = DocSize(width, height)
            }
            val metadata = snapshot.metadata
            val jpegBackground = if (snapshot.backgroundColor.a > 0f) snapshot.backgroundColor else RgbaColor.White
            val exportWarnings = buildList {
                if (options.format == app.croptop.core.engine.api.ExportFormat.JPEG) {
                    if (!options.stripMetadata && (metadata?.exifBytes != null || metadata?.iccProfile != null) &&
                        metadata?.iccProfile == null && metadata?.exifTiffForOrientedPixels == null
                    ) add("JPEG export omitted retained metadata that could not be normalized for export")
                    if (!options.stripMetadata && metadata?.exifBytes != null && metadata.exifTiffForOrientedPixels == null) {
                        add("JPEG export omitted retained EXIF metadata that could not be normalized")
                    }
                    if (!options.stripMetadata && (metadata?.exifTiffForOrientedPixels?.size ?: 0) > 65_527) {
                        add("JPEG export omitted EXIF metadata larger than one APP1 marker")
                    }
                    add("JPEG export composited transparency over the ${if (jpegBackground == RgbaColor.Black) "black" else "white"} canvas background")
                }
                if (options.format == app.croptop.core.engine.api.ExportFormat.PNG && !options.stripMetadata &&
                    metadata?.exifBytes != null && metadata.exifTiffForOrientedPixels == null
                ) add("Imported EXIF metadata exceeded the preservation limit and was omitted")
                if (options.format == app.croptop.core.engine.api.ExportFormat.PNG && options.stripMetadata) {
                    add("Embedded ICC profile was stripped; pixel values remain in the source color space")
                } else if (options.format == app.croptop.core.engine.api.ExportFormat.PNG) {
                    add("Working ICC profile was preserved; pixel values were not converted")
                }
                if (options.format == app.croptop.core.engine.api.ExportFormat.WEBP && !options.stripMetadata &&
                    metadata?.exifBytes != null && metadata.exifTiffForOrientedPixels == null
                ) add("Imported EXIF metadata could not be normalized and was omitted")
                if (options.format == app.croptop.core.engine.api.ExportFormat.WEBP) {
                    add(
                        if (options.stripMetadata) {
                            "Working ICC profile was stripped; pixel values remain in the source color space"
                        } else {
                            "Working ICC profile was preserved; pixel values were not converted"
                        },
                    )
                }
            }
            if (options.format == app.croptop.core.engine.api.ExportFormat.PSD && single == null && options.scale == 1f) {
                psdLayers = psdRecords(tree, snapshot.stores, snapshot.size, snapshot.pixelFormat)
            }
            val psdWarnings = if (psdLayers != null) psdRasterizationWarnings(tree) else emptyList()
            return RasterExportState(
                handle,
                exportSize,
                exportWarnings + psdWarnings,
                snapshot.workingIcc.takeUnless { options.stripMetadata },
                metadata?.exifTiffForOrientedPixels.takeUnless {
                    options.stripMetadata || (options.format == app.croptop.core.engine.api.ExportFormat.JPEG && (it?.size ?: 0) > 65_527)
                },
                if (options.format == app.croptop.core.engine.api.ExportFormat.JPEG) jpegBackground else snapshot.backgroundColor,
                ownedExportStore,
                psdLayers,
            )
        } catch (failure: Throwable) {
            if (ownedExportStore && handle != 0L) engine.destroyTileStore(handle)
            psdLayers?.forEach { if (it.store != 0L) engine.destroyTileStore(it.store) }
            throw failure
        }
    }

    /** What the layered PSD writer flattens or drops, so the export compatibility dialog can say exactly what was rasterized. */
    private fun psdRasterizationWarnings(tree: LayerTree): List<String> = buildList {
        for (node in tree.flatten()) {
            when (node.kind) {
                LayerKind.TEXT -> add("Type layer “${node.name}” was written as pixels; it is not editable text in Photoshop")
                LayerKind.SHAPE -> add("Shape layer “${node.name}” was rasterized")
                LayerKind.FILL -> add("Fill layer “${node.name}” was rasterized")
                LayerKind.SMART_OBJECT -> add("Smart object “${node.name}” was written as a pixel layer; its smart filters and transform are flattened")
                LayerKind.ADJUSTMENT -> if (node.adjustment?.let(PsdAdjustments::encode) == null) {
                    add("Adjustment layer “${node.name}” has no Photoshop equivalent and was left out")
                }

                else -> Unit
            }
            if (node.styles?.takeIf { it.anyEnabled }?.let(NativeLayerStylesBridge::psdEffects) == null && node.styles?.anyEnabled == true) {
                add("Layer styles on “${node.name}” include a pattern overlay and were baked into its pixels")
            }
        }
    }

    override fun colorReadout(color: RgbaColor): ColorReadout = synchronized(lock) {
        check(!closed) { "Document is closed" }
        require(listOf(color.r, color.g, color.b).all { it.isFinite() && it in 0f..1f }) {
            "Readout RGB channels must be finite and within 0–1"
        }
        val session = colorReadoutSession ?: run {
            val profileBytes = workingIccForCurrentProfile()
            val created = NativeColorManagementBridge.Profile.fromIcc(profileBytes).use { documentProfile ->
                NativeColorManagementBridge.Profile.genericCmyk().use { cmykProfile ->
                    NativeColorManagementBridge.ReadoutSession(documentProfile, cmykProfile)
                }
            }
            colorReadoutSession = created
            created
        }
        val readout = session.read(floatArrayOf(color.r, color.g, color.b))
        ColorReadout(readout.lab, readout.cmykPercent, readout.outOfGamut)
    }

    override fun applyColorProfile(profile: ColorProfileRef, iccProfile: ByteArray?, convertPixels: Boolean): CommandResult =
        applyColorProfileTransaction(profile, iccProfile?.copyOf(), convertPixels, { true }, { true })

    override fun applyColorProfileJob(profile: ColorProfileRef, iccProfile: ByteArray?, convertPixels: Boolean): EngineJob {
        val iccSnapshot = iccProfile?.copyOf()
        return withDocumentCommand { synchronized(lock) {
            if (closed || closing.get()) return@synchronized EngineJob.completed(reject(RejectionCode.CLOSED, "Document is closed"))
            NativeFillJob(fillJobScope, { activeFillJobs.remove(it) }) { _, isActive, beginCommit ->
                applyColorProfileTransaction(profile, iccSnapshot, convertPixels, isActive, beginCommit)
            }.also(activeFillJobs::add)
        }}
    }

    private fun applyColorProfileTransaction(
        profile: ColorProfileRef,
        iccProfile: ByteArray?,
        convertPixels: Boolean,
        isActive: () -> Boolean,
        beginCommit: () -> Boolean,
    ): CommandResult {
        data class ProfileSnapshot(
            val tree: LayerTree,
            val revision: Long,
            val historyCursor: Int,
            val selection: SelectionInfo,
            val selectionMaskRevision: Long,
            val sourceIcc: ByteArray,
            val targetIcc: ByteArray,
            val prepared: MutableMap<LayerId, Long>,
        )

        var immediate: CommandResult? = null
        var snapshotFailure: CommandResult? = null
        val snapshot = withDocumentCommand { synchronized(lock) {
            when {
                closed || closing.get() -> {
                    snapshotFailure = reject(RejectionCode.CLOSED, "Document is closed")
                    null
                }
                !isActive() -> {
                    snapshotFailure = reject(RejectionCode.CANCELLED, "Color profile operation was cancelled")
                    null
                }
                else -> {
                    finishSelectionTransformBeforeMutation()
                    val currentProfile = snapshots[cursor].profile
                    if (profile == currentProfile) {
                        immediate = CommandResult.Accepted(sequence)
                        null
                    } else {
                        val targetIcc = try {
                            when (profile) {
                                is ColorProfileRef.Embedded -> {
                                    val bytes = iccProfile?.copyOf()
                                    if (bytes == null || bytes.size > MAX_WORKING_ICC_BYTES || bytes.sha256() != profile.hash) {
                                        snapshotFailure = reject(
                                            RejectionCode.INVALID_ARGUMENT,
                                            "Embedded ICC profile is missing, too large or does not match its profile reference",
                                        )
                                        null
                                    } else {
                                        bytes
                                    }
                                }
                                is ColorProfileRef.BuiltIn -> {
                                    if (iccProfile != null) {
                                        snapshotFailure = reject(RejectionCode.INVALID_ARGUMENT, "Built-in profiles do not accept ICC bytes")
                                        null
                                    } else {
                                        builtInIcc(profile)
                                    }
                                }
                            }
                        } catch (failure: Exception) {
                            snapshotFailure = reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Could not load the target ICC profile")
                            null
                        }
                        if (targetIcc == null) {
                            null
                        } else {
                            val tree = snapshots[cursor].tree
                            val unsupported = if (convertPixels) tree.flatten().firstOrNull { it.kind != LayerKind.PIXEL && it.kind != LayerKind.GROUP } else null
                            val sourceIcc = try {
                                if (convertPixels) workingIccForCurrentProfile() else byteArrayOf()
                            } catch (failure: Exception) {
                                snapshotFailure = reject(RejectionCode.UNSUPPORTED, failure.message ?: "Could not read the source ICC profile")
                                null
                            }
                            val formatSupported = !convertPixels || pixelFormat in 0..2
                            when {
                                unsupported != null -> {
                                    snapshotFailure = reject(
                                        RejectionCode.UNSUPPORTED,
                                        "Convert Profile currently supports pixel and group layers; '${unsupported.name}' stores another color type",
                                    )
                                    null
                                }
                                sourceIcc == null -> null
                                !formatSupported -> {
                                    snapshotFailure = reject(RejectionCode.UNSUPPORTED, "This document pixel format cannot be color converted")
                                    null
                                }
                                else -> {
                                    val prepared = clonePixelStores(pixelStores, tree)?.toMutableMap()
                                    if (prepared == null) {
                                        snapshotFailure = reject(RejectionCode.UNSUPPORTED, "Could not preserve pixels before changing the profile")
                                        null
                                    } else {
                                        ProfileSnapshot(
                                            tree = tree,
                                            revision = sequence,
                                            historyCursor = cursor,
                                            selection = mutableSelection.value,
                                            selectionMaskRevision = mutableSelectionMaskRevision.value,
                                            sourceIcc = sourceIcc,
                                            targetIcc = targetIcc,
                                            prepared = prepared,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }}
        if (snapshot == null) return immediate ?: checkNotNull(snapshotFailure)

        var ownsPrepared = true
        fun discardPrepared() {
            if (!ownsPrepared) return
            ownsPrepared = false
            snapshot.prepared.values.forEach(engine::destroyTileStore)
            snapshot.prepared.clear()
        }

        try {
            NativeColorManagementBridge.Profile.fromIcc(snapshot.targetIcc).use { target ->
                if (target.model != NativeColorManagementBridge.Model.RGB) {
                    discardPrepared()
                    return reject(RejectionCode.UNSUPPORTED, "Only RGB working profiles are supported")
                }
            }
        } catch (failure: Exception) {
            discardPrepared()
            return reject(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Invalid target ICC profile")
        }

        try {
            if (convertPixels) {
                NativeColorManagementBridge.Profile.fromIcc(snapshot.sourceIcc).use { source ->
                    if (source.model != NativeColorManagementBridge.Model.RGB) {
                        return reject(RejectionCode.UNSUPPORTED, "Only RGB source profiles can be converted")
                    }
                }
                val format = when (pixelFormat) {
                    0 -> NativeColorManagementBridge.RgbaFormat.RGBA8
                    1 -> NativeColorManagementBridge.RgbaFormat.RGBA16
                    2 -> NativeColorManagementBridge.RgbaFormat.RGBA32F
                    else -> error("Validated pixel format became unsupported")
                }
                for (layer in snapshot.tree.flatten().filter { it.kind == LayerKind.PIXEL }) {
                    if (!isActive()) return reject(RejectionCode.CANCELLED, "Color profile operation was cancelled")
                    val sourceStore = snapshot.prepared[layer.id]
                        ?: error("Pixel layer '${layer.name}' has no native store")
                    val converted = NativeColorManagementBridge.convertTileStore(
                        sourceStore,
                        snapshot.sourceIcc,
                        snapshot.targetIcc,
                        format,
                    )
                    engine.destroyTileStore(sourceStore)
                    snapshot.prepared[layer.id] = converted
                }
            }
            if (!isActive() || !beginCommit()) return reject(RejectionCode.CANCELLED, "Color profile operation was cancelled")

            return withDocumentCommand { synchronized(lock) {
                if (closed || closing.get() || sequence != snapshot.revision || cursor != snapshot.historyCursor ||
                    mutableLayers.value != snapshot.tree || mutableSelection.value != snapshot.selection ||
                    mutableSelectionMaskRevision.value != snapshot.selectionMaskRevision
                ) {
                    return@synchronized reject(RejectionCode.CANCELLED, "Document changed while the color profile operation was running")
                }
                ownsPrepared = false // commitPrepared takes ownership on both accepted and rejected paths.
                val label = if (convertPixels) "Convert Color Profile" else "Assign Color Profile"
                commitPrepared(
                    label,
                    snapshot.tree,
                    snapshot.prepared,
                    profileAfter = profile,
                    workingIccAfter = snapshot.targetIcc.takeIf { profile is ColorProfileRef.Embedded },
                ).also { result ->
                    if (result is CommandResult.Accepted) {
                        colorReadoutSession?.close()
                        colorReadoutSession = null
                    }
                }
            }}
        } catch (failure: Exception) {
            return reject(RejectionCode.UNSUPPORTED, failure.message ?: "Color profile operation failed")
        } finally {
            discardPrepared()
        }
    }

    private fun builtInIcc(profile: ColorProfileRef.BuiltIn): ByteArray {
        cachedBuiltInIcc?.takeIf { it.first == profile }?.let { return it.second.copyOf() }
        val space = when (profile.space) {
            WorkingSpace.SRGB -> NativeColorManagementBridge.BuiltInSpace.SRGB
            WorkingSpace.DISPLAY_P3 -> NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3
            WorkingSpace.ADOBE_RGB -> NativeColorManagementBridge.BuiltInSpace.ADOBE_RGB
            WorkingSpace.PROPHOTO_RGB -> NativeColorManagementBridge.BuiltInSpace.PROPHOTO
        }
        return NativeColorManagementBridge.Profile.builtIn(space, profile.linear).use { it.iccBytes() }.also { bytes ->
            cachedBuiltInIcc = profile to bytes.copyOf()
        }
    }

    override fun sample(point: DocPoint, mode: SampleMode): ColorSample = sample(point, mode, 1)

    override fun trySample(point: DocPoint, mode: SampleMode, size: Int): ColorSample? {
        // Admission and COW capture are atomic with stroke commit admission. Rendering consumes
        // the immutable snapshot after releasing both locks.
        val snapshot = synchronized(sampleAdmissionLock) {
            if (pendingBrushCommits.get() > 0) return null
            require(size in SUPPORTED_SAMPLE_SIZES) { "Sample size must be one of $SUPPORTED_SAMPLE_SIZES" }
            require(point.x.isFinite() && point.y.isFinite()) { "Sample point must be finite" }
            capturePixelReadSnapshot(point, mode)
        }
        return snapshot?.let { renderSample(it, point, mode, size) }
    }

    override fun sample(point: DocPoint, mode: SampleMode, size: Int): ColorSample {
        require(size in SUPPORTED_SAMPLE_SIZES) { "Sample size must be one of $SUPPORTED_SAMPLE_SIZES" }
        require(point.x.isFinite() && point.y.isFinite()) { "Sample point must be finite" }
        val snapshot = capturePixelReadSnapshot(point, mode) ?: error("Document is closed")
        return renderSample(snapshot, point, mode, size)
    }

    private fun capturePixelReadSnapshot(point: DocPoint, mode: SampleMode): PixelReadSnapshot? = synchronized(lock) {
        if (closed) return@synchronized null
        val size = mutableInfo.value.size
        val tree = mutableLayers.value
        val x = kotlin.math.floor(point.x).toInt()
        val y = kotlin.math.floor(point.y).toInt()
        if (x !in 0 until size.width || y !in 0 until size.height) {
            val outside = when (background) {
                BackgroundContents.WHITE -> RgbaColor.White
                BackgroundContents.BLACK -> RgbaColor.Black
                else -> RgbaColor.Transparent
            }
            return@synchronized PixelReadSnapshot(tree, mutableMapOf(), size, ColorSample(outside, point, mode))
        }
        val stores = clonePixelStores(pixelStores, tree)?.toMutableMap() ?: return@synchronized null
        registerNativeReadSnapshot()
        PixelReadSnapshot(tree, stores, size, null)
    }

    private fun registerNativeReadSnapshot() {
        nativeReadSnapshots.beginRead()
    }

    private fun finishNativeReadSnapshot() {
        nativeReadSnapshots.endRead()
    }

    private fun releaseNativeReadSnapshot(stores: Map<LayerId, Long>) {
        releaseNativeReadSnapshot(stores.values)
    }

    private fun releaseNativeReadSnapshot(stores: Collection<Long>) {
        try {
            stores.forEach(engine::destroyTileStore)
        } finally {
            finishNativeReadSnapshot()
        }
    }

    private fun awaitNativeReadSnapshots() = nativeReadSnapshots.awaitReaders()

    private fun renderSample(snapshot: PixelReadSnapshot, point: DocPoint, mode: SampleMode, size: Int): ColorSample {
        snapshot.outsideSample?.let { return it }
        val stores = snapshot.stores ?: return synchronized(lock) {
            check(!closed) { "Document is closed" }
            sampleFromStores(mutableLayers.value, pixelStores, mutableInfo.value.size, point, mode, size)
        }
        return try {
            sampleFromStores(snapshot.tree, stores, snapshot.size, point, mode, size)
        } finally {
            releaseNativeReadSnapshot(stores)
        }
    }

    private fun sampleFromStores(
        tree: LayerTree,
        stores: Map<LayerId, Long>,
        documentSize: DocSize,
        point: DocPoint,
        mode: SampleMode,
        sampleSize: Int,
    ): ColorSample {
        val x = kotlin.math.floor(point.x).toInt()
        val y = kotlin.math.floor(point.y).toInt()
        if (x !in 0 until documentSize.width || y !in 0 until documentSize.height) {
            val outside = when (background) {
                BackgroundContents.WHITE -> RgbaColor.White
                BackgroundContents.BLACK -> RgbaColor.Black
                else -> RgbaColor.Transparent
            }
            return ColorSample(outside, point, mode)
        }
        val shapeStores = renderShapeStores(tree)
        var groupStack: NativeRenderLayerStack? = null
        return try {
            val renderLayers = if (mode == SampleMode.ACTIVE_LAYER) {
                collectRenderLayers(tree, stores + shapeStores)
            } else {
                collectRenderLayerStack(tree, stores + shapeStores).also { groupStack = it }.layers
            }
            val samplingLayers = if (mode == SampleMode.ACTIVE_LAYER) {
                val primary = tree.primary?.value
                renderLayers.filter { it.id == primary }
                    .map { it.copy(blendMode = 0, opacity = 255, fillOpacity = 255, visible = true) }
            } else {
                renderLayers
            }
            val primary = tree.primary?.value
            val color = engine.samplePixels(
                samplingLayers,
                mode,
                primary,
                x,
                y,
                sampleSize,
                if (mode == SampleMode.ACTIVE_LAYER) RgbaColor.Transparent else virtualBackgroundColor,
            ) ?: RgbaColor.Transparent
            ColorSample(color, point, mode)
        } finally {
            groupStack?.close()
            shapeStores.values.forEach(engine::destroyTileStore)
        }
    }

    override fun sampleBelow(layerId: LayerId, point: DocPoint, size: Int): ColorSample {
        require(size in SUPPORTED_SAMPLE_SIZES) { "Sample size must be one of $SUPPORTED_SAMPLE_SIZES" }
        require(point.x.isFinite() && point.y.isFinite()) { "Sample point must be finite" }
        val snapshot = capturePixelReadSnapshot(point, SampleMode.MERGED) ?: error("Document is closed")
        return renderSampleBelow(snapshot, layerId, point, size)
    }

    private fun renderSampleBelow(snapshot: PixelReadSnapshot, layerId: LayerId, point: DocPoint, size: Int): ColorSample {
        snapshot.outsideSample?.let { return it.copy(mode = SampleMode.MERGED) }
        val stores = snapshot.stores ?: return synchronized(lock) {
            check(!closed) { "Document is closed" }
            sampleBelowFromStores(mutableLayers.value, pixelStores, mutableInfo.value.size, point, layerId, size)
        }
        return try {
            sampleBelowFromStores(snapshot.tree, stores, snapshot.size, point, layerId, size)
        } finally {
            releaseNativeReadSnapshot(stores)
        }
    }

    private fun sampleBelowFromStores(
        tree: LayerTree,
        stores: Map<LayerId, Long>,
        documentSize: DocSize,
        point: DocPoint,
        layerId: LayerId,
        sampleSize: Int,
    ): ColorSample {
        val x = kotlin.math.floor(point.x).toInt()
        val y = kotlin.math.floor(point.y).toInt()
        if (x !in 0 until documentSize.width || y !in 0 until documentSize.height) {
            val outside = when (background) {
                BackgroundContents.WHITE -> RgbaColor.White
                BackgroundContents.BLACK -> RgbaColor.Black
                else -> RgbaColor.Transparent
            }
            return ColorSample(outside, point, SampleMode.MERGED)
        }
        val shapeStores = renderShapeStores(tree)
        var belowStack: NativeRenderLayerStack? = null
        return try {
            val layersBelow = collectRenderLayersBelow(tree, stores + shapeStores, layerId)
                .also { belowStack = it }
                .layers
            val background = if (belowStack?.usesTransparentBackdrop == true) RgbaColor.Transparent else virtualBackgroundColor
            val color = engine.samplePixels(layersBelow, SampleMode.MERGED, null, x, y, sampleSize, background)
                ?: virtualBackgroundColor
            ColorSample(color, point, SampleMode.MERGED)
        } finally {
            belowStack?.close()
            shapeStores.values.forEach(engine::destroyTileStore)
        }
    }

    override fun trySampleBelow(layerId: LayerId, point: DocPoint, size: Int): ColorSample? {
        val snapshot = synchronized(sampleAdmissionLock) {
            if (pendingBrushCommits.get() > 0) return null
            require(size in SUPPORTED_SAMPLE_SIZES) { "Sample size must be one of $SUPPORTED_SAMPLE_SIZES" }
            require(point.x.isFinite() && point.y.isFinite()) { "Sample point must be finite" }
            capturePixelReadSnapshot(point, SampleMode.MERGED)
        }
        return snapshot?.let { renderSampleBelow(it, layerId, point, size) }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun histogram(scope: HistogramScope): Flow<Histogram> =
        combine(mutableInfo, mutableLayers, mutableContentRevision, mutableSelection, mutableSelectionMaskRevision) { info, tree, _, selection, selectionRevision ->
            synchronized(lock) {
                HistogramRequest(
                    info.size,
                    if (scope == HistogramScope.ACTIVE_LAYER) null else tree.copy(active = emptySet(), primary = null),
                    if (scope == HistogramScope.ACTIVE_LAYER) {
                        tree.primary?.let { id -> pixelStores[id]?.let { mapOf(id to it) } }.orEmpty()
                    } else {
                        pixelStores.toMap()
                    },
                    if (scope == HistogramScope.ACTIVE_LAYER) tree.primary else null,
                    if (scope == HistogramScope.SELECTION) selection else SelectionInfo.None,
                    if (scope == HistogramScope.SELECTION) selectionRevision else 0L,
                )
            }
        }
            .distinctUntilChanged()
            .debounce(HISTOGRAM_DEBOUNCE_MILLIS)
            .transformLatest { request ->
                val cached = synchronized(lock) {
                    if (closed || currentHistogramRequest(scope) != request) null
                    else histogramCache[scope]?.takeIf { it.request == request }?.value
                }
                if (cached != null) {
                    emit(cached)
                    return@transformLatest
                }
                val snapshot = synchronized(lock) {
                    if (closed || currentHistogramRequest(scope) != request) return@synchronized null
                    val tree = mutableLayers.value
                    val stores = if (scope == HistogramScope.ACTIVE_LAYER) {
                        val id = tree.primary ?: return@synchronized null
                        val source = pixelStores[id] ?: return@synchronized null
                        val clone = engine.cloneTileStore(source).takeIf { it != 0L } ?: return@synchronized null
                        mapOf(id to clone)
                    } else {
                        clonePixelStores(pixelStores, tree) ?: return@synchronized null
                    }
                    val mask = if (scope == HistogramScope.SELECTION && mutableSelection.value is SelectionInfo.Active) {
                        engine.cloneTileStore(selectionMask).takeIf { it != 0L } ?: run {
                            stores.values.forEach(engine::destroyTileStore)
                            return@synchronized null
                        }
                    } else {
                        null
                    }
                    registerNativeReadSnapshot()
                    HistogramSourceSnapshot(tree, stores, mask ?: 0L, mutableInfo.value.size)
                } ?: run {
                    // A scope can legitimately have no pixel source (for example, an active
                    // group or an empty/hidden document). Publish that state so a long-lived
                    // collector cannot keep showing the histogram for the previous scope.
                    val empty = synchronized(lock) {
                        if (closed || currentHistogramRequest(scope) != request) {
                            null
                        } else {
                            histogramCache[scope] = HistogramCacheEntry(request, emptyHistogram())
                            histogramCache.getValue(scope).value
                        }
                    }
                    if (empty != null) emit(empty)
                    return@transformLatest
                }
                var derivedStore = 0L
                try {
                    val histogramStore = if (scope == HistogramScope.ACTIVE_LAYER) {
                        snapshot.tree.primary?.let(snapshot.stores::get) ?: 0L
                    } else {
                        try {
                            withContext(Dispatchers.Default) {
                                flattenVisible(snapshot.tree, stores = snapshot.stores)
                            }.also { derivedStore = it }
                        } catch (_: Exception) {
                            currentCoroutineContext().ensureActive()
                            0L
                        }
                    }
                    if (histogramStore == 0L) {
                        val empty = synchronized(lock) {
                            if (closed || currentHistogramRequest(scope) != request) null
                            else {
                                histogramCache[scope] = HistogramCacheEntry(request, emptyHistogram())
                                histogramCache.getValue(scope).value
                            }
                        }
                        if (empty != null) emit(empty)
                        return@transformLatest
                    }
                    val divisorMip = histogramMipLevel(snapshot.size.width, snapshot.size.height)
                    val data = withContext(Dispatchers.Default) {
                        NativeImageOpsBridge.histogram(histogramStore, snapshot.selectionMask, divisorMip)
                    }
                    val histogram = Histogram.fromChannels(data.red.toList(), data.green.toList(), data.blue.toList(), data.luminosity.toList())
                    val isCurrent = synchronized(lock) {
                        if (!closed && currentHistogramRequest(scope) == request) {
                            histogramCache[scope] = HistogramCacheEntry(request, histogram)
                            true
                        } else {
                            false
                        }
                    }
                    if (isCurrent) emit(histogram)
                } finally {
                    if (derivedStore != 0L) engine.destroyTileStore(derivedStore)
                    releaseNativeReadSnapshot(snapshot.stores.values + listOfNotNull(snapshot.selectionMask.takeIf { it != 0L }))
                }
            }
            .flowOn(Dispatchers.Default)

    private fun currentHistogramRequest(scope: HistogramScope) = synchronized(lock) {
        HistogramRequest(
            size = mutableInfo.value.size,
            tree = if (scope == HistogramScope.ACTIVE_LAYER) null else mutableLayers.value.copy(active = emptySet(), primary = null),
            pixelStores = if (scope == HistogramScope.ACTIVE_LAYER) {
                mutableLayers.value.primary?.let { id -> pixelStores[id]?.let { mapOf(id to it) } }.orEmpty()
            } else {
                pixelStores.toMap()
            },
            activeLayer = if (scope == HistogramScope.ACTIVE_LAYER) mutableLayers.value.primary else null,
            selection = if (scope == HistogramScope.SELECTION) mutableSelection.value else SelectionInfo.None,
            selectionMaskRevision = if (scope == HistogramScope.SELECTION) mutableSelectionMaskRevision.value else 0L,
        )
    }

    private fun emptyHistogram() = Histogram.fromChannels(
        red = List(256) { 0.0 },
        green = List(256) { 0.0 },
        blue = List(256) { 0.0 },
        luminosity = List(256) { 0.0 },
    )

    override suspend fun preview(px: Int): DocumentPreview? {
        require(px in 1..DocumentPreview.MAX_DIMENSION) {
            "Preview size must be between 1 and ${DocumentPreview.MAX_DIMENSION} px"
        }
        val snapshot = synchronized(lock) {
            if (closed) return@synchronized null
            val tree = mutableLayers.value
            val stores = clonePixelStores(pixelStores, tree) ?: return@synchronized null
            registerNativeReadSnapshot()
            Triple(tree, stores, mutableInfo.value.size)
        } ?: return null
        val (tree, stores, size) = snapshot
        return try {
            withContext(Dispatchers.Default) {
            val shapeStores = renderShapeStores(tree)
            var groupStack: NativeRenderLayerStack? = null
            try {
                val renderLayers = collectRenderLayerStack(tree, stores + shapeStores).also { groupStack = it }.layers
                val bitmap = engine.renderCompositePreview(
                    size.width,
                    size.height,
                    renderLayers,
                    px,
                    virtualBackgroundColor,
                    renderSession = compositeRenderSession,
                )
                    ?: return@withContext null
                try {
                    val argb = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val rgba = ByteArray(argb.size * 4)
                    argb.forEachIndexed { index, color ->
                        val offset = index * 4
                        rgba[offset] = (color ushr 16).toByte()
                        rgba[offset + 1] = (color ushr 8).toByte()
                        rgba[offset + 2] = color.toByte()
                        rgba[offset + 3] = (color ushr 24).toByte()
                    }
                    DocumentPreview(bitmap.width, bitmap.height, rgba)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                groupStack?.close()
                shapeStores.values.forEach(engine::destroyTileStore)
            }
            }
        } finally {
            releaseNativeReadSnapshot(stores)
        }
    }

    override suspend fun renderRegion(region: app.croptop.core.model.DocRect): DocumentRegionPreview? {
        require(!region.isEmpty && region.width <= DocumentRegionPreview.MAX_DIMENSION && region.height <= DocumentRegionPreview.MAX_DIMENSION) {
            "A region is 1–${DocumentRegionPreview.MAX_DIMENSION} px per side"
        }
        val bitmap = compositeRegionBitmap(region) ?: return null
        return try {
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val rgba = ByteArray(argb.size * 4)
            argb.forEachIndexed { index, color ->
                val offset = index * 4
                rgba[offset] = (color ushr 16).toByte()
                rgba[offset + 1] = (color ushr 8).toByte()
                rgba[offset + 2] = color.toByte()
                rgba[offset + 3] = (color ushr 24).toByte()
            }
            DocumentRegionPreview(region, rgba)
        } finally {
            bitmap.recycle()
        }
    }

    /** The visible layers composited over [region] at the document's own resolution (any size the caller can afford); the caller owns the bitmap. */
    private suspend fun compositeRegionBitmap(region: app.croptop.core.model.DocRect): Bitmap? {
        val snapshot = synchronized(lock) {
            if (closed || region.isEmpty || region.left < 0 || region.top < 0 ||
                region.right > mutableInfo.value.size.width || region.bottom > mutableInfo.value.size.height
            ) return@synchronized null
            val tree = mutableLayers.value
            val stores = clonePixelStores(pixelStores, tree) ?: return@synchronized null
            registerNativeReadSnapshot()
            Triple(tree, stores, mutableInfo.value.size)
        } ?: return null
        val (tree, stores, size) = snapshot
        return try {
            withContext(Dispatchers.Default) {
                var shapeStores = emptyMap<LayerId, Long>()
                var groupStack: NativeRenderLayerStack? = null
                try {
                    shapeStores = renderShapeStores(tree)
                    val layers = collectRenderLayerStack(tree, stores + shapeStores).also { groupStack = it }.layers
                    engine.renderCompositeRegion(size.width, size.height, layers, region, virtualBackgroundColor)
                } finally {
                    groupStack?.close()
                    shapeStores.values.forEach(engine::destroyTileStore)
                }
            }
        } finally {
            releaseNativeReadSnapshot(stores)
        }
    }

    // ---- Viewport refinement: re-render what is on screen at the document's own resolution once the view rests ----

    private val refineJobs = java.util.IdentityHashMap<SurfaceDocumentViewport, kotlinx.coroutines.Job>()

    @Volatile private var refineGeneration = 0L

    /** Asks for the viewport's visible region to be re-rendered sharply after [VIEWPORT_REGION_IDLE_MILLIS] without further changes. */
    internal fun scheduleRefine(viewport: SurfaceDocumentViewport, delayMillis: Long = VIEWPORT_REGION_IDLE_MILLIS) {
        synchronized(lock) {
            if (closed) return
            refineJobs.remove(viewport)?.cancel()
            refineJobs[viewport] = previewRenderScope.launch {
                delay(delayMillis)
                refineViewport(viewport)
            }
        }
    }

    internal fun forgetRefine(viewport: SurfaceDocumentViewport) {
        synchronized(lock) { refineJobs.remove(viewport)?.cancel() }
    }

    private suspend fun refineViewport(viewport: SurfaceDocumentViewport) {
        val view = viewport.currentState()
        val (size, previewWidth, generation, plain) = synchronized(lock) {
            if (closed) return
            // Overlays that live inside the composite (quick mask, mask view) and hidden previews are not part of a plain region render.
            val plain = viewportMaskOverlaySource() == null && previewPresentation(mutableLayers.value).let { it.visible && it.opacity >= 1f }
            RefineSnapshot(mutableInfo.value.size, viewportPreview?.width ?: 0, refineGeneration, plain)
        }
        val region = if (plain && previewWidth > 0) viewportRefineRegion(view, size.width, size.height, previewWidth.toFloat() / size.width) else null
        if (region == null) {
            viewport.clearRefined()
            return
        }
        val bitmap = try {
            compositeRegionBitmap(region)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            android.util.Log.w("CropTopRefine", "region render failed", failure)
            null
        }
        if (bitmap == null) return
        val accepted = synchronized(lock) { !closed && generation == refineGeneration && !viewport.isDetached() }
        if (accepted) viewport.setRefined(region, bitmap)
    }

    private data class RefineSnapshot(val size: DocSize, val previewWidth: Int, val generation: Long, val plain: Boolean)

    override fun selectionCoverage(region: app.croptop.core.model.DocRect): ByteArray? = synchronized(lock) {
        if (closed || region.isEmpty || mutableSelection.value == SelectionInfo.None || selectionMask == 0L) return@synchronized null
        if (region.width.toLong() * region.height > (1L shl 24)) return@synchronized null
        NativePixelBridge.readCoverage(selectionMask, region.left, region.top, region.width, region.height)
    }

    override fun thumbnails(layerIds: List<LayerId>, px: Int): Flow<LayerThumb> {
        require(px in 1..MAX_LAYER_THUMBNAIL_DIMENSION) {
            "Thumbnail size must be between 1 and $MAX_LAYER_THUMBNAIL_DIMENSION px"
        }
        val requestedIds = layerIds.distinct()
        if (requestedIds.isEmpty()) return flowOf()
        return flow {
            // History changes include pixel edits that leave the immutable layer-tree metadata equal.
            combine(mutableLayers, mutableHistory) { tree, _ -> tree }
                .conflate()
                .collect { tree ->
                    val snapshot = synchronized(lock) {
                        if (closed) return@collect
                        val stores = clonePixelStores(pixelStores, tree) ?: return@collect
                        registerNativeReadSnapshot()
                        Triple(stores, mutableInfo.value.size.width, mutableInfo.value.size.height)
                    }
                    val (stores, width, height) = snapshot
                    try {
                        for (layerId in requestedIds) {
                            val node = tree.find(layerId) ?: continue
                            // Layer thumbnails show the layer's own contents, independent of its
                            // eye, opacity, fill and blend mode. Child visibility remains relevant
                            // when a group thumbnail is rendered.
                            val thumbnailRoot = when (node.kind) {
                                LayerKind.PIXEL -> node.copy(
                                    visible = true,
                                    opacity = 1f,
                                    fillOpacity = 1f,
                                    blendMode = BlendMode.NORMAL,
                                )

                                LayerKind.GROUP -> node.copy(
                                    visible = true,
                                    opacity = 1f,
                                    fillOpacity = 1f,
                                    blendMode = BlendMode.PASS_THROUGH,
                                )

                                else -> node
                            }
                            val layerTree = LayerTree(layers = listOf(thumbnailRoot))
                            val renderLayers = collectRenderLayers(layerTree, stores)
                            if (renderLayers.isEmpty()) continue
                            val bitmap = engine.renderCompositePreview(width, height, renderLayers, px) ?: continue
                            try {
                                val argb = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                val rgba = ByteArray(argb.size * 4)
                                argb.forEachIndexed { index, color ->
                                    val offset = index * 4
                                    rgba[offset] = (color ushr 16).toByte()
                                    rgba[offset + 1] = (color ushr 8).toByte()
                                    rgba[offset + 2] = color.toByte()
                                    rgba[offset + 3] = (color ushr 24).toByte()
                                }
                                emit(LayerThumb(layerId, bitmap.width, bitmap.height, rgba))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    } finally {
                        releaseNativeReadSnapshot(stores)
                    }
                }
        }.flowOn(Dispatchers.Default)
    }

    override fun maskThumbnails(layerIds: List<LayerId>, px: Int): Flow<LayerThumb> {
        require(px in 1..MAX_LAYER_THUMBNAIL_DIMENSION) {
            "Thumbnail size must be between 1 and $MAX_LAYER_THUMBNAIL_DIMENSION px"
        }
        val requestedIds = layerIds.distinct()
        if (requestedIds.isEmpty()) return flowOf()
        return flow {
            val sources = synchronized(lock) {
                if (closed) return@synchronized emptyList()
                requestedIds.mapNotNull { id ->
                    val node = mutableLayers.value.find(id) ?: return@mapNotNull null
                    if (node.kind != LayerKind.PIXEL || node.mask == null) return@mapNotNull null
                    val source = pixelStores[id] ?: return@mapNotNull null
                    val snapshot = runCatching { NativeLayerMaskBridge.fromLayerStore(source) }.getOrNull()
                        ?.takeIf { it != 0L } ?: return@mapNotNull null
                    id to snapshot
                }.also { if (it.isNotEmpty()) registerNativeReadSnapshot() }
            }
            if (sources.isEmpty()) return@flow
            val pending = sources.toMutableList()
            try {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val (id, mask) = iterator.next()
                    try {
                        val thumb = NativeLayerMaskBridge.thumbnail(mask, px)
                        val rgba = ByteArray(thumb.width * thumb.height * 4)
                        thumb.argb.forEachIndexed { index, argb ->
                            val offset = index * 4
                            rgba[offset] = (argb shr 16).toByte()
                            rgba[offset + 1] = (argb shr 8).toByte()
                            rgba[offset + 2] = argb.toByte()
                            rgba[offset + 3] = 0xff.toByte()
                        }
                        emit(LayerThumb(id, thumb.width, thumb.height, rgba))
                    } finally {
                        NativeLayerMaskBridge.destroy(mask)
                        iterator.remove()
                    }
                }
            } finally {
                try {
                    pending.forEach { (_, mask) -> NativeLayerMaskBridge.destroy(mask) }
                } finally {
                    finishNativeReadSnapshot()
                }
            }
        }.flowOn(Dispatchers.Default)
    }

    override fun close() = synchronized(closeLock) close@{
        closing.set(true)
        val jobsToFinish = activeFillJobs.toList()
        jobsToFinish.forEach(NativeFillJob::cancel)
        var viewportsToDetach: List<SurfaceDocumentViewport> = emptyList()
        val shouldClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                activeAdjustmentPreview?.cancel()
                activeLiquify?.cancel()
                activeTransform?.let { transform ->
                    finishTransform(transform)
                }
                activeSelectionModifySession?.cancel()
                activeBrushSessions.toList().forEach(StrokeSession::cancel)
                activeBrushSessions.clear()
                histogramCache.clear()
                viewportsToDetach = attachedViewports.toList()
                attachedViewports.clear()
                previewRenderCancellationId.takeIf { it != 0L }?.let(engine::cancelRender)
                previewRenderJob?.cancel()
                previewRenderScope.cancel()
                fillJobScope.cancel()
                viewportPreview?.recycle()
                viewportPreview = null
                viewportPreviewLayerId = null
                true
            }
        }
        if (!shouldClose) return@close
        viewportsToDetach.forEach(SurfaceDocumentViewport::detach)

        // Join the per-document brush worker outside the document lock. A running terminal
        // commit may still need that lock before it can observe `closed` and reject safely.
        strokeWorkQueue.close()
        // Native fill kernels use the engine memory budget even after coroutine cancellation.
        // Let them release private snapshots before destroying document stores or the budget.
        jobsToFinish.forEach(NativeFillJob::awaitFinished)
        awaitNativeReadSnapshots()
        engine.destroyCompositeRenderSession(compositeRenderSession)

        synchronized(lock) {
            discardAutosaveLocked(destroy = true)
            colorReadoutSession?.close()
            colorReadoutSession = null
            pixelStores.values.forEach(engine::destroyTileStore)
            pixelStores.clear()
            smartSources.values.forEach(engine::destroyTileStore)
            smartSources.clear()
            filterMasks.values.forEach(engine::destroyTileStore)
            filterMasks.clear()
            rawFiles.clear()
            if (selectionMask != 0L) engine.destroyTileStore(selectionMask)
            selectionMask = 0L
            if (lastSelectionMask != 0L) engine.destroyTileStore(lastSelectionMask)
            lastSelectionMask = 0L
            channelStores.values.forEach(engine::destroyTileStore)
            channelStores.clear()
            lastSelectionInfo = null
            snapshots.forEach(::destroySnapshot)
            snapshots.clear()
            namedSnapshots.forEach(::destroyNamedSnapshot)
            namedSnapshots.clear()
            viewportOffsets.clear()
            onClose(this)
        }
        commandQueue.close()
    }

    private fun commit(label: String, tree: LayerTree): CommandResult {
        finishSelectionTransformBeforeMutation()
        val savedPixels = clonePixelStores(pixelStores, tree)
            ?: run {
                releaseUnreferencedStores()
                return reject(RejectionCode.UNSUPPORTED, "Could not preserve document pixels for undo")
            }
        val savedSelection = cloneSelectionMask(selectionMask)
            ?: run {
                savedPixels.values.forEach(engine::destroyTileStore)
                return reject(RejectionCode.UNSUPPORTED, "Could not preserve selection for undo")
            }
        val savedLastSelection = cloneOptionalSelection(lastSelectionMask)
            ?: run {
                savedPixels.values.forEach(engine::destroyTileStore)
                engine.destroyTileStore(savedSelection)
                return reject(RejectionCode.UNSUPPORTED, "Could not preserve reselect state for undo")
            }
        val previewOffset = viewportOffsets[cursor]
        while (snapshots.lastIndex > cursor) {
            destroySnapshot(snapshots.removeAt(snapshots.lastIndex))
            viewportOffsets.removeAt(viewportOffsets.lastIndex)
        }
        snapshots += Snapshot(
            label, tree, savedPixels, savedSelection, mutableSelection.value,
            savedLastSelection, lastSelectionInfo, mutableInfo.value.size, mutableInfo.value.resolutionPpi,
            snapshots[cursor].profile, snapshots[cursor].workingIcc?.copyOf(),
        )
        viewportOffsets += previewOffset
        cursor = snapshots.lastIndex
        releaseUnreferencedStores()
        publish()
        return CommandResult.Accepted(++sequence)
    }

    override fun resizeCanvas(size: DocSize, anchor: Anchor, fill: CanvasFill): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (size.width > MAX_NATIVE_GEOMETRY_DIMENSION || size.height > MAX_NATIVE_GEOMETRY_DIMENSION) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Native canvas dimensions are limited to $MAX_NATIVE_GEOMETRY_DIMENSION px per side")
        }
        val old = mutableInfo.value.size
        val canvas = CanvasResize(old, size, anchor)
        applyGeometry(
            "Canvas Size",
            GeometryChange(GEOMETRY_CANVAS_SIZE, old, size, canvas.offsetX, canvas.offsetY, fill = (fill as? CanvasFill.Color)?.color),
        )
    }}

    override fun resizeImage(size: DocSize, resample: Resample, resolutionPpi: Float?): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (size.width > MAX_NATIVE_GEOMETRY_DIMENSION || size.height > MAX_NATIVE_GEOMETRY_DIMENSION) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Native image dimensions are limited to $MAX_NATIVE_GEOMETRY_DIMENSION px per side")
        }
        if (resolutionPpi != null && (!resolutionPpi.isFinite() || resolutionPpi <= 0f)) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Resolution must be positive")
        }
        val source = mutableInfo.value.size
        val filter = when (resample.resolve(source, size)) {
            Resample.NEAREST -> RESAMPLE_NEAREST
            Resample.BILINEAR -> RESAMPLE_BILINEAR
            Resample.BICUBIC -> RESAMPLE_BICUBIC
            Resample.LANCZOS -> RESAMPLE_LANCZOS
            Resample.AUTOMATIC -> error("Resample.resolve must produce a concrete filter")
        }
        applyGeometry(
            "Image Size",
            GeometryChange(
                GEOMETRY_RESIZE, source, size,
                quarterTurns = filter,
                targetResolutionPpi = resolutionPpi ?: mutableInfo.value.resolutionPpi,
            ),
        )
    }}

    override fun flipCanvas(horizontal: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        val size = mutableInfo.value.size
        applyGeometry(
            if (horizontal) "Flip Canvas Horizontal" else "Flip Canvas Vertical",
            GeometryChange(if (horizontal) GEOMETRY_FLIP_HORIZONTAL else GEOMETRY_FLIP_VERTICAL, size, size),
        )
    }}

    override fun rotateCanvas(degrees: Float, fill: CanvasFill): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (!degrees.isFinite()) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Rotation angle must be finite")
        val turns = (degrees / 90f).roundToInt()
        if (kotlin.math.abs(degrees - turns * 90f) > 0.0001f) {
            // Image ▸ Rotate ▸ Arbitrary: warp every layer about the centre into the rotated bounding canvas.
            val old = mutableInfo.value.size
            val radians = Math.toRadians(degrees.toDouble())
            val cos = kotlin.math.cos(radians)
            val sin = kotlin.math.sin(radians)
            val width = ceil(kotlin.math.abs(old.width * cos) + kotlin.math.abs(old.height * sin) - 1e-6).toInt().coerceAtLeast(1)
            val height = ceil(kotlin.math.abs(old.width * sin) + kotlin.math.abs(old.height * cos) - 1e-6).toInt().coerceAtLeast(1)
            if (width > MAX_NATIVE_GEOMETRY_DIMENSION || height > MAX_NATIVE_GEOMETRY_DIMENSION) {
                return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "The rotated canvas would exceed $MAX_NATIVE_GEOMETRY_DIMENSION px per side")
            }
            val cx = old.width / 2.0
            val cy = old.height / 2.0
            val tx = width / 2.0
            val ty = height / 2.0
            val matrix = doubleArrayOf(
                cos, -sin, tx - cos * cx + sin * cy,
                sin, cos, ty - sin * cx - cos * cy,
                0.0, 0.0, 1.0,
            )
            return@synchronized applyGeometry(
                "Rotate Canvas ${"%.1f".format(degrees)}°",
                GeometryChange(
                    operation = GEOMETRY_CROP,
                    sourceSize = old,
                    targetSize = DocSize(width, height),
                    cropMatrix = matrix,
                    cropAngleDegrees = degrees,
                    fill = (fill as? CanvasFill.Color)?.color,
                ),
            )
        }
        val normalizedTurns = ((turns % 4) + 4) % 4
        val old = mutableInfo.value.size
        val target = if (normalizedTurns % 2 == 0) old else DocSize(old.height, old.width)
        val label = when (normalizedTurns) {
            1 -> "Rotate Canvas 90° Clockwise"
            2 -> "Rotate Canvas 180°"
            3 -> "Rotate Canvas 90° Counter Clockwise"
            else -> "Rotate Canvas 360°"
        }
        applyGeometry(label, GeometryChange(GEOMETRY_ROTATE, old, target, quarterTurns = normalizedTurns))
    }}

    override fun crop(rect: DocRectF, angleDegrees: Float, deleteCropped: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (!angleDegrees.isFinite()) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Crop angle must be finite")
        val keepCropped = !deleteCropped
        if (keepCropped && kotlin.math.abs(angleDegrees % 360f) > 1e-3f) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Keeping cropped pixels works for straight crops; turn on Delete Cropped Pixels to straighten")
        }
        val coordinateLimit = MAX_NATIVE_GEOMETRY_DIMENSION.toFloat() * 2f
        if (listOf(rect.left, rect.top, rect.right, rect.bottom).any { kotlin.math.abs(it) > coordinateLimit }) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Crop coordinates are outside the supported range")
        }
        val bounds = rect.toDocRect()
        if (bounds.isEmpty) return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Crop area must have positive width and height")
        if (bounds.width > MAX_NATIVE_GEOMETRY_DIMENSION || bounds.height > MAX_NATIVE_GEOMETRY_DIMENSION) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Crop dimensions are limited to $MAX_NATIVE_GEOMETRY_DIMENSION px per side")
        }
        val normalizedAngle = (((angleDegrees.toDouble() % 360.0) + 540.0) % 360.0 - 180.0).toFloat()
        val geometry = engine.cropStraightenGeometry(bounds.left, bounds.top, bounds.width, bounds.height, normalizedAngle.toDouble())
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, engine.geometryError().ifBlank { "Could not calculate crop geometry" })
        if (geometry.size != 11 || geometry[0] !in 1.0..MAX_NATIVE_GEOMETRY_DIMENSION.toDouble() ||
            geometry[1] !in 1.0..MAX_NATIVE_GEOMETRY_DIMENSION.toDouble()
        ) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Native crop geometry returned invalid output dimensions")
        }
        // Keep each unmasked pixel layer's full pixels (merged with any already hidden) before cutting the canvas.
        val kept = if (keepCropped) {
            keepHiddenPixels(-bounds.left, -bounds.top) ?: return@synchronized reject(RejectionCode.UNSUPPORTED, "Could not keep the cropped pixels")
        } else {
            null
        }
        applyGeometry(
            "Crop",
            GeometryChange(
                operation = GEOMETRY_CROP,
                sourceSize = mutableInfo.value.size,
                targetSize = DocSize(geometry[0].toInt(), geometry[1].toInt()),
                cropMatrix = geometry.copyOfRange(2, 11),
                cropAngleDegrees = normalizedAngle,
                keptPixels = kept,
            ),
        )
    }}

    // ---- Hidden pixels (crop with Delete Cropped Pixels off) ----

    /**
     * [info]'s source with the layer's current canvas pixels [live] merged in (canvas pixels win), as a new source
     * covering both; null when it would exceed the size limit. The caller registers the returned store.
     */
    private fun mergeHiddenPixels(info: app.croptop.core.model.HiddenPixelsInfo?, live: Long, canvas: DocSize): Pair<Long, app.croptop.core.model.HiddenPixelsInfo>? {
        if (info == null) {
            val clone = engine.cloneTileStore(live)
            if (clone == 0L) return null
            runCatching { NativeLayerMaskBridge.detach(clone) }
            NativeLayerMaskBridge.setClipped(clone, false)
            return clone to app.croptop.core.model.HiddenPixelsInfo(CroptopManifest.newLayerUuid(), 0, 0, canvas.width, canvas.height)
        }
        val source = smartSources[info.sourceKey] ?: return null
        // The canvas in source coordinates, and the union of both rectangles.
        val cx = -info.offsetX
        val cy = -info.offsetY
        val minX = minOf(0, cx)
        val minY = minOf(0, cy)
        val width = maxOf(info.sourceWidth, cx + canvas.width) - minX
        val height = maxOf(info.sourceHeight, cy + canvas.height) - minY
        if (width > MAX_NATIVE_GEOMETRY_DIMENSION || height > MAX_NATIVE_GEOMETRY_DIMENSION) return null
        val placedSource = engine.transformTileStore(source, GEOMETRY_CANVAS_SIZE, width, height, -minX, -minY)
        if (placedSource == 0L) return null
        val placedCanvas = engine.transformTileStore(live, GEOMETRY_CANVAS_SIZE, width, height, cx - minX, cy - minY)
        if (placedCanvas == 0L) {
            engine.destroyTileStore(placedSource)
            return null
        }
        val canvasArea = NativeSelectionBridge.rasterizeRect(
            width, height, (cx - minX).toDouble(), (cy - minY).toDouble(), (cx - minX + canvas.width).toDouble(), (cy - minY + canvas.height).toDouble(), false,
        )
        try {
            // Hidden part = the old source with the canvas area cleared; the canvas part comes from the layer itself.
            val cut = if (canvasArea == 0L) null else engine.layerViaCutStores(placedSource, canvasArea)
            cut?.getOrNull(1)?.takeIf { it != 0L }?.let(engine::destroyTileStore)
            val outside = cut?.getOrNull(0)?.takeIf { it != 0L } ?: return null
            val merged = try {
                engine.flattenLayers(
                    listOf(
                        NativeRenderLayer(outside, 1, 0, 255, 255, true),
                        NativeRenderLayer(placedCanvas, 2, 0, 255, 255, true),
                    ),
                )
            } finally {
                engine.destroyTileStore(outside)
            }
            if (merged == 0L) return null
            return merged to app.croptop.core.model.HiddenPixelsInfo(CroptopManifest.newLayerUuid(), info.offsetX + minX, info.offsetY + minY, width, height)
        } finally {
            if (canvasArea != 0L) NativeSelectionBridge.destroyStore(canvasArea)
            engine.destroyTileStore(placedSource)
            engine.destroyTileStore(placedCanvas)
        }
    }

    /** The canvas pixels of a hidden-pixel source at its offset, on a [canvas]-sized store. */
    private fun renderHiddenPixels(info: app.croptop.core.model.HiddenPixelsInfo, canvas: DocSize): Long {
        val source = smartSources[info.sourceKey] ?: return 0L
        return engine.transformTileStore(source, GEOMETRY_CANVAS_SIZE, canvas.width, canvas.height, info.offsetX, info.offsetY)
    }

    /**
     * Merges every unmasked pixel layer's canvas pixels into its hidden source and returns the records shifted by
     * ([dx], [dy]) (the canvas move of the operation about to run). Holds [lock]. Null on failure.
     */
    private fun keepHiddenPixels(dx: Int, dy: Int): Map<LayerId, app.croptop.core.model.HiddenPixelsInfo>? {
        val canvas = mutableInfo.value.size
        val out = linkedMapOf<LayerId, app.croptop.core.model.HiddenPixelsInfo>()
        for (node in mutableLayers.value.flatten().filter { it.kind == LayerKind.PIXEL && it.mask == null }) {
            val live = pixelStores[node.id] ?: continue
            val (store, info) = mergeHiddenPixels(node.hiddenPixels, live, canvas) ?: return null
            smartSources[info.sourceKey] = store
            out[node.id] = info.copy(offsetX = info.offsetX + dx, offsetY = info.offsetY + dy)
        }
        return out
    }

    override fun cropPerspective(quad: CropQuad, deleteCropped: Boolean): CommandResult = withDocumentCommand { synchronized(lock) {
        if (closed) return@synchronized reject(RejectionCode.CLOSED, "Document is closed")
        if (!deleteCropped) {
            return@synchronized reject(RejectionCode.UNSUPPORTED, "Keeping cropped pixels is not supported until hidden pixel storage is available")
        }
        val sourceSize = mutableInfo.value.size
        if (quad.points.any { point ->
                !point.x.isFinite() || !point.y.isFinite() || point.x < 0f || point.y < 0f ||
                    point.x > sourceSize.width || point.y > sourceSize.height
            }
        ) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Perspective crop corners must be finite and inside the canvas")
        }
        val geometry = engine.perspectiveCropGeometry(sourceSize.width, sourceSize.height, quad)
            ?: return@synchronized reject(RejectionCode.INVALID_ARGUMENT, engine.geometryError().ifBlank { "Could not calculate perspective crop geometry" })
        if (geometry.size != 11 || geometry[0] !in 1.0..MAX_NATIVE_GEOMETRY_DIMENSION.toDouble() ||
            geometry[1] !in 1.0..MAX_NATIVE_GEOMETRY_DIMENSION.toDouble() || geometry.drop(2).any { !it.isFinite() }
        ) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Perspective crop geometry is invalid")
        }
        val matrix = geometry.copyOfRange(2, 11)
        // Geometry remaps all document content. Reject a projective horizon crossing anywhere on canvas.
        val canvasCorners = listOf(
            0.0 to 0.0,
            sourceSize.width.toDouble() to 0.0,
            sourceSize.width.toDouble() to sourceSize.height.toDouble(),
            0.0 to sourceSize.height.toDouble(),
        )
        if (canvasCorners.any { (x, y) -> matrix[6] * x + matrix[7] * y + matrix[8] <= 1e-12 }) {
            return@synchronized reject(RejectionCode.INVALID_ARGUMENT, "Perspective crop crosses the canvas projective horizon")
        }
        applyGeometry(
            "Perspective Crop",
            GeometryChange(
                operation = GEOMETRY_CROP,
                sourceSize = sourceSize,
                targetSize = DocSize(geometry[0].toInt(), geometry[1].toInt()),
                cropMatrix = matrix,
                perspectiveCrop = true,
            ),
        )
    }}

    /** A freshly rendered store for [node] takes back its clipping flag and layer styles (masks are handled by callers). */
    private fun reattachLayerState(node: LayerNode, store: Long): Long {
        if (node.clipped) NativeLayerMaskBridge.setClipped(store, true)
        node.styles?.takeIf { it.anyEnabled }?.let { NativeLayerStylesBridge.attach(store, it) }
        return store
    }

    /** The source→target matrix of a canvas operation (keeps smart object transforms in step). */
    private fun geometryMatrix(change: GeometryChange): Matrix3? {
        val sw = change.sourceSize.width.toFloat()
        val sh = change.sourceSize.height.toFloat()
        return when (change.operation) {
            GEOMETRY_CROP -> change.cropMatrix?.takeIf { it.size == 9 }?.let { m -> Matrix3(m.map(Double::toFloat)) }
            GEOMETRY_RESIZE -> Matrix3.scale(change.targetSize.width / sw, change.targetSize.height / sh)
            GEOMETRY_CANVAS_SIZE -> Matrix3.translate(change.dx.toFloat(), change.dy.toFloat())
            GEOMETRY_FLIP_HORIZONTAL -> Matrix3(listOf(-1f, 0f, sw, 0f, 1f, 0f, 0f, 0f, 1f))
            GEOMETRY_FLIP_VERTICAL -> Matrix3(listOf(1f, 0f, 0f, 0f, -1f, sh, 0f, 0f, 1f))
            GEOMETRY_ROTATE -> when (((change.quarterTurns % 4) + 4) % 4) {
                1 -> Matrix3(listOf(0f, -1f, sh, 1f, 0f, 0f, 0f, 0f, 1f))
                2 -> Matrix3(listOf(-1f, 0f, sw, 0f, -1f, sh, 0f, 0f, 1f))
                3 -> Matrix3(listOf(0f, 1f, 0f, -1f, 0f, sw, 0f, 0f, 1f))
                else -> Matrix3.Identity
            }
            else -> null
        }
    }

    /** Prepares every pixel and selection store before replacing any live document state. */
    private fun applyGeometry(label: String, change: GeometryChange): CommandResult {
        fun supported(nodes: List<LayerNode>): Boolean = nodes.all { node ->
            when (node.kind) {
                LayerKind.GROUP -> supported(node.children)
                LayerKind.PIXEL, LayerKind.SHAPE, LayerKind.TEXT, LayerKind.ADJUSTMENT, LayerKind.SMART_OBJECT, LayerKind.FILL -> node.children.isEmpty()
                else -> false
            }
        }
        if (!supported(snapshots[cursor].tree.layers)) {
            return reject(RejectionCode.UNSUPPORTED, "Canvas geometry supports pixel and vector shape layers only")
        }
        if (snapshots[cursor].tree.flatten().any { it.kind == LayerKind.PIXEL && pixelStores[it.id] == null }) {
            return reject(RejectionCode.UNSUPPORTED, "A pixel layer has no native storage")
        }
        finishSelectionTransformBeforeMutation()

        val targetBounds = DocRect.of(change.targetSize)
        val scaleX = change.targetSize.width.toFloat() / change.sourceSize.width
        val scaleY = change.targetSize.height.toFloat() / change.sourceSize.height
        fun mapPoint(point: DocPoint): DocPoint = when (change.operation) {
            GEOMETRY_CANVAS_SIZE -> DocPoint(point.x + change.dx, point.y + change.dy)
            GEOMETRY_FLIP_HORIZONTAL -> DocPoint(change.sourceSize.width - point.x, point.y)
            GEOMETRY_FLIP_VERTICAL -> DocPoint(point.x, change.sourceSize.height - point.y)
            GEOMETRY_RESIZE -> DocPoint(point.x * scaleX, point.y * scaleY)
            GEOMETRY_CROP -> {
                val matrix = checkNotNull(change.cropMatrix)
                val denominator = matrix[6] * point.x + matrix[7] * point.y + matrix[8]
                DocPoint(
                    ((matrix[0] * point.x + matrix[1] * point.y + matrix[2]) / denominator).toFloat(),
                    ((matrix[3] * point.x + matrix[4] * point.y + matrix[5]) / denominator).toFloat(),
                )
            }
            else -> when (change.quarterTurns) {
                1 -> DocPoint(change.sourceSize.height - point.y, point.x)
                2 -> DocPoint(change.sourceSize.width - point.x, change.sourceSize.height - point.y)
                3 -> DocPoint(point.y, change.sourceSize.width - point.x)
                else -> point
            }
        }
        fun mapVector(vector: DocPoint, at: DocPoint): DocPoint {
            val mapped = when (change.operation) {
            GEOMETRY_RESIZE -> DocPoint(vector.x * scaleX, vector.y * scaleY)
            GEOMETRY_FLIP_HORIZONTAL -> DocPoint(-vector.x, vector.y)
            GEOMETRY_FLIP_VERTICAL -> DocPoint(vector.x, -vector.y)
            GEOMETRY_CROP -> {
                val matrix = checkNotNull(change.cropMatrix)
                if (change.perspectiveCrop) {
                    // Apply the homography Jacobian at each anchor so relative Bézier handles retain their local direction/scale.
                    val x = at.x.toDouble()
                    val y = at.y.toDouble()
                    val vx = vector.x.toDouble()
                    val vy = vector.y.toDouble()
                    val w = matrix[6] * x + matrix[7] * y + matrix[8]
                    val dw = matrix[6] * vx + matrix[7] * vy
                    val nx = matrix[0] * x + matrix[1] * y + matrix[2]
                    val ny = matrix[3] * x + matrix[4] * y + matrix[5]
                    DocPoint(
                        (((matrix[0] * vx + matrix[1] * vy) * w - nx * dw) / (w * w)).toFloat(),
                        (((matrix[3] * vx + matrix[4] * vy) * w - ny * dw) / (w * w)).toFloat(),
                    )
                } else {
                    DocPoint(
                        (matrix[0] * vector.x + matrix[1] * vector.y).toFloat(),
                        (matrix[3] * vector.x + matrix[4] * vector.y).toFloat(),
                    )
                }
            }
            GEOMETRY_ROTATE -> when (change.quarterTurns) {
                1 -> DocPoint(-vector.y, vector.x)
                2 -> DocPoint(-vector.x, -vector.y)
                3 -> DocPoint(vector.y, -vector.x)
                else -> vector
            }
            else -> vector
            }
            return DocPoint(if (mapped.x == 0f) 0f else mapped.x, if (mapped.y == 0f) 0f else mapped.y)
        }
        fun mapBounds(bounds: DocRect?): DocRect? {
            if (bounds == null) return null
            if (change.operation == GEOMETRY_RESIZE) return imageSizeBounds(bounds, change.sourceSize, change.targetSize)
            val corners = listOf(
                DocPoint(bounds.left.toFloat(), bounds.top.toFloat()),
                DocPoint(bounds.right.toFloat(), bounds.top.toFloat()),
                DocPoint(bounds.right.toFloat(), bounds.bottom.toFloat()),
                DocPoint(bounds.left.toFloat(), bounds.bottom.toFloat()),
            ).map(::mapPoint)
            val mapped = DocRect(
                floor(corners.minOf { it.x }.toDouble()).toInt(), floor(corners.minOf { it.y }.toDouble()).toInt(),
                ceil(corners.maxOf { it.x }.toDouble()).toInt(), ceil(corners.maxOf { it.y }.toDouble()).toInt(),
            ).intersect(targetBounds)
            return mapped.takeUnless { it.isEmpty }
        }
        fun mapSourceBox(box: VectorSourceBox?): VectorSourceBox? {
            if (box == null) return null
            val corners = listOf(
                DocPoint(box.x, box.y), DocPoint(box.x + box.width, box.y),
                DocPoint(box.x + box.width, box.y + box.height), DocPoint(box.x, box.y + box.height),
            ).map(::mapPoint)
            return VectorSourceBox(
                corners.minOf { it.x }, corners.minOf { it.y },
                corners.maxOf { it.x } - corners.minOf { it.x },
                corners.maxOf { it.y } - corners.minOf { it.y },
            )
        }
        fun mapPath(path: VectorPathDraft): VectorPathDraft {
            val primitive = path.primitive?.let { value ->
                if (change.perspectiveCrop) return@let null
                if (change.operation == GEOMETRY_RESIZE && kotlin.math.abs(scaleX - scaleY) > 0.000001f) return@let null
                val radii = if (change.operation == GEOMETRY_RESIZE) {
                    value.cornerRadii.copy(
                        topLeft = value.cornerRadii.topLeft * scaleX,
                        topRight = value.cornerRadii.topRight * scaleX,
                        bottomRight = value.cornerRadii.bottomRight * scaleX,
                        bottomLeft = value.cornerRadii.bottomLeft * scaleX,
                    )
                } else value.cornerRadii
                val mappedRadii = when (change.operation) {
                    GEOMETRY_FLIP_HORIZONTAL -> radii.copy(
                        topLeft = radii.topRight, topRight = radii.topLeft,
                        bottomRight = radii.bottomLeft, bottomLeft = radii.bottomRight,
                    )
                    GEOMETRY_FLIP_VERTICAL -> radii.copy(
                        topLeft = radii.bottomLeft, topRight = radii.bottomRight,
                        bottomRight = radii.topRight, bottomLeft = radii.topLeft,
                    )
                    GEOMETRY_ROTATE -> when (change.quarterTurns) {
                        1 -> radii.copy(topLeft = radii.bottomLeft, topRight = radii.topLeft, bottomRight = radii.topRight, bottomLeft = radii.bottomRight)
                        2 -> radii.copy(topLeft = radii.bottomRight, topRight = radii.bottomLeft, bottomRight = radii.topLeft, bottomLeft = radii.topRight)
                        3 -> radii.copy(topLeft = radii.topRight, topRight = radii.bottomRight, bottomRight = radii.bottomLeft, bottomLeft = radii.topLeft)
                        else -> radii
                    }
                    else -> radii
                }
                val rotation = when (change.operation) {
                    GEOMETRY_FLIP_HORIZONTAL -> 180f - value.rotationDegrees
                    GEOMETRY_FLIP_VERTICAL -> -value.rotationDegrees
                    GEOMETRY_ROTATE -> value.rotationDegrees + change.quarterTurns * 90f
                    GEOMETRY_CROP -> value.rotationDegrees + change.cropAngleDegrees
                    else -> value.rotationDegrees
                }
                value.copy(cornerRadii = mappedRadii, rotationDegrees = ((rotation + 180f).mod(360f)) - 180f, sourceBox = mapSourceBox(value.sourceBox))
            }
            return path.copy(
                subpaths = path.subpaths.map { subpath ->
                    subpath.copy(anchors = subpath.anchors.map { anchor ->
                        anchor.copy(
                            point = mapPoint(anchor.point),
                            incoming = anchor.incoming?.let { mapVector(it, anchor.point) },
                            outgoing = anchor.outgoing?.let { mapVector(it, anchor.point) },
                        )
                    })
                },
                primitive = primitive,
            )
        }
        fun mapNodes(nodes: List<LayerNode>): List<LayerNode> = nodes.map { node ->
            node.copy(
                children = mapNodes(node.children),
                bounds = mapBounds(node.bounds),
                vectorPaths = if (node.kind == LayerKind.SHAPE) node.vectorPaths.map(::mapPath) else node.vectorPaths,
                // Text pixels transform as raster; the origin follows so later edits re-render in place.
                text = node.text?.let { text ->
                    geometryMatrix(change)?.let { transformText(text, it) } ?: text.copy(origin = mapPoint(text.origin))
                },
            )
        }
        var nextTree = snapshots[cursor].tree.copy(layers = mapNodes(snapshots[cursor].tree.layers))
        val prepared = mutableMapOf<LayerId, Long>()
        fun cleanupPrepared() = prepared.values.forEach(engine::destroyTileStore)
        fun transformStore(handle: Long): Long = if (change.operation == GEOMETRY_CROP) {
                engine.warpTileStore(
                    handle,
                    change.targetSize.width,
                    change.targetSize.height,
                    checkNotNull(change.cropMatrix),
                    if (change.cropAngleDegrees == 0f && !change.perspectiveCrop) RESAMPLE_NEAREST else RESAMPLE_BILINEAR,
                )
            } else if (change.operation == GEOMETRY_RESIZE) {
                if (change.sourceSize == change.targetSize) engine.cloneTileStore(handle)
                else engine.resizeTileStore(handle, change.targetSize.width, change.targetSize.height, change.quarterTurns)
            } else {
                engine.transformTileStore(
                    handle, change.operation, change.targetSize.width, change.targetSize.height,
                    if (change.operation == GEOMETRY_ROTATE) change.quarterTurns else change.dx,
                    change.dy,
                )
            }
        for ((id, handle) in pixelStores) {
            val output = transformStore(handle)
            if (output == 0L) {
                cleanupPrepared()
                return reject(RejectionCode.UNSUPPORTED, engine.geometryError().ifBlank { "Could not transform layer pixels" })
            }
            prepared[id] = output
            // New stores don't carry the layer's mask or clipping: transform the mask pixels the same way and re-attach.
            val node = snapshots[cursor].tree.find(id)
            if (node?.clipped == true) NativeLayerMaskBridge.setClipped(output, true)
            val maskInfo = node?.mask
            if (maskInfo != null) {
                val moved = runCatching {
                    val mask = NativeLayerMaskBridge.fromLayerStore(handle)
                    try {
                        val pixels = NativeLayerMaskBridge.pixels(mask)
                        val empty = NativeLayerMaskBridge.emptyCoverage(mask)
                        val transformed = transformStore(pixels)
                        engine.destroyTileStore(pixels)
                        check(transformed != 0L) { engine.geometryError().ifBlank { "Could not transform the layer mask" } }
                        val next = NativeLayerMaskBridge.fromStore(transformed, empty)
                        engine.destroyTileStore(transformed)
                        try {
                            NativeLayerMaskBridge.attach(output, next, maskInfo)
                        } finally {
                            NativeLayerMaskBridge.destroy(next)
                        }
                    } finally {
                        NativeLayerMaskBridge.destroy(mask)
                    }
                }
                if (moved.isFailure) {
                    cleanupPrepared()
                    return reject(RejectionCode.UNSUPPORTED, moved.exceptionOrNull()?.message ?: "Could not transform the layer mask")
                }
            }
        }
        // Hidden pixels: a keep-pixels crop records them; Canvas Size reveals them; any other operation drops them.
        when {
            change.keptPixels != null -> for (node in nextTree.flatten().filter { it.kind == LayerKind.PIXEL }) {
                val kept = change.keptPixels[node.id]
                nextTree = NativeLayerTreeOps.update(nextTree, node.id) { it.copy(hiddenPixels = kept) }
            }
            change.operation == GEOMETRY_CANVAS_SIZE -> for (node in nextTree.flatten().filter { it.hiddenPixels != null }) {
                val live = pixelStores[node.id]
                val merged = if (node.kind == LayerKind.PIXEL && node.mask == null && live != null) {
                    mergeHiddenPixels(node.hiddenPixels, live, change.sourceSize)
                } else {
                    null
                }
                if (merged == null) {
                    nextTree = NativeLayerTreeOps.update(nextTree, node.id) { it.copy(hiddenPixels = null) }
                    continue
                }
                val (store, info) = merged
                smartSources[info.sourceKey] = store
                val moved = info.copy(offsetX = info.offsetX + change.dx, offsetY = info.offsetY + change.dy)
                val revealed = renderHiddenPixels(moved, change.targetSize)
                if (revealed != 0L) prepared.put(node.id, reattachLayerState(node, revealed))?.let(engine::destroyTileStore)
                nextTree = NativeLayerTreeOps.update(nextTree, node.id) { it.copy(hiddenPixels = moved) }
            }
            else -> for (node in nextTree.flatten().filter { it.hiddenPixels != null }) {
                nextTree = NativeLayerTreeOps.update(nextTree, node.id) { it.copy(hiddenPixels = null) }
            }
        }
        // Fill layers cover the whole new canvas: paint them again (the transformed store keeps a mask if any).
        for (node in nextTree.flatten().filter { it.kind == LayerKind.FILL && it.fill != null && it.mask == null }) {
            val repainted = renderFillStore(checkNotNull(node.fill), change.targetSize)
            if (repainted != 0L) prepared.put(node.id, reattachLayerState(node, repainted))?.let(engine::destroyTileStore)
        }
        // Smart objects: compose the canvas operation into their transform and re-render from the source.
        geometryMatrix(change)?.let { geometry ->
            for (node in nextTree.flatten().filter { it.kind == LayerKind.SMART_OBJECT && it.smartObject != null }) {
                val composed = checkNotNull(node.smartObject).let { it.copy(transform = (geometry * it.matrix).values) }
                nextTree = NativeLayerTreeOps.update(nextTree, node.id) { it.copy(smartObject = composed) }
                if (node.mask != null || node.clipped) continue
                val rendered = runCatching { renderSmartObject(composed, change.targetSize) }.getOrNull() ?: continue
                prepared.put(node.id, reattachLayerState(node, rendered))?.let(engine::destroyTileStore)
            }
        }
        // Canvas fill colour: paint the area the old canvas doesn't cover on the bottom pixel layer.
        change.fill?.let { color ->
            val bottom = nextTree.flatten().lastOrNull { it.kind == LayerKind.PIXEL }?.id
            val target = bottom?.let { prepared[it] }
            if (target != null) {
                val src = change.sourceSize
                val old = NativeSelectionBudgetBridge.rasterizeRectLike(
                    selectionMask, 0.0, 0.0, src.width.toDouble(), src.height.toDouble(), false,
                )
                val covered = if (old == 0L) 0L else transformStore(old).also { NativeSelectionBridge.destroyStore(old) }
                val exposed = if (covered == 0L) 0L else NativeSelectionBudgetBridge.invertLike(target, covered).also { engine.destroyTileStore(covered) }
                if (exposed == 0L) {
                    cleanupPrepared()
                    return reject(RejectionCode.UNSUPPORTED, "Could not compute the new canvas area")
                }
                val failure = NativeFillBridge.solid(target, floatArrayOf(color.r, color.g, color.b, color.a), 1f, 0, false, exposed)
                NativeSelectionBridge.destroyStore(exposed)
                if (failure != null) {
                    cleanupPrepared()
                    return reject(RejectionCode.UNSUPPORTED, failure)
                }
            }
        }
        // Transformed stores don't carry adjustment LUTs; re-bake them on the new carriers.
        nextTree.flatten().filter { it.kind == LayerKind.ADJUSTMENT && it.adjustment != null }.forEach { node ->
            prepared[node.id]?.let { store -> engine.setAdjustmentLayerLut(store, checkNotNull(node.adjustment)) }
        }
        val nextSelection = if (change.operation == GEOMETRY_CROP) {
            engine.warpTileStore(
                selectionMask,
                change.targetSize.width,
                change.targetSize.height,
                checkNotNull(change.cropMatrix),
                RESAMPLE_NEAREST,
            )
        } else if (change.operation == GEOMETRY_RESIZE) {
            if (change.sourceSize == change.targetSize) engine.cloneTileStore(selectionMask)
            else engine.resizeTileStore(selectionMask, change.targetSize.width, change.targetSize.height, change.quarterTurns)
        } else {
            engine.transformTileStore(
                selectionMask, change.operation, change.targetSize.width, change.targetSize.height,
                if (change.operation == GEOMETRY_ROTATE) change.quarterTurns else change.dx,
                change.dy,
            )
        }
        if (nextSelection == 0L) {
            cleanupPrepared()
            return reject(RejectionCode.UNSUPPORTED, engine.geometryError().ifBlank { "Could not transform selection" })
        }
        val nextLastSelection = if (change.operation == GEOMETRY_CROP) {
            0L
        } else if (lastSelectionMask == 0L) 0L else if (change.operation == GEOMETRY_RESIZE) {
            if (change.sourceSize == change.targetSize) engine.cloneTileStore(lastSelectionMask)
            else engine.resizeTileStore(lastSelectionMask, change.targetSize.width, change.targetSize.height, change.quarterTurns)
        } else {
            engine.transformTileStore(
                lastSelectionMask, change.operation, change.targetSize.width, change.targetSize.height,
                if (change.operation == GEOMETRY_ROTATE) change.quarterTurns else change.dx,
                change.dy,
            )
        }
        if (change.operation != GEOMETRY_CROP && lastSelectionMask != 0L && nextLastSelection == 0L) {
            cleanupPrepared()
            engine.destroyTileStore(nextSelection)
            return reject(RejectionCode.UNSUPPORTED, engine.geometryError().ifBlank { "Could not transform the previous selection" })
        }
        val nextSelectionInfo = if (change.operation == GEOMETRY_CROP) SelectionInfo.None else selectionInfoAfterGeometry(nextSelection, mutableSelection.value)
        val nextLastSelectionInfo = if (nextLastSelection == 0L) null else selectionInfoAfterGeometry(
            nextLastSelection,
            lastSelectionInfo ?: SelectionInfo.None,
        ) as? SelectionInfo.Active

        val historyPixels = clonePixelStores(prepared, nextTree)
        if (historyPixels == null) {
            cleanupPrepared()
            engine.destroyTileStore(nextSelection)
            engine.destroyTileStore(nextLastSelection)
            return reject(RejectionCode.UNSUPPORTED, "Could not preserve canvas geometry for undo")
        }
        val historySelection = cloneSelectionMask(nextSelection)
        if (historySelection == null) {
            historyPixels.values.forEach(engine::destroyTileStore)
            cleanupPrepared()
            engine.destroyTileStore(nextSelection)
            engine.destroyTileStore(nextLastSelection)
            return reject(RejectionCode.UNSUPPORTED, "Could not preserve transformed selection for undo")
        }
        val historyLastSelection = cloneOptionalSelection(nextLastSelection)
        if (historyLastSelection == null) {
            historyPixels.values.forEach(engine::destroyTileStore)
            engine.destroyTileStore(historySelection)
            cleanupPrepared()
            engine.destroyTileStore(nextSelection)
            engine.destroyTileStore(nextLastSelection)
            return reject(RejectionCode.UNSUPPORTED, "Could not preserve reselect state for undo")
        }
        while (snapshots.lastIndex > cursor) {
            destroySnapshot(snapshots.removeAt(snapshots.lastIndex))
            viewportOffsets.removeAt(viewportOffsets.lastIndex)
        }
        snapshots += Snapshot(
            label, nextTree, historyPixels, historySelection, nextSelectionInfo,
            historyLastSelection, nextLastSelectionInfo, change.targetSize,
            change.targetResolutionPpi ?: mutableInfo.value.resolutionPpi,
            mutableInfo.value.profile, snapshots[cursor].workingIcc?.copyOf(),
        )
        viewportOffsets += viewportOffsets[cursor]
        cursor = snapshots.lastIndex
        pixelStores.values.forEach(engine::destroyTileStore)
        pixelStores.clear()
        pixelStores.putAll(prepared)
        engine.destroyTileStore(selectionMask)
        selectionMask = nextSelection
        engine.destroyTileStore(lastSelectionMask)
        lastSelectionMask = nextLastSelection
        lastSelectionInfo = nextLastSelectionInfo
        mutableSelection.value = nextSelectionInfo
        publish()
        return CommandResult.Accepted(++sequence)
    }

    private fun selectionInfoAfterGeometry(mask: Long, previous: SelectionInfo): SelectionInfo {
        if (previous == SelectionInfo.None) return SelectionInfo.None
        val bounds = NativeSelectionBridge.bounds(mask)
        if (bounds.size != 4 || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) return SelectionInfo.None
        return SelectionInfo.Active(
            DocRect(bounds[0], bounds[1], bounds[2], bounds[3]),
            feathered = (previous as? SelectionInfo.Active)?.feathered ?: false,
        )
    }

    /** Atomically installs a fully prepared pixel-store set with one history snapshot. */
    private fun commitPrepared(
        label: String,
        tree: LayerTree,
        prepared: MutableMap<LayerId, Long>,
        previewOffset: Pair<Float, Float> = viewportOffsets[cursor],
        profileAfter: ColorProfileRef = snapshots[cursor].profile,
        workingIccAfter: ByteArray? = snapshots[cursor].workingIcc,
    ): CommandResult {
        finishSelectionTransformBeforeMutation()
        // Layer styles and clipping ride on the layer store; freshly computed stores (filters, geometry, re-rendered
        // type) need them back. Setting the clip flag is cheap; styles are re-attached only when missing (keeps the cache).
        for ((id, store) in prepared) {
            if (tree.find(id)?.clipped == true) NativeLayerMaskBridge.setClipped(store, true)
        }
        for ((id, store) in prepared) {
            val styles = tree.find(id)?.styles?.takeIf { it.anyEnabled } ?: continue
            if (!NativeLayerStylesBridge.nativeHasStyles(store)) NativeLayerStylesBridge.attach(store, styles)
        }
        val savedPixels = clonePixelStores(prepared, tree)
            ?: run {
                prepared.values.forEach(engine::destroyTileStore)
                return reject(RejectionCode.UNSUPPORTED, "Could not preserve translated pixels for undo")
            }
        val savedSelection = cloneSelectionMask(selectionMask)
            ?: run {
                savedPixels.values.forEach(engine::destroyTileStore)
                prepared.values.forEach(engine::destroyTileStore)
                return reject(RejectionCode.UNSUPPORTED, "Could not preserve selection for undo")
            }
        val savedLastSelection = cloneOptionalSelection(lastSelectionMask)
            ?: run {
                savedPixels.values.forEach(engine::destroyTileStore)
                prepared.values.forEach(engine::destroyTileStore)
                engine.destroyTileStore(savedSelection)
                return reject(RejectionCode.UNSUPPORTED, "Could not preserve reselect state for undo")
            }
        val previousOffset = viewportOffsets[cursor]
        while (snapshots.lastIndex > cursor) {
            destroySnapshot(snapshots.removeAt(snapshots.lastIndex))
            viewportOffsets.removeAt(viewportOffsets.lastIndex)
        }
        snapshots += Snapshot(
            label, tree, savedPixels, savedSelection, mutableSelection.value,
            savedLastSelection, lastSelectionInfo, mutableInfo.value.size, mutableInfo.value.resolutionPpi,
            profileAfter, workingIccAfter?.copyOf(),
        )
        viewportOffsets += previewOffset
        cursor = snapshots.lastIndex
        pixelStores.values.forEach(engine::destroyTileStore)
        pixelStores.clear()
        pixelStores.putAll(prepared)
        publish()
        updateViewportPreview(previousOffset, previewOffset)
        return CommandResult.Accepted(++sequence)
    }

    private fun updateViewportPreview(previousOffset: Pair<Float, Float>, nextOffset: Pair<Float, Float>) {
        if (previousOffset == nextOffset) return
        val preview = viewportPreview ?: return
        val movedPreview = translatePreview(
            preview,
            nextOffset.first - previousOffset.first,
            nextOffset.second - previousOffset.second,
            mutableInfo.value.size,
        )
        viewportPreview = movedPreview
        attachedViewports.toList().forEach { it.setPreview(movedPreview) }
        preview.recycle()
    }

    private fun publish() {
        blendIfPreview.clear()
        fillOpacityPreview.clear()
        mutableLayers.value = snapshots[cursor].tree
        mutableSelection.value = snapshots[cursor].selectionInfo
        mutableInfo.value = mutableInfo.value.copy(
            size = snapshots[cursor].documentSize,
            resolutionPpi = snapshots[cursor].resolutionPpi,
            profile = snapshots[cursor].profile,
        )
        publishHistory()
        val dirty = cursor != cleanIndex || guidesModified
        mutableInfo.value = mutableInfo.value.copy(dirty = dirty)
        if (dirty) scheduleAutosaveLocked()
        val presentation = previewPresentation(snapshots[cursor].tree)
        attachedViewports.toList().forEach { it.setPreviewPresentation(presentation) }
        val acceptedSequence = sequence + 1
        pendingRenderSequence = acceptedSequence
        mutableEvents.tryEmit(EngineEvent.Applied(acceptedSequence, snapshots[cursor].label))
        queueViewportComposite(snapshots[cursor].tree, acceptedSequence)
    }

    /** The imported-source preview follows its source layer's visibility and inherited group opacity. */
    private fun previewPresentation(tree: LayerTree): PreviewPresentation {
        if (viewportPreviewIsComposite) return PreviewPresentation(visible = true, opacity = 1f)
        val target = viewportPreviewLayerId ?: return PreviewPresentation(visible = false, opacity = 0f)
        return tree.importedSourcePresentation(target)
    }

    private inner class NativeRenderLayerStack(
        val layers: List<NativeRenderLayer>,
        private val temporaryStores: MutableList<Long>,
        val usesTransparentBackdrop: Boolean = false,
    ) : AutoCloseable {
        fun transferTemporaryStoresTo(destination: MutableList<Long>) {
            destination += temporaryStores
            temporaryStores.clear()
        }

        override fun close() = temporaryStores.forEach(engine::destroyTileStore)
    }

    /** Collects a display stack, isolating groups whose blend mode is not Pass Through. */
    private fun collectRenderLayerStack(tree: LayerTree, stores: Map<LayerId, Long>): NativeRenderLayerStack {
        val owned = mutableListOf<Long>()
        fun opacityByte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
        fun collect(nodes: List<LayerNode>, parentVisible: Boolean, parentOpacity: Float): List<NativeRenderLayer> {
            val result = mutableListOf<NativeRenderLayer>()
            nodes.asReversed().forEach { node ->
                val visible = parentVisible && node.visible
                if (node.kind == LayerKind.GROUP) {
                    if (node.blendMode == BlendMode.PASS_THROUGH) {
                        result += collect(node.children, visible, parentOpacity * node.opacity * (fillOpacityPreview[node.id] ?: node.fillOpacity))
                    } else if (visible) {
                        val children = collect(node.children, true, 1f)
                        if (children.any(NativeRenderLayer::visible)) {
                            val flattened = engine.flattenLayers(children)
                            owned += flattened
                            result += NativeRenderLayer(
                                store = flattened,
                                id = node.id.value,
                                blendMode = (node.blendMode.ordinal - 1).coerceIn(0, 26),
                                opacity = opacityByte(parentOpacity * node.opacity),
                                fillOpacity = opacityByte((fillOpacityPreview[node.id] ?: node.fillOpacity)),
                                visible = true,
                            )
                        }
                    }
                    return@forEach
                }
                stores[node.id]?.let { store ->
                    NativeLayerMaskBridge.setBlendIf(store, blendIfPreview[node.id] ?: node.blendIf)
                    result += NativeRenderLayer(
                        store = store,
                        id = node.id.value,
                        blendMode = (node.blendMode.ordinal - 1).coerceIn(0, 26),
                        opacity = opacityByte(parentOpacity * node.opacity),
                        fillOpacity = opacityByte((fillOpacityPreview[node.id] ?: node.fillOpacity)),
                        visible = visible,
                    )
                }
            }
            return result
        }
        return try {
            NativeRenderLayerStack(collect(tree.layers, true, 1f), owned)
        } catch (failure: Throwable) {
            owned.forEach(engine::destroyTileStore)
            throw failure
        }
    }

    private fun collectRenderLayers(tree: LayerTree, stores: Map<LayerId, Long>): List<NativeRenderLayer> {
        val renderLayers = mutableListOf<NativeRenderLayer>()
        fun opacityByte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
        fun collect(nodes: List<LayerNode>, parentVisible: Boolean, parentOpacity: Float) {
            nodes.asReversed().forEach { node ->
                val visible = parentVisible && node.visible
                val inheritedOpacity = parentOpacity * node.opacity * (fillOpacityPreview[node.id] ?: node.fillOpacity)
                when (node.kind) {
                    LayerKind.GROUP -> collect(node.children, visible, inheritedOpacity)

                    LayerKind.PIXEL, LayerKind.SHAPE, LayerKind.ADJUSTMENT, LayerKind.TEXT, LayerKind.SMART_OBJECT, LayerKind.FILL -> stores[node.id]?.let { store ->
                        NativeLayerMaskBridge.setBlendIf(store, blendIfPreview[node.id] ?: node.blendIf)
                        renderLayers += NativeRenderLayer(
                            store = store,
                            id = node.id.value,
                            blendMode = (node.blendMode.ordinal - 1).coerceIn(0, 26),
                            opacity = opacityByte(parentOpacity * node.opacity),
                            fillOpacity = opacityByte((fillOpacityPreview[node.id] ?: node.fillOpacity)),
                            visible = visible,
                        )
                    }

                    else -> Unit
                }
            }
        }
        collect(tree.layers, true, 1f)
        return renderLayers
    }

    /** Collects bottom-to-top render inputs until [target] is reached, excluding the target itself. */
    private fun collectRenderLayersBelow(
        tree: LayerTree,
        stores: Map<LayerId, Long>,
        target: LayerId,
    ): NativeRenderLayerStack {
        data class PrefixResult(val layers: List<NativeRenderLayer>, val foundTarget: Boolean, val resetOuterStack: Boolean)
        val owned = mutableListOf<Long>()
        fun opacityByte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
        fun collect(nodes: List<LayerNode>, parentVisible: Boolean, parentOpacity: Float): PrefixResult {
            val renderLayers = mutableListOf<NativeRenderLayer>()
            for (node in nodes.asReversed()) {
                if (node.id == target) {
                    return PrefixResult(renderLayers, foundTarget = true, resetOuterStack = false)
                }
                val visible = parentVisible && node.visible
                if (node.kind == LayerKind.GROUP) {
                    if (node.blendMode == BlendMode.PASS_THROUGH) {
                        val childPrefix = collect(node.children, visible, parentOpacity * node.opacity * (fillOpacityPreview[node.id] ?: node.fillOpacity))
                        if (childPrefix.foundTarget) {
                            if (childPrefix.resetOuterStack) return childPrefix
                            renderLayers += childPrefix.layers
                            return PrefixResult(renderLayers, foundTarget = true, resetOuterStack = false)
                        }
                        renderLayers += childPrefix.layers
                    } else {
                        val childPrefix = collect(node.children, parentVisible = true, parentOpacity = 1f)
                        if (childPrefix.foundTarget) {
                            // A target inside an isolated group samples only that group's internal backdrop.
                            return PrefixResult(childPrefix.layers, foundTarget = true, resetOuterStack = true)
                        }
                        if (visible && childPrefix.layers.any(NativeRenderLayer::visible)) {
                            val flattened = engine.flattenLayers(childPrefix.layers)
                            owned += flattened
                            renderLayers += NativeRenderLayer(
                                store = flattened,
                                id = node.id.value,
                                blendMode = (node.blendMode.ordinal - 1).coerceIn(0, 26),
                                opacity = opacityByte(parentOpacity * node.opacity),
                                fillOpacity = opacityByte((fillOpacityPreview[node.id] ?: node.fillOpacity)),
                                visible = true,
                            )
                        }
                    }
                    continue
                }
                when (node.kind) {
                    LayerKind.PIXEL, LayerKind.SHAPE, LayerKind.ADJUSTMENT, LayerKind.TEXT, LayerKind.SMART_OBJECT, LayerKind.FILL -> stores[node.id]?.let { store ->
                        NativeLayerMaskBridge.setBlendIf(store, blendIfPreview[node.id] ?: node.blendIf)
                        renderLayers += NativeRenderLayer(
                            store = store,
                            id = node.id.value,
                            blendMode = (node.blendMode.ordinal - 1).coerceIn(0, 26),
                            opacity = opacityByte(parentOpacity * node.opacity),
                            fillOpacity = opacityByte((fillOpacityPreview[node.id] ?: node.fillOpacity)),
                            visible = visible,
                        )
                    }

                    else -> Unit
                }
            }
            return PrefixResult(renderLayers, foundTarget = false, resetOuterStack = false)
        }
        return try {
            val prefix = collect(tree.layers, true, 1f)
            require(prefix.foundTarget) { "Layer $target is not in the document" }
            NativeRenderLayerStack(prefix.layers, owned, usesTransparentBackdrop = prefix.resetOuterStack)
        } catch (failure: Throwable) {
            owned.forEach(engine::destroyTileStore)
            throw failure
        }
    }

    /** Shape raster caches are reconstructed from their persisted vector paths for each read. */
    private fun renderShapeStores(tree: LayerTree, size: DocSize = mutableInfo.value.size): Map<LayerId, Long> {
        val stores = mutableMapOf<LayerId, Long>()
        try {
            for (layer in tree.flatten().filter { it.kind == LayerKind.SHAPE && it.vectorPaths.isNotEmpty() }) {
                when (val rendered = engine.renderShapeLayer(layer.vectorPaths, size)) {
                    is ShapeLayerBridgeResult.Success -> stores[layer.id] = rendered.value.also { if (layer.clipped) NativeLayerMaskBridge.setClipped(it, true) }
                    is ShapeLayerBridgeResult.Failure -> error("Could not rasterize ${layer.name} for sampling: ${rendered.message}")
                }
            }
            return stores
        } catch (failure: Throwable) {
            stores.values.forEach(engine::destroyTileStore)
            throw failure
        }
    }

    /** Capture a COW store snapshot under the document writer lock, then compose off-thread; only the newest installs. */
    private fun queueViewportComposite(tree: LayerTree, eventSequence: Long? = null) {
        if (!Thread.holdsLock(lock)) {
            synchronized(lock) { queueViewportComposite(tree, eventSequence) }
            return
        }
        val completionSequence = eventSequence ?: pendingRenderSequence
        if (closed) return
        refineGeneration += 1
        if (tree.flatten().none { it.kind in STORE_BACKED_KINDS || it.kind == LayerKind.SHAPE }) {
            previewRenderCancellationId.takeIf { it != 0L }?.let(engine::cancelRender)
            previewRenderJob?.cancel()
            previewRenderJob = null
            previewRenderGeneration += 1
            completionSequence?.let { completeRenderEvent(it, EngineEvent.Rendered(it)) }
            return
        }
        val renderSize = mutableInfo.value.size
        val snapshots = clonePixelStores(pixelStores, tree) ?: run {
            completionSequence?.let { completeRenderEvent(it, EngineEvent.Failed(it, "Could not snapshot pixels for preview")) }
            return
        }
        registerNativeReadSnapshot()

        val maskOverlaySource = viewportMaskOverlaySource()
        previewRenderCancellationId.takeIf { it != 0L }?.let(engine::cancelRender)
        previewRenderJob?.cancel()
        val generation = ++previewRenderGeneration
        val cancellationId = engine.createRenderCancellation()
        previewRenderCancellationId = cancellationId
        val ready = CompletableDeferred<Unit>()
        previewReadyGeneration = generation
        previewReady = ready
        val shapeStores = mutableMapOf<LayerId, Long>()
        var groupStack: NativeRenderLayerStack? = null
        var maskOverlayStore = 0L
        val job = previewRenderScope.launch {
            delay(VIEWPORT_PREVIEW_COALESCE_MILLIS)
            currentCoroutineContext().ensureActive()
            if (synchronized(lock) { closed || generation != previewRenderGeneration }) return@launch
            val maskOverlay = maskOverlaySource?.let(::buildViewportMaskOverlay)
            if (maskOverlay != null) maskOverlayStore = maskOverlay.first
            currentCoroutineContext().ensureActive()
            if (synchronized(lock) { closed || generation != previewRenderGeneration }) return@launch
            val width = renderSize.width
            val height = renderSize.height
            var renderFailure: String? = null
            for (layer in tree.flatten().filter { it.kind == LayerKind.SHAPE && it.vectorPaths.isNotEmpty() }) {
                when (val result = engine.renderShapeLayer(layer.vectorPaths, DocSize(width, height))) {
                    is ShapeLayerBridgeResult.Success -> shapeStores[layer.id] = result.value.also { if (layer.clipped) NativeLayerMaskBridge.setClipped(it, true) }

                    is ShapeLayerBridgeResult.Failure -> {
                        renderFailure = "Could not render shape layer ${layer.name}: ${result.message}"
                        android.util.Log.w("CropTopEngine", renderFailure)
                    }
                }
            }
            val renderLayers = collectRenderLayerStack(tree, snapshots + shapeStores).also { groupStack = it }.layers
            if (renderLayers.isEmpty()) {
                renderFailure = renderFailure ?: "There are no renderable layers in this document"
                completionSequence?.let { completeRenderEvent(it, EngineEvent.Failed(it, renderFailure!!)) }
                return@launch
            }
            suspend fun render(maxDimension: Int): Bitmap? {
                val bitmap = runCatching {
                    engine.renderCompositePreview(
                        width, height, renderLayers, maxDimension, virtualBackgroundColor,
                        cancellationId = cancellationId,
                        renderSession = compositeRenderSession,
                    )?.also { bitmap ->
                        maskOverlay?.let { (mask, mode) -> engine.applyMaskOverlay(bitmap, mask, mode, quickMaskOverlayColorState.value) }
                    }
                }.getOrNull()
                currentCoroutineContext().ensureActive()
                return bitmap
            }
            fun publish(composed: Bitmap?): List<CompletableDeferred<Boolean>> {
                if (composed == null) return emptyList()
                val framePosts = mutableListOf<CompletableDeferred<Boolean>>()
                synchronized(lock) {
                    if (closed || generation != previewRenderGeneration) {
                        composed.recycle()
                    } else {
                        val previous = viewportPreview
                        viewportPreview = composed
                        viewportPreviewIsComposite = true
                        completedPreviewRenderGeneration = generation
                        val presentation = PreviewPresentation(visible = true, opacity = 1f)
                        attachedViewports.toList().forEach { viewport ->
                            val posted = CompletableDeferred<Boolean>()
                            framePosts += posted
                            viewport.setPreview(composed) { wasPosted -> posted.complete(wasPosted) }
                            viewport.setPreviewPresentation(presentation)
                        }
                        if (previous !== composed) previous?.recycle()
                    }
                }
                return framePosts
            }
            val coarse = render(COARSE_VIEWPORT_PREVIEW_DIMENSION)
            if (coarse == null) {
                completionSequence?.let {
                    completeRenderEvent(it, EngineEvent.Failed(it, renderFailure ?: "Could not compose the document preview"))
                }
                return@launch
            }
            publish(coarse).forEach { it.await() }
            completionSequence?.let { sequence ->
                if (renderFailure == null) {
                    completeRenderEvent(sequence, EngineEvent.Rendered(sequence))
                } else {
                    completeRenderEvent(sequence, EngineEvent.Failed(sequence, renderFailure!!))
                }
            }
            // Keep the stroke overlay until the attached Surface has posted the first
            // committed composite. This acknowledges buffer submission, not display scanout.
            // The optional full-resolution refinement below remains asynchronous.
            ready.complete(Unit)
            if (viewportPreviewMipLevel(width, height, MAX_VIEWPORT_PREVIEW_DIMENSION) <
                viewportPreviewMipLevel(width, height, COARSE_VIEWPORT_PREVIEW_DIMENSION)
            ) {
                delay(VIEWPORT_REFINE_IDLE_MILLIS)
                publish(render(MAX_VIEWPORT_PREVIEW_DIMENSION))
            }
        }
        previewRenderJob = job
        job.invokeOnCompletion { failure ->
            engine.destroyRenderCancellation(cancellationId)
            synchronized(lock) {
                if (previewRenderCancellationId == cancellationId) previewRenderCancellationId = 0L
            }
            ready.complete(Unit)
            if (failure != null && failure !is kotlinx.coroutines.CancellationException) {
                completionSequence?.let {
                    completeRenderEvent(it, EngineEvent.Failed(it, failure.message ?: "Document preview failed"))
                }
            }
            releaseNativeReadSnapshot(snapshots)
            shapeStores.values.forEach(engine::destroyTileStore)
            groupStack?.close()
            if (maskOverlayStore != 0L && maskOverlayStore != maskOverlaySource?.store) {
                engine.destroyTileStore(maskOverlayStore)
            }
            maskOverlaySource?.let { engine.destroyTileStore(it.store) }
        }
    }

    private fun completeRenderEvent(sequence: Long, event: EngineEvent) {
        mutableEvents.tryEmit(event)
        synchronized(lock) {
            if (pendingRenderSequence == sequence) pendingRenderSequence = null
        }
    }

    private fun publishHistory() {
        mutableHistory.value = HistoryState(
            snapshots.map { HistoryEntry(it.label) },
            cursor,
            namedSnapshots.mapIndexed { index, snapshot -> HistorySnapshot(snapshot.name, index) },
        )
        mutableContentRevision.value++
    }

    private fun releaseUnreferencedStores() {
        val referenced = snapshots[cursor].tree.flatten().filter { it.kind in STORE_BACKED_KINDS }.mapTo(mutableSetOf()) { it.id }
        val stale = pixelStores.keys.filterNot(referenced::contains)
        stale.forEach { layerId -> pixelStores.remove(layerId)?.let(engine::destroyTileStore) }
    }

    /** Snapshot maps own separate native TileStore wrappers; the native tile buffers remain COW-shared. */
    private fun clonePixelStores(source: Map<LayerId, Long>, tree: LayerTree): Map<LayerId, Long>? {
        val clones = mutableMapOf<LayerId, Long>()
        // Every store-backed layer travels with history: pixels, rendered type, adjustment LUT carriers and smart
        // object caches. Only pixel layers must have one; shapes re-render from their paths.
        for (layer in tree.flatten().filter { it.kind in STORE_BACKED_KINDS }) {
            val sourceHandle = source[layer.id] ?: run {
                if (layer.kind != LayerKind.PIXEL) continue
                clones.values.forEach(engine::destroyTileStore)
                return null
            }
            val clone = engine.cloneTileStore(sourceHandle)
            if (clone == 0L) {
                clones.values.forEach(engine::destroyTileStore)
                return null
            }
            clones[layer.id] = clone
        }
        return clones
    }

    private fun destroySnapshot(snapshot: Snapshot) {
        snapshot.pixelStores.values.forEach(engine::destroyTileStore)
        engine.destroyTileStore(snapshot.selectionMask)
        engine.destroyTileStore(snapshot.lastSelectionMask)
    }

    private fun destroyNamedSnapshot(snapshot: NamedSnapshot) {
        snapshot.pixelStores.values.forEach(engine::destroyTileStore)
        engine.destroyTileStore(snapshot.selectionMask)
        engine.destroyTileStore(snapshot.lastSelectionMask)
    }

    private fun restoreCurrentPixelStores(): Boolean {
        val restored = clonePixelStores(snapshots[cursor].pixelStores, snapshots[cursor].tree) ?: return false
        val restoredSelection = cloneSelectionMask(snapshots[cursor].selectionMask)
        if (restoredSelection == null) {
            restored.values.forEach(engine::destroyTileStore)
            return false
        }
        val restoredLastSelection = cloneOptionalSelection(snapshots[cursor].lastSelectionMask)
        if (restoredLastSelection == null) {
            restored.values.forEach(engine::destroyTileStore)
            engine.destroyTileStore(restoredSelection)
            return false
        }
        pixelStores.values.forEach(engine::destroyTileStore)
        pixelStores.clear()
        pixelStores.putAll(restored)
        engine.destroyTileStore(selectionMask)
        selectionMask = restoredSelection
        engine.destroyTileStore(lastSelectionMask)
        lastSelectionMask = restoredLastSelection
        lastSelectionInfo = snapshots[cursor].lastSelectionInfo
        mutableSelection.value = snapshots[cursor].selectionInfo
        return true
    }

    private fun cloneSelectionMask(handle: Long): Long? = engine.cloneTileStore(handle).takeIf { it != 0L }

    private fun cloneOptionalSelection(handle: Long): Long? = if (handle == 0L) 0L else cloneSelectionMask(handle)

    private fun refreshCurrentPixelSnapshot() {
        val current = snapshots[cursor]
        val replacement = clonePixelStores(pixelStores, current.tree) ?: error("Could not snapshot imported pixels")
        current.pixelStores.values.forEach(engine::destroyTileStore)
        snapshots[cursor] = current.copy(pixelStores = replacement)
    }

    private fun reject(code: RejectionCode, message: String) = CommandResult.Rejected(code, message)

    companion object {
        private const val MAX_WORKING_ICC_BYTES = 16 * 1024 * 1024
        private const val MAX_NATIVE_GEOMETRY_DIMENSION = 65_535
        private const val GEOMETRY_CANVAS_SIZE = 0
        private const val GEOMETRY_FLIP_HORIZONTAL = 1
        private const val GEOMETRY_FLIP_VERTICAL = 2
        private const val GEOMETRY_ROTATE = 3
        private const val GEOMETRY_RESIZE = 4
        private const val GEOMETRY_CROP = 5
        private const val PIXEL_FORMAT_A16 = 4
        private const val RESAMPLE_NEAREST = 0
        private const val RESAMPLE_BILINEAR = 1
        private const val RESAMPLE_BICUBIC = 2
        private const val RESAMPLE_LANCZOS = 3
        private const val HISTOGRAM_DEBOUNCE_MILLIS = 120L
        private val NATIVE_DESTRUCTIVE_ADJUSTMENTS = setOf(
            "brightnessContrast",
            "exposure",
            "vibrance",
            "hueSaturation",
            "colorBalance",
            "blackWhite",
            "photoFilter",
            "invert",
            "posterize",
            "threshold",
            "channelMixer",
            "gradientMap",
            "selectiveColor",
            "shadowsHighlights",
            "levels",
            "curves",
        )
        private const val MAX_STROKE_SAMPLES = 1_000_000
        private const val MAX_SELECTION_VERTICES = 1_000_000
        private const val MAX_SNAPSHOT_NAME_LENGTH = 120
        private const val MAX_LAYER_THUMBNAIL_DIMENSION = 512
        private const val MOVE_LAYER_HIT_TOLERANCE = 4.0
        private val SUPPORTED_SAMPLE_SIZES = setOf(1, 3, 5, 11, 31, 51, 101)
    }
}

private fun translatePreview(source: Bitmap, dx: Float, dy: Float, documentSize: DocSize): Bitmap =
    Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { destination ->
        val scaleX = source.width.toFloat() / documentSize.width
        val scaleY = source.height.toFloat() / documentSize.height
        val canvas = Canvas(destination)
        canvas.drawBitmap(
            source,
            null,
            RectF(
                dx * scaleX,
                dy * scaleY,
                (documentSize.width + dx) * scaleX,
                (documentSize.height + dy) * scaleY,
            ),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )
    }

/** Non-blocking document surface renderer. It presents a bounded preview while native tile rendering is integrated. */
internal class SurfaceDocumentViewport(
    private val surface: Surface,
    private val documentSize: DocSize,
    preview: Bitmap?,
    initialPreviewPresentation: PreviewPresentation,
    initialViewport: ViewportState,
    private val onVulkanReady: (String, Int) -> Unit,
    /** Called when the view moved or the picture behind it changed, so the document can re-render what is visible sharply. */
    private val onViewportChanged: (SurfaceDocumentViewport, Boolean) -> Unit = { _, _ -> },
    private val onDetach: () -> Unit,
) : Viewport {
    private val thread = HandlerThread("CropTopDocumentViewport").apply { start() }
    private val handler = Handler(thread.looper)
    private val stateLock = Any()
    private var state = initialViewport
    private var overlays = OverlayState()
    private var previewPresentation = initialPreviewPresentation

    @Volatile private var preview: Bitmap? = preview?.copy(Bitmap.Config.ARGB_8888, true)
    private var pendingPreview: Bitmap? = null

    /** A region of the picture re-rendered at its own resolution (drawn over the preview; dropped whenever the picture changes). */
    private class Refined(val region: app.croptop.core.model.DocRect, val bitmap: Bitmap)

    @Volatile private var refined: Refined? = null

    fun setRefined(region: app.croptop.core.model.DocRect, bitmap: Bitmap) {
        if (detached) return
        refined = Refined(region, bitmap)
        scheduleDraw()
    }

    fun clearRefined() {
        if (refined == null) return
        refined = null
        scheduleDraw()
    }

    fun currentState(): ViewportState = synchronized(stateLock) { state }

    fun isDetached(): Boolean = detached

    /** A soft-proof / display LUT the frames are run through before they are shown (null = as composed). */
    @Volatile private var displayLut: FloatArray? = null

    fun setDisplayLut(lut: FloatArray?) {
        displayLut = lut
        scheduleDraw()
    }
    private val pendingPreviewCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var vulkanRenderer: NativeVulkanFrameBridge? = null
    private var viewportFrame: Bitmap? = null
    private var rgbaPixels: IntArray? = null
    private var rgbaFrame: ByteBuffer? = null
    private var loggedVulkanDimensions: Pair<Int, Int>? = null
    private var vulkanInitializationAttempted = false

    @Volatile private var detached = false
    private val frameScheduled = AtomicBoolean(false)
    private var selectionAnimationScheduled = false
    private val selectionAnimationTick = Runnable {
        selectionAnimationScheduled = false
        scheduleDraw()
    }
    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled.set(false)
        if (!detached) {
            drawLatest()
            val shouldAnimate = surface.isValid && synchronized(stateLock) {
                overlays.showSelection && overlays.selectionBounds.isNotEmpty()
            }
            if (shouldAnimate && !selectionAnimationScheduled) {
                selectionAnimationScheduled = true
                handler.postDelayed(selectionAnimationTick, SELECTION_FRAME_INTERVAL_MS)
            }
        }
    }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val checkerPaint = Paint()
    private val pixelGridPaint = Paint().apply {
        color = Color.argb(96, 0, 0, 0)
        style = Paint.Style.STROKE
        isAntiAlias = false
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    init {
        // Let SurfaceView finish its first measured/presentable frame before binding a Vulkan surface.
        scheduleDraw()
    }

    override fun update(state: ViewportState) {
        if (detached) return
        val moved = synchronized(stateLock) { (this.state != state).also { this.state = state } }
        scheduleDraw()
        if (moved) onViewportChanged(this, false)
    }

    override fun setOverlays(overlays: OverlayState) {
        if (detached) return
        synchronized(stateLock) { this.overlays = overlays }
        scheduleDraw()
    }

    fun setPreviewPresentation(presentation: PreviewPresentation) {
        if (detached) return
        synchronized(stateLock) { previewPresentation = presentation }
        refined = null
        scheduleDraw()
        onViewportChanged(this, true)
    }

    fun setPreview(bitmap: Bitmap, onFramePosted: (Boolean) -> Unit = {}) {
        if (detached) {
            onFramePosted(false)
            return
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: run {
            onFramePosted(false)
            return
        }
        synchronized(stateLock) {
            if (detached) {
                copy.recycle()
                onFramePosted(false)
                return
            }
            pendingPreview?.recycle()
            pendingPreview = copy
            pendingPreviewCallbacks += onFramePosted
        }
        scheduleDraw()
    }

    private fun scheduleDraw() {
        if (detached || !frameScheduled.compareAndSet(false, true)) return
        if (!handler.post {
                if (detached) {
                    frameScheduled.set(false)
                } else {
                    // Coalesce document and viewport updates to the next display frame.
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
            }
        ) {
            frameScheduled.set(false)
        }
    }

    private fun drawLatest() {
        if (detached) return
        var swapped = false
        val (previousPreview, frameCallbacks) = synchronized(stateLock) {
            val pending = pendingPreview
            pendingPreview = null
            if (pending == null) {
                null to emptyList()
            } else {
                swapped = true
                val callbacks = pendingPreviewCallbacks.toList()
                pendingPreviewCallbacks.clear()
                // The picture changed: a sharp region rendered from the old pixels would be stale, so it goes until the new one is ready.
                refined = null
                preview.also { preview = pending } to callbacks
            }
        }
        if (swapped) onViewportChanged(this, true)
        if (!surface.isValid) {
            previousPreview?.recycle()
            frameCallbacks.forEach { it(false) }
            return
        }
        val (requestedState, currentOverlays, currentPresentation) = synchronized(stateLock) {
            Triple(state, overlays, previewPresentation)
        }
        val renderer = vulkanRenderer ?: if (!vulkanInitializationAttempted && requestedState.viewWidth > 0 && requestedState.viewHeight > 0) {
            vulkanInitializationAttempted = true
            val candidate = runCatching { NativeVulkanFrameBridge(surface) }.getOrNull()
            if (candidate?.available == true) {
                vulkanRenderer = candidate
                onVulkanReady(candidate.gpuName, candidate.vulkanApiVersion)
                android.util.Log.i("CropTopVulkan", "Document viewport Vulkan renderer initialized after surface measurement")
                candidate
            } else {
                candidate?.close()
                android.util.Log.i("CropTopVulkan", "Vulkan document renderer unavailable; using Surface Canvas")
                null
            }
        } else {
            null
        }
        if (renderer != null && requestedState.viewWidth > 0 && requestedState.viewHeight > 0) {
            val width = requestedState.viewWidth
            val height = requestedState.viewHeight
            ensureViewportFrame(width, height)
            val frame = viewportFrame
            val pixels = rgbaPixels
            val bytes = rgbaFrame
            if (frame != null && pixels != null && bytes != null) {
                renderViewportFrame(Canvas(frame), requestedState, currentOverlays, currentPresentation)
                frame.getPixels(pixels, 0, width, 0, 0, width, height)
                displayLut?.let { NativeColorManagementBridge.applyLutArgb(pixels, width * height, it, SOFT_PROOF_LUT_SIZE) }
                bytes.clear()
                pixels.forEach { pixel ->
                    bytes.put((pixel shr 16).toByte())
                    bytes.put((pixel shr 8).toByte())
                    bytes.put(pixel.toByte())
                    bytes.put((pixel ushr 24).toByte())
                }
                bytes.flip()
                if (renderer.drawFrame(bytes, width, height)) {
                    if (loggedVulkanDimensions != (width to height)) {
                        loggedVulkanDimensions = width to height
                        android.util.Log.i("CropTopVulkan", "Document viewport frame presented through Vulkan (${width}x$height)")
                    }
                    previousPreview?.recycle()
                    frameCallbacks.forEach { it(true) }
                    return
                }
            }
            if (detached || !surface.isValid) {
                previousPreview?.recycle()
                frameCallbacks.forEach { it(false) }
                return
            }
            android.util.Log.w("CropTopVulkan", "Vulkan document viewport presentation failed; switching to Surface Canvas")
            renderer.close()
            vulkanRenderer = null
        }
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (_: IllegalArgumentException) {
            previousPreview?.recycle()
            frameCallbacks.forEach { it(false) }
            return
        } catch (_: IllegalStateException) {
            previousPreview?.recycle()
            frameCallbacks.forEach { it(false) }
            return
        }
        var framePosted = false
        try {
            val (currentState, currentOverlays, currentPresentation) = synchronized(stateLock) {
                Triple(state.copy(viewWidth = canvas.width, viewHeight = canvas.height), overlays, previewPresentation)
            }
            renderViewportFrame(canvas, currentState, currentOverlays, currentPresentation)
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
                framePosted = true
            } catch (_: IllegalArgumentException) {
                // Surface can be destroyed between lock and post during window changes.
            } catch (_: IllegalStateException) {
                // Ignore an in-flight frame when Android tears down the surface.
            }
            previousPreview?.recycle()
        }
        frameCallbacks.forEach { it(framePosted) }
    }

    private fun ensureViewportFrame(width: Int, height: Int) {
        if (viewportFrame?.width == width && viewportFrame?.height == height) return
        viewportFrame?.recycle()
        viewportFrame = null
        rgbaPixels = null
        rgbaFrame = null
        val pixelCount = width.toLong() * height
        if (width <= 0 || height <= 0 || pixelCount > MAX_VULKAN_VIEWPORT_PIXELS) return
        viewportFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
        rgbaPixels = IntArray(pixelCount.toInt())
        rgbaFrame = ByteBuffer.allocateDirect((pixelCount * 4L).toInt())
    }

    private fun renderViewportFrame(canvas: Canvas, view: ViewportState, overlays: OverlayState, presentation: PreviewPresentation) {
        canvas.drawColor(Color.rgb(42, 43, 46))
        canvas.save()
        canvas.translate(view.panX, view.panY)
        canvas.rotate(view.rotationDegrees)
        canvas.scale(if (view.flipHorizontal) -view.zoom else view.zoom, view.zoom)
        canvas.clipRect(0f, 0f, documentSize.width.toFloat(), documentSize.height.toFloat())
        drawTransparencyGrid(canvas, view)
        preview?.takeIf { presentation.visible && presentation.opacity > 0f }?.let {
            imagePaint.isFilterBitmap = !viewportUsesNearestNeighbor(view.zoom)
            imagePaint.alpha = (presentation.opacity * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(it, null, RectF(0f, 0f, documentSize.width.toFloat(), documentSize.height.toFloat()), imagePaint)
            imagePaint.alpha = 255
            val sharp = refined?.takeIf { presentation.opacity >= 1f }
            if (sharp != null) {
                val r = sharp.region
                canvas.drawBitmap(sharp.bitmap, null, RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), imagePaint)
            }
            if (viewportShowsPixelGrid(view.zoom, documentSize.width, documentSize.height, it.width, it.height) || (sharp != null && view.zoom >= 8f)) {
                drawDocumentPixelGrid(canvas, view.zoom)
            }
        }
        if (overlays.showSelection && overlays.selectionBounds.isNotEmpty()) drawSelectionBounds(canvas, overlays)
        if (overlays.showGuides && overlays.guides.isNotEmpty()) drawGuides(canvas, overlays)
        canvas.restore()
    }

    companion object {
        private const val SELECTION_FRAME_INTERVAL_MS = 80L
        private const val MAX_VULKAN_VIEWPORT_PIXELS = 4_194_304L
    }

    /** Draw marching-ants selection edges in document coordinates in the same surface pass as pixels. */
    private fun drawSelectionBounds(canvas: Canvas, overlays: OverlayState) {
        val phase = (SystemClock.uptimeMillis() / 80L % 8L).toFloat()
        for (bounds in overlays.selectionBounds) {
            val rect = RectF(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
            selectionPaint.color = Color.BLACK
            selectionPaint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), phase)
            canvas.drawRect(rect, selectionPaint)
            selectionPaint.color = Color.WHITE
            selectionPaint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), phase + 4f)
            canvas.drawRect(rect, selectionPaint)
        }
        selectionPaint.pathEffect = null
    }

    /** Draw one-pixel grid lines only when the viewport bitmap still maps 1:1 to source pixels. */
    private fun drawDocumentPixelGrid(canvas: Canvas, zoom: Float) {
        val visible = Rect()
        if (!canvas.getClipBounds(visible)) return
        val left = visible.left.coerceAtLeast(0)
        val top = visible.top.coerceAtLeast(0)
        val right = visible.right.coerceAtMost(documentSize.width)
        val bottom = visible.bottom.coerceAtMost(documentSize.height)
        if (left >= right || top >= bottom) return
        pixelGridPaint.strokeWidth = (1f / zoom).coerceAtLeast(0.02f)
        for (x in left + 1 until right) {
            canvas.drawLine(x.toFloat(), top.toFloat(), x.toFloat(), bottom.toFloat(), pixelGridPaint)
        }
        for (y in top + 1 until bottom) {
            canvas.drawLine(left.toFloat(), y.toFloat(), right.toFloat(), y.toFloat(), pixelGridPaint)
        }
    }

    /** Guides use the same document transform as image pixels and selection bounds. */
    private fun drawGuides(canvas: Canvas, overlays: OverlayState) {
        for (guide in overlays.guides) {
            if (!guide.position.isFinite()) continue
            selectionPaint.pathEffect = null
            selectionPaint.strokeWidth = 1f
            selectionPaint.color = Color.rgb(48, 220, 255)
            when (guide.axis) {
                GuideAxis.HORIZONTAL -> canvas.drawLine(0f, guide.position, documentSize.width.toFloat(), guide.position, selectionPaint)
                GuideAxis.VERTICAL -> canvas.drawLine(guide.position, 0f, guide.position, documentSize.height.toFloat(), selectionPaint)
            }
        }
    }

    private fun drawTransparencyGrid(canvas: Canvas, viewport: ViewportState) {
        val corners = arrayOf(
            ViewPoint(0f, 0f),
            ViewPoint(viewport.viewWidth.toFloat(), 0f),
            ViewPoint(0f, viewport.viewHeight.toFloat()),
            ViewPoint(viewport.viewWidth.toFloat(), viewport.viewHeight.toFloat()),
        ).map(viewport::viewToDoc)
        val cell = 24f / viewport.zoom.coerceAtLeast(0.01f)
        val left = (corners.minOf { it.x }.coerceAtLeast(0f) / cell).toInt() * cell
        val top = (corners.minOf { it.y }.coerceAtLeast(0f) / cell).toInt() * cell
        val right = corners.maxOf { it.x }.coerceAtMost(documentSize.width.toFloat())
        val bottom = corners.maxOf { it.y }.coerceAtMost(documentSize.height.toFloat())
        var y = top
        var row = (top / cell).toInt()
        while (y < bottom) {
            val nextY = minOf(y + cell, bottom)
            var x = left
            var column = (left / cell).toInt()
            while (x < right) {
                val nextX = minOf(x + cell, right)
                checkerPaint.color = if ((row + column) and 1 == 0) Color.rgb(205, 206, 209) else Color.rgb(164, 166, 170)
                canvas.drawRect(x, y, nextX, nextY, checkerPaint)
                x = nextX
                column++
            }
            y = nextY
            row++
        }
    }

    override fun detach() {
        if (detached) return
        detached = true
        frameScheduled.set(false)
        handler.removeCallbacksAndMessages(null)
        val completed = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                selectionAnimationScheduled = false
                val callbacks = synchronized(stateLock) {
                    preview?.recycle()
                    preview = null
                    pendingPreview?.recycle()
                    pendingPreview = null
                    pendingPreviewCallbacks.toList().also { pendingPreviewCallbacks.clear() }
                }
                vulkanRenderer?.close()
                vulkanRenderer = null
                viewportFrame?.recycle()
                viewportFrame = null
                rgbaPixels = null
                rgbaFrame = null
                callbacks.forEach { it(false) }
                onDetach()
            } finally {
                thread.quitSafely()
                completed.countDown()
            }
        }
        if (Thread.currentThread() === thread) {
            cleanup.run()
        } else if (handler.post(cleanup)) {
            var interrupted = false
            while (true) {
                try {
                    completed.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        } else {
            cleanup.run()
        }
    }
}

internal data class PreviewPresentation(val visible: Boolean, val opacity: Float)

private const val LIQUIFY_MAX_FIELD_FLOATS = 16_000_000L

/** Selects a bounded displacement mesh; when a narrow selection conflicts with the cap, the caller expands only the field mask. */
internal fun liquifyMeshSpacingForBudget(width: Int, height: Int, requestedSpacing: Int, selectionBounds: IntArray? = null): Int {
    require(width > 0 && height > 0 && requestedSpacing in 1..64)
    var spacing = requestedSpacing
    if (selectionBounds?.size == 4) {
        spacing = minOf(spacing, (selectionBounds[2] - selectionBounds[0]).coerceAtLeast(1), (selectionBounds[3] - selectionBounds[1]).coerceAtLeast(1))
    }
    fun fieldFloats(step: Int): Long {
        val columns = (width.toLong() + step - 1) / step + 1
        val rows = (height.toLong() + step - 1) / step + 1
        return columns * rows * 2
    }
    while (fieldFloats(spacing) > LIQUIFY_MAX_FIELD_FLOATS && spacing < 64) spacing++
    check(fieldFloats(spacing) <= LIQUIFY_MAX_FIELD_FLOATS) { "Liquify displacement field exceeds its memory budget" }
    return spacing
}

internal fun LayerTree.importedSourcePresentation(target: LayerId): PreviewPresentation {
    fun find(nodes: List<LayerNode>, parentVisible: Boolean, parentOpacity: Float): PreviewPresentation? {
        for (node in nodes) {
            val visible = parentVisible && node.visible
            val opacity = parentOpacity * node.opacity * node.fillOpacity
            if (node.id == target) return PreviewPresentation(visible, opacity.coerceIn(0f, 1f))
            find(node.children, visible, opacity)?.let { return it }
        }
        return null
    }
    return find(layers, true, 1f) ?: PreviewPresentation(visible = false, opacity = 0f)
}

private val EMPTY_RAW_METADATA = NativeRawBridge.Metadata("", "", "", "", "", "", 0f, 0f, 0f, 0f, 0L, 1, false, 0)
