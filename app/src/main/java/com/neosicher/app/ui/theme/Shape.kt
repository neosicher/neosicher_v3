package com.neosicher.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Formas NEOSICHER: bordes redondeados generosos, estilo panel/monitor.
 */
val NeoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

object NeoDimens {
    val PanelCorner = 24.dp
    val CardCorner = 18.dp
    val PillCorner = 100.dp
    val ScreenPadding = 16.dp
    val PanelGap = 14.dp
    val CardGap = 12.dp
    val BorderWidth = 1.dp
}
