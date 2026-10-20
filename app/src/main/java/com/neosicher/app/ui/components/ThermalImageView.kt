package com.neosicher.app.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.neosicher.app.thermal.ThermalFrameResult
import com.neosicher.app.thermal.ThermalPointReading
import com.neosicher.app.thermal.ThermalSpot
import com.neosicher.app.thermal.ThermalViewGeometry

/**
 * Imagen térmica con marcadores de puntos:
 *   ▲ rojo     = punto más caliente del frame
 *   ▼ azul     = punto más frío del frame
 *   ✚ blanco   = centro
 *   ✚ amarillo = punto elegido por el usuario (si lo hay)
 * Cada marcador muestra su valor: °C aproximados SOLO si hay calibración del
 * usuario (con "*" si queda fuera del rango calibrado); si no, el valor raw.
 *
 * Los marcadores salen de [ThermalFrameResult.measurements]; si todavía no
 * existen, se dibuja solo la imagen. Si [onSpotTap] no es null, un toque sobre
 * la imagen elige el punto a medir.
 *
 * REGLA (docs/GW192A_INVESTIGACION.md §14): el °C es una aproximación
 * EXPERIMENTAL del usuario, nunca una medición radiométrica ni médica.
 */
@Composable
fun ThermalImageView(
    frame: ThermalFrameResult,
    modifier: Modifier = Modifier,
    onSpotTap: ((ThermalSpot) -> Unit)? = null,
) {
    val bitmap = frame.thermalPaletteBitmap ?: frame.visibleBitmap
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val latestFrame by rememberUpdatedState(frame)
    val latestTap by rememberUpdatedState(onSpotTap)

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            isFakeBoldText = true
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    val tapModifier = if (onSpotTap != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val current = latestFrame
                val fit = ThermalViewGeometry.fit(
                    viewSize.width.toFloat(), viewSize.height.toFloat(),
                    current.rawWidth, current.rawHeight,
                ) ?: return@detectTapGestures
                val normalized = fit.toNormalized(offset.x, offset.y) ?: return@detectTapGestures
                latestTap?.invoke(ThermalSpot(normalized.first, normalized.second))
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewSize = it }
            .then(tapModifier),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Stream térmico experimental",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )

        val measurements = frame.measurements
        if (measurements != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val fit = ThermalViewGeometry.fit(
                    size.width, size.height, measurements.width, measurements.height,
                ) ?: return@Canvas

                drawMarker(fit, measurements.width, measurements.height, measurements.hot, MarkerKind.HOT, labelPaint)
                drawMarker(fit, measurements.width, measurements.height, measurements.cold, MarkerKind.COLD, labelPaint)
                drawMarker(fit, measurements.width, measurements.height, measurements.center, MarkerKind.CENTER, labelPaint)
                measurements.spot?.let {
                    drawMarker(fit, measurements.width, measurements.height, it, MarkerKind.SPOT, labelPaint)
                }
            }
        }
    }
}

private enum class MarkerKind(val color: Color, val prefix: String) {
    HOT(Color(0xFFFF5252), "Máx "),
    COLD(Color(0xFF40C4FF), "Mín "),
    CENTER(Color.White, "Centro "),
    SPOT(Color(0xFFFFEB3B), "Punto "),
}

private fun DrawScope.drawMarker(
    fit: ThermalViewGeometry.Fit,
    imageWidth: Int,
    imageHeight: Int,
    reading: ThermalPointReading,
    kind: MarkerKind,
    labelPaint: Paint,
) {
    // Centro del píxel dentro de la imagen -> coordenadas de la vista.
    val (cx, cy) = fit.toView((reading.x + 0.5f) / imageWidth, (reading.y + 0.5f) / imageHeight)
    val arm = 9.dp.toPx()
    val stroke = 2.dp.toPx()

    when (kind) {
        MarkerKind.HOT -> {
            // Triángulo hacia arriba.
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - arm)
                lineTo(cx - arm, cy + arm)
                lineTo(cx + arm, cy + arm)
                close()
            }
            drawPath(path, kind.color, style = Stroke(width = stroke))
        }
        MarkerKind.COLD -> {
            // Triángulo hacia abajo.
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy + arm)
                lineTo(cx - arm, cy - arm)
                lineTo(cx + arm, cy - arm)
                close()
            }
            drawPath(path, kind.color, style = Stroke(width = stroke))
        }
        MarkerKind.CENTER, MarkerKind.SPOT -> {
            drawLine(kind.color, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = stroke)
            drawLine(kind.color, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = stroke)
            if (kind == MarkerKind.SPOT) {
                drawCircle(kind.color, radius = arm * 1.5f, center = Offset(cx, cy), style = Stroke(width = stroke))
            }
        }
    }

    // Etiqueta con el valor, a la derecha del marcador (o a la izquierda si no cabe).
    labelPaint.textSize = 12.dp.toPx()
    labelPaint.color = kind.color.toArgb()
    val text = kind.prefix + reading.label()
    val textWidth = labelPaint.measureText(text)
    val gap = arm + 4.dp.toPx()
    val textX = if (cx + gap + textWidth > size.width) cx - gap - textWidth else cx + gap
    val textY = (cy + 4.dp.toPx()).coerceIn(labelPaint.textSize, size.height - 2.dp.toPx())
    drawContext.canvas.nativeCanvas.drawText(text, textX.coerceAtLeast(0f), textY, labelPaint)
}
