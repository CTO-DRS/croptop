package app.croptop.feature.editor.path

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.croptop.core.engine.NativeEngine
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SaveOptions
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ShapeToolOptions
import app.croptop.core.model.ToolId
import app.croptop.core.model.VectorShapeKind
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.feature.editor.tools.PointerPhase
import app.croptop.feature.editor.tools.ToolContext
import app.croptop.feature.editor.tools.ToolPointerEvent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the actual editor path controller against NativeEngine's persisted vector-layer contract. */
@RunWith(AndroidJUnit4::class)
class NativeVectorPathWorkflowTest {
    @Test
    fun horizontalLineCommitsAsAnOpenVectorPathAndRendersArrowhead() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(name = "Horizontal line", size = DocSize(96, 96), background = app.croptop.core.model.BackgroundContents.TRANSPARENT),
        )
        try {
            val drafts = PathDraftController()
            drafts.setShapeOptions(
                ShapeToolOptions(
                    kind = VectorShapeKind.LINE,
                    fillEnabled = false,
                    strokeColor = RgbaColor(1f, 0f, 0f, 1f),
                    strokeWidth = 4f,
                    arrowAtEnd = true,
                ),
            )
            val layerId = LayerId(41_000L)
            val controller = VectorPathToolController(ToolId.LINE, drafts) { paths ->
                document.createShapeLayer(layerId, "Line", paths) is CommandResult.Accepted
            }
            val toolContext = object : ToolContext {
                override val viewport = ViewportState(viewWidth = 96, viewHeight = 96)
                override val document = DocSize(96, 96)
                override fun updateViewport(state: ViewportState) = Unit
            }
            fun event(phase: PointerPhase, x: Float, y: Float) = ToolPointerEvent(
                phase = phase,
                view = ViewPoint(x, y),
                doc = DocPoint(x, y),
                pressure = 1f,
                modifiers = Modifiers.None,
                downAt = ViewPoint(x, y),
                pointerKind = app.croptop.core.input.PointerKind.MOUSE,
                eventTimeMillis = System.nanoTime() / 1_000_000L,
            )

            assertTrue(controller.onPointer(event(PointerPhase.DOWN, 12f, 48f), toolContext))
            assertTrue(controller.onPointer(event(PointerPhase.UP, 82f, 48f), toolContext))
            val path = document.layers.value.find(layerId)!!.vectorPaths.single()
            assertNull(path.primitive)
            assertEquals(2, path.subpaths.single().anchors.size)
            assertFalse(path.subpaths.single().closed)
            assertTrue(path.style.arrowAtEnd)
            document.awaitViewportPreview()
            assertTrue(document.sample(DocPoint(45f, 48f), SampleMode.MERGED).color.a > 0.95f)
            // At x=80 the arrow is only 2 px from its tip, where its 4 px-wide stroke produces
            // less than one pixel of vertical coverage. Sample inside the head instead.
            assertTrue(document.sample(DocPoint(74f, 48f), SampleMode.MERGED).color.a > 0.95f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun rectangleEllipseAndPenUndoRedoSaveReopenAndDelete() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(name = "Path workflow", size = DocSize(96, 96), background = app.croptop.core.model.BackgroundContents.TRANSPARENT),
        )
        val archive = File(context.cacheDir, "path-workflow-${System.nanoTime()}.croptop")
        try {
            val drafts = PathDraftController()
            drafts.setShapeOptions(drafts.state.value.shapeOptions.copy(fillColor = RgbaColor(1f, 0f, 0f, 1f), strokeEnabled = false))
            var shapeLayerId: LayerId? = null
            var generatedLayerId = 40_000L
            fun controller(tool: ToolId) = VectorPathToolController(tool, drafts) { paths ->
                val existing = shapeLayerId
                val result = if (existing == null) {
                    val id = LayerId(generatedLayerId++)
                    document.createShapeLayer(id, "Shape", paths).also {
                        if (it is CommandResult.Accepted) shapeLayerId = id
                    }
                } else {
                    document.setShapePaths(existing, paths)
                }
                result is CommandResult.Accepted
            }
            val toolContext = object : ToolContext {
                override val viewport = ViewportState(viewWidth = 96, viewHeight = 96)
                override val document = DocSize(96, 96)
                override fun updateViewport(state: ViewportState) = Unit
            }
            fun event(phase: PointerPhase, x: Float, y: Float) = ToolPointerEvent(
                phase = phase,
                view = ViewPoint(x, y),
                doc = DocPoint(x, y),
                pressure = 1f,
                modifiers = Modifiers.None,
                downAt = ViewPoint(x, y),
                pointerKind = app.croptop.core.input.PointerKind.MOUSE,
                eventTimeMillis = System.nanoTime() / 1_000_000L,
            )
            fun draw(controller: VectorPathToolController, x1: Float, y1: Float, x2: Float, y2: Float) {
                assertTrue(controller.onPointer(event(PointerPhase.DOWN, x1, y1), toolContext))
                assertTrue(controller.onPointer(event(PointerPhase.UP, x2, y2), toolContext))
            }

            val rectangle = controller(ToolId.RECTANGLE)
            draw(rectangle, 8f, 9f, 38f, 30f)
            val layerId = checkNotNull(shapeLayerId)
            var paths = document.layers.value.find(layerId)!!.vectorPaths
            assertEquals(1, paths.size)
            assertEquals(LayerKind.SHAPE, document.layers.value.find(layerId)!!.kind)
            assertEquals(4, paths.single().subpaths.single().anchors.size)
            document.awaitViewportPreview()
            assertEquals(1f, document.sample(DocPoint(20f, 20f), SampleMode.MERGED).color.a, 0.05f)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertNull(document.layers.value.find(layerId))
            document.awaitViewportPreview()
            assertEquals(0f, document.sample(DocPoint(20f, 20f), SampleMode.MERGED).color.a, 0.05f)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(1, document.layers.value.find(layerId)!!.vectorPaths.size)
            document.awaitViewportPreview()
            assertEquals(1f, document.sample(DocPoint(20f, 20f), SampleMode.MERGED).color.a, 0.05f)

            draw(controller(ToolId.ELLIPSE), 45f, 12f, 78f, 42f)
            paths = document.layers.value.find(layerId)!!.vectorPaths
            assertEquals(2, paths.size)
            assertEquals(4, paths.last().subpaths.single().anchors.size)
            document.awaitViewportPreview()
            assertEquals(1f, document.sample(DocPoint(61f, 27f), SampleMode.MERGED).color.a, 0.05f)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(1, document.layers.value.find(layerId)!!.vectorPaths.size)
            assertTrue(document.redo() is CommandResult.Accepted)

            val pen = controller(ToolId.PEN)
            draw(pen, 12f, 55f, 12f, 55f)
            draw(pen, 66f, 72f, 66f, 72f)
            paths = document.layers.value.find(layerId)!!.vectorPaths
            val penPath = paths.last()
            assertEquals(2, penPath.subpaths.single().anchors.size)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(1, document.layers.value.find(layerId)!!.vectorPaths.last().subpaths.single().anchors.size)
            assertTrue(document.redo() is CommandResult.Accepted)

            document.save(SaveTarget.AppFile(archive.absolutePath), SaveOptions())
            document.awaitViewportPreview()
            val reopened = engine.open(DocumentSource.AppFile(archive.absolutePath), OpenOptions())
            try {
                val restored = reopened.layers.value.flatten().firstOrNull { layer ->
                    layer.kind == LayerKind.SHAPE && layer.vectorPaths == paths
                }
                assertNotNull(restored)
                assertEquals(paths, restored!!.vectorPaths)
                assertTrue(reopened.setActiveLayers(setOf(restored.id), restored.id) is CommandResult.Accepted)
                assertTrue(reopened.execute(EditCommand.DeleteLayer(restored.id)) is CommandResult.Accepted)
                assertNull(reopened.layers.value.find(restored.id))
                assertTrue(reopened.undo() is CommandResult.Accepted)
                assertEquals(paths, reopened.layers.value.find(restored.id)!!.vectorPaths)
            } finally {
                reopened.close()
            }
        } finally {
            document.close()
            engine.close()
            archive.delete()
        }
    }
}
