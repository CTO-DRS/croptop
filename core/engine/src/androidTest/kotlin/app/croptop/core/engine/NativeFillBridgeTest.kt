package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BlendMode
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFillBridgeTest {
    @Test
    fun contentAwareFillCompletesForFivePercentOfTwentyFourMegapixels() {
        val engine = NativeEngine()
        val target = engine.createTileStore(6_000, 4_000, 0)
        val selection = NativeSelectionBridge.rasterizeRect(6_000, 4_000, 500.0, 500.0, 1_500.0, 1_700.0, false)
        try {
            assertTrue("24 MP target allocation", target != 0L)
            assertTrue("5% selection allocation", selection != 0L)
            val started = System.nanoTime()
            assertNull(NativeFillBridge.contentAware(target, 1f, 0, false, selection))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println("[content-aware fill] 24 MP / 5% selection completed in ${elapsedMs}ms on ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        } finally {
            if (target != 0L) engine.destroyTileStore(target)
            if (selection != 0L) NativeSelectionBridge.destroyStore(selection)
            engine.close()
        }
    }

    @Test
    fun documentSelectionConstrainsFillAndSurvivesHistory() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Selection fill", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(4f, 4f, 20f, 20f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.selection.value is SelectionInfo.Active)
            assertTrue(
                document.fill(FillSource.Solid(RgbaColor(0.9f, 0.2f, 0.1f, 1f)), 1f, BlendMode.NORMAL) is CommandResult.Accepted,
            )
            val inside = document.sample(DocPoint(10f, 10f), SampleMode.ACTIVE_LAYER).color
            val outside = document.sample(DocPoint(25f, 25f), SampleMode.ACTIVE_LAYER).color
            assertTrue("selected pixel is filled", inside.r > 0.85f && inside.a > 0.99f)
            assertEquals("unselected pixel stays transparent", 0f, outside.a, 0.001f)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertTrue("undo fill keeps selection", document.selection.value is SelectionInfo.Active)
            assertEquals(0f, document.sample(DocPoint(10f, 10f), SampleMode.ACTIVE_LAYER).color.a, 0.001f)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(SelectionInfo.None, document.selection.value)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertTrue(document.selection.value is SelectionInfo.Active)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertTrue(document.sample(DocPoint(10f, 10f), SampleMode.ACTIVE_LAYER).color.a > 0.99f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun solidFillJniAcceptsAnA16SelectionMaskAndRejectsMismatchedDimensions() {
        val engine = NativeEngine()
        val target = engine.createTileStore(32, 32, 0)
        val selection = NativeSelectionBridge.rasterizeRect(32, 32, 4.0, 4.0, 20.0, 20.0, false)
        val mismatched = NativeSelectionBridge.rasterizeRect(16, 16, 4.0, 4.0, 12.0, 12.0, false)
        try {
            assertTrue("native target allocation", target != 0L)
            assertTrue("selection allocation", selection != 0L)
            assertTrue("mismatched selection allocation", mismatched != 0L)
            assertNull(
                NativeFillBridge.solid(
                    target,
                    floatArrayOf(0.25f, 0.5f, 0.75f, 1f),
                    opacity = 1f,
                    blendMode = 0,
                    preserveTransparency = false,
                    selectionMask = selection,
                ),
            )
            assertTrue(
                NativeFillBridge.solid(
                    target,
                    floatArrayOf(0.25f, 0.5f, 0.75f, 1f),
                    opacity = 1f,
                    blendMode = 0,
                    preserveTransparency = false,
                    selectionMask = mismatched,
                )?.contains("incompatible") == true,
            )
        } finally {
            if (target != 0L) engine.destroyTileStore(target)
            if (selection != 0L) NativeSelectionBridge.destroyStore(selection)
            if (mismatched != 0L) NativeSelectionBridge.destroyStore(mismatched)
            engine.close()
        }
    }

    @Test
    fun solidFillIsVisibleUndoableAndRedoableAsOneCommand() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Solid fill",
                size = DocSize(32, 32),
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        try {
            assertEquals(1, document.history.value.entries.size)
            assertTrue(
                document.fill(
                    FillSource.Solid(RgbaColor(0.2f, 0.6f, 0.9f, 1f)),
                    opacity = 1f,
                    blendMode = BlendMode.NORMAL,
                ) is CommandResult.Accepted,
            )
            document.awaitViewportPreview()
            val filled = document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color
            assertTrue("red channel", kotlin.math.abs(filled.r - 0.2f) < 0.02f)
            assertTrue("green channel", kotlin.math.abs(filled.g - 0.6f) < 0.02f)
            assertTrue("blue channel", kotlin.math.abs(filled.b - 0.9f) < 0.02f)
            assertTrue("alpha channel", filled.a > 0.99f)
            assertEquals(2, document.history.value.entries.size)

            assertTrue(document.undo() is CommandResult.Accepted)
            val undone = document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color
            assertEquals(0f, undone.a, 0.001f)
            assertTrue(document.redo() is CommandResult.Accepted)
            document.awaitViewportPreview()
            val redone = document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color
            assertTrue(kotlin.math.abs(redone.g - 0.6f) < 0.02f)
            assertTrue(redone.a > 0.99f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun contentAwareFillRunsOnSelectionAndIsUndoable() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Content-aware fill", DocSize(128, 128), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val missingSelection = document.fill(FillSource.ContentAware()) as CommandResult.Rejected
            assertEquals(RejectionCode.INVALID_ARGUMENT, missingSelection.code)
            assertTrue(
                document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted,
            )
            val stroke = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor(1f, 0f, 0f, 1f), diameter = 8f, hardness = 1f),
            )
            stroke.add(floatArrayOf(64f, 64f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is CommandResult.Accepted)
            assertTrue(
                document.select(SelectionShape.Rect(DocRectF(48f, 48f, 80f, 80f)), antiAlias = false) is CommandResult.Accepted,
            )

            assertTrue(document.fill(FillSource.ContentAware()) is CommandResult.Accepted)
            val restored = document.sample(DocPoint(64f, 64f), SampleMode.ACTIVE_LAYER).color
            val outside = document.sample(DocPoint(40f, 64f), SampleMode.ACTIVE_LAYER).color
            assertTrue(
                "PatchMatch replaces the selected red stroke with its white surroundings",
                restored.r > 0.9f && restored.g > 0.9f && restored.b > 0.9f,
            )
            assertTrue("unselected pixels remain white", outside.r > 0.99f && outside.g > 0.99f && outside.b > 0.99f)

            assertTrue(document.undo() is CommandResult.Accepted)
            val undone = document.sample(DocPoint(64f, 64f), SampleMode.ACTIVE_LAYER).color
            assertTrue("undo restores the red source pixels", undone.r > 0.9f && undone.g < 0.1f && undone.b < 0.1f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun contentAwareFillJobCancellationAcknowledgesWithoutCommittingPixelsOrHistory() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Cancellable content-aware fill", DocSize(512, 512), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            assertTrue(
                document.select(SelectionShape.Rect(DocRectF(128f, 128f, 384f, 384f)), antiAlias = false) is CommandResult.Accepted,
            )
            val historyBefore = document.history.value
            val pixelBefore = document.sample(DocPoint(256f, 256f), SampleMode.ACTIVE_LAYER).color
            val job = document.fillJob(FillSource.ContentAware())
            job.cancel()

            val result = job.await() as CommandResult.Rejected
            assertEquals(RejectionCode.CANCELLED, result.code)
            assertEquals("cancelled fill must not add a history step", historyBefore, document.history.value)
            assertEquals(pixelBefore, document.sample(DocPoint(256f, 256f), SampleMode.ACTIVE_LAYER).color)
            document.close()
            val afterClose = document.fillJob(FillSource.ContentAware()).await() as CommandResult.Rejected
            assertEquals(RejectionCode.CLOSED, afterClose.code)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun contentAwareFillReportsProgressAndCancelsDuringNativeWork() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Progress content-aware fill", DocSize(1_024, 1_024), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            assertTrue(
                document.select(SelectionShape.Rect(DocRectF(256f, 256f, 768f, 768f)), antiAlias = false) is CommandResult.Accepted,
            )
            val historyBefore = document.history.value
            val job = document.fillJob(FillSource.ContentAware())
            val progress = checkNotNull(job.progress.first { it != null && it > 0f })
            assertTrue("native progress is a fraction", progress in 0f..1f)
            job.cancel()

            val result = job.await() as CommandResult.Rejected
            assertEquals(RejectionCode.CANCELLED, result.code)
            assertEquals("cancelled native work must not add a history step", historyBefore, document.history.value)
        } finally {
            document.close()
            engine.close()
        }
    }
}
