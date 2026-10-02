package com.neosicher.app.thermal

import android.graphics.Bitmap
import android.graphics.Color
import com.neosicher.app.usb.UvcFormatDescriptor
import com.neosicher.app.usb.YuyvDecoder

/**
 * Interpreta un frame UVC crudo del GW192A según el **FourCC real** declarado
 * por el propio dispositivo en sus descriptors (ver [UvcFormatDescriptor.fourCc]).
 *
 * Estrategia actualizada con evidencia pública real (ver
 * docs/GW192A_INVESTIGACION.md §13.5): otra persona con el MISMO hardware
 * (GOYOJO GW192A) confirmó públicamente que:
 *  - El sensor real es 96×96 (no 192×192, pese al marketing).
 *  - Expone NV12 96×96 (gris limpio), YUYV422 96×100 (verde alto contraste) y
 *    YUYV422 96×176 (compuesto con copias extra, no útil).
 *  - NINGÚN formato contiene datos de 16 bits / temperatura radiométrica.
 *
 * Por eso esta clase SIEMPRE produce solo una paleta de calor **relativa**
 * (luminancia normalizada min–max del propio frame), nunca temperatura en
 * grados. Es la misma estrategia que usan herramientas públicas de terceros
 * para este hardware (p. ej. `cv2.applyColorMap` sobre escala de grises).
 *
 * REGLAS QUE ESTA CLASE RESPETA (docs/GW192A_INVESTIGACION.md §6, §14):
 * - NO calcula temperatura en °C: no hay calibración, emissivity ni tabla de
 *   referencia confirmada. Ni siquiera la evidencia pública de terceros con
 *   el mismo hardware logró extraer temperatura real.
 * - Expone los valores de luminancia (min/max) crudos para referencia, nunca
 *   como medición.
 * - Todo resultado se considera EXPERIMENTAL.
 */
object ThermalFrameInterpreter {

    /**
     * Interpreta [frameBytes] según el FourCC real de [format] (NV12, YUY2/
     * YUYV/UYVY, u otro). Extrae solo la luminancia y aplica una paleta de
     * calor relativa. Si el FourCC no se reconoce, intenta YUYV como fallback
     * razonable (es el formato UVC sin comprimir más común).
     */
    fun interpretFrame(
        frameBytes: ByteArray,
        width: Int,
        height: Int,
        format: UvcFormatDescriptor,
    ): ThermalFrameResult? {
        if (width <= 0 || height <= 0) return null

        val fourCc = format.fourCc?.uppercase()
        val luma = when {
            fourCc?.startsWith("NV12") == true -> {
                if (frameBytes.size < width * height) return null
                YuyvDecoder.extractLumaNv12(frameBytes, width, height)
            }
            fourCc == "YUY2" || fourCc == "YUYV" || fourCc == "UYVY" || fourCc == null -> {
                if (frameBytes.size < width * height * 2) return null
                YuyvDecoder.extractLumaYuyv(frameBytes, width, height)
            }
            else -> {
                // Formato no reconocido (p. ej. MJPEG): no se interpreta para
                // evitar decodificar basura. Se deja sin resultado honesto.
                return null
            }
        }

        val heatmap = YuyvDecoder.lumaToHeatmap(luma, width, height)
        var min = 255
        var max = 0
        for (v in luma) {
            if (v < min) min = v
            if (v > max) max = v
        }

        return ThermalFrameResult(
            visibleBitmap = heatmap,
            thermalPaletteBitmap = heatmap,
            rawMin = min,
            rawMax = max,
            // Se conserva el array de luminancia crudo (0..255 por píxel) para
            // que la pantalla de calibración pueda muestrear un punto exacto
            // tocado por el usuario, y para que el ViewModel pueda leer el
            // punto central y aplicar la calibración del usuario (ver
            // ThermalCalibration). Nunca se usa aquí mismo para calcular °C.
            rawLuma = luma,
            rawWidth = width,
            rawHeight = height,
        )
    }

    /**
     * Interpreta un frame "doble altura" (mitad visible YUYV + mitad datos
     * crudos raw16), solo para variantes de hardware/firmware donde SÍ se
     * confirme ese patrón mediante descriptors (altura = 2× ancho). Para el
     * GW192A esto está descartado por evidencia real (ver §13.5); se conserva
     * como alternativa secundaria por si aplica a otra unidad/firmware.
     */
    fun interpretDoubleHeightFrame(frameBytes: ByteArray, width: Int, height: Int): ThermalFrameResult? {
        if (width <= 0 || height <= 0 || height != width * 2) return null

        val visibleHeight = height / 2
        val visibleByteLen = width * visibleHeight * 2 // YUYV = 2 bytes/píxel
        if (frameBytes.size < visibleByteLen) return null

        val visibleBytes = frameBytes.copyOfRange(0, visibleByteLen)
        val visibleBitmap = YuyvDecoder.decode(visibleBytes, width, visibleHeight)

        val thermalBytes = frameBytes.copyOfRange(
            visibleByteLen,
            minOf(frameBytes.size, visibleByteLen + width * visibleHeight * 2),
        )
        val thermalCandidate = decodeRaw16Palette(thermalBytes, width, visibleHeight)

        return ThermalFrameResult(
            visibleBitmap = visibleBitmap,
            thermalPaletteBitmap = thermalCandidate?.bitmap,
            rawMin = thermalCandidate?.min,
            rawMax = thermalCandidate?.max,
        )
    }

    /**
     * Lee la mitad "térmica" como muestras raw16 little-endian (hipótesis,
     * NO confirmada) y genera una paleta de calor **normalizada dentro del
     * propio frame** (min→azul, max→rojo). No representa °C.
     */
    private fun decodeRaw16Palette(bytes: ByteArray, width: Int, height: Int): PaletteResult? {
        val count = width * height
        if (bytes.size < count * 2) return null

        val raw = IntArray(count)
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            val value = lo or (hi shl 8)
            raw[i] = value
            if (value < min) min = value
            if (value > max) max = value
        }
        if (max <= min) return null // frame degenerado/sin variación: no se dibuja nada

        val pixels = IntArray(count)
        val range = (max - min).toFloat()
        for (i in 0 until count) {
            val t = (raw[i] - min) / range // 0..1
            pixels[i] = heatColor(t)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return PaletteResult(bitmap, min, max)
    }

    /** Gradiente azul→rojo simple (t en [0,1]). Solo visualización relativa. */
    private fun heatColor(t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val r = (clamped * 255).toInt()
        val b = ((1f - clamped) * 255).toInt()
        val g = (255 - kotlin.math.abs(clamped - 0.5f) * 2 * 255).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }

    private data class PaletteResult(val bitmap: Bitmap, val min: Int, val max: Int)
}

/**
 * Resultado de interpretar un frame térmico.
 *
 * @param visibleBitmap imagen a mostrar (en el flujo nuevo, la propia paleta
 *   de calor; en el flujo de doble altura, la imagen visible decodificada).
 * @param thermalPaletteBitmap visualización de calor **relativa** (o null si
 *   no se pudo generar). NUNCA representa temperatura en °C.
 * @param rawMin / [rawMax] valores de luminancia/raw crudos del frame, sin
 *   calibrar. Solo referencia relativa, nunca medición.
 * @param rawLuma array de luminancia (0..255) de cada píxel, fila por fila
 *   (tamaño = rawWidth*rawHeight), o null si no aplica (p. ej. doble altura).
 *   Permite muestrear un punto exacto para calibración manual del usuario.
 * @param rawWidth / [rawHeight] dimensiones de [rawLuma].
 */
data class ThermalFrameResult(
    val visibleBitmap: Bitmap,
    val thermalPaletteBitmap: Bitmap?,
    val rawMin: Int?,
    val rawMax: Int?,
    val rawLuma: IntArray? = null,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
) {
    /**
     * Promedio de luminancia en una pequeña ventana cuadrada centrada en el
     * punto relativo ([u], [v] en [0,1]×[0,1] respecto al bitmap mostrado).
     * Reduce ruido de un solo píxel. Devuelve null si no hay datos crudos.
     */
    fun sampleRelativePoint(u: Float, v: Float, windowRadiusPx: Int = 2): Double? {
        val luma = rawLuma ?: return null
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val cx = (u.coerceIn(0f, 1f) * (rawWidth - 1)).toInt()
        val cy = (v.coerceIn(0f, 1f) * (rawHeight - 1)).toInt()
        var sum = 0.0
        var count = 0
        for (dy in -windowRadiusPx..windowRadiusPx) {
            for (dx in -windowRadiusPx..windowRadiusPx) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until rawWidth && y in 0 until rawHeight) {
                    sum += luma[y * rawWidth + x]
                    count++
                }
            }
        }
        return if (count > 0) sum / count else null
    }

    /** Punto central del frame (p. ej. zona de la frente si está centrada). */
    fun sampleCenterPoint(): Double? = sampleRelativePoint(0.5f, 0.5f)
}
