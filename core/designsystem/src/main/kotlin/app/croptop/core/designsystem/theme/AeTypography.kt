package app.croptop.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.croptop.core.designsystem.R

private fun inter(weight: FontWeight) = Font(
    R.font.inter_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Inter variable, bundled. */
val InterFamily = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_variable, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono_variable, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: FontWeight, family: FontFamily = InterFamily, tracking: Double = 0.0) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    lineHeightStyle = Trim,
    // Tabular numerals are applied only to numeric fields (AeNumberField); globally they widen hyphens.
    fontFeatureSettings = "cv11",
)

/** Type scale — */
@Immutable
data class AeTypography(
    val display: TextStyle = style(28, 34, FontWeight.SemiBold, tracking = -0.01),
    val title: TextStyle = style(17, 22, FontWeight.SemiBold),
    val body: TextStyle = style(13, 18, FontWeight.Normal),
    val label: TextStyle = style(12, 16, FontWeight.Medium),
    val caption: TextStyle = style(11, 14, FontWeight.Normal),
    val micro: TextStyle = style(10, 12, FontWeight.Medium),
    /** Panel headers: label, uppercase at the call site, +4% tracking. */
    val panelHeader: TextStyle = style(11, 14, FontWeight.SemiBold, tracking = 0.04),
    val mono: TextStyle = style(12, 16, FontWeight.Normal, family = JetBrainsMonoFamily),
)

internal val LocalAeTypography = staticCompositionLocalOf { AeTypography() }
