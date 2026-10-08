package app.croptop.feature.editor.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.DockPanel
import app.croptop.core.model.WorkspaceLayout
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DockWorkspaceFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun panelMenuMoveDispatchesPersistableLayoutAndActionLabel() {
        var changedLayout: WorkspaceLayout? = null
        var action = ""
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.size(800.dp)) {
                    DockColumn(
                        layout = WorkspaceLayout.Essentials,
                        onLayout = {},
                        content = {},
                        onWorkspaceAction = { layout, label ->
                            changedLayout = layout
                            action = label
                        },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("History panel options").performClick()
        compose.onNodeWithText("Move Up").performClick()

        compose.runOnIdle {
            assertEquals("Move History panel up", action)
            assertEquals(WorkspaceLayout.Essentials.move(DockPanel.HISTORY, -1), changedLayout)
        }
    }

    @Test
    fun panelDisclosureDispatchesExpandOrCollapseActionLabel() {
        var action = ""
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.size(800.dp)) {
                    DockColumn(
                        layout = WorkspaceLayout.Essentials,
                        onLayout = {},
                        content = {},
                        onWorkspaceAction = { _, label -> action = label },
                    )
                }
            }
        }

        compose.onNodeWithText("LAYERS").performClick()

        compose.runOnIdle { assertEquals("Collapse Layers panel", action) }
    }

    @Test
    fun dockResizeReportsOneCompletionAfterDragEnds() {
        var action = ""
        var changedLayout: WorkspaceLayout? = null
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.size(800.dp)) {
                    DockResizeHandle(
                        layout = WorkspaceLayout.Essentials,
                        onLayout = {},
                        onWorkspaceAction = { layout, label ->
                            changedLayout = layout
                            action = label
                        },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Resize panels").performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            up()
        }

        compose.runOnIdle {
            assertEquals("Resize panel dock", action)
            assertTrue(changedLayout?.dockWidthDp?.let { it in WorkspaceLayout.MIN_DOCK_DP..<280f } == true)
        }
    }

    @Test
    fun floatingPanelMoveReportsOneCompletionAfterDragEnds() {
        val initial = WorkspaceLayout.Essentials.update(DockPanel.HISTORY) { it.copy(floating = true) }
        var action = ""
        var changedLayout: WorkspaceLayout? = null
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.size(800.dp)) {
                    FloatingPanels(
                        layout = initial,
                        onLayout = {},
                        content = {},
                        onWorkspaceAction = { layout, label ->
                            changedLayout = layout
                            action = label
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("HISTORY").performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            up()
        }

        compose.runOnIdle {
            assertEquals("Move History panel", action)
            assertTrue(changedLayout?.state(DockPanel.HISTORY)?.floatX?.let { it > initial.state(DockPanel.HISTORY).floatX } == true)
        }
    }
}
