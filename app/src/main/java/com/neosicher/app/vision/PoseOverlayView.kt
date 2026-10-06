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
 * recuadro de la cara, etiqueta de postura, banner de alerta y diagnóstico.
 *
 * Se añade como HERMANO del `PreviewView` (nunca hijo: CameraX ejecuta
 * removeAllViews() sobre el PreviewView al arrancar la cámara). Escala las
 * coordenadas de la imagen de análisis al tamaño de esta vista en modo
 * "center-crop", igual que `PreviewView.ScaleType.FILL_CENTER`.
 *
 * Marcas de verificación (siempre visibles, incluso sin detecciones):
 *  - borde cian alrededor del visor,
 *  - etiqueta "NEOSICHER v2.0" arriba a la derecha.
 * Si NO se ven, la app instalada no es este build o el overlay no está montado.
 */
class PoseOverlayView(context: Context) : View(context) {

    init {
        // Puramente decorativo: no intercepta toques.
        isClickable = false
        isFocusable = false
    }

    private val density: Float = resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    private var state: SleepPositionState = SleepPositionState.EMPTY

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        this.style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor("#4ADEDE")
        this.style = Paint.Style.STROKE
        this.strokeWidth = 2.5f * density
    }

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor("#30A46C")
        this.style = Paint.Style.STROKE
        this.strokeWidth = 2f * density
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor("#4ADEDE")
        this.style = Paint.Style.STROKE
        this.strokeWidth = 3f * density
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        this.textSize = 15f * density
        this.isFakeBoldText = true
    }

    private val noteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        this.textSize = 10f * density
        this.isFakeBoldText = true
        // Sombra para que se lea sobre cualquier fondo de cámara.
        this.setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }

    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.BLACK
        this.textSize = 11f * density
        this.isFakeBoldText = true
    }

    private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor("#4ADEDE")
        this.style = Paint.Style.FILL
    }

    /** Actualiza el estado a dibujar. Debe llamarse desde el hilo principal. */
    fun update(newState: SleepPositionState) {
        state = newState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawVerificationMarks(canvas)

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
            canvas.drawCircle(mapX(p.x), mapY(p.y), dp(3f), pointPaint)
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

    /** Borde + etiqueta de versión: prueban que ESTE overlay está montado y dibujando. */
    private fun drawVerificationMarks(canvas: Canvas) {
        val inset = dp(2f)
        canvas.drawRect(inset, inset, width - inset, height - inset, borderPaint)

        val padH = dp(10f)
        val padV = dp(5f)
        val textWidth = tagTextPaint.measureText(VERSION_TAG)
        val right = width - dp(14f)
        val left = right - textWidth - padH * 2
        val top = dp(14f)
        val bottom = top + tagTextPaint.textSize + padV * 2
        canvas.drawRoundRect(RectF(left, top, right, bottom), dp(10f), dp(10f), tagBgPaint)
        canvas.drawText(VERSION_TAG, left + padH, top + padV + tagTextPaint.textSize * 0.85f, tagTextPaint)
    }

    private fun drawLabel(canvas: Canvas, current: SleepPositionState) {
        val position = current.position
        val backgroundColor = when (position) {
            SleepPosition.PRONE -> Color.parseColor("#E5484D")        // rojo
            SleepPosition.FACE_COVERED -> Color.parseColor("#C2185B") // magenta
            SleepPosition.SUPINE -> Color.parseColor("#30A46C")       // verde
            SleepPosition.SIDE -> Color.parseColor("#F5A623")         // ámbar
            SleepPosition.UNKNOWN -> Color.parseColor("#6B6B6B")      // gris
        }
        labelBgPaint.color = backgroundColor

        val percent = (current.confidence * 100).toInt()
        val text = if (position == SleepPosition.UNKNOWN) {
            position.displayLabel
        } else {
            "${position.displayLabel} · exp. ${percent}%"
        }

        // Debajo del rótulo "Cámara Android" de la interfaz (arriba-izquierda),
        // que si no taparía la etiqueta.
        val pad = dp(10f)
        val boxLeft = dp(14f)
        val boxTop = dp(58f)
        val textWidth = labelTextPaint.measureText(text)
        val boxRight = boxLeft + textWidth + pad * 2
        val boxBottom = boxTop + labelTextPaint.textSize + pad * 1.6f

        canvas.drawRoundRect(RectF(boxLeft, boxTop, boxRight, boxBottom), dp(10f), dp(10f), labelBgPaint)
        canvas.drawText(
            text,
            boxLeft + pad,
            boxTop + pad * 0.8f + labelTextPaint.textSize * 0.85f,
            labelTextPaint,
        )

        var nextY = boxBottom + dp(18f)

        // Banner de alerta: solo si la postura de atención se sostiene.
        if (current.isAlert) {
            val alertText = "ATENCIÓN: revisar al bebé"
            val alertWidth = labelTextPaint.measureText(alertText)
            labelBgPaint.color = Color.parseColor("#B71C1C")
            canvas.drawRoundRect(
                RectF(boxLeft, nextY - dp(4f), boxLeft + alertWidth + pad * 2, nextY + labelTextPaint.textSize + pad),
                dp(10f), dp(10f), labelBgPaint,
            )
            canvas.drawText(alertText, boxLeft + pad, nextY + labelTextPaint.textSize * 0.9f, labelTextPaint)
            nextY += labelTextPaint.textSize + pad + dp(16f)
        }

        // Aviso permanente: experimental, nunca diagnóstico médico.
        canvas.drawText(
            "Estimación experimental — no sustituye la supervisión",
            boxLeft,
            nextY,
            noteTextPaint,
        )
        nextY += dp(16f)

        // Línea de diagnóstico: qué está viendo realmente el detector.
        val ratioText = current.shoulderRatio?.let { "%.2f".format(it) } ?: "--"
        val yawText = current.faceYawDegrees?.let { "%.0f°".format(it) } ?: "--"
        val bodyText = if (current.landmarks.isNotEmpty()) "sí" else "no"
        canvas.drawText(
            "frames=${current.framesReceived} cuerpo=$bodyText caras=${current.faceCount} " +
                "hombros/torso=$ratioText giro=$yawText",
            boxLeft,
            nextY,
            noteTextPaint,
        )
        current.analysisError?.let { error ->
            canvas.drawText("ERROR ML Kit -> $error", boxLeft, nextY + dp(16f), noteTextPaint)
        }
    }

    companion object {
        private const val VERSION_TAG = "NEOSICHER v2.0 · overlay activo"
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
