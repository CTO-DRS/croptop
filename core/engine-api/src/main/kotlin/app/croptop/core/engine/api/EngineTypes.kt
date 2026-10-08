package app.croptop.core.engine.api

import app.croptop.core.model.BlendMode
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.Guide
import app.croptop.core.model.LayerId
import app.croptop.core.model.RgbaColor
import kotlinx.serialization.Serializable

/** Field offsets for each packed FloatArray record accepted by [StrokeSession.add]. */
object StrokeSampleLayout {
    const val X = 0
    const val Y = 1
    const val PRESSURE = 2
    const val TILT_X = 3
    const val TILT_Y = 4
    const val ORIENTATION = 5
    const val TIME_SECONDS = 6
    const val STRIDE = 7
}

@Serializable
data class EngineCapabilities(
    val engineVersion: String,
    val abi: String,
    val cpuFeatures: Set<String> = emptySet(),
    val gpuName: String? = null,
    val vulkanApiVersion: Int? = null,
    val maxTextureDimension: Int = 0,
    val supportedDepthBits: Set<Int> = setOf(8),
    val features: Set<EngineFeature> = emptySet(),
)

@Serializable
enum class EngineFeature { TILES, LAYERS, VULKAN, COLOR_MANAGEMENT, RAW, AI }

@Serializable
sealed interface DocumentSource {
    /** Persisted SAF URI. The Android adapter owns opening and closing the descriptor. */
    @Serializable
    data class ContentUri(val value: String) : DocumentSource

    /** App-private path only; never pass an arbitrary user supplied filesystem path. */
    @Serializable
    data class AppFile(val path: String) : DocumentSource
}

@Serializable
data class OpenOptions(
    val recoverJournal: Boolean = true,
    val preserveUnknownData: Boolean = true,
    /** Applies this non-destructive recipe while decoding a RAW source; ignored for raster and project documents. */
    val rawDevelopSettings: app.croptop.core.model.DevelopSettings? = null,
    /** Opens a RAW as a smart object whose Develop settings stay re-editable ([DocumentHandle.setRawDevelopSettings]); otherwise flattened. */
    val rawAsSmartObject: Boolean = false,
)

@Serializable
sealed interface SaveTarget {
    @Serializable
    data class ContentUri(val value: String) : SaveTarget

    @Serializable
    data class AppFile(val path: String) : SaveTarget
}

@Serializable
data class SaveOptions(val includeHistory: Boolean = false, val compact: Boolean = false)

@Serializable
sealed interface ExportTarget {
    @Serializable
    data class ContentUri(val value: String) : ExportTarget

    @Serializable
    data class AppFile(val path: String) : ExportTarget
}

@Serializable
enum class ExportFormat {
    PNG,
    JPEG,
    WEBP,
    TIFF,
    PSD,

    /** AV1 image (libavif): 8-bit for 8-bit documents, 10-bit for 16-bit ones; quality 100 is lossless. */
    AVIF,

    /** JPEG with an HDR gain map (Ultra HDR); needs a 32-bit (linear float) document. */
    ULTRA_HDR,
}

/**
 * Descriptive metadata written into an export as IPTC (JPEG) and XMP (JPEG, PNG): the Library's title, caption, creator, copyright and
 * keywords. Blank fields are left out; [stripMetadata][ExportOptions.stripMetadata] drops all of it.
 */
@Serializable
data class IptcMetadata(
    val title: String? = null,
    val caption: String? = null,
    val creator: String? = null,
    val copyright: String? = null,
    val keywords: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = title.isNullOrBlank() && caption.isNullOrBlank() && creator.isNullOrBlank() && copyright.isNullOrBlank() && keywords.none { it.isNotBlank() }
}

@Serializable
data class ExportOptions(
    val format: ExportFormat = ExportFormat.PNG,
    val quality: Int = 92,
    val scale: Float = 1f,
    val stripMetadata: Boolean = false,
    val iptc: IptcMetadata? = null,
) {
    init {
        require(quality in 1..100)
        require(scale.isFinite() && scale > 0f)
    }
}

@Serializable
data class ExportResult(val width: Int, val height: Int, val bytesWritten: Long, val warnings: List<String> = emptyList())

@Serializable
data class LayerProperties(
    val name: String? = null,
    val visible: Boolean? = null,
    val opacity: Float? = null,
    val fillOpacity: Float? = null,
    val blendMode: BlendMode? = null,
    /** Blend If sliders; null leaves them unchanged. */
    val blendIf: app.croptop.core.model.BlendIfSettings? = null,
) {
    init {
        require(opacity == null || (opacity.isFinite() && opacity in 0f..1f))
        require(fillOpacity == null || (fillOpacity.isFinite() && fillOpacity in 0f..1f))
    }
}

@Serializable
data class StrokeSpec(
    val target: LayerId,
    val color: RgbaColor,
    val diameter: Float,
    val hardness: Float = 1f,
    val opacity: Float = 1f,
    /** Full brush (dynamics, spacing, scatter…, see `BrushDabs`); null = a plain round brush from the fields above. */
    val brush: app.croptop.core.model.BrushSettings? = null,
    /** Eraser: removes paint (or reveals transparency) instead of adding [color]. */
    val erase: Boolean = false,
) {
    init {
        require(diameter.isFinite() && diameter > 0f)
        require(hardness.isFinite() && hardness in 0f..1f)
        require(opacity.isFinite() && opacity in 0f..1f)
    }
}

@Serializable
enum class LiquifyTool { FORWARD_WARP, RECONSTRUCT, SMOOTH, TWIRL_CLOCKWISE, TWIRL_COUNTER_CLOCKWISE, PUCKER, BLOAT, PUSH_LEFT }

/** One sampled input to a native Liquify brush stroke; motion is the delta since the previous sample. */
@Serializable
data class LiquifyDab(
    val x: Double,
    val y: Double,
    val size: Double,
    val density: Float = 0.5f,
    val pressure: Float = 1f,
    val rate: Float = 0.8f,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
) {
    init {
        require(x.isFinite() && y.isFinite() && kotlin.math.abs(x) <= 1.0e9 && kotlin.math.abs(y) <= 1.0e9)
        require(size.isFinite() && size in 1.0..15_000.0)
        require(density.isFinite() && density in 0f..1f && pressure.isFinite() && pressure in 0f..1f)
        require(rate.isFinite() && rate in 0f..1f && dx.isFinite() && dy.isFinite())
        require(kotlin.math.abs(dx) <= 1.0e6 && kotlin.math.abs(dy) <= 1.0e6)
    }
}

/** All accepted dabs from one pointer gesture, committed as one undoable document operation. */
@Serializable
data class LiquifyStroke(val tool: LiquifyTool, val dabs: List<LiquifyDab>, val meshSpacing: Int = 4) {
    init {
        require(dabs.isNotEmpty() && dabs.size <= 65_536)
        require(meshSpacing in 1..64)
    }
}

@Serializable
enum class ShapeBooleanOperation { UNITE, SUBTRACT, INTERSECT, EXCLUDE }

/** Depth-driven optical blur controls. Radius is measured in document pixels. */
@Serializable
data class LensBlurSpec(
    val radius: Float = 15f,
    val focalDistance: Float = 0f,
    val invertDepth: Boolean = false,
    val irisBlades: Int = 6,
    val irisRotationDegrees: Float = 0f,
    val irisCurvature: Float = 0f,
    val specularBrightness: Float = 0f,
    val specularThreshold: Float = 0.9f,
    val noise: Float = 0f,
    val monochromaticNoise: Boolean = true,
    val seed: Int = 1,
    val workerThreads: Int = 0,
) {
    init {
        require(radius.isFinite() && radius in 0f..100f)
        require(focalDistance.isFinite() && focalDistance in 0f..1f)
        require(irisBlades == 0 || irisBlades in 3..8)
        require(irisRotationDegrees.isFinite() && irisRotationDegrees in -360f..360f)
        require(irisCurvature.isFinite() && irisCurvature in 0f..1f)
        require(specularBrightness.isFinite() && specularBrightness in 0f..1f)
        require(specularThreshold.isFinite() && specularThreshold in 0f..1f)
        require(noise.isFinite() && noise in 0f..1f)
        require(workerThreads in 0..32)
    }
}

/** Depth values can come from a grayscale pixel layer or an attached layer mask. */
@Serializable
sealed interface LensBlurDepthSource {
    @Serializable
    data class LayerPixels(val layerId: LayerId) : LensBlurDepthSource

    @Serializable
    data class LayerMask(val layerId: LayerId) : LensBlurDepthSource
}

@Serializable
data class DepthRangeSpec(val near: Float, val far: Float, val feather: Float = 0f) {
    init {
        require(near.isFinite() && far.isFinite() && near in 0f..1f && far in near..1f)
        require(feather.isFinite() && feather in 0f..1f)
    }
}

@Serializable
enum class SampleMode { MERGED, ACTIVE_LAYER, ALL_LAYERS }

@Serializable
data class ColorSample(val color: RgbaColor, val point: DocPoint, val mode: SampleMode)

@Serializable
enum class HistogramScope { MERGED, ACTIVE_LAYER, SELECTION }

@Serializable
enum class HistogramChannel { RED, GREEN, BLUE, LUMINOSITY, RGB }

@Serializable
data class HistogramChannelStats(
    /** Sum of alpha/selection weights; RGB uses the mean of its three channel totals. */
    val weightedPixels: Double,
    /** Weighted average intensity in the 0–255 bin domain. */
    val mean: Double,
    val standardDeviation: Double,
    /** Weighted median as a 0–255 bin index. */
    val median: Int,
)

/** 256-bin channel counts weighted by pixel alpha and, when requested, selection coverage. */
@Serializable
data class Histogram(
    val red: List<Double>,
    val green: List<Double>,
    val blue: List<Double>,
    val luminosity: List<Double>,
    val statistics: Map<HistogramChannel, HistogramChannelStats>,
) {
    companion object {
        fun fromChannels(red: List<Double>, green: List<Double>, blue: List<Double>, luminosity: List<Double>): Histogram {
            val sourceChannels = listOf(red, green, blue, luminosity)
            require(sourceChannels.all { it.size == 256 }) { "Histogram channels must have 256 bins" }
            val immutableBins = sourceChannels.map { it.toList() }
            require(immutableBins.all { bins -> bins.all { it.isFinite() && it >= 0.0 } }) {
                "Histogram bins must be finite and nonnegative"
            }
            val channels = linkedMapOf(
                HistogramChannel.RED to (listOf(immutableBins[0]) to 1.0),
                HistogramChannel.GREEN to (listOf(immutableBins[1]) to 1.0),
                HistogramChannel.BLUE to (listOf(immutableBins[2]) to 1.0),
                HistogramChannel.LUMINOSITY to (listOf(immutableBins[3]) to 1.0),
                HistogramChannel.RGB to (immutableBins.take(3) to 3.0),
            )
            return Histogram(
                immutableBins[0],
                immutableBins[1],
                immutableBins[2],
                immutableBins[3],
                channels.mapValues { (_, data) -> statisticsFor(data.first, data.second) },
            )
        }

        private fun statisticsFor(channels: List<List<Double>>, sampleDivisor: Double): HistogramChannelStats {
            val scale = channels.maxOf { bins -> bins.maxOrNull() ?: 0.0 }
            if (scale <= 0.0) return HistogramChannelStats(0.0, 0.0, 0.0, 0)
            val bins = List(256) { index -> channels.sumOf { it[index] / scale } }
            val total = bins.sum()
            val weightedPixels = scale * (total / sampleDivisor)
            require(weightedPixels.isFinite()) { "Histogram statistics exceed the supported numeric range" }
            var weightedSum = 0.0
            var weightedSquares = 0.0
            bins.forEachIndexed { index, weight ->
                weightedSum += weight * index
                weightedSquares += weight * index * index
            }
            val mean = weightedSum / total
            var cumulative = 0.0
            val median = bins.indexOfFirst { weight ->
                cumulative += weight
                cumulative >= total * 0.5 && cumulative > 0.0
            }.takeIf { it >= 0 } ?: 255
            val standardDeviation = kotlin.math.sqrt((weightedSquares / total - mean * mean).coerceAtLeast(0.0))
            require(mean.isFinite() && standardDeviation.isFinite()) { "Histogram statistics exceed the supported numeric range" }
            return HistogramChannelStats(
                weightedPixels = weightedPixels,
                mean = mean,
                standardDeviation = standardDeviation,
                median = median,
            )
        }
    }
}

/**
 * Row-major thumbnail pixels in straight-alpha sRGB RGBA byte order. For valid positive dimensions,
 * [rgba] contains exactly `width * height * 4` bytes; consumers should preserve the supplied aspect ratio.
 */
@Serializable
data class LayerThumb(val layerId: LayerId, val width: Int, val height: Int, val rgba: ByteArray)

@Serializable
data class OverlayState(
    val selectionBounds: List<DocRect> = emptyList(),
    val guides: List<Guide> = emptyList(),
    val showSelection: Boolean = true,
    val showGuides: Boolean = true,
)
