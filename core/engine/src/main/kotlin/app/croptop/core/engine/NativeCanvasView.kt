package app.croptop.core.engine

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewportState
import java.util.concurrent.atomic.AtomicInteger

/**
 * A thin SurfaceView bridge for the native Vulkan canvas. Vulkan calls run on a
 * dedicated thread paced by that thread's Choreographer; the UI thread never waits
 * for GPU fences. Feature modules can host this view through AndroidView.
 */
class NativeCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    /** Called on the UI thread when native rendering cannot start or stops unexpectedly. */
    var onInitFailed: ((String) -> Unit)? = null

    private val generation = AtomicInteger(0)

    @Volatile private var surfaceAvailable = false
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var rendererHandle: Long = 0L // Render thread only.
    private var rendererGeneration = 0 // Render thread only.
    private var viewport = ViewportState()
    private var hasCallerViewport = false

    @Volatile
    var gpuName: String = "unavailable"
        private set

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback {
        val handle = rendererHandle
        if (surfaceAvailable && handle != 0L && rendererGeneration == generation.get()) {
            if (nativeDraw(handle)) {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            } else {
                Log.w(TAG, "Vulkan frame submission failed; rebuilding the surface renderer")
                recoverRenderer(rendererGeneration, 0, "GPU frame submission failed")
            }
        }
    }

    init {
        // Loads the native library before the first Surface callback.
        check(NativeEngine.info.isNotEmpty())
        holder.setFormat(PixelFormat.OPAQUE)
    }

    /** Set the same document-to-view transform used by overlays and pointer tools. */
    fun setViewport(state: ViewportState) {
        viewport = state
        hasCallerViewport = true
        val currentGeneration = generation.get()
        renderHandler?.post {
            if (generation.get() == currentGeneration && rendererHandle != 0L) {
                if (!nativeSetViewport(
                        rendererHandle,
                        state.zoom,
                        state.panX,
                        state.panY,
                        state.rotationDegrees,
                        state.flipHorizontal,
                    )
                ) {
                    Log.e(TAG, "Rejected invalid Vulkan viewport state")
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureRenderThread()
        holder.addCallback(this)
    }

    override fun onDetachedFromWindow() {
        holder.removeCallback(this)
        surfaceAvailable = false
        generation.incrementAndGet()
        val thread = renderThread
        val handler = renderHandler
        handler?.post {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            destroyRenderer()
            thread?.quitSafely()
        }
        renderThread = null
        renderHandler = null
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        val currentGeneration = generation.incrementAndGet()
        val surface = holder.surface
        renderHandler?.post {
            if (!surfaceAvailable || generation.get() != currentGeneration) return@post
            createRenderer(currentGeneration, 0, "Vulkan initialization failed", surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (!hasCallerViewport) {
            viewport = ViewportState().copy(viewWidth = width, viewHeight = height)
                .actualSize(DocSize(512, 320))
        } else {
            viewport = viewport.copy(viewWidth = width, viewHeight = height)
        }
        val state = viewport
        val currentGeneration = generation.get()
        renderHandler?.post {
            if (generation.get() == currentGeneration && rendererHandle != 0L) {
                if (!nativeResize(rendererHandle, width, height)) {
                    Log.e(TAG, "Could not recreate Vulkan swapchain at ${width}x$height")
                    recoverRenderer(currentGeneration, 0, "GPU canvas resize failed")
                    return@post
                }
                nativeSetViewport(
                    rendererHandle,
                    state.zoom,
                    state.panX,
                    state.panY,
                    state.rotationDegrees,
                    state.flipHorizontal,
                )
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        generation.incrementAndGet()
        renderHandler?.post {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            destroyRenderer()
        }
    }

    private fun ensureRenderThread() {
        if (renderThread?.isAlive == true) return
        val thread = HandlerThread("CropTopVulkanCanvas")
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)
    }

    private fun applyViewport() {
        nativeSetViewport(
            rendererHandle,
            viewport.zoom,
            viewport.panX,
            viewport.panY,
            viewport.rotationDegrees,
            viewport.flipHorizontal,
        )
    }

    /** Rebuilds the renderer after Android invalidates its window surface during resize/rotation. */
    private fun recoverRenderer(expectedGeneration: Int, attempt: Int, reason: String) {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        destroyRenderer()
        createRenderer(expectedGeneration, attempt, reason)
    }

    private fun createRenderer(
        expectedGeneration: Int,
        attempt: Int,
        reason: String,
        surface: android.view.Surface = holder.surface,
    ) {
        if (!surfaceAvailable || generation.get() != expectedGeneration) return
        if (!surface.isValid) {
            retryRenderer(expectedGeneration, attempt, reason)
            return
        }
        val handle = nativeCreate(surface)
        if (handle == 0L) {
            retryRenderer(expectedGeneration, attempt, reason)
            return
        }
        rendererHandle = handle
        rendererGeneration = expectedGeneration
        gpuName = nativeGpuName(handle)
        applyViewport()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun retryRenderer(expectedGeneration: Int, attempt: Int, reason: String) {
        if (attempt >= MAX_RENDERER_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "$reason after $attempt renderer recovery attempts")
            post { onInitFailed?.invoke("GPU rendering is unavailable. Showing the canvas preview instead.") }
            return
        }
        val delayMs = 100L shl attempt
        renderHandler?.postDelayed({
            if (surfaceAvailable && generation.get() == expectedGeneration) {
                createRenderer(expectedGeneration, attempt + 1, reason)
            }
        }, delayMs)
    }

    private fun destroyRenderer() {
        if (rendererHandle != 0L) {
            nativeDestroy(rendererHandle)
            rendererHandle = 0L
        }
    }

    private external fun nativeCreate(surface: android.view.Surface): Long
    private external fun nativeResize(handle: Long, width: Int, height: Int): Boolean
    private external fun nativeSetViewport(
        handle: Long,
        zoom: Float,
        panX: Float,
        panY: Float,
        rotationDegrees: Float,
        flipHorizontal: Boolean,
    ): Boolean
    private external fun nativeDraw(handle: Long): Boolean
    private external fun nativeGpuName(handle: Long): String
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TAG = "CropTopCanvas"
        private const val MAX_RENDERER_RECOVERY_ATTEMPTS = 3
    }
}
