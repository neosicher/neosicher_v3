package com.neosicher.app.thermal

import com.neosicher.app.usb.UsbDeviceInfo

/**
 * Estado del ciclo de vida de la cámara térmica GW192A.
 *
 * REGLA: ningún estado afirma que el GW192A esté transmitiendo datos térmicos
 * hasta que se compruebe realmente. En este MVP el flujo real llega, como máximo,
 * hasta READY (dispositivo abierto y endpoints enumerados). Los estados
 * posteriores (STREAMING) existen en el modelo pero NO se alcanzan todavía porque
 * el protocolo no está determinado (ver docs/GW192A_INVESTIGACION.md).
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
     * Máximo estado alcanzable en este MVP. NO implica stream térmico.
     */
    READY,

    /**
     * Transmitiendo frames térmicos. REQUIERE PRUEBA EN POCO F7 + GW192A y un
     * protocolo confirmado. NO se activa en este MVP.
     */
    STREAMING,

    /** Error durante detección, permiso, apertura o enumeración. */
    ERROR,

    /**
     * El dispositivo/entorno no soporta lo necesario (p. ej. sin USB Host,
     * o descriptors incompatibles). No es un fallo transitorio.
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
) {
    val isDeviceKnown: Boolean get() = deviceInfo != null

    companion object {
        val NOT_CONNECTED = ThermalCameraState()
    }
}
