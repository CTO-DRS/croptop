package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorPathStyle
import app.croptop.core.model.VectorPrimitiveMetadata
import app.croptop.core.model.VectorShapeKind
import app.croptop.core.model.VectorSourceBox
import app.croptop.core.model.VectorSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShapeLayerMoveTest {
    @Test
    fun shapeAutoSelectAndFractionalMovePreserveGeometryWithUndoRedo() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Move vector shape", DocSize(80, 72), background = BackgroundContents.TRANSPARENT),
        )
        val shapeId = LayerId(987_650_501L)
        val path = shapePath()
        try {
            assertTrue(document.createShapeLayer(shapeId, "Curve", listOf(path)) is CommandResult.Accepted)
            val pixelId = document.layers.value.flatten().first { it.kind == LayerKind.PIXEL }.id
            assertEquals("shape interior is auto-selectable", shapeId, document.layerAt(DocPoint(16f, 16f)))
            assertTrue(document.sample(DocPoint(16.5f, 16.5f), SampleMode.MERGED).color.r > 0.9f)
            val historyBefore = document.history.value.current

            assertTrue(document.translateLayers(setOf(shapeId), 30.5f, 25.25f) is CommandResult.Accepted)
            assertEquals("one move creates one history step", historyBefore + 1, document.history.value.current)
            assertEquals("history describes the action", "Move", document.history.value.undoLabel)
            val moved = translated(path, 30.5f, 25.25f)
            assertEquals(listOf(moved), document.layers.value.flatten().first { it.id == shapeId }.vectorPaths)
            assertEquals(shapeId, document.layerAt(DocPoint(46.5f, 41.25f)))
            assertEquals(pixelId, document.layerAt(DocPoint(16f, 16f)))
            assertTrue(document.sample(DocPoint(16.5f, 16.5f), SampleMode.MERGED).color.a < 0.01f)
            assertTrue(document.sample(DocPoint(46.5f, 41.25f), SampleMode.MERGED).color.r > 0.9f)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(listOf(path), document.layers.value.flatten().first { it.id == shapeId }.vectorPaths)
            assertEquals(shapeId, document.layerAt(DocPoint(16f, 16f)))
            assertTrue(document.sample(DocPoint(16.5f, 16.5f), SampleMode.MERGED).color.r > 0.9f)

            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(listOf(moved), document.layers.value.flatten().first { it.id == shapeId }.vectorPaths)
            assertEquals(shapeId, document.layerAt(DocPoint(46.5f, 41.25f)))
            assertTrue(document.sample(DocPoint(46.5f, 41.25f), SampleMode.MERGED).color.r > 0.9f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun textAndSmartObjectLayersMoveLikePixels() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Unsupported movable layer data", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            // A real text layer moves like pixels and its origin follows.
            val textId = LayerId(987_650_502L)
            val info = app.croptop.core.model.TextLayerInfo("Moves", app.croptop.core.model.CharacterStyle(sizePt = 12f), origin = app.croptop.core.model.DocPoint(4f, 20f))
            assertTrue(document.createTextLayer(textId, info) is CommandResult.Accepted)
            val history = document.history.value.current
            val moved = document.translateLayers(setOf(textId), 2f, 3f)
            assertTrue("text layer: $moved", moved is CommandResult.Accepted)
            assertTrue(document.history.value.current > history)
            assertEquals(app.croptop.core.model.DocPoint(6f, 23f), document.layers.value.find(textId)?.text?.origin)
            // A smart object without contents has nothing to move: a typed rejection, never a crash.
            val smartId = LayerId(987_650_503L)
            assertTrue(document.execute(EditCommand.AddLayer(smartId, "smart object", LayerKind.SMART_OBJECT)) is CommandResult.Accepted)
            val smart = document.translateLayers(setOf(smartId), 2f, 3f)
            assertTrue("smart object: $smart", smart is CommandResult.Accepted || (smart is CommandResult.Rejected && smart.code == RejectionCode.UNSUPPORTED))
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun shapePath(): VectorPathDraft = VectorPathDraft(
        id = VectorPathId(301L),
        name = "Fractional curve rectangle",
        subpaths = listOf(
            VectorSubpath(
                anchors = listOf(
                    PathAnchor(PathAnchorId(1L), DocPoint(8.25f, 8.75f), outgoing = DocPoint(2.5f, 0.25f)),
                    PathAnchor(PathAnchorId(2L), DocPoint(24.25f, 8.75f), incoming = DocPoint(-2.5f, 0.25f)),
                    PathAnchor(PathAnchorId(3L), DocPoint(24.25f, 24.75f)),
                    PathAnchor(PathAnchorId(4L), DocPoint(8.25f, 24.75f)),
                ),
                closed = true,
            ),
        ),
        style = VectorPathStyle(fillColor = RgbaColor(1f, 0f, 0f, 1f), strokeColor = null),
        primitive = VectorPrimitiveMetadata(
            kind = VectorShapeKind.RECTANGLE,
            sourceBox = VectorSourceBox(8.25f, 8.75f, 16f, 16f),
        ),
    )

    private fun translated(path: VectorPathDraft, dx: Float, dy: Float): VectorPathDraft = path.copy(
        subpaths = path.subpaths.map { subpath ->
            subpath.copy(
                anchors = subpath.anchors.map { anchor ->
                    anchor.copy(point = DocPoint(anchor.point.x + dx, anchor.point.y + dy))
                },
            )
        },
        primitive = path.primitive?.let { primitive ->
            primitive.copy(
                sourceBox = primitive.sourceBox?.let { box -> box.copy(x = box.x + dx, y = box.y + dy) },
            )
        },
    )
}
