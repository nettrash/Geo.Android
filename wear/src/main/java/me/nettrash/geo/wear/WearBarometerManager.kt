package me.nettrash.geo.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wear OS barometer driver.
 *
 * Watch altitude is calibrated against the *paired phone's* most
 * recent calibrated reading rather than against standard atmosphere
 * (mirrors the iOS Watch's reliance on the phone for absolute
 * pressure context — the iPhone has internet, the Watch generally
 * doesn't):
 *
 *   altitude_watch = phoneRefAlt
 *                  + (Atmosphere.altitude(currentWatchPressure)
 *                     - Atmosphere.altitude(phoneRefPressure))
 *
 * The phone publishes its `(barPreassure, barAltitude)` over the
 * Wearable Data Layer via [WearSnapshotStore.token]; we treat the
 * last token we received as a reference point and shift from there
 * using the lapse-rate barometric formula ([Atmosphere.altitude]),
 * which is well-behaved both as an absolute base and as a delta.
 *
 * Until the first phone snapshot arrives we fall back to standard
 * atmosphere (101.325 kPa as the reference) and flag the readings
 * as uncalibrated via [hasAbsoluteFix].
 */
class WearBarometerManager(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    /** Watch->phone backfill bridge (mirrors iOS WatchConnectivity). */
    private val outbound = WearOutboundBridge(appContext)

    private val _pressure = MutableStateFlow(0.0)
    val pressure: StateFlow<Double> = _pressure.asStateFlow()

    private val _altitude = MutableStateFlow(0.0)
    val altitude: StateFlow<Double> = _altitude.asStateFlow()

    private val _everest = MutableStateFlow(0.0)
    val everest: StateFlow<Double> = _everest.asStateFlow()

    private val _history = MutableStateFlow<List<Double>>(emptyList())
    val history: StateFlow<List<Double>> = _history.asStateFlow()

    /** True once we've ever received a phone snapshot, so the
     *  calibrated formula above can run. */
    private val _hasAbsoluteFix = MutableStateFlow(false)
    val hasAbsoluteFix: StateFlow<Boolean> = _hasAbsoluteFix.asStateFlow()

    /** Time of last graph-history update; throttled to 30 s per iOS. */
    private var lastHistoryUpdateMs: Long = 0L
    private val historyIntervalMs = 30_000L
    private val historyCapacity = 20

    /** Last token written to persistence; avoids re-encoding the same
     *  reference on every tick. */
    private var lastPersistedToken: WearInformationToken? = null

    fun start() {
        // Rehydrate the last calibration token and altitude sparkline
        // persisted before the previous process death (Wear OS kills
        // the app routinely). Mirrors iOS restoreFromSharedStorage() so
        // the very first tick is calibrated and the graph isn't empty.
        restorePersistedState()
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun restorePersistedState() {
        // Only seed the calibration token if a fresher one hasn't
        // already been pushed by the phone this process.
        if (WearSnapshotStore.token.value == null) {
            WearPersistStore.readToken(appContext)?.let { token ->
                WearSnapshotStore.update(token)
                _hasAbsoluteFix.value = token.barPreassure > 0
            }
        }
        if (_history.value.isEmpty()) {
            val restored = WearPersistStore.readHistory(appContext)
            if (restored.isNotEmpty()) {
                _history.value = restored.takeLast(historyCapacity)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_PRESSURE) return
        // Clamp the live sample to a plausible barometric range (#11)
        // so a spurious reading can't poison altitude/history.
        val pressureKpa = (event.values[0] / 10.0).coerceIn(30.0, 110.0)  // hPa → kPa
        _pressure.value = pressureKpa

        val token = WearSnapshotStore.token.value
        // `barPreassure` is intentionally misspelled — that's the
        // on-wire field name on iOS, so we carry it through here so
        // the same JSON payload round-trips between platforms.
        val altitude: Double = if (token != null && token.barPreassure > 0) {
            // Calibrated against phone reference.
            _hasAbsoluteFix.value = true
            // Persist the calibration reference so a relaunched process
            // is calibrated from its first tick instead of falling back
            // to standard atmosphere until the phone re-pushes.
            if (token !== lastPersistedToken) {
                lastPersistedToken = token
                WearPersistStore.writeToken(appContext, token)
            }
            token.barAltitude +
                (Atmosphere.altitude(pressureKpa) - Atmosphere.altitude(token.barPreassure))
        } else {
            // Standard atmosphere fallback (lapse-rate, #10).
            Atmosphere.altitude(pressureKpa)
        }

        _altitude.value = altitude
        _everest.value = altitude / Atmosphere.EVEREST_HEIGHT_M

        val now = System.currentTimeMillis()
        if (now - lastHistoryUpdateMs >= historyIntervalMs) {
            lastHistoryUpdateMs = now
            val next = (_history.value + altitude).takeLast(historyCapacity)
            _history.value = next
            // Persist the sparkline so it survives a process kill.
            WearPersistStore.writeHistory(appContext, next, now)
        }

        // Stream this sample to the phone so it can backfill history,
        // mirroring iOS WatchConnectivity. The bridge throttles
        // internally (Δp<0.1 kPa && Δt<30 s), so calling on every tick
        // is fine. We forward the phone's last-known GPS context so the
        // persisted HistoryItem carries best-effort coordinates.
        val outboundToken = WearInformationToken(
            recordDate = now,
            gpsAltitude = token?.gpsAltitude ?: 0.0,
            gpsSpeed = token?.gpsSpeed ?: 0.0,
            barPreassure = pressureKpa,
            barAltitude = altitude,
            gpsLatitude = token?.gpsLatitude ?: 0.0,
            gpsLongitude = token?.gpsLongitude ?: 0.0
        )
        outbound.offer(outboundToken)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
