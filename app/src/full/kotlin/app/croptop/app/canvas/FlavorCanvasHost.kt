package app.croptop.app.canvas

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.croptop.core.engine.NativeCanvasView
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.OverlayState
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewportState
import app.croptop.feature.editor.canvas.CanvasHost
import app.croptop.feature.editor.canvas.ComposeCanvasHost
import app.croptop.feature.editor.canvas.ViewportBridge

/**
 * `full` flavor canvas (/013). When the open document draws its own pixels
 * ([DocumentHandle.rendersViewport]) it gets a passive [SurfaceView]: the document viewport is the only
 * renderer on that Surface (attached on surface creation, updated per viewport change, detached on
 * surface destruction, document switch or leaving the editor). Until then the diagnostic
 * [NativeCanvasView] draws, falling back to the Compose canvas when Vulkan can't start.
 */
val FlavorCanvasHost: CanvasHost = object : CanvasHost {
    @Composable
    override fun Content(
        viewport: ViewportState,
        document: DocSize,
        handle: DocumentHandle?,
        modifier: Modifier,
        overlays: OverlayState,
    ) {
        if (handle != null && handle.rendersViewport) {
            DocumentSurface(handle, viewport, overlays, modifier)
            return
        }
        var nativeUnavailable by remember { mutableStateOf(false) }
        if (nativeUnavailable) {
            ComposeCanvasHost.Content(viewport, document, handle, modifier, overlays)
            return
        }
        AndroidView(
            factory = { context -> NativeCanvasView(context).apply { onInitFailed = { _ -> nativeUnavailable = true } } },
            update = { it.setViewport(viewport) },
            modifier = modifier,
        )
    }
}

/** A plain Surface owned by the document's renderer (no second Vulkan renderer on it). */
@Composable
private fun DocumentSurface(handle: DocumentHandle, viewport: ViewportState, overlays: OverlayState, modifier: Modifier) {
    val bridge = remember { ViewportBridge<DocumentHandle, android.view.Surface> { h, s, v -> h.attachViewport(s, v) } }
    DisposableEffect(bridge) { onDispose { bridge.release() } }
    SideEffect {
        bridge.setTarget(handle)
        bridge.setViewport(viewport)
        bridge.setOverlays(overlays)
    }
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = bridge.setSurface(h.surface)

                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(h: SurfaceHolder) = bridge.setSurface(null)
                    },
                )
            }
        },
        modifier = modifier,
    )
}
