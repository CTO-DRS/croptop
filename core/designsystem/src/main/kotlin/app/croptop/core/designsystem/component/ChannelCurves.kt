package app.croptop.core.designsystem.component

import app.croptop.core.model.Curve

/** The composite curve or one of the RGB component curves. */
enum class CurveChannel(val label: String) {
    RGB("RGB"),
    RED("Red"),
    GREEN("Green"),
    BLUE("Blue"),
}

/** UI state for the four independently editable curves shown by [AeCurveEditor]. */
data class ChannelCurves(
    val rgb: Curve = Curve.Identity,
    val red: Curve = Curve.Identity,
    val green: Curve = Curve.Identity,
    val blue: Curve = Curve.Identity,
) {
    operator fun get(channel: CurveChannel): Curve = when (channel) {
        CurveChannel.RGB -> rgb
        CurveChannel.RED -> red
        CurveChannel.GREEN -> green
        CurveChannel.BLUE -> blue
    }

    fun withCurve(channel: CurveChannel, curve: Curve): ChannelCurves = when (channel) {
        CurveChannel.RGB -> copy(rgb = curve)
        CurveChannel.RED -> copy(red = curve)
        CurveChannel.GREEN -> copy(green = curve)
        CurveChannel.BLUE -> copy(blue = curve)
    }
}
