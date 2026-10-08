package app.croptop.core.ai

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TiledInferenceTest {
    private class FakeSession(private val transform: (FloatArray, IntArray) -> FloatArray) : ModelSession {
        var calls = 0
        override val delegate = Delegate.CPU
        override fun run(input: FloatArray, inputShape: IntArray): FloatArray {
            calls++
            return transform(input, inputShape)
        }
        override fun close() = Unit
    }

    private fun gradient(w: Int, h: Int, c: Int) = FloatArray(w * h * c) { i -> ((i * 31) % 97) / 97f }

    @Test
    fun identityModelReproducesTheImageAcrossTileSeams() {
        val (w, h, c) = Triple(100, 70, 3)
        val image = gradient(w, h, c)
        val session = FakeSession { input, _ -> input.copyOf() }
        val out = TiledInference.run(image, w, h, TiledSpec(tileSize = 32, overlap = 8, inputChannels = c), session)
        assertEquals(image.size, out.size)
        for (i in image.indices) assertEquals(image[i], out[i], 1e-5f, "sample $i")
        assertTrue(session.calls > 1)
    }

    @Test
    fun imageSmallerThanTheNetworkIsPaddedAndCropped() {
        val (w, h, c) = Triple(20, 12, 1)
        val image = gradient(w, h, c)
        var shape = IntArray(0)
        val session = FakeSession { input, s ->
            shape = s
            input.copyOf()
        }
        val out = TiledInference.run(image, w, h, TiledSpec(tileSize = 32, overlap = 8, inputChannels = c), session)
        assertEquals(listOf(1, 32, 32, 1), shape.toList())
        assertEquals(1, session.calls)
        for (i in image.indices) assertEquals(image[i], out[i], 1e-6f)
    }

    @Test
    fun upscalerOutputLandsAtTheScaledPositions() {
        val (w, h) = 45 to 37
        val image = FloatArray(w * h) { (it % w) + (it / w) * 100f }
        val spec = TiledSpec(tileSize = 16, overlap = 4, inputChannels = 1, scale = 2)
        // Nearest-neighbour x2 "model".
        val session = FakeSession { input, _ ->
            val out = FloatArray(32 * 32)
            for (y in 0 until 32) for (x in 0 until 32) out[y * 32 + x] = input[(y / 2) * 16 + x / 2]
            out
        }
        val out = TiledInference.run(image, w, h, spec, session)
        assertEquals(w * 2 * h * 2, out.size)
        for (y in 0 until h * 2) for (x in 0 until w * 2) assertEquals(image[(y / 2) * w + x / 2], out[y * w * 2 + x], 1e-3f, "($x,$y)")
    }

    @Test
    fun cancellationStopsBeforeTheNextTileAndProgressIsReported() {
        val image = gradient(64, 64, 1)
        val session = FakeSession { input, _ -> input.copyOf() }
        val reported = mutableListOf<Pair<Int, Int>>()
        assertFailsWith<CancellationException> {
            TiledInference.run(image, 64, 64, TiledSpec(32, 8, 1), session, isCancelled = { session.calls >= 2 }, progress = { d, t ->
                reported +=
                    d to t
            })
        }
        assertEquals(2, session.calls)
        assertEquals(listOf(1, 2), reported.map { it.first })
        assertTrue(reported.all { it.second == TilePlanner.plan(64, 64, 32, 8).size })
    }

    @Test
    fun wrongOutputSizeIsRejected() {
        val session = FakeSession { _, _ -> FloatArray(3) }
        assertFailsWith<IllegalArgumentException> { TiledInference.run(gradient(8, 8, 1), 8, 8, TiledSpec(8, 0, 1), session) }
    }
}
