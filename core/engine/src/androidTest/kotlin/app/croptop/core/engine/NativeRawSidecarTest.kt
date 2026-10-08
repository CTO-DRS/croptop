package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.model.BasicTone
import app.croptop.core.model.ColorGrading
import app.croptop.core.model.ColorMixer
import app.croptop.core.model.ColorRange
import app.croptop.core.model.Curve
import app.croptop.core.model.CurvePoint
import app.croptop.core.model.Detail
import app.croptop.core.model.DevelopProfile
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.GradeWheel
import app.croptop.core.model.HslShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRawSidecarTest {
    @Test
    fun basicToneRoundTripsThroughAdobeCompatibleSidecar() {
        withEngine {
            val tone = NativeRawBridge.Tone(
                exposure = 1.25f,
                contrast = -12f,
                highlights = 31f,
                shadows = -24f,
                whites = 7f,
                blacks = -3f,
                texture = 12f,
                clarity = 18f,
                dehaze = 5f,
                vibrance = 22f,
                saturation = -8f,
            )

            val xmp = NativeRawBridge.writeSidecar(tone)
            assertTrue(xmp.contains("crs:Exposure2012=\"1.25\""))
            assertEquals(tone, NativeRawBridge.readSidecar(xmp))
        }
    }

    @Test
    fun completePipelineRecipeRoundTripsSupportedDevelopControls() {
        withEngine {
            val settings = DevelopSettings(
                profile = DevelopProfile.PORTRAIT,
                basic = BasicTone(exposure = 0.75f, contrast = 18f, clarity = 12f, vibrance = 21f),
                toneCurve = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(0.35f, 0.28f), CurvePoint(1f, 1f))),
                mixer = ColorMixer(mapOf(ColorRange.RED to HslShift(hue = -11f, saturation = 13f, luminance = 4f))),
                grading = ColorGrading(shadows = GradeWheel(220f, 17f, -6f), balance = -9f),
                detail = Detail(sharpenAmount = 72f, sharpenRadius = 1.4f, luminanceNoise = 14f, colorNoise = 19f),
            )

            val encoded = NativeDevelopBridge.encode(settings)
            assertEquals(settings, NativeDevelopBridge.decode(encoded))
            val xmp = NativeRawBridge.writeSidecar(settings)
            assertTrue(xmp.contains("crs:Exposure2012=\"0.75\""))
            val decoded = NativeRawBridge.readDevelopSidecar(xmp)
            assertEquals(settings.profile, decoded.profile)
            assertEquals(settings.basic, decoded.basic)
            assertEquals(settings.toneCurve, decoded.toneCurve)
            assertEquals(settings.mixer, decoded.mixer)
            assertEquals(settings.grading, decoded.grading)
            assertEquals(settings.detail, decoded.detail)
        }
    }

    @Test
    fun unsupportedAndMalformedSidecarsFailClearly() {
        withEngine {
            val error = runCatching { NativeRawBridge.readSidecar("not xmp") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("XMP", ignoreCase = true))
        }
    }

    private fun withEngine(block: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NativeEngine(context).use { block() }
    }
}
