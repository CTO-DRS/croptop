package app.croptop.feature.editor.session

import androidx.lifecycle.ViewModelStore
import app.croptop.core.designsystem.component.LevelsPickTarget
import app.croptop.core.engine.api.ColorSample
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentGuides
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportResult
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SaveOptions
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.BasicTone
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.GridSettings
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ParamValue
import app.croptop.core.model.RgbaColor
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.NoRecents
import app.croptop.feature.editor.TempShareCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before

class EditorViewModelTest {
    private lateinit var vm: EditorViewModel
    private val viewModelStores = mutableListOf<ViewModelStore>()

    private fun track(viewModel: EditorViewModel): EditorViewModel {
        val store = ViewModelStore()
        store.put("editor", viewModel)
        viewModelStores += store
        return viewModel
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = track(
            EditorViewModel(
                FakeEngine(),
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(FakeEngine()),
                { null },
                EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
            ),
        )
    }

    @After
    fun tearDown() {
        viewModelStores.forEach(ViewModelStore::clear)
        viewModelStores.clear()
        Dispatchers.resetMain()
    }

    private val names get() = vm.state.value.layers.flatten().map { it.name }

    @Test
    fun `document guide geometry and view guide edits stay synchronized`() {
        val document = checkNotNull(vm.document.value)
        val persistedGuide = Guide(GuideAxis.VERTICAL, 24f)
        val persistedGrid = GridSettings(spacing = 48f, subdivisions = 3)

        document.setDocumentGuides(DocumentGuides(listOf(persistedGuide), persistedGrid))

        assertTrue(
            waitForState {
                vm.viewAids.value.guides == listOf(persistedGuide) && vm.viewAids.value.gridSettings == persistedGrid
            },
        )

        val editedGuide = Guide(GuideAxis.HORIZONTAL, 32f)
        val editedGrid = GridSettings(spacing = 64f, subdivisions = 2)
        vm.viewAids.value = vm.viewAids.value.copy(guides = listOf(editedGuide), gridSettings = editedGrid)

        assertTrue(
            waitForState {
                document.documentGuides.value == DocumentGuides(listOf(editedGuide), editedGrid)
            },
        )
    }

    @Test
    fun `quick mask overlay color follows active document and setter applies color`() = runBlocking {
        val document = checkNotNull(vm.document.value)
        assertEquals(RgbaColor(1f, 0f, 0f, 0.5f), vm.quickMaskOverlayColor().first())

        val tint = RgbaColor(0.12f, 0.48f, 0.86f, 0.32f)
        assertTrue(vm.setQuickMaskOverlayColor(tint))
        assertEquals(tint, document.quickMaskOverlayColor.value)
        assertEquals(tint, vm.quickMaskOverlayColor().first())

        assertFalse(vm.setQuickMaskOverlayColor(RgbaColor(Float.NaN, 0f, 0f, 0.5f)))
        assertEquals(tint, document.quickMaskOverlayColor.value)
    }

    @Test
    fun `refine new document output is registered and routed to a sibling editor tab`() {
        val engine = FakeEngine()
        val documents = app.croptop.core.agent.OpenDocuments(engine)
        val output = engine.newDocument(NewDocumentSpec(name = "Refined Selection", size = DocSize(32, 24)))
        val session = object : app.croptop.core.engine.api.RefineEdgeSession {
            override val outputDocument = output
            override fun update(settings: app.croptop.core.engine.api.RefineEdgeSettings) = CommandResult.Accepted(1)
            override fun commit(output: app.croptop.core.engine.api.RefineEdgeOutput) = CommandResult.Accepted(1)
            override fun cancel() = Unit
        }
        val routeVm = track(
            EditorViewModel(
                engine,
                NoRecents,
                TempShareCache,
                documents,
                { null },
                EditorRoute(newSpec = NewDocumentSpec(size = DocSize(40, 40))),
            ),
        )

        val route = routeVm.refineOutputDocumentRoute(session)

        assertEquals(output.id.value, route?.openDocumentId)
        assertEquals(output, documents.byId(output.id.value))
    }

    private fun waitForState(timeoutMillis: Long = 5_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(1)
        }
        return predicate()
    }

    private fun createViewModel(engine: Engine) =
        track(
            EditorViewModel(
                engine,
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(engine),
                { null },
                EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
            ),
        )

    private fun sampledEngine(color: RgbaColor, onSample: (app.croptop.core.model.LayerId, DocPoint, Int) -> Unit = { _, _, _ -> }): Engine {
        val delegate = FakeEngine()
        return object : Engine by delegate {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val document = delegate.newDocument(spec)
                return object : DocumentHandle by document {
                    override fun trySampleBelow(layerId: app.croptop.core.model.LayerId, point: DocPoint, size: Int): ColorSample? {
                        onSample(layerId, point, size)
                        return ColorSample(color, point, app.croptop.core.engine.api.SampleMode.MERGED)
                    }
                }
            }
        }
    }

    @Test
    fun `photoshop style names`() {
        vm.newLayer()
        vm.newLayer()
        vm.newLayer(LayerKind.GROUP)
        vm.newLayer(LayerKind.ADJUSTMENT)
        assertEquals(listOf("Adjustment 1", "Group 1", "Layer 2", "Layer 1", "Background"), names)
    }

    @Test
    fun `color shortcuts report completion while panel callbacks can publish one contextual result`() {
        val foreground = RgbaColor(0.9f, 0.2f, 0.1f)
        val background = RgbaColor(0.1f, 0.3f, 0.8f)
        vm.setForeground(foreground)
        vm.setBackground(background)

        vm.swapColors(reportCompletion = false)

        assertEquals(background, vm.colors.value.foreground)
        assertEquals(foreground, vm.colors.value.background)
        assertNull(vm.state.value.actionFeedback)

        vm.swapColors()

        assertEquals("Swap colors", vm.state.value.actionFeedback?.action)
        vm.defaultColors(reportCompletion = false)
        assertEquals(RgbaColor.Black, vm.colors.value.foreground)
        assertEquals(RgbaColor.White, vm.colors.value.background)
        assertEquals("Swap colors", vm.state.value.actionFeedback?.action)

        vm.defaultColors()

        assertEquals("Reset colors", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun `new layer goes above selection and is selected`() {
        vm.newLayer()
        val first = vm.state.value.primary
        assertEquals("Add Layer 1", vm.state.value.actionFeedback?.action)
        vm.selectAdjacent(below = true) // Background
        vm.newLayer()
        assertEquals(listOf("Layer 1", "Layer 2", "Background"), names)
        assertTrue(vm.state.value.primary != first)
    }

    @Test
    fun `shape paths create and update an engine-owned history layer`() {
        val paths = app.croptop.feature.editor.path.PathDraftController().apply {
            beginShapePath(DocPoint(10f, 12f), DocPoint(70f, 80f))
        }.state.value.paths

        assertTrue(vm.createShapeLayer(paths))
        val shape = vm.state.value.primaryLayer!!
        assertEquals(LayerKind.SHAPE, shape.kind)
        assertEquals(paths, shape.vectorPaths)

        val edited = paths.map { it.copy(name = "Logo contour") }
        assertTrue(vm.setShapePaths(shape.id, edited))
        assertEquals(edited, vm.state.value.layers.find(shape.id)?.vectorPaths)
        assertTrue(vm.state.value.history.canUndo)
    }

    @Test
    fun `removing final path deletes only its shape layer when multiple layers are selected`() {
        val paths = app.croptop.feature.editor.path.PathDraftController().apply {
            beginShapePath(DocPoint(10f, 12f), DocPoint(70f, 80f))
        }.state.value.paths
        assertTrue(vm.createShapeLayer(paths))
        val shapeId = vm.state.value.primary!!
        vm.newLayer()
        val otherLayerId = vm.state.value.primary!!
        assertTrue(vm.document.value!!.setActiveLayers(setOf(shapeId, otherLayerId), shapeId) is CommandResult.Accepted)

        assertTrue(vm.commitShapePaths(shapeId, emptyList()))

        assertEquals(null, vm.state.value.layers.find(shapeId))
        assertTrue(vm.state.value.layers.find(otherLayerId) != null)
        assertTrue(vm.state.value.history.canUndo)
    }

    @Test
    fun `second draft commit reuses shape id while create result is awaiting tree publication`() {
        val delegate = FakeEngine()
        var createCount = 0
        var updateCount = 0
        var createdId: app.croptop.core.model.LayerId? = null
        val delayedEngine = object : Engine by delegate {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val document = delegate.newDocument(spec)
                return object : DocumentHandle by document {
                    override fun createShapeLayer(
                        id: app.croptop.core.model.LayerId,
                        name: String,
                        paths: List<app.croptop.core.model.VectorPathDraft>,
                    ): CommandResult {
                        createCount++
                        createdId = id
                        return CommandResult.Accepted(900L)
                    }

                    override fun setShapePaths(
                        id: app.croptop.core.model.LayerId,
                        paths: List<app.croptop.core.model.VectorPathDraft>,
                    ): CommandResult {
                        updateCount++
                        assertEquals(createdId, id)
                        return CommandResult.Rejected(RejectionCode.CLOSED, "delayed fixture")
                    }
                }
            }
        }
        val delayedVm = createViewModel(delayedEngine)
        val draft = app.croptop.feature.editor.path.PathDraftController()
        draft.beginShapePath(DocPoint(10f, 12f), DocPoint(70f, 80f))

        assertTrue(delayedVm.commitShapePaths(null, draft.state.value.paths))
        val revised = draft.state.value.paths.map { it.copy(name = "Updated before publication") }
        assertFalse(delayedVm.commitShapePaths(null, revised))

        assertEquals(1, createCount)
        assertEquals(1, updateCount)
    }

    @Test
    fun `Levels picks sample merged color at document coordinates and update only requested input`() {
        val requested = mutableListOf<Triple<app.croptop.core.model.LayerId, DocPoint, Int>>()
        val testVm = createViewModel(sampledEngine(RgbaColor(0.5f, 0.5f, 0.5f)) { layerId, point, size -> requested += Triple(layerId, point, size) })
        assertTrue(testVm.addAdjustmentLayer("levels"))
        val id = testVm.state.value.primary!!

        assertTrue(testVm.sampleLevelsPick(id, LevelsPickTarget.BLACK, DocPoint(20.75f, 30.25f)))

        assertEquals(Triple(id, DocPoint(20.75f, 30.25f), 1), requested.single())
        val adjustment = testVm.state.value.layers.find(id)?.adjustment!!
        val levels = (adjustment.values.getValue("levels") as ParamValue.LevelsValue).v
        assertEquals(0.5f, levels.inBlack, 0.0001f)
        assertEquals(1f, levels.inWhite)
        assertEquals(1f, levels.gamma)
        assertEquals(0f, levels.apply(0.5f), 0.0001f)
        assertEquals("Levels black point set", testVm.state.value.message)

        assertTrue(testVm.sampleLevelsPick(id, LevelsPickTarget.BLACK, DocPoint(20.75f, 30.25f)))
        val pickedAgain = (testVm.state.value.layers.find(id)!!.adjustment!!.values.getValue("levels") as ParamValue.LevelsValue).v
        assertEquals(0.5f, pickedAgain.inBlack, 0.0001f, "sampling below the Levels layer must not drift after the first pick")
        assertEquals(listOf(Triple(id, DocPoint(20.75f, 30.25f), 1), Triple(id, DocPoint(20.75f, 30.25f), 1)), requested)
    }

    @Test
    fun `Levels black gray and white picks map the sampled gray to their selected output`() {
        listOf(
            Triple(LevelsPickTarget.BLACK, RgbaColor(0.2f, 0.5f, 0.8f), 0f),
            Triple(LevelsPickTarget.GRAY, RgbaColor(0.5f, 0.5f, 0.5f), 0.5f),
            Triple(LevelsPickTarget.WHITE, RgbaColor(0.2f, 0.5f, 0.8f), 1f),
        ).forEach { (target, color, expected) ->
            val testVm = createViewModel(sampledEngine(color))
            assertTrue(testVm.addAdjustmentLayer("levels"))
            val id = testVm.state.value.primary!!

            assertTrue(testVm.sampleLevelsPick(id, target, DocPoint(10f, 10f)))

            val levels = (testVm.state.value.layers.find(id)!!.adjustment!!.values.getValue("levels") as ParamValue.LevelsValue).v
            assertEquals(expected, levels.apply(color.r), 0.001f)
            assertEquals(expected, levels.apply(color.g), 0.001f)
            assertEquals(expected, levels.apply(color.b), 0.001f)
        }
    }

    @Test
    fun `Curves canvas target adds a neutral control point at sampled luminance`() {
        val sampled = RgbaColor(0.2f, 0.5f, 0.8f)
        val testVm = createViewModel(sampledEngine(sampled))
        assertTrue(testVm.addAdjustmentLayer("curves"))
        val id = testVm.state.value.primary!!

        val result = testVm.targetAdjustmentFromCanvas(id, "curves", DocPoint(10f, 10f))

        val channels = testVm.state.value.layers.find(id)!!.adjustment!!.values.getValue("curve") as ParamValue.CurveChannelsValue
        val expectedInput = 0.2126f * sampled.r + 0.7152f * sampled.g + 0.0722f * sampled.b
        val point = channels.v.rgb.points.single { kotlin.math.abs(it.x - expectedInput) < 0.0001f }
        assertTrue(result.accepted)
        assertEquals(expectedInput, point.y, 0.0001f)
        assertEquals(3, channels.v.rgb.points.size)
    }

    @Test
    fun `Hue Saturation canvas target selects sampled color range`() {
        val testVm = createViewModel(sampledEngine(RgbaColor(0f, 0f, 1f)))
        assertTrue(testVm.addAdjustmentLayer("hueSaturation"))
        val id = testVm.state.value.primary!!

        val result = testVm.targetAdjustmentFromCanvas(id, "hueSaturation", DocPoint(10f, 10f))

        assertTrue(result.accepted)
        assertEquals(app.croptop.core.model.HueChannel.BLUES, result.hueChannel)
        assertEquals("Targeted blues on canvas", testVm.state.value.message)
    }

    @Test
    fun `Hue Saturation neutral canvas target stays on Master`() {
        val testVm = createViewModel(sampledEngine(RgbaColor(0.5f, 0.5f, 0.5f)))
        assertTrue(testVm.addAdjustmentLayer("hueSaturation"))
        val id = testVm.state.value.primary!!

        val result = testVm.targetAdjustmentFromCanvas(id, "hueSaturation", DocPoint(10f, 10f))

        assertTrue(result.accepted)
        assertEquals(app.croptop.core.model.HueChannel.MASTER, result.hueChannel)
        assertEquals("Targeted master on canvas", testVm.state.value.message)
    }

    @Test
    fun `Levels sample failures keep values and expose sampling error`() {
        val delegate = FakeEngine()
        val engine = object : Engine by delegate {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val document = delegate.newDocument(spec)
                return object : DocumentHandle by document {
                    override fun trySampleBelow(layerId: app.croptop.core.model.LayerId, point: DocPoint, size: Int): ColorSample? =
                        throw IllegalStateException("pixel read failed")
                }
            }
        }
        val testVm = createViewModel(engine)
        assertTrue(testVm.addAdjustmentLayer("levels"))
        val id = testVm.state.value.primary!!
        val before = testVm.state.value.layers.find(id)?.adjustment?.values?.get("levels")

        assertFalse(testVm.sampleLevelsPick(id, LevelsPickTarget.WHITE, DocPoint(10f, 10f)))

        assertEquals(before, testVm.state.value.layers.find(id)?.adjustment?.values?.get("levels"))
        assertEquals("Couldn't sample the canvas: pixel read failed", testVm.state.value.message)
        assertEquals("Couldn't sample the canvas: pixel read failed", testVm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `Levels point outside current input range is explained`() {
        val testVm = createViewModel(sampledEngine(RgbaColor(1f, 1f, 1f)))
        assertTrue(testVm.addAdjustmentLayer("levels"))
        val id = testVm.state.value.primary!!

        assertFalse(testVm.sampleLevelsPick(id, LevelsPickTarget.BLACK, DocPoint(10f, 10f)))

        assertEquals("Picked black point must be below the white input point", testVm.state.value.actionFeedback?.statusText)
        assertEquals(ActionFeedbackPhase.REJECTED, testVm.state.value.actionFeedback?.phase)
    }

    @Test
    fun `group selected wraps and undo restores`() {
        vm.newLayer()
        vm.groupSelected()
        val tree = vm.state.value.layers
        assertEquals("Group 1", tree.layers.first().name)
        assertEquals(listOf("Layer 1"), tree.layers.first().children.map { it.name })
        assertEquals("Group layers", vm.state.value.actionFeedback?.action)
        vm.undo() // move
        vm.undo() // add group
        assertEquals(listOf("Layer 1", "Background"), names)
    }

    @Test
    fun `restack and rejection message`() {
        vm.newLayer()
        vm.restack(1, toEnd = true)
        assertEquals(listOf("Background", "Layer 1"), names)
        vm.select(vm.state.value.layers.flatten().first { it.name == "Background" }.id)
        assertTrue(vm.deleteSelected())
        vm.select(vm.state.value.layers.flatten().first { it.name == "Layer 1" }.id)
        assertFalse(vm.deleteSelected())
        assertEquals("A document needs at least one layer", vm.state.value.message)
    }

    @Test
    fun `no-op layer and crop actions explain their missing precondition`() {
        // `deselect()` clears the pixel selection; clear the engine-owned layer selection here.
        vm.document.value!!.setActiveLayers(emptySet(), null)
        assertFalse(vm.deleteSelected())
        assertEquals("Select a layer to delete", vm.state.value.actionFeedback?.statusText)
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)

        vm.groupSelected()
        assertEquals("Select at least one layer to group", vm.state.value.actionFeedback?.statusText)

        assertFalse(vm.cropToSelection())
        assertEquals("Create a selection before cropping", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `layer rename and boundary move no-ops report their result`() {
        val layer = checkNotNull(vm.state.value.primaryLayer)

        vm.rename(layer.id, "   ")
        assertEquals("Rename layer", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals("A layer name cannot be empty", vm.state.value.actionFeedback?.statusText)

        vm.rename(layer.id, layer.name)
        assertEquals("Rename layer", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
        assertEquals("Layer name is unchanged", vm.state.value.actionFeedback?.statusText)

        vm.rename(layer.id, "Retitled layer")
        assertEquals("Retitled layer", vm.state.value.layers.find(layer.id)?.name)
        assertEquals("Rename ${layer.name}", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)

        vm.restack(delta = 1)
        assertEquals("Move layer", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
        assertEquals("Layer is already at the bottom", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `UI owned engine result enters shared action feedback`() {
        vm.reportUiCommandResult(
            "Apply Curves",
            CommandResult.Rejected(
                app.croptop.core.engine.api.RejectionCode.UNSUPPORTED,
                "Curves cannot be applied in this engine build",
            ),
        )

        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals("Curves cannot be applied in this engine build", vm.state.value.actionFeedback?.statusText)
        assertEquals("Curves cannot be applied in this engine build", vm.state.value.message)
    }

    @Test
    fun `select adjacent explains layer list boundaries`() {
        vm.newLayer()
        vm.select(vm.state.value.layers.flatten().first { it.name == "Background" }.id)

        vm.selectAdjacent(below = true)

        assertEquals("There is no layer below", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `select adjacent success retains its direction in feedback`() {
        vm.newLayer()

        vm.selectAdjacent(below = true)
        assertEquals("Select layer below", vm.state.value.actionFeedback?.action)

        vm.selectAdjacent(below = false)
        assertEquals("Select layer above", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun `mask and retouch no-op preconditions are visible`() {
        val background = vm.state.value.layers.flatten().first { it.name == "Background" }.id
        vm.targetMask(background)
        assertEquals("This layer has no mask", vm.state.value.actionFeedback?.statusText)
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)

        vm.newLayer(LayerKind.GROUP)
        assertNull(vm.beginRetouch(12f, 0.5f, app.croptop.core.engine.api.RetouchEffect.SpotHeal()))
        assertEquals("“Group 1” isn't a pixel layer. Pick a pixel layer to retouch.", vm.state.value.actionFeedback?.statusText)
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
    }

    @Test
    fun `retouch rejects a targeted layer mask`() {
        vm.newLayer()
        val layer = vm.state.value.primary!!
        assertTrue(vm.addMask(id = layer))
        vm.targetMask(layer)
        assertEquals("Target layer mask", vm.state.value.actionFeedback?.action)

        assertNull(vm.beginRetouch(12f, 0.5f, app.croptop.core.engine.api.RetouchEffect.SpotHeal()))

        assertEquals("Select the layer thumbnail to retouch pixels instead of its mask", vm.state.value.actionFeedback?.statusText)
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
    }

    @Test
    fun `layer mask feedback names the target and operation`() {
        vm.newLayer()
        val layer = vm.state.value.primary!!

        assertTrue(vm.addMask(id = layer))
        assertEquals("Add mask to Layer 1", vm.state.value.actionFeedback?.action)

        assertTrue(vm.toggleMask(layer))
        assertEquals("Disable mask on Layer 1", vm.state.value.actionFeedback?.action)

        val disabledMask = vm.state.value.layers.find(layer)!!.mask!!
        assertTrue(vm.setMask(layer, disabledMask.copy(enabled = true)))
        assertEquals("Edit mask on Layer 1", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun `merge down and ungroup explain missing layer preconditions`() {
        vm.document.value!!.setActiveLayers(emptySet(), null)

        assertFalse(vm.mergeDown())
        assertEquals("Select a layer to merge down", vm.state.value.actionFeedback?.statusText)
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)

        assertFalse(vm.ungroup())
        assertEquals("Select a group to ungroup", vm.state.value.actionFeedback?.statusText)
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
    }

    @Test
    fun `solo toggles other layers`() {
        vm.newLayer()
        val id = vm.state.value.primary!!
        vm.solo(id)
        assertEquals(listOf(true, false), vm.state.value.layers.flatten().map { it.visible })
        vm.solo(id)
        assertTrue(vm.state.value.layers.flatten().all { it.visible })
    }

    @Test
    fun `export for sharing writes a png into the share cache`() = kotlinx.coroutines.test.runTest {
        val uri = vm.exportForSharing()
        kotlin.test.assertNotNull(uri)
        assertTrue(uri.endsWith("Untitled.png"))
        assertTrue(java.io.File(uri.removePrefix("file://")).exists())
        assertEquals(null, vm.state.value.busy)
    }

    @Test
    fun `save as clears dirty state and remembers the content uri only after success`() {
        vm.newLayer()
        assertTrue(vm.state.value.dirty)
        val completed = CountDownLatch(1)

        vm.save("content://documents/croptop/Portrait.croptop") { completed.countDown() }

        assertTrue(completed.await(5, TimeUnit.SECONDS), "Save As callback should run after a successful clean save")
        assertTrue(waitForState { vm.state.value.busy == null }, "Save progress should clear from the combined UI state")
        assertFalse(vm.state.value.dirty)
        assertEquals("content://documents/croptop/Portrait.croptop", vm.state.value.saveTarget)
        assertEquals("Saved", vm.state.value.message)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun `save failure preserves dirty state and reports provider error`() {
        val fake = FakeEngine()
        val failing = object : Engine by fake {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val delegate = fake.newDocument(spec)
                return object : DocumentHandle by delegate {
                    override suspend fun save(target: SaveTarget, options: SaveOptions) {
                        throw java.io.IOException("Permission denied: provider is read-only")
                    }
                }
            }
        }
        val failureVm = createViewModel(failing)
        failureVm.newLayer()
        var completed = false

        failureVm.save("content://documents/croptop/Portrait.croptop") { completed = true }

        assertTrue(failureVm.state.value.dirty)
        assertNull(failureVm.state.value.saveTarget)
        assertEquals("Couldn't save: Permission denied: provider is read-only", failureVm.state.value.message)
        assertNull(failureVm.state.value.busy)
        assertFalse(completed)
    }

    @Test
    fun `save keeps newer edits dirty and does not close after a stale snapshot`() {
        val fake = FakeEngine()
        val staleSnapshot = object : Engine by fake {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val delegate = fake.newDocument(spec)
                return object : DocumentHandle by delegate {
                    override suspend fun save(target: SaveTarget, options: SaveOptions) = Unit
                }
            }
        }
        val staleVm = createViewModel(staleSnapshot)
        staleVm.newLayer()
        var completed = false

        staleVm.save("content://documents/croptop/Portrait.croptop") { completed = true }

        assertTrue(waitForState { staleVm.state.value.message == "Saved a version; newer edits remain unsaved" })
        assertTrue(staleVm.state.value.dirty)
        assertEquals("content://documents/croptop/Portrait.croptop", staleVm.state.value.saveTarget)
        assertEquals("Saved a version; newer edits remain unsaved", staleVm.state.value.message)
        assertFalse(completed)
    }

    @Test
    fun `document commands during save keep both save activity and the latest result visible`() {
        val fake = FakeEngine()
        val saveEntered = CountDownLatch(1)
        val allowSave = CompletableDeferred<Unit>()
        val heldSaveEngine = object : Engine by fake {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val delegate = fake.newDocument(spec)
                return object : DocumentHandle by delegate {
                    override suspend fun save(target: SaveTarget, options: SaveOptions) {
                        saveEntered.countDown()
                        allowSave.await()
                    }
                }
            }
        }
        val editingVm = createViewModel(heldSaveEngine)
        editingVm.newLayer()
        val editable = editingVm.state.value.primary!!

        editingVm.save("content://documents/croptop/Portrait.croptop")
        assertTrue(saveEntered.await(5, TimeUnit.SECONDS), "Save should remain in progress while the test edits")
        assertEquals(ActionFeedbackPhase.RUNNING, editingVm.state.value.actionFeedback?.phase)
        assertEquals("Save", editingVm.state.value.actionFeedback?.action)

        editingVm.setVisible(editable, visible = false)
        assertEquals(ActionFeedbackPhase.RUNNING, editingVm.state.value.actionFeedback?.phase)
        assertEquals("Update layer rendered", editingVm.state.value.actionFeedback?.concurrentNotice)

        assertTrue(editingVm.select(editable))
        assertEquals("Layer selected", editingVm.state.value.actionFeedback?.concurrentNotice)

        assertTrue(editingVm.fillWith(foreground = false))
        assertEquals(ActionFeedbackPhase.RUNNING, editingVm.state.value.actionFeedback?.phase)
        assertEquals("Fill with background color rendered", editingVm.state.value.actionFeedback?.concurrentNotice)

        allowSave.complete(Unit)
        assertTrue(waitForState { editingVm.state.value.actionFeedback?.phase == ActionFeedbackPhase.COMPLETED })
        assertEquals("Saved a version; newer edits remain unsaved", editingVm.state.value.actionFeedback?.statusText)
        assertEquals("Fill with background color rendered", editingVm.state.value.actionFeedback?.concurrentNotice)
        assertTrue(editingVm.state.value.dirty)
    }

    @Test
    fun `share copy and drag report typed secondary rejection while save remains active`() =
        kotlinx.coroutines.test.runTest {
            val fake = FakeEngine()
            val saveEntered = CountDownLatch(1)
            val allowSave = CompletableDeferred<Unit>()
            val heldSaveEngine = object : Engine by fake {
                override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                    val delegate = fake.newDocument(spec)
                    return object : DocumentHandle by delegate {
                        override suspend fun save(target: SaveTarget, options: SaveOptions) {
                            saveEntered.countDown()
                            allowSave.await()
                        }
                    }
                }
            }
            val sharingVm = createViewModel(heldSaveEngine)
            sharingVm.newLayer()

            sharingVm.save("content://documents/croptop/Portrait.croptop")
            assertTrue(saveEntered.await(5, TimeUnit.SECONDS), "Save should be running before share actions begin")

            fun assertBusyRejection(action: String, detail: String) {
                val feedback = sharingVm.state.value.actionFeedback!!
                assertEquals(ActionFeedbackPhase.RUNNING, feedback.phase)
                assertEquals("Save", feedback.action)
                assertEquals(ActionFeedbackPhase.REJECTED, feedback.concurrentNoticePhase)
                assertEquals(action, feedback.concurrentNoticeAction)
                assertEquals(RejectionCode.INVALID_ARGUMENT, feedback.concurrentNoticeRejectionCode)
                assertEquals(detail, feedback.concurrentNotice)
            }

            sharingVm.save("content://documents/croptop/Portrait-copy.croptop")
            assertBusyRejection("Save", "Wait for the current action to finish before saving")
            sharingVm.export("content://documents/croptop/Portrait.png", ExportOptions(ExportFormat.PNG))
            assertBusyRejection("Export", "Wait for the current action to finish before exporting")

            val appClipboardBefore = sharingVm.clipboard.value
            var platformClipboard = "existing platform clip"
            assertFalse(
                copyMergedImageToClipboard(
                    prepareImage = { sharingVm.exportForSharing("Copy merged image to clipboard") },
                    copyMerged = { sharingVm.copy(merged = true, quietSuccess = true) },
                    publishToPlatformClipboard = { platformClipboard = it },
                ),
            )
            assertEquals(appClipboardBefore, sharingVm.clipboard.value)
            assertEquals("existing platform clip", platformClipboard)

            val actions = listOf("Share image", "Copy merged image to clipboard", "Drag image to another app")
            actions.forEach { action ->
                assertNull(sharingVm.exportForSharing(action))
                val feedback = sharingVm.state.value.actionFeedback!!
                assertEquals(ActionFeedbackPhase.RUNNING, feedback.phase)
                assertEquals("Save", feedback.action)
                assertEquals(ActionFeedbackPhase.REJECTED, feedback.concurrentNoticePhase)
                assertEquals(action, feedback.concurrentNoticeAction)
                assertEquals(RejectionCode.INVALID_ARGUMENT, feedback.concurrentNoticeRejectionCode)
                assertEquals(
                    "Wait for the current action to finish before preparing the image",
                    feedback.concurrentNotice,
                )
            }

            allowSave.complete(Unit)
            assertTrue(waitForState { sharingVm.state.value.actionFeedback?.phase == ActionFeedbackPhase.COMPLETED })
        }

    @Test
    fun `export reports successful dimensions and codec warnings`() {
        val fake = FakeEngine()
        val exportEngine = object : Engine by fake {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val delegate = fake.newDocument(spec)
                return object : DocumentHandle by delegate {
                    override suspend fun export(target: ExportTarget, options: ExportOptions) =
                        ExportResult(100, 100, 1200, warnings = listOf("Metadata omitted"))
                }
            }
        }
        val exportVm = createViewModel(exportEngine)

        exportVm.export("content://documents/image.png", ExportOptions(ExportFormat.PNG))

        assertEquals("Exported 100 × 100 PNG · Metadata omitted", exportVm.state.value.message)
        assertEquals(ExportFormat.PNG, exportVm.state.value.exportWarning?.format)
        assertEquals(listOf("Metadata omitted"), exportVm.state.value.exportWarning?.warnings)
        exportVm.dismissExportWarning()
        assertNull(exportVm.state.value.exportWarning)
        assertNull(exportVm.state.value.busy)
    }

    @Test
    fun `export failure is visible without changing document dirty state`() {
        val fake = FakeEngine()
        val exportEngine = object : Engine by fake {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val delegate = fake.newDocument(spec)
                return object : DocumentHandle by delegate {
                    override suspend fun export(target: ExportTarget, options: ExportOptions): ExportResult =
                        throw java.io.IOException("No permission to write the destination")
                }
            }
        }
        val exportVm = createViewModel(exportEngine)
        exportVm.newLayer()

        exportVm.export("content://documents/image.png", ExportOptions(ExportFormat.PNG))

        assertEquals("Couldn't export: No permission to write the destination", exportVm.state.value.message)
        assertNull(exportVm.state.value.busy)
        assertTrue(exportVm.state.value.dirty)
    }

    @Test
    fun `export can be canceled and clears progress`() {
        val fake = FakeEngine()
        val slowExportEngine = object : Engine by fake {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val delegate = fake.newDocument(spec)
                return object : DocumentHandle by delegate {
                    override suspend fun export(target: ExportTarget, options: ExportOptions): ExportResult =
                        kotlinx.coroutines.awaitCancellation()
                }
            }
        }
        val exportVm = createViewModel(slowExportEngine)

        exportVm.export("content://documents/image.png", ExportOptions(ExportFormat.PNG))
        assertEquals("Exporting…", exportVm.state.value.busy)

        exportVm.cancelExport()

        assertNull(exportVm.state.value.busy)
        assertEquals("Export canceled", exportVm.state.value.message)
    }

    @Test
    fun `held URI open shows indeterminate progress until it succeeds`() = kotlinx.coroutines.test.runTest {
        val delegate = FakeEngine()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<DocumentHandle>()
        val openingEngine = object : Engine by delegate {
            override suspend fun open(
                source: app.croptop.core.engine.api.DocumentSource,
                options: app.croptop.core.engine.api.OpenOptions,
            ): DocumentHandle {
                entered.complete(Unit)
                return release.await()
            }
        }
        val opening = track(
            EditorViewModel(
                openingEngine,
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(openingEngine),
                { null },
                EditorRoute(sourceUri = "content://x/photo.png"),
            ),
        )

        withTimeout(5_000) { entered.await() }
        assertEquals(ActionFeedbackPhase.RUNNING, opening.state.value.actionFeedback?.phase)
        assertEquals("Open document", opening.state.value.actionFeedback?.action)
        assertNull(opening.state.value.actionFeedback?.progress)
        assertTrue(opening.state.value.loading)

        release.complete(delegate.newDocument(NewDocumentSpec(size = DocSize(100, 100))))
        withTimeout(5_000) {
            opening.state.first { it.info != null && it.actionFeedback?.phase == ActionFeedbackPhase.COMPLETED }
        }
        assertEquals("Document opened", opening.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `URI open success completes with an operation-specific result`() = kotlinx.coroutines.test.runTest {
        val delegate = FakeEngine()
        val openingEngine = object : Engine by delegate {
            override suspend fun open(
                source: app.croptop.core.engine.api.DocumentSource,
                options: app.croptop.core.engine.api.OpenOptions,
            ): DocumentHandle =
                delegate.newDocument(NewDocumentSpec(size = DocSize(100, 100)))
        }
        val opened = track(
            EditorViewModel(
                openingEngine,
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(openingEngine),
                { null },
                EditorRoute(sourceUri = "content://x/photo.png"),
            ),
        )

        withTimeout(5_000) {
            opened.state.first { it.info != null && it.actionFeedback?.phase == ActionFeedbackPhase.COMPLETED }
        }
        assertEquals("Open document", opened.state.value.actionFeedback?.action)
        assertEquals("Document opened", opened.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `URI open forwards route develop settings to the engine options`() = kotlinx.coroutines.test.runTest {
        val engine = FakeEngine()
        val recipe = DevelopSettings(basic = BasicTone(exposure = -1.25f))
        val opened = track(
            EditorViewModel(
                engine,
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(engine),
                { null },
                EditorRoute(sourceUri = "content://photos/photo.raf", rawDevelopSettings = recipe),
            ),
        )

        withTimeout(5_000) { opened.state.first { it.info != null && it.actionFeedback?.phase == ActionFeedbackPhase.COMPLETED } }

        assertEquals(OpenOptions(rawDevelopSettings = recipe), engine.lastOpenOptions)
        assertEquals(recipe, engine.lastRawDevelopSettings)
    }

    @Test
    fun `open failure shows a friendly error and the same failed action detail`() = kotlinx.coroutines.test.runTest {
        val failing = object : app.croptop.core.engine.api.Engine by FakeEngine() {
            override suspend fun open(
                source: app.croptop.core.engine.api.DocumentSource,
                options: app.croptop.core.engine.api.OpenOptions,
            ): app.croptop.core.engine.api.DocumentHandle = throw UnsupportedOperationException("no decoders")
        }
        val broken = track(
            EditorViewModel(
                failing,
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(failing),
                { null },
                EditorRoute(sourceUri = "content://x/photo.cr3"),
            ),
        )
        withTimeout(5_000) {
            broken.state.first { it.openError != null && it.actionFeedback?.phase == ActionFeedbackPhase.FAILED }
        }
        assertEquals("This file type can't be opened yet.", broken.state.value.openError)
        assertEquals(false, broken.state.value.loading)
        assertEquals("Open document", broken.state.value.actionFeedback?.action)
        assertEquals(broken.state.value.openError, broken.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `masks follow photoshop defaults and clipping toggles`() {
        val background = vm.state.value.primary!!
        assertTrue(!vm.addMask())
        assertTrue(vm.state.value.message!!.startsWith("The Background layer can't have a mask"))
        vm.newLayer()
        val layer = vm.state.value.primary!!
        assertTrue(vm.addMask())
        assertEquals(app.croptop.core.model.LayerMaskInfo(), vm.state.value.layers.find(layer)?.mask)
        assertTrue(vm.toggleMask(layer))
        assertEquals(false, vm.state.value.layers.find(layer)?.mask?.enabled)
        assertTrue(vm.toggleClipping())
        assertEquals(true, vm.state.value.layers.find(layer)?.clipped)
        assertTrue(vm.deleteMask(layer, apply = true))
        assertEquals("Apply Layer Mask", vm.state.value.history.entries.last().label)
        assertTrue(!vm.toggleClipping(background))
    }

    @Test
    fun `a new mask becomes the paint target until pixels are targeted`() {
        vm.newLayer()
        val layer = vm.state.value.primary!!
        assertTrue(vm.addMask())
        assertEquals(layer, vm.maskTarget.value)
        val brush = app.croptop.core.model.BrushSettings()
        val maskStroke = checkNotNull(vm.beginStroke(brush, erase = false))
        assertEquals("Brush stroke started", vm.state.value.actionFeedback?.statusText)
        assertTrue(
            maskStroke.run {
                add(FloatArray(7))
                end()
            } is app.croptop.core.engine.api.CommandResult.Accepted,
        )
        fun paint() = vm.beginStroke(brush, erase = false)!!.run {
            add(FloatArray(7))
            end()
        }
        // Deleting the mask falls back to pixels even though the target id is unchanged.
        assertTrue(vm.deleteMask(layer, apply = false))
        assertTrue(!vm.isMaskTargeted(vm.state.value.primaryLayer!!))
        assertTrue(paint() is app.croptop.core.engine.api.CommandResult.Accepted)
        assertTrue(vm.addMask())
        vm.targetPixels(layer)
        assertEquals(null, vm.maskTarget.value)

        // Mask A → layer B → layer A: back on A, pixels are the target until its mask is clicked.
        vm.targetMask(layer)
        assertEquals(layer, vm.maskTarget.value)
        val background = vm.state.value.layers.flatten().first { it.name == "Background" }.id
        vm.select(background)
        vm.select(layer)
        assertEquals(null, vm.maskTarget.value)
        assertTrue(!vm.isMaskTargeted(vm.state.value.primaryLayer!!))
    }

    @Test
    fun `quick mask routes strokes to the selection even without a layer mask`() {
        val document = checkNotNull(vm.document.value)
        assertTrue(vm.toggleQuickMask())
        assertTrue(document.quickMask.value)

        val session = checkNotNull(vm.beginStroke(app.croptop.core.model.BrushSettings(), erase = false))
        val result = session.run {
            add(FloatArray(7))
            end()
        }
        assertTrue(result is CommandResult.Accepted)

        assertTrue(vm.toggleQuickMask())
        assertFalse(document.quickMask.value)
    }

    @Test
    fun `brush and eraser report start and terminal feedback with distinct names`() {
        vm.newLayer()
        val brush = app.croptop.core.model.BrushSettings()

        val brushSession = checkNotNull(vm.beginStroke(brush, erase = false))
        assertEquals("Brush stroke started", vm.state.value.actionFeedback?.statusText)
        vm.strokeResult("Brush stroke", brushSession.end())
        assertEquals("Brush stroke", vm.state.value.actionFeedback?.action)

        val eraseSession = checkNotNull(vm.beginStroke(brush, erase = true))
        assertEquals("Erase stroke started", vm.state.value.actionFeedback?.statusText)
        vm.strokeResult("Erase stroke", eraseSession.end())
        assertEquals("Erase stroke", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun `copy then paste adds a layer and cut needs a selection`() {
        assertTrue(vm.clipboard.value == null)
        assertTrue(!vm.cut())
        assertEquals("Cut needs a selection", vm.state.value.message)
        assertTrue(vm.copy())
        assertEquals(DocSize(100, 100), vm.clipboard.value?.size)
        assertTrue(vm.paste(app.croptop.core.model.DocPoint(50f, 50f)))
        assertEquals(listOf("Layer 1", "Background"), names)
        assertEquals("Paste", vm.state.value.history.entries.last().label)
    }

    @Test
    fun `quiet merged copy populates internal clipboard without publishing intermediate success`() {
        assertTrue(vm.copy(merged = true, quietSuccess = true))
        assertEquals(DocSize(100, 100), vm.clipboard.value?.size)
        assertNull(vm.state.value.actionFeedback)
        assertNull(vm.state.value.message)
    }

    @Test
    fun `quiet merged copy still surfaces a rejected engine command`() {
        val delegate = FakeEngine()
        val rejectingEngine = object : Engine by delegate {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val document = delegate.newDocument(spec)
                return object : DocumentHandle by document {
                    override fun copy(merged: Boolean): CommandResult =
                        CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Merged copy failed")
                }
            }
        }
        val testVm = track(
            EditorViewModel(
                rejectingEngine,
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(rejectingEngine),
                { null },
                EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
            ),
        )

        assertFalse(testVm.copy(merged = true, quietSuccess = true))
        assertEquals("Merged copy failed", testVm.state.value.actionFeedback?.statusText)
        assertEquals(ActionFeedbackPhase.REJECTED, testVm.state.value.actionFeedback?.phase)
    }

    @Test
    fun `solo layer reports one action after quiet visibility updates`() {
        vm.newLayer()
        vm.newLayer()
        val target = vm.state.value.layers.flatten().first()

        vm.solo(target.id)

        val nodes = vm.state.value.layers.flatten()
        assertTrue(nodes.first { it.id == target.id }.visible)
        assertTrue(nodes.filterNot { it.id == target.id }.none { it.visible })
        assertEquals("Solo ${target.name}", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)

        vm.solo(target.id)

        assertTrue(vm.state.value.layers.flatten().all { it.visible })
        assertEquals("Show all layers", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
    }

    @Test
    fun `soloing a hidden layer does not restore every layer`() {
        vm.newLayer()
        vm.newLayer()
        val nodes = vm.state.value.layers.flatten()
        val target = nodes.first()
        nodes.forEach { vm.setVisible(it.id, visible = false, quietSuccess = true) }

        vm.solo(target.id)

        val after = vm.state.value.layers.flatten()
        assertTrue(after.first { it.id == target.id }.visible)
        assertTrue(after.filterNot { it.id == target.id }.none { it.visible })
        assertEquals("Solo ${target.name}", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun `soloing a group keeps its descendants visible and toggles back to all`() {
        vm.newLayer()
        vm.newLayer()
        val children = vm.state.value.layers.flatten().filterNot { it.isGroup }.take(2)
        assertTrue(vm.select(children.first().id))
        assertTrue(vm.select(children.last().id, additive = true))
        vm.groupSelected()
        val group = checkNotNull(vm.state.value.layers.flatten().first { it.isGroup })

        vm.solo(group.id)

        val isolated = vm.state.value.layers.flatten()
        assertTrue(isolated.first { it.id == group.id }.visible)
        assertTrue(group.children.flatMap { child -> listOf(child) + child.children }.all { child -> isolated.first { it.id == child.id }.visible })
        assertTrue(
            isolated.filter {
                it.id != group.id && group.children.none { child -> child.id == it.id }
            }.filterNot { it.isGroup }.none { it.visible },
        )
        assertEquals("Solo ${group.name}", vm.state.value.actionFeedback?.action)

        vm.solo(group.id)

        assertTrue(vm.state.value.layers.flatten().all { it.visible })
        assertEquals("Show all layers", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun `layer visibility feedback names the operation and layer`() {
        val target = vm.state.value.layers.flatten().first()

        vm.setVisible(target.id, visible = false, action = "Hide ${target.name}")

        assertFalse(vm.state.value.layers.flatten().first { it.id == target.id }.visible)
        assertEquals("Hide ${target.name}", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
    }

    @Test
    fun `blend mode feedback names the selected mode`() {
        vm.newLayer()

        vm.setBlendMode(app.croptop.core.model.BlendMode.MULTIPLY)

        assertEquals(app.croptop.core.model.BlendMode.MULTIPLY, vm.state.value.primaryLayer?.blendMode)
        assertEquals("Set blend mode to Multiply", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun `quick selection reports stroke start and cancellation`() {
        val session = vm.beginQuickSelection(
            app.croptop.core.engine.api.QuickSelectionSpec(12f, app.croptop.core.model.SelectionOp.REPLACE),
        )

        assertNotNull(session)
        assertEquals("Quick selection stroke started", vm.state.value.actionFeedback?.statusText)
        vm.cancelQuickSelection()
        assertEquals("Quick selection canceled", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `canceling an active stroke reports the canceled stroke label`() {
        vm.cancelStroke("Erase stroke")

        assertEquals("Cancel Erase stroke", vm.state.value.actionFeedback?.action)
        assertEquals("Erase stroke canceled", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `subject and object selections run as jobs and clear the busy label`() {
        assertTrue(vm.selectSubject())
        val subject = (vm.state.value.selection as app.croptop.core.model.SelectionInfo.Active).bounds
        assertEquals(app.croptop.core.model.DocRect(20, 20, 80, 80), subject)
        assertEquals(null, vm.state.value.busy)
        assertTrue(
            vm.selectObject(
                app.croptop.core.model.SelectionShape.Rect(app.croptop.core.model.DocRectF(5f, 5f, 30f, 30f)),
                app.croptop.core.model.SelectionOp.REPLACE,
                sampleAll = false,
            ),
        )
        assertEquals(app.croptop.core.model.DocRect(5, 5, 30, 30), (vm.state.value.selection as app.croptop.core.model.SelectionInfo.Active).bounds)
        assertEquals("Object Selection", vm.state.value.history.entries.last().label)
        // Nothing runs, so Esc falls through to other handlers.
        assertTrue(!vm.cancelSelectionJob())
    }

    @Test
    fun `select menu, canvas and merge actions go through the engine`() {
        assertTrue(vm.selectAll())
        assertTrue(vm.state.value.hasSelection)
        assertTrue(vm.modifySelection(app.croptop.core.model.SelectionModify.CONTRACT, 10f))
        assertEquals("Contract selection", vm.state.value.actionFeedback?.action)
        assertEquals(app.croptop.core.model.DocRect(10, 10, 90, 90), (vm.state.value.selection as app.croptop.core.model.SelectionInfo.Active).bounds)
        assertTrue(vm.cropToSelection())
        assertEquals(DocSize(80, 80), vm.state.value.info?.size)
        assertTrue(!vm.state.value.hasSelection)
        assertTrue(vm.rotateCanvas(90f))
        vm.resizeCanvas(app.croptop.core.model.CanvasResize(DocSize(80, 80), DocSize(120, 80)), app.croptop.core.model.BackgroundContents.WHITE)
        assertEquals(DocSize(120, 80), vm.state.value.info?.size)
        vm.undo()
        assertEquals(DocSize(80, 80), vm.state.value.info?.size)
        vm.newLayer()
        val before = vm.state.value.layers.size
        assertTrue(vm.mergeDown())
        assertEquals(before - 1, vm.state.value.layers.size)
        assertTrue(vm.stampVisible())
        assertEquals("Stamp Visible", vm.state.value.history.entries.last().label)
        // Rejections surface as the status message instead of failing silently.
        assertTrue(!vm.deselect())
        assertEquals("Nothing is selected", vm.state.value.message)
    }

    @Test
    fun `named snapshots from the history panel`() {
        assertTrue(vm.createSnapshot("Clean", action = "Create Clean"))
        assertEquals("Create Clean", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
        vm.newLayer()
        assertTrue(vm.jumpToSnapshot(0, action = "Restore Clean"))
        assertEquals("Restore Clean", vm.state.value.actionFeedback?.action)
        assertEquals(listOf("Background"), names)
        assertTrue(vm.renameSnapshot(0, "Start", action = "Rename Clean"))
        assertEquals("Rename Clean", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
        assertEquals("Start", vm.state.value.history.snapshots.single().name)
        assertTrue(vm.deleteSnapshot(0, action = "Delete Start"))
        assertEquals("Delete Start", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
    }

    @Test
    fun `state-only layer selection reports completion without an engine event`() {
        val layer = vm.state.value.layers.flatten().first().id

        assertTrue(vm.select(layer))

        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
        assertEquals("Layer selected", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `unsupported UI action preserves typed reason in shared feedback`() {
        val reason = "This engine build does not support this adjustment"

        vm.reportUiActionUnsupported("Levels", reason)

        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.UNSUPPORTED, vm.state.value.actionFeedback?.rejectionCode)
        assertEquals("Levels", vm.state.value.actionFeedback?.action)
        assertEquals(reason, vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `unsupported Color Range retains the engine reason for its caller`() {
        val delegate = FakeEngine()
        val unsupportedEngine = object : Engine by delegate {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val document = delegate.newDocument(spec)
                return object : DocumentHandle by document {
                    override fun beginColorRange(op: app.croptop.core.model.SelectionOp) =
                        app.croptop.core.engine.api.UnsupportedColorRangeSession()
                }
            }
        }
        val testVm = createViewModel(unsupportedEngine)

        assertNull(testVm.beginColorRange())
        assertEquals(ActionFeedbackPhase.REJECTED, testVm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.UNSUPPORTED, testVm.state.value.actionFeedback?.rejectionCode)
        assertEquals("Select color range", testVm.state.value.actionFeedback?.action)
        assertEquals("Color Range isn't available in this build yet.", testVm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `invalid UI precondition remains an accessible typed rejection`() {
        val detail = "There is no previous transform to repeat"

        vm.reportUiActionRejected("Transform Again", RejectionCode.INVALID_ARGUMENT, detail)

        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        assertEquals("Transform Again", vm.state.value.actionFeedback?.action)
        assertEquals(detail, vm.state.value.actionFeedback?.statusText)
        assertEquals(detail, vm.state.value.message)
    }

    @Test
    fun `toggling off the last selected layer clears the selection in the engine`() {
        val base = vm.state.value.primary!!
        vm.select(base)
        vm.select(base, additive = true)
        assertEquals(emptySet(), vm.state.value.selected)
        assertEquals(emptySet(), vm.state.value.layers.active)
    }

    @Test
    fun `engine selection stays reflected after undo and redo`() {
        vm.newLayer()
        val first = vm.state.value.primary!!
        vm.newLayer()
        val second = vm.state.value.primary!!
        assertTrue(vm.select(first))

        vm.setOpacity(0.5f)
        assertTrue(vm.undo() is CommandResult.Accepted)
        assertEquals(setOf(first), vm.state.value.selected)
        assertEquals(setOf(first), vm.state.value.layers.active)
        assertTrue(vm.redo() is CommandResult.Accepted)
        assertEquals(setOf(first), vm.state.value.selected)
        assertEquals(setOf(first), vm.state.value.layers.active)
        assertEquals(second, vm.state.value.layers.flatten().first().id)
    }

    @Test
    fun `rejected engine selection preserves engine state and active mask target`() {
        val delegate = FakeEngine()
        var rejectSelection = false
        val engine = object : Engine by delegate {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val document = delegate.newDocument(spec)
                return object : DocumentHandle by document {
                    override fun setActiveLayers(ids: Set<LayerId>, primary: LayerId?): CommandResult =
                        if (rejectSelection) {
                            CommandResult.Rejected(app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT, "selection denied")
                        } else {
                            document.setActiveLayers(ids, primary)
                        }
                }
            }
        }
        val testVm = createViewModel(engine)
        testVm.newLayer()
        val id = testVm.state.value.primary!!
        assertTrue(testVm.addMask(id = id))
        testVm.targetMask(id)
        assertTrue(testVm.isMaskTargeted(testVm.state.value.layers.find(id)!!))

        val other = testVm.state.value.layers.flatten().first { it.id != id }.id
        rejectSelection = true
        assertFalse(testVm.select(other))

        assertEquals(setOf(id), testVm.state.value.selected)
        assertEquals(setOf(id), testVm.state.value.layers.active)
        assertEquals(id, testVm.state.value.primary)
        assertTrue(testVm.isMaskTargeted(testVm.state.value.layers.find(id)!!))
        assertEquals("selection denied", testVm.state.value.message)

        testVm.targetPixels(other)
        assertTrue(testVm.isMaskTargeted(testVm.state.value.layers.find(id)!!))
    }

    @Test
    fun `panel selection always follows engine state through undo and redo`() {
        vm.newLayer()
        val added = vm.state.value.primary!!
        val background = vm.state.value.layers.flatten().last().id
        vm.select(background, additive = true)
        assertEquals(vm.state.value.layers.active, vm.state.value.selected)
        assertEquals(vm.state.value.layers.primary, vm.state.value.primary)

        assertTrue(vm.undo() is CommandResult.Accepted)
        assertEquals(vm.state.value.layers.active, vm.state.value.selected)
        assertEquals(vm.state.value.layers.primary, vm.state.value.primary)
        assertFalse(added in vm.state.value.layers.active)

        assertTrue(vm.redo() is CommandResult.Accepted)
        assertEquals(vm.state.value.layers.active, vm.state.value.selected)
        assertEquals(vm.state.value.layers.primary, vm.state.value.primary)
        assertTrue(added in vm.state.value.layers.active)
    }

    @Test
    fun `ellipse outline follows history and a rejected rename is reported`() {
        val ellipse = app.croptop.core.model.SelectionShape.Ellipse(app.croptop.core.model.DocRectF(10f, 10f, 60f, 40f))
        assertTrue(vm.selectShape(ellipse, app.croptop.core.model.SelectionOp.REPLACE))
        assertEquals(ellipse, vm.selectionShape.value)
        vm.undo()
        assertEquals(null, vm.selectionShape.value) // stale shape isn't shown for the earlier step
        vm.redo()
        assertEquals(ellipse, vm.selectionShape.value)
        assertTrue(vm.createSnapshot("One"))
        assertTrue(vm.createSnapshot("Two"))
        assertTrue(!vm.renameSnapshot(1, "one"))
        assertEquals("A snapshot with that name already exists", vm.state.value.message)
    }

    @Test
    fun `shape selection feedback preserves the invoking action label`() {
        val ellipse = app.croptop.core.model.SelectionShape.Ellipse(app.croptop.core.model.DocRectF(10f, 10f, 60f, 40f))

        assertTrue(vm.selectShape(ellipse, app.croptop.core.model.SelectionOp.REPLACE, action = "Path to selection"))

        assertEquals("Path to selection", vm.state.value.actionFeedback?.action)
        assertEquals("Path to selection rendered", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `editor selection state reflects the document selection after Applied`() = runBlocking {
        val shape = app.croptop.core.model.SelectionShape.Ellipse(
            app.croptop.core.model.DocRectF(12f, 13f, 41f, 52f),
        )
        assertTrue(vm.selectShape(shape, app.croptop.core.model.SelectionOp.REPLACE))

        val document = checkNotNull(vm.document.value)
        val applied = document.events.first { event ->
            event is EngineEvent.Applied && event.label == "Elliptical Marquee"
        }

        assertTrue(applied is EngineEvent.Applied)
        assertEquals(
            app.croptop.core.model.SelectionInfo.Active(app.croptop.core.model.DocRect(12, 13, 41, 52)),
            vm.state.value.selection,
        )
        assertEquals(document.selection.value, vm.state.value.selection)
    }

    @Test
    fun `selection operation is forwarded through the ViewModel`() {
        val document = checkNotNull(vm.document.value)
        val outer = app.croptop.core.model.SelectionShape.Rect(
            app.croptop.core.model.DocRectF(10f, 10f, 60f, 60f),
        )
        val inner = app.croptop.core.model.SelectionShape.Rect(
            app.croptop.core.model.DocRectF(20f, 25f, 40f, 45f),
        )

        assertTrue(vm.selectShape(outer, app.croptop.core.model.SelectionOp.REPLACE))
        assertTrue(vm.selectShape(inner, app.croptop.core.model.SelectionOp.INTERSECT))
        assertEquals(
            app.croptop.core.model.SelectionInfo.Active(app.croptop.core.model.DocRect(20, 25, 40, 45)),
            document.selection.value,
        )
    }

    @Test
    fun `layer opacity slider previews quietly and reports one settled action`() {
        vm.newLayer()
        val beforePreview = vm.state.value.actionFeedback

        assertTrue(vm.previewLayerOpacity(54f))
        assertTrue(vm.previewLayerOpacity(63f))
        assertEquals(beforePreview, vm.state.value.actionFeedback)

        vm.finishLayerOpacityEdit(63f)

        assertEquals("Set layer opacity", vm.state.value.actionFeedback?.action)
        assertEquals("Layer opacity set to 63%", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun `adjustment layer edits preview then settle into one step`() = kotlinx.coroutines.test.runTest {
        assertTrue(vm.addAdjustmentLayer("brightnessContrast"))
        val id = vm.state.value.primary!!
        assertEquals("brightnessContrast", vm.state.value.primaryLayer?.adjustment?.specId)
        val settledFeedbackBeforePreview = vm.state.value.actionFeedback
        val steps = vm.state.value.history.entries.size
        vm.editAdjustment(id, mapOf("contrast" to app.croptop.core.model.ParamValue.Num(10f)))
        vm.editAdjustment(id, mapOf("contrast" to app.croptop.core.model.ParamValue.Num(25f)), "Set contrast")
        assertEquals(steps, vm.state.value.history.entries.size) // still previewing
        assertEquals(settledFeedbackBeforePreview, vm.state.value.actionFeedback) // pointer-rate previews stay quiet
        kotlinx.coroutines.delay(1_000)
        assertEquals(steps + 1, vm.state.value.history.entries.size)
        assertEquals(app.croptop.core.model.ParamValue.Num(25f), vm.state.value.primaryLayer?.adjustment?.values?.get("contrast"))
        assertEquals("Set contrast", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun `delete clears with a selection and ctrl+j duplicates without one`() {
        vm.newLayer()
        assertTrue(vm.layerViaCopy())
        assertEquals("Duplicate Layer", vm.state.value.history.entries.last().label)
        vm.selectAll()
        assertTrue(vm.deleteOrClear())
        assertEquals("Clear", vm.state.value.history.entries.last().label)
        assertTrue(vm.fillWith(foreground = true))
        assertEquals("Fill", vm.state.value.history.entries.last().label)
    }

    @Test
    fun `saving smart object contents replaces parent source and closes child route`() {
        val engine = FakeEngine()
        val openDocuments = app.croptop.core.agent.OpenDocuments(engine)
        val parent = track(
            EditorViewModel(
                engine,
                NoRecents,
                TempShareCache,
                openDocuments,
                { null },
                EditorRoute(newSpec = NewDocumentSpec(name = "Smart Object Parent", size = DocSize(64, 64))),
            ),
        )
        parent.newLayer(LayerKind.PIXEL)
        val layerId = checkNotNull(parent.state.value.primary)
        assertTrue(parent.convertToSmartObject())
        val previousSource = checkNotNull(parent.state.value.layers.find(layerId)?.smartObject?.sourceKey)
        val childRoute = checkNotNull(parent.openSmartObjectContents(layerId))
        val child = track(
            EditorViewModel(engine, NoRecents, TempShareCache, openDocuments, { null }, childRoute),
        )
        child.newLayer(LayerKind.PIXEL)

        var childRouteClosed = false
        assertTrue(child.saveSmartObjectContents { childRouteClosed = true })

        assertTrue(childRouteClosed)
        assertEquals("Edit Smart Object Contents", parent.state.value.history.entries.last().label)
        val updatedSource = checkNotNull(parent.state.value.layers.find(layerId)?.smartObject?.sourceKey)
        assertTrue(updatedSource != previousSource)
    }

    @Test
    fun `replacement source route returns to the selected smart object on save`() {
        val engine = FakeEngine()
        val openDocuments = app.croptop.core.agent.OpenDocuments(engine)
        val parent = track(
            EditorViewModel(
                engine,
                NoRecents,
                TempShareCache,
                openDocuments,
                { null },
                EditorRoute(newSpec = NewDocumentSpec(name = "Replacement Parent", size = DocSize(64, 64))),
            ),
        )
        parent.newLayer(LayerKind.PIXEL)
        val layerId = checkNotNull(parent.state.value.primary)
        assertTrue(parent.convertToSmartObject())

        val childRoute = checkNotNull(parent.smartObjectReplacementRoute(layerId, "content://replacement/source"))

        assertEquals("content://replacement/source", childRoute.sourceUri)
        assertEquals(parent.state.value.info?.id?.value, childRoute.smartObjectParentDocumentId)
        assertEquals(layerId.value, childRoute.smartObjectParentLayerId)
    }

    @Test
    fun `saving a source-uri replacement route replaces its parent smart object`() {
        val engine = FakeEngine()
        val openDocuments = app.croptop.core.agent.OpenDocuments(engine)
        val parent = track(
            EditorViewModel(
                engine,
                NoRecents,
                TempShareCache,
                openDocuments,
                { null },
                EditorRoute(newSpec = NewDocumentSpec(name = "Replacement Parent", size = DocSize(64, 64))),
            ),
        )
        parent.newLayer(LayerKind.PIXEL)
        val layerId = checkNotNull(parent.state.value.primary)
        assertTrue(parent.convertToSmartObject())
        val previousSource = checkNotNull(parent.state.value.layers.find(layerId)?.smartObject?.sourceKey)
        val route = checkNotNull(parent.smartObjectReplacementRoute(layerId, "content://replacement/source.png"))
        val child = track(
            EditorViewModel(engine, NoRecents, TempShareCache, openDocuments, { null }, route),
        )
        assertTrue(waitForState { child.state.value.info != null }, "replacement source should open before Save")
        child.newLayer(LayerKind.PIXEL)

        var childRouteClosed = false
        assertTrue(child.saveSmartObjectContents { childRouteClosed = true })

        assertTrue(childRouteClosed)
        assertEquals("Edit Smart Object Contents", parent.state.value.history.entries.last().label)
        val replacedSource = checkNotNull(parent.state.value.layers.find(layerId)?.smartObject?.sourceKey)
        assertTrue(replacedSource != previousSource)
    }
}
