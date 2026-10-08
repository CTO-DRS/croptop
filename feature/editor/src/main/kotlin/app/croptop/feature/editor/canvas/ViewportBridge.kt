package app.croptop.feature.editor.canvas

import app.croptop.core.engine.api.OverlayState
import app.croptop.core.engine.api.Viewport
import app.croptop.core.model.ViewportState

/**
 * Keeps one engine [Viewport] attached while both a document ([T]) and a surface ([S]) exist
 * . Surface recreation and document switches detach the old viewport before attaching
 * the new one; viewport changes are forwarded with [Viewport.update]. Generic so it is unit-tested
 * without Android surfaces; the app binds it to `DocumentHandle` and `android.view.Surface`.
 */
class ViewportBridge<T : Any, S : Any>(private val attach: (T, S, ViewportState) -> Viewport) {
    private var target: T? = null
    private var surface: S? = null
    private var state: ViewportState? = null
    private var overlays = OverlayState()
    private var attached: Viewport? = null

    val isAttached: Boolean get() = attached != null

    fun setTarget(t: T?) {
        if (t === target) return
        detach()
        target = t
        reattach()
    }

    fun setSurface(s: S?) {
        if (s === surface) return
        detach()
        surface = s
        reattach()
    }

    fun setViewport(v: ViewportState) {
        if (v == state) return
        state = v
        attached?.update(v) ?: reattach()
    }

    fun setOverlays(value: OverlayState) {
        if (value == overlays) return
        overlays = value
        attached?.setOverlays(value) ?: reattach()
    }

    /** Detaches and forgets everything (host left composition). */
    fun release() {
        detach()
        target = null
        surface = null
    }

    private fun detach() {
        attached?.let { runCatching { it.detach() } }
        attached = null
    }

    private fun reattach() {
        val t = target ?: return
        val s = surface ?: return
        val v = state?.takeIf { it.viewWidth > 0 && it.viewHeight > 0 } ?: return
        attached = attach(t, s, v).also { it.setOverlays(overlays) }
    }
}
