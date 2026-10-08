package app.croptop.feature.editor.session

import app.croptop.core.ai.AiEditActions
import app.croptop.core.ai.AiFeature
import app.croptop.core.ai.AiRuntime
import app.croptop.core.ai.ModelFile
import app.croptop.core.ai.ModelLicense
import app.croptop.core.ai.ModelManagerState
import app.croptop.core.ai.ModelSpec
import app.croptop.core.ai.ModelTask
import app.croptop.core.ai.PromptSegmenter
import app.croptop.core.data.DocumentNames
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.agent.OpenDocuments
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RecentDocument
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionOp
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.files.ShareCache
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

class EditorAiSelectionTest {
    private val spec = ModelSpec(
        id = "deeplab-v3",
        version = 1,
        task = ModelTask.SEGMENT_SUBJECT,
        displayName = "Subject selection",
        files = listOf(ModelFile("m.tflite", "0".repeat(64), 1_000_000, "https://example.com/m.tflite")),
        license = ModelLicense("Apache-2.0", true),
    )

    private class FakeAi(var availability: AiRuntime.Availability) : AiEditActions {
        override val models = MutableStateFlow(ModelManagerState(emptyList(), 0L))
        val calls = mutableListOf<String>()
        val installs = mutableListOf<String>()

        override fun install(id: String) {
            installs += id
        }

        override fun cancelInstall(id: String) = Unit

        override fun availability(feature: AiFeature) = availability

        private fun accept(name: String): CommandResult {
            calls += name
            return CommandResult.Accepted(1)
        }

        override suspend fun selectSubject(document: DocumentHandle, op: SelectionOp) = accept("subject")

        override suspend fun selectBackground(document: DocumentHandle, op: SelectionOp) = accept("background")

        override suspend fun selectSky(document: DocumentHandle, op: SelectionOp) = accept("sky")

        override suspend fun estimateDepth(document: DocumentHandle) = accept("depth")

        override suspend fun removeSelection(document: DocumentHandle) = accept("remove")

        override suspend fun removeBackground(document: DocumentHandle) = accept("removeBackground")

        override suspend fun removeStroke(document: DocumentHandle, points: List<app.croptop.core.model.DocPoint>, radius: Float) = accept("stroke${points.size}@$radius")

        override suspend fun segment(source: app.croptop.core.ai.AiMaskSource, image: app.croptop.core.ai.RgbaImage): app.croptop.core.ai.SegmentOutcome =
            app.croptop.core.ai.SegmentOutcome.Failed("not used here")

        override suspend fun denoise(document: DocumentHandle, strength: Float, progress: (Float) -> Unit): CommandResult {
            progress(1f)
            return accept("denoise@$strength")
        }

        override suspend fun upscale(document: DocumentHandle, factor: Int, progress: (Float) -> Unit) = accept("upscale@$factor")

        override suspend fun selectObject(document: DocumentHandle, clicks: List<PromptSegmenter.Click>, op: SelectionOp): CommandResult {
            calls += "object@${clicks.single().x},${clicks.single().y}"
            return CommandResult.Accepted(1)
        }
    }

    private object Recents : RecentDocumentsRepository {
        override fun observe(limit: Int) = flowOf(emptyList<RecentDocument>())
        override suspend fun recordOpened(uri: String, name: String, size: DocSize?, now: Long) = Unit
        override suspend fun remove(uri: String) = Unit
        override suspend fun setPinned(uri: String, pinned: Boolean) = Unit
    }

    private object Cache : ShareCache {
        override fun newFile(name: String) = java.io.File.createTempFile("s", name)
        override fun uriFor(file: java.io.File) = file.toURI().toString()
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(ai: AiEditActions): EditorViewModel {
        val engine = FakeEngine()
        return EditorViewModel(
            engine, Recents, Cache, OpenDocuments(engine), DocumentNames { null },
            EditorRoute(newSpec = NewDocumentSpec("AI", DocSize(400, 300))), ai,
        )
    }

    @Test
    fun `a ready model runs the feature as one dispatched action`() {
        val ai = FakeAi(AiRuntime.Availability.Ready)
        val vm = viewModel(ai)
        assertTrue(vm.selectSubject())
        assertTrue(vm.selectSky())
        assertTrue(vm.selectBackground())
        assertTrue(vm.estimateDepth())
        assertTrue(vm.removeSelection())
        assertTrue(vm.removeStroke(listOf(DocPoint(10f, 10f), DocPoint(20f, 12f)), 6f))
        assertEquals(listOf("subject", "sky", "background", "depth", "remove", "stroke2@6.0"), ai.calls)
        assertNull(vm.aiPrompt.value)
    }

    @Test
    fun `remove background is free and unlimited`() {
        val ai = FakeAi(AiRuntime.Availability.Ready)
        val vm = viewModel(ai)
        assertTrue(vm.removeBackground())
        assertTrue(vm.removeBackground())
        assertEquals(listOf("removeBackground", "removeBackground"), ai.calls)
    }

    @Test
    fun `denoise and super resolution run as cancelable actions and ask for their model first`() {
        val ai = FakeAi(AiRuntime.Availability.Ready)
        val vm = viewModel(ai)
        assertTrue(vm.runAiRestoration(app.croptop.core.ai.AiFeature.DENOISE, strength = 0.5f))
        assertEquals(listOf("denoise@0.5"), ai.calls)
        val missing = FakeAi(AiRuntime.Availability.NeedsDownload(listOf(spec), 1_000_000))
        val second = viewModel(missing)
        assertFalse(second.runAiRestoration(app.croptop.core.ai.AiFeature.UPSCALE, factor = 4))
        assertEquals(app.croptop.core.ai.AiFeature.UPSCALE, second.aiPrompt.value?.feature)
        assertTrue(missing.calls.isEmpty())
    }

    @Test
    fun `a click with the object tool sends the click as a fraction of the picture`() {
        val ai = FakeAi(AiRuntime.Availability.Ready)
        val vm = viewModel(ai)
        assertTrue(vm.selectObjectAt(DocPoint(100f, 150f)))
        assertEquals(listOf("object@0.25,0.5"), ai.calls)
    }

    @Test
    fun `a missing model opens the download sheet instead of running, and finishing it re-runs the feature`() {
        val ai = FakeAi(AiRuntime.Availability.NeedsDownload(listOf(spec), spec.totalBytes))
        val vm = viewModel(ai)
        assertEquals(false, vm.selectSubject())
        val prompt = assertNotNull(vm.aiPrompt.value)
        assertEquals(AiFeature.SELECT_SUBJECT, prompt.feature)
        assertEquals(listOf("deeplab-v3"), prompt.models.map { it.id })
        assertTrue(ai.calls.isEmpty())
        vm.installAiModel("deeplab-v3")
        assertEquals(listOf("deeplab-v3"), ai.installs)
        ai.availability = AiRuntime.Availability.Ready
        vm.dismissAiPrompt()
        prompt.retry()
        assertEquals(listOf("subject"), ai.calls)
        assertNull(vm.aiPrompt.value)
    }

    @Test
    fun `not now falls back to the basic selector and an unavailable runtime uses it directly`() {
        val ai = FakeAi(AiRuntime.Availability.NeedsDownload(listOf(spec), spec.totalBytes))
        val vm = viewModel(ai)
        vm.selectSubject()
        assertNotNull(vm.aiPrompt.value)
        vm.dismissAiPrompt(useBasic = true)
        assertNull(vm.aiPrompt.value)
        assertTrue(vm.state.value.selection is SelectionInfo.Active, "the basic subject selector ran")
        val none = viewModel(app.croptop.core.ai.UnavailableAi)
        assertTrue(none.selectSubject(), "without a runtime the basic selector is used")
        assertEquals(false, none.selectSky(), "sky has no basic fallback")
    }
}
