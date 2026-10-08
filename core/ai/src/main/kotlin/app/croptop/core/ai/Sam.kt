// Promptable object select with SAM 2.1 Hiera-Tiny: the heavy image encoder runs once per picture and its
// features are kept, so every further click costs only the small mask decoder (tens of milliseconds).
package app.croptop.core.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the encoder produces for one picture and the decoder needs for every click (about 17 MB of floats). */
class SamEmbedding(val embedding: FloatArray, val features1: FloatArray, val features0: FloatArray) {
    init {
        require(embedding.size == 256 * 64 * 64 && features1.size == 64 * 128 * 128 && features0.size == 32 * 256 * 256) { "unexpected SAM feature sizes" }
    }
}

/** The constants of SAM 2's prompt encoder for a single positive point: the Fourier matrix `[2,128]` and two learned tokens. */
class SamPromptConstants(private val posmat: FloatArray, private val positivePoint: FloatArray, val notAPoint: FloatArray) {
    init {
        require(posmat.size == 256 && positivePoint.size == 256 && notAPoint.size == 256) { "SAM prompt constants are 768 floats" }
    }

    /** The two sparse prompt tokens `[2, 256]` for a click at fractions ([x], [y]) of the picture: the point, then the padding point. */
    fun tokens(x: Float, y: Float): FloatArray {
        val cx = 2f * ((x * MODEL_SIDE + 0.5f) / MODEL_SIDE) - 1f
        val cy = 2f * ((y * MODEL_SIDE + 0.5f) / MODEL_SIDE) - 1f
        val out = FloatArray(512)
        for (k in 0 until 128) {
            val coord = (2.0 * PI * (cx * posmat[k] + cy * posmat[128 + k])).toFloat()
            out[k] = sin(coord) + positivePoint[k]
            out[128 + k] = cos(coord) + positivePoint[128 + k]
        }
        System.arraycopy(notAPoint, 0, out, 256, 256)
        return out
    }

    companion object {
        const val MODEL_SIDE = 1024

        fun parse(bytes: ByteArray): SamPromptConstants {
            require(bytes.size == 768 * Float.SIZE_BYTES) { "SAM prompt constants are 3072 bytes" }
            val floats = FloatArray(768).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            return SamPromptConstants(floats.copyOfRange(0, 256), floats.copyOfRange(256, 512), floats.copyOfRange(512, 768))
        }

        /** The constants shipped with the app (derived from the same Apache-2.0 SAM 2.1 weights as the models). */
        fun bundled(): SamPromptConstants =
            parse(requireNotNull(SamPromptConstants::class.java.getResourceAsStream("/sam2_prompt_const.bin")) { "SAM prompt constants are missing" }.use { it.readBytes() })
    }
}

/** The image encoder: `[1,3,1024,1024]` ImageNet-normalised in, three feature maps out (embedding, 1/8 features, 1/4 features). */
class SamEncoder(private val session: ModelSession) {
    fun embed(image: RgbaImage): SamEmbedding {
        val side = SamPromptConstants.MODEL_SIDE
        val nhwc = ImageOps.resizeRgb(image, side, side, 1f / 255f, 0f)
        val input = FloatArray(3 * side * side)
        for (p in 0 until side * side) for (c in 0 until 3) input[c * side * side + p] = (nhwc[p * 3 + c] - MEAN[c]) / STD[c]
        val out = session.runOutputs(listOf(input), listOf(intArrayOf(1, 3, side, side)))
        require(out.size == 3) { "the SAM encoder returned ${out.size} outputs, expected 3" }
        return SamEmbedding(out[0], out[1], out[2])
    }

    private companion object {
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/** The mask decoder: one click on a cached [SamEmbedding] gives the picture's object at that point. */
class SamDecoder(private val session: ModelSession, private val constants: SamPromptConstants = SamPromptConstants.bundled()) {
    fun decode(embedding: SamEmbedding, click: PromptSegmenter.Click): MaskResult? {
        val tokens = constants.tokens(click.x.coerceIn(0f, 1f), click.y.coerceIn(0f, 1f))
        val out = session.runOutputs(
            listOf(embedding.embedding, tokens, embedding.features1, embedding.features0),
            listOf(intArrayOf(1, 256, 64, 64), intArrayOf(1, 2, 256), intArrayOf(1, 64, 128, 128), intArrayOf(1, 32, 256, 256)),
        )
        require(out.size == 2 && out[0].size == 3 * MASK * MASK && out[1].size == 3) { "the SAM decoder returned unexpected tensors" }
        val best = (0 until 3).maxBy { out[1][it] }
        val alpha = ByteArray(MASK * MASK) { i ->
            // The model's own decision is logit > 0; a ramp one logit wide either side keeps a soft edge without the long sigmoid tails that
            // would stretch the selection's bounds. The engine's guided filter then snaps the edge to the picture.
            val ramp = (out[0][best * MASK * MASK + i] + 1f) / 2f
            (ramp.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
        return MaskResult(MASK, MASK, alpha).withoutNoise().takeIf { it.coverage > MIN_COVERAGE }
    }

    companion object {
        const val MASK = 256
        const val MIN_COVERAGE = 0.0005f
    }
}
