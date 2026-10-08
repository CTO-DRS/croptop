package app.croptop.feature.develop

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.BasicTone
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class DevelopScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A sky-to-ground gradient stands in for a photo. */
    private fun photo(): Bitmap {
        val w = 300
        val h = 200
        val px = IntArray(w * h) { i ->
            val y = i / w
            val x = i % w
            val t = y / h.toFloat()
            val r = (70 + 150 * t + x / 6).toInt().coerceAtMost(255)
            val g = (120 + 60 * t).toInt()
            val b = (210 - 150 * t).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun developScreen() {
        val vm = DevelopViewModel(MemoryDevelopRepo(), FakeEngine(), "content://t/DSCF0412.RAF")
        vm.set("Exposure") { it.copy(basic = BasicTone(exposure = 0.4f, contrast = 20f, vibrance = 15f)) }
        val route =
            DevelopRoute("content://t/DSCF0412.RAF", listOf("content://t/DSCF0411.RAF", "content://t/DSCF0412.RAF", "content://t/DSCF0413.RAF"))
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    DevelopContent(vm, route, onBack = {}, onOpenInEditor = { _, _ -> }, onOpenSibling = {}, previewBitmap = photo())
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/develop_1280x800.png")
    }

    @Test
    fun developScreenWithMasksAndSpots() {
        val vm = DevelopViewModel(MemoryDevelopRepo(), FakeEngine(), "content://t/DSCF0412.RAF")
        vm.addMask(app.croptop.core.model.MaskKind.RADIAL)
        vm.addMask(app.croptop.core.model.MaskKind.LINEAR)
        vm.addSpot(0.3f, 0.4f, 1.5f)
        vm.selectMask(0)
        val route = DevelopRoute("content://t/DSCF0412.RAF", listOf("content://t/DSCF0411.RAF", "content://t/DSCF0412.RAF"))
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    DevelopContent(vm, route, onBack = {}, onOpenInEditor = { _, _ -> }, onOpenSibling = {}, previewBitmap = photo())
                }
            }
        }
        compose.onNodeWithText("LOCAL MASKS").performScrollTo().performClick()
        compose.onNodeWithText("Delete mask").performScrollTo()
        compose.onRoot().captureRoboImage("src/test/snapshots/develop_masks_1280x800.png")
    }

    @Test
    fun `open in editor forwards the loaded non-neutral develop recipe`() {
        val repo = MemoryDevelopRepo()
        val recipe = app.croptop.core.model.DevelopSettings(basic = BasicTone(exposure = -1.25f))
        repo.stored.value = recipe
        val uri = "content://t/photo.raf"
        val vm = DevelopViewModel(repo, FakeEngine(), uri)
        var opened: Pair<String, app.croptop.core.model.DevelopSettings?>? = null
        val route = DevelopRoute(uri)

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    DevelopContent(vm, route, onBack = {}, onOpenInEditor = { source, settings -> opened = source to settings }, onOpenSibling = {})
                }
            }
        }

        compose.onNodeWithText("Open in Editor").performClick()

        org.junit.Assert.assertEquals(uri to recipe, opened)
    }

    @Test
    fun `sidecar export unsupported feedback is visible`() {
        val uri = "content://t/photo.dng"
        val vm = DevelopViewModel(MemoryDevelopRepo(), FakeEngine(), uri)
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    DevelopContent(vm, DevelopRoute(uri), onBack = {}, onOpenInEditor = { _, _ -> }, onOpenSibling = {})
                }
            }
        }

        compose.onNodeWithText("Save XMP Sidecar").performClick()
        compose.onNodeWithText("RAW Develop sidecar export is not supported by this engine").assertExists()
        compose.onNodeWithText("Open in Editor").assertExists()
    }
}
