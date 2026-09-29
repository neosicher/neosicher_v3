package com.neosicher.app.thermal

import android.hardware.usb.UsbDevice
import android.util.Log
import com.neosicher.app.monitoring.ThermalReading
import com.neosicher.app.usb.UsbDeviceManager
import com.neosicher.app.usb.UsbEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implementación de [ThermalCameraDataSource] para el GOYOJO GW192A por USB OTG.
 *
 * Alcance de este MVP (ver docs/GW192A_INVESTIGACION.md §10 — "NO INVENTAR EL
 * PROTOCOLO"):
 *
 *   NOT_CONNECTED → DETECTING → CONNECTED → PERMISSION_REQUIRED → READY
 *
 * En READY la conexión se abrió y se enumeraron interfaces y endpoints. AHÍ SE
 * DETIENE el flujo real. NO se inicia streaming, NO se envían comandos, NO se
 * interpretan frames ni se producen temperaturas: eso REQUIERE PRUEBA EN
 * POCO F7 + GW192A y un protocolo confirmado.
 *
 * Por diseño, [reading] permanece siempre en [ThermalReading.NONE]: no se generan
 * valores térmicos falsos.
 */
class Gw192aThermalCameraDataSource(
    private val usbManager: UsbDeviceManager,
    private val scope: CoroutineScope,
) : ThermalCameraDataSource {

    private val _state = MutableStateFlow(ThermalCameraState.NOT_CONNECTED)
    override val state: StateFlow<ThermalCameraState> = _state.asStateFlow()

    // Nunca se emite una lectura real en este MVP: sin protocolo confirmado no
    // hay temperatura. Se mantiene NONE deliberadamente.
    private val _reading = MutableStateFlow(ThermalReading.NONE)
    override val reading: StateFlow<ThermalReading> = _reading.asStateFlow()

    private var currentDevice: UsbDevice? = null

    init {
        // Escuchamos los eventos de permiso del gestor USB.
        scope.launch {
            usbManager.events.collect { event ->
                when (event) {
                    is UsbEvent.PermissionResult -> onPermissionResult(event.granted)
                    is UsbEvent.Error -> _state.value = _state.value.copy(
                        status = ThermalConnectionStatus.ERROR,
                        message = "Error USB",
                        errorDetail = event.message,
                    )
                    UsbEvent.Idle -> Unit
                }
            }
        }
    }

    override fun detect() {
        if (!usbManager.isUsbHostAvailable) {
            _state.value = ThermalCameraState(
                status = ThermalConnectionStatus.UNSUPPORTED,
                message = "USB Host no disponible en este dispositivo",
            )
            return
        }

        _state.value = _state.value.copy(
            status = ThermalConnectionStatus.DETECTING,
            message = "Buscando GW192A…",
            errorDetail = null,
        )

        val device = usbManager.findGw192a()
        currentDevice = device

        if (device == null) {
            _state.value = ThermalCameraState(
                status = ThermalConnectionStatus.NOT_CONNECTED,
                message = "Termográfica no conectada",
            )
            return
        }

        if (usbManager.hasPermission(device)) {
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.CONNECTED,
                message = "GW192A conectado",
            )
            // Con permiso ya concedido, podemos enumerar directamente.
            connectAndInspect()
        } else {
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.CONNECTED,
                message = "GW192A conectado",
            )
        }
    }

    override fun requestPermission() {
        val device = currentDevice ?: usbManager.findGw192a()?.also { currentDevice = it }
        if (device == null) {
            _state.value = ThermalCameraState(
                status = ThermalConnectionStatus.NOT_CONNECTED,
                message = "Termográfica no conectada",
            )
            return
        }
        if (usbManager.hasPermission(device)) {
            onPermissionResult(true)
            return
        }
        _state.value = _state.value.copy(
            status = ThermalConnectionStatus.PERMISSION_REQUIRED,
            message = "Permiso USB requerido",
        )
        usbManager.requestPermission(device)
    }

    private fun onPermissionResult(granted: Boolean) {
        if (!granted) {
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.PERMISSION_REQUIRED,
                message = "Permiso USB requerido",
            )
            return
        }
        connectAndInspect()
    }

    override fun connectAndInspect() {
        val device = currentDevice ?: usbManager.findGw192a()?.also { currentDevice = it }
        if (device == null) {
            _state.value = ThermalCameraState(
                status = ThermalConnectionStatus.NOT_CONNECTED,
                message = "Termográfica no conectada",
            )
            return
        }

        if (!usbManager.hasPermission(device)) {
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.PERMISSION_REQUIRED,
                message = "Permiso USB requerido",
            )
            return
        }

        val info = usbManager.openAndEnumerate(device)
        if (info == null) {
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.ERROR,
                message = "No se pudo leer el dispositivo",
            )
            return
        }

        Log.i(TAG, "GW192A enumerado. matchesGW192A=${info.matchesGw192a}. " +
            "Stream térmico NO iniciado (protocolo no confirmado).")

        // READY = conexión abierta + endpoints enumerados. NO es streaming.
        _state.value = ThermalCameraState(
            status = ThermalConnectionStatus.READY,
            deviceInfo = info,
            // Mensaje honesto: el dispositivo está listo para inspección, pero
            // todavía no hay stream térmico porque el protocolo no está confirmado.
            message = "GW192A conectado · Stream térmico no disponible",
        )
        // NO se avanza a STREAMING. REQUIERE PRUEBA EN POCO F7 + GW192A.
    }

    override fun release() {
        usbManager.release()
    }

    companion object {
        private const val TAG = "NeoThermal"
    }
}
