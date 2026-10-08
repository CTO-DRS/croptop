package app.croptop.core.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.croptop.core.designsystem.catalog.AeCatalog
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Design-system snapshots. Record: ./gradlew recordRoborazziDebug */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h2400dp-mdpi")
class CatalogScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shoot(name: String, theme: ThemePreference, density: AeDensity) {
        compose.setContent {
            CropTopTheme(theme = theme, density = density) { AeCatalog(scrollable = false) }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/catalog_$name.png")
    }

    @Test fun darkPointer() = shoot("dark_pointer", ThemePreference.Dark, AeDensity.Pointer)

    @Test fun lightPointer() = shoot("light_pointer", ThemePreference.Light, AeDensity.Pointer)

    @Test fun darkTouch() = shoot("dark_touch", ThemePreference.Dark, AeDensity.Touch)
}
