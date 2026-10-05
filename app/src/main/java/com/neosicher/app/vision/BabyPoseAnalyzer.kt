package com.neosicher.app.vision

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.hypot

/**
 * [ImageAnalysis.Analyzer] que corre ML Kit Pose Detection (on-device) sobre
 * cada frame del preview de la cámara Android, estima la postura al dormir del
 * bebé y publica un [SleepPositionState] observable.
 *
 * Se engancha al punto de extensión `CameraManager.imageAnalyzer` que ya
 * existía reservado para "detección/ubicación del bebé" — NO modifica el
 * dashboard ni CameraPreviewPanel. El overlay en tiempo real (ver
 * [PoseOverlayView]) consume este mismo StateFlow.
 *
 * HONESTIDAD / REGLA DEL PROYECTO (docs/GW192A_INVESTIGACION.md §9, §10):
 *  - La clasificación de postura es una HEURÍSTICA GEOMÉTRICA sobre los
 *    landmarks (hombros/caderas/nariz), no una medición certificada.
 *  - Los modelos de pose están entrenados mayoritariamente con adultos de pie;
 *    en bebés acostados la exactitud no está garantizada. Por eso ante
 *    cualquier duda se devuelve [SleepPosition.UNKNOWN] en vez de forzar una
 *    clasificación.
 */
class BabyPoseAnalyzer : ImageAnalysis.Analyzer {

    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            // STREAM_MODE: optimizado para vídeo en vivo (reutiliza predicción previa).
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val _state = MutableStateFlow(SleepPositionState.EMPTY)
    val state: StateFlow<SleepPositionState> = _state.asStateFlow()

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        // Dimensiones de la imagen YA rotada: tras una rotación de 90/270°,
        // ancho y alto se intercambian respecto al buffer original. Esto es lo
        // que el overlay necesita para escalar los landmarks correctamente.
        val (imgW, imgH) = if (rotation == 90 || rotation == 270) {
            imageProxy.height to imageProxy.width
        } else {
            imageProxy.width to imageProxy.height
        }

        detector.process(inputImage)
            .addOnSuccessListener { pose ->
                _state.value = classify(pose, imgW, imgH)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Fallo en detección de pose", e)
                _state.value = SleepPositionState.EMPTY
            }
            .addOnCompleteListener {
                // Imprescindible cerrar el ImageProxy para liberar el frame y
                // permitir que llegue el siguiente (STRATEGY_KEEP_ONLY_LATEST).
                imageProxy.close()
            }
    }

    /**
     * Clasifica la postura a partir de la geometría de los landmarks clave.
     *
     * Lógica (heurística, conservadora):
     *  - Si no hay suficientes puntos fiables → UNKNOWN.
     *  - Distancia horizontal entre hombros (y entre caderas) GRANDE respecto a
     *    la altura torso ⇒ el cuerpo se ve "de frente" (boca arriba o boca
     *    abajo). Para distinguir supino vs prono se usa la posición de la nariz
     *    respecto a los hombros y la visibilidad de puntos faciales:
     *      · Cara/ojos bien visibles y nariz entre los hombros ⇒ SUPINE.
     *      · Cara poco visible (nuca hacia la cámara) ⇒ PRONE.
     *  - Hombros muy juntos horizontalmente (uno oculta al otro) ⇒ SIDE.
     *
     * Devuelve confianza conservadora (máx 0.6) para no transmitir falsa certeza.
     */
    private fun classify(pose: Pose, imgW: Int, imgH: Int): SleepPositionState {
        val allLandmarks = pose.allPoseLandmarks
        if (allLandmarks.isEmpty()) {
            return SleepPositionState(
                sourceImageWidth = imgW,
                sourceImageHeight = imgH,
                personDetected = false,
            )
        }

        val points = allLandmarks.map {
            PosePoint(
                type = it.landmarkType,
                x = it.position.x,
                y = it.position.y,
                inFrameLikelihood = it.inFrameLikelihood,
            )
        }

        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        val leftEye = pose.getPoseLandmark(PoseLandmark.LEFT_EYE)
        val rightEye = pose.getPoseLandmark(PoseLandmark.RIGHT_EYE)

        // Necesitamos al menos ambos hombros con confianza razonable para intentar clasificar.
        val minLikelihood = 0.5f
        val shouldersReliable = leftShoulder != null && rightShoulder != null &&
            leftShoulder.inFrameLikelihood >= minLikelihood &&
            rightShoulder.inFrameLikelihood >= minLikelihood

        if (!shouldersReliable) {
            return SleepPositionState(
                position = SleepPosition.UNKNOWN,
                confidence = 0f,
                landmarks = points,
                sourceImageWidth = imgW,
                sourceImageHeight = imgH,
                personDetected = true,
            )
        }

        val shoulderWidth = hypot(
            (leftShoulder!!.position.x - rightShoulder!!.position.x).toDouble(),
            (leftShoulder.position.y - rightShoulder.position.y).toDouble(),
        ).toFloat()

        // Longitud de referencia del torso: distancia media hombro→cadera.
        val torsoLength: Float = run {
            val pairs = buildList {
                if (leftHip != null) add(
                    hypot(
                        (leftShoulder.position.x - leftHip.position.x).toDouble(),
                        (leftShoulder.position.y - leftHip.position.y).toDouble(),
                    ).toFloat()
                )
                if (rightHip != null) add(
                    hypot(
                        (rightShoulder.position.x - rightHip.position.x).toDouble(),
                        (rightShoulder.position.y - rightHip.position.y).toDouble(),
                    ).toFloat()
                )
            }
            if (pairs.isEmpty()) 0f else pairs.average().toFloat()
        }

        // Razón ancho de hombros / largo de torso. Si no hay torso fiable, se
        // normaliza con el tamaño de la imagen como fallback conservador.
        val ratio = when {
            torsoLength > 1f -> shoulderWidth / torsoLength
            imgH > 0 -> shoulderWidth / (imgH * 0.3f)
            else -> 0f
        }

        // Cara visible: ayuda a distinguir supino (cara a cámara) de prono (nuca).
        val faceVisible = listOfNotNull(nose, leftEye, rightEye)
            .count { it.inFrameLikelihood >= minLikelihood } >= 2

        // Heurística principal.
        //  ratio pequeño  ⇒ hombros "colapsados" en horizontal ⇒ de lado.
        //  ratio grande   ⇒ cuerpo de frente ⇒ supino (cara visible) o prono.
        val (position, confidence) = when {
            ratio < SIDE_RATIO_THRESHOLD ->
                SleepPosition.SIDE to 0.5f

            faceVisible ->
                SleepPosition.SUPINE to 0.6f

            else ->
                // Cuerpo de frente pero cara no visible: probable boca abajo.
                SleepPosition.PRONE to 0.45f
        }

        // Ajuste extra: si la nariz está claramente fuera del rango vertical de
        // los hombros, baja la confianza (postura ambigua / cuerpo girado).
        val adjustedConfidence = run {
            if (nose != null && nose.inFrameLikelihood >= minLikelihood) {
                val shoulderMidY = (leftShoulder.position.y + rightShoulder.position.y) / 2f
                val verticalGap = abs(nose.position.y - shoulderMidY)
                if (torsoLength > 1f && verticalGap > torsoLength * 1.2f) {
                    (confidence - 0.15f).coerceAtLeast(0.2f)
                } else confidence
            } else confidence
        }

        return SleepPositionState(
            position = position,
            confidence = adjustedConfidence,
            landmarks = points,
            sourceImageWidth = imgW,
            sourceImageHeight = imgH,
            personDetected = true,
        )
    }

    /** Libera el detector de ML Kit. Llamar al soltar la cámara. */
    fun close() {
        runCatching { detector.close() }
    }

    companion object {
        private const val TAG = "BabyPoseAnalyzer"

        /**
         * Por debajo de esta razón ancho-hombros / largo-torso se considera que
         * el bebé está de lado (un hombro oculta al otro). Valor empírico
         * conservador; ajustable tras pruebas reales en el POCO F7.
         */
        private const val SIDE_RATIO_THRESHOLD = 0.45f
    }
}
