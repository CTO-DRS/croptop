package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeColorManagementConvertTest {
    @Test
    fun conversionReturnsOwnedStoreAndLeavesSourcePixelsUntouched() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Color conversion primitive", size = DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        var converted = 0L
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(StrokeSpec(target = layer, color = RgbaColor(0.8f, 0.2f, 0.05f), diameter = 18f))
            stroke.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val source = checkNotNull((document as NativeDocument).pixelStore(layer))
            val before = NativeImageOpsBridge.histogram(source)
            val fromIcc = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3).use { it.iccBytes() }
            val toIcc = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.SRGB).use { it.iccBytes() }

            converted = NativeColorManagementBridge.convertTileStore(
                sourceHandle = source,
                sourceIcc = fromIcc,
                targetIcc = toIcc,
                outputFormat = NativeColorManagementBridge.RgbaFormat.RGBA16,
                intent = NativeColorManagementBridge.ConversionIntent.RELATIVE_COLORIMETRIC,
                blackPointCompensation = true,
                workerThreads = 1,
            )

            assertNotEquals("conversion returns a separately owned TileStore", source, converted)
            val after = NativeImageOpsBridge.histogram(source)
            assertArrayEquals(before.red, after.red, 0.0)
            assertArrayEquals(before.green, after.green, 0.0)
            assertArrayEquals(before.blue, after.blue, 0.0)
            val output = NativeImageOpsBridge.histogram(converted)
            assertEquals("conversion preserves alpha-weighted pixel coverage", before.total, output.total, 1e-6)
            assertTrue(output.red.sum() > 0.0)
        } finally {
            if (converted != 0L) NativeSelectionBridge.destroyStore(converted)
            document.close()
            engine.close()
        }
    }

    @Test
    fun conversionRejectsNonRgbProfilesAndInvalidOptions() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Invalid conversion inputs", size = DocSize(8, 8), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val source = checkNotNull(document.layers.value.primary?.let(document::pixelStore))
            val rgb = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.SRGB).use { it.iccBytes() }
            val cmyk = NativeColorManagementBridge.Profile.genericCmyk().use { it.iccBytes() }
            assertThrows(IllegalStateException::class.java) {
                NativeColorManagementBridge.convertTileStore(source, cmyk, rgb, NativeColorManagementBridge.RgbaFormat.RGBA8)
            }
            assertThrows(IllegalArgumentException::class.java) {
                NativeColorManagementBridge.convertTileStore(source, rgb, rgb, NativeColorManagementBridge.RgbaFormat.RGBA8, workerThreads = 33)
            }
        } finally {
            document.close()
            engine.close()
        }
    }
}
