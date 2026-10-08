package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlendModeTest {
    @Test
    fun `27 layer modes plus pass through`() {
        assertEquals(28, BlendMode.entries.size)
        assertEquals(27, BlendMode.availableFor(LayerKind.PIXEL).size)
        assertTrue(BlendMode.PASS_THROUGH in BlendMode.availableFor(LayerKind.GROUP))
        assertFalse(BlendMode.PASS_THROUGH in BlendMode.availableFor(LayerKind.PIXEL))
    }

    @Test
    fun `psd keys are unique 4-char and round-trip`() {
        BlendMode.entries.forEach {
            assertEquals(4, it.psdKey.length, it.name)
            assertEquals(it, BlendMode.fromPsdKey(it.psdKey))
        }
    }

    @Test
    fun `shortcut letters are unique`() {
        val keys = BlendMode.entries.mapNotNull { it.shortcutKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `groups keep menu order`() {
        val groups = BlendMode.entries.map { it.group }
        assertEquals(groups.sortedBy { it.ordinal }, groups)
    }

    @Test
    fun `new group layer defaults to pass through`() {
        assertEquals(BlendMode.PASS_THROUGH, LayerNode(LayerId(1), "G", LayerKind.GROUP).blendMode)
        assertEquals(BlendMode.NORMAL, LayerNode(LayerId(2), "L", LayerKind.PIXEL).blendMode)
    }
}
