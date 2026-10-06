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
 * Búsqueda de rotación: los modelos esperan a la persona "derecha". Un bebé
 * tumbado visto desde arriba aparece en cualquier orientación, así que si no se
 * detecta nada durante unos frames se prueba girando la imagen 90° cada vez
 * hasta encontrarlo, y se mantiene ese giro mientras siga apareciendo.
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

    /** Contador de frames recibidos (solo se toca desde el hilo de análisis). */
    private var framesReceived = 0L

    /** Giro extra (0/90/180/270, sentido horario) aplicado a la imagen antes de ML Kit. */
    @Volatile
    private var extraRotation = 0

    /** Frames seguidos sin encontrar ni cuerpo ni cara con el giro actual. */
    @Volatile
    private var missesInARow = 0

    private val _state = MutableStateFlow(SleepPositionState.EMPTY)
    val state: StateFlow<SleepPositionState> = _state.asStateFlow()

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        framesReceived++
        if (framesReceived == 1L) Log.i(TAG, "Primer frame recibido de la cámara")
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val sensorRotation = imageProxy.imageInfo.rotationDegrees
        val extra = extraRotation
        val inputImage = InputImage.fromMediaImage(mediaImage, (sensorRotation + extra) % 360)

        // Tamaño de la imagen YA orientada para el visor (sin el giro extra).
        val imgW: Int
        val imgH: Int
        if (sensorRotation == 90 || sensorRotation == 270) {
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
                        publish(pose, faces, imgW, imgH, extra)
                    }
                    .addOnFailureListener { e ->
                        // Sin detector de rostro no se puede distinguir "cara
                        // tapada": mejor no opinar que dar una falsa alerta.
                        Log.w(TAG, "Fallo en detección de rostro", e)
                        _state.value = SleepPositionState(
                            framesReceived = framesReceived,
                            analysisError = "rostro: ${e.message ?: e.javaClass.simpleName}",
                        )
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Fallo en detección de pose", e)
                _state.value = SleepPositionState(
                    framesReceived = framesReceived,
                    analysisError = "pose: ${e.message ?: e.javaClass.simpleName}",
                )
                imageProxy.close()
            }
    }

    /** Combina pose + rostro, clasifica y publica el estado. */
    private fun publish(pose: Pose, faces: List<Face>, imgW: Int, imgH: Int, extra: Int) {
        val displayW = imgW.toFloat()
        val displayH = imgH.toFloat()
        val allLandmarks = pose.allPoseLandmarks

        // Puntos para dibujar: se devuelven a las coordenadas del visor
        // deshaciendo el giro extra aplicado para ML Kit.
        val points = allLandmarks.map { landmark ->
            val mapped = PoseGeometry.rotatedToDisplay(
                landmark.position.x, landmark.position.y, extra, displayW, displayH,
            )
            PosePoint(
                type = landmark.landmarkType,
                x = mapped.first,
                y = mapped.second,
                inFrameLikelihood = landmark.inFrameLikelihood,
            )
        }

        // Cara principal = la más grande.
        val mainFace: Face? = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val faceBox: FaceBox? = mainFace?.let { face -> mapFaceBox(face, extra, displayW, displayH) }

        val shoulderGeometry = measureShoulders(pose, imgH)
        val chestFacing = estimateChestFacing(pose)
        val bodyFound = allLandmarks.isNotEmpty()

        val observation = FrameObservation(
            timestampMs = SystemClock.elapsedRealtime(),
            bodyDetected = bodyFound,
            shouldersReliable = shoulderGeometry != null,
            shoulderRatio = shoulderGeometry,
            faceDetected = mainFace != null,
            faceYawDegrees = mainFace?.headEulerAngleY,
            chestFacing = chestFacing,
        )

        val result = classifier.update(observation)

        // Búsqueda de rotación: si no hay nada, probar el siguiente giro.
        if (bodyFound || mainFace != null) {
            missesInARow = 0
        } else {
            missesInARow++
            if (missesInARow >= SCAN_AFTER_MISSES) {
                extraRotation = (extra + 90) % 360
                missesInARow = 0
            }
        }

        _state.value = SleepPositionState(
            position = result.position,
            confidence = result.confidence,
            isAlert = result.isAlert,
            landmarks = points,
            faceBox = faceBox,
            sourceImageWidth = imgW,
            sourceImageHeight = imgH,
            personDetected = bodyFound || mainFace != null,
            framesReceived = framesReceived,
            faceCount = faces.size,
            shoulderRatio = shoulderGeometry,
            faceYawDegrees = mainFace?.headEulerAngleY,
            chestFacing = chestFacing,
            extraRotationDegrees = extra,
        )
    }

    /** Devuelve el recuadro de la cara a coordenadas del visor (deshaciendo el giro extra). */
    private fun mapFaceBox(face: Face, extra: Int, displayW: Float, displayH: Float): FaceBox {
        val box = face.boundingBox
        val corners = listOf(
            box.left.toFloat() to box.top.toFloat(),
            box.right.toFloat() to box.top.toFloat(),
            box.left.toFloat() to box.bottom.toFloat(),
            box.right.toFloat() to box.bottom.toFloat(),
        ).map { corner ->
            PoseGeometry.rotatedToDisplay(corner.first, corner.second, extra, displayW, displayH)
        }
        return FaceBox(
            left = corners.minOf { it.first },
            top = corners.minOf { it.second },
            right = corners.maxOf { it.first },
            bottom = corners.maxOf { it.second },
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

    /**
     * ¿Pecho o espalda hacia la cámara? Usa los hombros y el eje caderas->cabeza
     * (o hombros->nariz si las caderas no se ven). Ver [PoseGeometry.chestFacing]:
     * es una HIPÓTESIS por validar con bebés reales. null = no concluyente.
     */
    private fun estimateChestFacing(pose: Pose): Boolean? {
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        if (leftShoulder == null || rightShoulder == null) return null
        if (leftShoulder.inFrameLikelihood < MIN_LIKELIHOOD || rightShoulder.inFrameLikelihood < MIN_LIKELIHOOD) {
            return null
        }
        val shoulderMidX = (leftShoulder.position.x + rightShoulder.position.x) / 2f
        val shoulderMidY = (leftShoulder.position.y + rightShoulder.position.y) / 2f

        val hips = listOfNotNull(
            pose.getPoseLandmark(PoseLandmark.LEFT_HIP),
            pose.getPoseLandmark(PoseLandmark.RIGHT_HIP),
        ).filter { it.inFrameLikelihood >= MIN_LIKELIHOOD }

        val axisX: Float
        val axisY: Float
        if (hips.isNotEmpty()) {
            // Eje cadera -> hombros (hacia la cabeza).
            axisX = shoulderMidX - hips.map { it.position.x }.average().toFloat()
            axisY = shoulderMidY - hips.map { it.position.y }.average().toFloat()
        } else {
            // Sin caderas: eje hombros -> nariz.
            val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
            if (nose == null || nose.inFrameLikelihood < MIN_LIKELIHOOD) return null
            axisX = nose.position.x - shoulderMidX
            axisY = nose.position.y - shoulderMidY
        }

        return PoseGeometry.chestFacing(
            leftShoulder.position.x, leftShoulder.position.y,
            rightShoulder.position.x, rightShoulder.position.y,
            axisX, axisY,
        )
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

        /** Frames seguidos sin detectar nada antes de probar otro giro de imagen. */
        private const val SCAN_AFTER_MISSES = 4
    }
}
