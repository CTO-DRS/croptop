package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.ColorReadout
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.WorkingSpace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeColorReadoutApiTest {
    @Test
    fun documentReadoutMatchesItsWorkingProfileAndReleasesWithDocument() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Color readout",
                size = DocSize(8, 8),
                profile = app.croptop.core.model.ColorProfileRef.BuiltIn(app.croptop.core.model.WorkingSpace.DISPLAY_P3),
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        val color = RgbaColor(0.2f, 0.5f, 1f)
        val expected = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3).use { profile ->
            NativeColorManagementBridge.Profile.genericCmyk().use { cmyk ->
                NativeColorManagementBridge.ReadoutSession(profile, cmyk).use { it.read(floatArrayOf(color.r, color.g, color.b)) }
            }
        }
        try {
            val actual: ColorReadout = document.colorReadout(color)
            assertArrayEquals(expected.lab.toFloatArray(), actual.lab.toFloatArray(), 0.02f)
            assertArrayEquals(expected.cmykPercent.toFloatArray(), actual.cmykPercent.toFloatArray(), 0.02f)
            assertEquals(expected.outOfGamut, actual.outOfGamut)
            assertTrue(actual.lab.all(Float::isFinite))
            assertTrue(actual.cmykPercent.all { it in 0f..100f })
        } finally {
            document.close()
            engine.close()
        }
        assertThrows(IllegalStateException::class.java) { document.colorReadout(color) }
    }

    @Test
    fun profileAssignmentAndPixelConversionAreAtomicUndoableDocumentEdits() = runBlocking {
        val engine = NativeEngine()
        val sourceProfile = ColorProfileRef.BuiltIn(WorkingSpace.DISPLAY_P3)
        val targetProfile = ColorProfileRef.BuiltIn(WorkingSpace.SRGB)
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Profile conversion",
                size = DocSize(32, 32),
                profile = sourceProfile,
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(StrokeSpec(target = layer, color = RgbaColor(0.22f, 0.35f, 0.50f), diameter = 14f))
            stroke.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is CommandResult.Accepted)

            val point = DocPoint(16f, 16f)
            val before = document.sample(point, SampleMode.ACTIVE_LAYER).color
            val appearanceBefore = document.colorReadout(before).lab
            assertTrue(document.applyColorProfile(targetProfile) is CommandResult.Accepted)
            assertEquals(targetProfile, document.info.value.profile)
            assertEquals("Assign Color Profile", document.history.value.undoLabel)
            assertEquals(before, document.sample(point, SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(sourceProfile, document.info.value.profile)

            assertTrue(document.applyColorProfileJob(targetProfile, convertPixels = true).await() is CommandResult.Accepted)
            assertEquals(targetProfile, document.info.value.profile)
            assertEquals("Convert Color Profile", document.history.value.undoLabel)
            val after = document.sample(point, SampleMode.ACTIVE_LAYER).color
            assertNotEquals("conversion should re-encode pixel values", before, after)
            val appearanceAfter = document.colorReadout(after).lab
            appearanceBefore.zip(appearanceAfter).forEach { (beforeLab, afterLab) ->
                assertTrue(
                    "conversion should preserve Lab appearance: $appearanceBefore -> $appearanceAfter",
                    kotlin.math.abs(beforeLab - afterLab) < 1.0f,
                )
            }

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(sourceProfile, document.info.value.profile)
            assertEquals(before, document.sample(point, SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(targetProfile, document.info.value.profile)
        } finally {
            document.close()
            engine.close()
        }
    }
}
