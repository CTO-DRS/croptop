package app.croptop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.croptop.app.palette.CommandPalette
import app.croptop.core.designsystem.component.LocalOpenCommandPalette
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.Actions
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.input.ShortcutScope
import app.croptop.feature.home.HomeScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi", application = android.app.Application::class)
class CommandPaletteScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun paletteOverHome() {
        val dispatcher = ActionDispatcher(ShortcutManager())
        // Pretend the editor is active: every editor/global action has a handler.
        dispatcher.register(ShortcutScope.EDITOR, Actions.all.associate { it.id to {} })
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides dispatcher, LocalOpenCommandPalette provides {}) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    Box {
                        HomeScreen(onNewDocument = {}, onOpen = {}, onOpenSettings = {})
                        CommandPalette(dispatcher, onDismiss = {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/palette_over_home.png")
    }

    @Test
    fun askReviewsPlanBeforeRunning() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        try {
            val engine = app.croptop.core.engine.fake.FakeEngine()
            val docs = app.croptop.core.agent.OpenDocuments(engine)
            val doc = engine.newDocument(app.croptop.core.model.NewDocumentSpec(size = app.croptop.core.model.DocSize(100, 100)))
            docs.register(doc)
            docs.activate(doc)
            val model = object : app.croptop.core.agent.AskModel {
                override suspend fun unavailableReason(): String? = null

                override suspend fun complete(system: String, prompt: String) =
                    """{"steps":[{"tool":"add_layer","args":{"name":"Sky"},"why":"Add a layer named Sky"},""" +
                        """{"tool":"set_layer","args":{"layer_id":1,"opacity":60},"why":"Set its opacity to 60%"}]}"""
            }
            val ask = app.croptop.core.agent.AskService(java.util.Optional.of(model), docs)
            val dispatcher = ActionDispatcher(ShortcutManager())
            compose.setContent {
                CompositionLocalProvider(LocalActionDispatcher provides dispatcher, LocalOpenCommandPalette provides {}) {
                    CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                        Box {
                            HomeScreen(onNewDocument = {}, onOpen = {}, onOpenSettings = {})
                            CommandPalette(dispatcher, onDismiss = {}, ask = ask)
                        }
                    }
                }
            }
            compose.onNode(androidx.compose.ui.test.hasContentDescription("Search commands")).performTextInput("?add a sky layer at 60%")
            compose.onNode(androidx.compose.ui.test.hasText("Ask: “add a sky layer at 60%”")).performClick()
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/snapshots/palette_ask_review.png")
            kotlin.test.assertTrue(doc.layers.value.flatten().none { it.name == "Sky" }) // nothing ran yet
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }
}
