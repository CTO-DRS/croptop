package app.croptop.feature.editor.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import app.croptop.core.data.GradientRepository
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.GradientPreset
import app.croptop.core.model.HistoryEntry
import app.croptop.core.model.HistorySnapshot
import app.croptop.core.model.HistoryState
import app.croptop.feature.editor.text.InlineTextEditor
import app.croptop.feature.editor.tools.GradientPresetDialog
import app.croptop.feature.editor.tools.GradientPresetsViewModel
import app.croptop.feature.editor.workspace.SaveWorkspaceDialog
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HistoryPanelInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun assertFieldAccessibleName(label: String, content: @Composable () -> Unit) {
        compose.setContent { CropTopTheme(density = AeDensity.Pointer, content = content) }
        compose.onNodeWithContentDescription(label).fetchSemanticsNode()
    }

    @Test
    fun historyStepClickDispatchesItsIndex() {
        var jumpedTo: Int? = null
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                HistoryPanelContent(
                    history = HistoryState(entries = listOf(HistoryEntry("Open"), HistoryEntry("Paint")), current = 0),
                    onJump = { jumpedTo = it },
                )
            }
        }
        compose.onNodeWithText("Paint").performClick()

        compose.runOnIdle { assertEquals(1, jumpedTo) }
    }

    @Test
    fun duplicateHistoryLabelsExposePositionAndState() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                HistoryPanelContent(
                    history = HistoryState(
                        entries = listOf(HistoryEntry("Layer properties"), HistoryEntry("Layer properties"), HistoryEntry("Layer properties")),
                        current = 1,
                    ),
                    onJump = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Layer properties, history step 1 of 3, available").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Layer properties, history step 2 of 3, current").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Layer properties, history step 3 of 3, undone").fetchSemanticsNode()
    }

    @Test
    fun snapshotCreationButtonDispatchesAction() {
        var creates = 0
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                HistoryPanelContent(
                    history = HistoryState(),
                    onJump = {},
                    snapshots = SnapshotActions(onCreate = { creates++ }),
                )
            }
        }

        compose.onNodeWithContentDescription("New snapshot").performClick()

        compose.runOnIdle { assertEquals(1, creates) }
    }

    @Test
    fun renameSnapshotFieldHasAnAccessibleName() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                SnapshotNameDialog(initial = "Snapshot 1", onDone = {}, onCancel = {})
            }
        }

        compose.onNodeWithContentDescription("Snapshot name").fetchSemanticsNode()
    }

    @Test
    fun workspaceNameFieldHasAnAccessibleName() {
        assertFieldAccessibleName("Workspace name") { SaveWorkspaceDialog("Workspace", {}, {}) }
    }

    @Test
    fun textContentFieldHasAnAccessibleName() {
        assertFieldAccessibleName("Text content") {
            InlineTextEditor(
                text = "Text",
                onTextChange = {},
                viewport = app.croptop.core.model.ViewportState(),
                origin = app.croptop.core.model.DocPoint(10f, 10f),
                boxWidth = null,
                ppi = 72f,
                character = app.croptop.core.model.CharacterStyle(),
                paragraph = app.croptop.core.model.ParagraphStyle(),
                editing = false,
                onCommit = {},
                onCancel = {},
            )
        }
    }

    @Test
    fun gradientPresetNameFieldHasAnAccessibleName() {
        val repository = object : GradientRepository {
            override val customPresets = flowOf(emptyList<GradientPreset>())
            override suspend fun save(name: String, gradient: ColorGradient) = GradientPreset(1, name, gradient)
            override suspend fun update(id: Long, name: String, gradient: ColorGradient) = true
            override suspend fun delete(id: Long) = true
        }
        val presets = GradientPresetsViewModel(repository)
        assertFieldAccessibleName("Gradient preset name") {
            GradientPresetDialog(ColorGradient.BlackToWhite, {}, {}, presets)
        }
    }

    @Test
    fun layerRenameFieldHasAnAccessibleName() {
        assertFieldAccessibleName("Layer or group name") { RenameField("Layer", {}, {}, {}) }
    }

    @Test
    fun switchingSnapshotRenameTargetsUsesTheSelectedSnapshotName() {
        var snapshotIndex by mutableStateOf(1)
        var initial by mutableStateOf("First snapshot")
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                SnapshotRenameDialog(
                    snapshotIndex,
                    initial,
                    onRename = { false },
                    onRenameAccepted = {},
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Snapshot name").performTextClearance()
        compose.onNodeWithContentDescription("Snapshot name").performTextInput("Unsaved first name")

        compose.runOnIdle {
            snapshotIndex = 2
            initial = "Second snapshot"
        }

        val fieldText = compose.onNodeWithContentDescription("Snapshot name")
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        assertEquals("Second snapshot", fieldText)
    }

    @Test
    fun rejectedSnapshotRenameCanBeCorrectedAndRetried() {
        val renameAttempts = mutableListOf<String>()
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Column(Modifier.width(320.dp).height(500.dp)) {
                    HistoryPanelContent(
                        history = HistoryState(snapshots = listOf(HistorySnapshot("First snapshot", 1))),
                        onJump = {},
                        snapshots = SnapshotActions(onRename = { _, name ->
                            renameAttempts += name
                            renameAttempts.size > 1
                        }),
                    )
                }
            }
        }

        compose.onNodeWithText("First snapshot").performMouseInput { rightClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithContentDescription("Snapshot name").fetchSemanticsNode()

        compose.onNodeWithContentDescription("Snapshot name").performTextClearance()
        compose.onNodeWithContentDescription("Snapshot name").performTextInput("Unique snapshot")
        compose.onNodeWithText("Rename").performClick()

        assertEquals(listOf("First snapshot", "Unique snapshot"), renameAttempts)
        assertTrue(compose.onAllNodesWithText("Rename Snapshot").fetchSemanticsNodes().isEmpty())
    }
}
