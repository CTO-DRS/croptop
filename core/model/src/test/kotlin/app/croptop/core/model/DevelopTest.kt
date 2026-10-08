package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DevelopTest {
    @Test
    fun `defaults, json round trip, mixer normalization`() {
        assertTrue(DevelopSettings().isDefault)
        val s = DevelopSettings(basic = BasicTone(exposure = 0.7f), mixer = ColorMixer().with(ColorRange.BLUE, HslShift(saturation = -20f)))
        assertFalse(s.isDefault)
        assertEquals(s, Json.decodeFromString(DevelopSettings.serializer(), Json.encodeToString(DevelopSettings.serializer(), s)))
        assertEquals(ColorMixer(), s.mixer.with(ColorRange.BLUE, HslShift()))
    }

    @Test
    fun `presets only touch their sections`() {
        val photo = DevelopSettings(whiteBalance = WhiteBalance(WbPreset.CUSTOM, 4800f, 5f), basic = BasicTone(exposure = 1f))
        val moody = BuiltInPresets.all.first { it.name == "Moody" }
        val out = moody.applyTo(photo)
        assertEquals(photo.whiteBalance, out.whiteBalance) // untouched
        assertEquals(moody.settings.basic, out.basic) // replaced
        assertEquals(210f, out.grading.shadows.hue)
        // Copy/paste with a section subset.
        val pasted = DevelopSection.WHITE_BALANCE.copy(from = photo, to = DevelopSettings())
        assertEquals(photo.whiteBalance, pasted.whiteBalance)
        assertEquals(BasicTone(), pasted.basic)
    }
}
