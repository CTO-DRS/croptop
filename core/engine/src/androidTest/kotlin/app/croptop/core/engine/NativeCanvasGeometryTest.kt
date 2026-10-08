package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.Anchor
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
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
class NativeCanvasGeometryTest {
    @Test
    fun canvasResizeFlipAndQuarterTurnTransformPixelsSelectionAndHistory() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Canvas geometry", size = DocSize(3, 2), resolutionPpi = 144f, background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            dab(document, layer, 0, 0, RgbaColor(1f, 0f, 0f))
            dab(document, layer, 2, 1, RgbaColor(0f, 1f, 0f))
            val red = document.sample(DocPoint(0.5f, 0.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color
            val green = document.sample(DocPoint(2.5f, 1.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color
            document.select(SelectionShape.Rect(DocRectF(1f, 0f, 3f, 2f)))

            val beforeResize = document.history.value.current
            assertTrue(document.resizeCanvas(DocSize(2, 2), Anchor.RIGHT) is CommandResult.Accepted)
            assertEquals(DocSize(2, 2), document.info.value.size)
            assertEquals(beforeResize + 1, document.history.value.current)
            assertEquals(0f, document.sample(DocPoint(0.5f, 0.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color.a, 0f)
            assertEquals(green, document.sample(DocPoint(1.5f, 1.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color)
            assertEquals(SelectionInfo.Active(app.croptop.core.model.DocRect(0, 0, 2, 2)), document.selection.value)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(DocSize(3, 2), document.info.value.size)
            assertEquals(red, document.sample(DocPoint(0.5f, 0.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(DocSize(2, 2), document.info.value.size)

            assertTrue(document.flipCanvas(horizontal = true) is CommandResult.Accepted)
            assertEquals(green, document.sample(DocPoint(0.5f, 1.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.flipCanvas(horizontal = false) is CommandResult.Accepted)
            assertEquals(green, document.sample(DocPoint(0.5f, 0.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.rotateCanvas(90f) is CommandResult.Accepted)
            assertEquals(DocSize(2, 2), document.info.value.size)
            assertEquals(144f, document.info.value.resolutionPpi, 0f)
            assertEquals(green, document.sample(DocPoint(1.5f, 0.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun rotateTransformsVectorGeometryAndUndoRedoRestoresPixelsAndMetadata() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Shape geometry", size = DocSize(10, 6), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        val path = VectorPathDraft(
            id = VectorPathId(41),
            name = "Curve",
            subpaths = listOf(
                VectorSubpath(
                    listOf(
                        PathAnchor(PathAnchorId(1), DocPoint(2f, 3f), incoming = DocPoint(-1f, 0f), outgoing = DocPoint(0f, 2f)),
                        PathAnchor(PathAnchorId(2), DocPoint(8f, 3f)),
                        PathAnchor(PathAnchorId(3), DocPoint(8f, 5f)),
                    ),
                ),
            ),
            primitive = VectorPrimitiveMetadata(
                kind = VectorShapeKind.RECTANGLE,
                rotationDegrees = 0f,
                sourceBox = VectorSourceBox(2f, 3f, 6f, 2f),
            ),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            dab(document, layer, 1, 2, RgbaColor(0f, 0f, 1f))
            val blue = document.sample(DocPoint(1.5f, 2.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color
            assertTrue(document.createShapeLayer(LayerId(500), "Curve", listOf(path)) is CommandResult.Accepted)
            assertTrue(document.setActiveLayers(setOf(layer), layer) is CommandResult.Accepted)
            document.select(SelectionShape.Rect(DocRectF(1f, 2f, 4f, 5f)))
            val history = document.history.value.current

            assertTrue(document.rotateCanvas(90f) is CommandResult.Accepted)
            assertEquals(history + 1, document.history.value.current)
            assertEquals(DocSize(6, 10), document.info.value.size)
            val rotated = document.layers.value.find(LayerId(500))!!.vectorPaths.single()
            val first = rotated.subpaths.single().anchors.first()
            assertEquals(DocPoint(3f, 2f), first.point)
            assertEquals(DocPoint(0f, -1f), first.incoming)
            assertEquals(DocPoint(-2f, 0f), first.outgoing)
            assertEquals(VectorSourceBox(1f, 2f, 2f, 6f), rotated.primitive!!.sourceBox)
            assertEquals(90f, rotated.primitive!!.rotationDegrees, 0f)
            assertEquals(SelectionInfo.Active(app.croptop.core.model.DocRect(1, 1, 4, 4)), document.selection.value)
            assertEquals(blue, document.sample(DocPoint(3.5f, 1.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(DocSize(10, 6), document.info.value.size)
            assertEquals(path, document.layers.value.find(LayerId(500))!!.vectorPaths.single())
            assertEquals(blue, document.sample(DocPoint(1.5f, 2.5f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(DocSize(6, 10), document.info.value.size)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun arbitraryRotationIsSupportedAndOversizedGeometryIsTypedAsInvalid() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Unsupported geometry", size = DocSize(4, 3), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val startHistory = document.history.value.current
            val coloredFill = document.resizeCanvas(DocSize(5, 3), Anchor.LEFT, app.croptop.core.model.CanvasFill.Color(RgbaColor.White))
            assertTrue(coloredFill is CommandResult.Accepted)
            assertEquals(DocSize(5, 3), document.info.value.size)
            assertTrue(startHistory < document.history.value.current)

            val sizeBefore = document.info.value.size
            val arbitrary = document.rotateCanvas(17f)
            assertTrue(arbitrary.toString(), arbitrary is CommandResult.Accepted)
            assertTrue("an arbitrary angle grows the canvas to hold the rotated picture", document.info.value.size.width > sizeBefore.width)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(sizeBefore, document.info.value.size)
            val oversized = document.resizeCanvas(DocSize(70000, 70000), Anchor.CENTER)
            assertTrue(oversized is CommandResult.Rejected && oversized.code == app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT)

            assertTrue(document.execute(EditCommand.AddLayer(LayerId(900), "Text", LayerKind.TEXT)) is CommandResult.Accepted)
            val afterText = document.history.value.current
            val flipResult = document.flipCanvas(horizontal = true)
            assertTrue(flipResult is CommandResult.Accepted)
            assertTrue(afterText < document.history.value.current)
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun dab(document: NativeDocument, layer: LayerId, x: Int, y: Int, color: RgbaColor) {
        val stroke = document.beginStroke(StrokeSpec(target = layer, color = color, diameter = 1f, hardness = 1f))
        stroke.add(floatArrayOf(x + 0.5f, y + 0.5f, 1f, 0f, 0f, 0f, 0f))
        assertTrue(stroke.end() is CommandResult.Accepted)
    }
}
