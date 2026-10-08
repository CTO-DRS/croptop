package app.croptop.feature.develop

import app.croptop.core.ai.AiEditActions
import app.croptop.core.ai.AiFeature
import app.croptop.core.ai.AiMaskSource
import app.croptop.core.ai.AiRuntime
import app.croptop.core.ai.MaskResult
import app.croptop.core.ai.ModelFile
import app.croptop.core.ai.ModelLicense
import app.croptop.core.ai.ModelManagerState
import app.croptop.core.ai.ModelSpec
import app.croptop.core.ai.ModelTask
import app.croptop.core.ai.PromptSegmenter
import app.croptop.core.ai.RgbaImage
import app.croptop.core.ai.SegmentOutcome
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.MaskKind
import app.croptop.core.model.SelectionOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

class DevelopAiMaskTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val spec = ModelSpec(
        id = "deeplab-v3", version = 1, task = ModelTask.SEGMENT_SUBJECT, displayName = "Subject selection",
        files = listOf(ModelFile("m.tflite", "0".repeat(64), 1_000_000, "https://example.com/m.tflite")),
        license = ModelLicense("Apache-2.0", true),
    )

    private class FakeAi(var availability: AiRuntime.Availability, var outcome: SegmentOutcome) : AiEditActions {
        override val models = MutableStateFlow(ModelManagerState(emptyList(), 0L))
        val seen = mutableListOf<Pair<AiMaskSource, Int>>()
        val installs = mutableListOf<String>()
        override fun install(id: String) { installs += id }
        override fun cancelInstall(id: String) = Unit
        override fun availability(feature: AiFeature) = availability
        override suspend fun segment(source: AiMaskSource, image: RgbaImage): SegmentOutcome {
            seen += source to image.width
            return outcome
        }
        override suspend fun selectSubject(document: DocumentHandle, op: SelectionOp) = CommandResult.Accepted(1)
        override suspend fun selectBackground(document: DocumentHandle, op: SelectionOp) = CommandResult.Accepted(1)
        override suspend fun selectSky(document: DocumentHandle, op: SelectionOp) = CommandResult.Accepted(1)
        override suspend fun selectObject(document: DocumentHandle, clicks: List<PromptSegmenter.Click>, op: SelectionOp) = CommandResult.Accepted(1)
        override suspend fun estimateDepth(document: DocumentHandle) = CommandResult.Accepted(1)
        override suspend fun removeSelection(document: DocumentHandle) = CommandResult.Accepted(1)
        override suspend fun removeStroke(document: DocumentHandle, points: List<app.croptop.core.model.DocPoint>, radius: Float) = CommandResult.Accepted(1)
        override suspend fun removeBackground(document: DocumentHandle) = CommandResult.Accepted(1)
        override suspend fun denoise(document: DocumentHandle, strength: Float, progress: (Float) -> Unit) = CommandResult.Accepted(1)
        override suspend fun upscale(document: DocumentHandle, factor: Int, progress: (Float) -> Unit) = CommandResult.Accepted(1)
    }

    private fun vm(ai: AiEditActions) = DevelopViewModel(MemoryDevelopRepo(), FakeEngine(), "content://x/photo.dng", null, ai)

    private fun coarse(w: Int = 512, h: Int = 512) = MaskResult(w, h, ByteArray(w * h) { if (it % w < w / 2) -1 else 0 })

    @Test
    fun `an AI mask is a stored small bitmap made from the picture as shown, selected and undoable`() {
        val ai = FakeAi(AiRuntime.Availability.Ready, SegmentOutcome.Found(coarse()))
        val vm = vm(ai)
        vm.addAiMask(AiMaskSource.SUBJECT)
        val mask = vm.settings.value.masks.single()
        assertEquals("Subject", mask.name)
        val component = mask.components.single()
        assertEquals(MaskKind.AI, component.kind)
        val bitmap = assertNotNull(component.bitmap)
        assertEquals(AiMaskBitmaps.STORED_SIDE, bitmap.width)
        val bytes = bitmap.bytes()
        assertTrue((bytes[10].toInt() and 0xFF) > 200 && (bytes[bitmap.width - 10].toInt() and 0xFF) < 50, "the left half stays inside, the right outside")
        assertEquals(0, vm.selectedMask.value)
        assertEquals(1, ai.seen.size)
        vm.addAiMask(AiMaskSource.SUBJECT)
        assertEquals("Subject 2", vm.settings.value.masks.last().name)
        vm.undo()
        assertEquals(1, vm.settings.value.masks.size)
    }

    @Test
    fun `a missing model asks for a download first and runs again once it is there`() {
        val ai = FakeAi(AiRuntime.Availability.NeedsDownload(listOf(spec), 1_000_000), SegmentOutcome.Found(coarse()))
        val vm = vm(ai)
        vm.addAiMask(AiMaskSource.BACKGROUND)
        assertEquals(AiMaskSource.BACKGROUND, vm.aiPrompt.value?.source)
        assertTrue(vm.settings.value.masks.isEmpty() && ai.seen.isEmpty())
        ai.availability = AiRuntime.Availability.Ready
        vm.retryAiPrompt()
        assertNull(vm.aiPrompt.value)
        assertEquals("Background", vm.settings.value.masks.single().name)
    }

    @Test
    fun `nothing found and an unavailable runtime leave a note and no mask`() {
        val none = FakeAi(AiRuntime.Availability.Ready, SegmentOutcome.Failed("Couldn't find a subject in this picture"))
        val first = vm(none)
        first.addAiMask(AiMaskSource.SUBJECT)
        assertEquals("Couldn't find a subject in this picture", first.aiNotice.value)
        assertTrue(first.settings.value.masks.isEmpty())
        val off = vm(FakeAi(AiRuntime.Availability.Unavailable("AI features are not available in this build"), SegmentOutcome.Failed("x")))
        off.addAiMask(AiMaskSource.SKY)
        assertEquals("AI features are not available in this build", off.aiNotice.value)
        assertTrue(off.settings.value.masks.isEmpty())
    }

    @Test
    fun `fitting keeps a small mask as is and shrinks a big one to the stored side`() {
        val small = AiMaskBitmaps.fit(coarse(40, 30), "Sky")
        assertEquals(40 to 30, small.width to small.height)
        val big = AiMaskBitmaps.fit(coarse(512, 256), "Sky")
        assertEquals(128 to 64, big.width to big.height)
        assertEquals("Sky", big.label)
    }
}
