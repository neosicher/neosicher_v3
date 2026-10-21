package com.neosicher.app.thermal

/**
 * Posición y valor del píxel de MAYOR y de MENOR luminancia cruda (raw, 0..255)
 * de un frame térmico.
 *
 * REGLA (docs/GW192A_INVESTIGACION.md §9, §14): el raw es luminancia relativa del
 * propio frame, NO temperatura. Aquí solo se localizan los extremos; no se
 * convierte nada a °C.
 */
data class RawExtremes(
    val maxX: Int,
    val maxY: Int,
    val maxValue: Int,
    val minX: Int,
    val minY: Int,
    val minValue: Int,
)

object RawExtremesFinder {

    /**
     * Busca el píxel más brillante y el más oscuro de [luma] ([width]x[height]).
     * Si hay empates se queda con el primero (recorrido fila por fila).
     * Devuelve null si las dimensiones no coinciden con el array.
     */
    fun find(luma: IntArray, width: Int, height: Int): RawExtremes? {
        if (width <= 0 || height <= 0 || luma.size < width * height) return null
        var maxIndex = 0
        var minIndex = 0
        var maxValue = luma[0]
        var minValue = luma[0]
        for (i in 1 until width * height) {
            val v = luma[i]
            if (v > maxValue) {
                maxValue = v
                maxIndex = i
            }
            if (v < minValue) {
                minValue = v
                minIndex = i
            }
        }
        return RawExtremes(
            maxX = maxIndex % width,
            maxY = maxIndex / width,
            maxValue = maxValue,
            minX = minIndex % width,
            minY = minIndex / width,
            minValue = minValue,
        )
    }
}
