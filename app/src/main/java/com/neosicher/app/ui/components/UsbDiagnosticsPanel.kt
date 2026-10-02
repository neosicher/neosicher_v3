package com.neosicher.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neosicher.app.thermal.ThermalCameraState
import com.neosicher.app.usb.UsbDeviceInfo
import com.neosicher.app.ui.theme.NeoColors
import com.neosicher.app.ui.theme.NeoDimens

/**
 * Panel de diagnóstico USB (sección de desarrollo).
 *
 * Muestra SOLO información real leída del dispositivo: VID/PID, manufacturer,
 * product, interfaces y endpoints. Fundamental para la investigación del
 * protocolo del GW192A. No muestra ni deduce nada del protocolo en sí.
 */
@Composable
fun UsbDiagnosticsPanel(
    thermalState: ThermalCameraState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NeoDimens.CardCorner))
            .background(NeoColors.Surface)
            .border(NeoDimens.BorderWidth, NeoColors.Border, RoundedCornerShape(NeoDimens.CardCorner))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.BugReport,
                contentDescription = null,
                tint = NeoColors.Accent,
                modifier = Modifier.padding(2.dp),
            )
            Text(
                text = "Diagnóstico USB",
                color = NeoColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        DiagRow("Estado", thermalState.status.name)
        DiagRow("Mensaje", thermalState.message)
        thermalState.errorDetail?.let { DiagRow("Error", it) }

        val info = thermalState.deviceInfo
        if (info == null) {
            Text(
                text = "Sin dispositivo enumerado todavía. Conecta el GW192A por USB OTG " +
                    "y concede el permiso para leer interfaces y endpoints.",
                color = NeoColors.TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            DeviceDetails(info)
        }

        // Formatos/resoluciones UVC declarados (evidencia clave para saber qué
        // expone realmente el dispositivo y cómo iniciar el stream).
        thermalState.uvcInfo?.let { uvc ->
            SectionText("── UVC: VideoControl=${uvc.videoControlInterfaceFound} · " +
                "formatos=${uvc.streamingFormats.size} ──")
            if (uvc.streamingFormats.isEmpty()) {
                SectionText("  (el dispositivo no declaró formatos VS_FORMAT/VS_FRAME)")
            }
            uvc.streamingFormats.forEach { fmt ->
                SectionText("  Formato ${fmt.kind} idx=${fmt.formatIndex} " +
                    (fmt.guidHex?.let { "guid=$it " } ?: "") +
                    (fmt.bitsPerPixel?.let { "bpp=$it" } ?: ""))
                fmt.frames.forEach { fr ->
                    SectionText("    Frame idx=${fr.frameIndex} ${fr.widthPx}x${fr.heightPx} " +
                        "~${"%.0f".format(fr.approxFps)}fps")
                }
            }
        }
    }
}

@Composable
private fun DeviceDetails(info: UsbDeviceInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DiagRow("Nombre", info.deviceName)
        DiagRow("VID", info.vendorIdHex)
        DiagRow("PID", info.productIdHex)
        DiagRow("Coincide GW192A", if (info.matchesGw192a) "Sí" else "No")
        DiagRow("Class/Sub/Proto", "${info.deviceClass}/${info.deviceSubclass}/${info.deviceProtocol}")
        DiagRow("Manufacturer", info.manufacturerName ?: "—")
        DiagRow("Product", info.productName ?: "—")
        DiagRow("Serial", info.serialNumber ?: "—")

        info.configurations.forEach { cfg ->
            SectionText("Config #${cfg.id} · ${cfg.maxPowerMilliAmps}mA · selfPowered=${cfg.isSelfPowered}")
            cfg.interfaces.forEach { iface ->
                SectionText(
                    "  Interface #${iface.id} alt=${iface.alternateSetting} " +
                        "class=${iface.interfaceClass} sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol}",
                )
                iface.endpoints.forEach { ep ->
                    SectionText(
                        "    EP ${ep.addressHex} ${ep.direction} ${ep.type} " +
                            "max=${ep.maxPacketSize} int=${ep.interval}",
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            color = NeoColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = value,
            color = NeoColors.TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SectionText(text: String) {
    Text(
        text = text,
        color = NeoColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
    )
}
