package com.neosicher.app.thermal

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.neosicher.app.monitoring.ThermalReading
import com.neosicher.app.usb.UsbDeviceManager
import com.neosicher.app.usb.UsbEvent
import com.neosicher.app.usb.UvcStreamingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implementación de [ThermalCameraDataSource] para el GOYOJO GW192A por USB OTG.
 *
 * Flujo (ver docs/GW192A_INVESTIGACION.md §10 — "NO INVENTAR EL PROTOCOLO", y
 * §13 — evidencia de descriptors UVC reales):
 *
 *   NOT_CONNECTED → DETECTING → CONNECTED → PERMISSION_REQUIRED → READY → STREAMING
 *
 * En READY la conexión se abrió y se enumeraron interfaces y endpoints. Desde
 * ahí, [attemptStartStreaming] intenta avanzar a STREAMING **solo** si:
 *   1. Los descriptors UVC crudos del propio dispositivo (leídos con
 *      [UsbDeviceManager.readUvcDescriptors], protocolo público UVC 1.1) confirman
 *      un formato/frame válido según la hipótesis de doble altura documentada.
 *   2. La negociación estándar UVC Probe/Commit ([UvcStreamingSession]) es
 *      aceptada por el hardware real.
 * Si cualquiera de las dos condiciones falla, el estado permanece en READY con
 * un `errorDetail` honesto — nunca se simula streaming ni se envían comandos
 * inventados. Toda interpretación de frame (ver [ThermalFrameInterpreter]) se
 * marca EXPERIMENTAL y nunca calcula temperatura en grados.
 *
 * Por diseño, [reading] permanece siempre en [ThermalReading.NONE]: no se
 * generan valores térmicos calibrados/falsos, incluso cuando hay streaming
 * visual experimental (ver [ThermalCameraState.lastFrame]).
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
    private var streamingConnection: UsbDeviceConnection? = null
    private var streamingSession: UvcStreamingSession? = null

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

        Log.i(TAG, "GW192A enumerado. matchesGW192A=${info.matchesGw192a}.")

        // READY = conexión abierta + endpoints enumerados. NO implica streaming.
        _state.value = ThermalCameraState(
            status = ThermalConnectionStatus.READY,
            deviceInfo = info,
            message = "GW192A conectado · Analizando formato de vídeo…",
        )

        // A partir de aquí se intenta, con evidencia real (no simulada),
        // avanzar hacia STREAMING. Si cualquier paso falla, el estado
        // permanece honesto (READY sin stream, o ERROR con detalle).
        attemptStartStreaming(device)
    }

    /**
     * Intenta iniciar el streaming real solo si los descriptors UVC del
     * propio dispositivo declaran un frame consistente con la hipótesis de
     * doble altura (ver docs/GW192A_INVESTIGACION.md §13). Si no, se detiene
     * en READY con un mensaje honesto — nunca se simula un stream.
     */
    private fun attemptStartStreaming(device: UsbDevice) {
        val uvcResult = usbManager.readUvcDescriptors(device)
        if (uvcResult == null) {
            _state.value = _state.value.copy(
                message = "GW192A conectado · Stream térmico no disponible",
                errorDetail = "No se pudieron leer los descriptors UVC crudos",
            )
            return
        }

        val candidatePair = uvcResult.doubleHeightCandidatesWithFormat.firstOrNull()
        if (!uvcResult.videoControlInterfaceFound || candidatePair == null) {
            Log.i(TAG, "Descriptors UVC no confirman formato de doble altura. " +
                "videoControlFound=${uvcResult.videoControlInterfaceFound} " +
                "formatos=${uvcResult.streamingFormats.size}")
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.READY,
                message = "GW192A conectado · Stream térmico no disponible",
                errorDetail = "Los descriptors UVC no declaran un formato reconocible " +
                    "(no se confirma la hipótesis de doble altura para este dispositivo)",
            )
            return
        }

        val connection = usbManager.openPersistentConnection(device)
        if (connection == null) {
            _state.value = _state.value.copy(
                message = "GW192A conectado · Stream térmico no disponible",
                errorDetail = "No se pudo abrir una conexión persistente para streaming",
            )
            return
        }
        streamingConnection = connection

        val (format, frame) = candidatePair
        val session = UvcStreamingSession(connection, device)
        streamingSession = session

        val started = session.start(
            formatIndex = format.formatIndex,
            frameIndex = frame.frameIndex,
            frameIntervalUnits = frame.defaultFrameIntervalUnits,
            expectedWidth = frame.widthPx,
            expectedHeight = frame.heightPx,
            onFrame = { bytes, width, height -> onRawFrame(bytes, width, height) },
            onError = { message -> onStreamingError(message) },
        )

        if (!started) {
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.READY,
                message = "GW192A conectado · Stream térmico no disponible",
            )
            cleanupStreaming()
        } else {
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.STREAMING,
                message = "GW192A · Stream experimental activo (sin calibrar)",
                errorDetail = null,
            )
        }
    }

    private fun onRawFrame(bytes: ByteArray, width: Int, height: Int) {
        val result = ThermalFrameInterpreter.interpretDoubleHeightFrame(bytes, width, height)
        if (result != null) {
            _state.value = _state.value.copy(lastFrame = result)
        }
        // Se mantiene _reading.value = ThermalReading.NONE deliberadamente:
        // no hay temperatura calibrada en °C que reportar como VitalSigns/
        // ThermalReading. La visualización EXPERIMENTAL vive solo en
        // ThermalCameraState.lastFrame (ver KDoc de ThermalFrameInterpreter).
    }

    private fun onStreamingError(message: String) {
        Log.w(TAG, "Streaming UVC detenido: $message")
        _state.value = _state.value.copy(
            status = ThermalConnectionStatus.READY,
            message = "GW192A conectado · Stream térmico no disponible",
            errorDetail = message,
            lastFrame = null,
        )
        cleanupStreaming()
    }

    private fun cleanupStreaming() {
        streamingSession?.stop()
        streamingSession = null
        streamingConnection?.let { runCatching { it.close() } }
        streamingConnection = null
    }

    override fun release() {
        cleanupStreaming()
        usbManager.release()
    }

    companion object {
        private const val TAG = "NeoThermal"
    }
}
