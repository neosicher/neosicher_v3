package com.neosicher.app.thermal

import kotlin.math.min

/**
 * Mediciones por puntos sobre un frame térmico (Kotlin puro, sin Android).
 *
 * Cada punto trae su valor RAW (luminancia 0..255 promediada localmente) y,
 * SOLO si hay calibración suficiente del usuario (ver [ThermalCalibration]),
 * una temperatura aproximada en °C. Sin calibración [ThermalPointReading.celsius]
 * es null: nunca se inventa un número.
 */

/** Punto elegido por el usuario, en coordenadas normalizadas [0,1] de la imagen. */
data class ThermalSpot(val u: Float, val v: Float)

data class ThermalPointReading(
    val x: Int,
    val y: Int,
    val raw: Double,
    /** Aproximación EXPERIMENTAL en °C, o null sin calibración suficiente. */
    val celsius: Double?,
    /** true si el raw queda fuera del rango cubierto por los puntos de calibración. */
    val extrapolated: Boolean,
) {
    /** Texto corto para dibujar junto al marcador: °C si hay, si no el raw. */
    fun label(): String {
        val base = if (celsius != null) "%.1f°C".format(celsius) else "raw %.0f".format(raw)
        return if (extrapolated) "$base*" else base
    }
}

data class ThermalMeasurements(
    val width: Int,
    val height: Int,
    val hot: ThermalPointReading,
    val cold: ThermalPointReading,
    val center: ThermalPointReading,
    val spot: ThermalPointReading?,
    val stats: LumaStats,
    val calibrated: Boolean,
    val agc: AgcVerdict,
    /** Fotogramas por segundo medidos en el stream real. */
    val fps: Double = 0.0,
    /** Frames recibidos desde que se abrió el stream. */
    val frameCount: Long = 0,
) {
    /** Punto de referencia para la tarjeta de Temperatura: el elegido, o el centro. */
    val reference: ThermalPointReading
        get() = spot ?: center
}

object ThermalMeasurementsBuilder {

    private const val SPOT_RADIUS = 2

    /**
     * Calcula las mediciones de un frame de luminancia [luma] ([width]x[height]).
     * @return null si el frame no es utilizable.
     */
    fun build(
        luma: IntArray,
        width: Int,
        height: Int,
        calibration: ThermalCalibration,
        spot: ThermalSpot?,
        agc: AgcVerdict,
        fps: Double = 0.0,
        frameCount: Long = 0,
    ): ThermalMeasurements? {
        if (width <= 0 || height <= 0 || luma.size < width * height) return null
        val stats = ThermalAnalysis.lumaStats(luma.copyOf(width * height)) ?: return null
        val (hotPixel, coldPixel) = ThermalAnalysis.findExtremes(luma, width, height) ?: return null

        fun reading(x: Int, y: Int, raw: Double) = ThermalPointReading(
            x = x,
            y = y,
            raw = raw,
            celsius = calibration.toCelsius(raw),
            extrapolated = calibration.isCalibrated && calibration.isExtrapolated(raw),
        )

        val cx = width / 2
        val cy = height / 2
        val center = reading(cx, cy, ThermalAnalysis.localMean(luma, width, height, cx, cy, SPOT_RADIUS))

        val spotReading = spot?.let { s ->
            val sx = (s.u * width).toInt().coerceIn(0, width - 1)
            val sy = (s.v * height).toInt().coerceIn(0, height - 1)
            reading(sx, sy, ThermalAnalysis.localMean(luma, width, height, sx, sy, SPOT_RADIUS))
        }

        return ThermalMeasurements(
            width = width,
            height = height,
            hot = reading(hotPixel.x, hotPixel.y, hotPixel.value),
            cold = reading(coldPixel.x, coldPixel.y, coldPixel.value),
            center = center,
            spot = spotReading,
            stats = stats,
            calibrated = calibration.isCalibrated,
            agc = agc,
            fps = fps,
            frameCount = frameCount,
        )
    }
}

/** Geometría de una imagen mostrada con "Fit" (centrada, sin recortar) dentro de una vista. */
object ThermalViewGeometry {

    data class Fit(val left: Float, val top: Float, val width: Float, val height: Float) {
        /** Coordenadas de vista -> normalizadas de la imagen; null si cae fuera de ella. */
        fun toNormalized(px: Float, py: Float): Pair<Float, Float>? {
            if (width <= 0f || height <= 0f) return null
            if (px < left || px > left + width || py < top || py > top + height) return null
            return ((px - left) / width) to ((py - top) / height)
        }

        /** Normalizadas de la imagen -> coordenadas de vista. */
        fun toView(u: Float, v: Float): Pair<Float, Float> =
            (left + u * width) to (top + v * height)
    }

    fun fit(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): Fit? {
        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) return null
        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val w = imageWidth * scale
        val h = imageHeight * scale
        return Fit((viewWidth - w) / 2f, (viewHeight - h) / 2f, w, h)
    }
}
