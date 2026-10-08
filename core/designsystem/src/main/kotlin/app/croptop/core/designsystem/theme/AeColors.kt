package app.croptop.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Color tokens — Chrome stays neutral; accent marks active/focus/primary only. */
@Immutable
data class AeColors(
    val canvas: Color,
    val app: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val lineHairline: Color,
    val lineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentMuted: Color,
    val onAccent: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val clipShadow: Color,
    val clipHighlight: Color,
    val isDark: Boolean,
) {
    /** Surface one step above [base] — hover state. */
    fun hover(base: Color): Color = when (base) {
        app -> surface1
        surface1 -> surface2
        surface2 -> surface3
        else -> surface4
    }

    /** Surface two steps above [base] — pressed state. */
    fun pressed(base: Color): Color = hover(hover(base))
}

val DarkAeColors = AeColors(
    canvas = Color(0xFF1A1A1A),
    app = Color(0xFF141416),
    surface1 = Color(0xFF1C1C1F),
    surface2 = Color(0xFF232327),
    surface3 = Color(0xFF2C2C31),
    surface4 = Color(0xFF36363C),
    lineHairline = Color(0x14FFFFFF),
    lineStrong = Color(0x29FFFFFF),
    textPrimary = Color(0xFFEDEDEF),
    textSecondary = Color(0xFFA1A1A8),
    textTertiary = Color(0xFF7A7A82),
    accent = Color(0xFF4C8DFF),
    accentMuted = Color(0x264C8DFF),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFFF5C5C),
    warning = Color(0xFFFFB23F),
    success = Color(0xFF3DD68C),
    clipShadow = Color(0xFF3D7BFF),
    clipHighlight = Color(0xFFFF3D3D),
    isDark = true,
)

val LightAeColors = AeColors(
    canvas = Color(0xFFD6D6D6),
    app = Color(0xFFF4F4F5),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF0F0F2),
    surface3 = Color(0xFFE6E6EA),
    surface4 = Color(0xFFDADADF),
    lineHairline = Color(0x1A000000),
    lineStrong = Color(0x33000000),
    textPrimary = Color(0xFF1A1A1C),
    textSecondary = Color(0xFF5A5A63),
    textTertiary = Color(0xFF7E7E87),
    accent = Color(0xFF2F6FEB),
    accentMuted = Color(0x1F2F6FEB),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFD93A3A),
    warning = Color(0xFFC77A00),
    success = Color(0xFF1E9E62),
    clipShadow = Color(0xFF3D7BFF),
    clipHighlight = Color(0xFFFF3D3D),
    isDark = false,
)

/** User-selectable canvas surround: 0% / 18% / 50% gray, or theme default. */
enum class CanvasGray(val color: Color?) {
    THEME(null),
    BLACK(Color(0xFF000000)),
    DARK(Color(0xFF2E2E2E)),
    MID(Color(0xFF808080)),
}

internal val LocalAeColors = staticCompositionLocalOf { DarkAeColors }
