package com.neosicher.app.monitoring

/**
 * Origen de una lectura térmica.
 */
enum class ThermalSource {
    NONE,
    GW192A,
}

/**
 * Estado de una lectura térmica concreta.
 */
enum class ThermalReadingStatus {
    /** No hay lectura disponible. */
    NO_READING,

    /**
     * Lectura experimental: proviene del GW192A pero su método y calibración
     * NO están validados. Nunca debe presentarse como diagnóstico médico.
     */
    EXPERIMENTAL,

    /** Lectura validada (reservado; no alcanzable en este MVP). */
    VALIDATED,
}

/**
 * Una lectura térmica.
 *
 * Campos mínimos según el modelo conceptual del MVP. NO se añaden campos para los
 * que todavía no hay evidencia de que el GW192A los pueda proporcionar
 * (ver docs/GW192A_INVESTIGACION.md, sección 8: preguntas sin resolver).
 *
 * @param temperatureCelsius temperatura en °C, o null si no hay lectura.
 * @param timestampMillis instante de la lectura (epoch ms), o null.
 * @param confidence confianza [0..1], o null si no aplica/desconocida.
 * @param source origen de la lectura.
 * @param status estado (por defecto: sin lectura).
 */
data class ThermalReading(
    val temperatureCelsius: Double? = null,
    val timestampMillis: Long? = null,
    val confidence: Double? = null,
    val source: ThermalSource = ThermalSource.NONE,
    val status: ThermalReadingStatus = ThermalReadingStatus.NO_READING,
) {
    companion object {
        /** Sin lectura: estado por defecto mientras no haya stream térmico real. */
        val NONE = ThermalReading()
    }
}
