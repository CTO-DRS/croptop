package app.croptop.core.engine

import android.view.Surface
import java.nio.ByteBuffer

/** Vulkan presentation for CPU-composited document viewport frames. Calls stay on the viewport render thread. */
internal class NativeVulkanFrameBridge(surface: Surface) : AutoCloseable {
    private var handle: Long = nativeCreate(surface)

    val available: Boolean get() = handle != 0L
    val gpuName: String get() = handle.takeIf { it != 0L }?.let(::nativeGpuName) ?: "unavailable"
    val vulkanApiVersion: Int get() = handle.takeIf { it != 0L }?.let(::nativeVulkanApiVersion) ?: 0

    fun drawFrame(rgba: ByteBuffer, width: Int, height: Int): Boolean {
        val current = handle
        return current != 0L && nativeDrawFrame(current, rgba, width, height)
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    private external fun nativeCreate(surface: Surface): Long
    private external fun nativeDrawFrame(handle: Long, rgba: ByteBuffer, width: Int, height: Int): Boolean
    private external fun nativeGpuName(handle: Long): String
    private external fun nativeVulkanApiVersion(handle: Long): Int
    private external fun nativeDestroy(handle: Long)
}
