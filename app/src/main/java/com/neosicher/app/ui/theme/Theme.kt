package com.neosicher.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Esquema de color NEOSICHER mapeado sobre Material3.
 *
 * La app es intencionadamente "dark only": la estética de monitoreo requiere
 * fondo azul-negro en cualquier configuración del sistema.
 */
private val NeoColorScheme = darkColorScheme(
    primary = NeoColors.Accent,
    onPrimary = NeoColors.Background,
    secondary = NeoColors.AccentDim,
    onSecondary = NeoColors.TextPrimary,
    background = NeoColors.Background,
    onBackground = NeoColors.TextPrimary,
    surface = NeoColors.Surface,
    onSurface = NeoColors.TextPrimary,
    surfaceVariant = NeoColors.SurfaceVariant,
    onSurfaceVariant = NeoColors.TextSecondary,
    error = NeoColors.Error,
    onError = NeoColors.Background,
    outline = NeoColors.BorderStrong,
)

/**
 * Gradientes reutilizables para fondos con "profundidad" tipo dispositivo.
 */
object NeoGradients {
    val ScreenBackground: Brush
        get() = Brush.verticalGradient(
            colors = listOf(
                NeoColors.Background,
                NeoColors.BackgroundElevated,
                NeoColors.Background,
            )
        )

    val PanelSurface: Brush
        get() = Brush.verticalGradient(
            colors = listOf(
                NeoColors.SurfaceHigh,
                NeoColors.Surface,
            )
        )

    val AccentSweep: Brush
        get() = Brush.horizontalGradient(
            colors = listOf(
                NeoColors.AccentBright,
                NeoColors.Accent,
                NeoColors.AccentDim,
            )
        )
}

@Composable
fun NeosicherTheme(
    // Ignoramos el modo del sistema a propósito: NEOSICHER siempre es oscuro.
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = NeoColors.Background.toArgb()
            window.navigationBarColor = NeoColors.Background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = NeoColorScheme,
        typography = NeoTypography,
        shapes = NeoShapes,
        content = content,
    )
}
