package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.ShapeBooleanOperation
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorPathStyle
import app.croptop.core.model.VectorSubpath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePathBooleanEngineTest {
    @Test
    fun booleanCodecPreservesBezierHandlesOnTheClosingSegment() {
        val path = VectorPathDraft(
            id = VectorPathId(150L),
            name = "Curved close",
            subpaths = listOf(
                VectorSubpath(
                    anchors = listOf(
                        PathAnchor(PathAnchorId(1L), DocPoint(4f, 4f), incoming = DocPoint(3f, 0f)),
                        PathAnchor(PathAnchorId(2L), DocPoint(20f, 4f)),
                        PathAnchor(PathAnchorId(3L), DocPoint(20f, 20f)),
                        PathAnchor(PathAnchorId(4L), DocPoint(4f, 20f), outgoing = DocPoint(0f, -3f)),
                    ),
                    closed = true,
                ),
            ),
        )

        val roundTrip = NativeVectorPathBooleanCodec.decode(NativeVectorPathBooleanCodec.encode(path), path)
        val anchors = roundTrip.subpaths.single().anchors
        assertEquals(4, anchors.size)
        assertEquals(DocPoint(3f, 0f), anchors.first().incoming)
        assertEquals(DocPoint(0f, -3f), anchors.last().outgoing)
        assertTrue(roundTrip.subpaths.single().closed)
    }

    @Test
    fun everyBooleanOperationProducesExpectedRasterCoverage() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Path boolean operations", DocSize(48, 40), background = BackgroundContents.TRANSPARENT),
        )
        try {
            ShapeBooleanOperation.entries.forEachIndexed { index, operation ->
                val layerId = LayerId(987_650_900L + index)
                val subject = rectangle(200L + index * 2, 100L + index * 10, 6f, 8f, 24f, 28f, RgbaColor(1f, 0f, 0f, 1f))
                val clip = rectangle(201L + index * 2, 105L + index * 10, 16f, 8f, 34f, 28f, RgbaColor(0f, 1f, 0f, 1f))
                assertTrue(document.createShapeLayer(layerId, "${operation.name} fixture", listOf(subject, clip)) is CommandResult.Accepted)
                val result = document.combineShapePaths(layerId, subject.id, clip.id, operation)
                assertTrue("$operation should be accepted: $result", result is CommandResult.Accepted)
                val subjectOnly = document.sample(DocPoint(10f, 18f), SampleMode.ACTIVE_LAYER).color
                val overlap = document.sample(DocPoint(20f, 18f), SampleMode.ACTIVE_LAYER).color
                val clipOnly = document.sample(DocPoint(30f, 18f), SampleMode.ACTIVE_LAYER).color
                val expectSubject = operation != ShapeBooleanOperation.INTERSECT
                val expectOverlap = operation == ShapeBooleanOperation.UNITE || operation == ShapeBooleanOperation.INTERSECT
                val expectClip = operation == ShapeBooleanOperation.UNITE || operation == ShapeBooleanOperation.EXCLUDE
                assertEquals("$operation subject-only coverage", expectSubject, subjectOnly.a > 0.95f)
                assertEquals("$operation overlap coverage", expectOverlap, overlap.a > 0.95f)
                assertEquals("$operation clip-only coverage", expectClip, clipOnly.a > 0.95f)
                listOf(subjectOnly, overlap, clipOnly).filter { it.a > 0.95f }.forEach { color ->
                    assertTrue("$operation keeps subject fill styling", color.r > 0.95f && color.g < 0.05f)
                }
            }
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun uniteProducesOneEditablePathAndOneUndoableChange() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Path boolean", DocSize(48, 40), background = BackgroundContents.TRANSPARENT),
        )
        val layerId = LayerId(987_650_821L)
        try {
            val subject = rectangle(101, 1, 6f, 8f, 22f, 28f, RgbaColor(1f, 0f, 0f, 1f))
            val clip = rectangle(102, 11, 17f, 8f, 34f, 28f, RgbaColor(0f, 1f, 0f, 1f))
            assertTrue(document.createShapeLayer(layerId, "Boolean shapes", listOf(subject, clip)) is CommandResult.Accepted)
            val before = document.history.value.current
            assertTrue(document.sample(DocPoint(32f, 18f), SampleMode.ACTIVE_LAYER).color.g > 0.95f)

            val result = document.combineShapePaths(layerId, subject.id, clip.id, ShapeBooleanOperation.UNITE)

            assertTrue("native path boolean should commit: $result", result is CommandResult.Accepted)
            assertEquals(before + 1, document.history.value.current)
            val paths = checkNotNull(document.layers.value.find(layerId)).vectorPaths
            assertEquals(1, paths.size)
            assertEquals(subject.id, paths.single().id)
            assertEquals(subject.style, paths.single().style)
            val points = paths.single().subpaths.flatMap { subpath -> subpath.anchors.map { it.point } }
            assertTrue(points.any { it.x < 8.1f })
            assertTrue(points.any { it.x > 31.9f })
            assertTrue(paths.single().subpaths.all { it.closed })
            val unitedEdge = document.sample(DocPoint(32f, 18f), SampleMode.ACTIVE_LAYER).color
            assertTrue("unite must rasterize clip-only coverage", unitedEdge.r > 0.95f && unitedEdge.g < 0.05f)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(2, checkNotNull(document.layers.value.find(layerId)).vectorPaths.size)
            assertTrue(document.sample(DocPoint(32f, 18f), SampleMode.ACTIVE_LAYER).color.g > 0.95f)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(1, checkNotNull(document.layers.value.find(layerId)).vectorPaths.size)
            assertTrue(document.sample(DocPoint(32f, 18f), SampleMode.ACTIVE_LAYER).color.r > 0.95f)
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun rectangle(
        pathId: Long,
        anchorStart: Long,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: RgbaColor,
    ) = VectorPathDraft(
        id = VectorPathId(pathId),
        name = "Rectangle $pathId",
        subpaths = listOf(
            VectorSubpath(
                anchors = listOf(
                    PathAnchor(PathAnchorId(anchorStart), DocPoint(left, top)),
                    PathAnchor(PathAnchorId(anchorStart + 1), DocPoint(right, top)),
                    PathAnchor(PathAnchorId(anchorStart + 2), DocPoint(right, bottom)),
                    PathAnchor(PathAnchorId(anchorStart + 3), DocPoint(left, bottom)),
                ),
                closed = true,
            ),
        ),
        style = VectorPathStyle(fillColor = color),
    )
}
