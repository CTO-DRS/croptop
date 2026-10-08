package app.croptop.core.designsystem.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class ThemePreference { System, Dark, Light }

/**
 * Root theme. Provides CropTop tokens and a matching Material 3 scheme so any stock M3 component
 * that slips in still looks flat and neutral.
 */
@Composable
fun CropTopTheme(
    theme: ThemePreference = ThemePreference.Dark,
    density: AeDensity = rememberAeDensity(),
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Dark -> true
        ThemePreference.Light -> false
    }
    val colors = if (dark) DarkAeColors else LightAeColors
    val typography = remember { AeTypography() }
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun readReducedMotion() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reducedMotion by remember(resolver) { mutableStateOf(readReducedMotion()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reducedMotion = readReducedMotion()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reducedMotion = readReducedMotion()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val motion = if (reducedMotion) AeMotion.Reduced else AeMotion()
    val m3 = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.app, onBackground = colors.textPrimary,
            surface = colors.surface1, onSurface = colors.textPrimary, surfaceVariant = colors.surface3,
            onSurfaceVariant = colors.textSecondary, outline = colors.lineStrong, outlineVariant = colors.lineHairline,
            error = colors.danger, surfaceContainer = colors.surface2, surfaceContainerHigh = colors.surface3,
            surfaceContainerHighest = colors.surface4, surfaceContainerLow = colors.surface1, surfaceContainerLowest = colors.app,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.app, onBackground = colors.textPrimary,
            surface = colors.surface1, onSurface = colors.textPrimary, surfaceVariant = colors.surface3,
            onSurfaceVariant = colors.textSecondary, outline = colors.lineStrong, outlineVariant = colors.lineHairline,
            error = colors.danger, surfaceContainer = colors.surface2, surfaceContainerHigh = colors.surface3,
            surfaceContainerHighest = colors.surface4, surfaceContainerLow = colors.surface1, surfaceContainerLowest = colors.app,
        )
    }
    MaterialTheme(colorScheme = m3) {
        CompositionLocalProvider(
            LocalAeColors provides colors,
            LocalAeTypography provides typography,
            LocalAeShapes provides AeShapes(),
            LocalAeMotion provides motion,
            LocalAeDensity provides density,
            // Flat: no ripples. Components draw their own hover/pressed surface steps.
            LocalIndication provides AeIndication,
            LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accentMuted),
            content = content,
        )
    }
}

/** Token accessors: `Ae.colors.surface1`, `Ae.type.body`, … */
object Ae {
    val colors: AeColors
        @Composable @ReadOnlyComposable
        get() = LocalAeColors.current
    val type: AeTypography
        @Composable @ReadOnlyComposable
        get() = LocalAeTypography.current
    val shapes: AeShapes
        @Composable @ReadOnlyComposable
        get() = LocalAeShapes.current
    val motion: AeMotion
        @Composable @ReadOnlyComposable
        get() = LocalAeMotion.current
    val density: AeDensity
        @Composable @ReadOnlyComposable
        get() = LocalAeDensity.current
    val spacing: AeSpacing get() = AeSpacing
}
