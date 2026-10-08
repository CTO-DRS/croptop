package app.croptop.core.engine

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hosts the actual native Vulkan SurfaceView without the document-preview renderer. */
class NativeCanvasVulkanSmokeActivity : Activity() {
    lateinit var canvas: NativeCanvasView
        private set

    @Volatile
    var rendererFailure: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        canvas = NativeCanvasView(this).apply {
            onInitFailed = { message -> rendererFailure = message }
        }
        setContentView(canvas, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

/** Exercises the document-frame upload bridge against a plain Android Surface. */
class NativeVulkanFrameBridgeSmokeActivity : Activity(), SurfaceHolder.Callback {
    lateinit var surfaceView: SurfaceView
        private set

    @Volatile var presented = false
        private set
    private var bridge: NativeVulkanFrameBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Thread {
            NativeEngine.info
            bridge?.close()
            val renderer = runCatching { NativeVulkanFrameBridge(holder.surface) }.getOrNull() ?: return@Thread
            if (!renderer.available) {
                renderer.close()
                return@Thread
            }
            val frameWidth = width.coerceAtLeast(1)
            val frameHeight = height.coerceAtLeast(1)
            val rgba = ByteBuffer.allocateDirect(frameWidth * frameHeight * 4)
            repeat(frameWidth * frameHeight) {
                rgba.put(32).put(192.toByte()).put(64).put(255.toByte())
            }
            rgba.flip()
            presented = renderer.drawFrame(rgba, frameWidth, frameHeight)
            bridge = renderer
        }.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        bridge?.close()
        bridge = null
    }
}

/** Opens a real NativeEngine document and attaches its production SurfaceDocumentViewport. */
private data class RetainedVulkanDocument(
    val engine: NativeEngine,
    val document: app.croptop.core.engine.api.DocumentHandle,
)

class NativeDocumentViewportVulkanSmokeActivity : Activity(), SurfaceHolder.Callback {
    private companion object {
        var retainedForConfigurationChange: RetainedVulkanDocument? = null
    }

    lateinit var surfaceView: SurfaceView
        private set

    @Volatile var attached = false
        private set

    @Volatile var failure: String? = null
        private set
    private val attachStarted = AtomicBoolean(false)

    @Volatile var lastSurfaceWidth = 0
        private set

    @Volatile var lastSurfaceHeight = 0
        private set

    @Volatile var viewportAttachCount = 0
        private set
    val engineCapabilities: app.croptop.core.engine.api.EngineCapabilities?
        get() = retainedDocument?.engine?.capabilities
    private var retainedDocument: RetainedVulkanDocument? = null
    private var viewport: app.croptop.core.engine.api.Viewport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainedDocument = retainedForConfigurationChange
        retainedForConfigurationChange = null
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        android.util.Log.i("VulkanViewportSmoke", "surfaceChanged ${width}x$height valid=${holder.surface.isValid} frame=${holder.surfaceFrame}")
        lastSurfaceWidth = width
        lastSurfaceHeight = height
        if (attached) {
            viewport?.update(app.croptop.core.model.ViewportState(viewWidth = width, viewHeight = height))
            return
        }
        if (attached || width <= 0 || height <= 0 || !attachStarted.compareAndSet(false, true)) return
        Thread {
            runCatching {
                val retained = retainedDocument ?: run {
                    val nativeEngine = NativeEngine(applicationContext)
                    val opened = nativeEngine.newDocument(
                        app.croptop.core.model.NewDocumentSpec(
                            name = "Vulkan viewport smoke",
                            size = app.croptop.core.model.DocSize(64, 64),
                            background = app.croptop.core.model.BackgroundContents.TRANSPARENT,
                        ),
                    )
                    RetainedVulkanDocument(nativeEngine, opened).also { retainedDocument = it }
                }
                viewport = retained.document.attachViewport(
                    holder.surface,
                    app.croptop.core.model.ViewportState(viewWidth = width, viewHeight = height),
                )
                attached = true
                viewportAttachCount++
            }.onFailure { failure = it.toString() }
        }.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        android.util.Log.i("VulkanViewportSmoke", "surfaceDestroyed attached=$attached")
        attachStarted.set(false)
        attached = false
        viewport?.close()
        viewport = null
        android.util.Log.i("VulkanViewportSmoke", "viewport detach completed before Surface callback returned")
    }

    override fun onDestroy() {
        if (isChangingConfigurations) {
            retainedForConfigurationChange = retainedDocument
        } else {
            retainedDocument?.document?.close()
            retainedDocument?.engine?.close()
            retainedDocument = null
        }
        super.onDestroy()
    }

    fun resizeSurface(width: Int, height: Int) {
        surfaceView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }

    fun fillSurface() {
        surfaceView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun zoomAndCenterDocument() {
        val zoom = 4f
        val panX = surfaceView.width / 2f - 32f * zoom
        val panY = surfaceView.height / 2f - 32f * zoom
        viewport?.update(
            app.croptop.core.model.ViewportState(
                zoom = zoom,
                panX = panX,
                panY = panY,
                viewWidth = surfaceView.width,
                viewHeight = surfaceView.height,
            ),
        )
    }
}

/** Framework baseline: post one ordinary hardware Canvas frame, then close the SurfaceView. */
class HardwareCanvasTeardownSmokeActivity : Activity(), SurfaceHolder.Callback {
    lateinit var surfaceView: SurfaceView
        private set
    val framePosted = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this).apply { holder.addCallback(this@HardwareCanvasTeardownSmokeActivity) }
        setContentView(surfaceView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (framePosted.count == 0L || width <= 0 || height <= 0) return
        val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            canvas.drawColor(android.graphics.Color.rgb(42, 43, 46))
        } finally {
            holder.unlockCanvasAndPost(canvas)
            framePosted.countDown()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}

@RunWith(AndroidJUnit4::class)
class NativeCanvasVulkanSmokeTest {
    @Test
    fun apiSurfaceTeardownAfterSingleHardwareCanvasFrame() {
        val scenario = ActivityScenario.launch(HardwareCanvasTeardownSmokeActivity::class.java)
        try {
            var framePosted: CountDownLatch? = null
            scenario.onActivity { framePosted = it.framePosted }
            assertTrue("Hardware Canvas baseline frame was not posted", requireNotNull(framePosted).await(5, TimeUnit.SECONDS))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun nativeDocumentViewportPresentsOnVulkanSurface() {
        val wasIgnoringOrientation = shellOutput("cmd window get-ignore-orientation-request").contains("true")
        if (wasIgnoringOrientation) shellOutput("cmd window set-ignore-orientation-request false")
        val scenario = ActivityScenario.launch(NativeDocumentViewportVulkanSmokeActivity::class.java)
        try {
            lateinit var surfaceView: SurfaceView
            scenario.onActivity { surfaceView = it.surfaceView }
            val deadline = SystemClock.uptimeMillis() + 15_000
            var attached = false
            var failure: String? = null
            while (SystemClock.uptimeMillis() < deadline && !attached && failure == null) {
                scenario.onActivity {
                    attached = it.attached
                    failure = it.failure
                }
                if (!attached && failure == null) SystemClock.sleep(50)
            }
            assertTrue("NativeEngine viewport did not attach: $failure", attached)
            assertViewportPixelCopy(surfaceView, "initial")
            var capabilities: app.croptop.core.engine.api.EngineCapabilities? = null
            scenario.onActivity { capabilities = it.engineCapabilities }
            assertTrue("Vulkan GPU name missing from EngineCapabilities", !capabilities?.gpuName.isNullOrBlank())
            assertTrue("Vulkan API version missing from EngineCapabilities", (capabilities?.vulkanApiVersion ?: 0) > 0)
            var initialSurfaceSize = 0 to 0
            scenario.onActivity { activity ->
                initialSurfaceSize = activity.lastSurfaceWidth to activity.lastSurfaceHeight
                activity.zoomAndCenterDocument()
            }
            assertViewportPixelCopy(surfaceView, "zoomed", expectedPixel = 0xFFCDCED1.toInt())

            scenario.onActivity { it.resizeSurface(1200, 800) }
            val resizeDeadline = SystemClock.uptimeMillis() + 10_000
            var resized = false
            while (SystemClock.uptimeMillis() < resizeDeadline && !resized) {
                scenario.onActivity { activity ->
                    resized = activity.lastSurfaceWidth == 1200 && activity.lastSurfaceHeight == 800 && activity.attached
                }
                if (!resized) SystemClock.sleep(50)
            }
            assertTrue("NativeEngine viewport did not receive the 1200x800 Surface resize", resized)
            assertViewportPixelCopy(surfaceView, "resized")

            scenario.onActivity {
                it.fillSurface()
            }
            val fullSurfaceDeadline = SystemClock.uptimeMillis() + 10_000
            var fullSurfaceSize: Pair<Int, Int>? = null
            while (SystemClock.uptimeMillis() < fullSurfaceDeadline && fullSurfaceSize == null) {
                scenario.onActivity { activity ->
                    val size = activity.lastSurfaceWidth to activity.lastSurfaceHeight
                    if (size == initialSurfaceSize && activity.attached) fullSurfaceSize = size
                }
                if (fullSurfaceSize == null) SystemClock.sleep(50)
            }
            assertTrue("Viewport Surface did not return to its full window size", fullSurfaceSize != null)
            val beforeRotation = requireNotNull(fullSurfaceSize)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            val rotationDeadline = SystemClock.uptimeMillis() + 15_000
            var rotatedSize: Pair<Int, Int>? = null
            while (SystemClock.uptimeMillis() < rotationDeadline && rotatedSize == null) {
                scenario.onActivity { activity ->
                    val size = activity.lastSurfaceWidth to activity.lastSurfaceHeight
                    if (size != beforeRotation && size.first > 0 && size.second > 0 && activity.attached) {
                        rotatedSize = size
                        surfaceView = activity.surfaceView
                    }
                }
                if (rotatedSize == null) SystemClock.sleep(50)
            }
            if (rotatedSize == null) {
                android.util.Log.w(TAG, "Device window manager did not rotate the resizable test Activity from $beforeRotation")
            } else {
                assertEquals("Portrait rotation must swap the surface axes", beforeRotation.first, rotatedSize!!.second)
                assertEquals("Portrait rotation must swap the surface axes", beforeRotation.second, rotatedSize!!.first)
                assertViewportPixelCopy(surfaceView, "rotated portrait")
            }

            var attachesBeforeResume = 0
            scenario.onActivity { attachesBeforeResume = it.viewportAttachCount }
            scenario.moveToState(Lifecycle.State.CREATED)
            val stoppedDeadline = SystemClock.uptimeMillis() + 5_000
            var surfaceDetached = false
            while (SystemClock.uptimeMillis() < stoppedDeadline && !surfaceDetached) {
                scenario.onActivity { surfaceDetached = !it.attached }
                if (!surfaceDetached) SystemClock.sleep(50)
            }
            assertTrue("Viewport Surface was not detached while the activity stopped", surfaceDetached)
            scenario.moveToState(Lifecycle.State.RESUMED)
            val resumeDeadline = SystemClock.uptimeMillis() + 15_000
            var resumedAttached = false
            while (SystemClock.uptimeMillis() < resumeDeadline && !resumedAttached) {
                scenario.onActivity { activity ->
                    resumedAttached = activity.attached && activity.viewportAttachCount > attachesBeforeResume
                    if (resumedAttached) surfaceView = activity.surfaceView
                }
                if (!resumedAttached) SystemClock.sleep(50)
            }
            assertTrue("The retained document viewport did not reattach after resume", resumedAttached)
            assertViewportPixelCopy(surfaceView, "resumed")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        } finally {
            scenario.close()
            if (wasIgnoringOrientation) shellOutput("cmd window set-ignore-orientation-request true")
        }
    }

    private fun assertViewportPixelCopy(
        surfaceView: SurfaceView,
        label: String,
        expectedPixel: Int = 0xFF2A2B2E.toInt(),
    ) {
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        var copyResult = PixelCopy.ERROR_SOURCE_NO_DATA
        var center = 0
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (
            (copyResult == PixelCopy.ERROR_SOURCE_NO_DATA || center != expectedPixel) &&
            SystemClock.uptimeMillis() < deadline
        ) {
            copyResult = PixelCopy.ERROR_SOURCE_NO_DATA
            val copied = CountDownLatch(1)
            PixelCopy.request(surfaceView, bitmap, { result ->
                copyResult = result
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("PixelCopy timed out for $label document viewport", copied.await(2, TimeUnit.SECONDS))
            if (copyResult == PixelCopy.SUCCESS) {
                center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            }
            if (copyResult == PixelCopy.ERROR_SOURCE_NO_DATA || center != expectedPixel) SystemClock.sleep(100)
        }
        assertEqualsPixelCopySuccess(copyResult)
        bitmap.recycle()
        assertEquals("Unexpected composed viewport pixel after $label", expectedPixel, center)
        android.util.Log.i(TAG, "NativeEngine $label viewport PixelCopy center=0x${center.toUInt().toString(16)}")
    }

    private fun shellOutput(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    @Test
    fun documentFrameBridgeUploadsAndPresentsRgbaPixels() {
        val scenario = ActivityScenario.launch(NativeVulkanFrameBridgeSmokeActivity::class.java)
        try {
            lateinit var surfaceView: SurfaceView
            scenario.onActivity { surfaceView = it.surfaceView }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var presented = false
            while (SystemClock.uptimeMillis() < deadline && !presented) {
                scenario.onActivity { presented = it.presented }
                if (!presented) SystemClock.sleep(50)
            }
            assertTrue("Vulkan document frame bridge did not present", presented)
            val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var copyResult = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(surfaceView, bitmap, { result ->
                copyResult = result
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("PixelCopy timed out for Vulkan document frame", copied.await(5, TimeUnit.SECONDS))
            assertEqualsPixelCopySuccess(copyResult)
            val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            bitmap.recycle()
            assertTrue("Expected uploaded green frame, got 0x${center.toUInt().toString(16)}", (center shr 8 and 0xFF) > 150)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun rendererCreatesAndPresentsOrReportsTypedFallback() {
        val scenario = ActivityScenario.launch(NativeCanvasVulkanSmokeActivity::class.java)
        try {
            val deadline = SystemClock.uptimeMillis() + 10_000
            var gpuName = "unavailable"
            var rendererFailure: String? = null
            while (SystemClock.uptimeMillis() < deadline && gpuName == "unavailable" && rendererFailure == null) {
                scenario.onActivity { activity ->
                    gpuName = activity.canvas.gpuName
                    rendererFailure = activity.rendererFailure
                }
                if (gpuName == "unavailable" && rendererFailure == null) SystemClock.sleep(50)
            }

            if (gpuName == "unavailable") {
                assertTrue("Vulkan renderer neither initialized nor reported a fallback", !rendererFailure.isNullOrBlank())
                InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
                    android.util.Log.i(TAG, "Vulkan unavailable; controlled fallback: $rendererFailure (${context.packageName})")
                }
                return
            }

            assertNotEquals("unavailable", gpuName)
            lateinit var canvas: NativeCanvasView
            scenario.onActivity { activity ->
                canvas = activity.canvas
            }
            val bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var copyResult = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(canvas, bitmap, { result ->
                copyResult = result
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("PixelCopy timed out while capturing the Vulkan surface", copied.await(5, TimeUnit.SECONDS))
            assertEqualsPixelCopySuccess(copyResult)

            // Search the fitted 512x320 chart for its red swatch, independent of screen insets.
            var redPixels = 0
            for (y in 0 until bitmap.height step 4) {
                for (x in 0 until bitmap.width step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    val red = pixel shr 16 and 0xFF
                    val green = pixel shr 8 and 0xFF
                    val blue = pixel and 0xFF
                    if (red > 120 && green < 80 && blue < 90) redPixels++
                }
            }
            bitmap.recycle()
            assertTrue("Expected the Vulkan chart's red cell, found $redPixels red samples", redPixels > 100)
            android.util.Log.i(TAG, "Vulkan renderer presented chart using $gpuName")
        } finally {
            scenario.close()
        }
    }

    private fun assertEqualsPixelCopySuccess(result: Int) {
        assertTrue("Vulkan Surface PixelCopy failed with result $result", result == PixelCopy.SUCCESS)
    }

    private companion object {
        const val TAG = "NativeCanvasVulkanSmoke"
    }
}
