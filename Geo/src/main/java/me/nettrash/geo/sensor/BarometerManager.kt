package me.nettrash.geo.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.nettrash.geo.util.QnhRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * Streams the device's barometer and turns the raw pressure into
 * altitude using a real sea-level reference when available.
 *
 *   * **Calibrated path** — when [QnhRepository] has fetched a QNH
 *     for the user's area, altitude is `SensorManager.getAltitude(
 *     qnhHpa, pressureHpa)` (Android's standard barometric formula
 *     with the right reference pressure). This is the equivalent of
 *     iOS's `CMAltimeter.startAbsoluteAltitudeUpdates`.
 *
 *   * **Uncalibrated fallback** — before the first QNH lands (and
 *     while offline) we use the standard-atmosphere reference
 *     1013.25 hPa. Values are flagged via [hasAbsoluteFix] so UIs
 *     can render a "calibrating…" hint instead of silently showing
 *     a number that could be off by hundreds of metres.
 */
@Singleton
class BarometerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val qnhRepository: QnhRepository
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val _pressure = MutableStateFlow(0.0) // kPa
    val pressure: StateFlow<Double> = _pressure.asStateFlow()

    private val _height = MutableStateFlow(0.0) // metres
    val height: StateFlow<Double> = _height.asStateFlow()

    private val _everest = MutableStateFlow(0.0) // ratio (height / 8848)
    val everest: StateFlow<Double> = _everest.asStateFlow()

    /** True once altitude is being computed against a fetched QNH
     *  rather than the standard-atmosphere fallback. Drives the
     *  "calibrating…" UI hint. Delegated to [QnhRepository] so all
     *  consumers see the same flag flip at the same moment. */
    val hasAbsoluteFix: StateFlow<Boolean> = qnhRepository.hasAbsoluteFix

    val available: Boolean = pressureSensor != null

    var onDataUpdated: (() -> Unit)? = null

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
        // event.values[0] is in hPa (mbar). Keep both representations
        // so consumers that report in kPa (info card, widget) don't
        // need to convert.
        val pressureHpa = event.values[0]
        val pressureKpa = pressureHpa / 10.0
        _pressure.value = pressureKpa

        val qnhHpa = qnhRepository.qnhHpa.value
        val altitude: Double = if (qnhHpa != null) {
            // Calibrated path. SensorManager.getAltitude implements
            // `44 330 * (1 − (p / p0)^(1/5.255))` — the standard
            // barometric formula with proper temperature lapse,
            // which `ln(p0 / p) / 0.00012` approximates badly at
            // altitudes more than a couple of km.
            SensorManager.getAltitude(qnhHpa.toFloat(), pressureHpa).toDouble()
        } else {
            // Uncalibrated fallback — matches the iOS pre-CMAltimeter
            // approximation so the readings don't suddenly stall at
            // zero while QNH is being fetched.
            val p0Kpa = 101.325
            ln(p0Kpa / pressureKpa) / 0.00012
        }
        _height.value = altitude
        _everest.value = altitude / 8848.0

        onDataUpdated?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
