package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAdjustmentLutBridgeTest {
    @Test
    fun invertReturnsRgbaTextureAndInvalidBlockReportsError() {
        // NativeEngine's class initializer loads the shared library used by the isolated bridge.
        val engine = NativeEngine(ApplicationProvider.getApplicationContext<Context>())
        try {
            val lut = NativeAdjustmentLutBridge.nativeBakeAdjustmentLut("invert", floatArrayOf(), 2)
            assertNotNull(lut)
            assertEquals(32, lut!!.size) // 2^3 RGBA texels
            assertEquals(1f, lut[0], 0f) // input black maps to white
            assertEquals(1f, lut[3], 0f) // alpha is fixed
            assertEquals(0f, lut[4], 0f) // second texel advances red first and maps red=1 to 0

            assertNull(NativeAdjustmentLutBridge.nativeBakeAdjustmentLut("threshold", floatArrayOf(), 2))
            assertTrue(NativeAdjustmentLutBridge.nativeAdjustmentLutLastError().isNotBlank())
        } finally {
            engine.close()
        }
    }
}
