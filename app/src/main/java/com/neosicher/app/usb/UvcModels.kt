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
)

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
     * HIPÓTESIS (no confirmada): un formato cuya altura declarada es
     * exactamente el doble del ancho podría corresponder al patrón, público y
     * documentado en proyectos open-source de terceros para módulos térmicos
     * UVC similares (no GW192A específicamente), donde la mitad superior del
     * frame es la imagen visible y la mitad inferior transporta datos
     * térmicos crudos dentro del mismo stream. Esto NO está confirmado para
     * el GW192A: requiere abrir el stream real y validar los bytes.
     */
    val doubleHeightCandidates: List<UvcFrameDescriptor>
        get() = streamingFormats.flatMap { it.frames }.filter { it.heightPx == it.widthPx * 2 }

    /**
     * Igual que [doubleHeightCandidates] pero conservando el formato padre de
     * cada frame candidato (necesario para negociar el Probe/Commit con el
     * par formatIndex/frameIndex correcto).
     */
    val doubleHeightCandidatesWithFormat: List<Pair<UvcFormatDescriptor, UvcFrameDescriptor>>
        get() = streamingFormats.flatMap { fmt -> fmt.frames.map { fr -> fmt to fr } }
            .filter { (_, fr) -> fr.heightPx == fr.widthPx * 2 }
}
