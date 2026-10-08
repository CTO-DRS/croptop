package app.croptop.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** AUTO follows attached pointer devices. */
@Serializable
enum class UiDensityMode { AUTO, POINTER, TOUCH }

/** Canvas surround. */
@Serializable
enum class CanvasBackground(val label: String) { THEME("Theme"), BLACK("Black"), DARK_GRAY("Dark gray"), MID_GRAY("Mid gray") }

/** Mouse-wheel behavior on the canvas. */
@Serializable
enum class WheelBehavior(val label: String) { SCROLL("Scroll (Ctrl zooms)"), ZOOM("Zoom") }

/** Action for the stylus barrel button while drawing. */
@Serializable
enum class StylusBarrelAction(val label: String) { EYEDROPPER("Eyedropper"), PAN("Pan canvas") }

/** ICC rendering intents. */
@Serializable
enum class RenderingIntent(val label: String) {
    PERCEPTUAL("Perceptual"),
    RELATIVE_COLORIMETRIC("Relative colorimetric"),
    SATURATION("Saturation"),
    ABSOLUTE_COLORIMETRIC("Absolute colorimetric"),
}

@Serializable
data class UiPreferences(
    val theme: ThemeMode = ThemeMode.DARK,
    val density: UiDensityMode = UiDensityMode.AUTO,
    val canvasBackground: CanvasBackground = CanvasBackground.THEME,
    val wheel: WheelBehavior = WheelBehavior.SCROLL,
    /** Share of device RAM the engine may use for tiles/undo before spilling to disk. */
    val memoryBudgetPercent: Int = 40,
    val defaultWorkingSpace: WorkingSpace = WorkingSpace.DISPLAY_P3,
    val renderingIntent: RenderingIntent = RenderingIntent.RELATIVE_COLORIMETRIC,
    /** Input-to-brush pressure calibration shared by all tools and devices. */
    val stylusPressureCurve: Curve = Curve.Identity,
    val stylusBarrelAction: StylusBarrelAction = StylusBarrelAction.EYEDROPPER,
) {
    companion object {
        val MemoryBudgetRange = 20..70
    }
}
