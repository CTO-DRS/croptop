package app.croptop.feature.editor.path

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.ShapeBooleanOperation
import app.croptop.core.model.DocPoint
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.ShapeToolOptions
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorShapeKind
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
class PathToolPanelsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pathBooleanActionsCombineSelectedSubjectAndClipPaths() {
        val controller = PathDraftController()
        val subject = controller.beginShapePath(DocPoint(10f, 10f), DocPoint(50f, 50f))!!
        val clip = controller.beginShapePath(DocPoint(30f, 30f), DocPoint(70f, 70f))!!
        val operations = mutableListOf<Triple<VectorPathId, VectorPathId, ShapeBooleanOperation>>()
        val actions = listOf(
            "Unite" to ShapeBooleanOperation.UNITE,
            "Subtract" to ShapeBooleanOperation.SUBTRACT,
            "Intersect" to ShapeBooleanOperation.INTERSECT,
            "Exclude" to ShapeBooleanOperation.EXCLUDE,
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                PathsPanelContent(
                    controller = controller,
                    onPathBoolean = { first, second, operation ->
                        operations += Triple(first, second, operation)
                        true
                    },
                    modifier = Modifier.width(320.dp),
                )
            }
        }

        actions.forEach { (label, _) -> compose.onNodeWithText(label).assertIsDisplayed().assertIsNotEnabled() }
        actions.forEach { (label, _) ->
            compose.onNodeWithContentDescription("Use ${subject.name} in path boolean").performClick()
            compose.onNodeWithContentDescription("Use ${clip.name} in path boolean").performClick()
            compose.onNodeWithText(label).assertIsEnabled().performClick()
        }

        compose.runOnIdle {
            assertEquals(actions.map { Triple(subject.id, clip.id, it.second) }, operations)
        }
    }

    @Test
    fun pathsPanelSelectsTheClickedDraftAnchor() {
        val controller = PathDraftController()
        val path = controller.beginPenPath(DocPoint(10f, 10f))
        controller.appendPenAnchor(DocPoint(30f, 20f))
        val completed = mutableListOf<String>()

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                PathsPanelContent(
                    controller,
                    onActionCompleted = { completed += it },
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        compose.onNodeWithContentDescription("Subpath 1, anchor 1").performClick()

        compose.runOnIdle {
            assertEquals(path.id, controller.state.value.selectedAnchor?.pathId)
            assertEquals(path.subpaths.single().anchors.first().id, controller.state.value.selectedAnchor?.anchorId)
            assertEquals(listOf("Select anchor 1.1"), completed)
        }
    }

    @Test
    fun shapeOptionsReportDiscreteFillChanges() {
        val completed = mutableListOf<String>()
        var options by mutableStateOf(ShapeToolOptions())

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                ShapeOptionsBar(
                    options = options,
                    onOptionsChange = { options = it },
                    modifier = Modifier.width(1000.dp),
                    onActionCompleted = completed::add,
                )
            }
        }

        compose.onNodeWithText("Fill").performClick()

        compose.runOnIdle {
            assertTrue(!options.fillEnabled)
            assertEquals(listOf("Disable shape fill"), completed)
        }
    }

    @Test
    fun pathsPanelCommitsPathDeletionAndRestoresWhenEngineRejectsIt() {
        val controller = PathDraftController()
        controller.beginPenPath(DocPoint(10f, 10f))
        var committed: List<app.croptop.core.model.VectorPathDraft>? = null

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                PathsPanelContent(
                    controller = controller,
                    onPathsChanged = { paths ->
                        committed = paths
                        false
                    },
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        compose.onNodeWithText("Delete path").performClick()

        compose.runOnIdle {
            assertEquals(emptyList(), committed)
            assertEquals(1, controller.state.value.paths.size)
        }
    }

    @Test
    fun pathsPanelCommitsSelectedPathFillDisablement() {
        val controller = PathDraftController()
        val path = controller.beginPenPath(DocPoint(10f, 10f))
        var committed: List<app.croptop.core.model.VectorPathDraft>? = null

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                PathsPanelContent(
                    controller = controller,
                    onPathsChanged = { paths ->
                        committed = paths
                        true
                    },
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        compose.onNodeWithText("Fill").performClick()

        compose.runOnIdle {
            val updated = controller.state.value.paths.single()
            assertEquals(path.id, updated.id)
            assertEquals(null, updated.style.fillColor)
            assertEquals(updated, committed?.single())
        }
    }

    @Test
    fun pathsPanelExposesDirectSelectedPathStrokeControls() {
        val controller = PathDraftController()
        controller.beginPenPath(DocPoint(10f, 10f))

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                PathsPanelContent(controller, modifier = Modifier.width(720.dp))
            }
        }

        compose.onNodeWithText("Path width").assertExists()
        compose.onNodeWithText("Join miter").assertExists()
        compose.onNodeWithText("Cap butt").assertExists()
        compose.onNodeWithText("Path solid").assertExists()
    }

    @Test
    fun pathsPanelPersistsPathWidthOnceAfterSliderDrag() {
        val controller = PathDraftController()
        val path = controller.beginPenPath(DocPoint(10f, 10f))
        val commits = mutableListOf<List<app.croptop.core.model.VectorPathDraft>>()

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                PathsPanelContent(
                    controller = controller,
                    onPathsChanged = { paths ->
                        commits += paths
                        true
                    },
                    modifier = Modifier.width(720.dp),
                )
            }
        }
        compose.onNodeWithText("Path width").performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            up()
        }

        compose.runOnIdle {
            assertEquals(1, commits.size)
            assertTrue(controller.state.value.paths.single().style.strokeWidth > path.style.strokeWidth)
            assertEquals(controller.state.value.paths, commits.single())
        }
    }

    @Test
    fun pathsPanelConvertsClosedPathToSelectionThroughCallback() {
        val controller = PathDraftController()
        controller.setShapeOptions(ShapeToolOptions(kind = VectorShapeKind.ELLIPSE))
        val ellipse = controller.beginShapePath(DocPoint(10f, 20f), DocPoint(90f, 70f))!!
        assertTrue((ellipse.toSelectionPolygon()?.points?.size ?: 0) > ellipse.subpaths.single().anchors.size)
        var selected: SelectionShape? = null

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                PathsPanelContent(
                    controller = controller,
                    onPathToSelection = { shape ->
                        selected = shape
                        true
                    },
                    modifier = Modifier.width(720.dp),
                )
            }
        }
        compose.onNodeWithText("Path to selection").assertIsDisplayed().assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(null, controller.state.value.message)
            val polygon = selected as SelectionShape.Polygon
            assertTrue(polygon.points.size > ellipse.subpaths.single().anchors.size)
        }
    }

    @Test
    fun pathsPanelReportsWhyAnOpenPathCannotBecomeASelection() {
        val controller = PathDraftController()
        controller.beginPenPath(DocPoint(10f, 10f))
        var failure: Pair<String, String>? = null

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                PathsPanelContent(
                    controller = controller,
                    onPathToSelection = { true },
                    onActionFailed = { action, detail -> failure = action to detail },
                    modifier = Modifier.width(720.dp),
                )
            }
        }
        compose.onNodeWithText("Path to selection").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(
                "Path to selection" to "Path to selection needs one closed path with three or more anchors",
                failure,
            )
            assertEquals(failure?.second, controller.state.value.message)
        }
    }
}
