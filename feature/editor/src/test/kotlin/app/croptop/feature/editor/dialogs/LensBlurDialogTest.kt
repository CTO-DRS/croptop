package app.croptop.feature.editor.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DepthRangeSpec
import app.croptop.core.engine.api.EngineJob
import app.croptop.core.engine.api.LensBlurDepthSource
import app.croptop.core.engine.api.LensBlurSpec
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerMaskInfo
import app.croptop.core.model.LayerNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class LensBlurDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun exposesLayerPixelAndMaskDepthAndCreatesUndoableDepthRangeMask() {
        val target = LayerId(1)
        val depth = LayerNode(LayerId(2), "Depth map", LayerKind.PIXEL, mask = LayerMaskInfo())
        var blurSpec: LensBlurSpec? = null
        var blurDepth: LensBlurDepthSource? = null
        var rangeTarget: LayerId? = null
        var rangeDepth: LensBlurDepthSource? = null
        var range: DepthRangeSpec? = null
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                LensBlurDialog(
                    target = target,
                    layers = listOf(LayerNode(target, "Photo", LayerKind.PIXEL), depth),
                    onApply = { id, spec, source ->
                        assertEquals(target, id)
                        blurSpec = spec
                        blurDepth = source
                        EngineJob.completed(CommandResult.Accepted(1))
                    },
                    onDepthRange = { id, source, spec ->
                        rangeTarget = id
                        rangeDepth = source
                        range = spec
                        CommandResult.Accepted(2)
                    },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Depth source").assertExists()
        compose.onNodeWithText("Depth Range Mask").assertExists()
        compose.onNodeWithText("Apply Lens Blur").assertIsDisplayed()
        compose.onNodeWithText("No depth map (uniform blur)").performClick()
        compose.onNodeWithText("Depth map · pixels").performClick()
        compose.onNodeWithText("Create Depth Range Mask").performScrollTo().performClick()
        compose.waitUntil(5_000) { range != null }
        assertEquals(target, rangeTarget)
        assertIs<LensBlurDepthSource.LayerPixels>(rangeDepth)
        assertEquals(0.4f, range?.near)

        compose.onNodeWithText("Apply Lens Blur").performClick()
        compose.waitUntil(5_000) { blurSpec != null }
        assertEquals(15f, blurSpec?.radius)
        assertIs<LensBlurDepthSource.LayerPixels>(blurDepth)
    }

    @Test
    fun estimatingDepthSelectsTheNewDepthMapLayerAsTheSource() {
        val target = LayerId(30)
        val layers = MutableStateFlow(listOf(LayerNode(target, "Photo", LayerKind.PIXEL)))
        var applied: LensBlurDepthSource? = null
        compose.setContent {
            val current by layers.collectAsState()
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                LensBlurDialog(
                    target = target,
                    layers = current,
                    onApply = { _, _, source ->
                        applied = source
                        EngineJob.completed(CommandResult.Accepted(1))
                    },
                    onDepthRange = { _, _, _ -> CommandResult.Accepted(1) },
                    onDismiss = {},
                    onEstimateDepth = {
                        layers.value = layers.value + LayerNode(LayerId(31), "Depth map", LayerKind.PIXEL)
                        true
                    },
                )
            }
        }
        compose.onNodeWithText("Estimate depth from the picture (AI)").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Depth map · pixels").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Apply Lens Blur").performClick()
        compose.waitUntil(5_000) { applied != null }
        assertEquals(LensBlurDepthSource.LayerPixels(LayerId(31)), applied)
    }

    @Test
    fun layerMaskIsAnExplicitDepthSourceOption() {
        val target = LayerId(10)
        val depth = LayerNode(LayerId(11), "Depth map", LayerKind.PIXEL, mask = LayerMaskInfo())
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                LensBlurDialog(target, listOf(depth), { _, _, _ ->
                    EngineJob.completed(CommandResult.Accepted(1))
                }, { _, _, _ -> CommandResult.Accepted(1) }, {})
            }
        }
        compose.onNodeWithText("No depth map (uniform blur)").performClick()
        compose.onNodeWithText("Depth map · pixels").performClick()
        compose.onNodeWithText("Depth map · pixels").performClick()
        compose.onNodeWithText("Depth map · layer mask").assertExists()
    }

    @Test
    fun cancelWaitsForTheEngineCancellationAcknowledgementBeforeDismissing() {
        val job = PendingLensBlurJob()
        var dismissed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                LensBlurDialog(
                    target = LayerId(21),
                    layers = listOf(LayerNode(LayerId(21), "Photo", LayerKind.PIXEL)),
                    onApply = { _, _, _ ->
                        job.started.value = true
                        job
                    },
                    onDepthRange = { _, _, _ -> CommandResult.Accepted(1) },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Apply Lens Blur").performClick()
        compose.waitUntil(5_000) { job.started.value }
        compose.onNodeWithText("Rendering… progress is indeterminate").assertIsDisplayed()
        compose.onNodeWithText("Cancel render").performClick()
        compose.waitUntil(5_000) { job.cancelRequested.value }
        compose.onNodeWithText("Canceling after the current native render…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(false, dismissed) }

        job.result.complete(CommandResult.Rejected(RejectionCode.CANCELLED, "Lens Blur was cancelled"))
        compose.waitUntil(5_000) { dismissed }
    }

    @Test
    fun showsDeterminateEngineProgressWhenAvailable() {
        val job = PendingLensBlurJob()
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                LensBlurDialog(
                    target = LayerId(31),
                    layers = listOf(LayerNode(LayerId(31), "Photo", LayerKind.PIXEL)),
                    onApply = { _, _, _ ->
                        job.started.value = true
                        job
                    },
                    onDepthRange = { _, _, _ -> CommandResult.Accepted(1) },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Apply Lens Blur").performClick()
        compose.waitUntil(5_000) { job.started.value }
        compose.onNodeWithText("Rendering… progress is indeterminate").assertIsDisplayed()
        job.progress.value = 0.42f
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Rendering… 42%").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Rendering… 42%").assertIsDisplayed()
    }

    private class PendingLensBlurJob : EngineJob {
        override val progress = MutableStateFlow<Float?>(null)
        val started = MutableStateFlow(false)
        val cancelRequested = MutableStateFlow(false)
        val result = CompletableDeferred<CommandResult>()

        override suspend fun await(): CommandResult = result.await()

        override fun cancel() {
            cancelRequested.value = true
        }
    }
}
