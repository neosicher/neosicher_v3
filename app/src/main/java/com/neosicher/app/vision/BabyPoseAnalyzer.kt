package com.neosicher.app.vision

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * NEOSICHER v2 — analiza cada frame del preview de la cámara Android con dos
 * modelos de ML Kit que corren en el teléfono (sin internet):
 *   1. Pose Detection: esqueleto del cuerpo (hombros, caderas...).
 *   2. Face Detection: si hay una cara y hacia dónde está girada.
 * Combina ambos en una [FrameObservation] y la pasa al [SleepPositionClassifier],
 * que decide la postura y la alerta. Publica un [SleepPositionState] observable.
 *
 * Se engancha al punto de extensión `CameraManager.imageAnalyzer`; no modifica
 * el dashboard ni CameraPreviewPanel. El overlay ([PoseOverlayView]) consume
 * el mismo StateFlow.
 *
 * HONESTIDAD (docs/GW192A_INVESTIGACION.md §9, §10): es una HEURÍSTICA sobre
 * modelos entrenados mayormente con adultos. En bebés acostados, envueltos o
 * con poca luz la exactitud NO está garantizada. Ante duda devuelve UNKNOWN.
 */
class BabyPoseAnalyzer : ImageAnalysis.Analyzer {

    private val poseDetector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            // STREAM_MODE: optimizado para vídeo en vivo.
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            // La cara de un bebé a distancia ocupa poco del encuadre.
            .setMinFaceSize(0.08f)
            .build()
    )

    private val classifier = SleepPositionClassifier()

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

        // Dimensiones de la imagen YA rotada (con 90/270° ancho y alto se intercambian).
        val imgW: Int
        val imgH: Int
        if (rotation == 90 || rotation == 270) {
            imgW = imageProxy.height
            imgH = imageProxy.width
        } else {
            imgW = imageProxy.width
            imgH = imageProxy.height
        }

        // Pose primero, luego rostro sobre la MISMA imagen. El ImageProxy se
        // cierra solo al terminar ambos (o al fallar), para liberar el frame.
        poseDetector.process(inputImage)
            .addOnSuccessListener { pose ->
                faceDetector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        publish(pose, faces, imgW, imgH)
                    }
                    .addOnFailureListener { e ->
                        // Sin detector de rostro no se puede distinguir "cara
                        // tapada": mejor no opinar que dar una falsa alerta.
                        Log.w(TAG, "Fallo en detección de rostro", e)
                        _state.value = SleepPositionState.EMPTY
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Fallo en detección de pose", e)
                _state.value = SleepPositionState.EMPTY
                imageProxy.close()
            }
    }

    /** Combina pose + rostro, clasifica y publica el estado. */
    private fun publish(pose: Pose, faces: List<Face>, imgW: Int, imgH: Int) {
        val allLandmarks = pose.allPoseLandmarks
        val points = allLandmarks.map {
            PosePoint(
                type = it.landmarkType,
                x = it.position.x,
                y = it.position.y,
                inFrameLikelihood = it.inFrameLikelihood,
            )
        }

        // Cara principal = la más grande.
        val mainFace: Face? = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val faceBox: FaceBox? = mainFace?.let {
            FaceBox(
                left = it.boundingBox.left.toFloat(),
                top = it.boundingBox.top.toFloat(),
                right = it.boundingBox.right.toFloat(),
                bottom = it.boundingBox.bottom.toFloat(),
            )
        }

        val shoulderGeometry = measureShoulders(pose, imgH)

        val observation = FrameObservation(
            timestampMs = SystemClock.elapsedRealtime(),
            bodyDetected = allLandmarks.isNotEmpty(),
            shouldersReliable = shoulderGeometry != null,
            shoulderRatio = shoulderGeometry,
            faceDetected = mainFace != null,
            faceYawDegrees = mainFace?.headEulerAngleY,
        )

        val result = classifier.update(observation)

        _state.value = SleepPositionState(
            position = result.position,
            confidence = result.confidence,
            isAlert = result.isAlert,
            landmarks = points,
            faceBox = faceBox,
            sourceImageWidth = imgW,
            sourceImageHeight = imgH,
            personDetected = allLandmarks.isNotEmpty() || mainFace != null,
        )
    }

    /**
     * Razón ancho-de-hombros / largo-de-torso, o null si los hombros no son
     * fiables. Un valor bajo indica cuerpo de lado (un hombro oculta al otro).
     */
    private fun measureShoulders(pose: Pose, imgH: Int): Float? {
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        if (leftShoulder == null || rightShoulder == null) return null
        if (leftShoulder.inFrameLikelihood < MIN_LIKELIHOOD || rightShoulder.inFrameLikelihood < MIN_LIKELIHOOD) {
            return null
        }

        val shoulderWidth = distance(leftShoulder, rightShoulder)

        val torsoSamples = mutableListOf<Float>()
        pose.getPoseLandmark(PoseLandmark.LEFT_HIP)?.let { torsoSamples.add(distance(leftShoulder, it)) }
        pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)?.let { torsoSamples.add(distance(rightShoulder, it)) }
        val torsoLength = if (torsoSamples.isEmpty()) 0f else torsoSamples.average().toFloat()

        return when {
            torsoLength > 1f -> shoulderWidth / torsoLength
            // Caderas tapadas (manta): referencia aproximada por la altura de la imagen.
            imgH > 0 -> shoulderWidth / (imgH * 0.3f)
            else -> null
        }
    }

    private fun distance(a: PoseLandmark, b: PoseLandmark): Float =
        hypot(
            (a.position.x - b.position.x).toDouble(),
            (a.position.y - b.position.y).toDouble(),
        ).toFloat()

    /** Libera los detectores de ML Kit. Llamar al soltar la cámara. */
    fun close() {
        runCatching { poseDetector.close() }
        runCatching { faceDetector.close() }
    }

    companion object {
        private const val TAG = "BabyPoseAnalyzer"
        private const val MIN_LIKELIHOOD = 0.5f
    }
}
