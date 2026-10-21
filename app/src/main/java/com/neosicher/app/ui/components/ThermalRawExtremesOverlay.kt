package com.neosicher.app.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.neosicher.app.thermal.RawExtremes
import com.neosicher.app.thermal.RawExtremesFinder
import com.neosicher.app.thermal.ThermalFrameResult

/**
 * Marca sobre la imagen térmica el píxel de MAYOR raw (▲ rojo) y el de MENOR raw
 * (▼ azul), cada uno con su valor. Se dibuja encima de la imagen, sin tocarla.
 *
 * El raw es luminancia relativa del frame (0..255), NO temperatura en °C
 * (docs/GW192A_INVESTIGACION.md §9, §14).
 *
 * La imagen se muestra con "Fit" (centrada, sin recortar), igual que el visor,
 * por eso aquí se calcula la misma caja para que los marcadores caigan sobre el
 * píxel correcto.
 */
@Composable
fun ThermalRawExtremesOverlay(
    frame: ThermalFrameResult,
    modifier: Modifier = Modifier,
) {
    val luma = frame.rawLuma ?: return
    val extremes = RawExtremesFinder.find(luma, frame.rawWidth, frame.rawHeight) ?: return

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawExtremes(extremes, frame.rawWidth, frame.rawHeight, labelPaint)
    }
}

private val HotColor = Color(0xFFFF5252)
private val ColdColor = Color(0xFF40C4FF)

private fun DrawScope.drawExtremes(
    extremes: RawExtremes,
    imageWidth: Int,
    imageHeight: Int,
    labelPaint: Paint,
) {
    // Misma caja que ContentScale.Fit: escala uniforme y centrada.
    val scale = minOf(size.width / imageWidth, size.height / imageHeight)
    val drawnWidth = imageWidth * scale
    val drawnHeight = imageHeight * scale
    val left = (size.width - drawnWidth) / 2f
    val top = (size.height - drawnHeight) / 2f

    drawOne(
        centerX = left + (extremes.maxX + 0.5f) * scale,
        centerY = top + (extremes.maxY + 0.5f) * scale,
        pointsUp = true,
        color = HotColor,
        text = "Máx ${extremes.maxValue}",
        labelPaint = labelPaint,
    )
    drawOne(
        centerX = left + (extremes.minX + 0.5f) * scale,
        centerY = top + (extremes.minY + 0.5f) * scale,
        pointsUp = false,
        color = ColdColor,
        text = "Mín ${extremes.minValue}",
        labelPaint = labelPaint,
    )
}

private fun DrawScope.drawOne(
    centerX: Float,
    centerY: Float,
    pointsUp: Boolean,
    color: Color,
    text: String,
    labelPaint: Paint,
) {
    val arm = 8.dp.toPx()
    val stroke = 2.dp.toPx()

    // Triángulo: hacia arriba para el máximo, hacia abajo para el mínimo.
    val path = Path()
    if (pointsUp) {
        path.moveTo(centerX, centerY - arm)
        path.lineTo(centerX - arm, centerY + arm)
        path.lineTo(centerX + arm, centerY + arm)
    } else {
        path.moveTo(centerX, centerY + arm)
        path.lineTo(centerX - arm, centerY - arm)
        path.lineTo(centerX + arm, centerY - arm)
    }
    path.close()
    drawPath(path, color, style = Stroke(width = stroke))
    drawCircle(color, radius = 2.dp.toPx(), center = Offset(centerX, centerY))

    // Etiqueta a la derecha del marcador, o a la izquierda si no cabe.
    labelPaint.textSize = 12.dp.toPx()
    labelPaint.color = color.toArgb()
    val textWidth = labelPaint.measureText(text)
    val gap = arm + 4.dp.toPx()
    val textX = if (centerX + gap + textWidth > size.width) centerX - gap - textWidth else centerX + gap
    val textY = (centerY + 4.dp.toPx()).coerceIn(labelPaint.textSize, size.height - 2.dp.toPx())
    drawContext.canvas.nativeCanvas.drawText(text, textX.coerceAtLeast(0f), textY, labelPaint)
}
