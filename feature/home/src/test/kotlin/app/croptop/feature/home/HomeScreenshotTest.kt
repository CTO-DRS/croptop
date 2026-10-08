package app.croptop.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.DocSize
import app.croptop.core.model.RecentDocument
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** First-screen snapshots at laptop sizes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shoot(
        name: String,
        theme: ThemePreference = ThemePreference.Dark,
        recents: List<RecentDocument> = emptyList(),
        density: AeDensity = AeDensity.Pointer,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                    CropTopTheme(theme = theme, density = density) {
                        HomeScreen(onNewDocument = {}, onOpen = {}, onOpenSettings = {}, recents = recents, onPaste = {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/home_$name.png")
    }

    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
    @Test
    fun laptop1280Dark() = shoot("1280x800_dark")

    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
    @Test
    fun laptop1280DarkAtTwoHundredPercentFontScale() = shoot("1280x800_dark_200pct", fontScale = 2f)

    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
    @Test
    fun laptop1280Light() = shoot("1280x800_light", ThemePreference.Light)

    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
    @Test
    fun laptop1920Dark() = shoot("1920x1080_dark")

    @Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
    @Test
    fun laptop1280WithRecents() {
        val now = System.currentTimeMillis()
        val docs = listOf(
            RecentDocument("content://1", "IMG_2031.CR3", DocSize(6000, 4000), null, now - 3_600_000, pinned = true),
            RecentDocument("content://2", "portrait_retouch.croptop", DocSize(4000, 5000), null, now - 7_200_000, pinned = false),
            RecentDocument("content://3", "sunset.jpg", DocSize(4032, 3024), null, now - 86_400_000, pinned = false),
            RecentDocument("content://4", "poster.psd", null, null, now - 3 * 86_400_000L, pinned = false),
            RecentDocument("content://5", "street.heic", DocSize(4032, 3024), null, now - 10 * 86_400_000L, pinned = false),
        )
        shoot("1280x900_recents", recents = docs)
    }

    /** LS-D2: phone width — one column, full titles, no keyboard hints. */
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
    @Test
    fun phoneDark() = shoot("phone_411x891_dark", density = AeDensity.Touch)

    @Config(sdk = [35], qualifiers = "w800dp-h700dp-mdpi")
    @Test
    fun narrowWindowDark() = shoot("800x700_dark")

    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
    @Test
    fun newDocumentDialog() {
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                NewDocumentDialog(onCreate = {}, onCancel = {})
            }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/dialog_new_document.png")
    }
}
