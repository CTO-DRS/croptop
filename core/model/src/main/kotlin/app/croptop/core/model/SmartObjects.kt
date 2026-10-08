package app.croptop.core.model

import kotlinx.serialization.Serializable

/**
 * A smart object layer: the layer's pixels are a cache rendered from an immutable embedded source
 * ([sourceKey], document-sized, saved in `.croptop`) through [transform] in one resample, so repeated scaling or
 * rotation never loses quality. [transform] is a row-major 3×3 source→document matrix.
 */
/** True for camera RAW file names (extension check only; the engine probes the bytes). */
fun isRawFileName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in RAW_FILE_EXTENSIONS

private val RAW_FILE_EXTENSIONS = setOf(
    "3fr", "arw", "cr2", "cr3", "dcr", "dng", "fff", "iiq", "kdc", "mef", "mos", "mrw",
    "nef", "nrw", "orf", "pef", "ptx", "raf", "raw", "rw2", "rwl", "srw", "x3f",
)

/**
 * The shared smart-filter mask: an A16 document-sized coverage (white = filters show, black = the unfiltered placement shows)
 * stored under [key] in the project. Edits create a new key, so history snapshots keep the mask they had.
 */
@Serializable
data class SmartFilterMask(val key: String, val enabled: Boolean = true)

/** A RAW smart object's original file (kept verbatim in the project under [fileKey]) and the Develop recipe that produced its pixels. */
@Serializable
data class RawSourceInfo(val fileKey: String, val settings: DevelopSettings)

@Serializable
data class SmartObjectInfo(
    val sourceKey: String,
    val transform: List<Float> = Matrix3.Identity.values,
    /** Smart filters, applied in order after the transform ([FilterCatalog] ids); editable and removable. */
    val filters: List<AdjustmentInfo> = emptyList(),
    /** Source pixel size (0 = unknown: the document size when it was embedded). */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** Set when the source is a camera RAW developed with [RawSourceInfo.settings]; re-editable from the original file. */
    val raw: RawSourceInfo? = null,
    /** One mask for the whole filter stack; null = filters apply everywhere. */
    val filterMask: SmartFilterMask? = null,
) {
    init {
        require(transform.size == 9 && transform.all { it.isFinite() }) { "a smart object transform needs nine finite numbers" }
    }

    val matrix: Matrix3 get() = Matrix3(transform)

    /**
     * This object with its source replaced by one of [width]×[height] under [newKey] (Replace Contents / Edit Contents save-back).
     * [documentWidth]/[documentHeight] stand in for an unknown (0) source size. A differently sized source is scaled uniformly to fit
     * inside the old source's extent and centered, so the object keeps its place on the canvas instead of shrinking into a corner.
     */
    fun withReplacedSource(newKey: String, width: Int, height: Int, documentWidth: Int, documentHeight: Int): SmartObjectInfo {
        val oldWidth = (if (sourceWidth > 0) sourceWidth else documentWidth).toFloat()
        val oldHeight = (if (sourceHeight > 0) sourceHeight else documentHeight).toFloat()
        val fit = if (width > 0 && height > 0 && oldWidth > 0f && oldHeight > 0f && (oldWidth != width.toFloat() || oldHeight != height.toFloat())) {
            val scale = minOf(oldWidth / width, oldHeight / height)
            Matrix3.translate((oldWidth - width * scale) / 2f, (oldHeight - height * scale) / 2f) * Matrix3.scale(scale, scale)
        } else {
            Matrix3.Identity
        }
        return copy(sourceKey = newKey, sourceWidth = width, sourceHeight = height, transform = (matrix * fit).values)
    }
}
