package app.croptop.feature.editor.path

import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.NativeEngine
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.ShapeBooleanOperation
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.VectorShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the visible Paths dock against the real document engine on an Android device. */
@RunWith(AndroidJUnit4::class)
class PathBooleanPanelDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun uniteUsesOrderedPathSelectionAndCommitsOneUndoableEngineEdit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeEngine(context)
        val document = engine.newDocument(NewDocumentSpec(name = "Path boolean", size = DocSize(96, 96)))
        val drafts = PathDraftController()
        val subject = checkNotNull(drafts.beginShapePath(DocPoint(8f, 8f), DocPoint(48f, 48f)))
        val clip = checkNotNull(drafts.beginShapePath(DocPoint(32f, 32f), DocPoint(72f, 72f)))
        val layerId = LayerId(77_001L)
        try {
            assertTrue(document.createShapeLayer(layerId, "Shapes", drafts.state.value.paths) is CommandResult.Accepted)
            val results = mutableListOf<CommandResult>()
            val operations = mutableListOf<ShapeBooleanOperation>()
            compose.setContent {
                CropTopTheme(density = AeDensity.Pointer) {
                    PathsPanelContent(
                        controller = drafts,
                        onPathBoolean = { selectedSubject, selectedClip, operation ->
                            operations += operation
                            document.combineShapePaths(layerId, selectedSubject, selectedClip, operation)
                                .also(results::add) is CommandResult.Accepted
                        },
                        modifier = androidx.compose.ui.Modifier.width(720.dp),
                    )
                }
            }

            compose.onNodeWithText("Unite").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Use ${subject.name} in path boolean").performClick()
            compose.onNodeWithContentDescription("Use ${clip.name} in path boolean").performClick()
            compose.onNodeWithText("Unite").assertIsEnabled().performClick()

            compose.runOnIdle {
                assertEquals(listOf(ShapeBooleanOperation.UNITE), operations)
                assertTrue(results.single() is CommandResult.Accepted)
                assertEquals(listOf(subject.id), document.layers.value.find(layerId)!!.vectorPaths.map { it.id })
            }
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(listOf(subject.id, clip.id), document.layers.value.find(layerId)!!.vectorPaths.map { it.id })
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun pathToSelectionCreatesNativeDocumentSelectionFromTheSelectedClosedPath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeEngine(context)
        val document = engine.newDocument(NewDocumentSpec(name = "Path selection", size = DocSize(96, 96)))
        val drafts = PathDraftController()
        drafts.setShapeOptions(drafts.state.value.shapeOptions.copy(kind = VectorShapeKind.ELLIPSE))
        val ellipse = checkNotNull(drafts.beginShapePath(DocPoint(12f, 16f), DocPoint(76f, 68f)))
        val layerId = LayerId(77_002L)
        try {
            assertTrue(document.createShapeLayer(layerId, "Ellipse", drafts.state.value.paths) is CommandResult.Accepted)
            val results = mutableListOf<CommandResult>()
            compose.setContent {
                CropTopTheme(density = AeDensity.Pointer) {
                    PathsPanelContent(
                        controller = drafts,
                        onPathToSelection = { selection ->
                            document.select(selection).also(results::add) is CommandResult.Accepted
                        },
                        modifier = androidx.compose.ui.Modifier.width(720.dp),
                    )
                }
            }

            compose.onNodeWithText("Path to selection").assertIsEnabled().performClick()

            compose.runOnIdle {
                assertTrue(results.single() is CommandResult.Accepted)
                assertEquals(SelectionInfo.Active(DocRect(12, 16, 76, 68)), document.selection.value)
            }
            // This rectangle lies inside the ellipse's bounding box but outside its curved edge.
            // Intersecting it must clear the selection; a mistaken rectangular conversion would
            // leave an active selection here.
            assertTrue(
                document.select(
                    SelectionShape.Rect(DocRectF(12f, 16f, 18f, 22f)),
                    op = SelectionOp.INTERSECT,
                    antiAlias = false,
                ) is CommandResult.Accepted,
            )
            assertEquals(SelectionInfo.None, document.selection.value)
        } finally {
            document.close()
            engine.close()
        }
    }
}
