package app.croptop.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.croptop.core.designsystem.theme.AeColors
import app.croptop.core.designsystem.theme.DarkAeColors
import app.croptop.core.designsystem.theme.LightAeColors
import kotlin.test.Test
import kotlin.test.assertTrue

/** WCAG 2.x contrast for the token pairs the UI actually uses; text requires at least 4.5:1. */
class ContrastTest {
    private fun ratio(fg: Color, bg: Color): Double {
        val a = fg.compositeOver(bg).luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b)
    }

    private fun check(name: String, c: AeColors) {
        val surfaces = mapOf("app" to c.app, "surface1" to c.surface1, "surface2" to c.surface2, "surface3" to c.surface3)
        surfaces.forEach { (s, bg) ->
            assertTrue(ratio(c.textPrimary, bg) >= 7.0, "$name textPrimary on $s = ${ratio(c.textPrimary, bg)}")
            assertTrue(ratio(c.textSecondary, bg) >= 4.5, "$name textSecondary on $s = ${ratio(c.textSecondary, bg)}")
            // Tertiary is for non-essential hints only; still keep it legible (large-text threshold).
            assertTrue(ratio(c.textTertiary, bg) >= 3.0, "$name textTertiary on $s = ${ratio(c.textTertiary, bg)}")
        }
        assertTrue(ratio(c.onAccent, c.accent) >= 3.0, "$name onAccent on accent = ${ratio(c.onAccent, c.accent)}")
        assertTrue(ratio(c.accent, c.surface1) >= 3.0, "$name accent (focus ring) on surface1 = ${ratio(c.accent, c.surface1)}")
    }

    @Test fun dark() = check("dark", DarkAeColors)

    @Test fun light() = check("light", LightAeColors)
}
