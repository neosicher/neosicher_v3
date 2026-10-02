package com.neosicher.app.thermal

/**
 * Calibración empírica del GW192A: convierte luminancia cruda (raw 0..255,
 * ya procesada internamente por la cámara — ver docs/GW192A_INVESTIGACION.md
 * §13.5/13.6, el sensor NO expone datos radiométricos) a una temperatura
 * **aproximada**, mediante una recta ajustada a puntos de referencia que el
 * usuario mide con OTRO sensor de confianza.
 *
 * Esto NO es una calibración de fábrica ni una conversión física exacta: es
 * una aproximación empírica de 2–3 puntos (regresión lineal), igual al método
 * de "corrección de dos puntos" usado en calibración de sensores no
 * radiométricos (ver literatura pública de calibración de cámaras térmicas
 * no enfriadas). El resultado SIEMPRE debe presentarse como aproximado, nunca
 * como medición médica certificada (ver docs/GW192A_INVESTIGACION.md §6, §14).
 *
 * Un [ThermalCalibrationPoint] de "temperatura corporal" puede partir de un
 * supuesto inicial (p. ej. 36.0 °C, promedio de piel humana) mientras no se
 * haya medido con el sensor de referencia; una vez el usuario mide con su
 * propio equipo, debe sustituirse por el valor real.
 */

/** Identifica cuál de los 3 puntos de calibración se está editando. */
enum class CalibrationPointKind { COLD, HOT, BODY }

/** Un punto de calibración: luminancia cruda observada vs. temperatura real conocida. */
data class ThermalCalibrationPoint(
    val label: String,
    val rawValue: Double,
    val knownTemperatureCelsius: Double,
    /** true si [knownTemperatureCelsius] es un supuesto, no una medición real con otro sensor. */
    val isAssumed: Boolean = false,
)

/**
 * Calibración completa (hasta 3 puntos: frío, caliente, corporal).
 *
 * Con 2+ puntos distintos se ajusta una recta `temp = slope*raw + intercept`
 * por mínimos cuadrados (si hay 3) o por los 2 puntos disponibles. Con 0 o 1
 * punto no hay suficiente información para calibrar: no se inventa una recta.
 */
data class ThermalCalibration(
    val coldPoint: ThermalCalibrationPoint? = null,
    val hotPoint: ThermalCalibrationPoint? = null,
    val bodyPoint: ThermalCalibrationPoint? = null,
) {
    private val points: List<ThermalCalibrationPoint>
        get() = listOfNotNull(coldPoint, hotPoint, bodyPoint)

    /** true si hay al menos 2 puntos con valores de raw distintos (necesario para una recta). */
    val isCalibrated: Boolean
        get() {
            val distinctRaw = points.map { it.rawValue }.distinct()
            return distinctRaw.size >= 2
        }

    /** true si al menos uno de los puntos usados sigue siendo un supuesto (no medido). */
    val hasAssumedPoint: Boolean
        get() = points.any { it.isAssumed }

    /**
     * Ajusta `temp = slope*raw + intercept` por mínimos cuadrados sobre los
     * puntos disponibles. Devuelve null si no hay suficientes puntos
     * distintos para determinar una recta (se niega a inventar una).
     */
    private fun fitLine(): Pair<Double, Double>? {
        val pts = points
        if (pts.map { it.rawValue }.distinct().size < 2) return null

        val n = pts.size
        val sumX = pts.sumOf { it.rawValue }
        val sumY = pts.sumOf { it.knownTemperatureCelsius }
        val sumXY = pts.sumOf { it.rawValue * it.knownTemperatureCelsius }
        val sumXX = pts.sumOf { it.rawValue * it.rawValue }

        val denom = n * sumXX - sumX * sumX
        if (denom == 0.0) return null

        val slope = (n * sumXY - sumX * sumY) / denom
        val intercept = (sumY - slope * sumX) / n
        return slope to intercept
    }

    /**
     * Convierte una luminancia cruda a temperatura aproximada en °C usando la
     * recta ajustada. Devuelve null si no hay calibración suficiente — en ese
     * caso el llamador NO debe mostrar ningún valor (nunca un número inventado).
     */
    fun toCelsius(rawValue: Double): Double? {
        val (slope, intercept) = fitLine() ?: return null
        return slope * rawValue + intercept
    }

    companion object {
        /** Supuesto inicial razonable para el punto corporal, editable por el usuario. */
        const val DEFAULT_BODY_TEMPERATURE_C = 36.0

        val EMPTY = ThermalCalibration()
    }
}
