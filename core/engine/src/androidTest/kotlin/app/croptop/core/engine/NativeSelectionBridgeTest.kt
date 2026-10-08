package app.croptop.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSelectionBridgeTest {
    @Test
    fun rasterizeContourModifyCombineAndReleaseHandles() {
        val engine = NativeEngine(ApplicationProvider.getApplicationContext<Context>())
        val handles = mutableListOf<Long>()
        fun keep(handle: Long): Long {
            assertNotEquals("native selection operation failed: ${NativeSelectionBridge.nativeLastError()}", 0L, handle)
            handles += handle
            return handle
        }

        try {
            val original = keep(NativeSelectionBridge.rasterizeRect(32, 32, 4.0, 4.0, 20.0, 20.0, false))
            assertArrayEquals(intArrayOf(4, 4, 20, 20), NativeSelectionBridge.bounds(original))

            val contour = NativeSelectionBridge.contour(original, intArrayOf(0, 0, 32, 32), 0, 32768, 20_000)
            assertFalse("contour should fit under its limit", contour[0] != 0)
            assertTrue("contour should produce boundary segments", contour[1] > 0)
            assertEquals(2 + contour[1] * 4, contour.size)

            val expanded = keep(NativeSelectionBridge.modify(original, 1, 2.0f))
            val expandedBounds = checkNotNull(NativeSelectionBridge.bounds(expanded))
            assertTrue("expand should extend beyond the original bounds", expandedBounds[0] < 4 && expandedBounds[2] > 20)

            val cutout = keep(NativeSelectionBridge.rasterizeEllipse(32, 32, 10.0, 10.0, 14.0, 14.0, true))
            val combined = keep(NativeSelectionBridge.combine(expanded, cutout, 2))
            assertArrayEquals(expandedBounds, NativeSelectionBridge.bounds(combined))

            val wrongDimensions = keep(NativeSelectionBridge.rasterizeRect(16, 16, 1.0, 1.0, 8.0, 8.0, false))
            assertEquals(0L, NativeSelectionBridge.combine(original, wrongDimensions, 0))
            assertEquals("selection masks must have matching dimensions", NativeSelectionBridge.nativeLastError())
            keep(NativeSelectionBridge.combine(original, cutout, 0))
            assertEquals("a successful call clears the prior failure", "", NativeSelectionBridge.nativeLastError())

            assertEquals(0L, NativeSelectionBridge.modify(original, 99, 1.0f))
            assertTrue("invalid operation should report an error", NativeSelectionBridge.nativeLastError().isNotBlank())
        } finally {
            handles.asReversed().forEach(NativeSelectionBridge::destroyStore)
            engine.close()
        }
    }

    @Test
    fun polygonInvertTransformColorSelectionAndQuickSelection() {
        val engine = NativeEngine(ApplicationProvider.getApplicationContext<Context>())
        val bridgeStores = mutableListOf<Long>()
        val engineStores = mutableListOf<Long>()
        val sessions = mutableListOf<Long>()
        fun keep(handle: Long): Long {
            assertNotEquals("native selection operation failed: ${NativeSelectionBridge.nativeLastError()}", 0L, handle)
            bridgeStores += handle
            return handle
        }

        try {
            val polygon = keep(
                NativeSelectionBridge.rasterizePolygon(
                    16,
                    12,
                    doubleArrayOf(1.0, 1.0, 6.0, 1.0, 6.0, 6.0, 1.0, 6.0),
                    false,
                ),
            )
            assertArrayEquals(intArrayOf(1, 1, 6, 6), NativeSelectionBridge.bounds(polygon))

            assertEquals(0L, NativeSelectionBridge.rasterizePolygon(16, 12, doubleArrayOf(1.0, 2.0, 3.0), false))
            assertTrue("odd polygon coordinates should report an error", NativeSelectionBridge.nativeLastError().isNotBlank())

            val inverted = keep(NativeSelectionBridge.invert(polygon))
            assertArrayEquals(intArrayOf(0, 0, 16, 12), NativeSelectionBridge.bounds(inverted))

            val translated = keep(
                NativeSelectionBridge.transform(
                    polygon,
                    doubleArrayOf(1.0, 0.0, 2.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0),
                    1,
                ),
            )
            assertArrayEquals(intArrayOf(3, 2, 8, 7), NativeSelectionBridge.bounds(translated))
            assertEquals(0L, NativeSelectionBridge.transform(polygon, doubleArrayOf(1.0, 0.0), 1))
            assertTrue("short transform matrix should report an error", NativeSelectionBridge.nativeLastError().isNotBlank())
            assertEquals(
                0L,
                NativeSelectionBridge.transform(
                    polygon,
                    doubleArrayOf(1.0, 2.0, 0.0, 2.0, 4.0, 0.0, 0.0, 0.0, 1.0),
                    1,
                ),
            )
            assertTrue("singular transform should report an error", NativeSelectionBridge.nativeLastError().isNotBlank())

            val source = keepEngineStore(engine, 16, 8)
            engineStores += source
            assertEquals(0L, NativeSelectionBridge.combine(polygon, source, 0))
            assertEquals("selection handle must contain an A16 mask", NativeSelectionBridge.nativeLastError())
            keep(NativeSelectionBridge.combine(polygon, polygon, 0))
            assertEquals("", NativeSelectionBridge.nativeLastError())
            val png = twoColorPng()
            assertEquals(null, engine.decodeToTileStore(png, png.size, source, 1))

            val wand = keep(NativeSelectionBridge.magicWand(source, 2, 3, 0, true))
            assertArrayEquals(intArrayOf(0, 0, 8, 8), NativeSelectionBridge.bounds(wand))
            assertEquals(0L, NativeSelectionBridge.magicWand(source, 2, 3, 256, true))
            assertTrue("invalid wand tolerance should report an error", NativeSelectionBridge.nativeLastError().isNotBlank())

            val colorRange = keep(
                NativeSelectionBridge.colorRange(source, 0, floatArrayOf(1.0f, 0.0f, 0.0f), 0, false, 1),
            )
            assertArrayEquals(intArrayOf(0, 0, 8, 8), NativeSelectionBridge.bounds(colorRange))
            assertEquals(0L, NativeSelectionBridge.colorRange(source, 99, floatArrayOf(), 40, true, 1))
            assertTrue("unknown color target should report an error", NativeSelectionBridge.nativeLastError().isNotBlank())

            val before = keep(NativeSelectionBridge.rasterizeRect(16, 8, 0.0, 0.0, 0.0, 0.0, false))
            val session = NativeSelectionBridge.quickBegin(source, before, 4.0f, false, true)
            assertNotEquals("Quick Selection begin failed: ${NativeSelectionBridge.nativeLastError()}", 0L, session)
            sessions += session

            assertFalse(NativeSelectionBridge.quickAdd(session, floatArrayOf(2.0f, 2.0f, 3.0f)))
            assertTrue("odd Quick Selection coordinates should report an error", NativeSelectionBridge.nativeLastError().isNotBlank())
            assertTrue(NativeSelectionBridge.quickAdd(session, floatArrayOf(3.0f, 4.0f, 4.0f, 4.0f)))
            val preview = keep(NativeSelectionBridge.quickCurrent(session))
            assertTrue("Quick Selection preview should contain the stroke", NativeSelectionBridge.bounds(preview).isNotEmpty())
            val finished = keep(NativeSelectionBridge.quickFinish(session))
            assertTrue("Quick Selection finish should preserve the result", NativeSelectionBridge.bounds(finished).isNotEmpty())

            assertEquals(0L, NativeSelectionBridge.quickCurrent(0L))
            assertTrue("invalid Quick Selection session should report an error", NativeSelectionBridge.nativeLastError().isNotBlank())
        } finally {
            sessions.asReversed().forEach(NativeSelectionBridge::quickDestroy)
            bridgeStores.asReversed().forEach(NativeSelectionBridge::destroyStore)
            engineStores.asReversed().forEach(engine::destroyTileStore)
            engine.close()
        }
    }

    private fun keepEngineStore(engine: NativeEngine, width: Int, height: Int): Long {
        val handle = engine.createTileStore(width, height, 0)
        assertNotEquals("could not create RGBA8 source store", 0L, handle)
        return handle
    }

    private fun twoColorPng(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) setPixel(x, y, if (x < 8) Color.RED else Color.BLUE)
            }
            check(compress(Bitmap.CompressFormat.PNG, 100, output))
            recycle()
        }
        output.toByteArray()
    }
}
