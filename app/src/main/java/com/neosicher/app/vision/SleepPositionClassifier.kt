package com.neosicher.app.vision

import kotlin.math.abs

/**
 * Observación cruda de UN frame, independiente de Android y de ML Kit.
 * Separarla permite razonar y probar la lógica de clasificación sin hardware.
 *
 * @param timestampMs reloj monótono en milisegundos.
 * @param bodyDetected ML Kit Pose encontró un cuerpo.
 * @param shouldersReliable ambos hombros detectados con confianza suficiente.
 * @param shoulderRatio ancho de hombros / largo de torso (null si no se pudo medir).
 * @param faceDetected el detector de rostros de ML Kit encontró una cara.
 * @param faceYawDegrees giro horizontal de la cabeza (0 = de frente), si hay cara.
 */
data class FrameObservation(
    val timestampMs: Long,
    val bodyDetected: Boolean,
    val shouldersReliable: Boolean,
    val shoulderRatio: Float?,
    val faceDetected: Boolean,
    val faceYawDegrees: Float?,
    /** true = se ve el pecho, false = se ve la espalda, null = no concluyente (ver PoseGeometry.chestFacing). */
    val chestFacing: Boolean? = null,
)

/** Resultado ya suavizado en el tiempo. */
data class ClassifierOutput(
    val position: SleepPosition,
    val confidence: Float,
    val isAlert: Boolean,
)

/**
 * Clasifica la postura del bebé combinando evidencia de varios frames.
 *
 * Reglas (heurísticas, NO una medición certificada):
 *  1. Cara visible y de frente            -> boca arriba.
 *  2. Cara girada, o cuerpo lateral       -> volteado (de lado).
 *  3. Sin cara + torso de frente:
 *       - la cara estaba visible y se perdió SIN que el cuerpo pasara por una
 *         posición lateral -> cara tapada (desapareció de golpe).
 *       - el cuerpo pasó por posición lateral tras perder la cara -> boca abajo
 *         (se volteó).
 *       - nunca se vio la cara -> boca abajo con confianza baja.
 *
 * LIMITACIÓN CONOCIDA (no ocultar): desde una sola cámara 2D, un bebé boca
 * abajo con la cabeza girada se parece a uno boca arriba con la cabeza girada,
 * y "boca abajo" vs "cara tapada" pueden confundirse. Por eso ambas se tratan
 * como posturas de atención ([SleepPosition.isRisk]) y se reportan con
 * confianza baja, nunca como certeza.
 *
 * Para evitar parpadeos, la etiqueta final sale de una votación sobre una
 * ventana corta de frames, y la alerta solo se activa si una postura de
 * atención se sostiene unos segundos.
 */
class SleepPositionClassifier {

    private data class Vote(val timestampMs: Long, val label: SleepPosition, val baseConfidence: Float)

    private val votes = ArrayDeque<Vote>()
    private var stablePosition = SleepPosition.UNKNOWN

    // Memoria de la cara, para distinguir "cara tapada" de "boca abajo".
    private var faceEverSeen = false
    private var sideSeenSinceFace = false
    private var noBodySinceMs: Long? = null
    private var riskSinceMs: Long? = null

    fun reset() {
        votes.clear()
        stablePosition = SleepPosition.UNKNOWN
        faceEverSeen = false
        sideSeenSinceFace = false
        noBodySinceMs = null
        riskSinceMs = null
    }

    fun update(obs: FrameObservation): ClassifierOutput {
        val (label, baseConfidence) = classifyRaw(obs)

        votes.addLast(Vote(obs.timestampMs, label, baseConfidence))
        while (votes.isNotEmpty() && obs.timestampMs - votes.first().timestampMs > WINDOW_MS) {
            votes.removeFirst()
        }

        if (votes.size >= MIN_VOTES) {
            var winner = SleepPosition.UNKNOWN
            var winnerCount = 0
            for (candidate in SleepPosition.entries) {
                val count = votes.count { it.label == candidate }
                if (count > winnerCount) {
                    winner = candidate
                    winnerCount = count
                }
            }
            val share = winnerCount.toFloat() / votes.size
            // Histéresis: solo cambia la etiqueta estable si hay mayoría clara.
            if (share >= MIN_SHARE) stablePosition = winner
        }

        val supporting = votes.filter { it.label == stablePosition }
        val share = if (votes.isEmpty()) 0f else supporting.size.toFloat() / votes.size
        val baseAverage = if (supporting.isEmpty()) 0f else supporting.map { it.baseConfidence }.average().toFloat()
        val confidence = if (stablePosition == SleepPosition.UNKNOWN) 0f else minOf(baseAverage * share, MAX_CONFIDENCE)

        // Alerta: postura de atención sostenida, no un frame suelto.
        if (stablePosition.isRisk) {
            if (riskSinceMs == null) riskSinceMs = obs.timestampMs
        } else {
            riskSinceMs = null
        }
        val since = riskSinceMs
        val isAlert = since != null && obs.timestampMs - since >= ALERT_HOLD_MS

        return ClassifierOutput(stablePosition, confidence, isAlert)
    }

    /** Clasifica un solo frame. Actualiza la memoria de la cara. */
    private fun classifyRaw(obs: FrameObservation): Pair<SleepPosition, Float> {
        if (!obs.bodyDetected && !obs.faceDetected) {
            val since = noBodySinceMs ?: obs.timestampMs
            noBodySinceMs = since
            // Bebé fuera de cuadro un rato: olvidar el contexto anterior.
            if (obs.timestampMs - since > FORGET_AFTER_MS) {
                faceEverSeen = false
                sideSeenSinceFace = false
            }
            return SleepPosition.UNKNOWN to 0f
        }
        noBodySinceMs = null

        val ratio = obs.shoulderRatio
        val lateralBody = obs.shouldersReliable && ratio != null && ratio < SIDE_RATIO_THRESHOLD

        val chest = obs.chestFacing

        if (obs.faceDetected) {
            faceEverSeen = true
            sideSeenSinceFace = false
            val turned = abs(obs.faceYawDegrees ?: 0f) >= SIDE_YAW_THRESHOLD_DEG
            return when {
                lateralBody -> SleepPosition.SIDE to 0.55f
                // Cabeza girada: con la espalda hacia la cámara es boca abajo
                // (cara girada); con el pecho hacia la cámara, solo cabeza girada.
                turned && chest == false -> SleepPosition.PRONE to 0.45f
                turned -> SleepPosition.SIDE to 0.45f
                // Cara de frente pero "espalda": contradictorio (un bebé boca abajo
                // no mira de frente a la cámara); se confía en la cara, con menos confianza.
                chest == false -> SleepPosition.SUPINE to 0.5f
                else -> SleepPosition.SUPINE to 0.65f
            }
        }

        // Sin cara detectada.
        if (!obs.shouldersReliable) return SleepPosition.UNKNOWN to 0f
        if (lateralBody) {
            sideSeenSinceFace = true
            return SleepPosition.SIDE to 0.45f
        }
        return when (chest) {
            // Espalda hacia la cámara y sin cara: boca abajo.
            false -> SleepPosition.PRONE to 0.5f
            // Pecho hacia la cámara pero la cara no aparece: si antes se veía,
            // se tapó; si nunca se vio, lo más probable es que el detector no
            // la encuentre (cara pequeña/girada), no que esté tapada.
            true -> if (faceEverSeen && !sideSeenSinceFace) {
                SleepPosition.FACE_COVERED to 0.45f
            } else {
                SleepPosition.SUPINE to 0.35f
            }
            // Sin pista de pecho/espalda: solo la memoria de la cara.
            null -> when {
                faceEverSeen && !sideSeenSinceFace -> SleepPosition.FACE_COVERED to 0.4f
                faceEverSeen -> SleepPosition.PRONE to 0.45f
                else -> SleepPosition.PRONE to 0.35f
            }
        }
    }

    companion object {
        /** Debajo de esta razón hombros/torso se considera cuerpo lateral. Empírico: ajustar con pruebas reales. */
        const val SIDE_RATIO_THRESHOLD = 0.45f

        /** Giro de cabeza (grados) a partir del cual se considera "volteado". Empírico. */
        const val SIDE_YAW_THRESHOLD_DEG = 35f

        /** Ventana de votación (ms). */
        const val WINDOW_MS = 2000L

        /** Mínimo de frames en la ventana para decidir. */
        const val MIN_VOTES = 3

        /** Proporción mínima de votos para cambiar la etiqueta estable. */
        const val MIN_SHARE = 0.6f

        /** Tiempo sostenido (ms) de una postura de atención antes de activar la alerta. */
        const val ALERT_HOLD_MS = 3000L

        /** Sin cuerpo durante este tiempo (ms) se descarta la memoria de la cara. */
        const val FORGET_AFTER_MS = 5000L

        /** Tope de confianza: nunca se transmite certeza. */
        const val MAX_CONFIDENCE = 0.7f
    }
}
