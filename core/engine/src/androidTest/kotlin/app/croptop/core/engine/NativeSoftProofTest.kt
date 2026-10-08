package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.SoftProofSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSoftProofTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun bakedProofLutChangesSaturatedColoursKeepsAlphaAndGamutWarningPaintsGrey() {
        NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.SRGB).use { srgb ->
            NativeColorManagementBridge.Profile.genericCmyk(300f).use { press ->
                val proofed = NativeColorManagementBridge.bakeDisplayLut(srgb, srgb, 33, press)
                assertEquals(33 * 33 * 33 * 3, proofed.size)
                val frame = intArrayOf(argb(255, 0, 0, 255), argb(128, 217, 166, 140), argb(255, 128, 128, 128))
                val before = frame.copyOf()
                assertTrue(NativeColorManagementBridge.applyLutArgb(frame, frame.size, proofed, 33))
                assertEquals("alpha is untouched", 128, frame[1] ushr 24)
                // sRGB blue is outside a press gamut: it comes back duller (more red/green than the pure blue it was).
                assertTrue("press blue is duller: ${Integer.toHexString(frame[0])}", ((frame[0] shr 16) and 0xFF) + ((frame[0] shr 8) and 0xFF) > 40)
                assertEquals("neutral grey stays close", 128f, ((frame[2] shr 8) and 0xFF).toFloat(), 8f)
                assertNotEquals(before[0], frame[0])

                val warned = NativeColorManagementBridge.bakeDisplayLut(srgb, srgb, 33, press, gamutWarning = true)
                val probe = intArrayOf(argb(255, 0, 0, 255), argb(255, 217, 166, 140))
                NativeColorManagementBridge.applyLutArgb(probe, probe.size, warned, 33)
                assertEquals("out-of-gamut blue becomes the warning grey", 128f, ((probe[0] shr 16) and 0xFF).toFloat(), 3f)
                assertEquals(128f, (probe[0] and 0xFF).toFloat(), 3f)
                assertEquals("in-gamut skin is left alone", 217f, ((probe[1] shr 16) and 0xFF).toFloat(), 12f)
                // Wrong sizes are refused instead of reading past the arrays.
                assertTrue(!NativeColorManagementBridge.applyLutArgb(probe, 5, warned, 33))
            }
        }
    }

    @Test
    fun documentSoftProofTurnsOnAndOffAsAViewSettingWithoutHistory() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Proof", DocSize(32, 32), background = BackgroundContents.WHITE))
        try {
            val before = document.history.value
            assertNull(document.softProof.value)
            val settings = SoftProofSettings(gamutWarning = true)
            assertTrue(document.setSoftProof(settings) is CommandResult.Accepted)
            assertEquals(settings, document.softProof.value)
            assertEquals("a view setting adds no history", before, document.history.value)
            assertTrue(document.setSoftProof(SoftProofSettings(target = app.croptop.core.model.ProofTarget.ADOBE_RGB)) is CommandResult.Accepted)
            assertTrue(document.setSoftProof(null) is CommandResult.Accepted)
            assertNull(document.softProof.value)
        } finally {
            document.close()
            engine.close()
        }
    }
}
