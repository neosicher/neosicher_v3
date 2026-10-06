package com.neosicher.app.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Overlay en tiempo real sobre el preview de la cámara Android: esqueleto,
 * recuadro de la cara, etiqueta de postura y banner de alerta.
 *
 * Se añade como VISTA HIJA del `PreviewView`, así que el dashboard y
 * `CameraPreviewPanel` NO se modifican. Escala las coordenadas de la imagen
 * de análisis al tamaño de esta vista en modo "center-crop", igual que
 * `PreviewView.ScaleType.FILL_CENTER`, para que todo quede alineado.
 */
class PoseOverlayView(context: Context) : View(context) {

    init {
        // Puramente decorativo: no intercepta toques.
        isClickable = false
        isFocusable = false
    }

    private var state: SleepPositionState = SleepPositionState.EMPTY

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        this.style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor("#4ADEDE")
        this.style = Paint.Style.STROKE
        this.strokeWidth = 6f
    }

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor("#30A46C")
        this.style = Paint.Style.STROKE
        this.strokeWidth = 5f
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        this.textSize = 42f
        this.isFakeBoldText = true
    }

    private val noteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        this.textSize = 26f
        this.isFakeBoldText = true
    }

    /** Actualiza el estado a dibujar. Debe llamarse desde el hilo principal. */
    fun update(newState: SleepPositionState) {
        state = newState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val current = state
        val srcW = current.sourceImageWidth
        val srcH = current.sourceImageHeight
        if (srcW <= 0 || srcH <= 0) {
            drawLabel(canvas, current)
            return
        }

        // Escala center-crop (igual que FILL_CENTER del PreviewView).
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = maxOf(viewW / srcW, viewH / srcH)
        val offsetX = (viewW - srcW * scale) / 2f
        val offsetY = (viewH - srcH * scale) / 2f

        fun mapX(x: Float): Float = x * scale + offsetX
        fun mapY(y: Float): Float = y * scale + offsetY

        // Esqueleto.
        val byType = current.landmarks.associateBy { it.type }
        for ((a, b) in CONNECTIONS) {
            val pa = byType[a] ?: continue
            val pb = byType[b] ?: continue
            if (pa.inFrameLikelihood < MIN_DRAW_LIKELIHOOD || pb.inFrameLikelihood < MIN_DRAW_LIKELIHOOD) continue
            canvas.drawLine(mapX(pa.x), mapY(pa.y), mapX(pb.x), mapY(pb.y), linePaint)
        }
        for (p in current.landmarks) {
            if (p.inFrameLikelihood < MIN_DRAW_LIKELIHOOD) continue
            canvas.drawCircle(mapX(p.x), mapY(p.y), 7f, pointPaint)
        }

        // Recuadro de la cara detectada.
        current.faceBox?.let { box ->
            canvas.drawRect(
                RectF(mapX(box.left), mapY(box.top), mapX(box.right), mapY(box.bottom)),
                facePaint,
            )
        }

        drawLabel(canvas, current)
    }

    private fun drawLabel(canvas: Canvas, current: SleepPositionState) {
        val position = current.position
        val backgroundColor = when (position) {
            SleepPosition.PRONE -> Color.parseColor("#E5484D")        // rojo
            SleepPosition.FACE_COVERED -> Color.parseColor("#C2185B") // magenta
            SleepPosition.SUPINE -> Color.parseColor("#30A46C")       // verde
            SleepPosition.SIDE -> Color.parseColor("#F5A623")         // ámbar
            SleepPosition.UNKNOWN -> Color.parseColor("#8B8B8B")      // gris
        }
        labelBgPaint.color = backgroundColor

        val percent = (current.confidence * 100).toInt()
        val text = if (position == SleepPosition.UNKNOWN) {
            position.displayLabel
        } else {
            "${position.displayLabel} · exp. ${percent}%"
        }

        val pad = 24f
        val textWidth = labelTextPaint.measureText(text)
        val boxLeft = 28f
        val boxTop = 28f
        val boxRight = boxLeft + textWidth + pad * 2
        val boxBottom = boxTop + labelTextPaint.textSize + pad * 1.4f

        canvas.drawRoundRect(RectF(boxLeft, boxTop, boxRight, boxBottom), 24f, 24f, labelBgPaint)
        canvas.drawText(
            text,
            boxLeft + pad,
            boxTop + pad + labelTextPaint.textSize * 0.8f,
            labelTextPaint,
        )

        var nextY = boxBottom + 40f

        // Banner de alerta: solo si la postura de atención se sostiene.
        if (current.isAlert) {
            val alertText = "ATENCIÓN: revisar al bebé"
            val alertWidth = labelTextPaint.measureText(alertText)
            labelBgPaint.color = Color.parseColor("#B71C1C")
            canvas.drawRoundRect(
                RectF(boxLeft, nextY - 34f, boxLeft + alertWidth + pad * 2, nextY + 22f),
                20f, 20f, labelBgPaint,
            )
            canvas.drawText(alertText, boxLeft + pad, nextY + 8f, labelTextPaint)
            nextY += 70f
        }

        // Aviso permanente: experimental, nunca diagnóstico médico.
        canvas.drawText(
            "Estimación experimental — no sustituye la supervisión",
            boxLeft,
            nextY,
            noteTextPaint,
        )
    }

    companion object {
        private const val MIN_DRAW_LIKELIHOOD = 0.4f

        /** Pares de landmarks a conectar para dibujar el esqueleto. */
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
