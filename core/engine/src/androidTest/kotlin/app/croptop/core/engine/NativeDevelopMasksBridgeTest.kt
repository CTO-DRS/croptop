package app.croptop.core.engine

import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BasicTone
import app.croptop.core.model.ColorDepth
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDevelopMasksBridgeTest {
    @Test
    fun developAdjustmentIsAppliedOnlyUnderItsA16Mask() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                "Local Develop adjustment",
                DocSize(32, 32),
                depth = ColorDepth.U16,
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        val stores = mutableListOf<Long>()
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is app.croptop.core.engine.api.CommandResult.Accepted)
            val source = document.flattenedContents().also(stores::add)
            val mask = NativeDevelopMasksBridge.radial(32, 32, 16.0, 16.0, 10.0, 10.0, feather = 0.2).also(stores::add)
            val output = NativeDevelopMasksBridge.renderLocalAdjustment(
                source,
                mask,
                DevelopSettings(basic = BasicTone(exposure = -2f)),
                useGpu = false,
                workerThreads = 2,
            ).also(stores::add)

            val center = engine.samplePixels(
                listOf(NativeRenderLayer(output, 1L, 0, 255, 255, true)),
                SampleMode.ACTIVE_LAYER,
                1L,
                16,
                16,
                1,
            )!!
            val outside = engine.samplePixels(
                listOf(NativeRenderLayer(output, 1L, 0, 255, 255, true)),
                SampleMode.ACTIVE_LAYER,
                1L,
                0,
                0,
                1,
            )!!
            assertTrue("Develop exposure should darken the covered center", center.r < 0.4f)
            assertTrue("the mask should preserve the source outside its coverage", outside.r > 0.9f)
            assertEquals(1f, outside.a, 0.001f)
        } finally {
            stores.asReversed().forEach(NativeSelectionBridge::destroyStore)
            document.close()
            engine.close()
        }
    }

    @Test
    fun generatorsAlgebraAndMaskedBlendCrossTheJniBoundary() = runBlocking {
        val engine = NativeEngine()
        val baseDoc = engine.newDocument(
            NewDocumentSpec("Mask base", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        val adjustedDoc = engine.newDocument(
            NewDocumentSpec("Mask adjustment", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        val stores = mutableListOf<Long>()
        try {
            assertTrue(baseDoc.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))) is app.croptop.core.engine.api.CommandResult.Accepted)
            assertTrue(adjustedDoc.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f))) is app.croptop.core.engine.api.CommandResult.Accepted)
            val base = (baseDoc as NativeDocument).flattenedContents().also(stores::add)
            val adjusted = (adjustedDoc as NativeDocument).flattenedContents().also(stores::add)

            val linear = NativeDevelopMasksBridge.linearGradient(32, 32, 0.0, 0.0, 32.0, 0.0).also(stores::add)
            val radial = NativeDevelopMasksBridge.radial(32, 32, 16.0, 16.0, 12.0, 12.0, feather = 0.2).also(stores::add)
            val luminance = NativeDevelopMasksBridge.luminanceRange(base, 0f, 1f, 0.1f).also(stores::add)
            val color = NativeDevelopMasksBridge.colorRange(base, 1f, 0f, 0f).also(stores::add)
            val brushed = NativeDevelopMasksBridge.brushDab(radial, 16.0, 16.0, 10.0, feather = 0.25).also(stores::add)
            val combined = NativeDevelopMasksBridge.combine(radial, brushed, NativeDevelopMasksBridge.Op.ADD).also(stores::add)
            val output = NativeDevelopMasksBridge.applyMasked(base, adjusted, combined).also(stores::add)

            val combinedBounds = NativeSelectionBridge.bounds(combined)
            assertEquals(4, combinedBounds.size)
            assertTrue("center should be covered by radial + brush masks", combinedBounds[0] <= 16 && combinedBounds[2] >= 17)
            assertTrue("linear gradient bridge returns a populated mask", NativeSelectionBridge.bounds(linear).isNotEmpty())
            val center = engine.samplePixels(
                listOf(NativeRenderLayer(output, 1L, 0, 255, 255, true)),
                SampleMode.ACTIVE_LAYER,
                1L,
                16,
                16,
                1,
            )!!
            assertEquals(0f, center.r, 0.03f)
            assertEquals(1f, center.b, 0.03f)

            val edge = engine.samplePixels(
                listOf(NativeRenderLayer(output, 1L, 0, 255, 255, true)),
                SampleMode.ACTIVE_LAYER,
                1L,
                0,
                0,
                1,
            )!!
            assertTrue("outside the localized mask the base color is preserved", edge.r > 0.9f)
            assertTrue("color-range bridge returns a populated mask", NativeSelectionBridge.bounds(color).isNotEmpty())
            assertTrue("luminance-range bridge returns a populated mask", NativeSelectionBridge.bounds(luminance).isNotEmpty())
        } finally {
            stores.asReversed().forEach(NativeSelectionBridge::destroyStore)
            baseDoc.close()
            adjustedDoc.close()
            engine.close()
        }
    }
}

class NativeDevelopAiMaskTest {
    @Test
    fun anAiMaskBitmapBrightensOnlyItsSideThroughTheDevelopJni() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("AI mask", DocSize(256, 256), depth = ColorDepth.U16, background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        var rendered = 0L
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.25f, 0.25f, 0.25f, 1f))) is app.croptop.core.engine.api.CommandResult.Accepted)
            val source = checkNotNull(document.pixelStore(checkNotNull(document.layers.value.primary)))
            val bytes = ByteArray(64 * 64) { if (it % 64 < 32) -1 else 0 }
            val settings = DevelopSettings(
                masks = listOf(
                    app.croptop.core.model.LocalMask(
                        name = "Subject",
                        adjust = BasicTone(exposure = 1.5f),
                        components = listOf(app.croptop.core.model.MaskComponent.ai(app.croptop.core.model.AiMaskBitmap.of(64, 64, bytes, "Subject"))),
                    ),
                ),
            )
            val output = NativeDevelopBridge.render(source, settings, useGpu = false)
            rendered = output.storeHandle
            assertTrue("the AI mask render should not be rejected: ${output.warnings}", rendered != 0L)
            fun red(x: Int) = engine.samplePixels(listOf(NativeRenderLayer(rendered, 1L, 0, 255, 255, true)), SampleMode.ACTIVE_LAYER, 1L, x, 128, 1)!!.r
            val inside = red(20)
            val outside = red(236)
            assertTrue("inside the AI mask is lifted ($inside vs $outside)", inside > outside * 1.5f)
            assertEquals("outside keeps the source", 0.25f, outside, 0.03f)
        } finally {
            if (rendered != 0L) NativeSelectionBridge.destroyStore(rendered)
            document.close()
            engine.close()
        }
    }
}
