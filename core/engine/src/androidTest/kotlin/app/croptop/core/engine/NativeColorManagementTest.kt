package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeColorManagementTest {
    @Test
    fun everyBuiltInWorkingSpaceProducesAnRgbProfile() {
        val engine = NativeEngine()
        try {
            NativeColorManagementBridge.BuiltInSpace.entries.forEach { space ->
                NativeColorManagementBridge.Profile.builtIn(space).use { profile ->
                    assertEquals(space.name, NativeColorManagementBridge.Model.RGB, profile.model)
                    assertTrue("${space.name} ICC data", profile.iccBytes().size > 128)
                }
            }
            NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.SRGB, linear = true).use { linear ->
                assertEquals(NativeColorManagementBridge.Model.RGB, linear.model)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun builtInAndEmbeddedProfilesRoundTripAndReadoutCloses() {
        val engine = NativeEngine()
        val displayP3 = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3)
        val cmyk = NativeColorManagementBridge.Profile.genericCmyk()
        try {
            assertEquals(NativeColorManagementBridge.Model.RGB, displayP3.model)
            assertTrue(displayP3.description.contains("Display P3"))
            val encoded = displayP3.iccBytes()
            assertTrue(encoded.size > 128)

            NativeColorManagementBridge.Profile.fromIcc(encoded).use { decoded ->
                assertEquals(displayP3.description, decoded.description)
                assertEquals(NativeColorManagementBridge.Model.RGB, decoded.model)
                assertTrue(decoded.iccBytes().contentEquals(encoded))
            }

            val readout = NativeColorManagementBridge.ReadoutSession(displayP3, cmyk)
            val value = readout.read(floatArrayOf(0.2f, 0.5f, 1f))
            assertEquals(3, value.lab.size)
            assertEquals(4, value.cmykPercent.size)
            assertTrue(value.lab.all { it.isFinite() })
            assertTrue(value.cmykPercent.all { it.isFinite() && it in 0f..100f })
            assertTrue("synthetic press output respects its total ink limit", value.cmykPercent.sum() <= 300.01f)
            readout.close()
            assertThrows(IllegalStateException::class.java) { readout.read(floatArrayOf(0f, 0f, 0f)) }
        } finally {
            cmyk.close()
            displayP3.close()
            engine.close()
        }
    }

    @Test
    fun malformedEmbeddedProfileIsRejected() {
        val engine = NativeEngine()
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                NativeColorManagementBridge.Profile.fromIcc(byteArrayOf(1, 2, 3, 4))
            }
            assertTrue(failure.message.orEmpty().contains("ICC", ignoreCase = true))
        } finally {
            engine.close()
        }
    }
}
