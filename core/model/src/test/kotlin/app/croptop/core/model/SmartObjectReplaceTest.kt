package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SmartObjectReplaceTest {
    private fun map(info: SmartObjectInfo, x: Float, y: Float) = info.matrix.map(DocPoint(x, y))

    @Test
    fun sameSizeKeepsTheTransform() {
        val info = SmartObjectInfo("a", sourceWidth = 400, sourceHeight = 300)
        val next = info.withReplacedSource("b", 400, 300, 400, 300)
        assertEquals(info.transform, next.transform)
        assertEquals("b", next.sourceKey)
    }

    @Test
    fun smallerSourceIsScaledUpToFillTheOldExtent() {
        val info = SmartObjectInfo("a", sourceWidth = 400, sourceHeight = 300)
        val next = info.withReplacedSource("b", 64, 64, 400, 300)
        // 64×64 fits a 400×300 box at scale 300/64, centered horizontally.
        val scale = 300f / 64f
        val topLeft = map(next, 0f, 0f)
        val bottomRight = map(next, 64f, 64f)
        assertEquals((400f - 64f * scale) / 2f, topLeft.x, 0.01f)
        assertEquals(0f, topLeft.y, 0.01f)
        assertEquals(topLeft.x + 64f * scale, bottomRight.x, 0.01f)
        assertEquals(300f, bottomRight.y, 0.01f)
    }

    @Test
    fun unknownOldSizeFallsBackToTheDocument() {
        val next = SmartObjectInfo("a").withReplacedSource("b", 200, 100, 400, 200)
        assertEquals(2f, map(next, 100f, 0f).x / 100f, 0.01f)
    }
}
