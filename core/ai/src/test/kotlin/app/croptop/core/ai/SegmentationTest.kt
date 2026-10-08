package app.croptop.core.ai

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentationTest {
    private fun image(width: Int, height: Int, color: (x: Int, y: Int) -> Triple<Int, Int, Int>): RgbaImage {
        val data = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (r, g, b) = color(x, y)
                val i = (y * width + x) * 4
                data[i] = r.toByte()
                data[i + 1] = g.toByte()
                data[i + 2] = b.toByte()
                data[i + 3] = 255.toByte()
            }
        }
        return RgbaImage(width, height, data)
    }

    private class FakeSession(val produce: (FloatArray, IntArray) -> FloatArray) : ModelSession {
        var lastShape: IntArray? = null
        var lastInput: FloatArray? = null
        override val delegate = Delegate.CPU
        override fun run(input: FloatArray, inputShape: IntArray): FloatArray {
            lastShape = inputShape
            lastInput = input
            return produce(input, inputShape)
        }

        override fun close() = Unit
    }

    @Test
    fun `sky detector finds a blue sky above dark ground and nothing in a ground only picture`() {
        val skyAndGround = image(200, 120) { _, y -> if (y < 60) Triple(110 + y / 2, 160 + y / 2, 235) else Triple(30, 70, 25) }
        val sky = assertNotNull(SkyDetector.detect(skyAndGround))
        assertEquals(0.5f, sky.coverage, 0.08f)
        fun at(x: Float, y: Float) = sky.alpha[(y * sky.height).toInt() * sky.width + (x * sky.width).toInt()].toInt() and 0xFF
        assertTrue(at(0.5f, 0.15f) > 240, "the sky is inside")
        assertTrue(at(0.5f, 0.85f) < 10, "the ground is outside")
        assertNull(SkyDetector.detect(image(200, 120) { _, _ -> Triple(35, 80, 30) }), "grass is not sky")
        assertNull(SkyDetector.detect(image(200, 120) { _, _ -> Triple(90, 90, 92) }), "dark grey asphalt is not sky")
    }

    @Test
    fun `sky detector stops at a warm building and keeps sky seen through a gap`() {
        // Blue sky, a warm stone wall down the left edge, and a thin dark branch crossing the sky.
        val scene = image(240, 160) { x, y ->
            when {
                x < 20 -> Triple(200, 170, 165)
                y in 40..43 && x > 60 -> Triple(40, 35, 30)
                y < 100 -> Triple(120, 170, 240)
                else -> Triple(40, 90, 40)
            }
        }
        val sky = assertNotNull(SkyDetector.detect(scene))
        fun at(x: Float, y: Float) = sky.alpha[(y * sky.height).toInt() * sky.width + (x * sky.width).toInt()].toInt() and 0xFF
        assertTrue(at(0.4f, 0.1f) > 200)
        assertTrue(at(0.6f, 0.5f) > 200, "sky below the branch is reached around it")
        assertTrue(at(0.03f, 0.3f) < 60, "the warm wall is not sky")
    }

    @Test
    fun `prompt segmenter sends rgb plus a click hint and returns the model's probability`() {
        val photo = image(64, 48) { x, _ -> Triple(x * 4, 100, 50) }
        val session = FakeSession { input, _ -> FloatArray(512 * 512) { p -> input[p * 4 + 3] } }
        val mask = assertNotNull(PromptSegmenter(session).segment(photo, listOf(PromptSegmenter.Click(0.25f, 0.5f))))
        assertContentEquals(intArrayOf(1, 512, 512, 4), session.lastShape)
        val input = checkNotNull(session.lastInput)
        // The red channel of the image is a left-to-right ramp scaled to 0–1; the hint is a disc around the click.
        assertEquals(0f, input[(256 * 512) * 4], 0.02f)
        assertTrue(input[(256 * 512 + 511) * 4] > 0.95f)
        assertEquals(1f, input[(256 * 512 + 128) * 4 + 3])
        assertEquals(0f, input[(256 * 512 + 300) * 4 + 3])
        assertEquals(0xFF, mask.alpha[256 * 512 + 128].toInt() and 0xFF)
        assertEquals(0, mask.alpha[256 * 512 + 400].toInt() and 0xFF)
    }

    @Test
    fun `prompt segmenter unions several clicks and gives nothing for an empty answer`() {
        val photo = image(32, 32) { _, _ -> Triple(10, 10, 10) }
        val echo = FakeSession { input, _ -> FloatArray(512 * 512) { p -> input[p * 4 + 3] } }
        val both = assertNotNull(PromptSegmenter(echo).segment(photo, listOf(PromptSegmenter.Click(0.2f, 0.2f), PromptSegmenter.Click(0.8f, 0.8f))))
        assertEquals(0xFF, both.alpha[(0.2f * 512).toInt() * 512 + (0.2f * 512).toInt()].toInt() and 0xFF)
        assertEquals(0xFF, both.alpha[(0.8f * 512).toInt() * 512 + (0.8f * 512).toInt()].toInt() and 0xFF)
        val nothing = FakeSession { _, _ -> FloatArray(512 * 512) }
        assertNull(PromptSegmenter(nothing).segment(photo, listOf(PromptSegmenter.Click(0.5f, 0.5f))))
        assertTrue(runCatching { PromptSegmenter(echo).segment(photo, emptyList()) }.isFailure)
    }

    @Test
    fun `subject segmenter turns class logits into a foreground mask`() {
        val photo = image(40, 30) { _, _ -> Triple(100, 120, 140) }
        val classes = 21
        // Left half: background wins; right half: class 15 (person) wins.
        val session = FakeSession { _, shape ->
            val size = shape[1]
            FloatArray(size * size * classes) { i ->
                val pixel = i / classes
                val c = i % classes
                val right = pixel % size >= size / 2
                if (right) (if (c == 15) 6f else 0f) else (if (c == 0) 6f else 0f)
            }
        }
        val mask = assertNotNull(SubjectSegmenter(session).segment(photo))
        assertContentEquals(intArrayOf(1, 257, 257, 3), session.lastShape)
        assertEquals(0.5f, mask.coverage, 0.02f)
        assertTrue((mask.alpha[100 * 257 + 200].toInt() and 0xFF) > 240)
        assertTrue((mask.alpha[100 * 257 + 20].toInt() and 0xFF) < 15)
        val input = checkNotNull(session.lastInput)
        assertEquals(100 / 127.5f - 1f, input[0], 0.02f)
        val allBackground = FakeSession { _, shape -> FloatArray(shape[1] * shape[1] * classes) { if (it % classes == 0) 6f else 0f } }
        assertNull(SubjectSegmenter(allBackground).segment(photo), "no object found")
        val allSubject = FakeSession { _, shape -> FloatArray(shape[1] * shape[1] * classes) { if (it % classes == 7) 6f else 0f } }
        assertNull(SubjectSegmenter(allSubject).segment(photo), "a mask covering the whole picture is not a subject")
    }

    @Test
    fun cleanedKeepsTheMainSubjectFillsHolesAndDropsStrayBlobsAndHaze() {
        val w = 40
        val h = 40
        val a = ByteArray(w * h)
        for (y in 5 until 35) for (x in 5 until 35) a[y * w + x] = -1 // 30x30 subject
        for (y in 18 until 22) for (x in 18 until 22) a[y * w + x] = 0 // hole inside
        a[1 * w + 1] = -1; a[1 * w + 2] = -1; a[2 * w + 1] = -1 // stray blob
        a[0 * w + 38] = 60 // faint haze outside
        val c = MaskResult(w, h, a).cleaned()
        fun at(x: Int, y: Int) = c.alpha[y * w + x].toInt() and 0xFF
        assertEquals(255, at(20, 20), "hole filled")
        assertEquals(255, at(10, 10))
        assertEquals(0, at(1, 1), "stray blob dropped")
        assertEquals(0, at(38, 0), "haze removed")
    }

    @Test
    fun `cutout segmenter feeds NCHW and undoes the doubled sigmoid at the picture's own aspect`() {
        val photo = image(40, 20) { x, _ -> if (x < 20) Triple(255, 0, 0) else Triple(0, 0, 255) }
        fun sigmoid(v: Float) = 1f / (1f + kotlin.math.exp(-v))
        // The right half is the object: the export's output is sigmoid(alpha) where alpha is already 0..1.
        val session = FakeSession { _, shape ->
            val size = shape[2]
            FloatArray(size * size) { i -> sigmoid(if (i % size >= size / 2) 1f else 0f) }
        }
        val mask = assertNotNull(CutoutSegmenter(session).segment(photo))
        assertContentEquals(intArrayOf(1, 3, 1024, 1024), session.lastShape)
        val input = checkNotNull(session.lastInput)
        assertEquals(0.5f, input[0], 0.01f, "red plane first, x/255 - 0.5")
        assertEquals(-0.5f, input[2 * 1024 * 1024], 0.01f, "blue plane of a red pixel")
        assertEquals(40, mask.width)
        assertEquals(20, mask.height)
        assertTrue((mask.alpha[10 * 40 + 35].toInt() and 0xFF) > 240)
        assertTrue((mask.alpha[10 * 40 + 5].toInt() and 0xFF) < 10)
    }
}
