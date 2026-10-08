package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLayerViaCutTest {
    @Test
    fun noSelectionMovesWholePrimaryLayerWithOneUndoStep() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Layer via Cut whole layer", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val source = checkNotNull(document.layers.value.primary)
            val original = RgbaColor(0.82f, 0.22f, 0.08f, 1f)
            assertTrue(document.fill(FillSource.Solid(original)) is CommandResult.Accepted)
            val beforeHistorySize = document.history.value.entries.size
            val copy = LayerId(9201)

            assertTrue(document.layerViaCut(copy) is CommandResult.Accepted)
            assertEquals(beforeHistorySize + 1, document.history.value.entries.size)
            assertEquals(copy, document.layers.value.primary)
            assertEquals("${document.layers.value.find(source)?.name} copy", document.layers.value.find(copy)?.name)
            assertEquals(SelectionInfo.None, document.selection.value)
            val copied = document.sample(DocPoint(12.5f, 12.5f), SampleMode.ACTIVE_LAYER).color
            assertEquals(original.r, copied.r, 0.01f)
            assertEquals(original.a, copied.a, 0.01f)
            assertTrue(document.setActiveLayers(setOf(source), source) is CommandResult.Accepted)
            assertEquals(0f, document.sample(DocPoint(12.5f, 12.5f), SampleMode.ACTIVE_LAYER).color.a, 0.001f)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertFalse(document.layers.value.flatten().any { it.id == copy })
            assertEquals(original.a, document.sample(DocPoint(12.5f, 12.5f), SampleMode.ACTIVE_LAYER).color.a, 0.01f)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertTrue(document.layers.value.flatten().any { it.id == copy })
            assertTrue(document.setActiveLayers(setOf(copy), copy) is CommandResult.Accepted)
            assertEquals(original.a, document.sample(DocPoint(12.5f, 12.5f), SampleMode.ACTIVE_LAYER).color.a, 0.01f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun selectionCutMovesOnlySelectedPixelsAndUndoRestoresBothStores() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Layer via Cut selection", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val source = checkNotNull(document.layers.value.primary)
            val original = RgbaColor(0.1f, 0.72f, 0.32f, 1f)
            assertTrue(document.fill(FillSource.Solid(original)) is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 20f, 20f)), antiAlias = false) is CommandResult.Accepted)
            val priorSelection = document.selection.value
            val beforeHistorySize = document.history.value.entries.size
            val cutLayer = LayerId(9202)

            assertTrue(document.layerViaCut(cutLayer) is CommandResult.Accepted)
            assertEquals(beforeHistorySize + 1, document.history.value.entries.size)
            assertEquals(DocRect(8, 8, 20, 20), document.layers.value.find(cutLayer)?.bounds)
            assertEquals(priorSelection, document.selection.value)
            assertEquals(original.g, document.sample(DocPoint(12.5f, 12.5f), SampleMode.ACTIVE_LAYER).color.g, 0.01f)
            assertEquals(0f, document.sample(DocPoint(3.5f, 3.5f), SampleMode.ACTIVE_LAYER).color.a, 0.001f)
            assertTrue(document.setActiveLayers(setOf(source), source) is CommandResult.Accepted)
            assertEquals(0f, document.sample(DocPoint(12.5f, 12.5f), SampleMode.ACTIVE_LAYER).color.a, 0.001f)
            assertEquals(original.a, document.sample(DocPoint(3.5f, 3.5f), SampleMode.ACTIVE_LAYER).color.a, 0.01f)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertFalse(document.layers.value.flatten().any { it.id == cutLayer })
            assertEquals(original.a, document.sample(DocPoint(12.5f, 12.5f), SampleMode.ACTIVE_LAYER).color.a, 0.01f)
            assertEquals(priorSelection, document.selection.value)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertTrue(document.layers.value.flatten().any { it.id == cutLayer })
            assertTrue(document.setActiveLayers(setOf(cutLayer), cutLayer) is CommandResult.Accepted)
            assertEquals(original.g, document.sample(DocPoint(12.5f, 12.5f), SampleMode.ACTIVE_LAYER).color.g, 0.01f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun selectionCutPreservesLayerMaskOnBothSplitStores() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Layer via Cut mask", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val source = checkNotNull(document.layers.value.primary)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.8f, 0.1f, 0.2f, 1f))) is CommandResult.Accepted)
            assertTrue(document.addLayerMask(source, MaskFill.HIDE_ALL) is CommandResult.Accepted)
            assertEquals(0f, document.sample(DocPoint(12.5f, 12.5f), SampleMode.MERGED).color.a, 0.001f)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 20f, 20f)), antiAlias = false) is CommandResult.Accepted)

            val cutLayer = LayerId(9205)
            assertTrue(document.layerViaCut(cutLayer) is CommandResult.Accepted)
            assertTrue(document.layers.value.find(source)?.mask != null)
            assertTrue(document.layers.value.find(cutLayer)?.mask != null)
            assertEquals(0f, document.sample(DocPoint(12.5f, 12.5f), SampleMode.MERGED).color.a, 0.001f)
            assertTrue(document.setActiveLayers(setOf(source), source) is CommandResult.Accepted)
            assertEquals(0f, document.sample(DocPoint(3.5f, 3.5f), SampleMode.MERGED).color.a, 0.001f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun unsupportedPrimaryRejectsBeforeHistory() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Layer via Cut reject", DocSize(24, 24), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val unsupported = LayerId(9204)
            assertTrue(document.execute(EditCommand.AddLayer(unsupported, "Shape", app.croptop.core.model.LayerKind.SHAPE)) is CommandResult.Accepted)
            val historySize = document.history.value.entries.size
            val rejected = document.layerViaCut(LayerId(9203))
            assertTrue(rejected is CommandResult.Rejected)
            assertEquals(historySize, document.history.value.entries.size)
            assertFalse(document.layers.value.flatten().any { it.id == LayerId(9203) })
            assertEquals(unsupported, document.layers.value.primary)
        } finally {
            document.close()
            engine.close()
        }
    }
}
