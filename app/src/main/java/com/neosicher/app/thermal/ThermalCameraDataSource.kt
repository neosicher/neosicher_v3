package com.neosicher.app.thermal

import com.neosicher.app.monitoring.ThermalReading
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstracción de una fuente de datos de cámara térmica.
 *
 * Permite sustituir la implementación (GW192A por USB, un simulador de pruebas,
 * u otra cámara futura) sin acoplar la UI ni el repositorio a un dispositivo
 * concreto. Ninguna implementación debe afirmar que hay datos térmicos reales
 * hasta comprobarlo.
 */
interface ThermalCameraDataSource {

    /** Estado observable del ciclo de vida de la cámara térmica. */
    val state: StateFlow<ThermalCameraState>

    /** Última lectura térmica observable. Por defecto: sin lectura. */
    val reading: StateFlow<ThermalReading>

    /**
     * Intenta detectar el dispositivo y comprobar VID/PID.
     * Actualiza [state] a DETECTING y luego a CONNECTED / NOT_CONNECTED / etc.
     */
    fun detect()

    /**
     * Solicita el permiso USB si es necesario. El resultado se refleja en [state].
     */
    fun requestPermission()

    /**
     * Abre la conexión y enumera interfaces/endpoints (máximo READY en este MVP).
     * NO inicia ningún stream térmico.
     */
    fun connectAndInspect()

    /** Libera recursos (receivers, conexiones). */
    fun release()
}
