package app.croptop.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SamTest {
    private class Session(val run: (List<FloatArray>, List<IntArray>) -> List<FloatArray>) : ModelSession {
        override val delegate = Delegate.CPU
        override fun run(input: FloatArray, inputShape: IntArray) = error("single-output run is not used")
        override fun runOutputs(inputs: List<FloatArray>, shapes: List<IntArray>) = run(inputs, shapes)
        override fun close() = Unit
    }

    @Test
    fun `the prompt tokens match the reference encoder`() {
        // Golden values from the numpy prompt encoder of the LiteRT SAM 2.1 card, with the bundled constants.
        val constants = SamPromptConstants.bundled()
        val t = constants.tokens(0.2f, 0.43f)
        assertEquals(512, t.size)
        assertEquals(-0.94691837f, t[0], 1e-4f)
        assertEquals(0.54971957f, t[1], 1e-4f)
        assertEquals(0.33184454f, t[128], 1e-4f)
        assertEquals(0.89364165f, t[129], 1e-4f)
        assertEquals(-2.8302612f, t.copyOfRange(0, 256).sum(), 5e-3f)
        val corner = constants.tokens(0f, 0f)
        assertEquals(0.06101191f, corner[0], 1e-4f)
        assertEquals(7.8204432f, corner.copyOfRange(0, 256).sum(), 5e-3f)
        // The second token is the learned "not a point" padding, untouched by the click.
        assertEquals(0.013688902f, t[256], 1e-6f)
        assertEquals(-0.6101998f, t.copyOfRange(256, 512).sum(), 1e-3f)
    }

    @Test
    fun `the encoder feeds a 1024 normalised CHW image and returns the three feature maps`() {
        var seenShape: IntArray? = null
        var seenFirst = 0f
        val encoder = SamEncoder(
            Session { inputs, shapes ->
                seenShape = shapes.single()
                seenFirst = inputs.single()[0]
                listOf(FloatArray(256 * 64 * 64), FloatArray(64 * 128 * 128), FloatArray(32 * 256 * 256))
            },
        )
        // A white picture: (1 - mean) / std for the red channel is the first value of the red plane.
        val white = RgbaImage(40, 30, ByteArray(40 * 30 * 4) { -1 })
        val embedding = encoder.embed(white)
        assertEquals(listOf(1, 3, 1024, 1024), seenShape!!.toList())
        assertEquals((1f - 0.485f) / 0.229f, seenFirst, 1e-3f)
        assertEquals(256 * 64 * 64, embedding.embedding.size)
    }

    @Test
    fun `a click is decoded to the highest scoring mask as a soft 256 square`() {
        var tokens: FloatArray? = null
        val embedding = SamEmbedding(FloatArray(256 * 64 * 64), FloatArray(64 * 128 * 128), FloatArray(32 * 256 * 256))
        val decoder = SamDecoder(
            Session { inputs, shapes ->
                tokens = inputs[1]
                assertEquals(listOf(1, 2, 256), shapes[1].toList())
                // Candidate 1 is the best: strongly inside (logit +8) on the left half, outside (−8) on the right; the others are empty.
                val masks = FloatArray(3 * 256 * 256) { -8f }
                for (y in 0 until 256) for (x in 0 until 128) masks[256 * 256 + y * 256 + x] = 8f
                listOf(masks, floatArrayOf(0.1f, 0.9f, 0.3f))
            },
        )
        val mask = assertNotNull(decoder.decode(embedding, PromptSegmenter.Click(0.25f, 0.5f)))
        assertEquals(256, mask.width)
        assertTrue((mask.alpha[10].toInt() and 0xFF) > 250 && (mask.alpha[200].toInt() and 0xFF) < 5)
        assertEquals(512, tokens!!.size)
    }

    @Test
    fun `a decoder that finds nothing returns no mask`() {
        val embedding = SamEmbedding(FloatArray(256 * 64 * 64), FloatArray(64 * 128 * 128), FloatArray(32 * 256 * 256))
        val decoder = SamDecoder(Session { _, _ -> listOf(FloatArray(3 * 256 * 256) { -9f }, floatArrayOf(0.5f, 0.2f, 0.1f)) })
        assertNull(decoder.decode(embedding, PromptSegmenter.Click(0.5f, 0.5f)))
    }
}
