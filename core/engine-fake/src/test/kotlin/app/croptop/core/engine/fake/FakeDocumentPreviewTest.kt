package app.croptop.core.engine.fake

import app.croptop.core.engine.api.DocumentPreview
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class FakeDocumentPreviewTest {
    @Test
    fun `preview is bounded aspect fit merged sRGB RGBA`() = runTest {
        val color = RgbaColor(1f, 0.5f, 0f, 0.25f)
        val document = FakeEngine { _, _, mode ->
            check(mode == SampleMode.ALL_LAYERS)
            color
        }.newDocument(NewDocumentSpec("Preview", DocSize(8, 4)))

        val preview = checkNotNull(document.preview(4))

        assertEquals(4, preview.width)
        assertEquals(2, preview.height)
        assertContentEquals(byteArrayOf(255.toByte(), 128.toByte(), 0, 64), preview.rgba.sliceArray(0..3))
        assertEquals(4 * 2 * 4, preview.rgba.size)
    }

    @Test
    fun `preview rejects invalid bounds and returns null after close`() = runTest {
        val document = FakeEngine().newDocument(NewDocumentSpec("Preview", DocSize(8, 4)))

        assertFailsWith<IllegalArgumentException> { document.preview(0) }
        document.close()

        assertEquals(null, document.preview(4))
    }
}
