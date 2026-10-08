package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLayerMaskTest {
    @Test
    fun maskThumbnailReadsAnOwnedSnapshotAndReturnsOpaqueGrayscalePixels() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Mask thumbnail snapshot", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            assertTrue(document.addLayerMask(layer, MaskFill.HIDE_ALL) is CommandResult.Accepted)
            val thumbnail = document.maskThumbnails(listOf(layer), 16).first()
            assertEquals(layer, thumbnail.layerId)
            assertEquals(16, thumbnail.width)
            assertEquals(16, thumbnail.height)
            for (offset in thumbnail.rgba.indices step 4) {
                assertEquals(0, thumbnail.rgba[offset].toInt() and 0xff)
                assertEquals(0, thumbnail.rgba[offset + 1].toInt() and 0xff)
                assertEquals(0, thumbnail.rgba[offset + 2].toInt() and 0xff)
                assertEquals(255, thumbnail.rgba[offset + 3].toInt() and 0xff)
            }
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun addRemoveAndHistoryPreserveMaskCompositing() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Layer mask history", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val color = RgbaColor(0.75f, 0.2f, 0.1f, 1f)
            assertTrue(document.fill(FillSource.Solid(color)) is CommandResult.Accepted)
            val beforeAdd = document.history.value.current

            assertTrue(document.addLayerMask(layer, MaskFill.HIDE_ALL) is CommandResult.Accepted)
            assertEquals(beforeAdd + 1, document.history.value.current)
            assertEquals(true, document.layers.value.find(layer)?.mask?.enabled)
            assertEquals(0f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)

            val beforeInvert = document.history.value.current
            assertTrue(document.invertLayerMask(layer) is CommandResult.Accepted)
            assertEquals(beforeInvert + 1, document.history.value.current)
            assertEquals(color.a, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(0f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)

            val beforeRemove = document.history.value.current
            assertTrue(document.removeLayerMask(layer, apply = false) is CommandResult.Accepted)
            assertEquals(beforeRemove + 1, document.history.value.current)
            assertEquals(null, document.layers.value.find(layer)?.mask)
            assertEquals(color.a, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(true, document.layers.value.find(layer)?.mask?.enabled)
            assertEquals(0f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(null, document.layers.value.find(layer)?.mask)
            assertEquals(color.a, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(true, document.layers.value.find(layer)?.mask?.enabled)
            assertEquals(0f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(null, document.layers.value.find(layer)?.mask)
            assertEquals(color.a, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun selectionFillRequiresSelectionAndApplyCommitsPixelAlpha() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Selection mask", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val rejected = document.addLayerMask(layer, MaskFill.REVEAL_SELECTION)
            assertTrue(rejected is CommandResult.Rejected)
            assertEquals(SelectionInfo.None, document.selection.value)

            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.2f, 0.6f, 0.9f, 1f))) is CommandResult.Accepted)
            assertTrue(
                document.select(SelectionShape.Rect(DocRectF(8f, 8f, 20f, 20f)), antiAlias = false) is CommandResult.Accepted,
            )
            val before = document.history.value.entries.size
            assertTrue(document.addLayerMask(layer, MaskFill.REVEAL_SELECTION) is CommandResult.Accepted)
            assertEquals(before + 1, document.history.value.entries.size)
            assertEquals(1f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
            assertEquals(0f, document.sample(DocPoint(3f, 3f), SampleMode.MERGED).color.a, 0.01f)

            val apply = document.removeLayerMask(layer, apply = true)
            assertTrue(apply is CommandResult.Accepted)
            assertEquals(before + 2, document.history.value.entries.size)
            assertEquals(null, document.layers.value.find(layer)?.mask)
            assertEquals(1f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
            assertEquals(0f, document.sample(DocPoint(3f, 3f), SampleMode.MERGED).color.a, 0.01f)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertTrue(document.layers.value.find(layer)?.mask != null)
            assertEquals(1f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
            assertEquals(0f, document.sample(DocPoint(3f, 3f), SampleMode.MERGED).color.a, 0.01f)
            assertTrue(document.invertLayerMask(layer) is CommandResult.Accepted)
            assertEquals(0f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
            assertEquals(1f, document.sample(DocPoint(3f, 3f), SampleMode.MERGED).color.a, 0.01f)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(1f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
            assertEquals(0f, document.sample(DocPoint(3f, 3f), SampleMode.MERGED).color.a, 0.01f)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(0f, document.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun applyingMaskPreservesClippingThroughUndoAndRedo() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Clipped mask apply", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 16f, 32f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(1f, 1f, 1f, 1f))) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)
            val upper = LayerId(987_654_399L)
            assertTrue(document.execute(EditCommand.AddLayer(upper, "Clipped", LayerKind.PIXEL)) is CommandResult.Accepted)
            assertTrue(document.setActiveLayers(setOf(upper), upper) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f))) is CommandResult.Accepted)
            assertTrue(document.setClipped(upper, true) is CommandResult.Accepted)
            assertTrue(document.addLayerMask(upper, MaskFill.REVEAL_ALL) is CommandResult.Accepted)
            val beforeApply = document.history.value.current
            assertTrue(document.removeLayerMask(upper, apply = true) is CommandResult.Accepted)
            assertEquals(beforeApply + 1, document.history.value.current)
            assertEquals(null, document.layers.value.find(upper)?.mask)
            repeat(3) { step ->
                assertEquals(1f, document.sample(DocPoint(8f, 16f), SampleMode.MERGED).color.a, 0.01f)
                assertEquals(0f, document.sample(DocPoint(24f, 16f), SampleMode.MERGED).color.a, 0.01f)
                when (step) {
                    0 -> assertTrue(document.undo() is CommandResult.Accepted)
                    1 -> assertTrue(document.redo() is CommandResult.Accepted)
                }
            }
        } finally {
            document.close()
            engine.close()
        }
    }
}
