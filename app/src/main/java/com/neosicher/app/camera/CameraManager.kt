package com.neosicher.app.camera

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Gestor de la cámara REAL del teléfono usando CameraX.
 *
 * Usa Preview + CameraSelector + ProcessCameraProvider (solución moderna
 * recomendada). NO usa imágenes simuladas: muestra el preview real.
 *
 * La arquitectura queda preparada para añadir análisis de imagen: existe un
 * punto de extensión [imageAnalyzer] que, si se define, vincula un caso de uso
 * ImageAnalysis. Por ahora es null (no se analiza nada en el MVP).
 */
class CameraManager(private val appContext: Context) {

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /**
     * Punto de extensión para análisis futuro (detección/ubicación del bebé,
     * métricas por visión, etc.). Si es null, no se vincula ImageAnalysis.
     * REQUIERE implementación de algoritmos reales antes de producir métricas.
     */
    var imageAnalyzer: ImageAnalysis.Analyzer? = null

    /**
     * Vista de overlay opcional (p. ej. esqueleto de pose del bebé) que se
     * superpone al preview en tiempo real. Si se define, al vincular el preview
     * se añade como VISTA HIJA del PreviewView, de modo que NO hay que tocar
     * CameraPreviewPanel ni el dashboard. Null = sin overlay.
     */
    var previewOverlay: View? = null

    /** Contexto de aplicación, usado por la UI para construir el PreviewView. */
    fun appContextForPreview(): Context = appContext

    fun updatePermission(state: CameraPermissionState) {
        _state.value = _state.value.copy(permission = state)
    }

    /** Alterna entre lente trasera y frontal y devuelve el nuevo estado. */
    fun toggleLens(): CameraLens {
        val next = if (_state.value.lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        _state.value = _state.value.copy(lens = next)
        return next
    }

    /**
     * Vincula el caso de uso Preview (y opcionalmente ImageAnalysis) al ciclo de
     * vida indicado, mostrando el resultado en [previewView].
     *
     * Debe llamarse con el permiso de cámara ya concedido.
     */
    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
    ) {
        if (_state.value.permission != CameraPermissionState.GRANTED) {
            Log.w(TAG, "bindPreview sin permiso de cámara concedido")
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val selector = when (_state.value.lens) {
                    CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                    CameraLens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.unbindAll()

                val analyzer = imageAnalyzer
                if (analyzer != null) {
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, selector, preview)
                }

                // Superponer el overlay (si existe) como vista hija del
                // PreviewView, en tiempo real sobre el preview. No se toca
                // CameraPreviewPanel: el overlay vive dentro del propio
                // contenedor del preview.
                attachOverlay(previewView)

                _state.value = _state.value.copy(isPreviewActive = true, errorMessage = null)
            } catch (t: Throwable) {
                Log.e(TAG, "Error al vincular la cámara", t)
                _state.value = _state.value.copy(
                    isPreviewActive = false,
                    errorMessage = t.message ?: "No se pudo iniciar la cámara",
                )
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /**
     * Añade [previewOverlay] como HERMANO del [previewView] (en su mismo
     * contenedor), por encima de él. NO puede ser hijo del PreviewView: CameraX
     * llama a removeAllViews() sobre él cuando la cámara entrega su superficie
     * (SurfaceViewImplementation/TextureViewImplementation.initializePreview)
     * y borraría el overlay. Idempotente; si el overlay estaba en otro
     * contenedor (p. ej. tras cambiar de modo), lo mueve a este.
     */
    private fun attachOverlay(previewView: PreviewView) {
        val overlay = previewOverlay ?: return
        val host = previewView.parent as? FrameLayout
        if (host == null) {
            Log.w(TAG, "El PreviewView no está dentro de un FrameLayout: no se puede mostrar el overlay")
            return
        }
        if (overlay.parent === host) return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        host.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun unbind() {
        runCatching { cameraProvider?.unbindAll() }
        _state.value = _state.value.copy(isPreviewActive = false)
    }

    fun release() {
        unbind()
        runCatching { analysisExecutor.shutdown() }
    }

    companion object {
        private const val TAG = "NeoCamera"
    }
}
