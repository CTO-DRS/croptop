package app.croptop.core.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.SampleMode
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLensBlurBridgeTest {
    @Test
    fun lensBlurAcceptsA16DepthRangeOutputFromNativeBridge() {
        val engine = NativeEngine(ApplicationProvider.getApplicationContext<Context>())
        var source = 0L
        var depthSelection = 0L
        var depth = 0L
        var blurred = 0L
        val sourceBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
            setPixel(32, 32, android.graphics.Color.WHITE)
        }
        val sourceBytes = ByteArrayOutputStream().also {
            sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        sourceBitmap.recycle()
        try {
            source = engine.createTileStore(64, 64, 0)
            assertNotEquals(0L, source)
            assertEquals(null, engine.decodeToTileStore(sourceBytes, sourceBytes.size, source, codec = 1, exifOrientation = 1))

            depthSelection = NativeSelectionBridge.rasterizeRect(64, 64, 0.0, 0.0, 64.0, 64.0, false)
            assertNotEquals(
                "A16 depth selection creation failed: ${NativeSelectionBridge.nativeLastError()}",
                0L,
                depthSelection,
            )
            depth = NativeLensBlurBridge.depthRangeMask(depthSelection, near = 0f, far = 1f, feather = 0f)
            assertNotEquals(0L, depth)
            assertTrue(engine.tileStoreResidentBytes(depth) > 0L)

            blurred = NativeLensBlurBridge.lensBlur(
                source,
                depth,
                NativeLensBlurBridge.Spec(radius = 8f, focalDistance = 0f, irisBlades = 0, workerThreads = 2),
            )
            val neighbor = checkNotNull(
                engine.samplePixels(
                    listOf(NativeRenderLayer(blurred, 1, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true)),
                    SampleMode.ACTIVE_LAYER,
                    primaryLayerId = 1,
                    x = 34,
                    y = 32,
                    size = 1,
                ),
            )
            assertTrue("A16 depth input should blur the bright point", neighbor.r > 0f)
        } finally {
            if (blurred != 0L) engine.destroyTileStore(blurred)
            if (depth != 0L) engine.destroyTileStore(depth)
            if (depthSelection != 0L) NativeSelectionBridge.destroyStore(depthSelection)
            if (source != 0L) engine.destroyTileStore(source)
            engine.close()
        }
    }

    @Test
    fun farDepthSpreadsBrightPointThroughBridge() {
        val engine = NativeEngine(ApplicationProvider.getApplicationContext<Context>())
        var source = 0L
        var depth = 0L
        var blurred = 0L
        val sourceBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
            setPixel(32, 32, android.graphics.Color.WHITE)
        }
        val sourceBytes = ByteArrayOutputStream().also {
            sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        sourceBitmap.recycle()
        val depthBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        val depthBytes = ByteArrayOutputStream().also {
            depthBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        depthBitmap.recycle()
        try {
            source = engine.createTileStore(64, 64, 0)
            depth = engine.createTileStore(64, 64, 0)
            assertEquals(null, engine.decodeToTileStore(sourceBytes, sourceBytes.size, source, codec = 1, exifOrientation = 1))
            assertEquals(null, engine.decodeToTileStore(depthBytes, depthBytes.size, depth, codec = 1, exifOrientation = 1))

            blurred = NativeLensBlurBridge.lensBlur(
                source,
                depth,
                NativeLensBlurBridge.Spec(radius = 8f, focalDistance = 0f, irisBlades = 0, workerThreads = 2),
            )
            fun red(x: Int, y: Int): Float = checkNotNull(
                engine.samplePixels(
                    listOf(NativeRenderLayer(blurred, 1, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true)),
                    SampleMode.ACTIVE_LAYER,
                    primaryLayerId = 1,
                    x = x,
                    y = y,
                    size = 1,
                ),
            ).r

            assertTrue("far point should be blurred", red(32, 32) < 0.9f)
            assertTrue("blur should spread light to its neighbor", red(34, 32) > 0f)
        } finally {
            if (blurred != 0L) engine.destroyTileStore(blurred)
            if (depth != 0L) engine.destroyTileStore(depth)
            if (source != 0L) engine.destroyTileStore(source)
            engine.close()
        }
    }

    @Test
    fun zeroRadiusBridgePreservesDecodedSourcePixels() {
        val engine = NativeEngine(ApplicationProvider.getApplicationContext<Context>())
        var source = 0L
        var blurred = 0L
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(37, 119, 203))
        }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        try {
            source = engine.createTileStore(16, 16, 0)
            assertNotEquals(0L, source)
            assertEquals(null, engine.decodeToTileStore(encoded, encoded.size, source, codec = 1, exifOrientation = 1))

            blurred = NativeLensBlurBridge.lensBlur(source, spec = NativeLensBlurBridge.Spec(radius = 0f))
            val actual = checkNotNull(
                engine.samplePixels(
                    listOf(NativeRenderLayer(blurred, 1, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true)),
                    SampleMode.ACTIVE_LAYER,
                    primaryLayerId = 1,
                    x = 8,
                    y = 8,
                    size = 1,
                ),
            )

            assertEquals(37f / 255f, actual.r, 1f / 255f)
            assertEquals(119f / 255f, actual.g, 1f / 255f)
            assertEquals(203f / 255f, actual.b, 1f / 255f)
            assertEquals(1f, actual.a, 1f / 255f)
        } finally {
            if (blurred != 0L) engine.destroyTileStore(blurred)
            if (source != 0L) engine.destroyTileStore(source)
            engine.close()
        }
    }

    @Test
    fun lensBlurAndDepthRangeMaskReturnOwnedStoresAndSurfaceNativeValidation() {
        val engine = NativeEngine(ApplicationProvider.getApplicationContext<Context>())
        var source = 0L
        var blurred = 0L
        var depthMask = 0L
        try {
            source = engine.createTileStore(16, 16, 0)
            assertNotEquals(0L, source)

            blurred = NativeLensBlurBridge.lensBlur(source, spec = NativeLensBlurBridge.Spec(radius = 0f))
            assertNotEquals(0L, blurred)

            depthMask = NativeLensBlurBridge.depthRangeMask(source, near = 0f, far = 0.5f, feather = 0f)
            assertNotEquals(0L, depthMask)
            assertTrue(engine.tileStoreResidentBytes(depthMask) > 0L)

            val failure = runCatching {
                NativeLensBlurBridge.lensBlur(source, spec = NativeLensBlurBridge.Spec(radius = 101f))
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("out of range"))

            val invalidDepthRange = runCatching {
                NativeLensBlurBridge.depthRangeMask(source, near = 0.75f, far = 0.25f, feather = 0f)
            }.exceptionOrNull()
            assertTrue(invalidDepthRange is IllegalStateException)
            assertTrue(invalidDepthRange?.message.orEmpty().contains("near"))
        } finally {
            if (depthMask != 0L) engine.destroyTileStore(depthMask)
            if (blurred != 0L) engine.destroyTileStore(blurred)
            if (source != 0L) engine.destroyTileStore(source)
            engine.close()
        }
    }
}
