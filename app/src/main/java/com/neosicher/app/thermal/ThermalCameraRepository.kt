package com.neosicher.app.thermal

import com.neosicher.app.monitoring.ThermalReading
import kotlinx.coroutines.flow.StateFlow

/**
 * Punto de acceso único a la cámara térmica para las capas superiores (ViewModel).
 *
 * Desacopla la UI de la implementación concreta ([Gw192aThermalCameraDataSource]).
 * Si en el futuro cambia la fuente (otra cámara, simulador de pruebas), solo se
 * sustituye el [ThermalCameraDataSource] inyectado.
 */
class ThermalCameraRepository(
    private val dataSource: ThermalCameraDataSource,
) {
    val state: StateFlow<ThermalCameraState> get() = dataSource.state
    val reading: StateFlow<ThermalReading> get() = dataSource.reading

    /** Detecta el dispositivo y comprueba VID/PID. */
    fun detect() = dataSource.detect()

    /** Solicita el permiso USB si hace falta. */
    fun requestPermission() = dataSource.requestPermission()

    /** Abre y enumera (máximo READY). No inicia stream. */
    fun connectAndInspect() = dataSource.connectAndInspect()

    fun release() = dataSource.release()
}
