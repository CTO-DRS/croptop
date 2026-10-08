package app.croptop.feature.editor.panels

import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.NativeEngine
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.LayerThumb
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RecentDocument
import app.croptop.core.model.RgbaColor
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.session.EditorViewModel
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class LayersPanelPerformanceActivity : ComponentActivity()

/** Native-backed target-device measurement for the real lazy Layers panel with a 200-layer tree. */
@RunWith(AndroidJUnit4::class)
class LayersPanelPerformanceTest {
    @Test
    fun twoHundredLayerPanelLoadsScrollsAndKeepsSelectionInSync() {
        val engine = RecordingThumbnailEngine(
            NativeEngine(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        val vm = EditorViewModel(
            engine,
            EmptyRecents,
            PanelShareCache,
            app.croptop.core.agent.OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
        )
        val scenario = ActivityScenario.launch(LayersPanelPerformanceActivity::class.java)
        val panelListState = LazyListState()
        val thumbnailPresentedNs = ConcurrentHashMap<LayerId, Long>()
        var vsyncClock: VsyncFrameClock? = null
        val panelDrawTimesNs = Collections.synchronizedList(mutableListOf<Long>())
        val scrollStartedNs = AtomicLong()
        val scrollCompletedNs = AtomicLong()
        val scrollCompleted = CountDownLatch(1)
        val scrollRequest = CompletableDeferred<Unit>()
        try {
            scenario.onActivity { repeat(200) { vm.newLayer() } }
            val updateDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (vm.state.value.layers.flatten().size != 201 && System.nanoTime() < updateDeadline) Thread.sleep(5)
            assertEquals(201, vm.state.value.layers.flatten().size)
            val initialLayerIds = vm.state.value.layers.flatten().map { it.id }.toSet()
            assertEquals("Expected one base layer plus 200 generated layers", 201, initialLayerIds.size)
            assertTrue("Could not seed a visible thumbnail", vm.fill(FillSource.Solid(RgbaColor(0.25f, 0.55f, 0.8f))))
            val measuredThumbnailId = checkNotNull(vm.state.value.primary)
            lateinit var initialDraw: CountDownLatch
            val compositionStarted = System.nanoTime()
            scenario.onActivity { activity ->
                initialDraw = nextDraw(activity)
                activity.setContent {
                    val state by vm.state.collectAsState()
                    LaunchedEffect(Unit) {
                        scrollRequest.await()
                        scrollStartedNs.set(System.nanoTime())
                        panelListState.animateScrollToItem(100)
                        scrollCompletedNs.set(System.nanoTime())
                        scrollCompleted.countDown()
                    }
                    CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                        CropTopTheme(density = AeDensity.Pointer) {
                            Column(
                                Modifier.width(280.dp).height(700.dp).drawWithContent {
                                    drawContent()
                                    panelDrawTimesNs += System.nanoTime()
                                },
                            ) {
                                LayersPanelContent(
                                    state,
                                    vm,
                                    panelListState,
                                    onThumbnailDraw = { id, drawnAt ->
                                        thumbnailPresentedNs.putIfAbsent(id, drawnAt)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            assertTrue("Layers panel did not draw", initialDraw.await(5, TimeUnit.SECONDS))
            val initialRootDrawMs = (System.nanoTime() - compositionStarted) / 1_000_000

            val thumbnailEmissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (engine.thumbnailEmissionCompletedNs.get() == 0L && System.nanoTime() < thumbnailEmissionDeadline) {
                Thread.sleep(5)
            }
            val thumbnailEmissionCompletedNs = engine.thumbnailEmissionCompletedNs.get()
            assertTrue("Native document emitted no thumbnail", thumbnailEmissionCompletedNs > 0L)
            val thumbnailFirstEmissionMs = (thumbnailEmissionCompletedNs - engine.thumbnailEmissionStartedNs.get()) / 1_000_000
            val presentationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thumbnailPresentedNs.isEmpty() && System.nanoTime() < presentationDeadline) Thread.sleep(5)
            assertTrue("Native thumbnails were emitted but no visible thumbnail reached an Image draw", thumbnailPresentedNs.isNotEmpty())
            val visibleThumbnailPresentationMs = engine.thumbnailEmittedNs.entries.mapNotNull { (id, emittedAt) ->
                thumbnailPresentedNs[id]?.let { drawnAt -> (drawnAt - emittedAt) / 1_000_000.0 }
            }
            assertTrue("No visible thumbnail emission could be correlated with presentation", visibleThumbnailPresentationMs.isNotEmpty())

            var initialVisibleRow = 0
            scenario.onActivity { initialVisibleRow = panelListState.firstVisibleItemIndex }
            scenario.onActivity { activity ->
                vsyncClock = VsyncFrameClock(Choreographer.getInstance())
                vsyncClock?.start()
            }
            val activeVsyncClock = checkNotNull(vsyncClock)
            scrollRequest.complete(Unit)
            assertTrue("Layers list scroll did not finish", scrollCompleted.await(5, TimeUnit.SECONDS))
            var finalVisibleRow = 0
            scenario.onActivity { finalVisibleRow = panelListState.firstVisibleItemIndex }
            assertTrue("Animated scroll did not advance the layer list", finalVisibleRow > initialVisibleRow)
            scenario.onActivity {
                vsyncClock?.stop()
                vsyncClock = null
            }
            Thread.sleep(100)
            val panelFrameTimesMs = synchronized(panelDrawTimesNs) {
                panelDrawTimesNs.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
            }
            val scrollDurationMs = (scrollCompletedNs.get() - scrollStartedNs.get()) / 1_000_000
            val scrollVsyncTimesNs = synchronized(activeVsyncClock.frameTimesNs) {
                activeVsyncClock.frameTimesNs.filter { it in scrollStartedNs.get()..scrollCompletedNs.get() }
            }
            val scrollFrameIntervalsMs = scrollVsyncTimesNs.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
            val scrollDrawTimesNs = synchronized(panelDrawTimesNs) {
                panelDrawTimesNs.filter { it in scrollStartedNs.get()..scrollCompletedNs.get() }
            }
            val scrollDrawIntervalsMs = scrollDrawTimesNs.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
            val over60HzBudget = panelFrameTimesMs.count { it > 16.67 }

            val target = vm.state.value.layers.flatten()[10]
            assertTrue("Selection was rejected", vm.select(target.id))
            val selectionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (vm.state.value.layers.active != setOf(target.id) && System.nanoTime() < selectionDeadline) Thread.sleep(5)
            assertEquals(setOf(target.id), vm.state.value.layers.active)
            assertEquals(vm.state.value.layers.active, vm.state.value.selected)

            fun awaitLayerCount(expected: Int) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (vm.state.value.layers.flatten().size != expected && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(expected, vm.state.value.layers.flatten().size)
            }
            vm.newLayer()
            awaitLayerCount(202)
            val addedLayerId = checkNotNull(vm.state.value.primary)
            assertEquals(vm.state.value.layers.active, vm.state.value.selected)
            assertTrue("Undo of the added layer was rejected", vm.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            awaitLayerCount(201)
            assertEquals(setOf(target.id), vm.state.value.selected)
            assertEquals(vm.state.value.layers.active, vm.state.value.selected)
            assertTrue("Redo of the added layer was rejected", vm.redo() is app.croptop.core.engine.api.CommandResult.Accepted)
            awaitLayerCount(202)
            assertEquals(setOf(addedLayerId), vm.state.value.selected)
            assertEquals(vm.state.value.layers.active, vm.state.value.selected)

            val metrics = scenarioDisplayMetrics(scenario)
            val perfSummary = listOf(
                "device=${android.os.Build.MODEL}",
                "abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}",
                "api=${android.os.Build.VERSION.SDK_INT}",
                "size=${metrics.first}",
                "densityDpi=${metrics.second}",
                "refreshHz=${metrics.third}",
                "layers=201",
                "initialComposeToRootDrawMs=$initialRootDrawMs",
                "thumbnailFirstEmissionMs=$thumbnailFirstEmissionMs",
                "visibleThumbnailPresentationMs=$visibleThumbnailPresentationMs",
                "visibleThumbnailPresentationP50Ms=${percentile(visibleThumbnailPresentationMs, 0.50)}",
                "visibleThumbnailPresentationP95Ms=${percentile(visibleThumbnailPresentationMs, 0.95)}",
                "visibleThumbnailPresentationMaxMs=${visibleThumbnailPresentationMs.maxOrNull()}",
                "thumbnailEmissionCount=${engine.thumbnailEmissionCount.get()}",
                "animatedScrollDurationMs=$scrollDurationMs",
                "scrolledFromRow=$initialVisibleRow toRow=$finalVisibleRow",
                "scrollVsyncCount=${scrollVsyncTimesNs.size}",
                "scrollVsyncIntervalMedianMs=${percentile(scrollFrameIntervalsMs, 0.50)}",
                "scrollVsyncIntervalP95Ms=${percentile(scrollFrameIntervalsMs, 0.95)}",
                "scrollDrawCount=${scrollDrawTimesNs.size}",
                "scrollDrawIntervalP95Ms=${percentile(scrollDrawIntervalsMs, 0.95)}",
                "scrollDrawOver60HzBudget=${scrollDrawIntervalsMs.count { it > 16.67 }}",
                "panelDrawIntervalP95Ms=${percentile(panelFrameTimesMs, 0.95)}",
                "panelDrawOver60HzBudget=$over60HzBudget",
            ).joinToString(" ")
            Log.i(
                "LayersPanelPerf",
                perfSummary,
            )
            assertTrue("Initial composition/root draw took ${initialRootDrawMs}ms", initialRootDrawMs < 5_000)
            assertTrue("Animated list scroll took ${scrollDurationMs}ms", scrollDurationMs < 5_000)
            assertTrue(
                "Native document did not emit the seeded layer thumbnail",
                engine.thumbnailEmittedNs.containsKey(measuredThumbnailId),
            )
        } finally {
            scenario.onActivity {
                vsyncClock?.stop()
            }
            scenario.close()
            engine.close()
        }
    }
}

private fun nextDraw(activity: ComponentActivity): CountDownLatch {
    val latch = CountDownLatch(1)
    val observer = activity.window.decorView.viewTreeObserver
    lateinit var listener: android.view.ViewTreeObserver.OnDrawListener
    listener = android.view.ViewTreeObserver.OnDrawListener {
        latch.countDown()
        activity.window.decorView.post {
            if (activity.window.decorView.viewTreeObserver.isAlive) {
                activity.window.decorView.viewTreeObserver.removeOnDrawListener(listener)
            }
        }
    }
    observer.addOnDrawListener(listener)
    return latch
}

private fun scenarioDisplayMetrics(scenario: ActivityScenario<LayersPanelPerformanceActivity>): Triple<String, Int, Float> {
    var result = Triple("unknown", 0, 0f)
    scenario.onActivity { activity ->
        val display = activity.resources.displayMetrics
        result = Triple("${display.widthPixels}x${display.heightPixels}", display.densityDpi, activity.windowManager.defaultDisplay.refreshRate)
    }
    return result
}

private class VsyncFrameClock(private val choreographer: Choreographer) {
    val frameTimesNs = Collections.synchronizedList(mutableListOf<Long>())
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameTimesNs += frameTimeNanos
            choreographer.postFrameCallback(this)
        }
    }

    fun start() = choreographer.postFrameCallback(callback)

    fun stop() = choreographer.removeFrameCallback(callback)
}

private class RecordingThumbnailEngine(private val delegate: Engine) : Engine by delegate {
    val thumbnailEmissionCount = AtomicInteger()
    val thumbnailEmissionStartedNs = AtomicLong()
    val thumbnailEmissionCompletedNs = AtomicLong()
    val thumbnailEmittedNs = ConcurrentHashMap<LayerId, Long>()

    override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
        val document = delegate.newDocument(spec)
        return object : DocumentHandle by document {
            override fun thumbnails(layerIds: List<app.croptop.core.model.LayerId>, px: Int): Flow<LayerThumb> {
                thumbnailEmissionStartedNs.compareAndSet(0, System.nanoTime())
                return document.thumbnails(layerIds, px).onEach { thumb ->
                    thumbnailEmissionCount.incrementAndGet()
                    thumbnailEmittedNs.putIfAbsent(thumb.layerId, System.nanoTime())
                    thumbnailEmissionCompletedNs.compareAndSet(0, System.nanoTime())
                }
            }
        }
    }
}

private fun percentile(values: List<Double>, fraction: Double): Double? {
    if (values.isEmpty()) return null
    return values.sorted()[((values.size - 1) * fraction).toInt()]
}

private object EmptyRecents : RecentDocumentsRepository {
    override fun observe(limit: Int): Flow<List<RecentDocument>> = flowOf(emptyList())
    override suspend fun recordOpened(uri: String, name: String, size: app.croptop.core.model.DocSize?, now: Long) = Unit
    override suspend fun remove(uri: String) = Unit
    override suspend fun setPinned(uri: String, pinned: Boolean) = Unit
}

private object PanelShareCache : app.croptop.feature.editor.files.ShareCache {
    private val directory = kotlin.io.path.createTempDirectory("layer-panel-share").toFile()
    override fun newFile(name: String) = java.io.File(directory, name)
    override fun uriFor(file: java.io.File) = "file://${file.absolutePath}"
}
