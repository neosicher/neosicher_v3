package com.neosicher.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neosicher.app.camera.CameraManager
import com.neosicher.app.camera.CameraLens
import com.neosicher.app.camera.CameraPermissionState
import com.neosicher.app.monitoring.ThermalReading
import com.neosicher.app.monitoring.ThermalReadingStatus
import com.neosicher.app.monitoring.ThermalSource
import com.neosicher.app.thermal.AgcMonitor
import com.neosicher.app.thermal.AgcVerdict
import com.neosicher.app.thermal.CalibrationPointKind
import com.neosicher.app.thermal.FpsMeter
import com.neosicher.app.thermal.Gw192aThermalCameraDataSource
import com.neosicher.app.thermal.ThermalAnalysis
import com.neosicher.app.thermal.ThermalCalibration
import com.neosicher.app.thermal.ThermalCalibrationPoint
import com.neosicher.app.thermal.ThermalCalibrationStore
import com.neosicher.app.thermal.ThermalCameraRepository
import com.neosicher.app.thermal.ThermalCameraState
import com.neosicher.app.thermal.ThermalFrameResult
import com.neosicher.app.thermal.ThermalMeasurements
import com.neosicher.app.thermal.ThermalMeasurementsBuilder
import com.neosicher.app.thermal.ThermalSpot
import com.neosicher.app.usb.UsbDeviceManager
import com.neosicher.app.vision.BabyPoseAnalyzer
import com.neosicher.app.vision.PoseOverlayView
import com.neosicher.app.vision.SleepPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel de la pantalla principal.
 *
 * Orquesta la cámara Android ([CameraManager]) y la cámara térmica GW192A
 * ([ThermalCameraRepository]) y expone un único [NeosicherUiState] observable.
 *
 * Las métricas (vitales, sueño, lectura térmica) permanecen SIN fuente real:
 * este MVP no las produce y por tanto no se muestran valores inventados.
 */
class NeosicherViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext

    val cameraManager = CameraManager(appContext)

    // Detección de postura del bebé en tiempo real sobre el preview de la
    // cámara Android (ML Kit Pose, on-device). Es EXPERIMENTAL — ver
    // BabyPoseAnalyzer / SleepPosition. Se engancha al punto de extensión
    // imageAnalyzer que ya estaba reservado en CameraManager, y dibuja un
    // overlay (PoseOverlayView) como hijo del PreviewView, SIN modificar el
    // dashboard ni CameraPreviewPanel.
    private val babyPoseAnalyzer = BabyPoseAnalyzer()
    private val poseOverlay = PoseOverlayView(appContext)

    val sleepPosition: StateFlow<SleepPositionState> = babyPoseAnalyzer.state

    private val usbManager = UsbDeviceManager(appContext)
    private val thermalRepository = ThermalCameraRepository(
        Gw192aThermalCameraDataSource(usbManager, viewModelScope)
    )

    private val _uiState = MutableStateFlow(NeosicherUiState())
    val uiState: StateFlow<NeosicherUiState> = _uiState.asStateFlow()

    // Calibración del usuario (3 puntos: frío, caliente, corporal). Vive aparte
    // de NeosicherUiState a propósito: su pantalla es independiente y no debe
    // tocar nada del dashboard principal salvo el resultado final (ver abajo).
    // Se guarda en el teléfono para no recalibrar cada vez que se abre la app.
    private val calibrationStore = ThermalCalibrationStore(appContext)
    private val _calibration = MutableStateFlow(calibrationStore.load())
    val calibration: StateFlow<ThermalCalibration> = _calibration.asStateFlow()

    // -- Estado del laboratorio térmico (mediciones por puntos) ----------------

    /** Punto elegido por el usuario sobre la imagen térmica (null = se usa el centro). */
    private val _thermalSpot = MutableStateFlow<ThermalSpot?>(null)
    val thermalSpot: StateFlow<ThermalSpot?> = _thermalSpot.asStateFlow()

    /** Mensaje corto del laboratorio (guardado de archivos, errores). */
    private val _labMessage = MutableStateFlow<String?>(null)
    val labMessage: StateFlow<String?> = _labMessage.asStateFlow()

    private val _isRecordingThermal = MutableStateFlow(false)
    val isRecordingThermal: StateFlow<Boolean> = _isRecordingThermal.asStateFlow()

    private val agcMonitor = AgcMonitor()
    private val fpsMeter = FpsMeter()
    private var thermalFrameCount = 0L
    private var lastFps = 0.0

    /** Último frame tal como lo entregó el repositorio (sin mediciones añadidas). */
    private var lastRawFrame: ThermalFrameResult? = null
    private var currentMeasurements: ThermalMeasurements? = null

    /** Últimos valores raw del punto de referencia, para promediar al capturar (reduce ruido). */
    private val recentReferenceRaw = ArrayDeque<Double>()

    private val recordingLines = ArrayList<String>()
    private var recordingStartMs = 0L

    private var batteryReceiver: BroadcastReceiver? = null

    init {
        // Enganchar la detección de postura al pipeline de la cámara Android.
        cameraManager.imageAnalyzer = babyPoseAnalyzer
        cameraManager.previewOverlay = poseOverlay

        // Cada nueva estimación de postura: redibujar el overlay (en el hilo
        // principal, requisito de las vistas Android). No toca el dashboard.
        viewModelScope.launch {
            babyPoseAnalyzer.state.collect { pose ->
                poseOverlay.post { poseOverlay.update(pose) }
            }
        }
        // Fusionar el estado de la cámara Android en el UI state.
        viewModelScope.launch {
            cameraManager.state.collect { camera ->
                _uiState.update { it.copy(cameraState = camera) }
            }
        }
        // Fusionar el estado de la cámara térmica.
        viewModelScope.launch {
            thermalRepository.state.collect { thermal ->
                val frame = thermal.lastFrame
                if (frame == null) {
                    // Sin stream: olvidar lo medido para no mostrar datos viejos.
                    lastRawFrame = null
                    currentMeasurements = null
                    recentReferenceRaw.clear()
                    fpsMeter.reset()
                } else if (frame !== lastRawFrame) {
                    // El repositorio también emite por cambios de mensaje; solo un
                    // frame distinto cuenta como frame nuevo.
                    lastRawFrame = frame
                    onNewThermalFrame(frame)
                }
                publishThermalState(thermal)
                // Si hay calibración suficiente, derivar la temperatura aproximada
                // del punto de referencia y publicarla en la tarjeta de
                // Temperatura, SIN tocar ningún otro componente de la interfaz.
                // Sin calibración suficiente, se mantiene "--".
                applyCalibrationToReading(currentMeasurements)
            }
        }
        // Fusionar la lectura térmica base (siempre NONE desde la fuente: no
        // hay protocolo radiométrico confirmado). La calibración del usuario
        // la complementa por separado, arriba.
        viewModelScope.launch {
            thermalRepository.reading.collect { reading ->
                _uiState.update { it.copy(thermalReading = reading) }
            }
        }
        registerBattery()
    }

    /** Procesa un frame térmico nuevo: fps, vigilancia de AGC, mediciones y grabación. */
    private fun onNewThermalFrame(frame: ThermalFrameResult) {
        thermalFrameCount++
        lastFps = fpsMeter.tick(SystemClock.elapsedRealtime())
        val frameMin = frame.rawMin
        val frameMax = frame.rawMax
        if (frameMin != null && frameMax != null) agcMonitor.add(frameMin, frameMax)

        currentMeasurements = measure(frame)
        currentMeasurements?.let { measurements ->
            recentReferenceRaw.addLast(measurements.reference.raw)
            while (recentReferenceRaw.size > REFERENCE_AVERAGE_FRAMES) recentReferenceRaw.removeFirst()
            if (_isRecordingThermal.value) appendRecordingLine(measurements)
        }
    }

    /** Calcula las mediciones por puntos de [frame] con la calibración y el punto actuales. */
    private fun measure(frame: ThermalFrameResult): ThermalMeasurements? {
        val luma = frame.rawLuma ?: return null
        return ThermalMeasurementsBuilder.build(
            luma = luma,
            width = frame.rawWidth,
            height = frame.rawHeight,
            calibration = _calibration.value,
            spot = _thermalSpot.value,
            agc = agcMonitor.verdict(),
            fps = lastFps,
            frameCount = thermalFrameCount,
        )
    }

    /** Publica el estado térmico añadiendo las mediciones al frame (para dibujar los marcadores). */
    private fun publishThermalState(thermal: ThermalCameraState) {
        val frame = thermal.lastFrame
        val shown = if (frame != null) {
            thermal.copy(lastFrame = frame.copy(measurements = currentMeasurements))
        } else {
            thermal
        }
        _uiState.update { it.copy(thermalState = shown) }
    }

    /** Recalcula las mediciones del último frame (cambió la calibración o el punto elegido). */
    private fun refreshThermalMeasurements() {
        val frame = lastRawFrame ?: return
        currentMeasurements = measure(frame)
        publishThermalState(thermalRepository.state.value)
        applyCalibrationToReading(currentMeasurements)
    }

    /**
     * Si la calibración del usuario tiene suficientes puntos, convierte el valor
     * crudo del PUNTO DE REFERENCIA (el elegido por el usuario o, si no hay, el
     * centro) a una temperatura aproximada y actualiza SOLO el campo de
     * temperatura del estado (no afecta cardiaca/respiratoria ni ningún otro
     * componente visual).
     */
    private fun applyCalibrationToReading(measurements: ThermalMeasurements?) {
        val calib = _calibration.value
        val celsius = measurements?.reference?.celsius
        if (measurements == null || !calib.isCalibrated || celsius == null) {
            if (_uiState.value.thermalReading.status != ThermalReadingStatus.NO_READING) {
                _uiState.update { it.copy(thermalReading = ThermalReading.NONE) }
            }
            return
        }
        // Confianza deliberadamente baja. Se reduce más si la cámara parece
        // reescalar cada frame (AGC) o si el valor queda fuera de lo calibrado.
        val base = if (calib.hasAssumedPoint) 0.3 else 0.6
        val degraded = measurements.agc == AgcVerdict.LIKELY_STRETCHED_EVERY_FRAME ||
            measurements.reference.extrapolated
        _uiState.update {
            it.copy(
                thermalReading = ThermalReading(
                    temperatureCelsius = celsius,
                    timestampMillis = System.currentTimeMillis(),
                    confidence = if (degraded) minOf(base, 0.2) else base,
                    source = ThermalSource.GW192A,
                    // Siempre EXPERIMENTAL: es una aproximación de 2-3 puntos
                    // sobre un sensor no radiométrico, nunca una medición
                    // médica certificada (ver docs/GW192A_INVESTIGACION.md §6, §14).
                    status = ThermalReadingStatus.EXPERIMENTAL,
                )
            )
        }
    }

    // -- Calibración térmica (pantalla independiente) -----------------------

    /**
     * Valor crudo (0..255) del punto de referencia (el elegido o, si no hay, el
     * centro), PROMEDIADO sobre los últimos ~[REFERENCE_AVERAGE_FRAMES] frames
     * para reducir el ruido. null si no hay stream.
     */
    fun latestReferenceRawValue(): Double? =
        if (recentReferenceRaw.isEmpty()) null else recentReferenceRaw.average()

    /** Se llama tras cualquier cambio de calibración: guarda y recalcula lo mostrado. */
    private fun onCalibrationChanged() {
        calibrationStore.save(_calibration.value)
        refreshThermalMeasurements()
    }

    fun setColdCalibrationPoint(rawValue: Double, knownTemperatureCelsius: Double) {
        _calibration.update {
            it.copy(coldPoint = ThermalCalibrationPoint("Frío conocido", rawValue, knownTemperatureCelsius))
        }
        onCalibrationChanged()
    }

    fun setHotCalibrationPoint(rawValue: Double, knownTemperatureCelsius: Double) {
        _calibration.update {
            it.copy(hotPoint = ThermalCalibrationPoint("Caliente conocido", rawValue, knownTemperatureCelsius))
        }
        onCalibrationChanged()
    }

    fun setBodyCalibrationPoint(rawValue: Double, knownTemperatureCelsius: Double, isAssumed: Boolean) {
        _calibration.update {
            it.copy(bodyPoint = ThermalCalibrationPoint("Corporal", rawValue, knownTemperatureCelsius, isAssumed))
        }
        onCalibrationChanged()
    }

    fun clearCalibrationPoint(which: CalibrationPointKind) {
        _calibration.update {
            when (which) {
                CalibrationPointKind.COLD -> it.copy(coldPoint = null)
                CalibrationPointKind.HOT -> it.copy(hotPoint = null)
                CalibrationPointKind.BODY -> it.copy(bodyPoint = null)
            }
        }
        onCalibrationChanged()
    }

    // -- Laboratorio térmico: punto elegido, análisis y captura -------------

    /** Elige (o con null, quita) el punto a medir sobre la imagen térmica. */
    fun setThermalSpot(spot: ThermalSpot?) {
        _thermalSpot.value = spot
        // El promedio anterior era de otro punto: empezar de cero.
        recentReferenceRaw.clear()
        refreshThermalMeasurements()
        currentMeasurements?.let { recentReferenceRaw.addLast(it.reference.raw) }
    }

    /** Describe la estructura del último frame recibido (formato, croma, filas extra, bytes sobrantes). */
    fun analyzeThermalFrame(): List<String> {
        val frame = lastRawFrame ?: return listOf("Sin frame: el stream térmico no está activo.")
        val bytes = frame.rawFrameBytes ?: return listOf("Este frame no conserva los bytes originales.")
        return ThermalAnalysis.describeLayout(bytes, frame.rawWidth, frame.rawHeight, frame.fourCc)
    }

    /**
     * Guarda el último frame EXACTAMENTE como lo envió la cámara (.bin) junto con
     * su descripción (.txt), en la carpeta privada de la app:
     * Android/data/com.neosicher.app/files/thermal/ (visible con el Explorador de
     * archivos del dispositivo de Android Studio).
     */
    fun saveRawThermalFrame() {
        val frame = lastRawFrame
        val bytes = frame?.rawFrameBytes
        if (frame == null || bytes == null) {
            _labMessage.value = "No hay frame para guardar: el stream térmico no está activo."
            return
        }
        val description = ThermalAnalysis.describeLayout(bytes, frame.rawWidth, frame.rawHeight, frame.fourCc)
        viewModelScope.launch(Dispatchers.IO) {
            _labMessage.value = try {
                val dir = thermalCaptureDir()
                val stamp = timestampForFile()
                File(dir, "frame_$stamp.bin").writeBytes(bytes)
                File(dir, "frame_$stamp.txt").writeText(description.joinToString("\n"))
                "Guardado en ${dir.absolutePath}/frame_$stamp (.bin y .txt)"
            } catch (t: Throwable) {
                "No se pudo guardar el frame: ${t.message}"
            }
        }
    }

    /** Inicia o detiene la grabación de mediciones a un CSV (una fila por frame). */
    fun toggleThermalRecording() {
        if (!_isRecordingThermal.value) {
            recordingLines.clear()
            recordingLines.add(CSV_HEADER)
            recordingStartMs = SystemClock.elapsedRealtime()
            _isRecordingThermal.value = true
            _labMessage.value = "Grabando mediciones… pulsa de nuevo para detener y guardar."
        } else {
            _isRecordingThermal.value = false
            val lines = ArrayList(recordingLines)
            recordingLines.clear()
            viewModelScope.launch(Dispatchers.IO) {
                _labMessage.value = try {
                    val file = File(thermalCaptureDir(), "mediciones_${timestampForFile()}.csv")
                    file.writeText(lines.joinToString("\n"))
                    "CSV guardado (${lines.size - 1} frames): ${file.absolutePath}"
                } catch (t: Throwable) {
                    "No se pudo guardar el CSV: ${t.message}"
                }
            }
        }
    }

    fun dismissLabMessage() {
        _labMessage.value = null
    }

    private fun appendRecordingLine(m: ThermalMeasurements) {
        if (recordingLines.size > MAX_RECORDING_LINES) return
        val ref = m.reference
        fun f(value: Double) = "%.2f".format(Locale.US, value)
        recordingLines.add(
            listOf(
                SystemClock.elapsedRealtime() - recordingStartMs,
                m.frameCount,
                m.stats.min,
                m.stats.max,
                f(m.stats.mean),
                m.stats.distinctLevels,
                ref.x,
                ref.y,
                f(ref.raw),
                ref.celsius?.let { f(it) } ?: "",
                f(m.hot.raw),
                f(m.cold.raw),
            ).joinToString(",")
        )
    }

    private fun thermalCaptureDir(): File {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val dir = File(base, "thermal")
        dir.mkdirs()
        return dir
    }

    private fun timestampForFile(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    // -- Selector de modo ---------------------------------------------------

    fun selectMode(mode: CameraMode) {
        _uiState.update { it.copy(cameraMode = mode) }
        if (mode == CameraMode.THERMOGRAPHIC) {
            // Al entrar en modo térmico, intentar detectar el GW192A.
            thermalRepository.detect()
        }
    }

    fun toggleMode() {
        val next = if (_uiState.value.cameraMode == CameraMode.ANDROID) {
            CameraMode.THERMOGRAPHIC
        } else {
            CameraMode.ANDROID
        }
        selectMode(next)
    }

    // -- Cámara Android -----------------------------------------------------

    fun onCameraPermissionResult(granted: Boolean) {
        cameraManager.updatePermission(
            if (granted) CameraPermissionState.GRANTED else CameraPermissionState.DENIED
        )
    }

    fun onCameraPermissionPermanentlyDenied() {
        cameraManager.updatePermission(CameraPermissionState.PERMANENTLY_DENIED)
    }

    fun toggleCameraLens(): CameraLens = cameraManager.toggleLens()

    // -- Cámara térmica -----------------------------------------------------

    fun detectThermal() = thermalRepository.detect()

    fun requestThermalPermission() = thermalRepository.requestPermission()

    fun connectThermal() = thermalRepository.connectAndInspect()

    // -- Diagnóstico --------------------------------------------------------

    fun toggleDiagnostics() {
        _uiState.update { it.copy(showDiagnostics = !it.showDiagnostics) }
    }

    // -- Batería ------------------------------------------------------------

    private fun registerBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                _uiState.update { it.copy(batteryPercent = percent, isCharging = charging) }
            }
        }
        val sticky = appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // registerReceiver con un filtro sticky devuelve el último Intent de batería.
        sticky?.let { receiver.onReceive(appContext, it) }
        batteryReceiver = receiver
    }

    override fun onCleared() {
        super.onCleared()
        batteryReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        cameraManager.release()
        thermalRepository.release()
        babyPoseAnalyzer.close()
    }

    companion object {
        /** Frames sobre los que se promedia el valor raw al capturar un punto de calibración. */
        private const val REFERENCE_AVERAGE_FRAMES = 8

        /** Tope de filas en una grabación CSV (evita llenar la memoria). */
        private const val MAX_RECORDING_LINES = 20_000

        private const val CSV_HEADER =
            "t_ms,frame,min,max,media,niveles,ref_x,ref_y,ref_raw,ref_celsius,caliente_raw,frio_raw"
    }
}
