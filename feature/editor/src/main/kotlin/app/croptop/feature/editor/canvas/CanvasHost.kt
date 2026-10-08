package app.croptop.feature.editor.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.OverlayState
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewportState

/**
 * Renders document pixels for a [ViewportState]. The app provides the engine-backed host
 * (`full` flavor: Vulkan `NativeCanvasView`); features never see `:core:engine`.
 * Input is handled above the host by [CanvasViewport], so hosts only draw.
 */
fun interface CanvasHost {
    /** [handle] is the open document (null while it loads); engine-backed hosts attach its viewport. */
    @Composable
    fun Content(
        viewport: ViewportState,
        document: DocSize,
        handle: DocumentHandle?,
        modifier: Modifier,
        overlays: OverlayState,
    )
}

/** Defaults to the pure-Compose preview canvas (fake flavor, previews, screenshot tests). */
val LocalCanvasHost = staticCompositionLocalOf<CanvasHost> { ComposeCanvasHost }
