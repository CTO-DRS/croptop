package app.croptop.core.engine

import app.croptop.core.model.ColorGradient
import app.croptop.core.model.DocPoint
import app.croptop.core.model.GradientShape
import app.croptop.core.model.RgbaColor

/** Native gradient rasterizer. Document retains ownership of the tile store handle. */
internal object NativeGradientBridge {
    fun render(
        store: Long,
        start: DocPoint,
        end: DocPoint,
        gradient: ColorGradient,
        shape: GradientShape,
        reverse: Boolean,
        dither: Boolean,
        opacity: Float,
        selectionMask: Long = 0L,
    ): String? {
        val colors = FloatArray(gradient.stops.size * 4)
        gradient.stops.forEachIndexed { index, stop ->
            val offset = index * 4
            colors[offset] = stop.position
            colors[offset + 1] = stop.color.r
            colors[offset + 2] = stop.color.g
            colors[offset + 3] = stop.color.b
        }
        val alphas = FloatArray(gradient.stops.size * 2)
        gradient.stops.forEachIndexed { index, stop ->
            alphas[index * 2] = stop.position
            alphas[index * 2 + 1] = stop.color.a
        }
        return nativeRender(store, start.x, start.y, end.x, end.y, colors, alphas, shape.ordinal, reverse, dither, opacity, selectionMask)
    }

    fun bucket(
        target: Long,
        sampleSource: Long,
        point: DocPoint,
        color: RgbaColor,
        tolerance: Int,
        contiguous: Boolean,
        opacity: Float,
        selectionMask: Long = 0L,
    ): String? =
        nativeBucket(target, sampleSource, point.x, point.y, color.r, color.g, color.b, color.a, tolerance, contiguous, opacity, selectionMask)

    private external fun nativeRender(
        store: Long,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        colors: FloatArray,
        opacities: FloatArray,
        shape: Int,
        reverse: Boolean,
        dither: Boolean,
        opacity: Float,
        selectionMask: Long,
    ): String?

    private external fun nativeBucket(
        target: Long,
        sampleSource: Long,
        x: Float,
        y: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        tolerance: Int,
        contiguous: Boolean,
        opacity: Float,
        selectionMask: Long,
    ): String?
}
