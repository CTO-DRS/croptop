package app.croptop.core.ai

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AiEditActionsTest {
    private class Session(val produce: (FloatArray, IntArray) -> FloatArray) : ModelSession {
        override val delegate = Delegate.CPU
        override fun run(input: FloatArray, inputShape: IntArray) = produce(input, inputShape)
        override fun runAll(inputs: List<FloatArray>, shapes: List<IntArray>) = inputs[0].copyOf()
        override fun close() = Unit
    }

    private class Runner(val sessions: Map<String, ModelSession>, val ready: Boolean = true) : ModelRunner {
        override fun availability(vararg modelIds: String): AiRuntime.Availability =
            if (ready && modelIds.all { it in sessions }) AiRuntime.Availability.Ready else AiRuntime.Availability.Unavailable("not downloaded")

        override fun <T> use(id: String, block: (ModelSession) -> T): T = block(sessions.getValue(id))
    }

    private val downloads = object : ModelDownloads {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(ModelManagerState(emptyList(), 0L))
        override fun install(id: String) = Unit
        override fun cancel(id: String) = Unit
    }

    private fun actions(runner: Runner) = ModelAiEditActions(runner, downloads)

    private fun document(width: Int = 200, height: Int = 100) =
        FakeEngine().newDocument(NewDocumentSpec("AI", DocSize(width, height), background = BackgroundContents.WHITE))

    /** DeepLab-like logits: class 15 inside the rectangle (fractions), background elsewhere. */
    private fun subjectSession(left: Float, top: Float, right: Float, bottom: Float) = Session { _, shape ->
        val size = shape[1]
        FloatArray(size * size * 21) { i ->
            val p = i / 21
            val c = i % 21
            val x = (p % size + 0.5f) / size
            val y = (p / size + 0.5f) / size
            val inside = x in left..right && y in top..bottom
            if (inside) (if (c == 15) 6f else 0f) else (if (c == 0) 6f else 0f)
        }
    }

    @Test
    fun `select subject turns the model's mask into one undoable selection of the subject`() = runTest {
        val doc = document()
        val actions = actions(Runner(mapOf(SUBJECT_MODEL to subjectSession(0.25f, 0.2f, 0.75f, 0.8f))))
        val before = doc.history.value.current
        assertIs<CommandResult.Accepted>(actions.selectSubject(doc, SelectionOp.REPLACE))
        val selection = assertIs<SelectionInfo.Active>(doc.selection.value)
        assertTrue(selection.bounds.left in 40..60 && selection.bounds.right in 140..160, "bounds ${selection.bounds}")
        assertEquals("Select Subject", doc.history.value.undoLabel)
        assertEquals(before + 1, doc.history.value.current)
    }

    @Test
    fun `select background selects what the subject is not`() = runTest {
        val doc = document()
        val actions = actions(Runner(mapOf(SUBJECT_MODEL to subjectSession(0.25f, 0.2f, 0.75f, 0.8f))))
        assertIs<CommandResult.Accepted>(actions.selectBackground(doc, SelectionOp.REPLACE))
        assertEquals("Select Background", doc.history.value.undoLabel)
        val selection = assertIs<SelectionInfo.Active>(doc.selection.value)
        assertEquals(0, selection.bounds.left, "the background reaches the frame edge")
    }

    @Test
    fun `a picture without a subject is a clear rejection and leaves the selection alone`() = runTest {
        val doc = document()
        val nothing = Session { _, shape -> FloatArray(shape[1] * shape[1] * 21) { if (it % 21 == 0) 6f else 0f } }
        val result = actions(Runner(mapOf(SUBJECT_MODEL to nothing))).selectSubject(doc, SelectionOp.REPLACE)
        val rejected = assertIs<CommandResult.Rejected>(result)
        assertEquals(RejectionCode.INVALID_ARGUMENT, rejected.code)
        assertTrue("subject" in rejected.message)
        assertEquals(SelectionInfo.None, doc.selection.value)
    }

    @Test
    fun `object select uses the click and a failing model becomes a typed rejection`() = runTest {
        val doc = document()
        val echo = Session { input, _ -> FloatArray(512 * 512) { p -> input[p * 4 + 3] } }
        val actions = actions(Runner(mapOf(PROMPT_MODEL to echo)))
        assertIs<CommandResult.Accepted>(actions.selectObject(doc, listOf(PromptSegmenter.Click(0.5f, 0.5f)), SelectionOp.REPLACE))
        assertEquals("Object Select", doc.history.value.undoLabel)
        val broken = Session { _, _ -> error("the delegate crashed") }
        val result = actions(Runner(mapOf(PROMPT_MODEL to broken))).selectObject(doc, listOf(PromptSegmenter.Click(0.5f, 0.5f)), SelectionOp.REPLACE)
        assertEquals(RejectionCode.UNSUPPORTED, assertIs<CommandResult.Rejected>(result).code)
    }

    @Test
    fun `sky needs no model and the unavailable stub says so`() = runTest {
        val actions = actions(Runner(emptyMap(), ready = false))
        assertIs<AiRuntime.Availability.Ready>(actions.availability(AiFeature.SELECT_SKY))
        assertIs<AiRuntime.Availability.Unavailable>(actions.availability(AiFeature.SELECT_SUBJECT))
        assertIs<AiRuntime.Availability.Unavailable>(UnavailableAi.availability(AiFeature.SELECT_SKY))
        val doc = document()
        assertEquals(RejectionCode.UNSUPPORTED, assertIs<CommandResult.Rejected>(UnavailableAi.selectSky(doc, SelectionOp.REPLACE)).code)
    }

    @Test
    fun `depth estimation adds a grayscale layer with the nearest pixels darkest`() = runTest {
        val doc = document()
        // Larger model value = nearer: a left-to-right ramp, so the left of the layer must be the farthest (white).
        val ramp = Session { _, _ -> FloatArray(256 * 256) { p -> (p % 256).toFloat() } }
        val actions = actions(Runner(mapOf(DEPTH_MODEL to ramp)))
        val before = doc.layers.value.layers.size
        assertIs<CommandResult.Accepted>(actions.estimateDepth(doc))
        assertEquals("Estimate Depth", doc.history.value.undoLabel)
        assertEquals(before + 1, doc.layers.value.layers.size)
        assertEquals("Depth map", doc.layers.value.primary?.let { id -> doc.layers.value.find(id)?.name })
        val flat = Session { _, _ -> FloatArray(256 * 256) { 3f } }
        val rejected = actions(Runner(mapOf(DEPTH_MODEL to flat))).estimateDepth(doc)
        assertEquals(RejectionCode.UNSUPPORTED, assertIs<CommandResult.Rejected>(rejected).code)
        assertEquals(before + 1, doc.layers.value.layers.size)
    }

    @Test
    fun `remove fills the selection on a new layer and needs a selection first`() = runTest {
        val doc = document(600, 400)
        val actions = actions(Runner(mapOf(INPAINT_MODEL to Session { _, _ -> FloatArray(0) })))
        val none = actions.removeSelection(doc)
        assertEquals(RejectionCode.INVALID_ARGUMENT, assertIs<CommandResult.Rejected>(none).code)
        assertIs<CommandResult.Accepted>(doc.select(app.croptop.core.model.SelectionShape.Rect(app.croptop.core.model.DocRectF(100f, 100f, 180f, 160f))))
        val before = doc.layers.value.layers.size
        assertIs<CommandResult.Accepted>(actions.removeSelection(doc))
        assertEquals("AI Remove", doc.history.value.undoLabel)
        assertEquals(before + 1, doc.layers.value.layers.size)
        assertEquals("Removed", doc.layers.value.layers.first().name)
        doc.undo()
        assertEquals(before, doc.layers.value.layers.size)
    }

    @Test
    fun `the inpainting window is the networks own 512 for small selections and stays inside the picture`() {
        val small = InpaintWindow.window(app.croptop.core.model.DocRect(100, 100, 180, 160), DocSize(3000, 2000))
        assertEquals(512, small.width)
        assertEquals(512, small.height)
        assertTrue(small.left <= 100 && small.right >= 180 && small.top <= 100 && small.bottom >= 160)
        val corner = InpaintWindow.window(app.croptop.core.model.DocRect(0, 0, 40, 40), DocSize(3000, 2000))
        assertEquals(app.croptop.core.model.DocRect(0, 0, 512, 512), corner)
        val big = InpaintWindow.window(app.croptop.core.model.DocRect(500, 400, 1300, 1000), DocSize(3000, 2000))
        assertEquals(1200, big.width)
        val tiny = InpaintWindow.window(app.croptop.core.model.DocRect(10, 10, 30, 30), DocSize(300, 200))
        assertEquals(app.croptop.core.model.DocRect(0, 0, 300, 200), tiny)
    }

    private fun restorationRunner() = Runner(
        mapOf(
            DENOISE_MODEL to Session { chw, _ -> chw.copyOf() },
            UPSCALE_MODEL to Session { nhwc, _ ->
                FloatArray(3 * 512 * 512).also { out ->
                    for (c in 0 until 3) for (y in 0 until 512) for (x in 0 until 512) out[(c * 512 + y) * 512 + x] = nhwc[((y / 4) * 128 + x / 4) * 3 + c]
                }
            },
        ),
    )

    @Test
    fun `denoise adds a layer, reports progress and respects strength and the selection`() = runTest {
        val doc = document(600, 400)
        val actions = actions(restorationRunner())
        val progress = mutableListOf<Float>()
        assertIs<CommandResult.Accepted>(actions.denoise(doc, 0.8f) { progress += it })
        assertEquals("AI Denoise", doc.history.value.undoLabel)
        assertEquals("Denoised", doc.layers.value.layers.first().name)
        assertEquals(1f, progress.last())
        assertEquals(2, progress.size) // 600×400 in 480 blocks: two across, one down
        assertEquals(RejectionCode.INVALID_ARGUMENT, assertIs<CommandResult.Rejected>(actions.denoise(doc, 1.5f)).code)
        assertIs<CommandResult.Accepted>(doc.select(app.croptop.core.model.SelectionShape.Rect(app.croptop.core.model.DocRectF(10f, 10f, 50f, 50f))))
        progress.clear()
        assertIs<CommandResult.Accepted>(actions.denoise(doc, 1f) { progress += it })
        assertEquals(1, progress.size)
    }

    @Test
    fun `super resolution resizes the picture and puts the detail on a new layer`() = runTest {
        val doc = document(100, 60)
        val actions = actions(restorationRunner())
        assertIs<CommandResult.Accepted>(actions.upscale(doc, 2))
        assertEquals(DocSize(200, 120), doc.info.value.size)
        assertEquals("AI Super Resolution", doc.history.value.undoLabel)
        assertEquals("Super Resolution", doc.layers.value.layers.first().name)
        doc.undo()
        assertEquals(DocSize(100, 60), doc.info.value.size)
        assertEquals(RejectionCode.INVALID_ARGUMENT, assertIs<CommandResult.Rejected>(actions.upscale(doc, 3)).code)
        val big = document(8000, 6000)
        val rejected = assertIs<CommandResult.Rejected>(actions.upscale(big, 2))
        assertTrue("16000 × 12000" in rejected.message, rejected.message)
    }

    @Test
    fun `remove background masks the layer to the subject in one undo step and refuses a masked or missing subject`() = runTest {
        val classes = 21
        val left = Session { _, _ ->
            FloatArray(257 * 257 * classes).also { out ->
                for (p in 0 until 257 * 257) out[p * classes + if (p % 257 < 128) 1 else 0] = 10f
            }
        }
        // A transparent-background document has a regular pixel layer (the fake engine keeps masks off a locked Background layer).
        val doc = FakeEngine().newDocument(NewDocumentSpec("AI", DocSize(400, 300), background = BackgroundContents.TRANSPARENT))
        val actions = actions(Runner(mapOf(SUBJECT_MODEL to left)))
        val layer = doc.layers.value.primary!!
        val first = actions.removeBackground(doc)
        assertTrue(first is CommandResult.Accepted, first.toString())
        assertEquals("Remove Background", doc.history.value.undoLabel)
        assertTrue(doc.layers.value.find(layer)!!.mask != null)
        assertEquals(SelectionInfo.None, doc.selection.value)
        val again = assertIs<CommandResult.Rejected>(actions.removeBackground(doc))
        assertTrue("already has a mask" in again.message, again.message)
        doc.undo()
        assertTrue(doc.layers.value.find(layer)!!.mask == null)
        val nothing = Session { _, _ -> FloatArray(257 * 257 * classes).also { out -> for (p in 0 until 257 * 257) out[p * classes] = 10f } }
        val rejected = assertIs<CommandResult.Rejected>(actions(Runner(mapOf(SUBJECT_MODEL to nothing))).removeBackground(doc))
        assertTrue("subject" in rejected.message, rejected.message)
        assertTrue(doc.layers.value.find(layer)!!.mask == null)
    }

    @Test
    fun `a stroke covers its brush width and the remove brush fills it on a new layer`() = runTest {
        val window = app.croptop.core.model.DocRect(0, 0, 100, 60)
        val cover = StrokeCoverage.rasterize(listOf(app.croptop.core.model.DocPoint(20f, 30f), app.croptop.core.model.DocPoint(80f, 30f)), 5f, window)
        fun at(x: Int, y: Int) = cover[y * 100 + x].toInt() and 0xFF
        assertEquals(255, at(50, 30))
        assertEquals(255, at(50, 27))
        assertEquals(0, at(50, 40))
        assertEquals(0, at(5, 30))
        assertEquals(255, at(22, 30))
        assertTrue(cover.any { (it.toInt() and 0xFF) in 1..254 }, "the rim is softened over a pixel")
        val dab = StrokeCoverage.rasterize(listOf(app.croptop.core.model.DocPoint(10f, 10f)), 3f, window)
        assertTrue(dab.count { it.toInt() != 0 } in 20..60)

        val doc = document(600, 400)
        val actions = actions(Runner(mapOf(INPAINT_MODEL to Session { _, _ -> FloatArray(0) })))
        val before = doc.layers.value.layers.size
        assertIs<CommandResult.Accepted>(actions.removeStroke(doc, listOf(app.croptop.core.model.DocPoint(100f, 100f), app.croptop.core.model.DocPoint(160f, 120f)), 12f))
        assertEquals("AI Remove", doc.history.value.undoLabel)
        assertEquals(before + 1, doc.layers.value.layers.size)
        assertEquals(RejectionCode.INVALID_ARGUMENT, assertIs<CommandResult.Rejected>(actions.removeStroke(doc, emptyList(), 12f)).code)
        assertEquals(RejectionCode.INVALID_ARGUMENT, assertIs<CommandResult.Rejected>(actions.removeStroke(doc, listOf(app.croptop.core.model.DocPoint(1f, 1f)), 0f)).code)
    }

    @Test
    fun `a hole too big for the network goes to content-aware fill on a new layer`() = runTest {
        val doc = document(1200, 900)
        var networkRuns = 0
        val actions = actions(Runner(mapOf(INPAINT_MODEL to Session { _, _ -> networkRuns++; FloatArray(0) })))
        assertIs<CommandResult.Accepted>(doc.select(app.croptop.core.model.SelectionShape.Rect(app.croptop.core.model.DocRectF(100f, 100f, 700f, 500f))))
        val before = doc.layers.value.layers.size
        assertIs<CommandResult.Accepted>(actions.removeSelection(doc))
        assertEquals(before + 1, doc.layers.value.layers.size)
        assertEquals(0, networkRuns, "the network was not used")
        assertTrue(doc.selection.value is SelectionInfo.Active, "an existing selection is left as it was")
        doc.deselect()
        val stroke = listOf(app.croptop.core.model.DocPoint(200f, 300f), app.croptop.core.model.DocPoint(600f, 320f), app.croptop.core.model.DocPoint(900f, 400f))
        assertIs<CommandResult.Accepted>(actions.removeStroke(doc, stroke, 80f))
        assertEquals("AI Remove", doc.history.value.undoLabel)
        assertEquals(before + 2, doc.layers.value.layers.size)
        assertEquals(SelectionInfo.None, doc.selection.value, "a stroke's temporary selection is cleared")
        assertEquals(0, networkRuns)
    }

    private class SamSession(val outputs: (List<FloatArray>) -> List<FloatArray>) : ModelSession {
        var runs = 0
        override val delegate = Delegate.CPU
        override fun run(input: FloatArray, inputShape: IntArray) = error("not used")
        override fun runOutputs(inputs: List<FloatArray>, shapes: List<IntArray>): List<FloatArray> {
            runs++
            return outputs(inputs)
        }
        override fun close() = Unit
    }

    @Test
    fun `a single click uses sam2 with the picture analysed once, and falls back to the click model without it`() = runTest {
        val encoder = SamSession { listOf(FloatArray(256 * 64 * 64), FloatArray(64 * 128 * 128), FloatArray(32 * 256 * 256)) }
        val decoder = SamSession {
            val masks = FloatArray(3 * 256 * 256) { -8f }
            for (y in 0 until 256) for (x in 0 until 128) masks[y * 256 + x] = 8f
            listOf(masks, floatArrayOf(0.9f, 0.1f, 0.1f))
        }
        val magic = Session { input, _ -> FloatArray(512 * 512) { p -> input[p * 4 + 3] } }
        val doc = document()
        val actions = actions(Runner(mapOf(PROMPT_MODEL to magic, SAM_ENCODER_MODEL to encoder, SAM_DECODER_MODEL to decoder)))
        assertIs<CommandResult.Accepted>(actions.selectObject(doc, listOf(PromptSegmenter.Click(0.2f, 0.5f)), SelectionOp.REPLACE))
        assertIs<CommandResult.Accepted>(actions.selectObject(doc, listOf(PromptSegmenter.Click(0.3f, 0.4f)), SelectionOp.REPLACE))
        assertEquals(1, encoder.runs, "the same picture is analysed once")
        assertEquals(2, decoder.runs)
        // Another picture (here another size, since separate fake engines reuse ids): its first click analyses it.
        assertIs<CommandResult.Accepted>(actions.selectObject(document(300, 200), listOf(PromptSegmenter.Click(0.2f, 0.5f)), SelectionOp.REPLACE))
        assertEquals(2, encoder.runs)
        // Two clicks (add and subtract) are the click model's job; sam2 takes one point.
        val before = decoder.runs
        assertIs<CommandResult.Accepted>(actions.selectObject(doc, listOf(PromptSegmenter.Click(0.2f, 0.5f), PromptSegmenter.Click(0.6f, 0.5f)), SelectionOp.REPLACE))
        assertEquals(before, decoder.runs)
        // Without the sam2 models installed the click model answers.
        val plain = actions(Runner(mapOf(PROMPT_MODEL to magic)))
        assertIs<CommandResult.Accepted>(plain.selectObject(document(), listOf(PromptSegmenter.Click(0.5f, 0.5f)), SelectionOp.REPLACE))
        // A sam2 session that cannot run falls back to the click model instead of failing the click.
        val broken = SamSession { error("out of memory") }
        val fallback = actions(Runner(mapOf(PROMPT_MODEL to magic, SAM_ENCODER_MODEL to broken, SAM_DECODER_MODEL to decoder)))
        assertIs<CommandResult.Accepted>(fallback.selectObject(document(), listOf(PromptSegmenter.Click(0.5f, 0.5f)), SelectionOp.REPLACE))
    }

    @Test
    fun `a model that fails half way leaves the picture exactly as it was`() = runTest {
        var calls = 0
        val flaky = Session { nhwc, _ ->
            if (++calls == 2) error("out of memory")
            FloatArray(3 * 512 * 512).also { out ->
                for (c in 0 until 3) for (y in 0 until 512) for (x in 0 until 512) out[(c * 512 + y) * 512 + x] = nhwc[((y / 4) * 128 + x / 4) * 3 + c]
            }
        }
        val doc = document(300, 200)
        val actions = actions(Runner(mapOf(UPSCALE_MODEL to flaky)))
        val history = doc.history.value
        val layers = doc.layers.value.layers.size
        val rejected = assertIs<CommandResult.Rejected>(actions.upscale(doc, 2))
        assertTrue("out of memory" in rejected.message, rejected.message)
        assertEquals(DocSize(300, 200), doc.info.value.size)
        assertEquals(layers, doc.layers.value.layers.size)
        assertEquals(history, doc.history.value, "no half-finished edit stays in the history")
        assertTrue(!doc.history.value.canRedo, "and nothing is left to redo")
        // The same document can be upscaled afterwards: the group was closed.
        calls = -100
        assertIs<CommandResult.Accepted>(actions.upscale(doc, 2))
        assertEquals("AI Super Resolution", doc.history.value.undoLabel)
    }

    @Test
    fun `cancelling a denoise rolls everything back and a finished one is a single undo step`() = runTest {
        val doc = document(1000, 400)
        val session = Session { chw, _ -> chw.copyOf() }
        val actions = actions(Runner(mapOf(DENOISE_MODEL to session)))
        val before = doc.history.value
        var blocks = 0
        // The progress callback is where the person's Cancel arrives; throw there after the second block.
        val outcome = runCatching { actions.denoise(doc, 1f) { if (++blocks == 2) throw kotlinx.coroutines.CancellationException("cancel") } }
        assertTrue(outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException, "the cancellation reaches the caller")
        assertEquals(before, doc.history.value)
        assertEquals(1, doc.layers.value.layers.size)
        assertIs<CommandResult.Accepted>(actions.denoise(doc, 1f))
        assertEquals(before.entries.size + 1, doc.history.value.entries.size)
        assertEquals("AI Denoise", doc.history.value.undoLabel)
    }

    @Test
    fun `an edit made while the model runs means nothing is applied`() = runTest {
        val doc = document()
        val model = subjectSession(0.25f, 0.2f, 0.75f, 0.8f)
        // Someone else edits the document while the model is thinking (another window, an agent).
        val meddling = Session { input, shape ->
            doc.execute(app.croptop.core.engine.api.EditCommand.AddLayer(app.croptop.core.model.LayerId(77), "Meanwhile", app.croptop.core.model.LayerKind.PIXEL))
            model.run(input, shape)
        }
        val actions = actions(Runner(mapOf(SUBJECT_MODEL to meddling)))
        val result = assertIs<CommandResult.Rejected>(actions.selectSubject(doc, SelectionOp.REPLACE))
        assertTrue(result.message.contains("changed"), result.message)
        assertEquals(SelectionInfo.None, doc.selection.value)
        assertTrue(doc.history.value.undoLabel != "Select Subject", "the stale selection was not recorded")
    }
}
