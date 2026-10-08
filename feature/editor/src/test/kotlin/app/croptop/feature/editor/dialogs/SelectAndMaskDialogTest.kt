package app.croptop.feature.editor.dialogs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RefineEdgeBrushMode
import app.croptop.core.engine.api.RefineEdgeBrushSpec
import app.croptop.core.engine.api.RefineEdgeOutput
import app.croptop.core.engine.api.RefineEdgeSession
import app.croptop.core.engine.api.RefineEdgeSettings
import app.croptop.core.engine.api.RefineEdgeViewMode
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class SelectAndMaskDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `settings and output selection commit the refine session`() {
        val session = RecordingRefineSession()
        var result: CommandResult? = null
        var selectedOutput: RefineEdgeOutput? = null
        compose.setContent {
            CropTopTheme {
                SelectAndMaskDialog(
                    session = session,
                    onDone = { command, output ->
                        result = command
                        selectedOutput = output
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.waitUntil { session.updates.contains(RefineEdgeSettings()) }
        compose.waitUntil { !compose.onNodeWithText("OK").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }

        compose.onNodeWithText("Smart Radius").performClick()
        compose.waitForIdle()
        compose.waitUntil { session.updates.lastOrNull()?.smartRadius == true }
        compose.waitForIdle()
        compose.onNodeWithText("OK").assertIsEnabled()

        compose.onNodeWithText("Layer Mask").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()

        compose.waitUntil { session.committedOutput != null && result != null }
        assertEquals(RefineEdgeOutput.LAYER_MASK, session.committedOutput)
        assertEquals(CommandResult.Accepted(17), result)
        assertEquals(RefineEdgeOutput.LAYER_MASK, selectedOutput)
        assertEquals(0, session.cancelCount)
    }

    @Test
    fun `view mode is truthful and new document commits an output handle`() {
        val session = RecordingRefineSession()
        var result: CommandResult? = null
        var selectedOutput: RefineEdgeOutput? = null
        var viewResult: CommandResult? = null
        compose.setContent {
            CropTopTheme {
                SelectAndMaskDialog(
                    session = session,
                    onDone = { command, output ->
                        result = command
                        selectedOutput = output
                    },
                    onViewModeResult = { viewResult = it },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("On Black").performClick()
        compose.waitForIdle()
        compose.waitUntil { viewResult != null }
        compose.waitForIdle()
        assertEquals(RefineEdgeViewMode.ON_BLACK, session.viewMode.value)
        assertEquals(CommandResult.Accepted(17), viewResult)

        compose.onNodeWithText("New Document").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()

        compose.waitUntil { result != null }
        assertEquals(RefineEdgeOutput.NEW_DOCUMENT, session.committedOutput)
        assertEquals(RefineEdgeOutput.NEW_DOCUMENT, selectedOutput)
        assertEquals(CommandResult.Accepted(17), result)
        assertNotNull(session.outputDocument)
    }

    @Test
    fun `cancel restores the previous refine session state`() {
        val session = RecordingRefineSession()
        var result: CommandResult? = CommandResult.Accepted(1)
        compose.setContent {
            CropTopTheme {
                SelectAndMaskDialog(session, onDone = { command, _ -> result = command })
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        compose.waitUntil { session.cancelCount == 1 }
        assertEquals(1, session.cancelCount)
        assertEquals(null, result)
    }

    @Test
    fun `workspace leaves canvas interactive and exposes brush modes`() {
        val session = RecordingRefineSession()
        var canvasClicks = 0
        var brushSpec = RefineEdgeBrushSpec()
        compose.setContent {
            CropTopTheme {
                Box {
                    Text("Canvas target", Modifier.clickable { canvasClicks++ })
                    SelectAndMaskDialog(
                        session = session,
                        onDone = { _, _ -> },
                        brushSpec = brushSpec,
                        onBrushSpecChange = { brushSpec = it },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Subtract").performClick()
        compose.onNodeWithText("Canvas target").performClick()
        compose.waitForIdle()

        assertEquals(RefineEdgeBrushMode.SUBTRACT, brushSpec.mode)
        assertEquals(1, canvasClicks)
    }

    @Test
    fun `preview error stays visible until a successful retry`() {
        val session = RecordingRefineSession().apply { rejectPreview = true }
        compose.setContent { CropTopTheme { SelectAndMaskDialog(session, onDone = { _, _ -> }) } }
        compose.waitUntil {
            compose.onAllNodesWithText("Preview failed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("OK").assertIsNotEnabled()
        session.rejectPreview = false
        compose.onNodeWithText("Retry preview").performClick()
        compose.waitUntil {
            compose.onAllNodesWithText("Preview ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("OK").assertIsEnabled()
        compose.onNodeWithText("Preview failed").assertDoesNotExist()
    }

    @Test
    fun `slow preview keeps canvas interactive and cancel restores after computation`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val session = RecordingRefineSession {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        var canvasClicks = 0
        var requestedCancel by mutableStateOf(false)
        compose.setContent {
            CropTopTheme {
                Box {
                    Text("Canvas target", Modifier.clickable { canvasClicks++ })
                    SelectAndMaskDialog(session, onDone = { _, _ -> }, cancelRequested = requestedCancel)
                }
            }
        }
        try {
            compose.waitUntil { entered.count == 0L }
            compose.onNodeWithText("Updating preview…").assertExists()
            compose.onNodeWithText("OK").assertIsNotEnabled()
            compose.onNodeWithText("Canvas target").performClick()
            assertEquals(1, canvasClicks)
            compose.runOnIdle { requestedCancel = true }
            compose.onNodeWithText("Restoring selection…").assertExists()
            release.countDown()
            compose.waitUntil { session.cancelCount == 1 }
        } finally {
            release.countDown()
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h480dp-mdpi")
    fun `compact panel keeps actions visible and outputs reachable`() {
        val session = RecordingRefineSession()
        compose.setContent { CropTopTheme { SelectAndMaskDialog(session, onDone = { _, _ -> }) } }
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsDisplayed()
        compose.onNodeWithText("New Document").performScrollTo().assertIsDisplayed()
    }

    private class RecordingRefineSession(private val beforeUpdate: () -> Unit = {}) : RefineEdgeSession {
        @Volatile var rejectPreview = false
        val updates = java.util.concurrent.CopyOnWriteArrayList<RefineEdgeSettings>()
        @Volatile var committedOutput: RefineEdgeOutput? = null
        @Volatile var cancelCount = 0
        private val viewModeState = MutableStateFlow(RefineEdgeViewMode.OVERLAY)
        override val viewMode: StateFlow<RefineEdgeViewMode> get() = viewModeState
        override var outputDocument: app.croptop.core.engine.api.DocumentHandle? = null
            private set

        override fun setViewMode(mode: RefineEdgeViewMode): CommandResult {
            viewModeState.value = mode
            return CommandResult.Accepted(17)
        }

        override fun update(settings: RefineEdgeSettings): CommandResult {
            beforeUpdate()
            updates += settings
            return if (rejectPreview) {
                CommandResult.Rejected(app.croptop.core.engine.api.RejectionCode.UNSUPPORTED, "Preview failed")
            } else {
                CommandResult.Accepted(17)
            }
        }

        override fun commit(output: RefineEdgeOutput): CommandResult {
            committedOutput = output
            if (output == RefineEdgeOutput.NEW_DOCUMENT) {
                outputDocument = FakeEngine().newDocument(NewDocumentSpec(size = DocSize(32, 24)))
            }
            return CommandResult.Accepted(17)
        }

        override fun cancel() {
            cancelCount++
        }
    }
}
