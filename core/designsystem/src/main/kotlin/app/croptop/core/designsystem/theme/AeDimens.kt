package app.croptop.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 4dp base grid. */
@Immutable
object AeSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

@Immutable
data class AeShapes(
    val control: Shape = RoundedCornerShape(2.dp),
    val popover: Shape = RoundedCornerShape(3.dp),
    /** Docked panels are flush — flat look. */
    val panel: Shape = RectangleShape,
    val chip: Shape = RoundedCornerShape(2.dp),
)

/** Motion tokens. Durations become 0 when the system disables animations. */
@Immutable
data class AeMotion(
    val fastMillis: Int = 120,
    val baseMillis: Int = 180,
    val slowMillis: Int = 240,
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f),
) {
    companion object {
        val Reduced = AeMotion(fastMillis = 0, baseMillis = 0, slowMillis = 0)
    }
}

/**
 * UI density: dense rows when a mouse/trackpad is present, comfortable for touch.
 */
@Immutable
enum class AeDensity(
    val rowHeight: Dp,
    val iconButton: Dp,
    val iconSize: Dp,
    val controlHeight: Dp,
    val minHitTarget: Dp,
) {
    Pointer(rowHeight = 28.dp, iconButton = 28.dp, iconSize = 20.dp, controlHeight = 28.dp, minHitTarget = 8.dp),
    Touch(rowHeight = 40.dp, iconButton = 44.dp, iconSize = 20.dp, controlHeight = 40.dp, minHitTarget = 24.dp),
}

object AeSizes {
    val hairline: Dp = 1.dp
    val focusRing: Dp = 2.dp
    val toolbarWidth: Dp = 44.dp
    val panelMinWidth: Dp = 240.dp
    val panelDefaultWidth: Dp = 280.dp
    val panelMaxWidth: Dp = 480.dp
    val selectedBar: Dp = 2.dp
}

internal val LocalAeShapes = staticCompositionLocalOf { AeShapes() }
internal val LocalAeMotion = staticCompositionLocalOf { AeMotion() }
internal val LocalAeDensity = staticCompositionLocalOf { AeDensity.Pointer }
