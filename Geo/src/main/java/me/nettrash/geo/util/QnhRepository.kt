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
import me.nettrash.geo.ar.TerrainElevationService
import me.nettrash.geo.sensor.Atmosphere
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

    // ── Manual "I am at X m" calibration (M5b) ───────────────────

    /** A manual calibration: the back-solved sea-level reference (QNH, hPa)
     *  and the time it was set. Its influence decays to zero over
     *  [me.nettrash.geo.sensor.Atmosphere.CALIBRATION_DECAY_HOURS]. */
    data class Calibration(val qnhHpa: Double, val calibratedAtMs: Long)

    private val calStore = me.nettrash.geo.util.AltitudeCalibrationStore(context)
    private val _calibration = MutableStateFlow<Calibration?>(null)
    val calibration: StateFlow<Calibration?> = _calibration.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchLock = Mutex()
    /** Serializes calibration persistence so a rapid calibrate→clear can't
     *  land out of order (see [persistCalibration]). */
    private val persistMutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor(minIntervalMs = 200))
        .build()

    /** Durable cache of the last fetched QNH so cold starts and the
     *  background worker have a real calibration reference before any
     *  network call completes. */
    private val store = QnhStore(context)

    /** Distance threshold for triggering a refresh. */
    private val refreshDistanceMeters = 5_000.0

    /** Time-based refresh interval (ms). */
    private val refreshIntervalMs: Long = 30 * 60 * 1000L

    /** A persisted QNH older than this is treated as stale and not used
     *  to seed [qnhHpa]/[hasAbsoluteFix] on init — weather can drift
     *  enough over several hours that the standard atmosphere is no
     *  worse than a very old reading. */
    private val staleAfterMs: Long = 6 * 60 * 60 * 1000L

    private var lastFetchLocation: Location? = null
    private var lastFetchAtMs: Long = 0L

    init {
        // Restore a persisted manual calibration so an "I am at X m" pin
        // survives a cold start / offline session. Seed atomically and only
        // while still null: if the user sets a fresh calibration during this
        // async DataStore read, compareAndSet leaves the newer value untouched
        // instead of clobbering it with the older persisted one (mirrors the
        // _qnhHpa seed guard below).
        scope.launch {
            calStore.read()?.let {
                _calibration.compareAndSet(null, Calibration(it.qnhHpa, it.calibratedAtMs))
            }
        }

        // Seed in-memory state from the last persisted fetch (with a
        // staleness check) so the very first barometer sample after a
        // cold start, and every offline session, can already calibrate.
        scope.launch {
            val persisted = store.read() ?: return@launch
            val age = System.currentTimeMillis() - persisted.fetchAtMs
            if (age in 0..staleAfterMs) {
                fetchLock.withLock {
                    // A concurrent fresh fetch may have already landed;
                    // only seed if we're still uncalibrated.
                    if (_qnhHpa.value == null) {
                        _qnhHpa.value = persisted.qnhHpa
                        _hasAbsoluteFix.value = true
                        lastFetchLocation = Location("qnhStore").apply {
                            latitude = persisted.lat
                            longitude = persisted.lon
                        }
                        lastFetchAtMs = persisted.fetchAtMs
                    }
                }
            }
        }
    }

    /**
     * Last-known QNH (hPa) for background consumers that can't observe
     * the [qnhHpa] flow over time (e.g. [me.nettrash.geo.worker.BarometerRefreshWorker]).
     * Returns the in-memory value if seeded, otherwise reads through to
     * the persisted store, applying the same staleness check.
     */
    suspend fun lastKnownQnhHpa(): Double? {
        _qnhHpa.value?.let { return it }
        val persisted = store.read() ?: return null
        val age = System.currentTimeMillis() - persisted.fetchAtMs
        return if (age in 0..staleAfterMs) persisted.qnhHpa else null
    }

    /** Back-solve the QNH from a known elevation + the live RAW station
     *  pressure (kPa) and persist it as a manual calibration. */
    fun calibrate(knownAltitudeM: Double, livePressureKpa: Double) {
        val qnhHpa = Atmosphere.solveReferencePressure(knownAltitudeM, livePressureKpa) * 10.0
        val now = System.currentTimeMillis()
        _calibration.value = Calibration(qnhHpa, now)
        persistCalibration()
    }

    fun clearCalibration() {
        _calibration.value = null
        persistCalibration()
    }

    /**
     * Persist whatever [_calibration] currently holds, serialized under
     * [persistMutex]. The job reads the latest in-memory value at execution
     * time rather than capturing it at call time, so even if a rapid
     * calibrate→clear (or vice versa) dispatches the two jobs out of order,
     * the last one to run writes the final state and the user's last action
     * deterministically wins — no stale calibration is left on disk for
     * [effectiveQnhHpaNow]/init to reload.
     */
    private fun persistCalibration() {
        scope.launch {
            persistMutex.withLock {
                when (val cal = _calibration.value) {
                    null -> calStore.clear()
                    else -> calStore.write(cal.qnhHpa, cal.calibratedAtMs)
                }
            }
        }
    }

    /** True while a manual calibration is set and not yet fully decayed. */
    fun isCalibrated(nowMs: Long): Boolean {
        val cal = _calibration.value ?: return false
        return Atmosphere.calibrationWeight((nowMs - cal.calibratedAtMs) / 1000.0) > 0.0
    }

    /** Calibration-aware effective QNH (hPa) for the foreground path: the
     *  manual QNH decaying toward the network QNH (or standard) over the
     *  decay window, or the plain network QNH when uncalibrated/expired.
     *  `null` ⇒ no reference at all (caller uses the lapse fallback). */
    fun effectiveQnhHpa(nowMs: Long): Double? = blendCalibration(_qnhHpa.value, nowMs)

    /** Suspend variant for the background worker: reads the calibration from
     *  the store if the in-memory value hasn't seeded yet (fresh process). */
    suspend fun effectiveQnhHpaNow(nowMs: Long): Double? {
        if (_calibration.value == null) {
            calStore.read()?.let { _calibration.value = Calibration(it.qnhHpa, it.calibratedAtMs) }
        }
        return blendCalibration(lastKnownQnhHpa(), nowMs)
    }

    private fun blendCalibration(networkHpa: Double?, nowMs: Long): Double? {
        val cal = _calibration.value ?: return networkHpa
        val weight = Atmosphere.calibrationWeight((nowMs - cal.calibratedAtMs) / 1000.0)
        if (weight <= 0.0) return networkHpa
        val baseline = networkHpa ?: (Atmosphere.SEA_LEVEL_KPA * 10.0)   // 1013.25 hPa
        return baseline + (cal.qnhHpa - baseline) * weight
    }

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
                    // Write through so cold starts / the worker can reuse
                    // this reference without a fresh network round-trip.
                    store.write(qnh, nowInner, location.latitude, location.longitude)
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
        // Quantise through the shared ~110 m privacy grid so all three
        // network clients (Overpass, Open-Elevation, Open-Meteo/QNH)
        // use identical grid math — privacy + cache-friendliness.
        val qLat = TerrainElevationService.quantise(lat)
        val qLon = TerrainElevationService.quantise(lon)

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$qLat&longitude=$qLon&current=pressure_msl"
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
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

    private companion object {
        /** Descriptive, app-identifying User-Agent so the third-party
         *  API operator can attribute / contact us rather than seeing
         *  anonymous library traffic. */
        const val USER_AGENT = "me.nettrash.Geo/1.0 (+https://nettrash.me)"
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
