package app.croptop.core.engine

import app.croptop.core.model.DevelopSettings

/**
 * Camera raw files (native LibRaw front end): metadata probe, embedded JPEG preview, and opening as an upright
 * sRGB layer. Usage: [probe] to decide (decodable? which camera and lens), [embeddedPreview] for library thumbnails,
 * [open] to get a native tile store for the document's first layer (destroy it with
 * `NativeSelectionBridge.destroyStore`). [open] runs for seconds on large sensors: give it a [CancelToken] and poll
 * [CancelToken.progress] from another thread.
 */
internal object NativeRawBridge {
    /** Cooperative cancel flag and progress for one [open] call. Close it when the call has returned. */
    class CancelToken : AutoCloseable {
        private var handle = nativeNewCancelToken()
        internal val id: Long get() = handle
        fun cancel() = nativeCancel(handle)
        val progress: Float get() = if (handle == 0L) 1f else nativeProgress(handle)
        override fun close() {
            if (handle != 0L) nativeFreeCancelToken(handle)
            handle = 0L
        }
    }

    data class Metadata(
        val make: String,
        val model: String,
        val normalizedMake: String,
        val normalizedModel: String,
        val lens: String,
        val software: String,
        val iso: Float,
        val shutterSeconds: Float,
        val aperture: Float,
        val focalLengthMm: Float,
        val timestamp: Long,
        /** EXIF orientation 1–8. */
        val orientation: Int,
        val isDng: Boolean,
        val rawCount: Int,
    )

    data class Probe(
        val metadata: Metadata,
        /** Active sensor area, before orientation. */
        val width: Int,
        val height: Int,
        val rawWidth: Int,
        val rawHeight: Int,
        val xtrans: Boolean,
        /** False for Foveon, linear/monochrome DNGs and other rasters the mosaic path cannot take. */
        val decodable: Boolean,
        val unsupportedReason: String,
    )

    class Opened(val storeHandle: Long, val width: Int, val height: Int, val bitDepth: Int, val metadata: Metadata, val warnings: List<String>)

    /** Develop BasicTone values applied before display encoding; all zero is neutral. */
    data class Tone(
        val exposure: Float = 0f,
        val contrast: Float = 0f,
        val highlights: Float = 0f,
        val shadows: Float = 0f,
        val whites: Float = 0f,
        val blacks: Float = 0f,
        val texture: Float = 0f,
        val clarity: Float = 0f,
        val dehaze: Float = 0f,
        val vibrance: Float = 0f,
        val saturation: Float = 0f,
    ) {
        internal fun toArray() = floatArrayOf(exposure, contrast, highlights, shadows, whites, blacks, texture, clarity, dehaze, vibrance, saturation)
    }

    /** Reads only headers and metadata. Throws [IllegalArgumentException] with LibRaw's reason for non-raw files. */
    fun probe(bytes: ByteArray, size: Int = bytes.size): Probe {
        val pairs = nativeProbe(bytes, size) ?: throw IllegalArgumentException(nativeLastError().ifBlank { "Not a supported raw file" })
        val info = toMap(pairs)
        return Probe(
            metadata = metadataOf(info),
            width = info.int("width"),
            height = info.int("height"),
            rawWidth = info.int("rawWidth"),
            rawHeight = info.int("rawHeight"),
            xtrans = info["xtrans"] == "true",
            decodable = info["decodable"] == "true",
            unsupportedReason = info["unsupportedReason"].orEmpty(),
        )
    }

    /** The largest embedded JPEG with a long edge of at least [minLongEdge] px, or null when the file has none. */
    fun embeddedPreview(bytes: ByteArray, size: Int = bytes.size, minLongEdge: Int = 0): ByteArray? = nativePreview(bytes, size, minLongEdge)

    /** Serialize the currently exposed BasicTone values as a Lightroom/Camera Raw compatible XMP sidecar. */
    fun writeSidecar(tone: Tone): String =
        nativeWriteSidecar(tone.toArray()) ?: throw IllegalStateException(nativeLastError().ifBlank { "Could not write RAW XMP sidecar" })

    /** Serialize the pipeline-supported portions of the complete Develop recipe as an Adobe-compatible XMP sidecar. */
    fun writeSidecar(settings: DevelopSettings): String =
        nativeWriteDevelopSidecar(NativeDevelopBridge.encode(settings))
            ?: throw IllegalStateException(nativeLastError().ifBlank { "Could not write RAW XMP sidecar" })

    /** Read supported BasicTone values from a CropTop or Adobe Camera Raw XMP packet. */
    fun readSidecar(xmp: String): Tone {
        val values = nativeReadSidecar(xmp)
            ?: throw IllegalArgumentException(nativeLastError().ifBlank { "Could not read RAW XMP sidecar" })
        require(values.size == 11) { "Native XMP reader returned an invalid tone" }
        return Tone(
            exposure = values[0], contrast = values[1], highlights = values[2], shadows = values[3], whites = values[4],
            blacks = values[5], texture = values[6], clarity = values[7], dehaze = values[8], vibrance = values[9],
            saturation = values[10],
        )
    }

    /** Read the pipeline-supported portion of a CropTop or Adobe Camera Raw XMP recipe. */
    fun readDevelopSidecar(xmp: String): DevelopSettings {
        val values = nativeReadDevelopSidecar(xmp)
            ?: throw IllegalArgumentException(nativeLastError().ifBlank { "Could not read RAW XMP sidecar" })
        return NativeDevelopBridge.decode(values)
    }

    /** Parse supported XMP settings and apply them in the RAW Develop pipeline before the image is encoded. */
    fun openWithSidecar(
        bytes: ByteArray,
        xmp: String,
        size: Int = bytes.size,
        sixteenBit: Boolean = true,
        applyOrientation: Boolean = true,
        workerThreads: Int = 0,
        cancel: CancelToken? = null,
        lensDatabase: NativeLensProfileBridge.Database? = null,
    ): Opened = open(
        bytes = bytes,
        size = size,
        sixteenBit = sixteenBit,
        applyOrientation = applyOrientation,
        settings = readDevelopSidecar(xmp),
        workerThreads = workerThreads,
        cancel = cancel,
        lensDatabase = lensDatabase,
    )

    /** Opens the raw as an upright sRGB layer, applying either [tone] or pipeline-supported [settings]. */
    fun open(
        bytes: ByteArray,
        size: Int = bytes.size,
        sixteenBit: Boolean = true,
        applyOrientation: Boolean = true,
        tone: Tone? = null,
        settings: DevelopSettings? = null,
        workerThreads: Int = 0,
        cancel: CancelToken? = null,
        lensDatabase: NativeLensProfileBridge.Database? = null,
    ): Opened {
        require(tone == null || settings == null) { "Pass either BasicTone or full DevelopSettings, not both" }
        val developValues = settings?.let(NativeDevelopBridge::encode) ?: tone?.toArray()
        val values = nativeOpen(bytes, size, sixteenBit, applyOrientation, developValues, workerThreads, cancel?.id ?: 0L, lensDatabase?.id ?: 0L)
            ?: throw IllegalStateException(nativeLastError().ifBlank { "Could not open the raw file" })
        val info = toMap(nativeLastInfo())
        return Opened(
            storeHandle = values[0],
            width = values[1].toInt(),
            height = values[2].toInt(),
            bitDepth = values[3].toInt(),
            metadata = metadataOf(info),
            warnings = info["warnings"].orEmpty().lines().filter(String::isNotBlank),
        )
    }

    /** A decoded RAW kept in memory for slider-speed Develop previews; close it when the screen goes away. */
    class DevelopSession internal constructor(private var handle: Long, val width: Int, val height: Int, val warnings: List<String>) : AutoCloseable {
        /** [width], [height] and ARGB [pixels] of one preview. */
        class Preview(val width: Int, val height: Int, val pixels: IntArray, val warnings: List<String>)

        @Synchronized
        fun render(settings: DevelopSettings, maxDimension: Int, workerThreads: Int = 0): Preview {
            check(handle != 0L) { "Develop session is closed" }
            val raw = nativeSessionRender(handle, NativeDevelopBridge.encode(settings), maxDimension, workerThreads)
                ?: throw IllegalStateException(nativeLastError().ifBlank { "Develop preview failed" })
            val info = toMap(nativeLastInfo())
            return Preview(raw[0], raw[1], raw.copyOfRange(2, raw.size), info["warnings"].orEmpty().lines().filter(String::isNotBlank))
        }

        /** The red coverage of `settings.masks[maskIndex]` over the same frame [render] returns for [maxDimension]. */
        @Synchronized
        fun maskOverlay(settings: DevelopSettings, maskIndex: Int, maxDimension: Int, workerThreads: Int = 0): Preview {
            check(handle != 0L) { "Develop session is closed" }
            val raw = nativeSessionMaskOverlay(handle, NativeDevelopBridge.encode(settings), maskIndex, maxDimension, workerThreads)
                ?: throw IllegalStateException(nativeLastError().ifBlank { "Mask overlay failed" })
            return Preview(raw[0], raw[1], raw.copyOfRange(2, raw.size), emptyList())
        }

        @Synchronized
        override fun close() {
            if (handle != 0L) nativeSessionClose(handle)
            handle = 0L
        }
    }

    fun openSession(bytes: ByteArray, size: Int = bytes.size, workerThreads: Int = 0, lensDatabase: NativeLensProfileBridge.Database? = null): DevelopSession {
        val handle = nativeSessionOpen(bytes, size, workerThreads, lensDatabase?.id ?: 0L)
        if (handle == 0L) throw IllegalStateException(nativeLastError().ifBlank { "Could not open the raw file for Develop" })
        val info = toMap(nativeLastInfo())
        return DevelopSession(handle, info.int("width"), info.int("height"), info["warnings"].orEmpty().lines().filter(String::isNotBlank))
    }

    private external fun nativeSessionOpen(bytes: ByteArray, size: Int, workerThreads: Int, lensDatabase: Long): Long
    private external fun nativeSessionRender(handle: Long, settings: FloatArray, maxDimension: Int, workerThreads: Int): IntArray?
    private external fun nativeSessionMaskOverlay(handle: Long, settings: FloatArray, maskIndex: Int, maxDimension: Int, workerThreads: Int): IntArray?
    private external fun nativeSessionClose(handle: Long)

    private fun toMap(pairs: Array<String>): Map<String, String> = buildMap {
        var i = 0
        while (i + 1 < pairs.size) {
            put(pairs[i], pairs[i + 1])
            i += 2
        }
    }

    private fun Map<String, String>.int(key: String) = this[key]?.toIntOrNull() ?: 0
    private fun Map<String, String>.float(key: String) = this[key]?.toFloatOrNull() ?: 0f

    private fun metadataOf(info: Map<String, String>) = Metadata(
        make = info["make"].orEmpty(),
        model = info["model"].orEmpty(),
        normalizedMake = info["normalizedMake"].orEmpty(),
        normalizedModel = info["normalizedModel"].orEmpty(),
        lens = info["lens"].orEmpty(),
        software = info["software"].orEmpty(),
        iso = info.float("iso"),
        shutterSeconds = info.float("shutterSeconds"),
        aperture = info.float("aperture"),
        focalLengthMm = info.float("focalLengthMm"),
        timestamp = info["timestamp"]?.toLongOrNull() ?: 0L,
        orientation = info.int("orientation").coerceIn(1, 8),
        isDng = info["isDng"] == "true",
        rawCount = info.int("rawCount"),
    )

    private external fun nativeLastError(): String
    private external fun nativeLastInfo(): Array<String>
    private external fun nativeProbe(bytes: ByteArray, size: Int): Array<String>?
    private external fun nativePreview(bytes: ByteArray, size: Int, minLongEdge: Int): ByteArray?
    private external fun nativeWriteSidecar(tone: FloatArray): String?
    private external fun nativeReadSidecar(xmp: String): FloatArray?
    private external fun nativeWriteDevelopSidecar(settings: FloatArray): String?
    private external fun nativeReadDevelopSidecar(xmp: String): FloatArray?
    private external fun nativeNewCancelToken(): Long
    private external fun nativeCancel(token: Long)
    private external fun nativeProgress(token: Long): Float
    private external fun nativeFreeCancelToken(token: Long)
    private external fun nativeOpen(
        bytes: ByteArray,
        size: Int,
        sixteenBit: Boolean,
        applyOrientation: Boolean,
        tone: FloatArray?,
        workerThreads: Int,
        cancelToken: Long,
        lensDatabase: Long,
    ): LongArray?
}
