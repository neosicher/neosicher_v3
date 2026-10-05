package com.neosicher.app.vision

/**
 * Postura al dormir estimada del bebé a partir de la detección de pose
 * (ML Kit) sobre la cámara Android.
 *
 * REGLA DEL PROYECTO (ver docs/GW192A_INVESTIGACION.md §9, §10): no inventar
 * datos. Esta estimación es EXPERIMENTAL:
 *  - Los modelos de detección de pose están entrenados mayoritariamente con
 *    ADULTOS de pie; su exactitud sobre un bebé acostado, posiblemente
 *    envuelto o parcialmente tapado, y con iluminación nocturna, NO está
 *    garantizada.
 *  - Nunca debe presentarse como dispositivo de seguridad ni diagnóstico
 *    médico. Es una ayuda visual aproximada.
 *
 * Por eso [UNKNOWN] es el valor por defecto y se usa siempre que la confianza
 * sea insuficiente: no se fuerza una clasificación sin evidencia.
 */
enum class SleepPosition {
    /** No hay pose detectada o la confianza es insuficiente para clasificar. */
    UNKNOWN,

    /** Boca arriba (de espaldas). */
    SUPINE,

    /** De lado (lateral izquierdo o derecho). */
    SIDE,

    /** Boca abajo (prono). Postura asociada a mayor riesgo — se resalta en la UI. */
    PRONE;

    /** Etiqueta legible en español para la UI. */
    val displayLabel: String
        get() = when (this) {
            UNKNOWN -> "Posición no determinada"
            SUPINE -> "Boca arriba"
            SIDE -> "De lado"
            PRONE -> "Boca abajo"
        }
}

/**
 * Un landmark normalizado de la pose, en coordenadas de la imagen analizada.
 *
 * @param type identificador del punto (ver [PoseLandmarkType]).
 * @param x coordenada X en píxeles de la imagen de análisis.
 * @param y coordenada Y en píxeles de la imagen de análisis.
 * @param inFrameLikelihood probabilidad [0,1] de que el punto esté realmente
 *   en el encuadre (la expone ML Kit por landmark).
 */
data class PosePoint(
    val type: Int,
    val x: Float,
    val y: Float,
    val inFrameLikelihood: Float,
)

/**
 * Resultado observable de la estimación de postura.
 *
 * @param position postura estimada (UNKNOWN si no hay evidencia suficiente).
 * @param confidence confianza agregada [0,1], deliberadamente conservadora.
 * @param landmarks puntos detectados (para dibujar el esqueleto en el overlay).
 * @param sourceImageWidth ancho de la imagen de análisis a la que refieren los
 *   landmarks (para que el overlay escale correctamente).
 * @param sourceImageHeight alto de la imagen de análisis.
 * @param personDetected true si se detectó una persona/cuerpo en el encuadre.
 */
data class SleepPositionState(
    val position: SleepPosition = SleepPosition.UNKNOWN,
    val confidence: Float = 0f,
    val landmarks: List<PosePoint> = emptyList(),
    val sourceImageWidth: Int = 0,
    val sourceImageHeight: Int = 0,
    val personDetected: Boolean = false,
) {
    companion object {
        val EMPTY = SleepPositionState()
    }
}
