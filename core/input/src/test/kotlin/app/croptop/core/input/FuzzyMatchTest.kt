package app.croptop.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuzzyMatchTest {
    private val labels = listOf("Undo", "Hue/Saturation", "New Layer", "Merge Down", "Image size", "Canvas size", "Levels", "Curves")

    @Test
    fun `prefix beats substring beats subsequence`() {
        assertEquals("Levels", FuzzyMatch.rank("lev", labels) { it }.first())
        assertEquals(listOf("Image size", "Canvas size"), FuzzyMatch.rank("size", labels) { it }.take(2))
        assertEquals("New Layer", FuzzyMatch.rank("nl", labels) { it }.first())
    }

    @Test
    fun `order matters and non matches drop`() {
        assertNull(FuzzyMatch.score("zzz", "Undo"))
        assertNull(FuzzyMatch.score("dnu", "Undo"))
        assertTrue(FuzzyMatch.score("hs", "Hue/Saturation")!! > FuzzyMatch.score("hs", "Merge Down") ?: Int.MIN_VALUE)
    }

    @Test
    fun `empty query keeps everything in order`() {
        assertEquals(labels, FuzzyMatch.rank("", labels) { it })
    }
}
