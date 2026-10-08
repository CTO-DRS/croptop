package app.croptop.feature.editor.dialogs

import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EngineJob
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.RgbaColor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
class FillDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectedContentsPassSpecificActionNameToTheEngineCallback() {
        val action = AtomicReference<String?>()
        var dismissed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                FillDialog(
                    foreground = RgbaColor.Black,
                    background = RgbaColor.White,
                    hasSelection = true,
                    onApply = { _, _, _, _, name ->
                        action.set(name)
                        EngineJob.completed(CommandResult.Accepted(1))
                    },
                    onDismiss = { dismissed = true },
                    initial = FillContents.BLACK,
                )
            }
        }

        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { action.get() != null && dismissed }

        assertEquals("Fill with black", action.get())
    }

    @Test
    fun contentAwareOptionsReachTheEngineSource() {
        val source = AtomicReference<app.croptop.core.model.FillSource?>()
        var dismissed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                FillDialog(
                    foreground = RgbaColor.Black,
                    background = RgbaColor.White,
                    hasSelection = true,
                    onApply = { src, _, _, _, _ ->
                        source.set(src)
                        EngineJob.completed(CommandResult.Accepted(1))
                    },
                    onDismiss = { dismissed = true },
                    initial = FillContents.CONTENT_AWARE,
                    newLayerId = { app.croptop.core.model.LayerId(77) },
                )
            }
        }

        compose.onNodeWithText("Mirror patches").performClick()
        compose.onNodeWithText("Color adaptation").performClick()
        // Output to a new layer is on by default: the fill never touches the original unless the person turns it off.
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { source.get() != null && dismissed }

        val options = (source.get() as app.croptop.core.model.FillSource.ContentAware).options
        assertTrue(options.mirror)
        assertFalse(options.colorAdaptation)
        assertEquals(app.croptop.core.model.LayerId(77), options.newLayerId)
    }

    @Test
    fun contentAwareFillCanWriteIntoTheCurrentLayerWhenAsked() {
        val source = AtomicReference<app.croptop.core.model.FillSource?>()
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                FillDialog(
                    foreground = RgbaColor.Black,
                    background = RgbaColor.White,
                    hasSelection = true,
                    onApply = { src, _, _, _, _ ->
                        source.set(src)
                        EngineJob.completed(CommandResult.Accepted(1))
                    },
                    onDismiss = {},
                    initial = FillContents.CONTENT_AWARE,
                    newLayerId = { app.croptop.core.model.LayerId(77) },
                )
            }
        }
        compose.onNodeWithText("Output to a new layer").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { source.get() != null }
        assertEquals(null, (source.get() as app.croptop.core.model.FillSource.ContentAware).options.newLayerId)
    }

    @Test
    fun paintedOnlySamplingBlocksOkUntilAnAreaIsAdded() {
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                FillDialog(
                    foreground = RgbaColor.Black,
                    background = RgbaColor.White,
                    hasSelection = true,
                    onApply = { _, _, _, _, _ -> EngineJob.completed(CommandResult.Accepted(1)) },
                    onDismiss = {},
                    initial = FillContents.CONTENT_AWARE,
                )
            }
        }

        compose.onNodeWithText("Sample only from the areas I paint").performClick()
        compose.onNodeWithText("OK").assertIsNotEnabled()
    }

    @Test
    fun idleCancelReportsCancelBeforeDismissal() {
        var canceled = 0
        var dismissed = 0
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                FillDialog(
                    foreground = RgbaColor.Black,
                    background = RgbaColor.White,
                    hasSelection = true,
                    onApply = { _, _, _, _, _ -> EngineJob.completed(CommandResult.Accepted(1)) },
                    onDismiss = { dismissed++ },
                    onCancel = { canceled++ },
                )
            }
        }

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, canceled)
        assertEquals(1, dismissed)
    }

    @Test
    fun applyRunsOffMainShowsRealIndeterminateStateAndIgnoresRepeatConfirm() {
        val job = AtomicReference<ControlledFillJob?>()
        val calls = AtomicInteger()
        val callbackThread = AtomicReference<Thread?>()
        var dismissed = false

        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                FillDialog(
                    foreground = RgbaColor.Black,
                    background = RgbaColor.White,
                    hasSelection = true,
                    onApply = { _, _, _, _, _ ->
                        calls.incrementAndGet()
                        callbackThread.set(Thread.currentThread())
                        ControlledFillJob().also(job::set)
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { job.get() != null }
        compose.onNodeWithText("Applying fill…").assertExists()
        compose.onNodeWithText("Applying…").assertIsNotEnabled()
        job.get()!!.setProgress(0.42f)
        compose.onNodeWithText("42%").assertExists()
        assertFalse(dismissed)

        compose.onNodeWithText("Applying…").performClick()
        assertEquals(1, calls.get())
        assertNotNull(callbackThread.get())
        assertFalse(callbackThread.get() === Looper.getMainLooper().thread)
        job.get()!!.complete(CommandResult.Accepted(1))
        compose.waitUntil(5_000) { dismissed }
    }

    @Test
    fun cancelWaitsForEngineAcknowledgmentBeforeDismissing() {
        val job = AtomicReference<ControlledFillJob?>()
        var dismissed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                FillDialog(
                    foreground = RgbaColor.Black,
                    background = RgbaColor.White,
                    hasSelection = true,
                    onApply = { _, _, _, _, _ -> ControlledFillJob().also(job::set) },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { job.get() != null }
        compose.onNodeWithText("Cancel fill").performClick()

        assertEquals(1, job.get()!!.cancelRequests.get())
        assertFalse(dismissed)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Canceling fill…").fetchSemanticsNodes().isNotEmpty()
        }
        job.get()!!.complete(CommandResult.Rejected(RejectionCode.CANCELLED, "Canceled"))
        compose.waitUntil(5_000) { dismissed }
    }

    @Test
    fun cancelBeforeEngineJobExistsIsForwardedWhenItArrives() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = AtomicReference<ControlledFillJob?>()
        var dismissed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                FillDialog(
                    foreground = RgbaColor.Black,
                    background = RgbaColor.White,
                    hasSelection = true,
                    onApply = { _, _, _, _, _ ->
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        ControlledFillJob().also(job::set)
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { entered.count == 0L }
        compose.onNodeWithText("Cancel fill").performClick()
        compose.onAllNodesWithText("Canceling fill…").assertCountEquals(2)
        assertFalse(dismissed)

        release.countDown()
        compose.waitUntil(5_000) { job.get()?.cancelRequests?.get() == 1 }
        assertEquals(1, job.get()!!.cancelRequests.get())
        assertFalse(dismissed)
        job.get()!!.complete(CommandResult.Rejected(RejectionCode.CANCELLED, "Canceled"))
        compose.waitUntil(5_000) { dismissed }
    }

    @Test
    fun rejectedFillKeepsDialogOpenWithVisibleFeedbackAndCanRetry() {
        val attempts = AtomicInteger()
        var dismissed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                FillDialog(
                    foreground = RgbaColor.Black,
                    background = RgbaColor.White,
                    hasSelection = true,
                    onApply = { _, _, _, _, _ ->
                        if (attempts.incrementAndGet() == 1) {
                            EngineJob.completed(
                                CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Selection mask could not be prepared"),
                            )
                        } else {
                            EngineJob.completed(CommandResult.Accepted(1))
                        }
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Selection mask could not be prepared")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(dismissed)
        assertEquals(1, attempts.get())

        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { dismissed }
        assertEquals(2, attempts.get())
    }
}

private class ControlledFillJob : EngineJob {
    private val terminal = CompletableDeferred<CommandResult>()
    private val mutableProgress = MutableStateFlow<Float?>(null)
    override val progress: StateFlow<Float?> = mutableProgress
    val cancelRequests = AtomicInteger()

    override suspend fun await(): CommandResult = terminal.await()

    override fun cancel() {
        cancelRequests.incrementAndGet()
    }

    fun complete(result: CommandResult) {
        terminal.complete(result)
    }

    fun setProgress(value: Float?) {
        mutableProgress.value = value
    }
}
