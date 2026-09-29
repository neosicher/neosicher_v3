package com.neosicher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neosicher.app.camera.CameraManager
import com.neosicher.app.ui.components.CameraPreviewPanel
import com.neosicher.app.ui.components.CameraSwitchButton
import com.neosicher.app.ui.components.MonitoringPanel
import com.neosicher.app.ui.components.NeosicherModeSelector
import com.neosicher.app.ui.components.NeosicherTopBar
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

        Column(modifier = Modifier.fillMaxSize()) {
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

/** Composición horizontal tipo dashboard: ~70% cámara / ~30% panel. */
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
        CameraPreviewPanel(
            mode = state.cameraMode,
            cameraManager = cameraManager,
            cameraState = state.cameraState,
            thermalState = state.thermalState,
            onRequestCameraPermission = onRequestCameraPermission,
            onFullscreen = onFullscreen,
            modifier = Modifier
                .weight(0.7f)
                .fillMaxHeight(),
        )

        Column(
            modifier = Modifier
                .weight(0.3f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(NeoDimens.CardGap),
        ) {
            MonitoringPanel(
                thermalReading = state.thermalReading,
                vitalSigns = state.vitalSigns,
                sleepStatus = state.sleepStatus,
                twoColumns = false,
                modifier = Modifier.weight(1f, fill = false),
            )

            if (state.showDiagnostics) {
                UsbDiagnosticsPanel(
                    thermalState = state.thermalState,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.weight(1f))

            DiagnosticsToggle(state.showDiagnostics, onToggleDiagnostics)
            CameraSwitchButton(
                targetMode = targetMode(state.cameraMode),
                label = switchLabel(state.cameraMode),
                onClick = onToggleMode,
            )
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
private fun DiagnosticsToggle(shown: Boolean, onClick: () -> Unit) {
    Text(
        text = if (shown) "Ocultar diagnóstico USB" else "Mostrar diagnóstico USB",
        color = NeoColors.Accent,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
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
