package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDefringeBridgeTest {
    @Test
    fun removesPurpleFringeAtAnEdgeAndHonorsTheSelectionMask() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Defringe edge", DocSize(32, 16), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        val stores = mutableListOf<Long>()
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 16f, 16f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 1f, 1f))) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)
            val source = document.flattenedContents().also(stores::add)
            val selection = NativeSelectionBridge.rasterizeRect(32, 16, 24.0, 0.0, 26.0, 16.0, false).also(stores::add)

            val spec = NativeDefringeBridge.Spec(purpleAmount = 20f)
            val defringed = NativeDefringeBridge.apply(source, spec, workerThreads = 2).also(stores::add)
            val selectionLimited = NativeDefringeBridge.apply(source, spec, selection, workerThreads = 2).also(stores::add)
            fun sample(handle: Long, x: Int) = checkNotNull(
                engine.samplePixels(
                    listOf(NativeRenderLayer(handle, 1L, 0, 255, 255, true)),
                    SampleMode.ACTIVE_LAYER,
                    1L,
                    x,
                    8,
                    1,
                ),
            )

            val before = sample(source, 15)
            val full = sample(defringed, 15)
            val partial = sample(selectionLimited, 15)
            val sourceChroma = maxOf(before.r, before.g, before.b) - minOf(before.r, before.g, before.b)
            val resultChroma = maxOf(full.r, full.g, full.b) - minOf(full.r, full.g, full.b)
            assertTrue("purple chroma should fall at the high-contrast edge: $before -> $full", resultChroma < sourceChroma)
            assertEquals(before.r, partial.r, 0.01f)
            assertEquals(before.g, partial.g, 0.01f)
            assertEquals(before.b, partial.b, 0.01f)
            assertEquals(before.a, partial.a, 0.001f)
        } finally {
            stores.asReversed().forEach(NativeSelectionBridge::destroyStore)
            document.close()
            engine.close()
        }
    }
}
