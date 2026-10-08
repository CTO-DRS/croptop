package app.croptop.app

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.app.onboarding.WelcomeDialog
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi", application = android.app.Application::class)
class WelcomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun welcomeSteps() {
        compose.setContent { CropTopTheme(density = AeDensity.Pointer) { WelcomeDialog(onFinish = {}) } }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/welcome_1.png")
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Coming from Photoshop?").assertExists()
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/welcome_2.png")
    }
}
