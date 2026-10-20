package com.neosicher.app.thermal

import kotlin.math.sqrt

/**
 * Análisis de datos térmicos del GW192A en Kotlin PURO (sin Android), para
 * poder probarlo sin teléfono. Todo opera sobre los bytes/luminancia que el
 * propio dispositivo entrega; no se inventa ningún dato.
 *
 * REGLA (docs/GW192A_INVESTIGACION.md §9, §10, §14): la luminancia (Y) NO es
 * temperatura radiométrica. Aquí solo se localizan puntos y se describe el
 * contenido del frame; la conversión a °C vive en [ThermalCalibration] y es
 * siempre una aproximación EXPERIMENTAL.
 */

/** Un píxel localizado en el frame, con su valor (promedio local, para reducir ruido). */
data class ThermalPixel(val x: Int, val y: Int, val value: Double)

/** Estadísticas de un plano de luminancia 0..255. */
data class LumaStats(
    val min: Int,
    val max: Int,
    val mean: Double,
    val median: Int,
    val stdDev: Double,
    /** Cuántos valores distintos aparecen: indica la resolución real del dato. */
    val distinctLevels: Int,
)

/** Estadísticas de un canal cualquiera (p. ej. croma U o V). */
data class ChannelStats(val min: Int, val max: Int, val distinctLevels: Int)

/**
 * ¿La cámara estira cada frame a todo el rango (AGC por frame)?
 * Es una HEURÍSTICA para decidir si raw->°C puede funcionar: si cada frame se
 * normaliza a 0..255, el mismo objeto cambia de valor según lo que haya en la
 * escena y una calibración fija NO es válida.
 */
enum class AgcVerdict {
    /** Pocos frames o sin patrón claro. */
    UNKNOWN,

    /** Todos los frames recientes ocupan casi todo 0..255: probable normalización por frame. */
    LIKELY_STRETCHED_EVERY_FRAME,

    /** El mínimo/máximo cambia entre frames: no se está forzando siempre el mismo rango. */
    RANGE_VARIES,
}

object ThermalAnalysis {

    /** Estadísticas de luminancia. null si el array está vacío. */
    fun lumaStats(luma: IntArray): LumaStats? {
        if (luma.isEmpty()) return null
        val histogram = IntArray(256)
        var min = 255
        var max = 0
        var sum = 0L
        for (raw in luma) {
            val v = raw.coerceIn(0, 255)
            histogram[v]++
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        val mean = sum.toDouble() / luma.size
        var squares = 0.0
        for (raw in luma) {
            val d = raw.coerceIn(0, 255) - mean
            squares += d * d
        }
        val half = (luma.size + 1) / 2
        var cumulative = 0
        var median = 0
        for (level in 0..255) {
            cumulative += histogram[level]
            if (cumulative >= half) {
                median = level
                break
            }
        }
        return LumaStats(
            min = min,
            max = max,
            mean = mean,
            median = median,
            stdDev = sqrt(squares / luma.size),
            distinctLevels = histogram.count { it > 0 },
        )
    }

    /** Estadísticas de un canal arbitrario. null si está vacío. */
    fun channelStats(values: IntArray): ChannelStats? {
        if (values.isEmpty()) return null
        val seen = BooleanArray(256)
        var min = 255
        var max = 0
        for (raw in values) {
            val v = raw.coerceIn(0, 255)
            seen[v] = true
            if (v < min) min = v
            if (v > max) max = v
        }
        return ChannelStats(min, max, seen.count { it })
    }

    /** Promedio de la ventana cuadrada de radio [radius] centrada en ([cx],[cy]), recortada a los bordes. */
    fun localMean(luma: IntArray, width: Int, height: Int, cx: Int, cy: Int, radius: Int): Double {
        var sum = 0.0
        var count = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until width && y in 0 until height) {
                    sum += luma[y * width + x]
                    count++
                }
            }
        }
        return if (count > 0) sum / count else 0.0
    }

    /**
     * Localiza el punto más caliente y el más frío (mayor y menor luminancia).
     * Usa el promedio local de radio [radius] para que un píxel ruidoso aislado
     * no sea elegido como extremo. Devuelve (caliente, frío) o null si el
     * array no coincide con las dimensiones.
     */
    fun findExtremes(luma: IntArray, width: Int, height: Int, radius: Int = 1): Pair<ThermalPixel, ThermalPixel>? {
        if (width <= 0 || height <= 0 || luma.size < width * height) return null
        var hot = ThermalPixel(0, 0, -1.0)
        var cold = ThermalPixel(0, 0, Double.MAX_VALUE)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = localMean(luma, width, height, x, y, radius)
                if (value > hot.value) hot = ThermalPixel(x, y, value)
                if (value < cold.value) cold = ThermalPixel(x, y, value)
            }
        }
        return hot to cold
    }

    /** Bytes en hexadecimal ("0A FF ..."), recortado a los límites del array. */
    fun hexDump(bytes: ByteArray, offset: Int, count: Int): String {
        val start = offset.coerceIn(0, bytes.size)
        val end = (offset + count).coerceIn(start, bytes.size)
        return (start until end).joinToString(" ") { "%02X".format(bytes[it].toInt() and 0xFF) }
    }

    /**
     * Describe QUÉ contiene realmente un frame crudo, para decidir con datos
     * (y no a ojo) si hay información térmica fuera del plano Y:
     *  - tamaño recibido vs. esperado,
     *  - croma U/V (¿varía? entonces el color podría codificar algo más),
     *  - filas extra bajo la imagen (p. ej. 96x100) y bytes sobrantes,
     *  - volcado hexadecimal de esas zonas.
     */
    fun describeLayout(bytes: ByteArray, width: Int, height: Int, fourCc: String?): List<String> {
        val lines = mutableListOf<String>()
        val format = fourCc?.uppercase() ?: "?"
        val isNv12 = format.startsWith("NV12")
        val isUyvy = format == "UYVY"
        val expected = if (isNv12) width * height * 3 / 2 else width * height * 2

        lines.add("Formato $format · declarado ${width}x$height")
        val delta = bytes.size - expected
        val deltaText = when {
            delta == 0 -> "exacto"
            delta > 0 -> "sobran $delta"
            else -> "faltan ${-delta}"
        }
        lines.add("Bytes: recibidos ${bytes.size} · esperados $expected ($deltaText)")

        if (isNv12) {
            val lumaEnd = (width * height).coerceAtMost(bytes.size)
            val y = IntArray(lumaEnd) { bytes[it].toInt() and 0xFF }
            appendChannel(lines, "Y", y)
            val chromaEnd = (width * height * 3 / 2).coerceAtMost(bytes.size)
            if (chromaEnd > lumaEnd) {
                val chroma = IntArray(chromaEnd - lumaEnd) { bytes[lumaEnd + it].toInt() and 0xFF }
                appendChannel(lines, "UV", chroma)
                appendChromaVerdict(lines, listOfNotNull(ThermalAnalysis.channelStats(chroma)))
            }
        } else {
            val limit = (width * height * 2).coerceAtMost(bytes.size)
            val yList = ArrayList<Int>()
            val uList = ArrayList<Int>()
            val vList = ArrayList<Int>()
            var i = 0
            while (i + 4 <= limit) {
                if (isUyvy) {
                    uList.add(bytes[i].toInt() and 0xFF)
                    yList.add(bytes[i + 1].toInt() and 0xFF)
                    vList.add(bytes[i + 2].toInt() and 0xFF)
                    yList.add(bytes[i + 3].toInt() and 0xFF)
                } else {
                    yList.add(bytes[i].toInt() and 0xFF)
                    uList.add(bytes[i + 1].toInt() and 0xFF)
                    yList.add(bytes[i + 2].toInt() and 0xFF)
                    vList.add(bytes[i + 3].toInt() and 0xFF)
                }
                i += 4
            }
            appendChannel(lines, "Y", yList.toIntArray())
            appendChannel(lines, "U", uList.toIntArray())
            appendChannel(lines, "V", vList.toIntArray())
            appendChromaVerdict(
                lines,
                listOfNotNull(channelStats(uList.toIntArray()), channelStats(vList.toIntArray())),
            )

            // Filas extra bajo la imagen cuadrada (p. ej. 96x100: 4 filas más).
            if (height > width && width > 0) {
                val rowBytes = width * 2
                for (row in width until height) {
                    val start = row * rowBytes
                    if (start + rowBytes > bytes.size) break
                    val rowY = IntArray(width) { bytes[start + it * 2 + (if (isUyvy) 1 else 0)].toInt() and 0xFF }
                    val s = channelStats(rowY)
                    val range = if (s != null) "${s.min}..${s.max}" else "?"
                    lines.add("Fila extra $row: Y $range · ${hexDump(bytes, start, 16)}")
                }
            }
        }

        if (bytes.size > expected) {
            lines.add("Bytes sobrantes (${bytes.size - expected}): ${hexDump(bytes, expected, 32)}")
        }
        return lines
    }

    private fun appendChannel(lines: MutableList<String>, name: String, values: IntArray) {
        val s = channelStats(values)
        if (s == null) {
            lines.add("$name: sin datos")
        } else {
            lines.add("$name: ${s.min}..${s.max} · ${s.distinctLevels} niveles distintos")
        }
    }

    private fun appendChromaVerdict(lines: MutableList<String>, stats: List<ChannelStats>) {
        if (stats.isEmpty()) return
        val varies = stats.any { it.distinctLevels > 3 }
        lines.add(
            if (varies) {
                "Croma VARÍA: el color podría codificar información extra (por verificar)"
            } else {
                "Croma casi constante: la información está solo en Y"
            }
        )
    }
}

/**
 * Vigila el mínimo/máximo de los últimos frames para sospechar de AGC por frame.
 * Resultado orientativo (HIPÓTESIS), no una prueba: confirmarlo con el
 * experimento de la pantalla Laboratorio térmico.
 */
class AgcMonitor(private val windowSize: Int = 30) {
    private val mins = ArrayDeque<Int>()
    private val maxs = ArrayDeque<Int>()

    fun add(min: Int, max: Int) {
        mins.addLast(min)
        maxs.addLast(max)
        while (mins.size > windowSize) {
            mins.removeFirst()
            maxs.removeFirst()
        }
    }

    fun verdict(): AgcVerdict {
        if (mins.size < MIN_FRAMES) return AgcVerdict.UNKNOWN
        val stretched = mins.all { it <= STRETCH_MIN } && maxs.all { it >= STRETCH_MAX }
        if (stretched) return AgcVerdict.LIKELY_STRETCHED_EVERY_FRAME
        val spread = (mins.max() - mins.min()) + (maxs.max() - maxs.min())
        return if (spread > VARIES_THRESHOLD) AgcVerdict.RANGE_VARIES else AgcVerdict.UNKNOWN
    }

    companion object {
        const val MIN_FRAMES = 15
        const val STRETCH_MIN = 2
        const val STRETCH_MAX = 253
        const val VARIES_THRESHOLD = 10
    }
}

/** Medidor de fotogramas por segundo sobre una ventana deslizante. */
class FpsMeter(private val window: Int = 30) {
    private val stamps = ArrayDeque<Long>()

    /** Registra un frame en [nowMs] (reloj monótono) y devuelve los fps actuales. */
    fun tick(nowMs: Long): Double {
        stamps.addLast(nowMs)
        while (stamps.size > window) stamps.removeFirst()
        if (stamps.size < 2) return 0.0
        val elapsed = stamps.last() - stamps.first()
        return if (elapsed <= 0) 0.0 else (stamps.size - 1) * 1000.0 / elapsed
    }

    fun reset() = stamps.clear()
}
