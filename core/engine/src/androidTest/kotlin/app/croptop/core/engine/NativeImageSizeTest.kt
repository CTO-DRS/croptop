package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.Resample
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorPrimitiveMetadata
import app.croptop.core.model.VectorShapeKind
import app.croptop.core.model.VectorSourceBox
import app.croptop.core.model.VectorSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeImageSizeTest {
    @Test
    fun fractionalBoundsRoundOutAndPpiOnlyChangePreservesPixelsExactly() {
        assertEquals(DocRect(0, 0, 2, 2), imageSizeBounds(DocRect(0, 0, 2, 2), DocSize(3, 3), DocSize(2, 2)))

        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "PPI only", size = DocSize(2, 1), resolutionPpi = 144f, background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            dab(document, layer, 0, RgbaColor(0.2f, 0.7f, 0.9f))
            val originalPixel = document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color
            val history = document.history.value.current
            assertTrue(document.resizeImage(DocSize(2, 1), Resample.LANCZOS, 300f) is CommandResult.Accepted)
            assertEquals(history + 1, document.history.value.current)
            assertEquals(300f, document.info.value.resolutionPpi, 0f)
            assertEquals(originalPixel, document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(144f, document.info.value.resolutionPpi, 0f)
            assertEquals(originalPixel, document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun nearestResizeScalesPixelsShapesSelectionPpiAndHistoryAtomically() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Image size", size = DocSize(2, 2), resolutionPpi = 144f, background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        val path = VectorPathDraft(
            id = VectorPathId(73),
            name = "scaled primitive",
            subpaths = listOf(
                VectorSubpath(
                    listOf(
                        PathAnchor(PathAnchorId(1), DocPoint(0.5f, 0.25f), outgoing = DocPoint(0.25f, 0.125f)),
                        PathAnchor(PathAnchorId(2), DocPoint(1.5f, 0.75f)),
                    ),
                ),
            ),
            primitive = VectorPrimitiveMetadata(
                kind = VectorShapeKind.RECTANGLE,
                sourceBox = VectorSourceBox(0.25f, 0.125f, 1.25f, 0.625f),
            ),
        )
        try {
            val pixelLayer = checkNotNull(document.layers.value.primary)
            dab(document, pixelLayer, 0, RgbaColor(1f, 0f, 0f))
            dab(document, pixelLayer, 1, RgbaColor(0f, 0f, 1f))
            val originalRed = document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color
            val originalBlue = document.sample(DocPoint(1.5f, 0.5f), SampleMode.ACTIVE_LAYER).color
            assertTrue(document.createShapeLayer(LayerId(730), "Shape", listOf(path)) is CommandResult.Accepted)
            document.setActiveLayers(setOf(pixelLayer), pixelLayer)
            document.select(SelectionShape.Rect(DocRectF(0f, 0f, 2f, 2f)))
            val history = document.history.value.current

            assertTrue(document.resizeImage(DocSize(3, 3), Resample.NEAREST, 300f) is CommandResult.Accepted)
            assertEquals(history + 1, document.history.value.current)
            assertEquals("Image Size", document.history.value.entries.last().label)
            assertEquals(DocSize(3, 3), document.info.value.size)
            assertEquals(300f, document.info.value.resolutionPpi, 0f)
            assertEquals(originalRed, document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(originalBlue, document.sample(DocPoint(2.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(SelectionInfo.Active(DocRect(0, 0, 3, 3)), document.selection.value)

            val scaled = document.layers.value.find(LayerId(730))!!.vectorPaths.single()
            val first = scaled.subpaths.single().anchors.first()
            assertEquals(DocPoint(0.75f, 0.375f), first.point)
            assertEquals(DocPoint(0.375f, 0.1875f), first.outgoing)
            assertEquals(VectorSourceBox(0.375f, 0.1875f, 1.875f, 0.9375f), scaled.primitive!!.sourceBox)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(DocSize(2, 2), document.info.value.size)
            assertEquals(144f, document.info.value.resolutionPpi, 0f)
            assertEquals(SelectionInfo.Active(DocRect(0, 0, 2, 2)), document.selection.value)
            assertEquals(originalRed, document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(path, document.layers.value.find(LayerId(730))!!.vectorPaths.single())
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(DocSize(3, 3), document.info.value.size)
            assertEquals(300f, document.info.value.resolutionPpi, 0f)
            assertEquals(originalBlue, document.sample(DocPoint(2.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun bilinearAndLanczosUseTheirRequestedFilters() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Filter choice", size = DocSize(4, 1), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val colors = listOf(
                RgbaColor(0f, 0f, 0f),
                RgbaColor.White,
                RgbaColor(0f, 0f, 0f),
                RgbaColor.White,
            )
            colors.forEachIndexed { x, color -> dab(document, layer, x, color) }
            assertTrue(document.resizeImage(DocSize(9, 1), Resample.BILINEAR) is CommandResult.Accepted)
            val samplePoints = (0..8).map { DocPoint(it + 0.5f, 0.5f) }
            val bilinear = samplePoints.map { document.sample(it, SampleMode.ACTIVE_LAYER).color }
            assertTrue(document.undo() is CommandResult.Accepted)
            assertTrue(document.resizeImage(DocSize(9, 1), Resample.LANCZOS) is CommandResult.Accepted)
            val lanczos = samplePoints.map { document.sample(it, SampleMode.ACTIVE_LAYER).color }
            assertTrue(
                "higher-quality filter must not silently fall back to bilinear",
                bilinear.zip(lanczos).any { (a, b) -> kotlin.math.abs(a.r - b.r) > 0.004f },
            )
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun unsupportedLeavesAndInvalidPpiRejectWithoutChangingHistoryOrPixels() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Rejected image size", size = DocSize(2, 2), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            dab(document, layer, 0, RgbaColor(0f, 1f, 0f))
            document.select(SelectionShape.Rect(DocRectF(0f, 0f, 1f, 1f)))
            val history = document.history.value.current
            val ppi = document.info.value.resolutionPpi
            val pixel = document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color

            val invalidPpi = document.resizeImage(DocSize(4, 2), Resample.BICUBIC, Float.NaN)
            assertTrue(invalidPpi is CommandResult.Rejected && invalidPpi.code == RejectionCode.INVALID_ARGUMENT)
            val oversized = document.resizeImage(DocSize(70000, 70000), Resample.BICUBIC, 240f)
            assertTrue(oversized is CommandResult.Rejected && oversized.code == RejectionCode.INVALID_ARGUMENT)
            assertTrue(
                document.execute(app.croptop.core.engine.api.EditCommand.AddLayer(LayerId(800), "Text", LayerKind.TEXT)) is CommandResult.Accepted,
            )
            document.setActiveLayers(setOf(layer), layer)
            val accepted = document.resizeImage(DocSize(4, 2), Resample.BICUBIC, 240f)
            assertTrue(accepted is CommandResult.Accepted)
            assertEquals(DocSize(4, 2), document.info.value.size)
            assertEquals(240f, document.info.value.resolutionPpi, 0f)
            assertTrue(history < document.history.value.current)
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun dab(document: NativeDocument, layer: LayerId, x: Int, color: RgbaColor) {
        val stroke = document.beginStroke(StrokeSpec(target = layer, color = color, diameter = 1f, hardness = 1f))
        stroke.add(floatArrayOf(x + 0.5f, 0.5f, 1f, 0f, 0f, 0f, 0f))
        assertTrue(stroke.end() is CommandResult.Accepted)
    }
}
