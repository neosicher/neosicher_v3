package com.neosicher.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.neosicher.app.monitoring.SleepStatus
import com.neosicher.app.monitoring.ThermalReading
import com.neosicher.app.monitoring.VitalSigns
import com.neosicher.app.ui.theme.NeoDimens

/**
 * Panel lateral (o inferior) de monitoreo: agrupa las cuatro tarjetas de métricas.
 * Componente puro. Se adapta a una o dos columnas según [twoColumns].
 */
@Composable
fun MonitoringPanel(
    thermalReading: ThermalReading,
    vitalSigns: VitalSigns,
    sleepStatus: SleepStatus,
    @Suppress("UNUSED_PARAMETER") twoColumns: Boolean,
    modifier: Modifier = Modifier,
) {
    // Siempre en cuadrícula 2x2: en horizontal (panel lateral estrecho) esto
    // garantiza que las CUATRO tarjetas (Temperatura, Frecuencia cardiaca,
    // Frecuencia respiratoria, Tiempo de sueño) sean visibles sin depender del
    // scroll. Se mantiene un scroll vertical como red de seguridad en pantallas
    // de muy poca altura. [twoColumns] se conserva por compatibilidad de firma.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NeoDimens.CardGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NeoDimens.CardGap)) {
            TemperatureCard(thermalReading, Modifier.weight(1f))
            HeartRateCard(vitalSigns.heartRate, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NeoDimens.CardGap)) {
            RespiratoryRateCard(vitalSigns.respiratoryRate, Modifier.weight(1f))
            SleepCard(sleepStatus, Modifier.weight(1f))
        }
    }
}
