package app.croptop.core.model

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** Units for size fields; pixels are the source of truth. [PERCENT] is relative to the current size. */
enum class SizeUnit(val label: String) {
    PX("Pixels"),
    PERCENT("Percent"),
    IN("Inches"),
    CM("Centimeters"),
    MM("Millimeters"),
    ;

    companion object {
        /** Units that make sense without a reference size (New Document). */
        val Absolute = listOf(PX, IN, CM, MM)
    }
}

/** Largest side we accept (PSB-class documents). */
const val MAX_DIMENSION = 300_000

/** [basePx] is the current size along this axis, used by [SizeUnit.PERCENT]. */
fun toPixels(value: Float, unit: SizeUnit, ppi: Float, basePx: Int = 0): Int = when (unit) {
    SizeUnit.PX -> value
    SizeUnit.PERCENT -> value / 100f * basePx
    SizeUnit.IN -> value * ppi
    SizeUnit.CM -> value / 2.54f * ppi
    SizeUnit.MM -> value / 25.4f * ppi
}.roundToInt().coerceIn(1, MAX_DIMENSION)

fun fromPixels(px: Int, unit: SizeUnit, ppi: Float, basePx: Int = 0): Float = when (unit) {
    SizeUnit.PX -> px.toFloat()
    SizeUnit.PERCENT -> if (basePx == 0) 100f else px * 100f / basePx
    SizeUnit.IN -> px / ppi
    SizeUnit.CM -> px / ppi * 2.54f
    SizeUnit.MM -> px / ppi * 25.4f
}

/** Image ▸ Image Size resampling. */
enum class Resample(val label: String) {
    AUTOMATIC("Automatic"),
    NEAREST("Nearest Neighbor (hard edges)"),
    BILINEAR("Bilinear"),
    BICUBIC("Bicubic (smooth gradients)"),
    LANCZOS("Lanczos (sharpest)"),
    ;

    /** What AUTOMATIC means for a given change: Lanczos to shrink, bicubic to enlarge. */
    fun resolve(from: DocSize, to: DocSize): Resample = if (this != AUTOMATIC) {
        this
    } else if (to.width.toLong() * to.height < from.width.toLong() * from.height) {
        LANCZOS
    } else {
        BICUBIC
    }
}

/**
 * Image Size dialog state. With [resample] off, the pixel size is fixed and changing a print size
 * changes the resolution instead (Photoshop behavior).
 */
@Serializable
data class ImageSizing(
    val original: DocSize,
    val originalPpi: Float,
    val size: DocSize = original,
    val ppi: Float = originalPpi,
    val linked: Boolean = true,
    val resample: Boolean = true,
) {
    private val aspect: Double get() = original.width.toDouble() / original.height

    fun withWidth(value: Float, unit: SizeUnit): ImageSizing {
        if (!resample && unit != SizeUnit.PX && unit != SizeUnit.PERCENT) {
            // Print width changes → resolution follows; pixels stay.
            val inches = fromPixels(toPixels(value, unit, 1f), SizeUnit.IN, 1f).coerceAtLeast(0.001f)
            return copy(ppi = (size.width / inches).coerceIn(1f, 30_000f))
        }
        if (!resample) return this
        val w = toPixels(value, unit, ppi, original.width)
        val h = if (linked) (w / aspect).roundToInt().coerceIn(1, MAX_DIMENSION) else size.height
        return copy(size = DocSize(w, h))
    }

    fun withHeight(value: Float, unit: SizeUnit): ImageSizing {
        if (!resample && unit != SizeUnit.PX && unit != SizeUnit.PERCENT) {
            val inches = fromPixels(toPixels(value, unit, 1f), SizeUnit.IN, 1f).coerceAtLeast(0.001f)
            return copy(ppi = (size.height / inches).coerceIn(1f, 30_000f))
        }
        if (!resample) return this
        val h = toPixels(value, unit, ppi, original.height)
        val w = if (linked) (h * aspect).roundToInt().coerceIn(1, MAX_DIMENSION) else size.width
        return copy(size = DocSize(w, h))
    }

    /** With resampling on, the print size is kept and pixels follow the new resolution. */
    fun withPpi(value: Float): ImageSizing {
        val p = value.coerceIn(1f, 30_000f)
        if (!resample) return copy(ppi = p)
        val w = (size.width * p / ppi).roundToInt().coerceIn(1, MAX_DIMENSION)
        val h = (size.height * p / ppi).roundToInt().coerceIn(1, MAX_DIMENSION)
        return copy(ppi = p, size = DocSize(w, h))
    }

    fun reset() = copy(size = original, ppi = originalPpi)

    val changed: Boolean get() = size != original || ppi != originalPpi

    /** Approximate RGBA memory for the result at [bytesPerChannel] (1, 2 or 4). */
    fun estimatedBytes(bytesPerChannel: Int = 1): Long = size.width.toLong() * size.height * 4 * bytesPerChannel
}

/** Canvas Size anchor: where the existing image sits in the new canvas. */
enum class Anchor(val fx: Float, val fy: Float) {
    TOP_LEFT(0f, 0f),
    TOP(0.5f, 0f),
    TOP_RIGHT(1f, 0f),
    LEFT(0f, 0.5f),
    CENTER(0.5f, 0.5f),
    RIGHT(1f, 0.5f),
    BOTTOM_LEFT(0f, 1f),
    BOTTOM(0.5f, 1f),
    BOTTOM_RIGHT(1f, 1f),
}

/** Canvas Size: new canvas and where the old image lands in it (negative = cropped off). */
data class CanvasResize(val original: DocSize, val size: DocSize, val anchor: Anchor = Anchor.CENTER) {
    val offsetX: Int get() = ((size.width - original.width) * anchor.fx).roundToInt()
    val offsetY: Int get() = ((size.height - original.height) * anchor.fy).roundToInt()

    /** True when part of the image would be cut off (Photoshop warns before proceeding). */
    val clips: Boolean get() = size.width < original.width || size.height < original.height

    companion object {
        /** "Relative": add [dw]/[dh] pixels in total to the current canvas. */
        fun relative(original: DocSize, dw: Int, dh: Int, anchor: Anchor) =
            CanvasResize(
                original,
                DocSize((original.width + dw).coerceIn(1, MAX_DIMENSION), (original.height + dh).coerceIn(1, MAX_DIMENSION)),
                anchor,
            )
    }
}
