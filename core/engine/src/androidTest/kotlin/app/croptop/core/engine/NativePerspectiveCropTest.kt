package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.CropQuad
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
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
import app.croptop.core.model.VectorSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePerspectiveCropTest {
    @Test
    fun skewedCropWarpsPixelsAndCommitsOneUndoableGeometryStep() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Perspective crop", size = DocSize(8, 8), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            dab(document, layer, 3, 3, RgbaColor(1f, 0f, 0f))
            val red = document.sample(DocPoint(3.5f, 3.5f), SampleMode.ACTIVE_LAYER).color
            assertTrue(document.select(SelectionShape.Rect(DocRectF(2f, 2f, 6f, 6f))) is CommandResult.Accepted)
            val beforeCrop = document.history.value.current
            val quad = CropQuad(
                topLeft = DocPoint(1f, 0f),
                topRight = DocPoint(7f, 1f),
                bottomRight = DocPoint(6f, 7f),
                bottomLeft = DocPoint(0f, 6f),
            )

            val result = document.cropPerspective(quad)

            assertTrue("perspective crop result: $result", result is CommandResult.Accepted)
            assertEquals(beforeCrop + 1, document.history.value.current)
            assertEquals(DocSize(6, 6), document.info.value.size)
            assertEquals(SelectionInfo.None, document.selection.value)
            assertTrue(
                document.sample(
                    DocPoint(document.info.value.size.width / 2f, document.info.value.size.height / 2f),
                    SampleMode.ACTIVE_LAYER,
                ).color.a >
                    0f,
            )

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(DocSize(8, 8), document.info.value.size)
            assertEquals(SelectionInfo.Active(DocRect(2, 2, 6, 6)), document.selection.value)
            assertEquals(red, document.sample(DocPoint(3.5f, 3.5f), SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(SelectionInfo.None, document.selection.value)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun rejectsInvalidAndHiddenPixelCropsWithoutHistoryMutation() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Perspective crop reject", size = DocSize(8, 8), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val before = document.history.value.current
            val quad = CropQuad(DocPoint(1f, 1f), DocPoint(7f, 1f), DocPoint(7f, 7f), DocPoint(1f, 7f))
            val hidden = document.cropPerspective(quad, deleteCropped = false)
            assertTrue(hidden is CommandResult.Rejected && hidden.code == RejectionCode.UNSUPPORTED)
            val crossed = document.cropPerspective(CropQuad(DocPoint(0f, 0f), DocPoint(7f, 7f), DocPoint(7f, 0f), DocPoint(0f, 7f)))
            assertTrue(crossed is CommandResult.Rejected && crossed.code == RejectionCode.INVALID_ARGUMENT)
            val outside = document.cropPerspective(CropQuad(DocPoint(-1f, 0f), DocPoint(7f, 0f), DocPoint(7f, 7f), DocPoint(0f, 7f)))
            assertTrue(outside is CommandResult.Rejected && outside.code == RejectionCode.INVALID_ARGUMENT)
            assertEquals(before, document.history.value.current)
            assertEquals(DocSize(8, 8), document.info.value.size)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun perspectiveCropTransformsVectorAnchorsAndDropsPrimitiveReconstructionMetadata() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Perspective shape", size = DocSize(8, 8), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val path = VectorPathDraft(
                id = VectorPathId(71),
                name = "Curve",
                subpaths = listOf(
                    VectorSubpath(
                        listOf(
                            PathAnchor(PathAnchorId(72), DocPoint(2f, 2f), outgoing = DocPoint(1f, 0f)),
                            PathAnchor(PathAnchorId(73), DocPoint(6f, 2f)),
                            PathAnchor(PathAnchorId(74), DocPoint(6f, 6f)),
                        ),
                    ),
                ),
                primitive = VectorPrimitiveMetadata(
                    kind = VectorShapeKind.RECTANGLE,
                    sourceBox = app.croptop.core.model.VectorSourceBox(2f, 2f, 4f, 4f),
                ),
            )
            assertTrue(document.createShapeLayer(app.croptop.core.model.LayerId(700), "Shape", listOf(path)) is CommandResult.Accepted)
            val quad = CropQuad(DocPoint(0f, 0f), DocPoint(8f, 1f), DocPoint(7f, 8f), DocPoint(1f, 7f))
            assertTrue(document.cropPerspective(quad) is CommandResult.Accepted)
            val mapped = document.layers.value.find(app.croptop.core.model.LayerId(700))!!.vectorPaths.single()
                .subpaths.single().anchors.first()
            assertTrue(mapped.point != DocPoint(2f, 2f))
            assertTrue(mapped.outgoing != DocPoint(1f, 0f))
            assertEquals(null, mapped.incoming)
            assertEquals(null, document.layers.value.find(app.croptop.core.model.LayerId(700))!!.vectorPaths.single().primitive)
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun dab(document: NativeDocument, layer: app.croptop.core.model.LayerId, x: Int, y: Int, color: RgbaColor) {
        val stroke = document.beginStroke(StrokeSpec(target = layer, color = color, diameter = 1f, hardness = 1f))
        stroke.add(floatArrayOf(x + 0.5f, y + 0.5f, 1f, 0f, 0f, 0f, 0f))
        assertTrue(stroke.end() is CommandResult.Accepted)
    }
}
