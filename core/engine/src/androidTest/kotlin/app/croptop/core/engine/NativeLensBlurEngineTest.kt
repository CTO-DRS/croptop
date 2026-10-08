package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DepthRangeSpec
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.LensBlurDepthSource
import app.croptop.core.engine.api.LensBlurSpec
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.Matrix3
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLensBlurEngineTest {
    @Test
    fun lensBlurJobPublishesNativeTileProgress() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Lens Blur progress", DocSize(512, 512), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val target = checkNotNull(document.layers.value.primary)
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            val job = document.applyLensBlurJob(target, LensBlurSpec(radius = 24f, workerThreads = 1))
            val progress = mutableListOf<Float?>()
            val collector = launch { job.progress.collect(progress::add) }

            assertTrue(job.await() is CommandResult.Accepted)
            collector.cancelAndJoin()

            assertTrue("native tile progress should include an in-flight value", progress.any { it != null && it in 0f..0.99f })
            assertEquals(1f, job.progress.value)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun lensBlurMixesThroughHardAndFeatheredSelectionsAndKeepsOutsidePixelsUntouched() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Selection-masked Lens Blur", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val target = checkNotNull(document.layers.value.primary)
            assertTrue(document.fill(FillSource.Solid(RgbaColor.Black)) is CommandResult.Accepted)
            val dab = document.beginStroke(StrokeSpec(target = target, color = RgbaColor.White, diameter = 1f, hardness = 1f))
            dab.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(dab.end() is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 24f, 24f)), antiAlias = false) is CommandResult.Accepted)
            val committedSelection = document.selection.value
            val selectionTransform = document.beginTransform(app.croptop.core.engine.api.TransformTarget.Selection)
            selectionTransform.update(Matrix3.translate(16f, 0f))
            withTimeout(10_000L) {
                document.selection.first { (it as? SelectionInfo.Active)?.bounds?.left == 24 }
            }

            val before = document.history.value.current
            assertTrue(document.applyLensBlur(target, LensBlurSpec(radius = 8f, workerThreads = 1)) is CommandResult.Accepted)
            assertEquals(before + 1, document.history.value.current)
            assertEquals(committedSelection, document.selection.value)
            assertTrue(document.sample(DocPoint(18f, 16f), SampleMode.ACTIVE_LAYER).color.r > 0f)
            assertEquals(0f, document.sample(DocPoint(3f, 16f), SampleMode.ACTIVE_LAYER).color.r, 0f)
            assertTrue(document.undo() is CommandResult.Accepted)

            assertTrue(document.select(SelectionShape.Rect(DocRectF(8.25f, 8f, 24.25f, 24f)), antiAlias = true) is CommandResult.Accepted)
            assertTrue(document.applyLensBlur(target, LensBlurSpec(radius = 8f, workerThreads = 1)) is CommandResult.Accepted)
            val featheredEdge = document.sample(DocPoint(8f, 16f), SampleMode.ACTIVE_LAYER).color.r
            val selectedInterior = document.sample(DocPoint(9f, 16f), SampleMode.ACTIVE_LAYER).color.r
            assertTrue(featheredEdge > 0f)
            assertTrue(featheredEdge < selectedInterior)
            assertEquals(0f, document.sample(DocPoint(3f, 16f), SampleMode.ACTIVE_LAYER).color.r, 0f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun lensBlurJobCancellationWinsBeforeHistoryCommit() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Cancel Lens Blur", DocSize(512, 512), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val target = checkNotNull(document.layers.value.primary)
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            val before = document.history.value.current
            val entriesBefore = document.history.value.entries.size
            val job = document.applyLensBlurJob(target, LensBlurSpec(radius = 100f, workerThreads = 1))
            withTimeout(20_000L) { job.progress.first { it != null && it > 0f } }
            job.cancel()

            val result = job.await()
            assertTrue(result is CommandResult.Rejected && result.code == RejectionCode.CANCELLED)
            assertEquals(before, document.history.value.current)
            assertEquals(entriesBefore, document.history.value.entries.size)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun lensBlurAndDepthRangeMaskAreUndoableNativeDocumentCommands() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Lens Blur API", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val target = checkNotNull(document.layers.value.primary)
            assertTrue(document.fill(FillSource.Solid(RgbaColor.Black)) is CommandResult.Accepted)
            val point = document.beginStroke(StrokeSpec(target = target, color = RgbaColor.White, diameter = 1f, hardness = 1f))
            point.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(point.end() is CommandResult.Accepted)

            val blurHistory = document.history.value.current
            assertTrue(document.applyLensBlur(target, LensBlurSpec(radius = 8f, irisBlades = 0, workerThreads = 2)) is CommandResult.Accepted)
            assertEquals(blurHistory + 1, document.history.value.current)
            assertEquals("Lens Blur", document.history.value.entries.last().label)
            assertTrue(document.sample(DocPoint(18f, 16f), SampleMode.ACTIVE_LAYER).color.r > 0f)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(blurHistory, document.history.value.current)
            assertEquals(0f, document.sample(DocPoint(18f, 16f), SampleMode.ACTIVE_LAYER).color.r, 0f)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertTrue(document.sample(DocPoint(18f, 16f), SampleMode.ACTIVE_LAYER).color.r > 0f)

            val depthId = LayerId(777)
            assertTrue(document.execute(EditCommand.AddLayer(depthId, "Depth", LayerKind.PIXEL)) is CommandResult.Accepted)
            assertTrue(document.setActiveLayers(setOf(depthId), depthId) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.5f, 0.5f, 0.5f, 1f))) is CommandResult.Accepted)
            assertTrue(document.addLayerMask(depthId, MaskFill.REVEAL_ALL) is CommandResult.Accepted)
            assertTrue(
                document.applyDepthRangeMask(
                    target,
                    LensBlurDepthSource.LayerPixels(depthId),
                    DepthRangeSpec(near = 0.4f, far = 0.6f, feather = 0.05f),
                ) is CommandResult.Accepted,
            )
            assertNotNull(document.layers.value.find(target)?.mask)
            assertEquals("Depth Range Mask", document.history.value.entries.last().label)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertNull(document.layers.value.find(target)?.mask)
            assertTrue(
                document.applyLensBlur(target, LensBlurSpec(radius = 0f), LensBlurDepthSource.LayerMask(depthId)) is CommandResult.Accepted,
            )
            val asyncHistory = document.history.value.current
            val job = document.applyLensBlurJob(target, LensBlurSpec(radius = 0f))
            assertTrue(runBlocking { job.await() } is CommandResult.Accepted)
            assertEquals(asyncHistory + 1, document.history.value.current)
            assertEquals("Lens Blur", document.history.value.entries.last().label)

            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 8f, 8f))) is CommandResult.Accepted)
            val beforeSelectedBlur = document.history.value.current
            assertTrue(document.applyLensBlur(target, LensBlurSpec(radius = 8f, focalDistance = 0f)) is CommandResult.Accepted)
            assertEquals(beforeSelectedBlur + 1, document.history.value.current)
            assertEquals(true, document.selection.value is SelectionInfo.Active)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun closingDocumentWaitsForLensBlurSnapshotsToRelease() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Close During Lens Blur", DocSize(1024, 1024), background = BackgroundContents.TRANSPARENT),
        )
        val target = checkNotNull(document.layers.value.primary)
        assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
        val before = document.history.value.current
        val job = document.applyLensBlurJob(target, LensBlurSpec(radius = 100f, workerThreads = 1))
        document.close()

        val result = runBlocking { job.await() }
        assertTrue(result is CommandResult.Rejected)
        assertEquals(before, document.history.value.current)
        engine.close()
    }
}
