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
    twoColumns: Boolean,
    modifier: Modifier = Modifier,
) {
    if (twoColumns) {
        Column(
            modifier = modifier.fillMaxWidth(),
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
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NeoDimens.CardGap),
        ) {
            TemperatureCard(thermalReading, Modifier.fillMaxWidth())
            HeartRateCard(vitalSigns.heartRate, Modifier.fillMaxWidth())
            RespiratoryRateCard(vitalSigns.respiratoryRate, Modifier.fillMaxWidth())
            SleepCard(sleepStatus, Modifier.fillMaxWidth())
        }
    }
}
