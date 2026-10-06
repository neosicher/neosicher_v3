package com.neosicher.app.vision

/**
 * Postura al dormir estimada del bebé a partir de la cámara Android
 * (NEOSICHER v2: detección de pose + detección de rostro de ML Kit).
 *
 * REGLA DEL PROYECTO (ver docs/GW192A_INVESTIGACION.md §9, §10): no inventar
 * datos. Esta estimación es EXPERIMENTAL:
 *  - Los modelos de ML Kit están entrenados mayoritariamente con ADULTOS; su
 *    exactitud sobre un bebé acostado, envuelto o con poca luz NO está
 *    garantizada.
 *  - Nunca debe presentarse como dispositivo de seguridad ni diagnóstico
 *    médico. Es una ayuda visual aproximada; no sustituye la supervisión.
 *
 * [UNKNOWN] es el valor por defecto y se usa siempre que no haya evidencia
 * suficiente: no se fuerza una clasificación.
 */
enum class SleepPosition {
    /** No hay bebé detectado o la evidencia es insuficiente para clasificar. */
    UNKNOWN,

    /** Boca arriba (de espaldas): cara visible de frente. */
    SUPINE,

    /** De lado / volteado: cuerpo lateral o cabeza girada. */
    SIDE,

    /** Boca abajo (prono): torso visible, sin cara, tras haberse volteado. */
    PRONE,

    /**
     * Cara posiblemente tapada (manta, peluche, etc.): la cara desapareció de
     * golpe mientras el torso siguió visible y de frente.
     */
    FACE_COVERED;

    /** Posturas que la app trata como de atención (resaltadas en rojo). */
    val isRisk: Boolean
        get() = this == PRONE || this == FACE_COVERED

    /** Etiqueta legible en español para la UI. */
    val displayLabel: String
        get() = when (this) {
            UNKNOWN -> "Posición no determinada"
            SUPINE -> "Boca arriba"
            SIDE -> "Volteado (de lado)"
            PRONE -> "Boca abajo"
            FACE_COVERED -> "Cara posiblemente tapada"
        }
}

/**
 * Un landmark de la pose, en coordenadas de la imagen analizada.
 *
 * @param type identificador del punto (ver PoseLandmark de ML Kit).
 * @param x coordenada X en píxeles de la imagen de análisis.
 * @param y coordenada Y en píxeles de la imagen de análisis.
 * @param inFrameLikelihood probabilidad [0,1] de que el punto esté en el encuadre.
 */
data class PosePoint(
    val type: Int,
    val x: Float,
    val y: Float,
    val inFrameLikelihood: Float,
)

/** Recuadro de la cara detectada, en píxeles de la imagen de análisis. */
data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Resultado observable de la estimación de postura.
 *
 * @param position postura estimada (UNKNOWN si no hay evidencia suficiente).
 * @param confidence confianza agregada [0,1], deliberadamente conservadora.
 * @param isAlert true si una postura de atención ([SleepPosition.isRisk]) se
 *   mantiene de forma sostenida (no por un frame suelto).
 * @param landmarks puntos de la pose (para dibujar el esqueleto).
 * @param faceBox recuadro de la cara detectada, o null si no hay cara.
 * @param sourceImageWidth ancho de la imagen de análisis a la que refieren los puntos.
 * @param sourceImageHeight alto de la imagen de análisis.
 * @param personDetected true si se detectó un cuerpo en el encuadre.
 */
data class SleepPositionState(
    val position: SleepPosition = SleepPosition.UNKNOWN,
    val confidence: Float = 0f,
    val isAlert: Boolean = false,
    val landmarks: List<PosePoint> = emptyList(),
    val faceBox: FaceBox? = null,
    val sourceImageWidth: Int = 0,
    val sourceImageHeight: Int = 0,
    val personDetected: Boolean = false,
    /** Diagnóstico: frames recibidos de la cámara (si no sube, el analizador no está enganchado). */
    val framesReceived: Long = 0,
    /** Diagnóstico: número de caras que ve el detector de rostros en este frame. */
    val faceCount: Int = 0,
    /** Diagnóstico: razón hombros/torso medida (null si los hombros no son fiables). */
    val shoulderRatio: Float? = null,
    /** Diagnóstico: giro horizontal de la cabeza en grados (null si no hay cara). */
    val faceYawDegrees: Float? = null,
    /** Diagnóstico: mensaje si falló algún modelo de ML Kit; null si todo bien. */
    val analysisError: String? = null,
) {
    companion object {
        val EMPTY = SleepPositionState()
    }
}
