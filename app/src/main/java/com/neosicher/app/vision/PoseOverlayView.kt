package com.neosicher.app.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Vista de overlay que dibuja, en tiempo real y encima del preview de la
 * cámara, el esqueleto detectado por ML Kit y una etiqueta con la postura
 * estimada del bebé.
 *
 * Diseñada para añadirse como VISTA HIJA del `PreviewView` (que es un
 * FrameLayout), de modo que el dashboard y `CameraPreviewPanel` NO se
 * modifican: el overlay se superpone al mismo contenedor del preview.
 *
 * Escala los landmarks (que vienen en coordenadas de la imagen de análisis)
 * al tamaño real de esta vista usando el modo "center-crop", que es como
 * `PreviewView.ScaleType.FILL_CENTER` recorta el preview — así el esqueleto
 * queda alineado con lo que se ve.
 */
class PoseOverlayView(context: Context) : View(context) {

    init {
        // Puramente decorativo: no debe interceptar toques (deja pasar el botón
        // de pantalla completa y demás overlays del preview).
        isClickable = false
        isFocusable = false
    }

    private var state: SleepPositionState = SleepPositionState.EMPTY

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        strokeWidth = 8f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ADEDE") // cian NeoAccent aproximado
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        isFakeBoldText = true
    }

    /** Actualiza el estado a dibujar. Seguro desde el hilo principal. */
    fun update(newState: SleepPositionState) {
        state = newState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val s = state
        val srcW = s.sourceImageWidth
        val srcH = s.sourceImageHeight
        if (srcW <= 0 || srcH <= 0) {
            drawLabel(canvas, SleepPosition.UNKNOWN, 0f)
            return
        }

        // Escala center-crop (igual que FILL_CENTER del PreviewView).
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = maxOf(viewW / srcW, viewH / srcH)
        val offsetX = (viewW - srcW * scale) / 2f
        val offsetY = (viewH - srcH * scale) / 2f

        fun mapX(x: Float) = x * scale + offsetX
        fun mapY(y: Float) = y * scale + offsetY

        // Mapa rápido tipo→punto para dibujar conexiones.
        val byType = s.landmarks.associateBy { it.type }

        // Conexiones del esqueleto (torso + extremidades principales).
        for ((a, b) in CONNECTIONS) {
            val pa = byType[a] ?: continue
            val pb = byType[b] ?: continue
            if (pa.inFrameLikelihood < MIN_DRAW_LIKELIHOOD || pb.inFrameLikelihood < MIN_DRAW_LIKELIHOOD) continue
            canvas.drawLine(mapX(pa.x), mapY(pa.y), mapX(pb.x), mapY(pb.y), linePaint)
        }

        // Puntos.
        for (p in s.landmarks) {
            if (p.inFrameLikelihood < MIN_DRAW_LIKELIHOOD) continue
            canvas.drawCircle(mapX(p.x), mapY(p.y), 7f, pointPaint)
        }

        drawLabel(canvas, s.position, s.confidence)
    }

    private fun drawLabel(canvas: Canvas, position: SleepPosition, confidence: Float) {
        val color = when (position) {
            SleepPosition.PRONE -> Color.parseColor("#E5484D")   // rojo: postura de riesgo
            SleepPosition.SUPINE -> Color.parseColor("#30A46C")  // verde
            SleepPosition.SIDE -> Color.parseColor("#F5A623")    // ámbar
            SleepPosition.UNKNOWN -> Color.parseColor("#8B8B8B") // gris
        }
        labelBgPaint.color = color

        val pct = (confidence * 100).toInt()
        val text = if (position == SleepPosition.UNKNOWN) {
            position.displayLabel
        } else {
            "${position.displayLabel} · exp. ${pct}%"
        }

        val pad = 24f
        val textWidth = labelTextPaint.measureText(text)
        val boxLeft = 28f
        val boxTop = 28f
        val boxRight = boxLeft + textWidth + pad * 2
        val boxBottom = boxTop + labelTextPaint.textSize + pad * 1.4f

        canvas.drawRoundRect(
            RectF(boxLeft, boxTop, boxRight, boxBottom),
            24f, 24f, labelBgPaint,
        )
        canvas.drawText(
            text,
            boxLeft + pad,
            boxTop + pad + labelTextPaint.textSize * 0.8f,
            labelTextPaint,
        )

        // Aviso permanente de que es experimental, nunca diagnóstico médico.
        val note = "Estimación experimental — no es diagnóstico médico"
        val notePaint = Paint(labelTextPaint).apply { textSize = 26f; color = Color.WHITE }
        canvas.drawText(note, boxLeft, boxBottom + 36f, notePaint)
    }

    companion object {
        private const val MIN_DRAW_LIKELIHOOD = 0.4f

        /** Pares de landmarks a conectar con líneas para dibujar el esqueleto. */
        private val CONNECTIONS: List<Pair<Int, Int>> = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
        )
    }
}
