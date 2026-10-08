package app.croptop.feature.develop

import app.croptop.core.ai.MaskResult
import app.croptop.core.model.AiMaskBitmap
import kotlin.math.max
import kotlin.math.min

/** Turns a model's coarse mask into the small bitmap a Develop recipe stores. */
internal object AiMaskBitmaps {
    /** Long side of the stored bitmap: 128 keeps a recipe light; the pipeline recovers the edges from the picture. */
    const val STORED_SIDE = 128

    fun fit(mask: MaskResult, label: String): AiMaskBitmap {
        val scale = min(1f, STORED_SIDE.toFloat() / max(mask.width, mask.height))
        val w = max(AiMaskBitmap.MIN_SIDE, (mask.width * scale).toInt())
        val h = max(AiMaskBitmap.MIN_SIDE, (mask.height * scale).toInt())
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = y * mask.height / h
            val y1 = max(y0 + 1, (y + 1) * mask.height / h)
            for (x in 0 until w) {
                val x0 = x * mask.width / w
                val x1 = max(x0 + 1, (x + 1) * mask.width / w)
                var sum = 0
                var n = 0
                for (yy in y0 until min(y1, mask.height)) for (xx in x0 until min(x1, mask.width)) {
                    sum += mask.alpha[yy * mask.width + xx].toInt() and 0xFF
                    n++
                }
                out[y * w + x] = (sum / max(1, n)).toByte()
            }
        }
        return AiMaskBitmap.of(w, h, out, label)
    }
}
