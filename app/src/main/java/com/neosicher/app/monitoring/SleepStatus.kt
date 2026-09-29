package com.neosicher.app.monitoring

/**
 * Estado de sueño del bebé.
 *
 * En el MVP no existe una fuente real de detección de sueño: el estado por defecto
 * es UNKNOWN y la duración es null. No se simula.
 */
enum class SleepPhase {
    UNKNOWN,
    AWAKE,
    ASLEEP,
}

/**
 * @param phase fase de sueño (por defecto desconocida).
 * @param durationMillis duración acumulada de sueño en ms, o null si no hay dato.
 */
data class SleepStatus(
    val phase: SleepPhase = SleepPhase.UNKNOWN,
    val durationMillis: Long? = null,
) {
    companion object {
        val UNKNOWN = SleepStatus()
    }
}
