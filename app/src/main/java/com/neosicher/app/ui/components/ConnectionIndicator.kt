package com.neosicher.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.neosicher.app.ui.theme.NeoColors

/**
 * Indicador circular de conectividad con glow suave. Puro.
 */
@Composable
fun ConnectionIndicator(
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "conn")
    val pulse by transition.animateFloatAsState(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connPulse",
    )

    val tint = if (active) NeoColors.Success else NeoColors.TextTertiary
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (active) NeoColors.AccentGlowSoft else NeoColors.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (active) "Conectado" else "Sin conexión",
            tint = tint,
            modifier = Modifier
                .size(16.dp)
                .alpha(if (active) pulse else 1f),
        )
    }
}
