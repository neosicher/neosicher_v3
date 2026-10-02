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
 * §13 — evidencia de descriptors UVC reales + evidencia pública de terceros):
 *
 *   NOT_CONNECTED → DETECTING → CONNECTED → PERMISSION_REQUIRED → READY → STREAMING
 *
 * En READY la conexión se abrió y se enumeraron interfaces y endpoints. Desde
 * ahí, [attemptStartStreaming] intenta avanzar a STREAMING **solo** si:
 *   1. Los descriptors UVC crudos del propio dispositivo (leídos con
 *      [UsbDeviceManager.readUvcDescriptors], protocolo público UVC 1.1)
 *      declaran al menos un formato/frame válido. Se elige con
 *      [UvcParseResult.recommendedFrame], que prioriza NV12/YUYV simple según
 *      evidencia pública real de otra persona con el MISMO hardware (ver
 *      §13.5): el sensor real es 96×96, no 192×192 como anuncia el marketing.
 *   2. La negociación estándar UVC Probe/Commit ([UvcStreamingSession]) es
 *      aceptada por el hardware real.
 * Si cualquiera de las dos condiciones falla, el estado permanece en READY con
 * un `errorDetail` honesto — nunca se simula streaming ni se envían comandos
 * inventados. Toda interpretación de frame (ver [ThermalFrameInterpreter]) se
 * marca EXPERIMENTAL y nunca calcula temperatura en grados: la misma evidencia
 * pública confirma que ningún formato del GW192A transporta datos de 16 bits
 * ni temperatura radiométrica.
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
    private var currentFormat: com.neosicher.app.usb.UvcFormatDescriptor? = null
    private var framesReceivedSinceStart = 0
    private var watchdogJob: kotlinx.coroutines.Job? = null

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
     * Intenta iniciar el streaming UVC real del dispositivo.
     *
     * Estrategia basada en evidencia pública real con el MISMO hardware
     * (ver docs/GW192A_INVESTIGACION.md §13.5): se usa
     * [UvcParseResult.recommendedFrame], que prioriza el formato NV12 más
     * simple (96×96, imagen limpia en escala de grises), luego YUYV sin
     * composición, y solo como último recurso un candidato de doble altura.
     * El GW192A no transmite temperatura radiométrica en ningún formato
     * conocido: siempre se produce una paleta de calor relativa, no grados.
     *
     * Si no hay ningún frame declarado, se detiene en READY de forma honesta.
     * En todos los casos se guarda [uvcInfo] en el estado para diagnóstico.
     */
    private fun attemptStartStreaming(device: UsbDevice) {
        try {
            attemptStartStreamingInternal(device)
        } catch (t: Throwable) {
            Log.e(TAG, "Excepción inesperada iniciando streaming", t)
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.READY,
                message = "GW192A conectado · Stream térmico no disponible",
                errorDetail = "Excepción inesperada: ${t.javaClass.simpleName}: ${t.message}",
            )
            cleanupStreaming()
        }
    }

    private fun attemptStartStreamingInternal(device: UsbDevice) {
        val uvcResult = usbManager.readUvcDescriptors(device)
        if (uvcResult == null) {
            _state.value = _state.value.copy(
                message = "GW192A conectado · Stream térmico no disponible",
                errorDetail = "No se pudieron leer los descriptors UVC crudos",
            )
            return
        }

        // Guardar la info UVC siempre, para que el diagnóstico muestre los
        // formatos/resoluciones reales que declara este dispositivo.
        _state.value = _state.value.copy(uvcInfo = uvcResult)

        val chosen = uvcResult.recommendedFrame
        if (chosen == null) {
            Log.i(TAG, "Descriptors UVC sin frames declarados. " +
                "videoControlFound=${uvcResult.videoControlInterfaceFound} " +
                "formatos=${uvcResult.streamingFormats.size}")
            _state.value = _state.value.copy(
                status = ThermalConnectionStatus.READY,
                message = "GW192A conectado · Stream térmico no disponible",
                errorDetail = "Los descriptors UVC no declaran ningún formato de vídeo " +
                    "reconocible en este dispositivo",
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

        val (format, frame) = chosen
        currentFormat = format
        framesReceivedSinceStart = 0
        Log.i(TAG, "Intentando streaming UVC: formatIdx=${format.formatIndex} " +
            "frameIdx=${frame.frameIndex} ${frame.widthPx}x${frame.heightPx} " +
            "fourCc=${format.fourCc} kind=${format.kind}")
        val session = UvcStreamingSession(connection, device)
        streamingSession = session

        val started = session.start(
            formatIndex = format.formatIndex,
            frameIndex = frame.frameIndex,
            frameIntervalUnits = frame.defaultFrameIntervalUnits,
            expectedWidth = frame.widthPx,
            expectedHeight = frame.heightPx,
            onNegotiated = {
                // Se llama de forma SÍNCRONA en el hilo que invocó start(),
                // justo tras aceptar el Probe/Commit. Evita la carrera entre
                // "negociación OK" y un posible error reportado por el hilo
                // de lectura antes de que actualicemos el estado.
                _state.value = _state.value.copy(
                    status = ThermalConnectionStatus.STREAMING,
                    message = "GW192A · Negociado, esperando frames…",
                    errorDetail = null,
                )
                startNoFrameWatchdog(frame.widthPx, frame.heightPx)
            },
            onFrame = { bytes, width, height -> onRawFrame(bytes, width, height) },
            onError = { message -> onStreamingError(message) },
        )

        if (!started) {
            // Si start() devuelve false, onError ya fue invocado dentro con
            // el detalle correspondiente (o la excepción se capturó arriba).
            cleanupStreaming()
        }
    }

    /**
     * Si tras negociar el stream no llega NINGÚN frame interpretable en un
     * tiempo razonable, se reporta como error honesto en vez de dejar la UI
     * mostrando el mensaje genérico de "sin confirmar protocolo" sin ninguna
     * pista. Esto puede pasar, por ejemplo, si el endpoint bulk no entrega
     * datos pese a que la negociación de control fue aceptada.
     */
    private fun startNoFrameWatchdog(width: Int, height: Int) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            kotlinx.coroutines.delay(6000)
            if (_state.value.status == ThermalConnectionStatus.STREAMING && framesReceivedSinceStart == 0) {
                Log.w(TAG, "Watchdog: 6s sin ningún frame interpretable tras negociar (${width}x$height)")
                onStreamingError(
                    "El dispositivo aceptó la negociación UVC pero no llegó ningún frame " +
                        "interpretable en 6s (formato ${width}x$height). Puede que el endpoint " +
                        "bulk no esté entregando datos, o que el formato elegido no sea el correcto."
                )
            }
        }
    }

    private fun onRawFrame(bytes: ByteArray, width: Int, height: Int) {
        val format = currentFormat
        Log.d(TAG, "Frame crudo recibido: ${bytes.size} bytes, declarado ${width}x$height, " +
            "formato=${format?.fourCc}")
        val result = when {
            // Caso excepcional: el formato elegido fue el candidato de doble
            // altura (solo ocurre si no había ninguna otra opción disponible).
            format != null && height == width * 2 ->
                ThermalFrameInterpreter.interpretDoubleHeightFrame(bytes, width, height)
            format != null ->
                ThermalFrameInterpreter.interpretFrame(bytes, width, height, format)
            else -> null
        }
        if (result != null) {
            framesReceivedSinceStart++
            watchdogJob?.cancel()
            _state.value = _state.value.copy(
                lastFrame = result,
                message = "GW192A · Stream experimental activo (sin calibrar)",
            )
        } else {
            Log.w(TAG, "Frame recibido (${bytes.size}B) pero no se pudo interpretar " +
                "(formato=${format?.fourCc}, ${width}x$height) — tamaño insuficiente o FourCC no soportado")
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
        watchdogJob?.cancel()
        watchdogJob = null
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
