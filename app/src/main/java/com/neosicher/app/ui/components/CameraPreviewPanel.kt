package com.neosicher.app.ui.components

import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.neosicher.app.camera.CameraManager
import com.neosicher.app.camera.CameraPermissionState
import com.neosicher.app.camera.CameraState
import com.neosicher.app.thermal.ThermalCameraState
import com.neosicher.app.thermal.ThermalConnectionStatus
import com.neosicher.app.ui.CameraMode
import com.neosicher.app.ui.theme.NeoColors
import com.neosicher.app.ui.theme.NeoDimens
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panel grande del visor. Muestra:
 *  - Modo ANDROID: preview REAL de CameraX (o el estado de permiso).
 *  - Modo THERMOGRAPHIC: el estado del GW192A (nunca imagen térmica inventada).
 *
 * Overlays: etiqueta de fuente, timestamp en vivo, indicador HD y botón fullscreen.
 */
@Composable
fun CameraPreviewPanel(
    mode: CameraMode,
    cameraManager: CameraManager,
    cameraState: CameraState,
    thermalState: ThermalCameraState,
    onRequestCameraPermission: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NeoDimens.PanelCorner))
            .background(NeoColors.BackgroundElevated)
            .border(
                NeoDimens.BorderWidth,
                NeoColors.Border,
                RoundedCornerShape(NeoDimens.PanelCorner),
            ),
    ) {
        when (mode) {
            CameraMode.ANDROID -> AndroidCameraContent(
                cameraManager = cameraManager,
                cameraState = cameraState,
                onRequestCameraPermission = onRequestCameraPermission,
            )
            CameraMode.THERMOGRAPHIC -> ThermalContent(thermalState = thermalState)
        }

        // --- Overlays comunes ---
        // Etiqueta de fuente arriba-izquierda (como en la referencia).
        val sourceLabel = if (mode == CameraMode.ANDROID) "Cámara Android" else "GW192A · Térmica"
        val sourceIcon = if (mode == CameraMode.ANDROID) Icons.Filled.PhotoCamera else Icons.Filled.Sensors
        SourceLabel(
            text = sourceLabel,
            icon = sourceIcon,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
        )

        // Abajo-izquierda: timestamp + indicador HD (como en la referencia).
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiveTimestamp()
            if (mode == CameraMode.ANDROID && cameraState.isPreviewActive) {
                HdIndicator()
            }
        }

        FullscreenButton(
            onClick = onFullscreen,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
        )
    }
}

// -- Cámara Android (preview real) -----------------------------------------

@Composable
private fun AndroidCameraContent(
    cameraManager: CameraManager,
    cameraState: CameraState,
    onRequestCameraPermission: () -> Unit,
) {
    when (cameraState.permission) {
        CameraPermissionState.GRANTED -> {
            val lifecycleOwner = LocalLifecycleOwner.current
            val previewView = remember {
                PreviewView(cameraManager.appContextForPreview()).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            }
            // Contenedor que aloja el PreviewView. El overlay de detección de
            // postura (CameraManager.previewOverlay) se añade como HERMANO del
            // PreviewView dentro de este contenedor, NUNCA como hijo suyo:
            // CameraX ejecuta removeAllViews() sobre el PreviewView al arrancar
            // la cámara y borraría cualquier hijo. Sin efecto visual propio.
            val previewContainer = remember(previewView) {
                FrameLayout(cameraManager.appContextForPreview()).apply {
                    // Imprescindible: AndroidView mide la vista con SUS layoutParams.
                    // Sin esto el contenedor queda en WRAP_CONTENT y el visor
                    // (y el overlay) se encogen al tamaño de la superficie.
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    addView(
                        previewView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            }
            AndroidView(
                factory = { previewContainer },
                modifier = Modifier.fillMaxSize(),
            )
            // Vincular / re-vincular cuando cambie la lente.
            LaunchedEffect(cameraState.lens) {
                cameraManager.bindPreview(lifecycleOwner, previewView)
            }
            cameraState.errorMessage?.let { err ->
                CenteredState(
                    icon = Icons.Filled.VideocamOff,
                    title = "Error de cámara",
                    subtitle = err,
                    tint = NeoColors.Error,
                )
            }
        }
        else -> {
            CenteredState(
                icon = Icons.Filled.CameraAlt,
                title = "Cámara no activa",
                subtitle = if (cameraState.permission == CameraPermissionState.PERMANENTLY_DENIED) {
                    "Permiso de cámara denegado. Actívalo en Ajustes."
                } else {
                    "Se requiere permiso de cámara"
                },
                tint = NeoColors.Accent,
                actionLabel = if (cameraState.permission != CameraPermissionState.PERMANENTLY_DENIED) {
                    "Conceder permiso"
                } else null,
                onAction = onRequestCameraPermission,
            )
        }
    }
}

// -- Cámara térmica (stream experimental real, o estado honesto) ------------

@Composable
private fun ThermalContent(thermalState: ThermalCameraState) {
    val frame = thermalState.lastFrame
    if (thermalState.status == ThermalConnectionStatus.STREAMING && frame != null) {
        ThermalStreamContent(frame = frame)
        return
    }

    val (icon, tint) = when (thermalState.status) {
        ThermalConnectionStatus.READY,
        ThermalConnectionStatus.CONNECTED,
        ThermalConnectionStatus.STREAMING -> Icons.Filled.Sensors to NeoColors.Success
        ThermalConnectionStatus.ERROR,
        ThermalConnectionStatus.UNSUPPORTED -> Icons.Filled.UsbOff to NeoColors.Error
        else -> Icons.Filled.Sensors to NeoColors.TextTertiary
    }
    CenteredState(
        icon = icon,
        title = thermalState.message,
        subtitle = thermalState.errorDetail
            ?: "No se muestran datos térmicos hasta confirmar el protocolo del GW192A.",
        tint = tint,
    )
}

/**
 * Muestra el stream térmico REAL (frames recibidos del GW192A por USB),
 * interpretado bajo la hipótesis EXPERIMENTAL de doble altura (ver
 * ThermalFrameInterpreter). Se prioriza la paleta de calor si está
 * disponible; si no, se muestra la imagen visible decodificada.
 *
 * Se marca de forma visible y permanente como "experimental / sin calibrar":
 * los valores mostrados son counts crudos relativos del propio frame, NUNCA
 * temperatura en grados ni un diagnóstico.
 */
@Composable
private fun ThermalStreamContent(frame: com.neosicher.app.thermal.ThermalFrameResult) {
    val bitmap = frame.thermalPaletteBitmap ?: frame.visibleBitmap
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Stream térmico experimental (sin calibrar)",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        )

        // Aviso permanente: nunca se debe interpretar esto como temperatura real.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .clip(RoundedCornerShape(NeoDimens.PillCorner))
                .background(NeoColors.Warning.copy(alpha = 0.9f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (frame.rawMin != null && frame.rawMax != null) {
                    "Experimental · sin calibrar · raw[${frame.rawMin}–${frame.rawMax}]"
                } else {
                    "Experimental · sin calibrar"
                },
                color = NeoColors.Background,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// -- Piezas de overlay / estado --------------------------------------------

@Composable
private fun SourceLabel(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NeoDimens.PillCorner))
            .background(NeoColors.Background.copy(alpha = 0.5f))
            .border(1.dp, NeoColors.Border, RoundedCornerShape(NeoDimens.PillCorner))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, contentDescription = null, tint = NeoColors.TextPrimary, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            color = NeoColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LiveTimestamp(modifier: Modifier = Modifier) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        text = formatter.format(Date(now)),
        color = NeoColors.TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(RoundedCornerShape(NeoDimens.PillCorner))
            .background(NeoColors.Background.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun HdIndicator(modifier: Modifier = Modifier) {
    Text(
        text = "HD",
        color = NeoColors.Background,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NeoColors.AccentBright)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun FullscreenButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(NeoColors.Background.copy(alpha = 0.55f))
            .border(1.dp, NeoColors.Border, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Fullscreen,
            contentDescription = "Pantalla completa",
            tint = NeoColors.TextPrimary,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onClick),
        )
    }
}

@Composable
private fun CenteredState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.size(14.dp))
        Text(
            text = title,
            color = NeoColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = subtitle,
            color = NeoColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(NeoDimens.PillCorner))
                    .background(NeoColors.Accent)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = actionLabel,
                    color = NeoColors.Background,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}


