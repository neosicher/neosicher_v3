package com.neosicher.app.vision

import kotlin.math.sqrt

/**
 * Geometría pura (sin Android) usada por el analizador de postura.
 * Al ser Kotlin puro se puede probar sin teléfono.
 */
object PoseGeometry {

    /** Por debajo de este valor normalizado el resultado se considera "no concluyente". */
    const val MIN_CHEST_CONFIDENCE = 0.3f

    /**
     * ML Kit devuelve coordenadas en la imagen que SE LE ENTREGÓ. Si antes de
     * entregarla se giró [extraRotationDegrees] grados en sentido horario (para
     * que un bebé tumbado quede "derecho"), hay que deshacer ese giro para
     * dibujar sobre el visor.
     *
     * @param x,y coordenadas en la imagen girada.
     * @param displayWidth,displayHeight tamaño de la imagen SIN el giro extra.
     * @return coordenadas en la imagen sin el giro extra.
     */
    fun rotatedToDisplay(
        x: Float,
        y: Float,
        extraRotationDegrees: Int,
        displayWidth: Float,
        displayHeight: Float,
    ): Pair<Float, Float> =
        when (((extraRotationDegrees % 360) + 360) % 360) {
            90 -> y to (displayHeight - x)
            180 -> (displayWidth - x) to (displayHeight - y)
            270 -> (displayWidth - y) to x
            else -> x to y
        }

    /**
     * ¿Se ve el PECHO del bebé o su ESPALDA?
     *
     * Idea: el modelo de pose etiqueta hombro izquierdo/derecho de forma
     * anatómica. Mirando a una persona de frente, su hombro izquierdo cae a la
     * derecha de la imagen; de espaldas, a la izquierda. Con el vector
     * "hombro derecho -> hombro izquierdo" y el eje "cadera -> cabeza", el signo
     * del producto cruzado dice de qué lado estamos mirando. Ese signo no cambia
     * al girar la imagen, así que sirve con el bebé tumbado en cualquier
     * dirección.
     *
     * HIPÓTESIS (NO verificada con bebés reales): que ML Kit mantenga bien la
     * izquierda/derecha con un cuerpo tumbado visto desde arriba. Por eso el
     * resultado se usa como una pista más, nunca como certeza.
     *
     * @return true = pecho hacia la cámara, false = espalda hacia la cámara,
     *   null = no concluyente (cuerpo de canto o medidas inválidas).
     */
    fun chestFacing(
        leftShoulderX: Float,
        leftShoulderY: Float,
        rightShoulderX: Float,
        rightShoulderY: Float,
        axisX: Float,
        axisY: Float,
    ): Boolean? {
        val shoulderX = leftShoulderX - rightShoulderX
        val shoulderY = leftShoulderY - rightShoulderY
        val shoulderLength = sqrt(shoulderX * shoulderX + shoulderY * shoulderY)
        val axisLength = sqrt(axisX * axisX + axisY * axisY)
        if (shoulderLength < 1f || axisLength < 1f) return null

        // Producto cruzado 2D en coordenadas de imagen (y hacia abajo).
        val cross = shoulderX * axisY - shoulderY * axisX
        val normalized = cross / (shoulderLength * axisLength)
        return when {
            normalized < -MIN_CHEST_CONFIDENCE -> true
            normalized > MIN_CHEST_CONFIDENCE -> false
            else -> null
        }
    }
}
