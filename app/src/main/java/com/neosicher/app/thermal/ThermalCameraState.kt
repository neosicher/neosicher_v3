package com.neosicher.app.thermal

import com.neosicher.app.usb.UsbDeviceInfo

/**
 * Estado del ciclo de vida de la cámara térmica GW192A.
 *
 * REGLA: ningún estado afirma que el GW192A esté transmitiendo datos térmicos
 * hasta que se compruebe realmente. El estado READY (dispositivo abierto y
 * endpoints enumerados) NO implica stream. STREAMING solo se alcanza si:
 * (a) los descriptors UVC reales del dispositivo declaran un formato/frame
 * válido (ver docs/GW192A_INVESTIGACION.md §13), y (b) la negociación
 * estándar UVC Probe/Commit fue aceptada por el hardware real. Si cualquiera
 * de las dos condiciones falla, el estado permanece en READY o pasa a ERROR,
 * nunca se simula STREAMING.
 */
enum class ThermalConnectionStatus {
    /** No hay ningún GW192A conectado. */
    NOT_CONNECTED,

    /** Se ha detectado un dispositivo USB y se está comprobando VID/PID. */
    DETECTING,

    /** GW192A detectado (VID/PID coinciden) pero sin permiso ni conexión abierta. */
    CONNECTED,

    /** Se requiere que el usuario conceda el permiso USB para este dispositivo. */
    PERMISSION_REQUIRED,

    /**
     * Conexión USB abierta y descriptors (interfaces/endpoints) enumerados.
     * NO implica stream térmico. Desde aquí se intenta automáticamente leer
     * los descriptors UVC y, si son válidos, iniciar el streaming real.
     */
    READY,

    /**
     * Streaming UVC real negociado y activo con el hardware conectado
     * (Probe/Commit aceptado, frames llegando por el endpoint bulk real).
     * La interpretación del contenido del frame es EXPERIMENTAL (ver
     * ThermalFrameInterpreter): nunca se presenta como temperatura calibrada.
     */
    STREAMING,

    /** Error durante detección, permiso, apertura, enumeración o negociación UVC. */
    ERROR,

    /**
     * El dispositivo/entorno no soporta lo necesario (p. ej. sin USB Host,
     * sin descriptors UVC válidos, o solo endpoint isócrono no soportado).
     * No es un fallo transitorio.
     */
    UNSUPPORTED,
}

/**
 * Estado observable completo de la cámara térmica.
 *
 * @param status estado del ciclo de vida.
 * @param deviceInfo información USB REAL enumerada, o null si aún no se abrió.
 * @param message mensaje legible para la UI (estado en español).
 * @param errorDetail detalle técnico de error, o null.
 */
data class ThermalCameraState(
    val status: ThermalConnectionStatus = ThermalConnectionStatus.NOT_CONNECTED,
    val deviceInfo: UsbDeviceInfo? = null,
    val message: String = "Termográfica no conectada",
    val errorDetail: String? = null,
    /** Último frame interpretado (solo cuando status == STREAMING). EXPERIMENTAL. */
    val lastFrame: ThermalFrameResult? = null,
) {
    val isDeviceKnown: Boolean get() = deviceInfo != null

    companion object {
        val NOT_CONNECTED = ThermalCameraState()
    }
}
