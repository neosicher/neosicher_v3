package com.neosicher.app.thermal

import android.graphics.Bitmap
import android.graphics.Color
import com.neosicher.app.usb.YuyvDecoder

/**
 * Interpreta un frame UVC crudo del GW192A aplicando la hipótesis de
 * **"doble altura"** documentada en docs/GW192A_INVESTIGACION.md §13: la
 * mitad superior es la imagen visible (YUYV) y la mitad inferior contiene
 * datos crudos del sensor térmico.
 *
 * Esta clase solo debe invocarse cuando [UvcParseResult] ya confirmó (por los
 * descriptors reales del dispositivo) un frame con altura = 2× ancho —
 * ver [UvcParseResult.doubleHeightCandidates]. Si esa condición no se cumplió,
 * la capa superior (ver [Gw192aThermalCameraDataSource]) no debe llamar aquí.
 *
 * REGLAS QUE ESTA CLASE RESPETA (docs/GW192A_INVESTIGACION.md §6, §14):
 * - NO calcula temperatura en °C: no existe calibración, emissivity ni tabla
 *   de referencia confirmada para el GW192A. Inventar una fórmula de
 *   conversión sería presentar un dato médico/técnico falso.
 * - Solo produce una **visualización de paleta de calor relativa**: normaliza
 *   los valores crudos del propio frame (min–max) y los mapea a un gradiente
 *   de color. Es una ayuda visual, no una medición.
 * - Expone los valores crudos (raw16 counts) tal cual, para que cualquier
 *   futura calibración real parta de datos honestos.
 * - Todo resultado consumido por capas superiores debe marcarse con estado
 *   EXPERIMENTAL (ver ThermalReadingStatus), nunca como lectura validada.
 */
object ThermalFrameInterpreter {

    /**
     * @param frameBytes bytes crudos completos del frame (según lo entregado
     *   por [com.neosicher.app.usb.UvcStreamingSession]).
     * @param width ancho negociado (p. ej. 192).
     * @param height alto TOTAL negociado (p. ej. 384 = 2 × 192).
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
     * Interpreta un frame UVC "normal" (no doble altura) decodificándolo como
     * YUYV y mostrándolo tal cual. Es útil para dispositivos como el GW192A que
     * entregan la imagen térmica ya coloreada por el propio hardware (14
     * paletas, según el fabricante) a través de un stream UVC estándar.
     *
     * No se inventa nada: se muestra el vídeo real transmitido. No hay datos
     * térmicos crudos separados, por lo que rawMin/rawMax quedan en null.
     */
    fun interpretVisibleFrame(frameBytes: ByteArray, width: Int, height: Int): ThermalFrameResult? {
        if (width <= 0 || height <= 0) return null
        val expected = width * height * 2 // YUYV = 2 bytes/píxel
        if (frameBytes.size < expected) return null
        val visibleBitmap = YuyvDecoder.decode(frameBytes, width, height)
        return ThermalFrameResult(
            visibleBitmap = visibleBitmap,
            thermalPaletteBitmap = null,
            rawMin = null,
            rawMax = null,
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
 * Resultado de interpretar un frame de doble altura.
 *
 * @param visibleBitmap imagen visible decodificada de YUYV (mitad superior).
 * @param thermalPaletteBitmap visualización de calor **relativa** (o null si
 *   el frame no permitió decodificarla). NO representa temperatura en °C.
 * @param rawMin / [rawMax] valores crudos (counts del sensor) mínimo y máximo
 *   del frame, sin calibrar. Útiles solo como referencia relativa.
 */
data class ThermalFrameResult(
    val visibleBitmap: Bitmap,
    val thermalPaletteBitmap: Bitmap?,
    val rawMin: Int?,
    val rawMax: Int?,
)
