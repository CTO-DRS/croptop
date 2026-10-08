package app.croptop.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Desktop-windowing caption bar. When the app draws its own header into the caption
 * area, [height] is the caption height and [start]/[end] are the widths taken by system controls
 * (app chip at start, minimize/maximize/close at end) that app content must avoid.
 */
@Immutable
data class CaptionBar(val height: Dp, val start: Dp, val end: Dp) {
    companion object {
        val None = CaptionBar(0.dp, 0.dp, 0.dp)
    }
}

val LocalCaptionBar = staticCompositionLocalOf { CaptionBar.None }

/** Measures the caption bar from the current window insets (API 35+ bounding rects). */
@Composable
fun rememberCaptionBar(): CaptionBar {
    val density = LocalDensity.current
    val top = WindowInsets.captionBar.getTop(density)
    val view = LocalView.current
    val width = view.rootView.width
    return remember(top, width, density) {
        if (top == 0) return@remember CaptionBar.None
        var start = 0
        var end = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val insets = view.rootWindowInsets
            val rects = insets?.getBoundingRects(android.view.WindowInsets.Type.captionBar()).orEmpty()
            rects.forEach { r ->
                if (r.left <= 0) {
                    start = maxOf(start, r.right)
                } else if (width > 0) {
                    end = maxOf(end, width - r.left)
                }
            }
        }
        with(density) { CaptionBar(top.toDp(), start.toDp(), end.toDp()) }
    }
}
