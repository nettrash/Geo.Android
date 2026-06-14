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
 * Persists the most recently fetched QNH (sea-level reference pressure)
 * across process death so that cold launches and background worker
 * ticks start from a real calibration reference instead of the
 * 1013.25 hPa standard atmosphere.
 *
 * Why this exists
 * ---------------
 * [QnhRepository] held the fetched QNH purely in memory, so every
 * fresh process (app cold start, [me.nettrash.geo.worker.BarometerRefreshWorker]
 * tick) re-started uncalibrated, and offline users never calibrated at
 * all. QNH varies on a kilometre/hour scale, so a cached value from
 * minutes ago is a far better reference than the standard atmosphere.
 *
 * iOS gets calibrated absolute altitude on-device from
 * `CMAltimeter.startAbsoluteAltitudeUpdates` with no per-launch network
 * round-trip; this store is Android's equivalent durable reference.
 *
 * We store the value, the fetch timestamp (for the staleness check) and
 * the fetch lat/lon (so the repository can decide whether the cached
 * value is still close enough to reuse for distance-based refresh).
 */
class QnhStore(private val context: Context) {

    /** Snapshot of the persisted calibration reference. `null` value
     *  means nothing has ever been persisted. */
    data class Persisted(
        val qnhHpa: Double,
        val fetchAtMs: Long,
        val lat: Double,
        val lon: Double
    )

    suspend fun read(): Persisted? {
        val prefs = context.qnhDataStore.data.first()
        val qnh = prefs[KEY_QNH] ?: return null
        return Persisted(
            qnhHpa = qnh,
            fetchAtMs = prefs[KEY_FETCH_AT] ?: 0L,
            lat = prefs[KEY_LAT] ?: 0.0,
            lon = prefs[KEY_LON] ?: 0.0
        )
    }

    suspend fun write(qnhHpa: Double, fetchAtMs: Long, lat: Double, lon: Double) {
        context.qnhDataStore.edit { prefs ->
            prefs[KEY_QNH] = qnhHpa
            prefs[KEY_FETCH_AT] = fetchAtMs
            prefs[KEY_LAT] = lat
            prefs[KEY_LON] = lon
        }
    }

    companion object {
        private val KEY_QNH = doublePreferencesKey("qnh_hpa")
        private val KEY_FETCH_AT = longPreferencesKey("qnh_fetch_at_ms")
        private val KEY_LAT = doublePreferencesKey("qnh_fetch_lat")
        private val KEY_LON = doublePreferencesKey("qnh_fetch_lon")

        private val Context.qnhDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "qnh_calibration"
        )
    }
}
