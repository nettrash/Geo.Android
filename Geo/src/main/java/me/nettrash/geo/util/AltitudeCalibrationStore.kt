package me.nettrash.geo.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Persists the manual altitude calibration — the back-solved sea-level
 * reference (QNH, hPa) plus the time it was set — across process death, so
 * an "I am at X m" pin survives a cold start and offline sessions. Mirrors
 * the iOS App Group `BarometerCalibration*` keys.
 */
class AltitudeCalibrationStore(private val context: Context) {

    data class Persisted(val qnhHpa: Double, val calibratedAtMs: Long)

    suspend fun read(): Persisted? {
        val prefs = context.calibrationDataStore.data.first()
        val qnh = prefs[KEY_QNH] ?: return null
        val at = prefs[KEY_AT] ?: return null
        return Persisted(qnh, at)
    }

    suspend fun write(qnhHpa: Double, calibratedAtMs: Long) {
        context.calibrationDataStore.edit { prefs ->
            prefs[KEY_QNH] = qnhHpa
            prefs[KEY_AT] = calibratedAtMs
        }
    }

    suspend fun clear() {
        context.calibrationDataStore.edit { it.clear() }
    }

    companion object {
        private val KEY_QNH = doublePreferencesKey("calibration_qnh_hpa")
        private val KEY_AT = longPreferencesKey("calibration_at_ms")

        private val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "altitude_calibration"
        )
    }
}
