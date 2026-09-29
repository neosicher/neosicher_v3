package com.neosicher.app.camera

/**
 * Estado de la cámara del teléfono (Android / CameraX).
 *
 * Independiente del estado de la cámara térmica USB.
 */
enum class CameraPermissionState {
    UNKNOWN,
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
}

/** Lente activa de la cámara del teléfono. */
enum class CameraLens {
    BACK,
    FRONT,
}

/**
 * Estado observable de la cámara Android.
 *
 * @param permission estado del permiso android.permission.CAMERA.
 * @param lens lente seleccionada actualmente.
 * @param isPreviewActive true cuando CameraX ha vinculado el Preview correctamente.
 * @param errorMessage mensaje de error legible, o null si no hay error.
 */
data class CameraState(
    val permission: CameraPermissionState = CameraPermissionState.UNKNOWN,
    val lens: CameraLens = CameraLens.BACK,
    val isPreviewActive: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasError: Boolean get() = errorMessage != null
    val canShowPreview: Boolean get() = permission == CameraPermissionState.GRANTED
}
