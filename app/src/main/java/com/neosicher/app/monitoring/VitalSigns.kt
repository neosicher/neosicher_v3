package com.neosicher.app.monitoring

/**
 * Estado de disponibilidad de una métrica.
 *
 * IMPORTANTE: mientras no exista una fuente real y validada, las métricas deben
 * permanecer en NO_SOURCE / NO_READING. NO se presentan valores simulados como
 * mediciones reales (ver requisitos del MVP).
 */
enum class MetricAvailability {
    /** No existe todavía una fuente de datos para esta métrica. */
    NO_SOURCE,

    /** Existe una fuente, pero aún no ha entregado una lectura. */
    NO_READING,

    /** Lectura disponible pero marcada como experimental (p. ej. térmica sin calibrar). */
    EXPERIMENTAL,

    /** Lectura disponible y considerada válida. */
    AVAILABLE,
}

/**
 * Una métrica individual con su estado de disponibilidad.
 *
 * @param value valor numérico crudo, o null si no hay lectura.
 * @param unit unidad (p. ej. "bpm", "°C", "rpm"). Solo informativa.
 * @param availability estado de la métrica.
 */
data class Metric(
    val value: Double? = null,
    val unit: String = "",
    val availability: MetricAvailability = MetricAvailability.NO_SOURCE,
) {
    companion object {
        /** Métrica sin fuente: no hay hardware ni proveedor de datos aún. */
        fun noSource(unit: String = "") = Metric(null, unit, MetricAvailability.NO_SOURCE)
    }
}

/**
 * Signos vitales del bebé monitorizado.
 *
 * En el MVP TODAS permanecen sin fuente real: no hay algoritmo ni sensor validado
 * que las produzca. Existen como estructura para conectar fuentes reales más adelante.
 */
data class VitalSigns(
    val heartRate: Metric = Metric.noSource("bpm"),
    val respiratoryRate: Metric = Metric.noSource("rpm"),
) {
    companion object {
        val EMPTY = VitalSigns()
    }
}
