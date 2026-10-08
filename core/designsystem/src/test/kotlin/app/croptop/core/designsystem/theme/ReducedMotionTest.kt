package app.croptop.core.designsystem.theme

import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReducedMotionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `motion follows preference changes while theme remains mounted`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = context.contentResolver
        val original = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        var motion = AeMotion()
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            compose.setContent {
                CropTopTheme { motion = Ae.motion }
            }
            compose.runOnIdle { assertEquals(120, motion.fastMillis) }
            compose.runOnIdle { Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(AeMotion.Reduced, motion)
                Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(120, motion.fastMillis) }
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, original)
        }
    }

    @Test
    fun `theme releases settings observer when removed`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = context.contentResolver
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val visible = mutableStateOf(false)
        compose.setContent {
            if (visible.value) CropTopTheme {}
        }
        val baseline = shadowOf(resolver).getContentObservers(uri).size
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(shadowOf(resolver).getContentObservers(uri).size > baseline)
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(baseline, shadowOf(resolver).getContentObservers(uri).size) }
    }
}
