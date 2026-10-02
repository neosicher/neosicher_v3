package com.neosicher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neosicher.app.camera.CameraManager
import com.neosicher.app.ui.components.CameraPreviewPanel
import com.neosicher.app.ui.components.CameraSwitchButton
import com.neosicher.app.ui.components.HeartRateCard
import com.neosicher.app.ui.components.MonitoringPanel
import com.neosicher.app.ui.components.NeosicherModeSelector
import com.neosicher.app.ui.components.NeosicherTopBar
import com.neosicher.app.ui.components.RespiratoryRateCard
import com.neosicher.app.ui.components.SleepCard
import com.neosicher.app.ui.components.TemperatureCard
import com.neosicher.app.ui.components.UsbDiagnosticsPanel
import com.neosicher.app.ui.theme.NeoColors
import com.neosicher.app.ui.theme.NeoDimens
import com.neosicher.app.ui.theme.NeoGradients

/**
 * Pantalla principal NEOSICHER.
 *
 * La app está fijada a orientación horizontal (ver AndroidManifest:
 * `android:screenOrientation="sensorLandscape"`), independientemente de si la
 * pantalla del sistema está bloqueada en vertical. Por eso el layout tipo
 * dashboard 70% cámara / 30% panel lateral es el predeterminado.
 *
 * Se conserva `BoxWithConstraints` como red de seguridad: en pantallas muy
 * angostas (p. ej. la pantalla de cobertura de un plegable) se apila el
 * contenido en vez de romper las proporciones. No se fijan tamaños absolutos.
 */
@Composable
fun NeosicherScreen(
    state: NeosicherUiState,
    cameraManager: CameraManager,
    onModeSelected: (CameraMode) -> Unit,
    onToggleMode: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(NeoGradients.ScreenBackground),
    ) {
        // maxWidth proviene del BoxWithConstraintsScope (this).
        // Umbral bajo (480dp) porque la app ya está forzada a horizontal:
        // casi cualquier teléfono en landscape supera esto. El modo "narrow"
        // queda solo como red de seguridad para pantallas muy angostas
        // (p. ej. pantalla de cobertura de un plegable).
        val wide = this.maxWidth >= 480.dp

        // La app corre en modo inmersivo (barras del sistema ocultas, ver
        // MainActivity.enableImmersiveMode), por lo que tiene prioridad total
        // sobre la pantalla. Se añade un pequeño padding de seguridad solo para
        // el notch/cutout, de modo que el contenido no quede bajo una muesca,
        // sin reservar espacio para barras que ya no se muestran.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
        ) {
            NeosicherTopBar(
                mode = state.cameraMode,
                onModeSelected = onModeSelected,
                thermalConnected = state.thermalState.isDeviceKnown,
                batteryPercent = state.batteryPercent,
                isCharging = state.isCharging,
                compact = !wide,
            )

            if (wide) {
                WideContent(
                    state = state,
                    cameraManager = cameraManager,
                    onToggleMode = onToggleMode,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onToggleDiagnostics = onToggleDiagnostics,
                    onFullscreen = onFullscreen,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(NeoDimens.ScreenPadding),
                )
            } else {
                NarrowContent(
                    state = state,
                    cameraManager = cameraManager,
                    onModeSelected = onModeSelected,
                    onToggleMode = onToggleMode,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onToggleDiagnostics = onToggleDiagnostics,
                    onFullscreen = onFullscreen,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(NeoDimens.ScreenPadding),
                )
            }
        }
    }
}

/**
 * Composición horizontal en DOS COLUMNAS:
 *  - Izquierda: visor de cámara grande + barra inferior (diagnóstico + cambio).
 *  - Derecha: cuadrícula 2×2 con las cuatro tarjetas de métricas.
 */
@Composable
private fun WideContent(
    state: NeosicherUiState,
    cameraManager: CameraManager,
    onToggleMode: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NeoDimens.PanelGap),
    ) {
        // --- Columna izquierda: cámara + controles inferiores ---
        Column(
            modifier = Modifier
                .weight(0.62f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(NeoDimens.PanelGap),
        ) {
            CameraPreviewPanel(
                mode = state.cameraMode,
                cameraManager = cameraManager,
                cameraState = state.cameraState,
                thermalState = state.thermalState,
                onRequestCameraPermission = onRequestCameraPermission,
                onFullscreen = onFullscreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            if (state.showDiagnostics) {
                UsbDiagnosticsPanel(
                    thermalState = state.thermalState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NeoDimens.CardGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiagnosticsToggle(
                    shown = state.showDiagnostics,
                    onClick = onToggleDiagnostics,
                    modifier = Modifier.weight(0.4f),
                )
                CameraSwitchButton(
                    targetMode = targetMode(state.cameraMode),
                    label = switchLabel(state.cameraMode),
                    onClick = onToggleMode,
                    modifier = Modifier.weight(0.6f),
                )
            }
        }

        // --- Columna derecha: cuadrícula 2×2 de métricas ---
        MonitoringGrid(
            state = state,
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight(),
        )
    }
}

/**
 * Cuadrícula 2×2 con las cuatro tarjetas de métricas. Cada tarjeta ocupa un
 * cuadrante por igual (dos filas de peso 1, dos columnas de peso 1), siempre
 * visibles sin scroll.
 */
@Composable
private fun MonitoringGrid(
    state: NeosicherUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NeoDimens.CardGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(NeoDimens.CardGap),
        ) {
            TemperatureCard(state.thermalReading, Modifier.weight(1f).fillMaxHeight())
            HeartRateCard(state.vitalSigns.heartRate, Modifier.weight(1f).fillMaxHeight())
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(NeoDimens.CardGap),
        ) {
            RespiratoryRateCard(state.vitalSigns.respiratoryRate, Modifier.weight(1f).fillMaxHeight())
            SleepCard(state.sleepStatus, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Composición vertical apilada para teléfono en retrato. */
@Composable
private fun NarrowContent(
    state: NeosicherUiState,
    cameraManager: CameraManager,
    onModeSelected: (CameraMode) -> Unit,
    onToggleMode: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NeoDimens.PanelGap),
    ) {
        // En estrecho, el selector va bajo la barra (la barra lo oculta en compact).
        NeosicherModeSelector(
            mode = state.cameraMode,
            onModeSelected = onModeSelected,
            modifier = Modifier.fillMaxWidth(),
        )

        CameraPreviewPanel(
            mode = state.cameraMode,
            cameraManager = cameraManager,
            cameraState = state.cameraState,
            thermalState = state.thermalState,
            onRequestCameraPermission = onRequestCameraPermission,
            onFullscreen = onFullscreen,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f),
        )

        MonitoringPanel(
            thermalReading = state.thermalReading,
            vitalSigns = state.vitalSigns,
            sleepStatus = state.sleepStatus,
            twoColumns = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.showDiagnostics) {
            UsbDiagnosticsPanel(
                thermalState = state.thermalState,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        DiagnosticsToggle(state.showDiagnostics, onToggleDiagnostics)
        CameraSwitchButton(
            targetMode = targetMode(state.cameraMode),
            label = switchLabel(state.cameraMode),
            onClick = onToggleMode,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DiagnosticsToggle(shown: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = if (shown) "Ocultar diagnóstico USB" else "Mostrar diagnóstico USB",
        color = NeoColors.Accent,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        textAlign = TextAlign.Center,
    )
}

private fun switchLabel(mode: CameraMode): String = when (mode) {
    CameraMode.ANDROID -> "Cambiar a cámara termográfica"
    CameraMode.THERMOGRAPHIC -> "Cambiar a cámara Android"
}

/** Modo al que se cambia al pulsar el botón (el opuesto al actual). */
private fun targetMode(mode: CameraMode): CameraMode = when (mode) {
    CameraMode.ANDROID -> CameraMode.THERMOGRAPHIC
    CameraMode.THERMOGRAPHIC -> CameraMode.ANDROID
}
