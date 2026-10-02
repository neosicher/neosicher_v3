package com.neosicher.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neosicher.app.R
import com.neosicher.app.ui.CameraMode
import com.neosicher.app.ui.theme.NeoColors
import com.neosicher.app.ui.theme.NeoDimens

/**
 * Barra superior: logo NEOSICHER + selector de modo + conectividad + batería.
 * Componente puro: recibe datos y callbacks por parámetro.
 */
@Composable
fun NeosicherTopBar(
    mode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    thermalConnected: Boolean,
    batteryPercent: Int?,
    isCharging: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NeoDimens.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeosicherLogo()

        // El selector va centrado (como en la referencia). Se logra con dos
        // Spacers de igual peso a ambos lados.
        if (!compact) {
            Spacer(Modifier.weight(1f))
            NeosicherModeSelector(
                mode = mode,
                onModeSelected = onModeSelected,
            )
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }

        ConnectionIndicator(
            icon = if (thermalConnected) Icons.Filled.Usb else Icons.Filled.Wifi,
            active = thermalConnected,
        )
        BatteryStatus(percent = batteryPercent, isCharging = isCharging)
    }
}

/**
 * Logotipo NEOSICHER: símbolo (ojo/lente) + wordmark con tagline, en el azul
 * claro de la marca. Recreación vectorial basada en el logo proporcionado.
 */
@Composable
private fun NeosicherLogo(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_neosicher_mark),
            contentDescription = "NEOSICHER",
            modifier = Modifier.size(30.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "NEOSICHER",
                color = NeoColors.Logo,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "SLEEP LIKE A BABY",
                color = NeoColors.Logo.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Selector segmentado "Cámara Android" / "Cámara termográfica".
 */
@Composable
fun NeosicherModeSelector(
    mode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(NeoDimens.PillCorner))
            .background(NeoColors.SurfaceVariant)
            .border(1.dp, NeoColors.Border, RoundedCornerShape(NeoDimens.PillCorner))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SegmentButton(
            label = "Cámara Android",
            icon = Icons.Filled.PhotoCamera,
            selected = mode == CameraMode.ANDROID,
            onClick = { onModeSelected(CameraMode.ANDROID) },
        )
        SegmentButton(
            label = "Cámara termográfica",
            icon = Icons.Filled.Sensors,
            selected = mode == CameraMode.THERMOGRAPHIC,
            onClick = { onModeSelected(CameraMode.THERMOGRAPHIC) },
        )
    }
}

@Composable
private fun SegmentButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) NeoColors.Accent else androidx.compose.ui.graphics.Color.Transparent,
        label = "segBg",
    )
    val fg by animateColorAsState(
        if (selected) NeoColors.Background else NeoColors.TextSecondary,
        label = "segFg",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NeoDimens.PillCorner))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(
            text = label,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BatteryStatus(percent: Int?, isCharging: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
            contentDescription = "Batería",
            tint = if (isCharging) NeoColors.Success else NeoColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = percent?.let { "$it%" } ?: "--",
            color = NeoColors.TextSecondary,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
    }
}
