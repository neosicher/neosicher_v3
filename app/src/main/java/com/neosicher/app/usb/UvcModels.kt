package com.neosicher.app.usb

/**
 * Modelos para descriptors UVC (USB Video Class) parseados de los
 * descriptors de configuración USB **crudos**.
 *
 * Fuente de la estructura: especificación pública **USB Device Class
 * Definition for Video Devices** (USB-IF, UVC 1.1/1.5), un estándar abierto
 * y públicamente documentado. NO proviene de THG Start ni de ningún código
 * propietario — ver docs/GW192A_INVESTIGACION.md §10 y §13.
 *
 * Esto es la siguiente etapa de la cadena de evidencia
 * (VID/PID → descriptors → interfaces → endpoints → **estos** → protocolo)
 * definida en la sección 1 de la investigación. Su presencia NO confirma que
 * el GW192A implemente el protocolo UVC de streaming completo: solo indica
 * qué declara en sus descriptors.
 */

/** Subtipos de descriptor class-specific de VideoStreaming (UVC §3.9.2). */
object UvcVsSubtype {
    const val INPUT_HEADER = 0x01
    const val OUTPUT_HEADER = 0x02
    const val STILL_IMAGE_FRAME = 0x03
    const val FORMAT_UNCOMPRESSED = 0x04
    const val FRAME_UNCOMPRESSED = 0x05
    const val FORMAT_MJPEG = 0x06
    const val FRAME_MJPEG = 0x07
    const val FORMAT_FRAME_BASED = 0x10
    const val FRAME_FRAME_BASED = 0x11
}

/** Subtipos de descriptor class-specific de VideoControl (UVC §3.7.2). */
object UvcVcSubtype {
    const val HEADER = 0x01
    const val INPUT_TERMINAL = 0x02
    const val OUTPUT_TERMINAL = 0x03
    const val SELECTOR_UNIT = 0x04
    const val PROCESSING_UNIT = 0x05
    const val EXTENSION_UNIT = 0x06
}

/**
 * Una resolución/frame declarada dentro de un formato UVC
 * (VS_FRAME_UNCOMPRESSED / VS_FRAME_MJPEG / VS_FRAME_FRAME_BASED).
 *
 * @param widthPx ancho declarado en píxeles (wWidth, leído directo del descriptor).
 * @param heightPx alto declarado en píxeles (wHeight, leído directo del descriptor).
 * @param defaultFrameIntervalUnits dwDefaultFrameInterval en unidades de 100ns (UVC).
 */
data class UvcFrameDescriptor(
    val frameIndex: Int,
    val widthPx: Int,
    val heightPx: Int,
    val defaultFrameIntervalUnits: Long,
) {
    /** Frames por segundo aproximados a partir del intervalo por defecto. */
    val approxFps: Double
        get() = if (defaultFrameIntervalUnits > 0) 10_000_000.0 / defaultFrameIntervalUnits else 0.0
}

/** Tipo de formato de video declarado (según el subtipo de descriptor UVC). */
enum class UvcFormatKind { UNCOMPRESSED, MJPEG, FRAME_BASED, UNKNOWN }

/**
 * Un formato de video declarado por la interfaz VideoStreaming, con sus
 * resoluciones/frames asociados. Para UNCOMPRESSED se incluye el GUID de
 * subtipo (p. ej. YUY2), leído directamente de los bytes del descriptor.
 */
data class UvcFormatDescriptor(
    val kind: UvcFormatKind,
    val formatIndex: Int,
    val guidHex: String? = null,
    val bitsPerPixel: Int? = null,
    val frames: List<UvcFrameDescriptor> = emptyList(),
) {
    /**
     * FourCC (p. ej. "NV12", "YUY2", "UYVY") decodificado de los primeros 4
     * bytes del GUID de 16 bytes del descriptor UVC_FORMAT_UNCOMPRESSED/
     * FRAME_BASED. Por especificación UVC, esos 4 bytes son el FourCC en
     * ASCII. null si no hay GUID (p. ej. formatos MJPEG) o no es ASCII legible.
     */
    val fourCc: String?
        get() {
            val hex = guidHex ?: return null
            if (hex.length < 8) return null
            return try {
                val chars = (0 until 4).map { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toChar()
                }
                val s = chars.joinToString("")
                if (s.all { it.code in 32..126 }) s else null
            } catch (e: Exception) {
                null
            }
        }
}

/**
 * Resultado del parseo de los descriptors crudos de configuración.
 *
 * @param videoControlInterfaceFound true si se encontró al menos un
 *   descriptor class-specific de VideoControl (evidencia de la interfaz #0).
 * @param streamingFormats formatos/resoluciones declarados por la interfaz
 *   VideoStreaming, leídos directamente de los descriptors.
 * @param rawDescriptorTotalBytes tamaño total de los descriptors crudos leídos.
 * @param parseLog líneas de diagnóstico del parseo (para depuración/registro).
 */
data class UvcParseResult(
    val videoControlInterfaceFound: Boolean = false,
    val streamingFormats: List<UvcFormatDescriptor> = emptyList(),
    val rawDescriptorTotalBytes: Int = 0,
    val parseLog: List<String> = emptyList(),
) {
    /**
     * Todos los pares (formato, frame) declarados, aplanados para elegir uno
     * al negociar el streaming.
     */
    val allFramesWithFormat: List<Pair<UvcFormatDescriptor, UvcFrameDescriptor>>
        get() = streamingFormats.flatMap { fmt -> fmt.frames.map { fr -> fmt to fr } }

    /**
     * HIPÓTESIS (no confirmada en general; descartada para el GW192A por
     * evidencia pública de terceros con el mismo hardware — ver
     * docs/GW192A_INVESTIGACION.md §13): un frame cuya altura es exactamente
     * el doble del ancho podría transportar imagen visible + datos crudos.
     * Se conserva solo como alternativa secundaria para otras variantes de
     * firmware/hardware, nunca como primera opción.
     */
    val doubleHeightCandidatesWithFormat: List<Pair<UvcFormatDescriptor, UvcFrameDescriptor>>
        get() = allFramesWithFormat.filter { (_, fr) -> fr.heightPx == fr.widthPx * 2 }

    /**
     * Selección de formato recomendada, basada en evidencia pública real
     * (ver docs/GW192A_INVESTIGACION.md §13.5): se prioriza el formato NV12
     * más simple (p. ej. 96×96), luego cualquier YUYV/UNCOMPRESSED sin
     * composición, y solo como último recurso el candidato de doble altura.
     * Ninguna opción implica temperatura calibrada: todas son solo imagen.
     */
    val recommendedFrame: Pair<UvcFormatDescriptor, UvcFrameDescriptor>?
        get() = orderedCandidates.firstOrNull()

    /**
     * TODOS los pares (formato, frame) declarados, ORDENADOS por preferencia,
     * para intentarlos uno por uno hasta que el dispositivo acepte negociar y
     * entregue datos (fallback automático). El orden de preferencia:
     *   1) NV12 (imagen de gris limpia según evidencia real), menor resolución.
     *   2) YUY2/YUYV/UYVY, menor resolución (evita compuestos con copias extra).
     *   3) Resto de UNCOMPRESSED/FRAME_BASED, menor resolución.
     *   4) Cualquier otro (incluido MJPEG), como último recurso.
     * Elegir la MENOR resolución reduce ancho de banda y evita el frame
     * compuesto 96×176 documentado. Los duplicados se eliminan conservando
     * la primera aparición.
     */
    val orderedCandidates: List<Pair<UvcFormatDescriptor, UvcFrameDescriptor>>
        get() {
            val all = allFramesWithFormat
            if (all.isEmpty()) return emptyList()

            fun area(p: Pair<UvcFormatDescriptor, UvcFrameDescriptor>) =
                p.second.widthPx * p.second.heightPx

            val nv12 = all.filter { (f, _) -> f.fourCc?.startsWith("NV12", true) == true }
                .sortedBy(::area)
            val yuyv = all.filter { (f, _) ->
                val c = f.fourCc?.uppercase(); c == "YUY2" || c == "YUYV" || c == "UYVY"
            }.sortedBy(::area)
            val otherUncompressed = all.filter { (f, _) ->
                f.kind == UvcFormatKind.UNCOMPRESSED || f.kind == UvcFormatKind.FRAME_BASED
            }.sortedBy(::area)
            val rest = all.sortedBy(::area)

            // Concatenar en orden de preferencia y quitar duplicados conservando
            // la primera aparición (identidad por formatIndex+frameIndex).
            return (nv12 + yuyv + otherUncompressed + rest)
                .distinctBy { (f, fr) -> f.formatIndex to fr.frameIndex }
        }
}
