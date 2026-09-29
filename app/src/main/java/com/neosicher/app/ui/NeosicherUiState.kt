package com.neosicher.app.ui

import com.neosicher.app.camera.CameraState
import com.neosicher.app.monitoring.SleepStatus
import com.neosicher.app.monitoring.ThermalReading
import com.neosicher.app.monitoring.VitalSigns
import com.neosicher.app.thermal.ThermalCameraState

/** Modo de fuente de imagen seleccionado en el selector superior. */
enum class CameraMode {
    ANDROID,       // "Cámara Android"
    THERMOGRAPHIC, // "Cámara termográfica"
}

/**
 * Estado inmutable de la pantalla principal NEOSICHER.
 *
 * Se compone del modo activo, el estado de ambas cámaras y las métricas de
 * monitoreo. Las métricas parten SIN fuente real (no se simula nada).
 */
data class NeosicherUiState(
    val cameraMode: CameraMode = CameraMode.ANDROID,
    val cameraState: CameraState = CameraState(),
    val thermalState: ThermalCameraState = ThermalCameraState.NOT_CONNECTED,
    val thermalReading: ThermalReading = ThermalReading.NONE,
    val vitalSigns: VitalSigns = VitalSigns.EMPTY,
    val sleepStatus: SleepStatus = SleepStatus.UNKNOWN,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val showDiagnostics: Boolean = false,
) {
    val isThermalMode: Boolean get() = cameraMode == CameraMode.THERMOGRAPHIC
}
