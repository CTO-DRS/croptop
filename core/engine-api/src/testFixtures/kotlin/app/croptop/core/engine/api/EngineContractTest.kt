package app.croptop.core.engine.api

import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.Anchor
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.DocumentInfo
import app.croptop.core.model.FillSource
import app.croptop.core.model.GradientShape
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import app.croptop.core.model.HistoryState
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerTree
import app.croptop.core.model.Matrix3
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ParamValue
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionModify
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Shared behavioral tests. Implementations provide a fresh engine using [createEngine]. */
abstract class EngineContractTest {
    protected abstract fun createEngine(): Engine

    private lateinit var engine: Engine
    private lateinit var document: DocumentHandle

    @Before
    fun createDocument() {
        engine = createEngine()
        document = engine.newDocument(NewDocumentSpec(name = "Contract", size = DocSize(64, 48)))
    }

    @After
    fun closeResources() {
        document.close()
        engine.close()
    }

    @Test
    fun viewportOverlayContractPreservesGuideAxes() {
        val guides = listOf(
            Guide(GuideAxis.HORIZONTAL, 12.5f),
            Guide(GuideAxis.VERTICAL, 30f),
        )
        val overlays = OverlayState(guides = guides)

        assertEquals(guides, overlays.guides)
        assertEquals(GuideAxis.HORIZONTAL, overlays.guides[0].axis)
        assertEquals(GuideAxis.VERTICAL, overlays.guides[1].axis)
    }

    @Test
    fun createsInitialDocumentState() {
        assertEquals("Contract", document.info.value.name)
        assertEquals(DocSize(64, 48), document.info.value.size)
        assertFalse(document.info.value.dirty)
        assertTrue(document.layers.value.layers.isNotEmpty())
        assertNotNull(document.layers.value.primary)
        assertFalse(document.history.value.canUndo)
        assertFalse(document.history.value.canRedo)
    }

    @Test
    fun memoryBudgetCanBeChangedAndRejectsInvalidValues() {
        val requested = 128L * 1024 * 1024
        engine.setMemoryBudget(requested)
        assertEquals(requested, engine.memoryBudgetBytes)
        try {
            engine.setMemoryBudget(0L)
            throw AssertionError("Expected a non-positive budget to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(requested, engine.memoryBudgetBytes)
        }
    }

    @Test
    fun layerCommandsPublishStateAndCanReorderAndDelete() = runBlocking {
        val firstId = LayerId(100)
        val secondId = LayerId(101)

        val firstUpdate = nextLayers { it.find(firstId) != null }
        assertAccepted(document.execute(EditCommand.AddLayer(firstId, "First", LayerKind.PIXEL)))
        assertEquals(firstId, firstUpdate.await().layers.first().id)

        val secondUpdate = nextLayers { it.find(secondId) != null }
        assertAccepted(document.execute(EditCommand.AddLayer(secondId, "Second", LayerKind.PIXEL)))
        assertEquals(listOf(secondId, firstId), secondUpdate.await().layers.take(2).map { it.id })

        val reordered = nextLayers { it.layers.firstOrNull()?.id == firstId }
        assertAccepted(document.execute(EditCommand.MoveLayer(firstId, index = 0)))
        assertEquals(listOf(firstId, secondId), reordered.await().layers.take(2).map { it.id })

        val deleted = nextLayers { it.find(secondId) == null }
        assertAccepted(document.execute(EditCommand.DeleteLayer(secondId)))
        assertFalse(deleted.await().layers.any { it.id == secondId })
    }

    @Test
    fun undoAndRedoRestoreLayerState() = runBlocking {
        val layerId = LayerId(200)
        val added = nextLayers { it.find(layerId) != null }
        assertAccepted(document.execute(EditCommand.AddLayer(layerId, "Undo target", LayerKind.PIXEL)))
        added.await()

        val undone = nextLayers { it.find(layerId) == null }
        assertAccepted(document.undo())
        undone.await()
        assertTrue(document.history.value.canRedo)

        val redone = nextLayers { it.find(layerId) != null }
        assertAccepted(document.redo())
        redone.await()
        assertTrue(document.history.value.canUndo)
    }

    @Test
    fun closingEngineClosesOpenDocuments() {
        engine.close()

        val result = document.execute(EditCommand.AddLayer(LayerId(300), "After close", LayerKind.PIXEL))
        assertTrue("Expected CLOSED rejection, got $result", result is CommandResult.Rejected)
        assertEquals(RejectionCode.CLOSED, (result as CommandResult.Rejected).code)
    }

    @Test
    fun closingDocumentIsIdempotentAndRejectsCommands() {
        document.close()
        document.close()

        val result = document.execute(EditCommand.AddLayer(LayerId(301), "After close", LayerKind.PIXEL))
        assertTrue("Expected CLOSED rejection, got $result", result is CommandResult.Rejected)
        assertEquals(RejectionCode.CLOSED, (result as CommandResult.Rejected).code)
    }

    // ---- Named snapshots ----

    @Test
    fun namedSnapshotsCreateRenameJumpAndDelete() = runBlocking<Unit> {
        val layerId = LayerId(400)
        supported(document.createSnapshot("Before"))
        assertEquals(listOf("Before"), awaitHistory { it.snapshots.size == 1 }.snapshots.map { it.name })
        assertRejected(document.createSnapshot("before"), RejectionCode.INVALID_ARGUMENT) // names are case-insensitive unique
        assertRejected(document.createSnapshot("   "), RejectionCode.INVALID_ARGUMENT)
        assertAccepted(document.execute(EditCommand.AddLayer(layerId, "After", LayerKind.PIXEL)))
        awaitLayers { it.find(layerId) != null }
        assertAccepted(document.renameSnapshot(0, "Clean"))
        assertEquals("Clean", awaitHistory { it.snapshots.firstOrNull()?.name == "Clean" }.snapshots.single().name)
        assertAccepted(document.jumpToSnapshot(0))
        awaitLayers { it.find(layerId) == null }
        assertTrue("restoring a snapshot is undoable", document.history.value.canUndo)
        assertAccepted(document.deleteSnapshot(0))
        awaitHistory { it.snapshots.isEmpty() }
        assertRejected(document.jumpToSnapshot(0), RejectionCode.INVALID_ARGUMENT)
    }

    // ---- Layer properties and dirty state ----

    @Test
    fun layerPropertiesApplyAndMarkTheDocumentDirty() = runBlocking<Unit> {
        val id = document.layers.value.layers.first().id
        assertAccepted(document.execute(EditCommand.SetLayerProps(id, LayerProperties(visible = false, opacity = 0.5f))))
        val node = awaitLayers { it.find(id)?.visible == false }.find(id)!!
        assertEquals(0.5f, node.opacity, 1e-4f)
        assertTrue(awaitInfo { it.dirty }.dirty)
        assertRejected(
            document.execute(EditCommand.SetLayerProps(LayerId(Long.MAX_VALUE - 7), LayerProperties(visible = true))),
            RejectionCode.NOT_FOUND,
        )
    }

    // ---- engine-api v3 (skipped per call while an engine returns UNSUPPORTED) ----

    @Test
    fun setActiveLayersIsViewStateOnly() = runBlocking<Unit> {
        val a = LayerId(500)
        assertAccepted(document.execute(EditCommand.AddLayer(a, "A", LayerKind.PIXEL)))
        awaitLayers { it.find(a) != null }
        val steps = document.history.value.entries.size
        val base = document.layers.value.layers.last().id
        supported(document.setActiveLayers(setOf(a, base), primary = base))
        val tree = awaitLayers { it.active == setOf(a, base) }
        assertEquals(base, tree.primary)
        assertEquals("no history step", steps, document.history.value.entries.size)
        assertRejected(document.setActiveLayers(setOf(LayerId(Long.MAX_VALUE - 1)), null), RejectionCode.NOT_FOUND)
        assertRejected(document.setActiveLayers(setOf(a), primary = base), RejectionCode.INVALID_ARGUMENT)
    }

    @Test
    fun translateLayersIsOneStepAndValidates() = runBlocking<Unit> {
        val a = LayerId(510)
        assertAccepted(document.execute(EditCommand.AddLayer(a, "Movable", LayerKind.PIXEL)))
        awaitLayers { it.find(a) != null }
        val steps = document.history.value.entries.size
        supported(document.translateLayers(setOf(a), 12f, -4f))
        awaitHistory { it.entries.size == steps + 1 }
        assertAccepted(document.translateLayers(setOf(a), 0f, 0f))
        assertEquals("a zero move adds no step", steps + 1, document.history.value.entries.size)
        assertRejected(document.translateLayers(setOf(a), Float.NaN, 0f), RejectionCode.INVALID_ARGUMENT)
        assertRejected(document.translateLayers(emptySet(), 1f, 1f), RejectionCode.INVALID_ARGUMENT)
        assertRejected(document.translateLayers(setOf(LayerId(Long.MAX_VALUE - 2)), 1f, 1f), RejectionCode.NOT_FOUND)
    }

    @Test
    fun translateGroupMovesItsNestedPixelLayersAsOneStep() = runBlocking<Unit> {
        val group = LayerId(512)
        val nested = LayerId(513)
        assertAccepted(document.execute(EditCommand.AddLayer(group, "Group", LayerKind.GROUP)))
        assertAccepted(document.execute(EditCommand.AddLayer(nested, "Nested", LayerKind.PIXEL, parentId = group)))
        awaitLayers { it.find(nested) != null }
        val steps = document.history.value.entries.size
        supported(document.translateLayers(setOf(group), 0.5f, -1.25f))
        awaitHistory { it.entries.size == steps + 1 }
        assertEquals("nested pixel layer stays under its group", group, awaitLayers { it.find(nested) != null }.parentOf(nested)?.id)
    }

    @Test
    fun selectionLifecycle() = runBlocking<Unit> {
        val size = document.info.value.size
        supported(document.selectAll())
        assertEquals(DocRect.of(size), (awaitSelection { it is SelectionInfo.Active } as SelectionInfo.Active).bounds)
        assertAccepted(document.deselect())
        awaitSelection { it is SelectionInfo.None }
        assertRejected(document.deselect(), RejectionCode.INVALID_ARGUMENT)
        assertAccepted(document.reselect())
        awaitSelection { it is SelectionInfo.Active }
        assertAccepted(document.select(SelectionShape.Rect(DocRectF(4f, 4f, 20f, 16f))))
        assertEquals(
            DocRect(4, 4, 20, 16),
            (
                awaitSelection {
                    (it as? SelectionInfo.Active)?.bounds == DocRect(4, 4, 20, 16)
                } as SelectionInfo.Active
                ).bounds,
        )
        assertAccepted(document.undo())
        awaitSelection { (it as? SelectionInfo.Active)?.bounds == DocRect.of(size) }
        assertRejected(document.modifySelection(SelectionModify.EXPAND, -3f), RejectionCode.INVALID_ARGUMENT)
        assertRejected(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 4f, 4f)), feather = -1f), RejectionCode.INVALID_ARGUMENT)
    }

    @Test
    fun modifySelectionSessionPreviewsLiveAndCommitsOneStep() = runBlocking<Unit> {
        assertAccepted(document.select(SelectionShape.Rect(DocRectF(10f, 10f, 30f, 30f))))
        val initialSelection = awaitSelection { it is SelectionInfo.Active } as SelectionInfo.Active
        val before = document.history.value.entries.size

        val session = document.beginModifySelection(SelectionModify.EXPAND)
        assumeFalse("Selection modify session not implemented yet", session is UnsupportedSelectionModifySession)

        assertAccepted(session.update(4f))
        val previewSelection = awaitSelection {
            (it as? SelectionInfo.Active)?.bounds != initialSelection.bounds
        } as SelectionInfo.Active
        assertEquals("Preview must not add history entries", before, document.history.value.entries.size)
        assertTrue(previewSelection.bounds.width > initialSelection.bounds.width)

        assertAccepted(session.commit())
        awaitHistory { it.entries.size == before + 1 && it.entries.last().label == "Expand" }

        // A cancelled session restores previous selection without adding history
        val committedSelection = document.selection.value
        val historyAfterCommit = document.history.value.entries.size
        val cancelSession = document.beginModifySelection(SelectionModify.CONTRACT)
        assertAccepted(cancelSession.update(2f))
        awaitSelection { it != committedSelection }
        assertEquals("Preview must not add history entries", historyAfterCommit, document.history.value.entries.size)

        cancelSession.cancel()
        assertEquals(committedSelection, awaitSelection { it == committedSelection })
        assertEquals(historyAfterCommit, document.history.value.entries.size)
    }

    @Test
    fun quickSelectionPreviewsLiveAndCommitsOneStep() = runBlocking<Unit> {
        val stroke = document.beginQuickSelection(QuickSelectionSpec(diameter = 8f))
        assumeFalse("Quick Selection not implemented by this engine yet", stroke is UnsupportedQuickSelectionSession)
        val before = document.history.value.entries.size
        stroke.add(floatArrayOf(10f, 10f, 20f, 12f))
        awaitSelection { it is SelectionInfo.Active }
        assertEquals("Preview must not add history", before, document.history.value.entries.size)
        assertAccepted(stroke.end())
        awaitHistory { it.entries.size == before + 1 && it.entries.last().label == "Quick Selection" }

        // A cancelled stroke restores the committed selection and adds nothing.
        val committed = document.selection.value
        val cancelled = document.beginQuickSelection(QuickSelectionSpec(diameter = 8f, op = SelectionOp.ADD))
        cancelled.add(floatArrayOf(40f, 40f))
        cancelled.cancel()
        assertEquals(committed, awaitSelection { it == committed })
        assertEquals(before + 1, document.history.value.entries.size)

        // A stroke entirely off the canvas selects nothing.
        val offCanvas = document.beginQuickSelection(QuickSelectionSpec(diameter = 4f))
        offCanvas.add(floatArrayOf(-500f, -500f))
        assertRejected(offCanvas.end(), RejectionCode.INVALID_ARGUMENT)
        assertEquals(committed, awaitSelection { it == committed })
    }

    @Test
    fun retouchStrokesCommitOneLabeledStepAndValidateTheSource() = runBlocking<Unit> {
        val layer = LayerId(700)
        assertAccepted(document.execute(EditCommand.AddLayer(layer, "Retouch", LayerKind.PIXEL)))
        awaitLayers { it.find(layer) != null }
        val tip = StrokeSpec(layer, app.croptop.core.model.RgbaColor.Black, diameter = 12f)
        val stroke = floatArrayOf(10f, 10f, 1f, 0f, 0f, 0f, 0f, 20f, 12f, 1f, 0f, 0f, 0f, 0.01f)
        val dodge = document.beginRetouch(tip, RetouchEffect.Tone(burn = false))
        assumeFalse("Retouch strokes not implemented by this engine yet", dodge is UnsupportedStrokeSession)
        val before = document.history.value.entries.size
        dodge.add(stroke)
        assertAccepted(dodge.end())
        awaitHistory { it.entries.size == before + 1 && it.entries.last().label == "Dodge" }

        val offCanvas = document.beginRetouch(tip, RetouchEffect.Clone(DocPoint(-100f, -100f)))
        offCanvas.add(stroke)
        assertRejected(offCanvas.end(), RejectionCode.INVALID_ARGUMENT)
        assertEquals(before + 1, document.history.value.entries.size)
        assertThrows(IllegalArgumentException::class.java) { RetouchEffect.Tone(burn = true, exposure = 2f) }
    }

    @Test
    fun copyCutAndPasteGoThroughTheSharedClipboard() = runBlocking<Unit> {
        val layer = LayerId(710)
        assertAccepted(document.execute(EditCommand.AddLayer(layer, "Source", LayerKind.PIXEL)))
        awaitLayers { it.primary == layer }
        supported(document.select(SelectionShape.Rect(DocRectF(4f, 6f, 24f, 16f))))
        val before = document.history.value.entries.size
        supported(document.copy())
        assertEquals("Copy is not a history step", before, document.history.value.entries.size)
        val clip = engine.clipboard.value
        assertNotNull("Copy fills the engine clipboard", clip)
        clip!!
        assertEquals(DocSize(20, 10), clip.size)
        assertEquals(DocRect(4, 6, 24, 16), clip.origin)

        val inPlace = LayerId(711)
        supported(document.paste(inPlace))
        val tree = awaitLayers { it.find(inPlace) != null }
        assertEquals(inPlace, tree.primary)
        assertEquals(DocRect(4, 6, 24, 16), tree.find(inPlace)!!.bounds)
        awaitHistory { it.entries.last().label == "Paste" }

        val centered = LayerId(712)
        supported(document.paste(centered, DocPoint(32f, 24f)))
        assertEquals(DocRect(22, 19, 42, 29), awaitLayers { it.find(centered) != null }.find(centered)!!.bounds)
        assertRejected(document.paste(centered), RejectionCode.INVALID_ARGUMENT)

        supported(document.setActiveLayers(setOf(layer), layer))
        awaitLayers { it.primary == layer }
        supported(document.cut())
        awaitHistory { it.entries.last().label == "Cut" }
        assertAccepted(document.deselect())
        assertRejected(document.cut(), RejectionCode.INVALID_ARGUMENT)
    }

    @Test
    fun colorRangePreviewsThenCommitsOrCancels() = runBlocking<Unit> {
        val target = checkNotNull(document.layers.value.primary)
        val stroke = document.beginStroke(
            StrokeSpec(
                target = target,
                color = app.croptop.core.model.RgbaColor(1f, 0f, 0f),
                diameter = 8f,
            ),
        )
        stroke.add(floatArrayOf(8f, 8f, 1f, 0f, 0f, 0f, 0f))
        assertAccepted(stroke.end())
        val range = document.beginColorRange()
        assumeFalse("Color Range not implemented by this engine yet", range is UnsupportedColorRangeSession)
        val before = document.history.value.entries.size
        val sampled = document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color
        range.update(ColorRangeSpec(samples = listOf(sampled), fuzziness = 10))
        awaitSelection { it is SelectionInfo.Active }
        assertEquals("Preview must not add history", before, document.history.value.entries.size)
        assertAccepted(range.commit())
        awaitHistory { it.entries.size == before + 1 && it.entries.last().label == "Color Range" }

        val committed = document.selection.value
        val cancelled = document.beginColorRange(SelectionOp.SUBTRACT)
        cancelled.update(ColorRangeSpec(target = ColorRangeTarget.SHADOWS))
        cancelled.cancel()
        assertEquals(committed, awaitSelection { it == committed })
        assertEquals(before + 1, document.history.value.entries.size)
        assertThrows(IllegalArgumentException::class.java) { ColorRangeSpec(fuzziness = 201) }
    }

    @Test
    fun layerMasksAndClippingAreUndoableSteps() = runBlocking<Unit> {
        val base = LayerId(720)
        val top = LayerId(721)
        assertAccepted(document.execute(EditCommand.AddLayer(base, "Base", LayerKind.PIXEL)))
        assertAccepted(document.execute(EditCommand.AddLayer(top, "Top", LayerKind.PIXEL)))
        awaitLayers { it.find(top) != null }

        supported(document.addLayerMask(top))
        assertEquals(app.croptop.core.model.LayerMaskInfo(), awaitLayers { it.find(top)?.mask != null }.find(top)!!.mask)
        assertRejected(document.addLayerMask(top), RejectionCode.INVALID_ARGUMENT)
        assertRejected(document.addLayerMask(base, MaskFill.REVEAL_SELECTION), RejectionCode.INVALID_ARGUMENT)

        // A density drag previews without history; the release adds one step.
        val steps = document.history.value.entries.size
        assertAccepted(document.setLayerMask(top, app.croptop.core.model.LayerMaskInfo(density = 0.4f), preview = true))
        assertEquals(steps, document.history.value.entries.size)
        assertAccepted(document.setLayerMask(top, app.croptop.core.model.LayerMaskInfo(density = 0.5f)))
        awaitHistory { it.entries.size == steps + 1 }
        assertAccepted(document.setLayerMask(top, app.croptop.core.model.LayerMaskInfo(enabled = false, density = 0.5f)))
        awaitHistory { it.entries.last().label == "Disable Layer Mask" }
        assertRejected(document.setLayerMask(top, app.croptop.core.model.LayerMaskInfo(density = 2f)), RejectionCode.INVALID_ARGUMENT)
        assertAccepted(document.setLayerMask(top, app.croptop.core.model.LayerMaskInfo(enabled = false, density = 1f)))

        assertAccepted(document.maskToSelection(top))
        awaitSelection { it is SelectionInfo.Active }
        // Invert must change the mask: an inverted Reveal All mask hides everything, so nothing loads.
        assertAccepted(document.invertLayerMask(top))
        awaitHistory { it.entries.last().label == "Invert" }
        assertAccepted(document.maskToSelection(top))
        awaitSelection { it is SelectionInfo.None }
        assertAccepted(document.undo())
        assertAccepted(document.undo())
        assertAccepted(document.maskToSelection(top))
        awaitSelection { it is SelectionInfo.Active }
        assertAccepted(document.removeLayerMask(top, apply = true))
        awaitLayers { it.find(top)?.mask == null }
        awaitHistory { it.entries.last().label == "Apply Layer Mask" }
        assertRejected(document.removeLayerMask(top, apply = false), RejectionCode.INVALID_ARGUMENT)

        supported(document.setClipped(top, true))
        awaitLayers { it.find(top)?.clipped == true }
        awaitHistory { it.entries.last().label == "Create Clipping Mask" }
        val bottom = document.layers.value.layers.last().id
        assertRejected(document.setClipped(bottom, true), RejectionCode.INVALID_ARGUMENT)
        assertAccepted(document.undo())
        awaitLayers { it.find(top)?.clipped == false }
    }

    @Test
    fun quickSelectionSpecRejectsBadBrushes() {
        assertThrows(IllegalArgumentException::class.java) { QuickSelectionSpec(diameter = 0f) }
        assertThrows(IllegalArgumentException::class.java) { QuickSelectionSpec(diameter = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { QuickSelectionSpec(diameter = 10f, op = SelectionOp.INTERSECT) }
    }

    @Test
    fun objectSelectionAndSubjectAreOneStepInsideTheCanvas() = runBlocking<Unit> {
        val size = document.info.value.size
        val target = checkNotNull(document.layers.value.primary)
        for ((x, y, color) in listOf(
            Triple(14f, 14f, app.croptop.core.model.RgbaColor(1f, 0f, 0f)),
            Triple(32f, 24f, app.croptop.core.model.RgbaColor(0f, 0f, 1f)),
        )) {
            val stroke = document.beginStroke(StrokeSpec(target = target, color = color, diameter = 8f))
            stroke.add(floatArrayOf(x, y, 1f, 0f, 0f, 0f, 0f))
            assertAccepted(stroke.end())
        }
        val region = SelectionShape.Rect(DocRectF(4f, 4f, 24f, 24f))
        val before = document.history.value.entries.size
        supported(document.selectObject(region).await())
        awaitHistory { it.entries.size == before + 1 && it.entries.last().label == "Object Selection" }
        val obj = (awaitSelection { it is SelectionInfo.Active } as SelectionInfo.Active).bounds
        assertTrue("Object must lie inside the region, was $obj", obj.left >= 4 && obj.top >= 4 && obj.right <= 24 && obj.bottom <= 24)
        assertRejected(document.selectObject(SelectionShape.Rect(DocRectF(-50f, -50f, -10f, -10f))).await(), RejectionCode.INVALID_ARGUMENT)

        supported(document.selectSubject().await())
        awaitHistory { it.entries.size == before + 2 && it.entries.last().label == "Select Subject" }
        val subject = (awaitSelection { it is SelectionInfo.Active && it.bounds != obj } as SelectionInfo.Active).bounds
        assertTrue("Subject must lie inside the canvas, was $subject", DocRect.of(size).let { subject.intersect(it) == subject })
    }

    @Test
    fun wandBucketAndGradientHaveUndoableCommits() = runBlocking<Unit> {
        val point = DocPoint(8f, 8f)
        val beforeWand = document.history.value.entries.size
        supported(document.magicWand(point, 32, contiguous = true, sampleAll = true, op = SelectionOp.REPLACE))
        awaitHistory { it.entries.size == beforeWand + 1 }
        assertTrue(document.selection.value is SelectionInfo.Active)
        assertRejected(document.magicWand(point, -1, true, false, SelectionOp.REPLACE), RejectionCode.INVALID_ARGUMENT)
        assertAccepted(document.undo())
        awaitSelection { it is SelectionInfo.None }

        val beforeBucket = document.history.value.current
        supported(document.paintBucket(point, app.croptop.core.model.RgbaColor.Black, 32, true, false, 1f))
        awaitHistory { it.current == beforeBucket + 1 && it.entries.last().label == "Paint Bucket" }
        assertRejected(document.paintBucket(point, app.croptop.core.model.RgbaColor.Black, 256, true, false, 1f), RejectionCode.INVALID_ARGUMENT)
        assertAccepted(document.undo())

        val beforeGradient = document.history.value.current
        supported(document.drawGradient(point, DocPoint(24f, 8f), ColorGradient.BlackToWhite, GradientShape.LINEAR, false, true, 1f))
        awaitHistory { it.current == beforeGradient + 1 && it.entries.last().label == "Gradient" }
        assertRejected(
            document.drawGradient(point, point, ColorGradient.BlackToWhite, GradientShape.LINEAR, false, true, 1f),
            RejectionCode.INVALID_ARGUMENT,
        )
        assertAccepted(document.undo())
    }

    @Test
    fun canvasSizeCropRotateAreUndoable() = runBlocking<Unit> {
        val original = document.info.value.size
        supported(document.resizeCanvas(DocSize(original.width + 10, original.height), Anchor.LEFT))
        awaitInfo { it.size == DocSize(original.width + 10, original.height) }
        assertAccepted(document.undo())
        awaitInfo { it.size == original }
        assertAccepted(document.rotateCanvas(90f))
        awaitInfo { it.size == DocSize(original.height, original.width) }
        assertAccepted(document.undo())
        assertAccepted(document.crop(DocRectF(2f, 3f, 22f, 13f)))
        awaitInfo { it.size == DocSize(20, 10) }
        assertRejected(document.crop(DocRectF(5f, 5f, 5f, 9f)), RejectionCode.INVALID_ARGUMENT)
    }

    @Test
    fun mergeDownAndStampVisible() = runBlocking<Unit> {
        val top = LayerId(520)
        assertAccepted(document.execute(EditCommand.AddLayer(top, "Top", LayerKind.PIXEL)))
        val before = awaitLayers { it.find(top) != null }.size
        supported(document.mergeDown(top))
        awaitLayers { it.find(top) == null && it.size == before - 1 }
        val bottom = document.layers.value.layers.last().id
        assertRejected(document.mergeDown(bottom), RejectionCode.INVALID_ARGUMENT)
        val stamp = LayerId(521)
        supported(document.stampVisible(stamp))
        awaitLayers { it.find(stamp) != null }
    }

    @Test
    fun transformSessionCommitsOnceAndCancelAddsNothing() = runBlocking<Unit> {
        val a = LayerId(530)
        assertAccepted(document.execute(EditCommand.AddLayer(a, "T", LayerKind.PIXEL)))
        awaitLayers { it.find(a) != null }
        val steps = document.history.value.entries.size
        val cancelled = document.beginTransform(TransformTarget.Layers(setOf(a)))
        cancelled.update(Matrix3.translate(5f, 5f))
        cancelled.cancel()
        assertEquals(steps, document.history.value.entries.size)
        val session = document.beginTransform(TransformTarget.Layers(setOf(a)))
        session.update(Matrix3.translate(3f, 0f))
        session.update(Matrix3.scale(2f, 2f))
        supported(session.commit())
        awaitHistory { it.entries.size == steps + 1 }
    }

    @Test
    fun activeLayersKeepANullPrimaryAndCanBeCleared() = runBlocking<Unit> {
        val base = document.layers.value.layers.last().id
        supported(document.setActiveLayers(setOf(base), primary = null))
        val tree = awaitLayers { it.active == setOf(base) }
        assertEquals(null, tree.primary)
        assertAccepted(document.setActiveLayers(emptySet(), primary = null))
        awaitLayers { it.active.isEmpty() && it.primary == null }
    }

    @Test
    fun layerAtHitsTopMostVisibleUnlockedPixelLayerIncludingGroups() = runBlocking<Unit> {
        val size = document.info.value.size
        val center = DocPoint(size.width / 2f, size.height / 2f)
        val group = LayerId(540)
        val child = LayerId(541)
        assertAccepted(document.execute(EditCommand.AddLayer(group, "Group", LayerKind.GROUP)))
        assertAccepted(document.execute(EditCommand.AddLayer(child, "Inside", LayerKind.PIXEL, parentId = group, index = 0)))
        awaitLayers { it.find(child) != null }
        val hit = document.layerAt(center)
        assumeTrue("layerAt not implemented by this engine yet", hit != null)
        // New layers have unknown bounds (= the full canvas), and layers inside groups are hit-tested.
        assertEquals(child, hit)
        assertEquals(null, document.layerAt(DocPoint(Float.NaN, 1f)))
        assertEquals(null, document.layerAt(DocPoint(-1f, 1f)))
        assertEquals(null, document.layerAt(DocPoint(size.width.toFloat(), 1f)))
        assertAccepted(document.execute(EditCommand.SetLayerProps(group, LayerProperties(visible = false))))
        awaitLayers { it.find(group)?.visible == false }
        assertTrue("hidden groups hide their children", document.layerAt(center) != child)
    }

    @Test
    fun v3CallsRejectAfterClose() {
        val base = document.layers.value.layers.last().id
        document.close()
        val calls = listOf(
            document.setActiveLayers(setOf(base), base),
            document.translateLayers(setOf(base), 1f, 1f),
            document.select(SelectionShape.Rect(DocRectF(0f, 0f, 4f, 4f))),
            document.selectAll(),
            document.deselect(),
            document.reselect(),
            document.invertSelection(),
            document.modifySelection(SelectionModify.EXPAND, 2f),
            document.crop(DocRectF(0f, 0f, 4f, 4f)),
            document.resizeCanvas(DocSize(8, 8)),
            document.rotateCanvas(90f),
            document.flipCanvas(true),
            document.mergeDown(base),
            document.stampVisible(LayerId(599)),
        )
        calls.forEach { r ->
            assertTrue(
                "Expected CLOSED or UNSUPPORTED after close, got $r",
                r is CommandResult.Rejected && (r.code == RejectionCode.CLOSED || r.code == RejectionCode.UNSUPPORTED),
            )
        }
        assertEquals(null, document.layerAt(DocPoint(1f, 1f)))
    }

    @Test
    fun enormousGeometryIsRejectedNotOverflowed() = runBlocking<Unit> {
        val huge = 1e12f
        val crop = document.crop(DocRectF(0f, 0f, huge, huge))
        assumeFalse("crop not implemented yet", crop is CommandResult.Rejected && crop.code == RejectionCode.UNSUPPORTED)
        assertRejected(crop, RejectionCode.INVALID_ARGUMENT)
        val sel = document.select(SelectionShape.Rect(DocRectF(-huge, -huge, huge, huge)))
        if (!(sel is CommandResult.Rejected && sel.code == RejectionCode.UNSUPPORTED)) assertRejected(sel, RejectionCode.INVALID_ARGUMENT)
        val base = document.layers.value.layers.first().id
        val move = document.translateLayers(setOf(base), huge, 0f)
        if (!(move is CommandResult.Rejected && move.code == RejectionCode.UNSUPPORTED)) assertRejected(move, RejectionCode.INVALID_ARGUMENT)
        assertEquals(DocSize(64, 48), document.info.value.size)
    }

    @Test
    fun fillClearAndLayerViaCopyCut() = runBlocking<Unit> {
        val a = LayerId(600)
        assertAccepted(document.execute(EditCommand.AddLayer(a, "Paint", LayerKind.PIXEL)))
        awaitLayers { it.primary == a }
        supported(document.fill(FillSource.Solid(app.croptop.core.model.RgbaColor.Black)))
        awaitHistory { it.entries.last().label == "Fill" }
        assertRejected(document.fill(FillSource.Solid(app.croptop.core.model.RgbaColor.Black), opacity = 2f), RejectionCode.INVALID_ARGUMENT)
        val dup = LayerId(601)
        supported(document.layerViaCopy(dup))
        awaitLayers { it.find(dup) != null && it.primary == dup }
        supported(document.layerViaCut(LayerId(602))) // no selection cuts the whole primary layer
        val clear = document.clearSelection()
        if (!(clear is CommandResult.Rejected && clear.code == RejectionCode.UNSUPPORTED)) assertRejected(clear, RejectionCode.INVALID_ARGUMENT)
    }

    @Test
    fun mergeVisibleAndUngroup() = runBlocking<Unit> {
        val g = LayerId(610)
        val c = LayerId(611)
        assertAccepted(document.execute(EditCommand.AddLayer(g, "G", LayerKind.GROUP)))
        assertAccepted(document.execute(EditCommand.AddLayer(c, "C", LayerKind.PIXEL, parentId = g, index = 0)))
        awaitLayers { it.find(c) != null }
        supported(document.ungroup(g))
        val tree = awaitLayers { it.find(g) == null && it.find(c) != null }
        assertEquals(null, tree.parentOf(c))
        assertRejected(document.ungroup(c), RejectionCode.INVALID_ARGUMENT)
        supported(document.mergeVisible())
        awaitLayers { it.size == 1 }
    }

    @Test
    fun adjustmentLayersPreviewThenCommitOneStep() = runBlocking<Unit> {
        val id = LayerId(620)
        val spec = document.let { engine.adjustmentSpecs().first { it.id == "brightnessContrast" } }
        supported(document.addAdjustmentLayer(id, AdjustmentInfo(spec.id)))
        awaitLayers { it.find(id)?.adjustment?.specId == spec.id }
        val steps = document.history.value.entries.size
        assertAccepted(document.setAdjustment(id, mapOf("contrast" to ParamValue.Num(10f)), preview = true))
        assertAccepted(document.setAdjustment(id, mapOf("contrast" to ParamValue.Num(20f)), preview = true))
        assertEquals("previews add no step", steps, document.history.value.entries.size)
        assertAccepted(document.setAdjustment(id, mapOf("contrast" to ParamValue.Num(30f))))
        awaitHistory { it.entries.size == steps + 1 }
        assertEquals(ParamValue.Num(30f), awaitLayers { true }.find(id)!!.adjustment!!.values["contrast"])
        assertAccepted(document.undo())
        val undone = awaitLayers { it.find(id)?.adjustment?.values?.get("contrast") != ParamValue.Num(30f) }
        assertEquals("undo returns to the pre-drag value", ParamValue.Num(0f), undone.find(id)!!.adjustment!!.values["contrast"])
        assertRejected(document.addAdjustmentLayer(LayerId(621), AdjustmentInfo("nope")), RejectionCode.INVALID_ARGUMENT)
    }

    /** Skips the rest of a test while this engine hasn't implemented the call. */
    private fun supported(result: CommandResult) {
        assumeFalse("Not implemented by this engine yet: $result", result is CommandResult.Rejected && result.code == RejectionCode.UNSUPPORTED)
        assertAccepted(result)
    }

    private fun assertRejected(result: CommandResult, code: RejectionCode) {
        assertTrue("Expected $code rejection, got $result", result is CommandResult.Rejected && result.code == code)
    }

    private suspend fun awaitLayers(p: (LayerTree) -> Boolean): LayerTree = withTimeout(5_000) { document.layers.first(p) }

    private suspend fun awaitHistory(p: (HistoryState) -> Boolean): HistoryState = withTimeout(5_000) { document.history.first(p) }

    private suspend fun awaitInfo(p: (DocumentInfo) -> Boolean): DocumentInfo = withTimeout(5_000) { document.info.first(p) }

    private suspend fun awaitSelection(p: (SelectionInfo) -> Boolean): SelectionInfo = withTimeout(5_000) { document.selection.first(p) }

    private fun CoroutineScope.nextLayers(predicate: (LayerTree) -> Boolean) =
        async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { document.layers.drop(1).first(predicate) }
        }

    private fun assertAccepted(result: CommandResult) {
        assertTrue("Expected command acceptance, got $result", result is CommandResult.Accepted)
    }
}
