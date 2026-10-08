package app.croptop.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeUprightBridgeTest {
    @Test
    fun manualAnalysisAndRenderCrossTheRegisteredJniBridge() {
        val engine = NativeEngine()
        val source = engine.createTileStore(64, 48, 0)
        var rendered = 0L
        try {
            assertTrue("RGBA source store must be created", source != 0L)
            val result = NativeUprightBridge.analyze(
                layerHandle = source,
                mode = NativeUprightBridge.Mode.MANUAL,
                manual = NativeUprightBridge.Manual(rotate = 7.0, scale = 110.0),
                autoCrop = false,
            )
            assertEquals(64, result.width)
            assertEquals(48, result.height)
            assertNotEquals("manual rotation should produce a transform", 0.0, result.matrix[1], 1e-8)
            assertTrue("missing focal metadata should be surfaced", result.notes.isNotBlank())

            rendered = NativeUprightBridge.render(source, result, NativeUprightBridge.Filter.BILINEAR)
            assertTrue("render must return an owned store", rendered != 0L)
            val sample = engine.samplePixels(
                listOf(NativeRenderLayer(rendered, 1, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true)),
                app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER,
                primaryLayerId = 1,
                x = 32,
                y = 24,
                size = 1,
            )
            assertEquals(0f, checkNotNull(sample).a, 0f)
        } finally {
            if (rendered != 0L) engine.destroyTileStore(rendered)
            if (source != 0L) engine.destroyTileStore(source)
            engine.close()
        }
    }

    @Test
    fun invalidManualMetadataReturnsAnActionableBridgeError() {
        val engine = NativeEngine()
        val source = engine.createTileStore(64, 48, 0)
        try {
            try {
                NativeUprightBridge.analyze(
                    layerHandle = source,
                    mode = NativeUprightBridge.Mode.MANUAL,
                    manual = NativeUprightBridge.Manual(rotate = Double.NaN),
                )
                throw AssertionError("non-finite manual input should fail")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("manual geometry value"))
            }
        } finally {
            if (source != 0L) engine.destroyTileStore(source)
            engine.close()
        }
    }
}
