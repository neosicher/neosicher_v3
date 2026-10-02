package com.neosicher.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta NEOSICHER.
 *
 * Aproximada a la referencia visual: fondo azul-negro muy oscuro, superficies
 * azul oscuro, acento azul claro con glow sutil, texto blanco/azul grisáceo.
 * Los valores son aproximaciones propias, no recursos extraídos de la imagen.
 */
object NeoColors {
    // Fondos y superficies
    val Background = Color(0xFF060B18)       // azul-negro casi negro (fondo principal)
    val BackgroundElevated = Color(0xFF0A1122)
    val Surface = Color(0xFF0E1526)          // paneles oscuros
    val SurfaceVariant = Color(0xFF141D33)   // superficies secundarias (azul grisáceo oscuro)
    val SurfaceHigh = Color(0xFF1A2540)

    // Acentos
    val Accent = Color(0xFF5AC8FA)           // azul claro principal
    val AccentBright = Color(0xFF7FE1FF)     // azul claro brillante (glow / highlights)
    val AccentDim = Color(0xFF2E6E8E)        // azul apagado

    // Logotipo (azul claro de la marca NEOSICHER)
    val Logo = Color(0xFFA9CFF5)

    // Texto
    val TextPrimary = Color(0xFFF2F6FF)      // blanco levemente azulado
    val TextSecondary = Color(0xFF9FB2CC)    // azul grisáceo claro
    val TextTertiary = Color(0xFF5E7290)     // azul grisáceo apagado (placeholders "--")

    // Bordes
    val Border = Color(0x33355070)           // azul oscuro con baja opacidad
    val BorderStrong = Color(0x554E80B0)

    // Estados
    val Error = Color(0xFFFF5B6E)            // rojo/rosado claramente diferenciable
    val Warning = Color(0xFFFFC15A)          // ámbar (permiso requerido, etc.)
    val Success = Color(0xFF4BE0A2)          // verde (conectado / OK)
    val Neutral = Color(0xFF6B7C99)          // gris azulado (sin lectura / no disponible)

    // Glow (usos con baja opacidad)
    val AccentGlow = Color(0x335AC8FA)
    val AccentGlowSoft = Color(0x1A5AC8FA)
}
