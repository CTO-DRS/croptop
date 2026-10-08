package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.TransformTarget
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.Matrix3
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSelectionTransformTest {
    @Test
    fun selectionOutlineTransformPreviewsCommitsUndoAndRedoWithoutMovingPixels() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Selection outline transform", DocSize(64, 64), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 24f, 24f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.8f, 0.2f, 0.1f, 1f))) is CommandResult.Accepted)
            val original = document.selection.value as SelectionInfo.Active
            val beforeInside = document.sample(DocPoint(12f, 12f), SampleMode.ACTIVE_LAYER).color
            val beforeOutside = document.sample(DocPoint(36f, 36f), SampleMode.ACTIVE_LAYER).color
            val historyBefore = document.history.value.current

            val session = document.beginTransform(TransformTarget.Selection)
            session.update(Matrix3.translate(18f, 10f))
            val transformedBounds = DocRect(
                original.bounds.left + 18,
                original.bounds.top + 10,
                original.bounds.right + 18,
                original.bounds.bottom + 10,
            )
            withTimeout(10_000L) {
                document.selection.first { (it as? SelectionInfo.Active)?.bounds == transformedBounds }
            }
            assertEquals("preview does not add history", historyBefore, document.history.value.current)
            assertEquals("preview leaves pixels unchanged", beforeInside, document.sample(DocPoint(12f, 12f), SampleMode.ACTIVE_LAYER).color)
            assertEquals("preview does not move pixels", beforeOutside, document.sample(DocPoint(36f, 36f), SampleMode.ACTIVE_LAYER).color)

            assertTrue(session.commit() is CommandResult.Accepted)
            assertEquals("one selection history step", historyBefore + 1, document.history.value.current)
            assertEquals("history label", "Transform Selection", document.history.value.undoLabel)
            assertEquals(transformedBounds, (document.selection.value as SelectionInfo.Active).bounds)
            assertEquals(beforeInside, document.sample(DocPoint(12f, 12f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(beforeOutside, document.sample(DocPoint(36f, 36f), SampleMode.ACTIVE_LAYER).color)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(original, document.selection.value)
            assertEquals(beforeInside, document.sample(DocPoint(12f, 12f), SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(transformedBounds, (document.selection.value as SelectionInfo.Active).bounds)
            assertEquals(beforeInside, document.sample(DocPoint(12f, 12f), SampleMode.ACTIVE_LAYER).color)

            val beforeIdentity = document.history.value.current
            val identity = document.beginTransform(TransformTarget.Selection)
            identity.update(Matrix3.Identity)
            assertTrue(identity.commit() is CommandResult.Accepted)
            assertEquals("identity is not a history edit", beforeIdentity, document.history.value.current)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun cancelRestoresCommittedBoundsAndLatestPreviewGenerationWins() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Selection preview cancellation", DocSize(96, 80), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(10f, 12f, 30f, 34f)), antiAlias = false) is CommandResult.Accepted)
            val original = document.selection.value as SelectionInfo.Active
            val historyBefore = document.history.value.current
            val session = document.beginTransform(TransformTarget.Selection)
            session.update(Matrix3.translate(4f, 2f))
            session.update(Matrix3.translate(22f, 14f))
            val latest = DocRect(
                original.bounds.left + 22,
                original.bounds.top + 14,
                original.bounds.right + 22,
                original.bounds.bottom + 14,
            )
            withTimeout(10_000L) { document.selection.first { (it as? SelectionInfo.Active)?.bounds == latest } }
            withTimeout(10_000L) { document.awaitViewportPreview() }
            assertEquals("stale preview did not replace the latest matrix", latest, (document.selection.value as SelectionInfo.Active).bounds)

            session.cancel()
            assertEquals(original, document.selection.value)
            assertEquals(historyBefore, document.history.value.current)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun projectiveSelectionTransformPreviewsAndCommitsWithPixelsUntouched() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Projective selection transform", DocSize(64, 64), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(10f, 10f, 30f, 30f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.3f, 0.7f, 0.2f, 1f))) is CommandResult.Accepted)
            val originalBounds = (document.selection.value as SelectionInfo.Active).bounds
            val pixelBefore = document.sample(DocPoint(14f, 14f), SampleMode.ACTIVE_LAYER).color
            val historyBefore = document.history.value.current
            val projective = Matrix3(listOf(1f, 0f, 0f, 0f, 1f, 0f, 0.01f, 0f, 1f))

            val session = document.beginTransform(TransformTarget.Selection)
            session.update(projective)
            val projected = withTimeout(10_000L) {
                document.selection.first { (it as? SelectionInfo.Active)?.bounds?.let { bounds -> bounds != originalBounds } == true }
                    .let { (it as SelectionInfo.Active).bounds }
            }
            assertEquals(historyBefore, document.history.value.current)
            assertEquals(pixelBefore, document.sample(DocPoint(14f, 14f), SampleMode.ACTIVE_LAYER).color)

            assertTrue(session.commit() is CommandResult.Accepted)
            assertEquals(historyBefore + 1, document.history.value.current)
            assertEquals(projected, (document.selection.value as SelectionInfo.Active).bounds)
            assertEquals(
                "projective outline transform does not move pixels",
                pixelBefore,
                document.sample(DocPoint(14f, 14f), SampleMode.ACTIVE_LAYER).color,
            )
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun transformedFeatheredMaskRetainsCoverageAndCompetingEditCancelsPreview() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Feathered selection transform", DocSize(80, 64), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(
                document.select(SelectionShape.Rect(DocRectF(10f, 10f, 30f, 30f)), feather = 3f, antiAlias = true) is CommandResult.Accepted,
            )
            val original = document.selection.value as SelectionInfo.Active
            assertTrue(original.feathered)
            val session = document.beginTransform(TransformTarget.Selection)
            session.update(Matrix3.translate(20f, 8f))
            val moved = DocRect(original.bounds.left + 20, original.bounds.top + 8, original.bounds.right + 20, original.bounds.bottom + 8)
            withTimeout(10_000L) { document.selection.first { (it as? SelectionInfo.Active)?.bounds == moved } }
            assertTrue(session.commit() is CommandResult.Accepted)
            assertTrue((document.selection.value as SelectionInfo.Active).feathered)

            assertTrue(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))) is CommandResult.Accepted)
            val sampleY = moved.top + moved.height / 2
            val partialCoverage = (moved.left until moved.right).any { x ->
                val alpha = document.sample(DocPoint(x + 0.5f, sampleY + 0.5f), SampleMode.ACTIVE_LAYER).color.a
                alpha > 0.02f && alpha < 0.98f
            }
            assertTrue("translated feather mask keeps partial pixel coverage", partialCoverage)

            val competing = document.beginTransform(TransformTarget.Selection)
            competing.update(Matrix3.translate(3f, 0f))
            val preview = DocRect(moved.left + 3, moved.top, moved.right + 3, moved.bottom)
            withTimeout(10_000L) { document.selection.first { (it as? SelectionInfo.Active)?.bounds == preview } }
            assertTrue(document.select(SelectionShape.Rect(DocRectF(2f, 2f, 6f, 6f)), antiAlias = false) is CommandResult.Accepted)
            assertEquals(DocRect(2, 2, 6, 6), (document.selection.value as SelectionInfo.Active).bounds)
            assertFalse("competing edit ends the stale transform", competing.commit() is CommandResult.Accepted)
            assertEquals(DocRect(2, 2, 6, 6), (document.selection.value as SelectionInfo.Active).bounds)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun deselectDuringOutlinePreviewReselectsTheCommittedMaskAndBounds() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Selection preview superseded by deselect", DocSize(64, 64), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 10f, 24f, 30f)), antiAlias = false) is CommandResult.Accepted)
            val committed = document.selection.value
            val session = document.beginTransform(TransformTarget.Selection)
            session.update(Matrix3.translate(20f, 12f))
            withTimeout(10_000L) {
                document.selection.first { (it as? SelectionInfo.Active)?.bounds == DocRect(28, 22, 44, 42) }
            }

            assertTrue(document.deselect() is CommandResult.Accepted)
            assertEquals(SelectionInfo.None, document.selection.value)
            assertFalse("selection action supersedes transient transform", session.commit() is CommandResult.Accepted)
            assertTrue(document.reselect() is CommandResult.Accepted)
            assertEquals("reselect restores committed bounds, not preview bounds", committed, document.selection.value)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun historyNavigationAndCloseReleasePendingSelectionPreview() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Pending selection transform", DocSize(64, 64), background = BackgroundContents.TRANSPARENT),
        )
        assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 20f, 20f)), antiAlias = false) is CommandResult.Accepted)
        val original = document.selection.value
        val session = document.beginTransform(TransformTarget.Selection)
        session.update(Matrix3.translate(20f, 20f))
        withTimeout(10_000L) { document.selection.first { it != original } }
        assertTrue(document.undo() is CommandResult.Accepted)
        assertEquals(SelectionInfo.None, document.selection.value)
        assertFalse("history navigation cancels the pending session", session.commit() is CommandResult.Accepted)
        assertTrue(document.redo() is CommandResult.Accepted)
        assertEquals(original, document.selection.value)

        val closing = document.beginTransform(TransformTarget.Selection)
        closing.update(Matrix3.translate(1f, 1f))
        document.close()
        closing.cancel()
        engine.close()
    }
}
