package com.neosicher.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neosicher.app.monitoring.Metric
import com.neosicher.app.monitoring.MetricAvailability
import com.neosicher.app.monitoring.SleepStatus
import com.neosicher.app.monitoring.ThermalReading
import com.neosicher.app.monitoring.ThermalReadingStatus
import com.neosicher.app.ui.theme.NeoColors
import com.neosicher.app.ui.theme.NeoDimens

/**
 * Tarjeta genérica de métrica. Componente puro: solo pinta lo que recibe.
 *
 * @param value texto ya formateado del valor principal (p. ej. "--", "36.5").
 * @param statusLabel etiqueta de estado bajo el valor (p. ej. "Sin lectura").
 */
@Composable
fun MonitoringCard(
    title: String,
    icon: ImageVector,
    value: String,
    unit: String,
    accent: Color,
    statusLabel: String,
    modifier: Modifier = Modifier,
) {
    // Layout horizontal como en la referencia: icono grande a la izquierda,
    // bloque de texto (título arriba, valor grande debajo) a la derecha.
    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(NeoDimens.CardCorner))
            .background(
                Brush.verticalGradient(listOf(NeoColors.SurfaceHigh, NeoColors.Surface))
            )
            .border(
                NeoDimens.BorderWidth,
                NeoColors.Border,
                androidx.compose.foundation.shape.RoundedCornerShape(NeoDimens.CardCorner),
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(34.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = NeoColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = NeoColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.displaySmall,
                )
                if (unit.isNotEmpty() && value != "--") {
                    Spacer(Modifier.size(5.dp))
                    Text(
                        text = unit,
                        color = NeoColors.TextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
            // La etiqueta de estado sólo se muestra cuando NO hay valor real,
            // para dejar clara la ausencia de lectura sin ensuciar el diseño.
            if (value == "--") {
                Text(
                    text = statusLabel,
                    color = NeoColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// -- Variantes específicas --------------------------------------------------
// Todas traducen el estado del modelo a texto honesto: sin fuente => "--".

@Composable
fun TemperatureCard(reading: ThermalReading, modifier: Modifier = Modifier) {
    val hasValue = reading.temperatureCelsius != null &&
        reading.status != ThermalReadingStatus.NO_READING
    MonitoringCard(
        title = "Temperatura",
        icon = Icons.Filled.DeviceThermostat,
        value = if (hasValue) "%.1f".format(reading.temperatureCelsius) else "--",
        unit = "°C",
        accent = NeoColors.Warning,
        statusLabel = when (reading.status) {
            ThermalReadingStatus.NO_READING -> "Sin lectura"
            ThermalReadingStatus.EXPERIMENTAL -> "Lectura térmica experimental"
            ThermalReadingStatus.VALIDATED -> "Lectura validada"
        },
        modifier = modifier,
    )
}

@Composable
fun HeartRateCard(metric: Metric, modifier: Modifier = Modifier) {
    MonitoringCard(
        title = "Frecuencia cardiaca",
        icon = Icons.Filled.Favorite,
        value = metricValueText(metric),
        unit = metric.unit,
        accent = NeoColors.Error,
        statusLabel = metricStatusText(metric),
        modifier = modifier,
    )
}

@Composable
fun RespiratoryRateCard(metric: Metric, modifier: Modifier = Modifier) {
    MonitoringCard(
        title = "Frecuencia respiratoria",
        icon = Icons.Filled.Air,
        value = metricValueText(metric),
        unit = metric.unit,
        accent = NeoColors.Accent,
        statusLabel = metricStatusText(metric),
        modifier = modifier,
    )
}

@Composable
fun SleepCard(status: SleepStatus, modifier: Modifier = Modifier) {
    val text = status.durationMillis?.let { ms ->
        val totalMin = ms / 60000
        "%dh %02dm".format(totalMin / 60, totalMin % 60)
    } ?: "--"
    MonitoringCard(
        title = "Tiempo de sueño",
        icon = Icons.Filled.Bedtime,
        value = text,
        unit = "",
        accent = NeoColors.AccentBright,
        statusLabel = if (status.durationMillis == null) "No disponible" else "Registrado",
        modifier = modifier,
    )
}

private fun metricValueText(metric: Metric): String = when (metric.availability) {
    MetricAvailability.AVAILABLE, MetricAvailability.EXPERIMENTAL ->
        metric.value?.let { "%.0f".format(it) } ?: "--"
    else -> "--"
}

private fun metricStatusText(metric: Metric): String = when (metric.availability) {
    MetricAvailability.NO_SOURCE -> "No disponible"
    MetricAvailability.NO_READING -> "Sin lectura"
    MetricAvailability.EXPERIMENTAL -> "Experimental"
    MetricAvailability.AVAILABLE -> "En vivo"
}
