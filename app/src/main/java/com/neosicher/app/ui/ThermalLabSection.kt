package com.neosicher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neosicher.app.thermal.AgcVerdict
import com.neosicher.app.thermal.ThermalCalibration
import com.neosicher.app.thermal.ThermalCameraState
import com.neosicher.app.thermal.ThermalConnectionStatus
import com.neosicher.app.thermal.ThermalPointReading
import com.neosicher.app.thermal.ThermalSpot
import com.neosicher.app.ui.components.ThermalImageView
import com.neosicher.app.ui.theme.NeoColors
import com.neosicher.app.ui.theme.NeoDimens

/**
 * Laboratorio térmico en vivo: se muestra al inicio de la pantalla de
 * calibración (pantalla independiente; el dashboard no se modifica).
 *
 * Permite:
 *  - ver la imagen térmica y TOCAR un punto para medirlo,
 *  - leer el valor raw (y los °C aproximados si hay calibración) del punto
 *    elegido, del más caliente, del más frío y del centro,
 *  - saber si la cámara estira el contraste en cada frame (AGC), que decide si
 *    una calibración raw->°C puede funcionar,
 *  - inspeccionar cómo está armado el frame crudo y guardarlo tal cual.
 *
 * REGLA: nada aquí inventa temperatura; los °C salen solo de la calibración
 * del usuario y siempre son EXPERIMENTALES.
 */
@Composable
fun ThermalLabSection(
    thermalState: ThermalCameraState,
    calibration: ThermalCalibration,
    spot: ThermalSpot?,
    onSpotChange: (ThermalSpot?) -> Unit,
    onAnalyzeFrame: () -> List<String>,
    onSaveRawFrame: () -> Unit,
    isRecording: Boolean,
    onToggleRecording: () -> Unit,
    labMessage: String?,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var analysisLines by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NeoDimens.CardCorner))
            .background(NeoColors.Surface)
            .border(NeoDimens.BorderWidth, NeoColors.Border, RoundedCornerShape(NeoDimens.CardCorner))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Laboratorio térmico (en vivo)",
            color = NeoColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
        )

        val frame = thermalState.lastFrame
        if (thermalState.status != ThermalConnectionStatus.STREAMING || frame == null) {
            Text(
                text = "Sin imagen térmica. Estado: ${thermalState.message}" +
                    (thermalState.errorDetail?.let { "\n$it" } ?: "") +
                    "\n\nVuelve al panel principal, cambia a \"cámara termográfica\" y espera a que " +
                    "aparezca la imagen; luego regresa aquí.",
                color = NeoColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        val measurements = frame.measurements

        ThermalImageView(
            frame = frame,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            onSpotTap = { onSpotChange(it) },
        )
        Text(
            text = "Toca la imagen para elegir el punto a medir. Ese punto es el que usan \"Tomar lectura\" " +
                "y la tarjeta Temperatura del panel principal (si no eliges ninguno, se usa el centro).",
            color = NeoColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )

        if (measurements == null) {
            Text(
                text = "Midiendo… (este formato no entrega luminancia por píxel).",
                color = NeoColors.TextTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                text = "Stream: %.1f fps · %d frames · %s %dx%d".format(
                    measurements.fps, measurements.frameCount, frame.fourCc ?: "?",
                    frame.rawWidth, frame.rawHeight,
                ),
                color = NeoColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )

            measurements.spot?.let { ReadoutRow("Punto elegido", it, Color(0xFFFFEB3B)) }
            ReadoutRow("Más caliente", measurements.hot, Color(0xFFFF5252))
            ReadoutRow("Más frío", measurements.cold, Color(0xFF40C4FF))
            ReadoutRow("Centro", measurements.center, Color.White)

            val stats = measurements.stats
            Text(
                text = "Frame: raw ${stats.min}–${stats.max} · media %.1f · mediana %d · %d niveles distintos".format(
                    stats.mean, stats.median, stats.distinctLevels,
                ),
                color = NeoColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )

            AgcBanner(measurements.agc)
            FitBanner(calibration, stats.distinctLevels)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LabButton("Quitar punto", emphasized = false, enabled = spot != null, modifier = Modifier.weight(1f)) {
                onSpotChange(null)
            }
            LabButton("Analizar frame", emphasized = false, modifier = Modifier.weight(1f)) {
                analysisLines = onAnalyzeFrame()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LabButton("Guardar frame RAW", modifier = Modifier.weight(1f)) { onSaveRawFrame() }
            LabButton(
                label = if (isRecording) "Detener y guardar CSV" else "Grabar mediciones CSV",
                emphasized = !isRecording,
                modifier = Modifier.weight(1f),
            ) { onToggleRecording() }
        }

        if (analysisLines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0B1220))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                analysisLines.forEach { line ->
                    Text(
                        text = line,
                        color = NeoColors.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        labMessage?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(NeoColors.SurfaceVariant)
                    .clickable(onClick = onDismissMessage)
                    .padding(10.dp),
            ) {
                Text(
                    text = "$message  (toca para cerrar)",
                    color = NeoColors.Success,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ReadoutRow(label: String, reading: ThermalPointReading, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label (${reading.x},${reading.y})",
            color = accent,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "raw %.1f".format(reading.raw) +
                (reading.celsius?.let { "  →  %.1f °C%s".format(it, if (reading.extrapolated) "*" else "") } ?: ""),
            color = NeoColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AgcBanner(verdict: AgcVerdict) {
    val (text, color) = when (verdict) {
        AgcVerdict.LIKELY_STRETCHED_EVERY_FRAME ->
            "⚠ Cada frame ocupa casi todo el rango 0–255: la cámara parece reescalar el contraste en CADA frame " +
                "(AGC). Si es así, una calibración fija raw→°C no es válida: el mismo objeto cambia de valor según " +
                "lo que haya en la escena. Compruébalo: apunta a una pared y acerca una mano; si la pared cambia " +
                "de valor, hay AGC." to NeoColors.Warning
        AgcVerdict.RANGE_VARIES ->
            "El mínimo/máximo cambia entre frames: la cámara no fuerza siempre 0–255. Es compatible con una " +
                "escala más estable (no lo prueba): la calibración tiene opción de funcionar." to NeoColors.Success
        AgcVerdict.UNKNOWN ->
            "Evaluando si la cámara reescala el contraste (necesita unos segundos de stream)…" to NeoColors.TextTertiary
    }
    Text(text = text, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FitBanner(calibration: ThermalCalibration, distinctLevels: Int) {
    val fit = calibration.fitInfo()
    if (fit == null) {
        Text(
            text = "Sin calibración suficiente: solo se muestra raw. Se necesitan 2 puntos con raw distinto " +
                "(más abajo).",
            color = NeoColors.TextTertiary,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val residualNote = when {
        fit.pointCount < 3 -> " · con 2 puntos el error es 0 por construcción (no prueba que sea buena)"
        fit.maxResidualCelsius > 1.0 -> " · ⚠ los puntos no son coherentes con una recta"
        else -> ""
    }
    val template = "Calibración: %.3f °C por nivel de raw · error máx. %.2f °C · %d puntos%s. " +
        "Con %d niveles distintos en el frame, la resolución real es de unos %.1f °C. " +
        "(* = fuera del rango calibrado)"
    Text(
        text = template.format(
            fit.slopeCelsiusPerLevel, fit.maxResidualCelsius, fit.pointCount, residualNote,
            distinctLevels, kotlin.math.abs(fit.slopeCelsiusPerLevel),
        ),
        color = NeoColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LabButton(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NeoDimens.PillCorner))
            .background(
                when {
                    !enabled -> NeoColors.SurfaceVariant
                    emphasized -> NeoColors.Accent
                    else -> NeoColors.SurfaceHigh
                }
            )
            .border(NeoDimens.BorderWidth, NeoColors.Border, RoundedCornerShape(NeoDimens.PillCorner))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> NeoColors.TextTertiary
                emphasized -> NeoColors.Background
                else -> NeoColors.TextPrimary
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
