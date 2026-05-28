package me.nettrash.geo.util

import android.location.Location
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Provides the current sea-level (QNH-equivalent) air pressure for
 * the user's location.
 *
 * Why this exists
 * ---------------
 * iOS gets calibrated altitude for free via
 * `CMAltimeter.startAbsoluteAltitudeUpdates`, which Apple calibrates
 * against location-tagged sea-level pressure. Android has no
 * equivalent. The naive `SensorManager.getAltitude(1013.25, p)`
 * (i.e. standard atmosphere as the reference) is biased by 100–500 m
 * in real weather. Fetching a real QNH lets us hand
 * `SensorManager.getAltitude(qnh, p)` something accurate.
 *
 * Source
 * ------
 * Open-Meteo's `pressure_msl` (mean-sea-level pressure, hPa) —
 * free, no key, generous quotas. The brief mentioned
 * `surface_pressure` but that's "pressure at the local elevation"
 * (i.e. QFE-ish); `pressure_msl` is the actual reduced-to-sea-level
 * value `getAltitude` wants.
 *
 * Refresh policy
 * --------------
 *   * Refresh on first location.
 *   * Refresh when user moves more than [refreshDistanceMeters].
 *   * Refresh after [refreshIntervalMs] regardless.
 *
 * Privacy
 * -------
 * Latitude / longitude are quantised to 3 decimal places (~110 m
 * grid at the equator) before being sent — the QNH field varies
 * over kilometre-scale distances anyway, so we're not losing any
 * accuracy in exchange for not handing the third-party API the
 * device's exact position.
 */
@Singleton
class QnhRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /** Latest QNH (hPa) for the user's location. `null` until the
     *  first successful fetch; consumers should treat this as
     *  "calibrating, not yet calibrated" until non-null. */
    private val _qnhHpa = MutableStateFlow<Double?>(null)
    val qnhHpa: StateFlow<Double?> = _qnhHpa.asStateFlow()

    /** Convenience: true once QNH has been fetched at least once. */
    private val _hasAbsoluteFix = MutableStateFlow(false)
    val hasAbsoluteFix: StateFlow<Boolean> = _hasAbsoluteFix.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchLock = Mutex()

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Distance threshold for triggering a refresh. */
    private val refreshDistanceMeters = 5_000.0

    /** Time-based refresh interval (ms). */
    private val refreshIntervalMs: Long = 30 * 60 * 1000L

    private var lastFetchLocation: Location? = null
    private var lastFetchAtMs: Long = 0L

    /**
     * Called from [me.nettrash.geo.location.LocationManager] every
     * time a fresh fix arrives. Idempotent — fast no-op when neither
     * the distance nor the time threshold has been crossed.
     */
    fun maybeRefresh(location: Location) {
        val now = System.currentTimeMillis()
        val last = lastFetchLocation
        val needsByDistance = last == null || last.distanceTo(location) > refreshDistanceMeters
        val needsByTime = now - lastFetchAtMs > refreshIntervalMs
        if (!needsByDistance && !needsByTime) return

        scope.launch {
            fetchLock.withLock {
                // Re-check under the lock so two near-simultaneous
                // updates don't fire two requests.
                val nowInner = System.currentTimeMillis()
                val lastInner = lastFetchLocation
                if (lastInner != null &&
                    lastInner.distanceTo(location) <= refreshDistanceMeters &&
                    nowInner - lastFetchAtMs <= refreshIntervalMs
                ) return@withLock

                val qnh = fetch(location.latitude, location.longitude)
                if (qnh != null) {
                    _qnhHpa.value = qnh
                    _hasAbsoluteFix.value = true
                    lastFetchLocation = Location(location)
                    lastFetchAtMs = nowInner
                    AppLog.barometer.info(
                        "QNH refreshed: ${"%.2f".format(qnh)} hPa @ " +
                            "(${"%.3f".format(location.latitude)}, " +
                            "${"%.3f".format(location.longitude)})"
                    )
                }
            }
        }
    }

    private suspend fun fetch(lat: Double, lon: Double): Double? = withContext(Dispatchers.IO) {
        // 3-decimal quantisation — privacy + cache-friendliness.
        val qLat = Math.round(lat * 1000.0) / 1000.0
        val qLon = Math.round(lon * 1000.0) / 1000.0

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$qLat&longitude=$qLon&current=pressure_msl"
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.barometer.warn("QNH HTTP ${resp.code}")
                    return@withContext null
                }
                val text = resp.body.string()
                val parsed = json.decodeFromString<OpenMeteoResponse>(text)
                val value = parsed.current?.pressureMsl
                if (value == null || value <= 800 || value >= 1100) {
                    // Sanity-check: real QNH is between ~870 and
                    // ~1085 hPa. Anything outside is bad data.
                    AppLog.barometer.warn("QNH out of plausible range: $value")
                    return@withContext null
                }
                value
            }
        } catch (t: Throwable) {
            AppLog.barometer.warn("QNH fetch failed", t)
            null
        }
    }
}

@Serializable
private data class OpenMeteoResponse(
    val current: Current? = null
) {
    @Serializable
    data class Current(
        @kotlinx.serialization.SerialName("pressure_msl")
        val pressureMsl: Double? = null
    )
}
