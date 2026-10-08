package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContentAwareOptionsTest {
    private fun stroke(add: Boolean = true) = SamplingStroke(listOf(DocPoint(1f, 2f), DocPoint(5f, 6f)), 10f, add)

    @Test
    fun defaultsMatchPhotoshopAuto() {
        val o = ContentAwareOptions()
        assertEquals(ContentAwareRotation.NONE, o.rotation)
        assertEquals(true, o.colorAdaptation)
        assertEquals(false, o.samplingOnlyPainted)
    }

    @Test
    fun paintedOnlySamplingNeedsAnAddedArea() {
        assertFailsWith<IllegalArgumentException> { ContentAwareOptions(samplingOnlyPainted = true) }
        assertFailsWith<IllegalArgumentException> { ContentAwareOptions(samplingOnlyPainted = true, strokes = listOf(stroke(add = false))) }
        ContentAwareOptions(samplingOnlyPainted = true, strokes = listOf(stroke()))
    }

    @Test
    fun rejectsBadStrokes() {
        assertFailsWith<IllegalArgumentException> { SamplingStroke(emptyList(), 5f) }
        assertFailsWith<IllegalArgumentException> { SamplingStroke(listOf(DocPoint(0f, 0f)), 0f) }
        assertFailsWith<IllegalArgumentException> { SamplingStroke(listOf(DocPoint(Float.NaN, 0f)), 5f) }
        assertFailsWith<IllegalArgumentException> { ContentAwareOptions(strokes = List(65) { stroke() }) }
    }
}
