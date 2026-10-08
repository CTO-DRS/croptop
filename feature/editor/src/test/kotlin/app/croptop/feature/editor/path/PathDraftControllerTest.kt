package app.croptop.feature.editor.path

import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.PathAnchorMode
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ShapeCornerRadii
import app.croptop.core.model.ShapeToolOptions
import app.croptop.core.model.ToolId
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorPrimitiveMetadata
import app.croptop.core.model.VectorShapeKind
import app.croptop.core.model.VectorSourceBox
import app.croptop.core.model.VectorSubpath
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.feature.editor.tools.PointerPhase
import app.croptop.feature.editor.tools.ToolContext
import app.croptop.feature.editor.tools.ToolPointerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathDraftControllerTest {
    @Test
    fun `rectangle shape draft normalizes reverse drag and snapshots style and primitive source box`() {
        val controller = PathDraftController()
        controller.setShapeOptions(
            ShapeToolOptions(
                kind = VectorShapeKind.RECTANGLE,
                fillColor = RgbaColor(0.2f, 0.3f, 0.4f, 0.8f),
                strokeWidth = 7f,
            ),
        )

        val path = controller.beginShapePath(DocPoint(90f, 70f), DocPoint(10f, 20f))!!

        assertEquals("Rectangle 1", path.name)
        assertEquals(
            listOf(DocPoint(10f, 20f), DocPoint(90f, 20f), DocPoint(90f, 70f), DocPoint(10f, 70f)),
            path.subpaths.single().anchors.map { it.point },
        )
        assertTrue(path.subpaths.single().closed)
        assertEquals(VectorSourceBox(10f, 20f, 80f, 50f), path.primitive?.sourceBox)
        assertEquals(0f, path.primitive?.rotationDegrees)
        assertEquals(RgbaColor(0.2f, 0.3f, 0.4f, 0.8f), path.style.fillColor)
        assertEquals(7f, path.style.strokeWidth)
        assertEquals(path.id, controller.state.value.selectedPathId)
    }

    @Test
    fun `rounded rectangle draft preserves per-corner cubic geometry and rotated primitive bounds`() {
        val controller = PathDraftController()
        val radii = ShapeCornerRadii(topLeft = 6f, topRight = 10f, bottomRight = 4f, bottomLeft = 8f)
        controller.setShapeOptions(
            ShapeToolOptions(kind = VectorShapeKind.ROUNDED_RECTANGLE, cornerRadii = radii, rotationDegrees = 90f),
        )

        val path = controller.beginShapePath(DocPoint(90f, 70f), DocPoint(10f, 20f))!!
        val subpath = path.subpaths.single()

        assertTrue(subpath.closed)
        assertEquals(8, subpath.anchors.size)
        assertTrue(subpath.anchors.any { it.incoming != null })
        assertTrue(subpath.anchors.any { it.outgoing != null })
        assertEquals(8, subpath.anchors.map { it.id }.distinct().size)
        assertEquals(VectorShapeKind.ROUNDED_RECTANGLE, path.primitive?.kind)
        assertEquals(radii, path.primitive?.cornerRadii)
        assertEquals(VectorSourceBox(10f, 20f, 80f, 50f), path.primitive?.sourceBox)
        assertEquals(90f, path.primitive?.rotationDegrees)
        assertTrue(subpath.anchors.first().point.x > 50f)

        controller.setShapeOptions(
            ShapeToolOptions(kind = VectorShapeKind.ROUNDED_RECTANGLE, cornerRadii = ShapeCornerRadii()),
        )
        val squareCornerPath = controller.beginShapePath(DocPoint(0f, 0f), DocPoint(20f, 10f))!!
        assertEquals(4, squareCornerPath.subpaths.single().anchors.size)
        assertEquals(4, squareCornerPath.subpaths.single().anchors.map { it.id }.distinct().size)
        assertTrue(squareCornerPath.subpaths.single().anchors.first().point != squareCornerPath.subpaths.single().anchors.last().point)
    }

    @Test
    fun `rounded rectangle corner handle updates one rotated radius and preserves primitive bounds`() {
        val controller = PathDraftController()
        controller.setShapeOptions(
            ShapeToolOptions(
                kind = VectorShapeKind.ROUNDED_RECTANGLE,
                cornerRadii = ShapeCornerRadii.all(6f),
                rotationDegrees = 90f,
            ),
        )
        val original = controller.beginShapePath(DocPoint(10f, 20f), DocPoint(90f, 70f))!!
        val handle = DocPoint(24f, 34f).rotateAround(DocPoint(50f, 45f), 90f)

        assertTrue(controller.setSelectedRoundedCornerFromPoint(RoundedCorner.TOP_LEFT, handle))

        val updated = controller.state.value.paths.single()
        assertEquals(14f, updated.primitive?.cornerRadii?.topLeft)
        assertEquals(6f, updated.primitive?.cornerRadii?.topRight)
        assertEquals(original.primitive?.sourceBox, updated.primitive?.sourceBox)
        assertEquals(original.primitive?.rotationDegrees, updated.primitive?.rotationDegrees)
        assertTrue(original.subpaths.single().anchors.map { it.point } != updated.subpaths.single().anchors.map { it.point })
    }

    @Test
    fun `direct select corner handle commits one edit on pointer release`() {
        val drafts = PathDraftController()
        drafts.setShapeOptions(
            ShapeToolOptions(kind = VectorShapeKind.ROUNDED_RECTANGLE, cornerRadii = ShapeCornerRadii.all(6f)),
        )
        drafts.beginShapePath(DocPoint(10f, 20f), DocPoint(90f, 70f))
        val before = drafts.state.value.paths
        var commits = 0
        val tool = VectorPathToolController(ToolId.DIRECT_SELECT, drafts) {
            commits += 1
            true
        }
        val context = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, point: DocPoint) = ToolPointerEvent(
            phase = phase,
            view = ViewPoint(point.x, point.y),
            doc = point,
            pressure = 1f,
            modifiers = Modifiers(),
            downAt = ViewPoint(point.x, point.y),
        )

        assertTrue(tool.onPointer(event(PointerPhase.DOWN, DocPoint(16f, 26f)), context))
        assertTrue(tool.onPointer(event(PointerPhase.MOVE, DocPoint(24f, 34f)), context))
        assertTrue(tool.onPointer(event(PointerPhase.UP, DocPoint(24f, 34f)), context))

        assertEquals(1, commits)
        assertEquals(14f, drafts.state.value.paths.single().primitive?.cornerRadii?.topLeft)
        assertTrue(before != drafts.state.value.paths)
    }

    @Test
    fun `path select moves the complete primitive and commits once`() {
        val drafts = PathDraftController()
        drafts.setShapeOptions(ShapeToolOptions(kind = VectorShapeKind.RECTANGLE))
        val path = drafts.beginShapePath(DocPoint(10f, 10f), DocPoint(30f, 30f))!!
        val originalPoints = path.subpaths.single().anchors.map { it.point }
        var commits = 0
        val tool = VectorPathToolController(ToolId.PATH_SELECT, drafts) {
            commits += 1
            true
        }
        val context = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, point: DocPoint) = ToolPointerEvent(
            phase = phase,
            view = ViewPoint(point.x, point.y),
            doc = point,
            pressure = 1f,
            modifiers = Modifiers.None,
            downAt = ViewPoint(point.x, point.y),
        )

        assertTrue(tool.onPointer(event(PointerPhase.DOWN, DocPoint(20f, 20f)), context))
        assertTrue(tool.onPointer(event(PointerPhase.MOVE, DocPoint(25f, 27f)), context))
        assertTrue(tool.onPointer(event(PointerPhase.UP, DocPoint(25f, 27f)), context))

        val moved = drafts.state.value.paths.single()
        assertEquals(1, commits)
        assertEquals(originalPoints.map { it + DocPoint(5f, 7f) }, moved.subpaths.single().anchors.map { it.point })
        assertEquals(VectorSourceBox(15f, 17f, 20f, 20f), moved.primitive?.sourceBox)
        assertEquals(path.id, drafts.state.value.selectedPathId)
        assertNull(drafts.state.value.selectedAnchor)
    }

    @Test
    fun `pen ctrl temporarily direct selects and moves one anchor`() {
        val drafts = PathDraftController()
        drafts.beginPenPath(DocPoint(10f, 10f))
        drafts.appendPenAnchor(DocPoint(50f, 10f))
        val anchorId = drafts.state.value.paths.single().subpaths.single().anchors.last().id
        var commits = 0
        val tool = VectorPathToolController(ToolId.PEN, drafts) {
            commits += 1
            true
        }
        val context = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, point: DocPoint, modifiers: Modifiers = Modifiers.None) = ToolPointerEvent(
            phase = phase,
            view = ViewPoint(point.x, point.y),
            doc = point,
            pressure = 1f,
            modifiers = modifiers,
            downAt = ViewPoint(point.x, point.y),
        )

        assertTrue(tool.onPointer(event(PointerPhase.DOWN, DocPoint(50f, 10f), Modifiers(ctrl = true)), context))
        assertTrue(tool.onPointer(event(PointerPhase.MOVE, DocPoint(60f, 20f)), context))
        assertTrue(tool.onPointer(event(PointerPhase.UP, DocPoint(60f, 20f)), context))

        assertEquals(1, commits)
        assertEquals(DocPoint(60f, 20f), drafts.state.value.paths.single().subpaths.single().anchors.last().point)
        assertEquals(anchorId, drafts.state.value.selectedAnchor?.anchorId)
    }

    @Test
    fun `pen modifier changes after pointer down do not switch the active gesture`() {
        val drafts = PathDraftController()
        var commits = 0
        val tool = VectorPathToolController(ToolId.PEN, drafts) {
            commits += 1
            true
        }
        val context = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, point: DocPoint, modifiers: Modifiers = Modifiers.None) = ToolPointerEvent(
            phase = phase,
            view = ViewPoint(point.x, point.y),
            doc = point,
            pressure = 1f,
            modifiers = modifiers,
            downAt = ViewPoint(point.x, point.y),
        )

        assertTrue(tool.onPointer(event(PointerPhase.DOWN, DocPoint(10f, 10f)), context))
        assertTrue(tool.onPointer(event(PointerPhase.MOVE, DocPoint(20f, 20f), Modifiers(ctrl = true)), context))
        assertTrue(tool.onPointer(event(PointerPhase.UP, DocPoint(20f, 20f), Modifiers(ctrl = true)), context))

        val anchor = drafts.state.value.paths.single().subpaths.single().anchors.single()
        assertEquals(1, commits)
        assertEquals(DocPoint(10f, 10f), anchor.outgoing)
    }

    @Test
    fun `pen alt convert changes a smooth anchor to an independent corner handle`() {
        val drafts = PathDraftController()
        val initialHandle = DocPoint(5f, 0f)
        val path = drafts.beginPenPath(DocPoint(10f, 10f), initialHandle)
        val anchor = path.subpaths.single().anchors.single()
        var commits = 0
        val tool = VectorPathToolController(ToolId.PEN, drafts) {
            commits += 1
            true
        }
        val context = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, point: DocPoint, modifiers: Modifiers = Modifiers.None) = ToolPointerEvent(
            phase = phase,
            view = ViewPoint(point.x, point.y),
            doc = point,
            pressure = 1f,
            modifiers = modifiers,
            downAt = ViewPoint(point.x, point.y),
        )

        assertTrue(tool.onPointer(event(PointerPhase.DOWN, anchor.point, Modifiers(alt = true)), context))
        assertTrue(tool.onPointer(event(PointerPhase.MOVE, DocPoint(15f, 15f), Modifiers(alt = true)), context))
        assertTrue(tool.onPointer(event(PointerPhase.UP, DocPoint(15f, 15f)), context))

        val converted = drafts.state.value.paths.single().subpaths.single().anchors.single()
        assertEquals(1, commits)
        assertEquals(PathAnchorMode.CORNER, converted.mode)
        assertEquals(DocPoint(-initialHandle.x, 0f), converted.incoming)
        assertEquals(DocPoint(5f, 5f), converted.outgoing)
    }

    @Test
    fun `ellipse shape draft carries rotated cubic anchors and unique identifiers`() {
        val controller = PathDraftController()
        controller.setShapeOptions(ShapeToolOptions(kind = VectorShapeKind.ELLIPSE, rotationDegrees = 45f))

        val first = controller.beginShapePath(DocPoint(10f, 20f), DocPoint(50f, 40f))!!
        val second = controller.beginShapePath(DocPoint(70f, 80f), DocPoint(90f, 100f))!!
        val anchors = first.subpaths.single().anchors

        assertEquals(4, anchors.size)
        assertTrue(anchors.all { it.incoming != null && it.outgoing != null && it.mode == PathAnchorMode.SYMMETRIC })
        assertTrue(anchors.any { it.point.x !in 10f..50f || it.point.y !in 20f..40f })
        assertEquals(45f, first.primitive?.rotationDegrees)
        assertTrue(first.id.value > 0 && second.id.value > first.id.value)
        assertEquals(8, (first.subpaths + second.subpaths).flatMap { it.anchors }.map { it.id }.distinct().size)
    }

    @Test
    fun `polygon draft honors side count and snapshots primitive metadata`() {
        val controller = PathDraftController()
        controller.setShapeOptions(ShapeToolOptions(kind = VectorShapeKind.POLYGON, polygonSides = 6, rotationDegrees = 15f))

        val path = controller.beginShapePath(DocPoint(90f, 70f), DocPoint(10f, 20f))!!

        assertEquals(6, path.subpaths.single().anchors.size)
        assertTrue(path.subpaths.single().closed)
        assertEquals(VectorShapeKind.POLYGON, path.primitive?.kind)
        assertEquals(6, path.primitive?.polygonSides)
        assertEquals(VectorSourceBox(10f, 20f, 80f, 50f), path.primitive?.sourceBox)
        assertTrue(path.subpaths.single().anchors.all { it.mode == PathAnchorMode.CORNER })
    }

    @Test
    fun `star draft alternates outer and inner vertices`() {
        val controller = PathDraftController()
        controller.setShapeOptions(ShapeToolOptions(kind = VectorShapeKind.STAR, polygonSides = 5, starInnerRatio = 0.4f))

        val path = controller.beginShapePath(DocPoint(0f, 0f), DocPoint(100f, 80f))!!
        val center = DocPoint(50f, 40f)
        val radii = path.subpaths.single().anchors.map { anchor ->
            kotlin.math.hypot((anchor.point.x - center.x).toDouble() / 50.0, (anchor.point.y - center.y).toDouble() / 40.0)
        }

        assertEquals(10, radii.size)
        assertTrue(radii[0] > radii[1])
        assertTrue(kotlin.math.abs(radii[1] / radii[0] - 0.4) < 0.0001)
        assertEquals(VectorShapeKind.STAR, path.primitive?.kind)
        assertEquals(0.4f, path.primitive?.starInnerRatio)
    }

    @Test
    fun `shape creation rejects empty and out of range geometry while line keeps directed endpoints`() {
        val controller = PathDraftController()

        assertNull(controller.beginShapePath(DocPoint(3f, 4f), DocPoint(3f, 40f)))
        assertTrue(controller.state.value.message!!.contains("wider and taller"))
        assertNull(controller.beginShapePath(DocPoint(3f, 4f), DocPoint(40f, 4f)))
        assertTrue(controller.state.value.message!!.contains("wider and taller"))
        assertNull(controller.beginShapePath(DocPoint(Float.NaN, 0f), DocPoint(40f, 50f)))
        assertTrue(controller.state.value.message!!.contains("finite"))

        controller.setShapeOptions(ShapeToolOptions(kind = VectorShapeKind.LINE, rotationDegrees = 90f, arrowAtEnd = true))
        assertNull(controller.beginShapePath(DocPoint(4f, 7f), DocPoint(4f, 7f)))
        val line = controller.beginShapePath(DocPoint(40f, 0f), DocPoint(0f, 0f))!!
        val subpath = line.subpaths.single()
        assertFalse(subpath.closed)
        assertEquals(listOf(DocPoint(20f, 20f), DocPoint(20f, -20f)), subpath.anchors.map { it.point })
        assertEquals(2, subpath.anchors.map { it.id }.distinct().size)
        assertNull(line.primitive)
        assertTrue(line.style.arrowAtEnd)
    }

    @Test
    fun `pen click and drag build transient anchors and close after three points`() {
        val controller = PathDraftController()
        val path = controller.beginPenPath(DocPoint(10f, 20f))

        assertEquals("Path 1", path.name)
        assertEquals(DocPoint(10f, 20f), path.subpaths.single().anchors.single().point)
        assertFalse(controller.closeActivePath())
        assertEquals("Add at least three anchors to close a path", controller.state.value.message)

        assertTrue(controller.appendPenAnchor(DocPoint(40f, 20f), DocPoint(0f, 12f)))
        assertTrue(controller.appendPenAnchor(DocPoint(30f, 50f)))
        assertTrue(controller.closeActivePath())
        assertTrue(controller.state.value.paths.single().subpaths.single().closed)
        assertEquals(PathAnchorMode.SMOOTH, controller.state.value.paths.single().subpaths.single().anchors[1].mode)
    }

    @Test
    fun `symmetric handles mirror drag and moving anchor preserves relative handles`() {
        val controller = PathDraftController()
        val path = controller.beginPenPath(DocPoint(10f, 10f))
        controller.appendPenAnchor(DocPoint(30f, 30f), DocPoint(5f, 0f))
        val selected = controller.state.value.selectedAnchor!!

        assertTrue(controller.setAnchorMode(selected, PathAnchorMode.SYMMETRIC))
        assertTrue(controller.moveHandle(selected, incoming = true, endpoint = DocPoint(30f, 27f)))
        var anchor = controller.state.value.paths.single().subpaths.single().anchors.last()
        assertEquals(DocPoint(0f, -3f), anchor.incoming)
        assertEquals(DocPoint(0f, 3f), anchor.outgoing)

        assertTrue(controller.moveAnchor(selected, DocPoint(50f, 60f)))
        anchor = controller.state.value.paths.single().subpaths.single().anchors.last()
        assertEquals(DocPoint(50f, 60f), anchor.point)
        assertEquals(DocPoint(0f, -3f), anchor.incoming)
        assertEquals(DocPoint(0f, 3f), anchor.outgoing)
    }

    @Test
    fun `corner mode leaves opposite handle independent and delete updates closed state`() {
        val controller = PathDraftController()
        val path = controller.beginPenPath(DocPoint(0f, 0f))
        controller.appendPenAnchor(DocPoint(10f, 0f), DocPoint(3f, 0f))
        controller.appendPenAnchor(DocPoint(10f, 10f), DocPoint(3f, 0f))
        assertTrue(controller.closeActivePath())
        val selection = controller.state.value.selectedAnchor!!
        assertTrue(controller.setAnchorMode(selection, PathAnchorMode.CORNER))
        assertTrue(controller.moveHandle(selection, incoming = true, endpoint = DocPoint(8f, 10f)))

        val anchor = controller.state.value.paths.single().subpaths.single().anchors.last()
        assertEquals(DocPoint(-2f, 0f), anchor.incoming)
        assertEquals(DocPoint(3f, 0f), anchor.outgoing)
        assertTrue(controller.deleteSelectedAnchor())
        assertFalse(controller.state.value.paths.single().subpaths.single().closed)
        assertEquals(2, controller.state.value.paths.single().subpaths.single().anchors.size)
        assertEquals(path.id, controller.state.value.selectedPathId)
    }

    @Test
    fun `path selection rename removal and shape options stay editor local`() {
        val controller = PathDraftController()
        val first = controller.beginPenPath(DocPoint(1f, 1f))
        val second = controller.beginPenPath(DocPoint(2f, 2f))
        assertEquals("Path 2", second.name)
        controller.selectPath(first.id)
        assertTrue(controller.renamePath(first.id, "Logo edge"))
        controller.setShapeOptions(ShapeToolOptions(kind = VectorShapeKind.STAR, polygonSides = 2))

        assertEquals("Logo edge", controller.state.value.paths.first().name)
        assertEquals(3, controller.state.value.shapeOptions.polygonSides)
        assertEquals(second.id, controller.state.value.paths.last().id)
        assertTrue(controller.deleteSelectedPath())
        assertEquals(listOf(second.id), controller.state.value.paths.map { it.id })
        assertNull(controller.state.value.selectedAnchor)
    }

    @Test
    fun `pen draft snapshots current style without attaching to document state`() {
        val controller = PathDraftController()
        controller.setShapeOptions(
            ShapeToolOptions(
                fillEnabled = false,
                strokeColor = RgbaColor(0.2f, 0.4f, 0.6f),
                strokeWidth = 6f,
            ),
        )

        val path = controller.beginPenPath(DocPoint(1f, 2f))

        assertEquals(null, path.style.fillColor)
        assertEquals(RgbaColor(0.2f, 0.4f, 0.6f), path.style.strokeColor)
        assertEquals(6f, path.style.strokeWidth)
        assertNull(path.primitive)
        assertNull(controller.state.value.message)
    }

    @Test
    fun `editing primitive anchor converts draft to ordinary path geometry`() {
        val anchor = PathAnchor(PathAnchorId(1), DocPoint(20f, 30f))
        val path = VectorPathDraft(
            id = VectorPathId(1),
            name = "Ellipse",
            subpaths = listOf(VectorSubpath(listOf(anchor), closed = true)),
            primitive = VectorPrimitiveMetadata(
                kind = VectorShapeKind.ELLIPSE,
                sourceBox = VectorSourceBox(10f, 20f, 40f, 20f),
            ),
        )
        val controller = PathDraftController(
            PathDraftState(paths = listOf(path), selectedPathId = path.id),
        )
        val selection = PathAnchorSelection(path.id, 0, anchor.id)

        assertTrue(controller.moveAnchor(selection, DocPoint(22f, 31f)))

        assertEquals(DocPoint(22f, 31f), controller.state.value.paths.single().subpaths.single().anchors.single().point)
        assertNull(controller.state.value.paths.single().primitive)
    }
}
