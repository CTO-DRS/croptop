package app.croptop.core.engine.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentPreviewTest {
    @Test
    fun acceptsBoundedRgbaWithExactByteCount() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)

        val preview = DocumentPreview(width = 1, height = 1, rgba = bytes)

        assertEquals(1, preview.width)
        assertEquals(1, preview.height)
        assertContentEquals(bytes, preview.rgba)
    }

    @Test
    fun rejectsInvalidDimensionsAndByteCount() {
        assertFailsWith<IllegalArgumentException> { DocumentPreview(0, 1, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> {
            DocumentPreview(DocumentPreview.MAX_DIMENSION + 1, 1, byteArrayOf())
        }
        assertFailsWith<IllegalArgumentException> { DocumentPreview(2, 1, byteArrayOf(0, 1, 2, 3)) }
    }
}
