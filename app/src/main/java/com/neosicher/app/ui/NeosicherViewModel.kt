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
import com.neosicher.app.thermal.Gw192aThermalCameraDataSource
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
            }
        }
        // Fusionar la lectura térmica (siempre NONE en este MVP).
        viewModelScope.launch {
            thermalRepository.reading.collect { reading ->
                _uiState.update { it.copy(thermalReading = reading) }
            }
        }
        registerBattery()
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
