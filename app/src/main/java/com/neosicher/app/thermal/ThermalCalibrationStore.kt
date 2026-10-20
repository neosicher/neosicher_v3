package com.neosicher.app.thermal

import android.content.Context

/**
 * Guarda y recupera la calibración del usuario en el almacenamiento privado de
 * la app (SharedPreferences), para no tener que recalibrar cada vez que se abre.
 *
 * Solo persiste lo que el usuario capturó (raw + temperatura de referencia +
 * si fue un supuesto). No guarda ni inventa nada más. Los números se guardan
 * como texto para no perder precisión.
 */
class ThermalCalibrationStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Calibración guardada, o una vacía si nunca se guardó nada. */
    fun load(): ThermalCalibration = ThermalCalibration(
        coldPoint = readPoint("cold", "Frío conocido"),
        hotPoint = readPoint("hot", "Caliente conocido"),
        bodyPoint = readPoint("body", "Corporal"),
    )

    fun save(calibration: ThermalCalibration) {
        val editor = prefs.edit()
        writePoint(editor, "cold", calibration.coldPoint)
        writePoint(editor, "hot", calibration.hotPoint)
        writePoint(editor, "body", calibration.bodyPoint)
        editor.apply()
    }

    private fun readPoint(key: String, label: String): ThermalCalibrationPoint? {
        val raw = prefs.getString("${key}_raw", null)?.toDoubleOrNull() ?: return null
        val known = prefs.getString("${key}_known", null)?.toDoubleOrNull() ?: return null
        val assumed = prefs.getBoolean("${key}_assumed", false)
        return ThermalCalibrationPoint(label, raw, known, assumed)
    }

    private fun writePoint(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        point: ThermalCalibrationPoint?,
    ) {
        if (point == null) {
            editor.remove("${key}_raw").remove("${key}_known").remove("${key}_assumed")
        } else {
            editor.putString("${key}_raw", point.rawValue.toString())
            editor.putString("${key}_known", point.knownTemperatureCelsius.toString())
            editor.putBoolean("${key}_assumed", point.isAssumed)
        }
    }

    companion object {
        private const val PREFS_NAME = "thermal_calibration"
    }
}
