package com.neosicher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.neosicher.app.thermal.CalibrationPointKind
import com.neosicher.app.thermal.ThermalCalibration
import com.neosicher.app.ui.theme.NeoColors
import com.neosicher.app.ui.theme.NeoDimens
import com.neosicher.app.ui.theme.NeoGradients

/**
 * Pantalla INDEPENDIENTE de calibración manual del sensor térmico.
 *
 * No forma parte del dashboard principal (NeosicherScreen) y no modifica su
 * diseño: solo escribe en [ThermalCalibration], que el ViewModel usa para
 * derivar — aparte, de forma invisible — el valor que se muestra en la
 * tarjeta "Temperatura" del dashboard.
 *
 * Flujo: el usuario mide, con OTRO sensor de referencia, la temperatura real
 * de hasta 3 puntos:
 *   1. Un punto FRÍO conocido (p. ej. agua fría, un objeto a temperatura ambiente).
 *   2. Un punto CALIENTE conocido (p. ej. agua caliente, una superficie tibia).
 *   3. Un punto CORPORAL (parte del cuerpo). Arranca con un supuesto editable
 *      de 36.0 °C (promedio de piel humana) mientras no se mida con el sensor
 *      de referencia — debe sustituirse por una medición real en cuanto se
 *      pueda, para que la calibración sea confiable.
 *
 * Con 2+ puntos de luminancia distinta, se ajusta una recta real (no
 * inventada) — ver [ThermalCalibration.toCelsius]. El resultado siempre se
 * presenta como aproximado/experimental, nunca como medición médica.
 */
@Composable
fun ThermalCalibrationScreen(
    calibration: ThermalCalibration,
    latestRawValueProvider: () -> Double?,
    onSetColdPoint: (raw: Double, knownC: Double) -> Unit,
    onSetHotPoint: (raw: Double, knownC: Double) -> Unit,
    onSetBodyPoint: (raw: Double, knownC: Double, isAssumed: Boolean) -> Unit,
    onClearPoint: (CalibrationPointKind) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NeoGradients.ScreenBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(NeoDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(NeoDimens.PanelGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = NeoColors.TextPrimary,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onBack),
                )
                Text(
                    text = "Calibración del sensor térmico",
                    color = NeoColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Text(
                text = "El GW192A no entrega temperatura radiométrica real (ver investigación " +
                    "del proyecto). Esta pantalla permite una aproximación empírica de 2-3 " +
                    "puntos, midiendo con OTRO sensor de confianza. El resultado siempre es " +
                    "experimental, nunca un diagnóstico médico.",
                color = NeoColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )

            CalibrationStatusCard(calibration)

            CalibrationPointEditor(
                title = "1. Punto frío conocido",
                hint = "Mide con tu otro sensor un punto frío (p. ej. agua fría) y apunta " +
                    "el GW192A ahí. Luego pulsa \"Tomar lectura\" y escribe la temperatura real.",
                existing = calibration.coldPoint,
                latestRawValueProvider = latestRawValueProvider,
                defaultKnownC = "10.0",
                onSetPoint = { raw, known -> onSetColdPoint(raw, known) },
                onClear = { onClearPoint(CalibrationPointKind.COLD) },
            )

            CalibrationPointEditor(
                title = "2. Punto caliente conocido",
                hint = "Mide con tu otro sensor un punto caliente (p. ej. agua tibia/caliente, " +
                    "sin quemarte) y apunta el GW192A ahí.",
                existing = calibration.hotPoint,
                latestRawValueProvider = latestRawValueProvider,
                defaultKnownC = "45.0",
                onSetPoint = { raw, known -> onSetHotPoint(raw, known) },
                onClear = { onClearPoint(CalibrationPointKind.HOT) },
            )

            CalibrationPointEditor(
                title = "3. Punto corporal",
                hint = "Apunta el GW192A a una parte del cuerpo (p. ej. frente). Por defecto " +
                    "se asume 36.0 °C (promedio de piel humana) hasta que midas el valor real " +
                    "con tu otro sensor — sustitúyelo en cuanto puedas para mayor precisión.",
                existing = calibration.bodyPoint,
                latestRawValueProvider = latestRawValueProvider,
                defaultKnownC = ThermalCalibration.DEFAULT_BODY_TEMPERATURE_C.toString(),
                allowAssumed = true,
                onSetPoint = { raw, known -> onSetBodyPoint(raw, known, false) },
                onSetAssumedPoint = { raw, known -> onSetBodyPoint(raw, known, true) },
                onClear = { onClearPoint(CalibrationPointKind.BODY) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CalibrationStatusCard(calibration: ThermalCalibration) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NeoDimens.CardCorner))
            .background(NeoColors.Surface)
            .border(NeoDimens.BorderWidth, NeoColors.Border, RoundedCornerShape(NeoDimens.CardCorner))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Thermostat, contentDescription = null, tint = NeoColors.Accent)
            Text(
                text = if (calibration.isCalibrated) "Calibración activa" else "Sin calibración suficiente",
                color = NeoColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = if (calibration.isCalibrated) {
                "La tarjeta \"Temperatura\" del panel principal ya muestra un valor " +
                    "aproximado derivado de esta calibración." +
                    if (calibration.hasAssumedPoint) {
                        " Incluye al menos un punto SUPUESTO (no medido con tu sensor de " +
                        "referencia) — la precisión mejorará cuando lo reemplaces por una medición real."
                    } else ""
            } else {
                "Se necesitan al menos 2 puntos con lecturas distintas para calcular una " +
                    "aproximación. Mientras tanto, la tarjeta \"Temperatura\" se mantiene en \"--\"."
            },
            color = NeoColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CalibrationPointEditor(
    title: String,
    hint: String,
    existing: com.neosicher.app.thermal.ThermalCalibrationPoint?,
    latestRawValueProvider: () -> Double?,
    defaultKnownC: String,
    allowAssumed: Boolean = false,
    onSetPoint: (raw: Double, knownC: Double) -> Unit,
    onSetAssumedPoint: ((raw: Double, knownC: Double) -> Unit)? = null,
    onClear: () -> Unit,
) {
    var capturedRaw by remember { mutableStateOf<Double?>(existing?.rawValue) }
    var knownCText by remember { mutableStateOf(existing?.knownTemperatureCelsius?.toString() ?: defaultKnownC) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NeoDimens.CardCorner))
            .background(NeoColors.SurfaceVariant)
            .border(NeoDimens.BorderWidth, NeoColors.Border, RoundedCornerShape(NeoDimens.CardCorner))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = NeoColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(hint, color = NeoColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)

        if (existing != null) {
            Text(
                text = "Guardado: raw=${"%.1f".format(existing.rawValue)} → " +
                    "${existing.knownTemperatureCelsius}°C" +
                    if (existing.isAssumed) " (supuesto, no medido)" else "",
                color = NeoColors.Success,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalibrationButton(
                label = "Tomar lectura",
                onClick = { capturedRaw = latestRawValueProvider() },
            )
            Text(
                text = capturedRaw?.let { "raw=${"%.1f".format(it)}" } ?: "Sin lectura capturada",
                color = if (capturedRaw != null) NeoColors.TextPrimary else NeoColors.TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        OutlinedTextField(
            value = knownCText,
            onValueChange = { knownCText = it },
            label = { Text("Temperatura real medida (°C)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CalibrationButton(
                label = "Guardar punto",
                modifier = Modifier.weight(1f),
                onClick = {
                    val raw = capturedRaw
                    val known = knownCText.toDoubleOrNull()
                    if (raw != null && known != null) {
                        onSetPoint(raw, known)
                    }
                },
            )
            if (allowAssumed && onSetAssumedPoint != null) {
                CalibrationButton(
                    label = "Usar como supuesto",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val raw = capturedRaw
                        val known = knownCText.toDoubleOrNull()
                        if (raw != null && known != null) {
                            onSetAssumedPoint(raw, known)
                        }
                    },
                )
            }
            CalibrationButton(
                label = "Borrar",
                emphasized = false,
                onClick = {
                    capturedRaw = null
                    onClear()
                },
            )
        }
    }
}

@Composable
private fun CalibrationButton(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NeoDimens.PillCorner))
            .background(if (emphasized) NeoColors.Accent else NeoColors.SurfaceHigh)
            .border(NeoDimens.BorderWidth, NeoColors.Border, RoundedCornerShape(NeoDimens.PillCorner))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (emphasized) NeoColors.Background else NeoColors.TextPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
