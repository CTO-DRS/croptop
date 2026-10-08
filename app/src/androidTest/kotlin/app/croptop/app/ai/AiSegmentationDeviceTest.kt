package app.croptop.app.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.croptop.core.ai.AiFeature
import app.croptop.core.ai.AiRuntime
import app.croptop.core.ai.HttpDownloader
import app.croptop.core.ai.LiteRtBackend
import app.croptop.core.ai.ModelAiEditActions
import app.croptop.core.ai.ModelManager
import app.croptop.core.ai.ModelRegistry
import app.croptop.core.ai.ModelRow
import app.croptop.core.ai.ModelStore
import app.croptop.core.ai.PromptSegmenter
import app.croptop.core.engine.NativeEngine
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.LensBlurDepthSource
import app.croptop.core.engine.api.LensBlurSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Downloads the real models (hash-checked), runs them through LiteRT on the device and selects through the real engine. */
@RunWith(AndroidJUnit4::class)
class AiSegmentationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun manager(): ModelManager {
        val manifest = instrumentation.targetContext.assets.open("ai/models.json").bufferedReader().use { it.readText() }
        val registry = ModelRegistry.parse(manifest)
        assertTrue("manifest entries rejected: ${registry.rejected}", registry.rejected.isEmpty())
        val root = File(instrumentation.targetContext.cacheDir, "ai-device-test-models").apply { mkdirs() }
        return ModelManager(ModelStore(root, HttpDownloader()), registry, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    private suspend fun ensureInstalled(manager: ModelManager, id: String) {
        if (manager.isInstalled(id)) return
        manager.install(id)
        withTimeout(10 * 60_000L) {
            manager.state.first { state ->
                val row = state.rows.first { it.spec.id == id }
                if (row.status is ModelRow.Status.Failed) error("download failed: ${row.status}")
                row.status == ModelRow.Status.Installed
            }
        }
    }

    private suspend fun carDocument(engine: NativeEngine): DocumentHandle {
        val file = File(instrumentation.targetContext.cacheDir, "ai_car.jpg")
        instrumentation.context.assets.open("ai_car.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        return engine.open(DocumentSource.AppFile(file.absolutePath))
    }

    private fun actions(manager: ModelManager) = ModelAiEditActions(AiRuntime(manager, LiteRtBackend()), manager)

    @Test
    fun clickSelectsTheCarAndNotTheRoad() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "magic-touch")
        val actions = actions(manager)
        assertEquals(AiRuntime.Availability.Ready, actions.availability(AiFeature.OBJECT_SELECT))
        val engine = NativeEngine(instrumentation.targetContext)
        val document = carDocument(engine)
        try {
            val size = document.info.value.size
            val started = System.nanoTime()
            val result = actions.selectObject(document, listOf(PromptSegmenter.Click(0.20f, 0.43f)), SelectionOp.REPLACE)
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(result.toString(), result is CommandResult.Accepted)
            val bounds = (document.selection.value as SelectionInfo.Active).bounds
            println("[ai] click select ${size.width}x${size.height} took $millis ms, selection $bounds")
            val cx = (bounds.left + bounds.right) / 2f / size.width
            val cy = (bounds.top + bounds.bottom) / 2f / size.height
            assertTrue("the car is around x=0.2, y=0.43; got $cx,$cy", cx in 0.10f..0.32f && cy in 0.30f..0.55f)
            assertTrue("the selection is the car, not the whole road: ${bounds.width}x${bounds.height}", bounds.width < size.width * 0.35f)
            assertEquals("Object Select", document.history.value.undoLabel)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun subjectSelectionRunsTheRealNetworkAndReportsHonestly() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "deeplab-v3")
        val actions = actions(manager)
        val engine = NativeEngine(instrumentation.targetContext)
        val document = carDocument(engine)
        try {
            // The photo may or may not contain a class the network knows: a selection or a clear "nothing found", never a crash.
            val result = actions.selectSubject(document, SelectionOp.REPLACE)
            println("[ai] subject result: $result")
            assertTrue(result.toString(), result is CommandResult.Accepted || result is CommandResult.Rejected)
            if (result is CommandResult.Rejected) assertTrue(result.toString(), result.message.contains("subject"))
            val background = actions.selectBackground(document, SelectionOp.REPLACE)
            assertTrue(background.toString(), background is CommandResult.Accepted || background is CommandResult.Rejected)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun skySelectionNeedsNoModelAndFindsTheSkyAboveTheGround() = runBlocking {
        val actions = actions(manager())
        assertEquals(AiRuntime.Availability.Ready, actions.availability(AiFeature.SELECT_SKY))
        val engine = NativeEngine(instrumentation.targetContext)
        val document = engine.newDocument(NewDocumentSpec("Sky", DocSize(600, 400), background = BackgroundContents.WHITE))
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 600f, 200f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.45f, 0.65f, 0.95f, 1f))) is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 200f, 600f, 400f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.15f, 0.3f, 0.12f, 1f))) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)
            val result = actions.selectSky(document, SelectionOp.REPLACE)
            assertTrue(result.toString(), result is CommandResult.Accepted)
            val bounds = (document.selection.value as SelectionInfo.Active).bounds
            assertTrue("sky spans the width at the top: $bounds", bounds.left <= 10 && bounds.right >= 590 && bounds.top <= 10)
            assertTrue("and stops at the horizon: $bounds", bounds.bottom in 170..230)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun depthMapFromTheRealModelDrivesLensBlur() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "midas-small")
        val actions = actions(manager)
        assertEquals(AiRuntime.Availability.Ready, actions.availability(AiFeature.DEPTH_MAP))
        val engine = NativeEngine(instrumentation.targetContext)
        val document = carDocument(engine)
        try {
            val photo = document.layers.value.layers.single().id
            val started = System.nanoTime()
            val result = actions.estimateDepth(document)
            println("[ai] depth took ${(System.nanoTime() - started) / 1_000_000} ms: $result")
            assertTrue(result.toString(), result is CommandResult.Accepted)
            assertEquals("Estimate Depth", document.history.value.undoLabel)
            val layers = document.layers.value
            val depth = layers.primary!!
            assertEquals("Depth map", layers.find(depth)?.name)
            // The depth layer sits on top, so the merged preview is the depth map: the bottom of the frame is near (dark), the far top-right bright.
            val view = document.preview(256)!!
            fun luma(x0: Float, y0: Float, x1: Float, y1: Float): Float {
                var sum = 0f; var n = 0
                for (y in (y0 * view.height).toInt() until (y1 * view.height).toInt()) for (x in (x0 * view.width).toInt() until (x1 * view.width).toInt()) {
                    sum += (view.rgba[(y * view.width + x) * 4].toInt() and 0xFF); n++
                }
                return sum / n
            }
            val far = luma(0.75f, 0.02f, 0.98f, 0.15f)
            val near = luma(0.30f, 0.88f, 0.70f, 0.98f)
            println("[ai] depth far(top-right)=$far near(bottom)=$near")
            assertTrue("far ($far) must be brighter than near ($near)", far > near + 40f)
            val blur = document.applyLensBlur(photo, LensBlurSpec(radius = 12f, focalDistance = 0f), LensBlurDepthSource.LayerPixels(depth))
            assertTrue(blur.toString(), blur is CommandResult.Accepted)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun removeFillsTheSelectedCarWithRoadAndLeavesTheRestAlone() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "magic-touch")
        ensureInstalled(manager, "lama-dilated")
        val actions = actions(manager)
        assertEquals(AiRuntime.Availability.Ready, actions.availability(AiFeature.REMOVE))
        val engine = NativeEngine(instrumentation.targetContext)
        val document = carDocument(engine)
        try {
            val size = document.info.value.size
            assertTrue(actions.selectObject(document, listOf(PromptSegmenter.Click(0.20f, 0.43f)), SelectionOp.REPLACE) is CommandResult.Accepted)
            val bounds = (document.selection.value as SelectionInfo.Active).bounds
            val view = 512
            fun colourfulness(shot: app.croptop.core.engine.api.DocumentPreview, x0: Int, y0: Int, x1: Int, y1: Int): Float {
                val sx = shot.width.toFloat() / size.width
                val sy = shot.height.toFloat() / size.height
                var sum = 0f; var n = 0
                for (y in (y0 * sy).toInt() until (y1 * sy).toInt()) for (x in (x0 * sx).toInt() until (x1 * sx).toInt()) {
                    val o = (y * shot.width + x) * 4
                    val r = shot.rgba[o].toInt() and 0xFF; val g = shot.rgba[o + 1].toInt() and 0xFF; val b = shot.rgba[o + 2].toInt() and 0xFF
                    sum += (maxOf(r, g, b) - minOf(r, g, b)); n++
                }
                return sum / n
            }
            val before = document.preview(view)!!
            val carBefore = colourfulness(before, bounds.left, bounds.top, bounds.right, bounds.bottom)
            val farBefore = colourfulness(before, 700, 300, 1000, 600)
            val started = System.nanoTime()
            val result = actions.removeSelection(document)
            println("[ai] remove took ${(System.nanoTime() - started) / 1_000_000} ms: $result")
            assertTrue(result.toString(), result is CommandResult.Accepted)
            assertEquals("AI Remove", document.history.value.undoLabel)
            assertEquals("Removed", document.layers.value.layers.first().name)
            val after = document.preview(view)!!
            val carAfter = colourfulness(after, bounds.left, bounds.top, bounds.right, bounds.bottom)
            val farAfter = colourfulness(after, 700, 300, 1000, 600)
            println("[ai] colourfulness car $carBefore -> $carAfter, far $farBefore -> $farAfter")
            assertTrue("the car's colours are gone: $carBefore -> $carAfter", carAfter < carBefore * 0.5f)
            assertEquals("pixels away from the selection are unchanged", farBefore, farAfter, 0.5f)
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun roughness(shot: app.croptop.core.engine.api.DocumentPreview, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        // Mean absolute difference between horizontal neighbours' green: grain and noise raise it, smooth asphalt does not.
        var sum = 0f; var n = 0
        for (y in (y0 * shot.height).toInt() until (y1 * shot.height).toInt()) for (x in (x0 * shot.width).toInt() until (x1 * shot.width).toInt() - 1) {
            val a = shot.rgba[(y * shot.width + x) * 4 + 1].toInt() and 0xFF
            val b = shot.rgba[(y * shot.width + x + 1) * 4 + 1].toInt() and 0xFF
            sum += kotlin.math.abs(a - b); n++
        }
        return sum / n
    }

    @Test
    fun denoiseSmoothsGrainOnANewLayerAndCanBeUndone() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "nafnet-sidd")
        val actions = actions(manager)
        val engine = NativeEngine(instrumentation.targetContext)
        val document = carDocument(engine)
        try {
            // Asphalt in the lower right of the frame: only grain varies there.
            val before = document.preview(512)!!
            val progress = mutableListOf<Float>()
            val started = System.nanoTime()
            val result = actions.denoise(document, 1f) { progress += it }
            println("[ai] denoise took ${(System.nanoTime() - started) / 1_000_000} ms: $result, ${progress.size} blocks")
            assertTrue(result.toString(), result is CommandResult.Accepted)
            assertEquals("AI Denoise", document.history.value.undoLabel)
            assertEquals("Denoised", document.layers.value.layers.first().name)
            assertEquals(1f, progress.last())
            val after = document.preview(512)!!
            val rb = roughness(before, 0.45f, 0.70f, 0.85f, 0.95f)
            val ra = roughness(after, 0.45f, 0.70f, 0.85f, 0.95f)
            println("[ai] roughness $rb -> $ra")
            assertTrue("grain should drop: $rb -> $ra", ra < rb)
            document.undo()
            assertEquals(1, document.layers.value.layers.size)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun superResolutionDoublesThePictureAndKeepsItsColours() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "realesr-general-x4v3")
        val actions = actions(manager)
        val engine = NativeEngine(instrumentation.targetContext)
        val document = carDocument(engine)
        try {
            val size = document.info.value.size
            val before = document.preview(256)!!
            val started = System.nanoTime()
            val result = actions.upscale(document, 2)
            println("[ai] super resolution took ${(System.nanoTime() - started) / 1_000_000} ms: $result")
            assertTrue(result.toString(), result is CommandResult.Accepted)
            assertEquals(app.croptop.core.model.DocSize(size.width * 2, size.height * 2), document.info.value.size)
            assertEquals("AI Super Resolution", document.history.value.undoLabel)
            assertEquals("Super Resolution", document.layers.value.layers.first().name)
            val after = document.preview(256)!!
            fun mean(shot: app.croptop.core.engine.api.DocumentPreview): FloatArray {
                val m = FloatArray(3)
                for (p in 0 until shot.width * shot.height) for (c in 0 until 3) m[c] += (shot.rgba[p * 4 + c].toInt() and 0xFF).toFloat()
                return FloatArray(3) { m[it] / (shot.width * shot.height) }
            }
            val mb = mean(before); val ma = mean(after)
            println("[ai] mean colour ${mb.toList()} -> ${ma.toList()}")
            for (c in 0 until 3) assertEquals(mb[c], ma[c], 12f)
            document.undo()
            assertEquals(size, document.info.value.size)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun removeBackgroundMasksTheLayerToTheDog() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "deeplab-v3")
        val actions = actions(manager)
        // A photo with something DeepLab knows (a dog), fetched at run time so no third-party image ships in the repo.
        val file = File(instrumentation.targetContext.cacheDir, "dog.jpg")
        file.writeBytes(java.net.URL("https://picsum.photos/id/237/800/533").openStream().use { it.readBytes() })
        val engine = NativeEngine(instrumentation.targetContext)
        val document = engine.open(DocumentSource.AppFile(file.absolutePath))
        try {
            val layer = document.layers.value.primary!!
            val started = System.nanoTime()
            val result = actions.removeBackground(document)
            println("[ai] remove background took ${(System.nanoTime() - started) / 1_000_000} ms: $result")
            assertTrue(result.toString(), result is CommandResult.Accepted)
            assertEquals("Remove Background", document.history.value.undoLabel)
            assertTrue(document.layers.value.find(layer)!!.mask != null)
            assertEquals(SelectionInfo.None, document.selection.value)
            val shot = document.preview(256)!!
            fun alpha(fx: Float, fy: Float) = shot.rgba[((fy * shot.height).toInt() * shot.width + (fx * shot.width).toInt()) * 4 + 3].toInt() and 0xFF
            val dog = alpha(0.5f, 0.6f)
            val corner = alpha(0.03f, 0.05f)
            println("[ai] alpha dog=$dog corner=$corner")
            assertTrue("the subject stays opaque ($dog)", dog > 200)
            assertTrue("the background is hidden ($corner)", corner < 60)
            document.undo()
            assertTrue(document.layers.value.find(layer)!!.mask == null)
        } finally {
            document.close()
            engine.close()
            file.delete()
        }
    }

    @Test
    fun removeBackgroundWithSamDrawsTheOutlineAndSavesAPreview() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "deeplab-v3")
        ensureInstalled(manager, "sam2-encoder")
        ensureInstalled(manager, "sam2-decoder")
        val actions = actions(manager)
        val file = File(instrumentation.targetContext.cacheDir, "dog-sam.jpg")
        file.writeBytes(java.net.URL("https://picsum.photos/id/237/800/533").openStream().use { it.readBytes() })
        val engine = NativeEngine(instrumentation.targetContext)
        val document = engine.open(DocumentSource.AppFile(file.absolutePath))
        try {
            val started = System.nanoTime()
            val result = actions.removeBackground(document)
            println("[ai] remove background (SAM) took ${(System.nanoTime() - started) / 1_000_000} ms: $result")
            assertTrue(result.toString(), result is CommandResult.Accepted)
            val shot = document.preview(800)!!
            // Composite on magenta so any leftover background is obvious, and keep it for a look.
            val bitmap = android.graphics.Bitmap.createBitmap(shot.width, shot.height, android.graphics.Bitmap.Config.ARGB_8888)
            val pixels = IntArray(shot.width * shot.height) { i ->
                val a = (shot.rgba[i * 4 + 3].toInt() and 0xFF) / 255f
                fun ch(c: Int, bg: Int) = ((c * a + bg * (1 - a)).toInt() and 0xFF)
                val r = ch(shot.rgba[i * 4].toInt() and 0xFF, 255)
                val g = ch(shot.rgba[i * 4 + 1].toInt() and 0xFF, 0)
                val b = ch(shot.rgba[i * 4 + 2].toInt() and 0xFF, 255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(pixels, 0, shot.width, 0, 0, shot.width, shot.height)
            val out = File(instrumentation.targetContext.getExternalFilesDir(null), "remove-bg-sam.png")
            out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            println("[ai] preview saved to ${out.absolutePath}")
        } finally {
            document.close()
            engine.close()
            file.delete()
        }
    }

    @Test
    fun theRemoveBrushPaintsOutTheCarWithoutASelection() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "lama-dilated")
        val actions = actions(manager)
        val engine = NativeEngine(instrumentation.targetContext)
        val document = carDocument(engine)
        try {
            val size = document.info.value.size
            fun colourfulness(shot: app.croptop.core.engine.api.DocumentPreview, x0: Int, y0: Int, x1: Int, y1: Int): Float {
                val sx = shot.width.toFloat() / size.width
                val sy = shot.height.toFloat() / size.height
                var sum = 0f; var n = 0
                for (y in (y0 * sy).toInt() until (y1 * sy).toInt()) for (x in (x0 * sx).toInt() until (x1 * sx).toInt()) {
                    val o = (y * shot.width + x) * 4
                    sum += maxOf(shot.rgba[o].toInt() and 0xFF, shot.rgba[o + 1].toInt() and 0xFF, shot.rgba[o + 2].toInt() and 0xFF) -
                        minOf(shot.rgba[o].toInt() and 0xFF, shot.rgba[o + 1].toInt() and 0xFF, shot.rgba[o + 2].toInt() and 0xFF)
                    n++
                }
                return sum / n
            }
            fun blueShare(shot: app.croptop.core.engine.api.DocumentPreview, x0: Int, y0: Int, x1: Int, y1: Int): Float {
                val sx = shot.width.toFloat() / size.width
                val sy = shot.height.toFloat() / size.height
                var blue = 0; var n = 0
                for (y in (y0 * sy).toInt() until (y1 * sy).toInt()) for (x in (x0 * sx).toInt() until (x1 * sx).toInt()) {
                    val o = (y * shot.width + x) * 4
                    if ((shot.rgba[o + 2].toInt() and 0xFF) > (shot.rgba[o].toInt() and 0xFF) + 40) blue++
                    n++
                }
                return blue.toFloat() / n
            }
            val before = document.preview(512)!!
            val blueBefore = blueShare(before, 130, 240, 310, 335)
            val carBefore = colourfulness(before, 130, 240, 310, 335)
            val farBefore = colourfulness(before, 700, 300, 1000, 600)
            assertTrue(document.selection.value is SelectionInfo.None)
            val stroke = listOf(app.croptop.core.model.DocPoint(120f, 300f), app.croptop.core.model.DocPoint(215f, 305f), app.croptop.core.model.DocPoint(315f, 310f))
            val started = System.nanoTime()
            val result = actions.removeStroke(document, stroke, 95f)
            println("[ai] remove brush took ${(System.nanoTime() - started) / 1_000_000} ms: $result")
            assertTrue(result.toString(), result is CommandResult.Accepted)
            assertEquals("AI Remove", document.history.value.undoLabel)
            val after = document.preview(512)!!
            val carAfter = colourfulness(after, 130, 240, 310, 335)
            val farAfter = colourfulness(after, 700, 300, 1000, 600)
            println("[ai] brush colourfulness car $carBefore -> $carAfter, far $farBefore -> $farAfter")
            val blueAfter = blueShare(after, 130, 240, 310, 335)
            println("[ai] brush blue share $blueBefore -> $blueAfter")
            // The fill of a hole this large is built from a downscaled view, so it is a soft smear, not clean asphalt: the car's own colours must be gone.
            assertTrue("the car's blue is painted out: $blueBefore -> $blueAfter", blueBefore > 0.1f && blueAfter < blueBefore * 0.1f)
            assertTrue("and the colour spread drops: $carBefore -> $carAfter", carAfter < carBefore * 0.8f)
            assertEquals(farBefore, farAfter, 0.5f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun sam2SelectsTheCarAndAnswersFurtherClicksFromCachedFeatures() = runBlocking {
        val manager = manager()
        ensureInstalled(manager, "magic-touch")
        ensureInstalled(manager, "sam2-encoder")
        ensureInstalled(manager, "sam2-decoder")
        val actions = actions(manager)
        val engine = NativeEngine(instrumentation.targetContext)
        val document = carDocument(engine)
        try {
            val size = document.info.value.size
            suspend fun click(x: Float, y: Float): Triple<Long, app.croptop.core.model.DocRect, CommandResult> {
                val started = System.nanoTime()
                val result = actions.selectObject(document, listOf(PromptSegmenter.Click(x, y)), SelectionOp.REPLACE)
                val millis = (System.nanoTime() - started) / 1_000_000
                return Triple(millis, (document.selection.value as? SelectionInfo.Active)?.bounds ?: app.croptop.core.model.DocRect(0, 0, 1, 1), result)
            }
            val first = click(0.20f, 0.43f)
            println("[ai] sam first click (encode + decode) ${first.first} ms bounds ${first.second}")
            assertTrue(first.third.toString(), first.third is CommandResult.Accepted)
            val cx = (first.second.left + first.second.right) / 2f / size.width
            val cy = (first.second.top + first.second.bottom) / 2f / size.height
            assertTrue("the car: $cx,$cy ${first.second}", cx in 0.10f..0.32f && cy in 0.30f..0.55f && first.second.width < size.width * 0.35f)
            val second = click(0.85f, 0.35f) // the grass on the right
            println("[ai] sam second click (decode only) ${second.first} ms bounds ${second.second}")
            assertTrue(second.third.toString(), second.third is CommandResult.Accepted)
            assertTrue("the second click reuses the picture features: ${first.first} ms then ${second.first} ms", second.first < first.first / 2)
            assertTrue("the grass is on the right, not the car", (second.second.left + second.second.right) / 2f > size.width * 0.5f)
        } finally {
            document.close()
            engine.close()
        }
    }
}
