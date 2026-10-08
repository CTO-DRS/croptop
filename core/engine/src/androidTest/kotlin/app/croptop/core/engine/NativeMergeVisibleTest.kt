package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillKind
import app.croptop.core.model.FillLayerInfo
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMergeVisibleTest {
    @Test
    fun mergeVisibleReplacesPixelAndFillLayersAndUndoRestoresBoth() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Merge visible fill", DocSize(12, 10), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val pixelLayer = checkNotNull(document.layers.value.primary)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f))) is CommandResult.Accepted)
            val fillLayer = LayerId(82_101)
            assertTrue(
                document.addFillLayer(
                    fillLayer,
                    FillLayerInfo(kind = FillKind.SOLID, color = RgbaColor(1f, 0f, 0f)),
                ) is CommandResult.Accepted,
            )
            val expected = document.sample(DocPoint(6f, 5f), SampleMode.MERGED).color
            val history = document.history.value.current

            assertTrue(document.mergeVisible() is CommandResult.Accepted)
            val mergedLayers = document.layers.value.flatten().filter { it.visible && it.kind != LayerKind.GROUP }
            assertEquals(listOf(LayerKind.PIXEL), mergedLayers.map { it.kind })
            assertEquals(expected, document.sample(DocPoint(6f, 5f), SampleMode.MERGED).color)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(history, document.history.value.current)
            assertEquals(LayerKind.PIXEL, document.layers.value.find(pixelLayer)?.kind)
            assertEquals(LayerKind.FILL, document.layers.value.find(fillLayer)?.kind)
            assertEquals(expected, document.sample(DocPoint(6f, 5f), SampleMode.MERGED).color)

            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(listOf(LayerKind.PIXEL), document.layers.value.flatten().filter { it.visible && it.kind != LayerKind.GROUP }.map { it.kind })
        } finally {
            document.close()
            engine.close()
        }
    }
}
