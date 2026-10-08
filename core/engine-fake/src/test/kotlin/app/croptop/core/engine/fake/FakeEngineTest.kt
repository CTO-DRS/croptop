package app.croptop.core.engine.fake

import app.cash.turbine.test
import app.croptop.core.engine.api.AdjustmentPreviewSession
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DepthRangeSpec
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.LayerProperties
import app.croptop.core.engine.api.LensBlurDepthSource
import app.croptop.core.engine.api.LensBlurSpec
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSampleLayout
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.BasicTone
import app.croptop.core.model.BlendIfRange
import app.croptop.core.model.BlendIfSettings
import app.croptop.core.model.BlendMode
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.ContentAwareOptions
import app.croptop.core.model.CropQuad
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerStyles
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ParamValue
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.StyleColorOverlay
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorSubpath
import app.croptop.core.model.WorkingSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FakeEngineTest {
    private val engine = FakeEngine()
    private val doc = engine.newDocument(NewDocumentSpec(name = "Test", size = DocSize(800, 600)))
    private val bg get() = doc.layers.value.layers.last().id

    private fun add(id: Long, kind: LayerKind = LayerKind.PIXEL, parent: Long? = null, index: Int = 0) =
        doc.execute(EditCommand.AddLayer(LayerId(id), "L$id", kind, parent?.let(::LayerId), index))

    private fun rejected(r: CommandResult, code: RejectionCode) = assertEquals(code, assertIs<CommandResult.Rejected>(r).code)

    @Test
    fun `raw develop options are observable for raw opens and ignored for raster opens`() = runTest {
        val rawEngine = FakeEngine()
        val recipe = DevelopSettings(basic = BasicTone(exposure = -1.25f))
        val options = OpenOptions(rawDevelopSettings = recipe)

        rawEngine.open(DocumentSource.ContentUri("content://photos/sample.RAF"), options)

        assertEquals(options, rawEngine.lastOpenOptions)
        assertEquals(recipe, rawEngine.lastRawDevelopSettings)

        rawEngine.open(DocumentSource.ContentUri("content://photos/sample.png"), options)
        assertEquals(options, rawEngine.lastOpenOptions)
        assertEquals(null, rawEngine.lastRawDevelopSettings)
    }

    @Test
    fun `quick mask overlay color validates and publishes document scoped state`() {
        assertEquals(RgbaColor(1f, 0f, 0f, 0.5f), doc.quickMaskOverlayColor.value)
        val chosen = RgbaColor(0.1f, 0.7f, 0.9f, 0.35f)

        assertIs<CommandResult.Accepted>(doc.setQuickMaskOverlayColor(chosen))
        assertEquals(chosen, doc.quickMaskOverlayColor.value)
        rejected(doc.setQuickMaskOverlayColor(RgbaColor(Float.NaN, 0f, 0f)), RejectionCode.INVALID_ARGUMENT)
        rejected(doc.setQuickMaskOverlayColor(RgbaColor(0f, 0f, 1.01f)), RejectionCode.INVALID_ARGUMENT)
    }

    @Test
    fun `smart object contents open and replace as one parent history step`() {
        val layerId = LayerId(101)
        assertIs<CommandResult.Accepted>(add(layerId.value))
        assertIs<CommandResult.Accepted>(doc.convertToSmartObject(layerId))

        val contents = doc.openSmartObjectContents(layerId)
        assertTrue(contents.info.value.name.endsWith(".psb"))
        val historyBeforeReplace = doc.history.value.entries.size
        assertIs<CommandResult.Accepted>(doc.replaceSmartObjectContents(layerId, contents))

        val smart = doc.layers.value.find(layerId)?.smartObject
        assertEquals(contents.info.value.size.width, smart?.sourceWidth)
        assertEquals(contents.info.value.size.height, smart?.sourceHeight)
        assertEquals(historyBeforeReplace + 1, doc.history.value.entries.size)
        assertEquals("Edit Smart Object Contents", doc.history.value.entries.last().label)
        contents.close()
    }

    @Test
    fun `color readout returns bounded Lab and CMYK values`() {
        val readout = doc.colorReadout(RgbaColor(1f, 0f, 0f))

        assertEquals(3, readout.lab.size)
        assertEquals(4, readout.cmykPercent.size)
        assertTrue(readout.lab.all(Float::isFinite))
        assertTrue(readout.lab[0] in 0f..100f)
        assertTrue(readout.cmykPercent.all { it.isFinite() && it in 0f..100f })
        assertFailsWith<IllegalArgumentException> { doc.colorReadout(RgbaColor(Float.NaN, 0f, 0f)) }
    }

    @Test
    fun `working profile assignment participates in undo and redo`() {
        val original = doc.info.value.profile
        val target = ColorProfileRef.BuiltIn(WorkingSpace.SRGB)
        assertIs<CommandResult.Accepted>(doc.applyColorProfile(target))
        assertEquals(target, doc.info.value.profile)
        assertEquals("Assign Color Profile", doc.history.value.undoLabel)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(original, doc.info.value.profile)
        assertIs<CommandResult.Accepted>(doc.redo())
        assertEquals(target, doc.info.value.profile)

        val embedded = ColorProfileRef.Embedded("Bad profile", "00")
        rejected(doc.applyColorProfile(embedded, byteArrayOf(1)), RejectionCode.INVALID_ARGUMENT)
    }

    @Test
    fun `accepted edit emits applied and rendered events with its sequence`() = runTest {
        doc.events.test {
            val accepted = assertIs<CommandResult.Accepted>(add(101))
            assertEquals(EngineEvent.Applied(accepted.sequence, "New Layer"), awaitItem())
            assertEquals(EngineEvent.Rendered(accepted.sequence), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `transaction groups document edits into one labeled undo step`() {
        val before = doc.history.value.entries.size
        assertIs<CommandResult.Accepted>(
            doc.transaction("Agent · setup") {
                execute(EditCommand.AddLayer(LayerId(910), "One", LayerKind.PIXEL))
                execute(EditCommand.AddLayer(LayerId(911), "Two", LayerKind.PIXEL))
            },
        )
        assertEquals(before + 1, doc.history.value.entries.size)
        assertEquals("Agent · setup", doc.history.value.entries.last().label)
        assertTrue(doc.layers.value.find(LayerId(910)) != null)
        assertTrue(doc.layers.value.find(LayerId(911)) != null)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(null, doc.layers.value.find(LayerId(910)))
        assertEquals(null, doc.layers.value.find(LayerId(911)))
    }

    @Test
    fun `fill job mirrors completed fake fill with terminal progress and history`() = runTest {
        add(101)

        val job = doc.fillJob(FillSource.Solid(RgbaColor.White))

        assertEquals(1f, job.progress.value)
        assertIs<CommandResult.Accepted>(job.await())
        assertEquals("Fill", doc.history.value.entries.last().label)
        assertIs<CommandResult.Accepted>(doc.undo())
    }

    @Test
    fun `fill job preserves typed rejection and is already terminal in fake`() = runTest {
        val job = doc.fillJob(FillSource.ContentAware())

        assertEquals(1f, job.progress.value)
        val result = assertIs<CommandResult.Rejected>(job.await())
        assertEquals(RejectionCode.INVALID_ARGUMENT, result.code)
        assertEquals("Content-Aware Fill needs a selection", result.message)
    }

    @Test
    fun `shape layer paths are editable document state with undo and redo`() {
        val path = VectorPathDraft(
            VectorPathId(10),
            "Rectangle",
            listOf(VectorSubpath(listOf(PathAnchor(PathAnchorId(11), DocPoint(12f, 18f))))),
        )
        val shapeId = LayerId(90)

        assertIs<CommandResult.Accepted>(doc.createShapeLayer(shapeId, "Shape 1", listOf(path)))
        assertEquals(listOf(path), doc.layers.value.find(shapeId)?.vectorPaths)
        assertEquals(shapeId, doc.layers.value.primary)

        val updated = path.copy(name = "Renamed path")
        assertIs<CommandResult.Accepted>(doc.setShapePaths(shapeId, listOf(updated)))
        assertEquals("Renamed path", doc.layers.value.find(shapeId)?.vectorPaths?.single()?.name)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals("Rectangle", doc.layers.value.find(shapeId)?.vectorPaths?.single()?.name)
        assertIs<CommandResult.Accepted>(doc.redo())
        assertEquals("Renamed path", doc.layers.value.find(shapeId)?.vectorPaths?.single()?.name)
    }

    @Test
    fun `layer style preview is visible without history and cancel and apply preserve undo state`() {
        val layer = LayerId(930)
        assertIs<CommandResult.Accepted>(add(layer.value))
        val baselineHistory = doc.history.value
        val red = LayerStyles(colorOverlay = StyleColorOverlay(enabled = true, color = RgbaColor(1f, 0f, 0f)))
        val blue = LayerStyles(colorOverlay = StyleColorOverlay(enabled = true, color = RgbaColor(0f, 0f, 1f)))

        assertIs<CommandResult.Accepted>(doc.previewLayerStyles(layer, red))
        assertEquals(red, doc.layers.value.find(layer)?.styles)
        assertTrue(doc.layers.value.find(layer)?.hasStyles == true)
        assertEquals(baselineHistory, doc.history.value)

        assertIs<CommandResult.Accepted>(doc.previewLayerStyles(layer, blue))
        assertEquals(blue, doc.layers.value.find(layer)?.styles)
        assertIs<CommandResult.Accepted>(doc.previewLayerStyles(layer, null))
        assertEquals(null, doc.layers.value.find(layer)?.styles)
        assertFalse(doc.layers.value.find(layer)?.hasStyles == true)
        assertEquals(baselineHistory, doc.history.value)

        assertIs<CommandResult.Accepted>(doc.previewLayerStyles(layer, red))
        assertIs<CommandResult.Accepted>(doc.setLayerStyles(layer, red))
        assertEquals(red, doc.layers.value.find(layer)?.styles)
        assertEquals(baselineHistory.entries.size + 1, doc.history.value.entries.size)
        assertEquals("Layer Style", doc.history.value.entries.last().label)

        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(null, doc.layers.value.find(layer)?.styles)
        assertIs<CommandResult.Accepted>(doc.redo())
        assertEquals(red, doc.layers.value.find(layer)?.styles)
    }

    @Test
    fun `destructive adjustment preview cancels without history and commits one step`() {
        assertIs<CommandResult.Accepted>(add(948))
        val baseline = doc.history.value.current
        assertTrue(doc.supportsDestructiveAdjustment("brightnessContrast"))

        val canceled = doc.beginAdjustmentPreview(AdjustmentInfo("brightnessContrast"))
        assertIs<CommandResult.Accepted>(canceled.preview(mapOf("contrast" to ParamValue.Num(30f))))
        assertEquals(baseline, doc.history.value.current)
        canceled.cancel()
        assertEquals(baseline, doc.history.value.current)

        val applied = doc.beginAdjustmentPreview(AdjustmentInfo("brightnessContrast"))
        assertIs<CommandResult.Accepted>(applied.preview(mapOf("brightness" to ParamValue.Num(12f))))
        assertEquals(baseline, doc.history.value.current)
        assertIs<CommandResult.Accepted>(applied.commit())
        assertEquals(baseline + 1, doc.history.value.current)
        assertEquals("Brightness/Contrast", doc.history.value.undoLabel)
    }

    @Test
    fun `lens blur and depth range mask record undoable commands and validate depth sources`() {
        val target = LayerId(940)
        val depth = LayerId(941)
        add(target.value)
        add(depth.value)
        val baseline = doc.history.value.current

        assertIs<CommandResult.Accepted>(
            doc.applyLensBlur(target, LensBlurSpec(radius = 12f), LensBlurDepthSource.LayerPixels(depth)),
        )
        assertEquals("Lens Blur", doc.history.value.entries.last().label)
        assertEquals(baseline + 1, doc.history.value.current)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(baseline, doc.history.value.current)
        assertIs<CommandResult.Accepted>(doc.redo())

        assertIs<CommandResult.Accepted>(
            doc.applyDepthRangeMask(target, LensBlurDepthSource.LayerPixels(depth), DepthRangeSpec(0.2f, 0.8f, 0.1f)),
        )
        assertTrue(doc.layers.value.find(target)?.mask != null)
        assertEquals("Depth Range Mask", doc.history.value.entries.last().label)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(null, doc.layers.value.find(target)?.mask)

        rejected(doc.applyLensBlur(target, LensBlurSpec(), LensBlurDepthSource.LayerPixels(LayerId(999))), RejectionCode.NOT_FOUND)
        assertIs<CommandResult.Accepted>(doc.select(SelectionShape.Rect(DocRectF(0f, 0f, 40f, 40f))))
        val selectedHistory = doc.history.value.current
        assertIs<CommandResult.Accepted>(doc.applyLensBlur(target))
        assertEquals(selectedHistory + 1, doc.history.value.current)
        assertFailsWith<IllegalArgumentException> { LensBlurSpec(irisRotationDegrees = 361f) }
        assertFailsWith<IllegalArgumentException> { DepthRangeSpec(near = 0.8f, far = 0.2f) }
    }

    @Test
    fun `layer style preview rejects missing and unsupported layer targets`() {
        add(931, LayerKind.GROUP)

        val missing = assertIs<CommandResult.Rejected>(doc.previewLayerStyles(LayerId(9999), null))
        assertEquals(RejectionCode.NOT_FOUND, missing.code)
        val group = assertIs<CommandResult.Rejected>(doc.previewLayerStyles(LayerId(931), null))
        assertEquals(RejectionCode.UNSUPPORTED, group.code)
    }

    @Test
    fun `unrelated edit does not commit a transient layer style preview`() {
        val layer = LayerId(932)
        assertIs<CommandResult.Accepted>(add(layer.value))
        val red = LayerStyles(colorOverlay = StyleColorOverlay(enabled = true, color = RgbaColor(1f, 0f, 0f)))
        assertIs<CommandResult.Accepted>(doc.previewLayerStyles(layer, red))

        assertIs<CommandResult.Accepted>(
            doc.execute(EditCommand.SetLayerProps(layer, LayerProperties(opacity = 0.5f))),
        )
        assertEquals(null, doc.layers.value.find(layer)?.styles)
        assertEquals(0.5f, doc.layers.value.find(layer)?.opacity)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(null, doc.layers.value.find(layer)?.styles)
        assertEquals(1f, doc.layers.value.find(layer)?.opacity)
    }

    @Test
    fun `blend if is stored per layer and undoable`() {
        val layer = LayerId(933)
        assertIs<CommandResult.Accepted>(add(layer.value))
        val settings = BlendIfSettings(self = BlendIfRange(whiteLo = 180, whiteHi = 220), under = BlendIfRange(blackLo = 10, blackHi = 40))
        assertIs<CommandResult.Accepted>(doc.execute(EditCommand.SetLayerProps(layer, LayerProperties(blendIf = settings))))
        assertEquals(settings, doc.layers.value.find(layer)?.blendIf)
        assertIs<CommandResult.Accepted>(doc.execute(EditCommand.SetLayerProps(layer, LayerProperties(opacity = 0.5f))))
        assertEquals(settings, doc.layers.value.find(layer)?.blendIf)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(BlendIfSettings(), doc.layers.value.find(layer)?.blendIf)
    }

    @Test
    fun `content aware fill can output to a new layer and undo removes it`() {
        val layer = LayerId(934)
        assertIs<CommandResult.Accepted>(add(layer.value))
        assertIs<CommandResult.Accepted>(doc.select(SelectionShape.Rect(DocRectF(10f, 10f, 40f, 40f))))
        val before = doc.layers.value.size
        val out = LayerId(935)
        assertIs<CommandResult.Accepted>(doc.fill(FillSource.ContentAware(ContentAwareOptions(newLayerId = out))))
        assertEquals(before + 1, doc.layers.value.size)
        assertEquals(out, doc.layers.value.primary)
        assertIs<CommandResult.Rejected>(doc.fill(FillSource.ContentAware(ContentAwareOptions(newLayerId = out))))
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(before, doc.layers.value.size)
    }

    @Test
    fun `raw opened as a smart object keeps an editable develop recipe`() = kotlinx.coroutines.test.runTest {
        val engine = FakeEngine()
        val raw = engine.open(
            app.croptop.core.engine.api.DocumentSource.AppFile("/x/photo.dng"),
            app.croptop.core.engine.api.OpenOptions(rawAsSmartObject = true),
        )
        val layer = raw.layers.value.primary!!
        assertEquals(app.croptop.core.model.LayerKind.SMART_OBJECT, raw.layers.value.find(layer)?.kind)
        val recipe = app.croptop.core.model.DevelopSettings()
        assertIs<CommandResult.Accepted>(raw.setRawDevelopSettings(layer, recipe))
        assertEquals(recipe, raw.layers.value.find(layer)?.smartObject?.raw?.settings)
        val flat = engine.open(app.croptop.core.engine.api.DocumentSource.AppFile("/x/photo.dng"))
        assertEquals(null, flat.layers.value.find(flat.layers.value.primary!!)?.smartObject)
        assertIs<CommandResult.Rejected>(flat.setRawDevelopSettings(flat.layers.value.primary!!, recipe))
    }

    @Test
    fun `blend if preview is transient and never enters history`() {
        val layer = LayerId(936)
        assertIs<CommandResult.Accepted>(add(layer.value))
        val settings = BlendIfSettings(self = BlendIfRange(blackLo = 10, blackHi = 30))
        val steps = doc.history.value.entries.size
        assertIs<CommandResult.Accepted>(doc.previewBlendIf(layer, settings))
        assertEquals(steps, doc.history.value.entries.size)
        assertEquals(BlendIfSettings(), doc.layers.value.find(layer)?.blendIf)
        assertIs<CommandResult.Accepted>(doc.previewBlendIf(layer, null))
    }

    @Test
    fun `warp commits one step and an untouched mesh is a no-op`() {
        val layer = LayerId(937)
        assertIs<CommandResult.Accepted>(add(layer.value))
        val size = doc.info.value.size
        val steps = doc.history.value.entries.size
        val identity = app.croptop.core.model.WarpMesh.identity(size.width, size.height, 3, 3)
        assertIs<CommandResult.Accepted>(doc.warpLayer(layer, identity))
        assertEquals(steps, doc.history.value.entries.size)
        val bent = identity.copy(nodes = identity.nodes.mapIndexed { i, p -> if (i == 4) DocPoint(p.x + 20f, p.y - 10f) else p })
        assertIs<CommandResult.Accepted>(doc.warpLayer(layer, bent))
        assertEquals(steps + 1, doc.history.value.entries.size)
    }

    @Test
    fun `liquify session previews without history and commits one step or cancels cleanly`() {
        val layer = LayerId(938)
        assertIs<CommandResult.Accepted>(add(layer.value))
        val steps = doc.history.value.entries.size
        val stroke = app.croptop.core.engine.api.LiquifyStroke(
            app.croptop.core.engine.api.LiquifyTool.FORWARD_WARP,
            listOf(app.croptop.core.engine.api.LiquifyDab(10.0, 10.0, 40.0, dx = 3.0, dy = 1.0)),
        )
        val cancelled = doc.beginLiquify(layer)
        assertIs<CommandResult.Accepted>(cancelled.stroke(stroke))
        assertIs<CommandResult.Accepted>(cancelled.paintFreeze(listOf(DocPoint(5f, 5f)), 10f, true))
        assertEquals(steps, doc.history.value.entries.size)
        cancelled.cancel()
        assertEquals(steps, doc.history.value.entries.size)

        val committed = doc.beginLiquify(layer)
        assertIs<CommandResult.Rejected>(committed.reconstructAll(2f))
        assertIs<CommandResult.Accepted>(committed.stroke(stroke))
        assertIs<CommandResult.Accepted>(committed.commit())
        assertEquals(steps + 1, doc.history.value.entries.size)
        assertIs<CommandResult.Rejected>(committed.stroke(stroke))
    }

    @Test
    fun `smart filter mask lifecycle is undoable and needs a smart object`() {
        val layer = LayerId(939)
        assertIs<CommandResult.Accepted>(add(layer.value))
        assertIs<CommandResult.Rejected>(doc.addSmartFilterMask(layer))
        assertIs<CommandResult.Accepted>(doc.convertToSmartObject(layer))
        assertIs<CommandResult.Accepted>(doc.addSmartFilterMask(layer))
        assertIs<CommandResult.Rejected>(doc.addSmartFilterMask(layer))
        val key = doc.layers.value.find(layer)?.smartObject?.filterMask?.key
        assertIs<CommandResult.Accepted>(doc.paintSmartFilterMask(layer, listOf(DocPoint(5f, 5f)), 10f, reveal = false))
        assertEquals(false, doc.layers.value.find(layer)?.smartObject?.filterMask?.key == key)
        assertIs<CommandResult.Accepted>(doc.setSmartFilterMaskEnabled(layer, false))
        assertEquals(false, doc.layers.value.find(layer)?.smartObject?.filterMask?.enabled)
        assertIs<CommandResult.Accepted>(doc.invertSmartFilterMask(layer))
        assertIs<CommandResult.Accepted>(doc.deleteSmartFilterMask(layer))
        assertEquals(null, doc.layers.value.find(layer)?.smartObject?.filterMask)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(true, doc.layers.value.find(layer)?.smartObject?.filterMask != null)
    }

    @Test
    fun `perspective crop updates size and is undoable while rejecting hidden pixels and malformed quads`() {
        val quad = CropQuad(DocPoint(10f, 12f), DocPoint(110f, 8f), DocPoint(105f, 72f), DocPoint(14f, 70f))
        val original = doc.info.value.size
        assertIs<CommandResult.Accepted>(doc.cropPerspective(quad))
        val cropped = doc.info.value.size
        assertTrue(cropped.width in 1..65_535 && cropped.height in 1..65_535)
        assertEquals("Perspective Crop", doc.history.value.entries.last().label)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(original, doc.info.value.size)
        assertIs<CommandResult.Accepted>(doc.redo())
        assertEquals(cropped, doc.info.value.size)

        val before = doc.history.value.current
        rejected(doc.cropPerspective(quad, deleteCropped = false), RejectionCode.UNSUPPORTED)
        rejected(
            doc.cropPerspective(CropQuad(DocPoint(0f, 0f), DocPoint(20f, 20f), DocPoint(20f, 0f), DocPoint(0f, 20f))),
            RejectionCode.INVALID_ARGUMENT,
        )
        assertEquals(before, doc.history.value.current)
    }

    @Test
    fun `layer via cut without selection moves whole primary bounds in one undoable step`() {
        val sourceId = doc.layers.value.primary!!
        val sourceBounds = doc.layers.value.find(sourceId)!!.bounds!!
        val copyId = LayerId(401)

        assertIs<CommandResult.Accepted>(doc.layerViaCut(copyId))

        assertEquals("Layer via Cut", doc.history.value.entries.last().label)
        assertEquals(sourceBounds, doc.layers.value.find(copyId)?.bounds)
        assertEquals(null, doc.layers.value.find(sourceId)?.bounds)
        assertEquals(copyId, doc.layers.value.primary)

        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(sourceBounds, doc.layers.value.find(sourceId)?.bounds)
        assertEquals(null, doc.layers.value.find(copyId))

        assertIs<CommandResult.Accepted>(doc.redo())
        assertEquals(sourceBounds, doc.layers.value.find(copyId)?.bounds)
        assertEquals(null, doc.layers.value.find(sourceId)?.bounds)
    }

    @Test
    fun `shape layer rejects invalid or duplicate vector identities`() {
        val path = VectorPathDraft(
            VectorPathId(10),
            "Rectangle",
            listOf(VectorSubpath(listOf(PathAnchor(PathAnchorId(11), DocPoint(12f, 18f))))),
        )
        rejected(doc.createShapeLayer(LayerId(90), "Shape", listOf(path, path)), RejectionCode.INVALID_ARGUMENT)
        rejected(doc.createShapeLayer(LayerId(90), "Shape", emptyList()), RejectionCode.INVALID_ARGUMENT)
        assertEquals(1, doc.layers.value.size)
    }

    @Test
    fun `sample averages centered pixel box and clips it at document edges`() {
        val modes = mutableListOf<SampleMode>()
        val pixelEngine = FakeEngine { x, y, mode ->
            modes += mode
            RgbaColor(((x + y * 3) / 8f).coerceIn(0f, 1f), 0f, 0f)
        }
        val pixelDoc = pixelEngine.newDocument(NewDocumentSpec(name = "Sampler", size = DocSize(3, 3)))

        val center = pixelDoc.sample(DocPoint(1.5f, 1.5f), SampleMode.ACTIVE_LAYER, size = 3)
        assertEquals(0.613f, center.color.r, 0.001f)
        assertEquals(DocPoint(1.5f, 1.5f), center.point)
        assertEquals(SampleMode.ACTIVE_LAYER, center.mode)

        val corner = pixelDoc.sample(DocPoint(0.2f, 0.2f), SampleMode.MERGED, size = 3)
        assertEquals(0.325f, corner.color.r, 0.001f)
        assertEquals(SampleMode.MERGED, modes.last())
        pixelEngine.close()
    }

    @Test
    fun `sample supports documented sizes and rejects unsupported sizes`() {
        val pixelEngine = FakeEngine { _, _, _ -> RgbaColor(0.25f, 0.5f, 0.75f) }
        val pixelDoc = pixelEngine.newDocument(NewDocumentSpec(name = "Sampler", size = DocSize(120, 120)))
        listOf(1, 3, 5, 11, 31, 51, 101).forEach { size ->
            assertEquals(0.25f, pixelDoc.sample(DocPoint(60f, 60f), SampleMode.MERGED, size).color.r, 1e-6f)
        }
        assertFailsWith<IllegalArgumentException> {
            pixelDoc.sample(DocPoint(60f, 60f), SampleMode.MERGED, size = 7)
        }
        pixelEngine.close()
    }

    @Test
    fun `sample averages rgb in premultiplied alpha space`() {
        val pixelEngine = FakeEngine { x, _, _ ->
            if (x < 2) RgbaColor(1f, 0f, 0f, 0f) else RgbaColor(0f, 0f, 1f, 1f)
        }
        val pixelDoc = pixelEngine.newDocument(NewDocumentSpec(name = "Alpha sampler", size = DocSize(3, 3)))

        val sample = pixelDoc.sample(DocPoint(1f, 1f), SampleMode.MERGED, size = 3)

        assertEquals(0f, sample.color.r, 1e-6f)
        assertEquals(0f, sample.color.g, 1e-6f)
        assertEquals(1f, sample.color.b, 1e-6f)
        assertEquals(1f / 3f, sample.color.a, 1e-6f)
        pixelEngine.close()
    }

    @Test
    fun `sample averages sRGB pixels in linear light to match native rendering`() {
        val pixelEngine = FakeEngine { x, y, _ ->
            if (x == 2 && y == 2) RgbaColor.White else RgbaColor.Black
        }
        val pixelDoc = pixelEngine.newDocument(NewDocumentSpec(name = "Linear sampler", size = DocSize(3, 3)))

        val sample = pixelDoc.sample(DocPoint(1f, 1f), SampleMode.MERGED, size = 3)

        assertEquals(0.367f, sample.color.r, 0.002f)
        assertEquals(1f, sample.color.a, 1e-6f)
        pixelEngine.close()
    }

    @Test
    fun `new document has locked background and clean history`() {
        val tree = doc.layers.value
        assertEquals(listOf("Background"), tree.layers.map { it.name })
        assertTrue(tree.layers.single().locks.position)
        assertEquals(setOf(bg), tree.active)
        assertFalse(doc.history.value.canUndo)
        assertFalse(doc.info.value.dirty)
    }

    @Test
    fun `add layer goes on top, becomes active, marks dirty`() {
        assertIs<CommandResult.Accepted>(add(100))
        val tree = doc.layers.value
        assertEquals(listOf(100L, bg.value), tree.layers.map { it.id.value })
        assertEquals(LayerId(100), tree.primary)
        assertTrue(doc.info.value.dirty)
        assertEquals("New Layer", doc.history.value.undoLabel)
    }

    @Test
    fun `mask thumbnail flow emits opaque grayscale for edits undo and redo`() = runTest {
        val id = LayerId(100)
        add(100)
        doc.addLayerMask(id, MaskFill.REVEAL_ALL)

        fun assertCoverage(thumb: app.croptop.core.engine.api.LayerThumb, gray: Int) {
            assertEquals(2, thumb.width)
            assertEquals(2, thumb.height)
            for (offset in thumb.rgba.indices step 4) {
                assertEquals(gray.toByte(), thumb.rgba[offset])
                assertEquals(gray.toByte(), thumb.rgba[offset + 1])
                assertEquals(gray.toByte(), thumb.rgba[offset + 2])
                assertEquals(0xFF.toByte(), thumb.rgba[offset + 3])
            }
        }

        doc.maskThumbnails(listOf(id), px = 2).test {
            val white = awaitItem()
            assertCoverage(white, 0xFF)

            assertIs<CommandResult.Accepted>(doc.invertLayerMask(id))
            assertCoverage(awaitItem(), 0)
            assertIs<CommandResult.Accepted>(doc.undo())
            assertCoverage(awaitItem(), 0xFF)
            assertIs<CommandResult.Accepted>(doc.redo())
            assertCoverage(awaitItem(), 0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `undo redo and branch truncation`() {
        add(100)
        add(101)
        doc.undo()
        assertEquals(2, doc.layers.value.size)
        doc.redo()
        assertEquals(3, doc.layers.value.size)
        doc.undo()
        add(102) // discards redo of 101
        assertFalse(doc.history.value.canRedo)
        assertEquals(listOf(102L, 100L, bg.value), doc.layers.value.layers.map { it.id.value })
        doc.jumpTo(0)
        assertEquals(1, doc.layers.value.size)
    }

    @Test
    fun `named snapshots restore as undoable entries and survive branch truncation`() {
        assertIs<CommandResult.Accepted>(doc.createSnapshot("Before edit"))
        assertEquals("Before edit", doc.history.value.snapshots.single().name)
        add(100)
        doc.undo()
        add(101) // truncates the redo branch, but the named snapshot remains available
        assertEquals(1, doc.history.value.snapshots.size)
        assertIs<CommandResult.Accepted>(doc.renameSnapshot(0, "  Original  "))
        assertEquals("Original", doc.history.value.snapshots.single().name)
        rejected(doc.createSnapshot(" original "), RejectionCode.INVALID_ARGUMENT)
        assertIs<CommandResult.Accepted>(doc.jumpToSnapshot(0))
        assertEquals(1, doc.layers.value.size)
        assertEquals("Restore snapshot: Original", doc.history.value.undoLabel)
        assertIs<CommandResult.Accepted>(doc.undo())
        assertEquals(2, doc.layers.value.size)
        assertIs<CommandResult.Accepted>(doc.deleteSnapshot(0))
        assertTrue(doc.history.value.snapshots.isEmpty())
    }

    @Test
    fun `groups nest and cannot contain themselves`() {
        add(10, LayerKind.GROUP)
        add(11, parent = 10)
        assertEquals(LayerId(10), doc.layers.value.parentOf(LayerId(11))?.id)
        add(12, LayerKind.GROUP, parent = 10)
        rejected(doc.execute(EditCommand.MoveLayer(LayerId(10), LayerId(12), 0)), RejectionCode.INVALID_ARGUMENT)
        assertIs<CommandResult.Accepted>(doc.execute(EditCommand.MoveLayer(LayerId(11), null, 0)))
        assertEquals(null, doc.layers.value.parentOf(LayerId(11)))
    }

    @Test
    fun `deleting a group removes children from active set`() {
        add(10, LayerKind.GROUP)
        add(11, parent = 10)
        doc.execute(EditCommand.DeleteLayer(LayerId(10)))
        val tree = doc.layers.value
        assertEquals(1, tree.size)
        assertFalse(LayerId(11) in tree.active)
    }

    @Test
    fun `validation rejections`() {
        rejected(doc.execute(EditCommand.DeleteLayer(bg)), RejectionCode.INVALID_ARGUMENT) // last layer
        rejected(doc.execute(EditCommand.DeleteLayer(LayerId(999))), RejectionCode.NOT_FOUND)
        rejected(add(100, index = 5), RejectionCode.INVALID_ARGUMENT)
        add(100)
        rejected(add(100), RejectionCode.INVALID_ARGUMENT) // duplicate id
        rejected(doc.execute(EditCommand.SetBlendMode(LayerId(100), BlendMode.PASS_THROUGH)), RejectionCode.INVALID_ARGUMENT)
        // Position lock blocks moving into another group, but not restacking (Photoshop behavior).
        add(10, LayerKind.GROUP)
        rejected(doc.execute(EditCommand.MoveLayer(bg, LayerId(10), 0)), RejectionCode.INVALID_ARGUMENT)
        assertIs<CommandResult.Accepted>(doc.execute(EditCommand.MoveLayer(bg, null, 0)))
    }

    @Test
    fun `props update with photoshop history labels`() = runTest {
        add(100)
        doc.layers.test {
            skipItems(1)
            doc.execute(EditCommand.SetLayerProps(LayerId(100), LayerProperties(opacity = 0.5f)))
            assertEquals(0.5f, awaitItem().find(LayerId(100))!!.opacity)
        }
        assertEquals("Opacity Change", doc.history.value.undoLabel)
        doc.execute(EditCommand.SetLayerProps(LayerId(100), LayerProperties(visible = false)))
        assertEquals("Hide Layer", doc.history.value.undoLabel)
    }

    @Test
    fun `strokes add history steps and respect locks`() {
        add(100)
        val s = doc.beginStroke(StrokeSpec(LayerId(100), RgbaColor.Black, diameter = 20f))
        s.add(FloatArray(StrokeSampleLayout.STRIDE * 3))
        assertIs<CommandResult.Accepted>(s.end())
        assertEquals("Brush Tool", doc.history.value.undoLabel)
        val empty = doc.beginStroke(StrokeSpec(LayerId(100), RgbaColor.Black, diameter = 20f))
        rejected(empty.end(), RejectionCode.INVALID_ARGUMENT)
    }

    @Test
    fun `eraser and retouch strokes are labeled by what they do`() {
        add(100)
        fun run(s: app.croptop.core.engine.api.StrokeSession): CommandResult {
            s.add(FloatArray(StrokeSampleLayout.STRIDE * 2))
            return s.end()
        }
        val tip = StrokeSpec(LayerId(100), RgbaColor.Black, diameter = 20f)
        assertIs<CommandResult.Accepted>(run(doc.beginStroke(tip.copy(erase = true))))
        assertEquals("Eraser", doc.history.value.undoLabel)
        val effects = listOf(
            app.croptop.core.engine.api.RetouchEffect.Tone(burn = true) to "Burn",
            app.croptop.core.engine.api.RetouchEffect.Focus(sharpen = false) to "Blur",
            app.croptop.core.engine.api.RetouchEffect.Clone(app.croptop.core.model.DocPoint(5f, 5f)) to "Clone Stamp",
            app.croptop.core.engine.api.RetouchEffect.SpotHeal() to "Spot Healing Brush",
            app.croptop.core.engine.api.RetouchEffect.BackgroundErase() to "Background Eraser",
        )
        for ((effect, label) in effects) {
            assertIs<CommandResult.Accepted>(run(doc.beginRetouch(tip, effect)))
            assertEquals(label, doc.history.value.undoLabel)
        }
        val offCanvas = app.croptop.core.engine.api.RetouchEffect.Heal(app.croptop.core.model.DocPoint(-10f, 5f))
        rejected(run(doc.beginRetouch(tip, offCanvas)), RejectionCode.INVALID_ARGUMENT)
    }

    @Test
    fun `the history brush paints back from a state that exists`() {
        add(100)
        fun run(s: app.croptop.core.engine.api.StrokeSession): CommandResult {
            s.add(FloatArray(StrokeSampleLayout.STRIDE * 2))
            return s.end()
        }
        val tip = StrokeSpec(LayerId(100), RgbaColor.Black, diameter = 20f)
        val states = doc.history.value.entries.size
        assertIs<CommandResult.Accepted>(run(doc.beginRetouch(tip, app.croptop.core.engine.api.RetouchEffect.HistoryPaint(0))))
        assertEquals("History Brush", doc.history.value.undoLabel)
        rejected(run(doc.beginRetouch(tip, app.croptop.core.engine.api.RetouchEffect.HistoryPaint(states + 40))), RejectionCode.INVALID_ARGUMENT)
        assertFailsWith<IllegalArgumentException> { app.croptop.core.engine.api.RetouchEffect.HistoryPaint(-1) }
        assertFailsWith<IllegalArgumentException> {
            app.croptop.core.engine.api.RetouchEffect.BackgroundErase(sampling = app.croptop.core.engine.api.BackgroundSampling.SWATCH)
        }
        assertFailsWith<IllegalArgumentException> { app.croptop.core.engine.api.RetouchEffect.BackgroundErase(tolerance = 1.5f) }
    }

    @Test
    fun `save clears dirty and close rejects further edits`() = runTest {
        add(100)
        doc.save(app.croptop.core.engine.api.SaveTarget.AppFile(kotlin.io.path.createTempFile().toString()))
        assertFalse(doc.info.value.dirty)
        doc.undo()
        assertTrue(doc.info.value.dirty)
        engine.close()
        rejected(add(200), RejectionCode.CLOSED)
    }

    @Test
    fun historyGroupMergesEditsIntoOneStepAndCancelRestoresTheDocument() {
        val engine = FakeEngine()
        val doc = engine.newDocument(app.croptop.core.model.NewDocumentSpec("Group", app.croptop.core.model.DocSize(100, 80)))
        val baseline = doc.history.value
        assertTrue(doc.beginHistoryGroup("Big job") is CommandResult.Accepted)
        assertTrue(doc.beginHistoryGroup("Again") is CommandResult.Rejected, "groups do not nest")
        assertTrue(doc.execute(EditCommand.AddLayer(app.croptop.core.model.LayerId(500), "One", app.croptop.core.model.LayerKind.PIXEL)) is CommandResult.Accepted)
        assertTrue(doc.execute(EditCommand.AddLayer(app.croptop.core.model.LayerId(501), "Two", app.croptop.core.model.LayerKind.PIXEL)) is CommandResult.Accepted)
        assertTrue(doc.endHistoryGroup() is CommandResult.Accepted)
        assertEquals(baseline.entries.size + 1, doc.history.value.entries.size)
        assertEquals("Big job", doc.history.value.undoLabel)
        assertEquals(3, doc.layers.value.layers.size)
        assertTrue(doc.endHistoryGroup() is CommandResult.Rejected, "nothing is open any more")
        assertTrue(doc.undo() is CommandResult.Accepted)
        assertEquals(1, doc.layers.value.layers.size, "one undo removes both layers")

        val clean = doc.history.value
        assertTrue(doc.beginHistoryGroup("Abandoned") is CommandResult.Accepted)
        assertTrue(doc.execute(EditCommand.AddLayer(app.croptop.core.model.LayerId(502), "Three", app.croptop.core.model.LayerKind.PIXEL)) is CommandResult.Accepted)
        assertTrue(doc.cancelHistoryGroup() is CommandResult.Accepted)
        assertEquals(1, doc.layers.value.layers.size)
        assertEquals(clean.current, doc.history.value.current)
        assertEquals(clean.entries.size - 1 + 1, doc.history.value.entries.size.coerceAtLeast(clean.entries.size))
        assertTrue(doc.cancelHistoryGroup() is CommandResult.Rejected)
        // An empty group adds nothing.
        assertTrue(doc.beginHistoryGroup("Nothing") is CommandResult.Accepted)
        val sizeBefore = doc.history.value.entries.size
        assertTrue(doc.endHistoryGroup() is CommandResult.Accepted)
        assertEquals(sizeBefore, doc.history.value.entries.size)
    }
}
