package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.Anchor
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCropTest {
    @Test
    fun rectangularCropChangesPixelsAndDimensionsInOneUndoableStep() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Crop", size = DocSize(5, 4), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            dab(document, layer, 0, 0, RgbaColor(1f, 0f, 0f))
            dab(document, layer, 2, 1, RgbaColor(0f, 1f, 0f))
            val before = document.history.value.current
            val red = document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color
            val green = document.sample(DocPoint(2.5f, 1.5f), SampleMode.ACTIVE_LAYER).color
            assertTrue(document.select(SelectionShape.Rect(DocRectF(1f, 1f, 4f, 3f))) is CommandResult.Accepted)

            val cropResult = document.crop(DocRectF(1f, 1f, 4f, 3f))
            assertTrue("crop result: $cropResult", cropResult is CommandResult.Accepted)
            assertEquals(DocSize(3, 2), document.info.value.size)
            assertEquals(before + 2, document.history.value.current) // Select + one Crop step.
            assertEquals(SelectionInfo.None, document.selection.value)
            assertEquals(0f, document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color.a, 0f)
            assertEquals(green, document.sample(DocPoint(1.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(DocSize(5, 4), document.info.value.size)
            assertEquals(SelectionInfo.Active(app.croptop.core.model.DocRect(1, 1, 4, 3)), document.selection.value)
            assertEquals(red, document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(DocSize(3, 2), document.info.value.size)
            assertEquals(green, document.sample(DocPoint(1.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun cropClearsPreviousSelectionInsteadOfRejectingGeometryChange() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Crop after deselect", size = DocSize(5, 4), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 3f, 3f))) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)

            val result = document.crop(DocRectF(1f, 1f, 4f, 3f))

            assertTrue("crop result: $result", result is CommandResult.Accepted)
            assertEquals(DocSize(3, 2), document.info.value.size)
            assertEquals(SelectionInfo.None, document.selection.value)
            assertTrue("crop discards the old reselect state", document.reselect() is CommandResult.Rejected)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun straightenCropRotatesAboutCropCenterAndKeepsOnlyInteriorPixels() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Straighten crop", size = DocSize(6, 4), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            dab(document, layer, 2, 1, RgbaColor(0f, 0f, 1f))
            val blue = document.sample(DocPoint(2.5f, 1.5f), SampleMode.ACTIVE_LAYER).color
            val cropResult = document.crop(DocRectF(0f, 0f, 6f, 4f), angleDegrees = 90f)
            assertTrue("crop result: $cropResult", cropResult is CommandResult.Accepted)
            assertEquals(DocSize(4, 2), document.info.value.size)
            assertEquals(blue, document.sample(DocPoint(2.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun rejectsInvalidBoundsWithoutHistoryMutation() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Crop rejection", size = DocSize(4, 3), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val before = document.history.value.current
            val empty = document.crop(DocRectF(2f, 1f, 2f, 3f))
            assertTrue(empty is CommandResult.Rejected && empty.code == RejectionCode.INVALID_ARGUMENT)
            val oversized = document.crop(DocRectF(0f, 0f, 65_536f, 2f))
            assertTrue(oversized is CommandResult.Rejected && oversized.code == RejectionCode.INVALID_ARGUMENT)
            val badAngle = document.crop(DocRectF(0f, 0f, 3f, 2f), angleDegrees = Float.NaN)
            assertTrue(badAngle is CommandResult.Rejected && badAngle.code == RejectionCode.INVALID_ARGUMENT)
            assertEquals(before, document.history.value.current)
            assertEquals(DocSize(4, 3), document.info.value.size)

            assertTrue(
                document.execute(
                    app.croptop.core.engine.api.EditCommand.AddLayer(
                        app.croptop.core.model.LayerId(901),
                        "Text",
                        LayerKind.TEXT,
                    ),
                ) is CommandResult.Accepted,
            )
            val afterText = document.history.value.current
            assertTrue(document.crop(DocRectF(0f, 0f, 3f, 2f)) is CommandResult.Accepted)
            assertEquals(afterText + 1, document.history.value.current)
            assertEquals(DocSize(3, 2), document.info.value.size)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun keepPixelsCropCanRevealOriginalPixelsAndPreservesEditsMadeAfterCrop() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Keep crop pixels", size = DocSize(5, 4), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            dab(document, layer, 0, 0, RgbaColor(1f, 0f, 0f))
            dab(document, layer, 2, 1, RgbaColor(0f, 1f, 0f))
            val red = document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color
            assertTrue(document.crop(DocRectF(1f, 1f, 4f, 3f), deleteCropped = false) is CommandResult.Accepted)
            assertEquals(DocSize(3, 2), document.info.value.size)
            assertEquals(0f, document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color.a, 0f)

            // The live canvas wins over the retained source if it is edited before the reveal.
            dab(document, layer, 1, 0, RgbaColor(0f, 0f, 1f))
            val blue = document.sample(DocPoint(1.5f, 0.5f), SampleMode.ACTIVE_LAYER).color
            assertTrue(document.resizeCanvas(DocSize(5, 4), Anchor.CENTER) is CommandResult.Accepted)
            assertEquals(DocSize(5, 4), document.info.value.size)
            assertEquals(red, document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(blue, document.sample(DocPoint(2.5f, 1.5f), SampleMode.ACTIVE_LAYER).color)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(DocSize(3, 2), document.info.value.size)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(red, document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(blue, document.sample(DocPoint(2.5f, 1.5f), SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun hiddenPixelsSurviveCroptopSaveAndOpen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val project = java.io.File(context.cacheDir, "hidden-pixels-roundtrip.croptop")
        val document = engine.newDocument(
            NewDocumentSpec(name = "Hidden pixel archive", size = DocSize(5, 4), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        var reopened: NativeDocument? = null
        try {
            val layer = checkNotNull(document.layers.value.primary)
            dab(document, layer, 0, 0, RgbaColor(1f, 0f, 0f))
            val red = document.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color
            assertTrue(document.crop(DocRectF(1f, 1f, 4f, 3f), deleteCropped = false) is CommandResult.Accepted)
            document.save(SaveTarget.AppFile(project.absolutePath))

            val restored = engine.open(DocumentSource.AppFile(project.absolutePath)) as NativeDocument
            reopened = restored
            assertEquals(DocSize(3, 2), restored.info.value.size)
            assertEquals(0f, restored.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color.a, 0f)
            assertTrue(restored.resizeCanvas(DocSize(5, 4), Anchor.CENTER) is CommandResult.Accepted)
            assertEquals(red, restored.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
        } finally {
            reopened?.close()
            document.close()
            engine.close()
            project.delete()
        }
    }

    @Test
    fun hiddenPixelsSurviveCrashRecoveryAndCanBeRevealed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalEngine = NativeEngine(context)
        val recoveryEngine = NativeEngine(context)
        val before = originalEngine.recoveryRoot?.listFiles()?.map { it.name }?.toSet().orEmpty()
        val original = originalEngine.newDocument(
            NewDocumentSpec(name = "Recover hidden pixels", size = DocSize(5, 4), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        var recovered: NativeDocument? = null
        try {
            val layer = checkNotNull(original.layers.value.primary)
            dab(original, layer, 0, 0, RgbaColor(1f, 0f, 0f))
            val red = original.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color
            assertTrue(original.crop(DocRectF(1f, 1f, 4f, 3f), deleteCropped = false) is CommandResult.Accepted)
            assertTrue("recovery journal flush", original.autosaveNow(flushTimeoutMs = 30_000L))
            val journal = checkNotNull(
                originalEngine.recoveryRoot?.listFiles()?.singleOrNull { it.isDirectory && it.name !in before },
            )

            recovered = recoveryEngine.recover(journal.name) as NativeDocument
            assertEquals(DocSize(3, 2), recovered.info.value.size)
            assertEquals(0f, recovered.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color.a, 0f)
            assertTrue(recovered.resizeCanvas(DocSize(5, 4), Anchor.CENTER) is CommandResult.Accepted)
            assertEquals(red, recovered.sample(DocPoint(0.5f, 0.5f), SampleMode.ACTIVE_LAYER).color)
        } finally {
            recovered?.close()
            original.close()
            recoveryEngine.close()
            originalEngine.close()
        }
    }

    private fun dab(document: NativeDocument, layer: app.croptop.core.model.LayerId, x: Int, y: Int, color: RgbaColor) {
        val stroke = document.beginStroke(StrokeSpec(target = layer, color = color, diameter = 1f, hardness = 1f))
        stroke.add(floatArrayOf(x + 0.5f, y + 0.5f, 1f, 0f, 0f, 0f, 0f))
        assertTrue(stroke.end() is CommandResult.Accepted)
    }
}
