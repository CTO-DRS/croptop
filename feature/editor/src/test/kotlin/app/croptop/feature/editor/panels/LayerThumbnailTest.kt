package app.croptop.feature.editor.panels

import android.graphics.Color
import app.croptop.core.engine.api.LayerThumb
import app.croptop.core.model.LayerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayerThumbnailTest {
    @Test
    fun `thumbnail RGBA bytes become straight ARGB colors without losing aspect dimensions`() {
        val thumb = LayerThumb(
            layerId = LayerId(4),
            width = 2,
            height = 1,
            // Half-transparent red followed by opaque blue, both in straight sRGB RGBA.
            rgba = byteArrayOf(0xFF.toByte(), 0, 0, 0x80.toByte(), 0, 0, 0xFF.toByte(), 0xFF.toByte()),
        )

        assertContentEquals(
            intArrayOf(Color.argb(128, 255, 0, 0), Color.argb(255, 0, 0, 255)),
            layerThumbArgbPixels(thumb),
        )
        val image = thumb.toImageBitmapOrNull()
        assertEquals(2, image?.width)
        assertEquals(1, image?.height)
    }

    @Test
    fun `malformed or oversized thumbnails are rejected before allocating pixels`() {
        assertNull(layerThumbArgbPixels(LayerThumb(LayerId(1), 0, 1, byteArrayOf())))
        assertNull(layerThumbArgbPixels(LayerThumb(LayerId(1), 2, 1, byteArrayOf(0, 1, 2, 3))))
        assertNull(layerThumbArgbPixels(LayerThumb(LayerId(1), 513, 1, ByteArray(513 * 4))))
        assertNull(layerThumbArgbPixels(LayerThumb(LayerId(1), Int.MAX_VALUE, Int.MAX_VALUE, byteArrayOf())))
    }
}
