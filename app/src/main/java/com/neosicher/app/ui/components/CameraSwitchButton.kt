package com.neosicher.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neosicher.app.ui.CameraMode
import com.neosicher.app.ui.theme.NeoColors
import com.neosicher.app.ui.theme.NeoDimens

/**
 * Botón inferior para cambiar de cámara (Android <-> termográfica).
 *
 * Estilizado como una tarjeta del panel (fondo oscuro con borde), coherente con
 * las tarjetas de métricas de la referencia: icono del modo DESTINO a la
 * izquierda, etiqueta centrada y flechas de intercambio a la derecha.
 * Personalizado (no botón Material estándar). Puro.
 *
 * @param targetMode modo al que se cambiará al pulsar (define icono y texto).
 */
@Composable
fun CameraSwitchButton(
    targetMode: CameraMode,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leadingIcon: ImageVector = when (targetMode) {
        CameraMode.ANDROID -> Icons.Filled.PhotoCamera
        CameraMode.THERMOGRAPHIC -> Icons.Filled.Sensors
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(NeoDimens.CardCorner))
            .background(
                Brush.verticalGradient(listOf(NeoColors.SurfaceHigh, NeoColors.Surface))
            )
            .border(1.dp, NeoColors.BorderStrong, RoundedCornerShape(NeoDimens.CardCorner))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = NeoColors.Accent,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            color = NeoColors.TextPrimary,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.SwapHoriz,
            contentDescription = null,
            tint = NeoColors.Accent,
            modifier = Modifier.size(22.dp),
        )
    }
}
