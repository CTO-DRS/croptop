package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BlendMode
import app.croptop.core.model.ColorDepth
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.GradientShape
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGradientBridgeTest {
    @Test
    fun sixteenBitDocumentGradientRetainsMoreThanEightBitPrecision() {
        val engine = NativeEngine()
        val width = 4000
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "16-bit gradient precision",
                size = DocSize(width, 1),
                depth = ColorDepth.U16,
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        try {
            assertTrue(
                document.drawGradient(
                    DocPoint(0f, 0.5f),
                    DocPoint(width.toFloat(), 0.5f),
                    ColorGradient.BlackToWhite,
                    GradientShape.LINEAR,
                    reverse = false,
                    dither = true,
                    opacity = 1f,
                ) is CommandResult.Accepted,
            )

            // Sampling every tenth pixel is enough to distinguish this 16-bit path from an
            // 8-bit conversion without turning the instrumentation test into a timing test.
            val sampleStep = 10
            val levels = HashSet<Int>(width / sampleStep)
            for (x in 0 until width step sampleStep) {
                val red = document.sample(DocPoint(x + 0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color.r
                levels += (red * 65535f).toInt()
            }
            assertTrue("16-bit samples should retain over 350 levels; saw ${levels.size}", levels.size > 350)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun documentSelectionConstrainsGradientAndPaintBucket() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Selection raster tools", DocSize(32, 16), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(4f, 2f, 20f, 14f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(
                document.drawGradient(
                    DocPoint(0f, 8f),
                    DocPoint(31f, 8f),
                    ColorGradient.BlackToWhite,
                    GradientShape.LINEAR,
                    reverse = false,
                    dither = false,
                    opacity = 1f,
                ) is CommandResult.Accepted,
            )
            assertTrue(document.sample(DocPoint(10f, 8f), SampleMode.ACTIVE_LAYER).color.a > 0.99f)
            assertEquals(0f, document.sample(DocPoint(25f, 8f), SampleMode.ACTIVE_LAYER).color.a, 0.001f)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(0f, document.sample(DocPoint(10f, 8f), SampleMode.ACTIVE_LAYER).color.a, 0.001f)
            assertTrue(
                document.paintBucket(
                    DocPoint(10f, 8f),
                    RgbaColor(0.2f, 0.8f, 0.3f, 1f),
                    tolerance = 0,
                    contiguous = true,
                    sampleAll = false,
                    opacity = 1f,
                ) is CommandResult.Accepted,
            )
            assertTrue(document.sample(DocPoint(10f, 8f), SampleMode.ACTIVE_LAYER).color.g > 0.75f)
            assertEquals(0f, document.sample(DocPoint(25f, 8f), SampleMode.ACTIVE_LAYER).color.a, 0.001f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun gradientAndBucketJniAcceptSelectionMasksAndRejectMismatchedDimensions() {
        val engine = NativeEngine()
        val target = engine.createTileStore(32, 16, 0)
        val sample = engine.createTileStore(32, 16, 0)
        val selection = NativeSelectionBridge.rasterizeRect(32, 16, 4.0, 2.0, 20.0, 14.0, false)
        val mismatched = NativeSelectionBridge.rasterizeRect(16, 16, 4.0, 2.0, 12.0, 14.0, false)
        try {
            assertTrue("native target allocation", target != 0L)
            assertTrue("native sample allocation", sample != 0L)
            assertTrue("selection allocation", selection != 0L)
            assertTrue("mismatched selection allocation", mismatched != 0L)
            assertNull(
                NativeGradientBridge.render(
                    target,
                    DocPoint(0f, 8f),
                    DocPoint(31f, 8f),
                    ColorGradient.BlackToWhite,
                    GradientShape.LINEAR,
                    reverse = false,
                    dither = false,
                    opacity = 1f,
                    selectionMask = selection,
                ),
            )
            assertTrue(
                NativeGradientBridge.render(
                    target,
                    DocPoint(0f, 8f),
                    DocPoint(31f, 8f),
                    ColorGradient.BlackToWhite,
                    GradientShape.LINEAR,
                    reverse = false,
                    dither = false,
                    opacity = 1f,
                    selectionMask = mismatched,
                )?.contains("incompatible") == true,
            )
            assertNull(
                NativeGradientBridge.bucket(
                    target,
                    sample,
                    DocPoint(8f, 8f),
                    RgbaColor(1f, 0f, 0f, 1f),
                    tolerance = 0,
                    contiguous = true,
                    opacity = 1f,
                    selectionMask = selection,
                ),
            )
            assertTrue(
                NativeGradientBridge.bucket(
                    target,
                    sample,
                    DocPoint(8f, 8f),
                    RgbaColor(1f, 0f, 0f, 1f),
                    tolerance = 0,
                    contiguous = true,
                    opacity = 1f,
                    selectionMask = mismatched,
                )?.contains("incompatible") == true,
            )
        } finally {
            if (target != 0L) engine.destroyTileStore(target)
            if (sample != 0L) engine.destroyTileStore(sample)
            if (selection != 0L) NativeSelectionBridge.destroyStore(selection)
            if (mismatched != 0L) NativeSelectionBridge.destroyStore(mismatched)
            engine.close()
        }
    }

    @Test
    fun linearGradientRendersAsSingleUndoableCommand() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Gradient bridge",
                size = DocSize(32, 16),
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        try {
            val result = document.drawGradient(
                start = DocPoint(0f, 8f),
                end = DocPoint(31f, 8f),
                gradient = ColorGradient.BlackToWhite,
                shape = GradientShape.LINEAR,
                reverse = false,
                dither = false,
                opacity = 1f,
            )
            assertTrue("gradient should be accepted: $result", result is CommandResult.Accepted)
            assertEquals(2, document.history.value.entries.size)
            document.awaitViewportPreview()
            val left = document.sample(DocPoint(1f, 8f), SampleMode.ACTIVE_LAYER).color
            val right = document.sample(DocPoint(30f, 8f), SampleMode.ACTIVE_LAYER).color
            assertTrue("gradient should brighten toward its end", right.r > left.r + 0.8f)
            assertTrue(left.a > 0.99f && right.a > 0.99f)

            assertTrue(document.undo() is CommandResult.Accepted)
            val undone = document.sample(DocPoint(16f, 8f), SampleMode.ACTIVE_LAYER).color
            assertEquals(0f, undone.a, 0.001f)
            assertTrue(document.redo() is CommandResult.Accepted)
            val redone = document.sample(DocPoint(30f, 8f), SampleMode.ACTIVE_LAYER).color
            assertTrue(redone.r > 0.9f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun paintBucketFillsConnectedRegionAsSingleUndoableCommand() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Bucket bridge",
                size = DocSize(32, 16),
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        try {
            assertTrue(
                document.fill(FillSource.Solid(RgbaColor(0.1f, 0.25f, 0.8f)), blendMode = BlendMode.NORMAL) is CommandResult.Accepted,
            )
            val result = document.paintBucket(
                point = DocPoint(16f, 8f),
                color = RgbaColor(0.9f, 0.1f, 0.2f),
                tolerance = 0,
                contiguous = true,
                sampleAll = false,
                opacity = 1f,
            )
            assertTrue("bucket should be accepted: $result", result is CommandResult.Accepted)
            assertEquals(3, document.history.value.entries.size)
            val filled = document.sample(DocPoint(16f, 8f), SampleMode.ACTIVE_LAYER).color
            assertTrue(filled.r > 0.85f && filled.g < 0.15f && filled.b < 0.25f)

            assertTrue(document.undo() is CommandResult.Accepted)
            val undone = document.sample(DocPoint(16f, 8f), SampleMode.ACTIVE_LAYER).color
            assertTrue(kotlin.math.abs(undone.b - 0.8f) < 0.03f)
            assertTrue(document.redo() is CommandResult.Accepted)
            val redone = document.sample(DocPoint(16f, 8f), SampleMode.ACTIVE_LAYER).color
            assertTrue(redone.r > 0.85f)
        } finally {
            document.close()
            engine.close()
        }
    }
}
