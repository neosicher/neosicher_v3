package com.neosicher.app.usb

/**
 * Parser de descriptors de configuración USB **crudos** en busca de
 * descriptors class-specific de UVC (USB Video Class).
 *
 * Fuente de la estructura de bytes: especificación pública
 * **"USB Device Class Definition for Video Devices"** (USB Implementers
 * Forum, UVC 1.1/1.5) — un estándar publicado y de acceso público. Este
 * parser NO se basa en THG Start ni en ningún código propietario
 * (ver docs/GW192A_INVESTIGACION.md §5, §10, §13).
 *
 * Solo interpreta bytes que YA declaró el propio dispositivo en sus
 * descriptors estándar de configuración (los mismos que Android expone de
 * forma parcial vía UsbInterface/UsbEndpoint). No envía nada al dispositivo,
 * no abre streams, no asume que el GW192A "es" UVC: solo reporta lo que
 * encuentra, byte a byte, con logging para poder auditar cada interpretación.
 */
object UvcDescriptorParser {

    // Tipos de descriptor estándar USB (USB 2.0 spec, tabla pública).
    private const val DT_INTERFACE = 0x04
    private const val DT_CS_INTERFACE = 0x24 // class-specific interface (UVC)

    // bInterfaceSubClass de UVC (tabla pública UVC A.2).
    private const val SC_VIDEOCONTROL = 0x01
    private const val SC_VIDEOSTREAMING = 0x02

    /**
     * Parsea el bloque de descriptors crudos de configuración.
     *
     * @param raw bytes devueltos por `UsbDeviceConnection.getRawDescriptors()`.
     */
    fun parse(raw: ByteArray): UvcParseResult {
        val log = mutableListOf<String>()
        val formats = mutableListOf<UvcFormatDescriptor>()
        var vcFound = false

        var offset = 0
        var currentInterfaceSubclass = -1
        // Formato en construcción mientras acumulamos sus frames asociados.
        var pendingFormat: PendingFormat? = null

        fun flushPending() {
            pendingFormat?.let { formats.add(it.toDescriptor()) }
            pendingFormat = null
        }

        while (offset + 2 <= raw.size) {
            val bLength = raw[offset].toIntUnsigned()
            if (bLength < 2 || offset + bLength > raw.size) {
                log.add("offset=$offset: bLength=$bLength inválido, deteniendo parseo")
                break
            }
            val bDescriptorType = raw[offset + 1].toIntUnsigned()

            when (bDescriptorType) {
                DT_INTERFACE -> {
                    flushPending()
                    if (bLength >= 9) {
                        currentInterfaceSubclass = raw[offset + 6].toIntUnsigned()
                        val ifaceNum = raw[offset + 2].toIntUnsigned()
                        log.add("Interface #$ifaceNum: subclass=$currentInterfaceSubclass " +
                            "(${subclassName(currentInterfaceSubclass)})")
                    }
                }

                DT_CS_INTERFACE -> {
                    val subtype = raw[offset + 2].toIntUnsigned()
                    when (currentInterfaceSubclass) {
                        SC_VIDEOCONTROL -> {
                            if (subtype == UvcVcSubtype.HEADER) {
                                vcFound = true
                                log.add("VC_HEADER encontrado (VideoControl confirmado por CS descriptor)")
                            }
                        }
                        SC_VIDEOSTREAMING -> {
                            parseVsDescriptor(raw, offset, bLength, subtype, log)?.let { event ->
                                when (event) {
                                    is VsEvent.NewFormat -> {
                                        flushPending()
                                        pendingFormat = PendingFormat(event.format)
                                    }
                                    is VsEvent.NewFrame -> {
                                        pendingFormat?.frames?.add(event.frame)
                                            ?: log.add("Frame descriptor sin formato previo, se ignora")
                                    }
                                }
                            }
                        }
                        else -> {
                            // CS_INTERFACE fuera de VC/VS (p. ej. otra función compuesta): se ignora.
                        }
                    }
                }

                else -> {
                    // Otros tipos estándar (Configuration, Endpoint, IAD, etc.):
                    // no aportan nada al análisis de formato de video.
                }
            }

            offset += bLength
        }
        flushPending()

        return UvcParseResult(
            videoControlInterfaceFound = vcFound,
            streamingFormats = formats,
            rawDescriptorTotalBytes = raw.size,
            parseLog = log,
        )
    }

    private fun subclassName(subclass: Int): String = when (subclass) {
        SC_VIDEOCONTROL -> "VideoControl"
        SC_VIDEOSTREAMING -> "VideoStreaming"
        else -> "otro/no-UVC"
    }

    private sealed interface VsEvent {
        data class NewFormat(val format: UvcFormatDescriptor) : VsEvent
        data class NewFrame(val frame: UvcFrameDescriptor) : VsEvent
    }

    private class PendingFormat(val base: UvcFormatDescriptor) {
        val frames = mutableListOf<UvcFrameDescriptor>()
        fun toDescriptor() = base.copy(frames = frames.toList())
    }

    private fun parseVsDescriptor(
        raw: ByteArray,
        offset: Int,
        length: Int,
        subtype: Int,
        log: MutableList<String>,
    ): VsEvent? {
        return when (subtype) {
            UvcVsSubtype.FORMAT_UNCOMPRESSED -> {
                if (length < 27) return null
                val formatIndex = raw[offset + 3].toIntUnsigned()
                val guid = raw.copyOfRange(offset + 5, offset + 21).toHexString()
                val bpp = raw[offset + 21].toIntUnsigned()
                log.add("VS_FORMAT_UNCOMPRESSED idx=$formatIndex guid=$guid bpp=$bpp")
                VsEvent.NewFormat(
                    UvcFormatDescriptor(UvcFormatKind.UNCOMPRESSED, formatIndex, guid, bpp)
                )
            }
            UvcVsSubtype.FORMAT_MJPEG -> {
                if (length < 11) return null
                val formatIndex = raw[offset + 3].toIntUnsigned()
                log.add("VS_FORMAT_MJPEG idx=$formatIndex")
                VsEvent.NewFormat(UvcFormatDescriptor(UvcFormatKind.MJPEG, formatIndex))
            }
            UvcVsSubtype.FORMAT_FRAME_BASED -> {
                if (length < 28) return null
                val formatIndex = raw[offset + 3].toIntUnsigned()
                val guid = raw.copyOfRange(offset + 5, offset + 21).toHexString()
                val bpp = raw[offset + 21].toIntUnsigned()
                log.add("VS_FORMAT_FRAME_BASED idx=$formatIndex guid=$guid bpp=$bpp")
                VsEvent.NewFormat(
                    UvcFormatDescriptor(UvcFormatKind.FRAME_BASED, formatIndex, guid, bpp)
                )
            }
            UvcVsSubtype.FRAME_UNCOMPRESSED, UvcVsSubtype.FRAME_MJPEG -> {
                if (length < 25) return null
                val frameIndex = raw[offset + 3].toIntUnsigned()
                val width = readU16(raw, offset + 5)
                val height = readU16(raw, offset + 7)
                val interval = readU32(raw, offset + 21)
                log.add("VS_FRAME idx=$frameIndex ${width}x$height defaultInterval=$interval " +
                    "(~${"%.1f".format(if (interval > 0) 10_000_000.0 / interval else 0.0)} fps)")
                VsEvent.NewFrame(UvcFrameDescriptor(frameIndex, width, height, interval))
            }
            UvcVsSubtype.FRAME_FRAME_BASED -> {
                if (length < 21) return null
                val frameIndex = raw[offset + 3].toIntUnsigned()
                val width = readU16(raw, offset + 5)
                val height = readU16(raw, offset + 7)
                val interval = if (length >= 21) readU32(raw, offset + 17) else 0L
                log.add("VS_FRAME_FRAME_BASED idx=$frameIndex ${width}x$height defaultInterval=$interval")
                VsEvent.NewFrame(UvcFrameDescriptor(frameIndex, width, height, interval))
            }
            else -> {
                log.add("VS descriptor subtype=0x%02X ignorado (no relevante para formato/frame)".format(subtype))
                null
            }
        }
    }

    private fun readU16(raw: ByteArray, offset: Int): Int =
        (raw[offset].toIntUnsigned()) or (raw[offset + 1].toIntUnsigned() shl 8)

    private fun readU32(raw: ByteArray, offset: Int): Long =
        (raw[offset].toIntUnsigned().toLong()) or
            (raw[offset + 1].toIntUnsigned().toLong() shl 8) or
            (raw[offset + 2].toIntUnsigned().toLong() shl 16) or
            (raw[offset + 3].toIntUnsigned().toLong() shl 24)

    private fun Byte.toIntUnsigned(): Int = this.toInt() and 0xFF

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02X".format(it) }
}
