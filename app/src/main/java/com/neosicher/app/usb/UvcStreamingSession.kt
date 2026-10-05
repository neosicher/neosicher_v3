package com.neosicher.app.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Apertura de un stream UVC **mínimo**: negociación estándar Probe/Commit y
 * lectura de frames por bulk transfer, ensamblados según el **Payload Header**
 * estándar de UVC.
 *
 * Fuente del protocolo: especificación pública **UVC 1.1 (USB-IF)**,
 * secciones 2.4.3.3 (Payload Header) y 4.3.1.1 (Probe/Commit) — ver
 * [UvcControlRequests] y docs/GW192A_INVESTIGACION.md §10, §13. No se basa en
 * THG Start ni en código propietario.
 *
 * Esta clase NO decide qué significan los bytes del frame (eso es
 * responsabilidad de una capa de interpretación separada, marcada
 * EXPERIMENTAL). Su única responsabilidad es: reclamar la interfaz
 * VideoStreaming, negociar un formato/frame concretos, y entregar los bytes
 * crudos de cada frame completo tal como los transmite el dispositivo.
 *
 * Si la negociación UVC estándar falla (el dispositivo no responde como se
 * espera, un control transfer devuelve error, o no hay endpoint bulk/isóc
 * disponible), esta clase **falla explícitamente** vía [onError]. Nunca
 * produce un frame simulado.
 */
class UvcStreamingSession(
    private val connection: UsbDeviceConnection,
    private val device: UsbDevice,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var vsInterface: UsbInterface? = null

    /**
     * Intenta iniciar el stream para el formato/frame indicados (obtenidos de
     * un [UvcParseResult] previo, ver [UvcDescriptorParser]).
     *
     * @param expectedWidth / [expectedHeight] usados solo para que [onFrame]
     *   pueda interpretar el buffer; no se envían al dispositivo (eso ya lo
     *   determina formatIndex/frameIndex en el Probe/Commit).
     * @return true si la negociación tuvo éxito y el hilo de lectura arrancó.
     */
    fun start(
        formatIndex: Int,
        frameIndex: Int,
        frameIntervalUnits: Long,
        expectedWidth: Int,
        expectedHeight: Int,
        onNegotiated: () -> Unit,
        onFrame: (bytes: ByteArray, width: Int, height: Int) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        // Todo el proceso de negociación se envuelve en try/catch: cualquier
        // excepción inesperada (p. ej. IllegalStateException, SecurityException
        // si el permiso USB se revoca a mitad de camino) se convierte en un
        // error explícito en vez de propagarse silenciosamente y dejar la UI
        // "congelada" sin ninguna explicación.
        return try {
            startInternal(
                formatIndex, frameIndex, frameIntervalUnits,
                expectedWidth, expectedHeight, onNegotiated, onFrame, onError,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Excepción durante negociación UVC", t)
            onError("Excepción durante negociación UVC: ${t.javaClass.simpleName}: ${t.message}")
            runCatching { vsInterface?.let { connection.releaseInterface(it) } }
            false
        }
    }

    private fun startInternal(
        formatIndex: Int,
        frameIndex: Int,
        frameIntervalUnits: Long,
        expectedWidth: Int,
        expectedHeight: Int,
        onNegotiated: () -> Unit,
        onFrame: (bytes: ByteArray, width: Int, height: Int) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val vs = findVideoStreamingInterface(device)
        if (vs == null) {
            onError("No se encontró interfaz VideoStreaming (class=14 sub=2)")
            return false
        }
        if (!connection.claimInterface(vs, true)) {
            onError("No se pudo reclamar la interfaz VideoStreaming (¿en uso por otra app/proceso?)")
            return false
        }
        vsInterface = vs

        // Seleccionar explícitamente el alternate setting de la interfaz de
        // streaming. En UVC por BULK el streaming vive en alt setting 0, pero
        // algunos dispositivos requieren una llamada explícita a
        // setInterface para "activar" la interfaz antes de transmitir.
        // Si falla, no es fatal (se continúa), pero se registra.
        runCatching {
            val ok = connection.setInterface(vs)
            Log.i(TAG, "setInterface(VideoStreaming alt=${vs.alternateSetting}) ok=$ok")
        }.onFailure { Log.w(TAG, "setInterface lanzó excepción (no fatal): ${it.message}") }

        // NOTA: solo se soporta el endpoint tipo BULK, porque
        // UsbDeviceConnection.bulkTransfer() de Android únicamente opera
        // sobre endpoints bulk/interrupt. La transferencia isócrona requiere
        // la API UsbRequest con colas nativas y NO está implementada aquí.
        // La evidencia real registrada en la sección 12 del documento de
        // investigación confirma que el GW192A usa BULK (EP 0x81 IN BULK),
        // así que esta restricción es coherente con el hardware observado.
        val endpoint = findBulkInEndpoint(vs)
        if (endpoint == null) {
            connection.releaseInterface(vs)
            onError(
                "La interfaz VideoStreaming no tiene endpoint IN de tipo BULK " +
                    "(si es isócrono, este MVP no lo soporta todavía: requeriría UsbRequest)"
            )
            return false
        }

        val probeRequest = UvcControlRequests.buildProbeCommitRequest(formatIndex, frameIndex, frameIntervalUnits)

        // 1) SET_CUR sobre VS_PROBE_CONTROL: proponer formato/frame.
        val setProbeOk = controlTransferOut(
            vs.id,
            UvcControlRequests.SET_CUR,
            UvcControlRequests.VS_PROBE_CONTROL,
            probeRequest,
        )
        if (!setProbeOk) {
            connection.releaseInterface(vs)
            onError("SET_CUR(Probe) rechazado por el dispositivo (formatIdx=$formatIndex frameIdx=$frameIndex)")
            return false
        }

        // 2) GET_CUR sobre VS_PROBE_CONTROL: leer los valores que el
        //    dispositivo realmente aceptó/ajustó (tamaño máximo de frame, etc.).
        val probeResponse = ByteArray(UvcControlRequests.PROBE_COMMIT_LENGTH)
        val gotProbe = controlTransferIn(vs.id, UvcControlRequests.GET_CUR, UvcControlRequests.VS_PROBE_CONTROL, probeResponse)
        // Buffer que se reenviará en el Commit: la respuesta real del
        // dispositivo si GET_CUR funcionó, o nuestra propia propuesta en caso
        // contrario (ver UvcControlRequests.requestAsResult — tolerancia
        // necesaria para hardware que no implementa bien GET_CUR, como el
        // GW192A real; confirmado funcional con el driver UVC de Windows).
        val commitBuffer: ByteArray
        val negotiated = if (gotProbe) {
            UvcControlRequests.parseProbeCommitResponse(probeResponse)
        } else {
            null
        }
        if (negotiated != null) {
            commitBuffer = probeResponse
            Log.i(TAG, "Probe negociado (GET_CUR OK): formatIdx=${negotiated.formatIndex} " +
                "frameIdx=${negotiated.frameIndex} maxFrameSize=${negotiated.maxVideoFrameSize} " +
                "maxPayload=${negotiated.maxPayloadTransferSize}")
        } else {
            Log.w(TAG, "GET_CUR(Probe) no devolvió respuesta válida (gotProbe=$gotProbe); " +
                "usando la propuesta propia como fallback tolerante (ver docs §13.6)")
            commitBuffer = probeRequest
        }
        val effective = negotiated ?: UvcControlRequests.requestAsResult(probeRequest)
        if (effective == null) {
            connection.releaseInterface(vs)
            onError("No se pudo determinar un struct Probe/Commit válido para negociar")
            return false
        }

        // 3) SET_CUR sobre VS_COMMIT_CONTROL: confirmar el formato negociado.
        val commitOk = controlTransferOut(
            vs.id,
            UvcControlRequests.SET_CUR,
            UvcControlRequests.VS_COMMIT_CONTROL,
            commitBuffer,
        )
        if (!commitOk) {
            connection.releaseInterface(vs)
            onError("SET_CUR(Commit) rechazado por el dispositivo")
            return false
        }

        val maxFrameSize = if (effective.maxVideoFrameSize > 0) {
            effective.maxVideoFrameSize.toInt()
        } else {
            expectedWidth * expectedHeight * 2 // fallback: estimación YUY2 (2 bytes/px)
        }

        // Negociación completada con éxito: se notifica de forma SÍNCRONA,
        // antes de lanzar el hilo de lectura. Esto evita una condición de
        // carrera donde el hilo de lectura podría reportar un error (p. ej.
        // timeout) y el llamador sobrescribirlo igualmente con "éxito" al
        // procesar el valor de retorno de start() después.
        onNegotiated()

        running.set(true)
        thread = Thread {
            try {
                readLoop(endpoint, maxFrameSize, expectedWidth, expectedHeight, onFrame, onError)
            } catch (t: Throwable) {
                Log.e(TAG, "Excepción no capturada en el hilo de lectura UVC", t)
                onError("Excepción en lectura de stream: ${t.javaClass.simpleName}: ${t.message}")
            }
        }.apply {
            name = "NeoUvcStreamThread"
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running.set(false)
        thread?.let { runCatching { it.join(500) } }
        thread = null
        vsInterface?.let { runCatching { connection.releaseInterface(it) } }
        vsInterface = null
    }

    // -- Lectura de frames ----------------------------------------------

    private fun readLoop(
        endpoint: UsbEndpoint,
        maxFrameSize: Int,
        width: Int,
        height: Int,
        onFrame: (ByteArray, Int, Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        // Buffer de lectura GRANDE: en UVC por bulk, cada bulkTransfer debe
        // poder recibir un bloque grande (no solo maxPacketSize=512). Leer de
        // a 512 bytes hace que el dispositivo abandone la transferencia. Se usa
        // un buffer del tamaño de un frame completo + margen para el header.
        val readBufSize = (maxFrameSize + 4096).coerceAtLeast(65536)
        val readBuf = ByteArray(readBufSize)
        val frameBuf = ByteArray((maxFrameSize * 2).coerceAtLeast(65536))
        var frameOffset = 0
        var currentFid = -1
        var consecutiveTimeouts = 0
        var totalBytesSeen = 0L
        var transfersWithData = 0

        Log.i(TAG, "readLoop iniciado: maxPacket=${endpoint.maxPacketSize} " +
            "readBuf=$readBufSize maxFrameSize=$maxFrameSize ${width}x$height")

        while (running.get()) {
            val n = try {
                connection.bulkTransfer(endpoint, readBuf, readBuf.size, TRANSFER_TIMEOUT_MS)
            } catch (t: Throwable) {
                onError("Error de lectura USB: ${t.message}")
                return
            }

            if (n <= 0) {
                consecutiveTimeouts++
                if (consecutiveTimeouts > MAX_CONSECUTIVE_TIMEOUTS) {
                    onError("Sin datos del endpoint de streaming tras $MAX_CONSECUTIVE_TIMEOUTS intentos " +
                        "(bytesVistos=$totalBytesSeen transfersConDatos=$transfersWithData)")
                    return
                }
                continue
            }
            consecutiveTimeouts = 0
            totalBytesSeen += n
            transfersWithData++
            if (transfersWithData <= 5) {
                Log.i(TAG, "bulk transfer #$transfersWithData: $n bytes " +
                    "(primeros bytes: ${firstBytesHex(readBuf, n)})")
            }

            // Payload Header UVC (spec §2.4.3.3): byte0=HLE (header length),
            // byte1=bitfield (BFH). Algunos dispositivos mandan datos sin
            // header UVC (bulk "crudo"); se maneja ese caso también.
            if (n < 2) continue
            val hle = readBuf[0].toIntUnsigned()
            val looksLikeUvcHeader = hle in 2..12 && hle <= n

            if (looksLikeUvcHeader) {
                val bfh = readBuf[1].toIntUnsigned()
                val fid = bfh and 0x01
                val eof = (bfh and 0x02) != 0
                val error = (bfh and 0x40) != 0

                if (error) {
                    frameOffset = 0
                    currentFid = fid
                    continue
                }
                if (currentFid == -1) currentFid = fid
                if (fid != currentFid) {
                    // Nuevo frame: emitir el anterior si tenía contenido.
                    if (frameOffset > 0) {
                        onFrame(frameBuf.copyOf(frameOffset), width, height)
                    }
                    frameOffset = 0
                    currentFid = fid
                }

                val payloadLen = n - hle
                if (payloadLen > 0 && frameOffset + payloadLen <= frameBuf.size) {
                    System.arraycopy(readBuf, hle, frameBuf, frameOffset, payloadLen)
                    frameOffset += payloadLen
                }

                // Emitir frame cuando: hay EOF, o se completó el tamaño esperado.
                if (eof || frameOffset >= maxFrameSize) {
                    if (frameOffset > 0) {
                        onFrame(frameBuf.copyOf(frameOffset), width, height)
                    }
                    frameOffset = 0
                    currentFid = -1
                }
            } else {
                // Sin header UVC reconocible: tratar el bloque como datos crudos
                // y acumular hasta completar un frame del tamaño esperado.
                if (frameOffset + n <= frameBuf.size) {
                    System.arraycopy(readBuf, 0, frameBuf, frameOffset, n)
                    frameOffset += n
                }
                if (frameOffset >= maxFrameSize) {
                    onFrame(frameBuf.copyOf(maxFrameSize), width, height)
                    frameOffset = 0
                }
            }
        }
    }

    private fun firstBytesHex(buf: ByteArray, n: Int): String {
        val count = minOf(n, 8)
        return (0 until count).joinToString(" ") { "%02X".format(buf[it]) }
    }

    // -- Control transfers ------------------------------------------------

    private fun controlTransferOut(interfaceNumber: Int, request: Int, controlSelectorHigh: Int, data: ByteArray): Boolean {
        val wValue = controlSelectorHigh shl 8
        val result = connection.controlTransfer(
            UvcControlRequests.REQUEST_TYPE_INTERFACE_SET,
            request,
            wValue,
            interfaceNumber,
            data,
            data.size,
            TRANSFER_TIMEOUT_MS,
        )
        return result == data.size
    }

    private fun controlTransferIn(interfaceNumber: Int, request: Int, controlSelectorHigh: Int, out: ByteArray): Boolean {
        val wValue = controlSelectorHigh shl 8
        val result = connection.controlTransfer(
            UvcControlRequests.REQUEST_TYPE_INTERFACE_GET,
            request,
            wValue,
            interfaceNumber,
            out,
            out.size,
            TRANSFER_TIMEOUT_MS,
        )
        return result == out.size
    }

    // -- Búsqueda de interfaz/endpoint -------------------------------------

    private fun findVideoStreamingInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UVC_CLASS_VIDEO && iface.interfaceSubclass == UVC_SUBCLASS_STREAMING) {
                return iface
            }
        }
        return null
    }

    private fun findBulkInEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            val isIn = ep.direction == UsbConstants.USB_DIR_IN
            val isBulk = ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            if (isIn && isBulk) return ep
        }
        return null
    }

    private fun Byte.toIntUnsigned(): Int = this.toInt() and 0xFF

    companion object {
        private const val TAG = "NeoUvcStream"
        private const val TRANSFER_TIMEOUT_MS = 1000
        private const val MAX_CONSECUTIVE_TIMEOUTS = 8
        private const val UVC_CLASS_VIDEO = 14
        private const val UVC_SUBCLASS_STREAMING = 2
    }
}
