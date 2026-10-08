package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.LocalCaptionBar

/** True inside [AeAppHeader]; interactive components then claim input from the system caption. */
val LocalInAppHeader = staticCompositionLocalOf { false }

/**
 * In the desktop caption area the system handles presses (window drag) unless the region is
 * excluded (developer.android.com "Support desktop windowing"). Apply to every interactive element
 * that may sit in the header; empty header space stays draggable.
 */
@Composable
fun Modifier.captionInteractive(): Modifier = if (LocalInAppHeader.current) this.systemGestureExclusion() else this

/**
 * Top row of every screen. In desktop windowing it occupies the system caption bar (one header,
 * no duplicate title), keeping clear of the window controls reported by [LocalCaptionBar].
 */
@Composable
fun AeAppHeader(
    modifier: Modifier = Modifier,
    minHeight: Dp = 48.dp,
    background: Color = Ae.colors.app,
    startPadding: Dp = 16.dp,
    endPadding: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val caption = LocalCaptionBar.current
    CompositionLocalProvider(LocalInAppHeader provides true) {
        Row(
            modifier
                .fillMaxWidth()
                .background(background)
                .height(max(minHeight, caption.height))
                .padding(start = caption.start + startPadding, end = caption.end + endPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
