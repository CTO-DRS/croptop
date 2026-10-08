package app.croptop.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFilterStateTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun legacyFilterSettingsDefaultToEnabledNormalFullOpacity() {
        val legacy = json.decodeFromString<AdjustmentInfo>("""{"specId":"filter.gaussianBlur","values":{}}""")

        assertTrue(legacy.enabled)
        assertEquals(BlendMode.NORMAL, legacy.blendMode)
        assertEquals(1f, legacy.opacity)
    }

    @Test
    fun smartFilterStateRoundTrips() {
        val filter = AdjustmentInfo(
            specId = "filter.gaussianBlur",
            values = mapOf("radius" to ParamValue.Num(8f)),
            enabled = false,
            blendMode = BlendMode.MULTIPLY,
            opacity = 0.35f,
        )

        assertEquals(filter, json.decodeFromString<AdjustmentInfo>(json.encodeToString(filter)))
    }

    @Test
    fun smartFilterOpacityMustBeFiniteAndInRange() {
        assertFalse(runCatching { AdjustmentInfo("filter.gaussianBlur", opacity = -0.1f) }.isSuccess)
        assertFalse(runCatching { AdjustmentInfo("filter.gaussianBlur", opacity = Float.NaN) }.isSuccess)
        assertFalse(runCatching { AdjustmentInfo("filter.gaussianBlur", opacity = 1.1f) }.isSuccess)
    }
}
