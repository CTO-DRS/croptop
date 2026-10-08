package app.croptop.core.engine

import app.croptop.core.model.BasicTone
import app.croptop.core.model.ColorMixer
import app.croptop.core.model.ColorRange
import app.croptop.core.model.Curve
import app.croptop.core.model.CurvePoint
import app.croptop.core.model.DevelopProfile
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.HslShift
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The layout must match `develop_settings_codec.h`; these positions are the same ones the native round-trip test pins. */
class NativeDevelopBridgeEncodingTest {
    @Test
    fun defaultSettingsUseTheDocumentedLayout() {
        val v = NativeDevelopBridge.encode(DevelopSettings())
        assertEquals(NativeDevelopBridge.FIXED + 4, v.size)
        assertEquals(1f, v[0]) // version
        assertEquals(5500f, v[3]) // temperature
        assertEquals(40f, v[54]) // sharpen amount
        assertEquals(50f, v[52]) // grading blending
        assertEquals(1f, v[64]) // lens profile
        assertEquals(1f, v[70]) // crop right
        assertEquals(2f, v[73]) // curve point count
        assertEquals(listOf(0f, 0f, 1f, 1f), v.drop(74))
    }

    @Test
    fun fieldsLandAtTheirIndices() {
        val settings = DevelopSettings(
            profile = DevelopProfile.LANDSCAPE,
            basic = BasicTone(exposure = 0.5f, saturation = -7f),
            mixer = ColorMixer().with(ColorRange.MAGENTA, HslShift(-30f, 40f, 25f)),
            toneCurve = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.2f), CurvePoint(1f, 1f))),
        )
        val v = NativeDevelopBridge.encode(settings)
        assertEquals(3f, v[1])
        assertEquals(0.5f, v[5])
        assertEquals(-7f, v[15])
        assertEquals(listOf(-30f, 40f, 25f), v.slice(37..39)) // magenta is range 7: 16 + 7 * 3
        assertEquals(3f, v[73])
        assertEquals(NativeDevelopBridge.FIXED + 6, v.size)
    }

    @Test
    fun tooManyCurvePointsAreRejectedBeforeCrossingJni() {
        val points = List(33) { CurvePoint(it / 32f, it / 32f) }
        assertFailsWith<IllegalArgumentException> { NativeDevelopBridge.encode(DevelopSettings(toneCurve = Curve(points))) }
    }

    @Test
    fun calibrationAndMasksRoundTripInTheDocumentedOrder() {
        val calibration = app.croptop.core.model.Calibration(20f, 10f, -30f, 5f, 15f, -25f, 40f)
        val alone = DevelopSettings(calibration = calibration)
        val encoded = NativeDevelopBridge.encode(alone)
        assertEquals(NativeDevelopBridge.FIXED + 4 + 7 + 1 + 2 + 8 + 1, encoded.size)
        assertEquals(calibration.toList(), encoded.drop(NativeDevelopBridge.FIXED + 4).take(7))
        assertEquals(0f, encoded[NativeDevelopBridge.FIXED + 4 + 7], "upright off")
        assertEquals(0f, encoded[NativeDevelopBridge.FIXED + 4 + 7 + 1 + 2 + 8], "no spots")
        val defringed = NativeDevelopBridge.decode(
            NativeDevelopBridge.encode(DevelopSettings(optics = app.croptop.core.model.Optics(defringePurple = 12f, defringeGreen = 4f))),
        )
        assertEquals(12f, defringed.optics.defringePurple)
        assertEquals(4f, defringed.optics.defringeGreen)
        assertEquals(DevelopSettings().optics.lensProfile, defringed.optics.lensProfile)
        val guided = DevelopSettings(
            upright = app.croptop.core.model.Upright.GUIDED,
            uprightManual = app.croptop.core.model.UprightManual(vertical = 10f, scale = 120f),
            uprightGuides = listOf(app.croptop.core.model.UprightGuide(0.1f, 0.2f, 0.12f, 0.8f)),
        )
        assertEquals(guided, NativeDevelopBridge.decode(NativeDevelopBridge.encode(guided)))
        val tilted = NativeDevelopBridge.decode(NativeDevelopBridge.encode(DevelopSettings(upright = app.croptop.core.model.Upright.FULL)))
        assertEquals(app.croptop.core.model.Upright.FULL, tilted.upright)
        assertEquals(alone.calibration, NativeDevelopBridge.decode(encoded).calibration)

        val spot = app.croptop.core.model.Spot(0.2f, 0.3f, 0.03f, 0.6f, 0.4f, 0.25f, 0.8f, false)
        val withSpots = NativeDevelopBridge.decode(NativeDevelopBridge.encode(DevelopSettings(spots = listOf(spot, spot.copy(heal = true)))))
        assertEquals(listOf(spot, spot.copy(heal = true)), withSpots.spots)
        assertEquals(app.croptop.core.model.Calibration(), withSpots.calibration)

        val mask = app.croptop.core.model.LocalMaskTemplates.of(app.croptop.core.model.MaskKind.LINEAR, 1)
        val both = DevelopSettings(calibration = calibration, masks = listOf(mask))
        val decoded = NativeDevelopBridge.decode(NativeDevelopBridge.encode(both))
        assertEquals(calibration, decoded.calibration)
        assertEquals(1, decoded.masks.size)

        val masksOnly = NativeDevelopBridge.decode(NativeDevelopBridge.encode(DevelopSettings(masks = listOf(mask))))
        assertEquals(app.croptop.core.model.Calibration(), masksOnly.calibration)
        assertEquals(1, masksOnly.masks.size)
    }

    @Test
    fun aiMaskBitmapsRoundTripThroughTheCodec() {
        val bytes = ByteArray(6 * 4) { (it * 10).toByte() }
        val settings = DevelopSettings(
            masks = listOf(
                app.croptop.core.model.LocalMask(
                    components = listOf(
                        app.croptop.core.model.MaskComponent.radial(0.5f, 0.5f, 0.2f, 0.2f),
                        app.croptop.core.model.MaskComponent.ai(app.croptop.core.model.AiMaskBitmap.of(6, 4, bytes, "Sky")),
                    ),
                ),
            ),
        )
        val back = NativeDevelopBridge.decode(NativeDevelopBridge.encode(settings))
        val ai = back.masks.single().components[1]
        assertEquals(app.croptop.core.model.MaskKind.AI, ai.kind)
        assertEquals(6 to 4, ai.bitmap!!.width to ai.bitmap!!.height)
        assertEquals(bytes.toList(), ai.bitmap!!.bytes().toList())
        assertEquals(null, back.masks.single().components[0].bitmap)
    }
}
