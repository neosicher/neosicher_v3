package com.neosicher.app.usb

/**
 * Construcción/parseo del struct **VideoProbeCommitControl** de UVC y de los
 * bRequest/wValue estándar usados para negociar un formato de streaming.
 *
 * Fuente: especificación pública **"USB Device Class Definition for Video
 * Devices" (UVC 1.1, USB-IF)**, sección 4.3.1.1 (Video Probe and Commit
 * Control) y Tabla A.8 (VideoStreaming Control Selectors). Estos son
 * bRequest/wValue/estructuras **estándar del protocolo UVC**, iguales para
 * cualquier dispositivo UVC — no son específicos ni derivados del GW192A ni
 * de THG Start (ver docs/GW192A_INVESTIGACION.md §10, §13).
 *
 * Enviar estos control transfers a un dispositivo que declare descriptors
 * UVC (VC_HEADER + VS_FORMAT/VS_FRAME, ver [UvcDescriptorParser]) es la forma
 * estándar de negociar un formato antes de iniciar la transferencia de datos.
 * Si el GW192A no responde como UVC estándar, la negociación debe fallar de
 * forma explícita y honesta (ver [UvcStreamingSession]), nunca simularse.
 */
object UvcControlRequests {

    // bRequest (UVC spec Tabla A.8 / USB spec estándar para class-specific requests)
    const val SET_CUR = 0x01
    const val GET_CUR = 0x81
    const val GET_MIN = 0x82
    const val GET_MAX = 0x83

    // Control Selector para VideoStreaming (UVC spec Tabla A.8)
    const val VS_PROBE_CONTROL = 0x01
    const val VS_COMMIT_CONTROL = 0x02

    // bmRequestType estándar para class-specific request dirigido a una interfaz.
    // USB_TYPE_CLASS=0x20, USB_RECIP_INTERFACE=0x01.
    const val REQUEST_TYPE_INTERFACE_SET = 0x21 // host-to-device, class, interface
    const val REQUEST_TYPE_INTERFACE_GET = 0xA1 // device-to-host, class, interface

    /** Tamaño del struct VideoProbeCommitControl versión 1.0/1.1 (26 bytes). */
    const val PROBE_COMMIT_LENGTH = 26

    /**
     * Construye el buffer de 26 bytes de VideoProbeCommitControl para solicitar
     * el formato/frame indicados. El resto de campos se dejan en 0 para que el
     * dispositivo los complete con sus valores por defecto (comportamiento
     * estándar UVC al negociar).
     */
    fun buildProbeCommitRequest(formatIndex: Int, frameIndex: Int, frameIntervalUnits: Long): ByteArray {
        val buf = ByteArray(PROBE_COMMIT_LENGTH)
        // bmHint (2 bytes): bit 0 = dwFrameInterval fijo por el host.
        buf[0] = 0x01
        buf[1] = 0x00
        buf[2] = formatIndex.toByte()
        buf[3] = frameIndex.toByte()
        writeU32(buf, 4, frameIntervalUnits)
        // wKeyFrameRate, wPFrameRate, wCompQuality, wCompWindowSize, wDelay = 0
        // dwMaxVideoFrameSize, dwMaxPayloadTransferSize se dejan en 0: el
        // dispositivo debe devolverlos en el GET_CUR posterior al Probe.
        return buf
    }

    /** Resultado parseado del buffer devuelto por el dispositivo tras GET_CUR(Probe). */
    data class ProbeCommitResult(
        val formatIndex: Int,
        val frameIndex: Int,
        val frameIntervalUnits: Long,
        val maxVideoFrameSize: Long,
        val maxPayloadTransferSize: Long,
    )

    fun parseProbeCommitResponse(buf: ByteArray): ProbeCommitResult? {
        if (buf.size < PROBE_COMMIT_LENGTH) return null
        return ProbeCommitResult(
            formatIndex = buf[2].toIntUnsigned(),
            frameIndex = buf[3].toIntUnsigned(),
            frameIntervalUnits = readU32(buf, 4),
            maxVideoFrameSize = readU32(buf, 18),
            maxPayloadTransferSize = readU32(buf, 22),
        )
    }

    /**
     * Reconstruye un [ProbeCommitResult] a partir de la propuesta ORIGINAL del
     * host (el buffer construido por [buildProbeCommitRequest]), sin depender
     * de una respuesta GET_CUR del dispositivo.
     *
     * Justificación: algunas cámaras UVC de bajo costo (observado con el
     * GW192A real — ver docs/GW192A_INVESTIGACION.md §13.6) aceptan el
     * SET_CUR(Probe) pero no implementan correctamente el GET_CUR(Probe)
     * posterior, pese a aceptar y transmitir el stream con normalidad (así lo
     * hace, de hecho, el driver UVC estándar de Windows con este mismo
     * dispositivo). Usar la propuesta propia como "negociada" es un
     * comportamiento tolerante estándar en implementaciones UVC de terceros,
     * no una invención de protocolo: seguimos pidiendo exactamente el
     * formatIndex/frameIndex que el propio descriptor del dispositivo declaró.
     */
    fun requestAsResult(buf: ByteArray): ProbeCommitResult? {
        if (buf.size < PROBE_COMMIT_LENGTH) return null
        return ProbeCommitResult(
            formatIndex = buf[2].toIntUnsigned(),
            frameIndex = buf[3].toIntUnsigned(),
            frameIntervalUnits = readU32(buf, 4),
            maxVideoFrameSize = 0L, // desconocido: el llamador aplica un fallback por tamaño esperado
            maxPayloadTransferSize = 0L,
        )
    }

    private fun writeU32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readU32(buf: ByteArray, offset: Int): Long =
        (buf[offset].toIntUnsigned().toLong()) or
            (buf[offset + 1].toIntUnsigned().toLong() shl 8) or
            (buf[offset + 2].toIntUnsigned().toLong() shl 16) or
            (buf[offset + 3].toIntUnsigned().toLong() shl 24)

    private fun Byte.toIntUnsigned(): Int = this.toInt() and 0xFF
}
