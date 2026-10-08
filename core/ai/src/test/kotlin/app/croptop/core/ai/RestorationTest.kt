package app.croptop.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestorationTest {
    private class Echo(val produce: (FloatArray, IntArray) -> FloatArray) : ModelSession {
        override val delegate = Delegate.CPU
        val shapes = mutableListOf<List<Int>>()
        override fun run(input: FloatArray, inputShape: IntArray): FloatArray {
            shapes += inputShape.toList()
            return produce(input, inputShape)
        }
        override fun close() = Unit
    }

    @Test
    fun `a channels first network is fed CHW and read back as HWC`() {
        // The network doubles red and leaves the rest: in CHW the red plane is the first third of the tensor.
        val inner = Echo { chw, shape ->
            val plane = shape[2] * shape[3]
            FloatArray(chw.size) { if (it < plane) chw[it] * 0.5f else chw[it] }
        }
        val session = ChannelsFirstSession(inner, inputChannelsFirst = true, outputChannelsFirst = true)
        val input = FloatArray(2 * 2 * 3) { (it % 3).toFloat() + 1f } // every pixel (1, 2, 3)
        val out = session.run(input, intArrayOf(1, 2, 2, 3))
        assertEquals(listOf(1, 3, 2, 2), inner.shapes.single())
        assertEquals(listOf(0.5f, 2f, 3f), out.take(3))
        assertEquals(input.size, out.size)
    }

    @Test
    fun `denoising keeps the picture size and feeds 256 tiles`() {
        val inner = Echo { chw, _ -> chw.copyOf() }
        val rgba = ByteArray(300 * 200 * 4) { if (it % 4 == 3) -1 else 100 }
        val out = Denoiser(ChannelsFirstSession(inner, true, true)).denoise(rgba, 300, 200)
        assertEquals(300 * 200 * 3, out.size)
        assertTrue(inner.shapes.all { it == listOf(1, 3, 256, 256) })
        assertEquals(100 / 255f, out[1000], 1e-4f)
    }

    @Test
    fun `an upscaled block has the factor times its size and keeps its content`() {
        // Nearest-neighbour x4 of the NHWC input into an NCHW output.
        val inner = Echo { nhwc, _ ->
            val out = FloatArray(3 * 512 * 512)
            for (c in 0 until 3) for (y in 0 until 512) for (x in 0 until 512) out[(c * 512 + y) * 512 + x] = nhwc[((y / 4) * 128 + x / 4) * 3 + c]
            out
        }
        val width = 300
        val height = 200
        // Red grows to the right: the left half is darker than the right.
        val rgba = ByteArray(width * height * 4) { i -> if (i % 4 == 0) ((i / 4 % width) * 255 / (width - 1)).toByte() else if (i % 4 == 3) -1 else 0 }
        val upscaler = Upscaler(ChannelsFirstSession(inner, inputChannelsFirst = false, outputChannelsFirst = true))
        for (factor in listOf(2, 4)) {
            val block = upscaler.upscaleBlock(rgba, width, height, blockX = 96, blockY = 64, blockW = 96, blockH = 96, factor = factor)
            assertEquals(96 * factor * 96 * factor * 3, block.size)
            val left = block[(10 * 96 * factor + 4) * 3]
            val right = block[(10 * 96 * factor + 96 * factor - 5) * 3]
            assertTrue(right > left, "x$factor: $left < $right")
            assertEquals((96 + 1) / (width - 1f) * 1f, left + 0f, 0.2f)
        }
    }

    @Test
    fun `blocks cover an area exactly once`() {
        val blocks = BlockPlan.blocks(10, 20, 1000, 700, 480)
        assertEquals((10..999).count() * (20..699).count(), blocks.sumOf { it.width * it.height })
        assertTrue(blocks.all { it.width <= 480 && it.height <= 480 })
    }

    @Test
    fun `edge blocks of a small picture still get a full tile`() {
        val inner = Echo { nhwc, _ -> FloatArray(3 * 512 * 512) { nhwc.getOrElse(0) { 0f } } }
        val rgba = ByteArray(40 * 30 * 4) { -1 }
        val out = Upscaler(ChannelsFirstSession(inner, false, true)).upscaleBlock(rgba, 40, 30, 0, 0, 40, 30, 4)
        assertEquals(160 * 120 * 3, out.size)
        assertEquals(listOf(1, 128, 128, 3), inner.shapes.single())
    }

    @Test
    fun `upscaled alpha follows the source so transparent areas stay transparent`() {
        val w = 8
        val h = 4
        val rgba = ByteArray(w * h * 4) { i -> if (i % 4 == 3) (if ((i / 4) % w < 4) 0 else 255).toByte() else 100 }
        val alpha = Upscaler.Alpha.alphaBlock(rgba, w, h, 0, 0, w, h, 2)
        assertEquals(w * 2 * h * 2, alpha.size)
        fun at(x: Int, y: Int) = alpha[y * w * 2 + x].toInt() and 0xFF
        assertEquals(0, at(0, 0), "transparent stays transparent")
        assertEquals(255, at(15, 7), "opaque stays opaque")
        assertTrue(at(7, 3) in 1..254 || at(8, 3) in 1..254, "the edge is a soft step, not a jump")
    }
}
