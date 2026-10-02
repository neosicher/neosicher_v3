package com.neosicher.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neosicher.app.camera.CameraManager
import com.neosicher.app.camera.CameraLens
import com.neosicher.app.camera.CameraPermissionState
import com.neosicher.app.monitoring.ThermalReading
import com.neosicher.app.monitoring.ThermalReadingStatus
import com.neosicher.app.monitoring.ThermalSource
import com.neosicher.app.thermal.CalibrationPointKind
import com.neosicher.app.thermal.Gw192aThermalCameraDataSource
import com.neosicher.app.thermal.ThermalCalibration
import com.neosicher.app.thermal.ThermalCalibrationPoint
import com.neosicher.app.thermal.ThermalCameraRepository
import com.neosicher.app.usb.UsbDeviceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel de la pantalla principal.
 *
 * Orquesta la cámara Android ([CameraManager]) y la cámara térmica GW192A
 * ([ThermalCameraRepository]) y expone un único [NeosicherUiState] observable.
 *
 * Las métricas (vitales, sueño, lectura térmica) permanecen SIN fuente real:
 * este MVP no las produce y por tanto no se muestran valores inventados.
 */
class NeosicherViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext

    val cameraManager = CameraManager(appContext)

    private val usbManager = UsbDeviceManager(appContext)
    private val thermalRepository = ThermalCameraRepository(
        Gw192aThermalCameraDataSource(usbManager, viewModelScope)
    )

    private val _uiState = MutableStateFlow(NeosicherUiState())
    val uiState: StateFlow<NeosicherUiState> = _uiState.asStateFlow()

    // Calibración del usuario (3 puntos: frío, caliente, corporal). Vive aparte
    // de NeosicherUiState a propósito: su pantalla es independiente y no debe
    // tocar nada del dashboard principal salvo el resultado final (ver abajo).
    private val _calibration = MutableStateFlow(
        ThermalCalibration(
            bodyPoint = null, // el usuario fija el punto corporal explícitamente desde la pantalla de calibración
        )
    )
    val calibration: StateFlow<ThermalCalibration> = _calibration.asStateFlow()

    private var batteryReceiver: BroadcastReceiver? = null

    init {
        // Fusionar el estado de la cámara Android en el UI state.
        viewModelScope.launch {
            cameraManager.state.collect { camera ->
                _uiState.update { it.copy(cameraState = camera) }
            }
        }
        // Fusionar el estado de la cámara térmica.
        viewModelScope.launch {
            thermalRepository.state.collect { thermal ->
                _uiState.update { it.copy(thermalState = thermal) }
                // Cada frame nuevo: si hay calibración suficiente, derivar la
                // temperatura aproximada del punto central y publicarla en la
                // tarjeta de Temperatura, SIN tocar ningún otro componente de
                // la interfaz. Sin calibración suficiente, se mantiene "--".
                applyCalibrationToLatestFrame(thermal.lastFrame?.sampleCenterPoint())
            }
        }
        // Fusionar la lectura térmica base (siempre NONE desde la fuente: no
        // hay protocolo radiométrico confirmado). La calibración del usuario
        // la complementa por separado, arriba.
        viewModelScope.launch {
            thermalRepository.reading.collect { reading ->
                _uiState.update { it.copy(thermalReading = reading) }
            }
        }
        registerBattery()
    }

    /**
     * Si la calibración del usuario tiene suficientes puntos, convierte el
     * valor crudo del punto central a una temperatura aproximada y actualiza
     * SOLO el campo de temperatura del estado (no afecta cardiaca/respiratoria
     * ni ningún otro componente visual).
     */
    private fun applyCalibrationToLatestFrame(centerRaw: Double?) {
        val calib = _calibration.value
        if (!calib.isCalibrated || centerRaw == null) {
            if (_uiState.value.thermalReading.status != ThermalReadingStatus.NO_READING) {
                _uiState.update {
                    it.copy(thermalReading = ThermalReading.NONE)
                }
            }
            return
        }
        val celsius = calib.toCelsius(centerRaw) ?: return
        _uiState.update {
            it.copy(
                thermalReading = ThermalReading(
                    temperatureCelsius = celsius,
                    timestampMillis = System.currentTimeMillis(),
                    confidence = if (calib.hasAssumedPoint) 0.3 else 0.6,
                    source = ThermalSource.GW192A,
                    // Siempre EXPERIMENTAL: es una aproximación de 2-3 puntos
                    // sobre un sensor no radiométrico, nunca una medición
                    // médica certificada (ver docs/GW192A_INVESTIGACION.md §6, §14).
                    status = ThermalReadingStatus.EXPERIMENTAL,
                )
            )
        }
    }

    // -- Calibración térmica (pantalla independiente) -----------------------

    /** Último valor crudo (0..255) del punto central del frame actual, o null si no hay stream. */
    fun latestCenterRawValue(): Double? = _uiState.value.thermalState.lastFrame?.sampleCenterPoint()

    fun setColdCalibrationPoint(rawValue: Double, knownTemperatureCelsius: Double) {
        _calibration.update {
            it.copy(coldPoint = ThermalCalibrationPoint("Frío conocido", rawValue, knownTemperatureCelsius))
        }
    }

    fun setHotCalibrationPoint(rawValue: Double, knownTemperatureCelsius: Double) {
        _calibration.update {
            it.copy(hotPoint = ThermalCalibrationPoint("Caliente conocido", rawValue, knownTemperatureCelsius))
        }
    }

    fun setBodyCalibrationPoint(rawValue: Double, knownTemperatureCelsius: Double, isAssumed: Boolean) {
        _calibration.update {
            it.copy(bodyPoint = ThermalCalibrationPoint("Corporal", rawValue, knownTemperatureCelsius, isAssumed))
        }
    }

    fun clearCalibrationPoint(which: CalibrationPointKind) {
        _calibration.update {
            when (which) {
                CalibrationPointKind.COLD -> it.copy(coldPoint = null)
                CalibrationPointKind.HOT -> it.copy(hotPoint = null)
                CalibrationPointKind.BODY -> it.copy(bodyPoint = null)
            }
        }
    }

    // -- Selector de modo ---------------------------------------------------

    fun selectMode(mode: CameraMode) {
        _uiState.update { it.copy(cameraMode = mode) }
        if (mode == CameraMode.THERMOGRAPHIC) {
            // Al entrar en modo térmico, intentar detectar el GW192A.
            thermalRepository.detect()
        }
    }

    fun toggleMode() {
        val next = if (_uiState.value.cameraMode == CameraMode.ANDROID) {
            CameraMode.THERMOGRAPHIC
        } else {
            CameraMode.ANDROID
        }
        selectMode(next)
    }

    // -- Cámara Android -----------------------------------------------------

    fun onCameraPermissionResult(granted: Boolean) {
        cameraManager.updatePermission(
            if (granted) CameraPermissionState.GRANTED else CameraPermissionState.DENIED
        )
    }

    fun onCameraPermissionPermanentlyDenied() {
        cameraManager.updatePermission(CameraPermissionState.PERMANENTLY_DENIED)
    }

    fun toggleCameraLens(): CameraLens = cameraManager.toggleLens()

    // -- Cámara térmica -----------------------------------------------------

    fun detectThermal() = thermalRepository.detect()

    fun requestThermalPermission() = thermalRepository.requestPermission()

    fun connectThermal() = thermalRepository.connectAndInspect()

    // -- Diagnóstico --------------------------------------------------------

    fun toggleDiagnostics() {
        _uiState.update { it.copy(showDiagnostics = !it.showDiagnostics) }
    }

    // -- Batería ------------------------------------------------------------

    private fun registerBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                _uiState.update { it.copy(batteryPercent = percent, isCharging = charging) }
            }
        }
        val sticky = appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // registerReceiver con un filtro sticky devuelve el último Intent de batería.
        sticky?.let { receiver.onReceive(appContext, it) }
        batteryReceiver = receiver
    }

    override fun onCleared() {
        super.onCleared()
        batteryReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        cameraManager.release()
        thermalRepository.release()
    }
}
