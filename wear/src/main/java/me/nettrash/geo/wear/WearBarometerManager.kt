package me.nettrash.geo.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ln

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
 *                  + ln(phoneRefPressure / currentWatchPressure)
 *                    / 0.00012
 *
 * The phone publishes its `(barPreassure, barAltitude)` over the
 * Wearable Data Layer via [WearSnapshotStore.token]; we treat the
 * last token we received as a reference point and shift from there
 * using the standard barometric formula. The 0.00012 constant is
 * fine for the *delta* — it only goes wrong as an absolute base.
 *
 * Until the first phone snapshot arrives we fall back to standard
 * atmosphere (101.325 kPa as the reference) and flag the readings
 * as uncalibrated via [hasAbsoluteFix].
 */
class WearBarometerManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

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

    fun start() {
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_PRESSURE) return
        val pressureKpa = (event.values[0] / 10.0)  // hPa → kPa
        _pressure.value = pressureKpa

        val token = WearSnapshotStore.token.value
        // `barPreassure` is intentionally misspelled — that's the
        // on-wire field name on iOS, so we carry it through here so
        // the same JSON payload round-trips between platforms.
        val altitude: Double = if (token != null && token.barPreassure > 0) {
            // Calibrated against phone reference.
            _hasAbsoluteFix.value = true
            token.barAltitude +
                ln(token.barPreassure / pressureKpa) / 0.00012
        } else {
            // Standard atmosphere fallback.
            ln(101.325 / pressureKpa) / 0.00012
        }

        _altitude.value = altitude
        _everest.value = altitude / 8848.0

        val now = System.currentTimeMillis()
        if (now - lastHistoryUpdateMs >= historyIntervalMs) {
            lastHistoryUpdateMs = now
            val next = (_history.value + altitude).takeLast(historyCapacity)
            _history.value = next
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
