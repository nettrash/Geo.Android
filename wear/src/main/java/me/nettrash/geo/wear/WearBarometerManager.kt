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
 * Wear OS barometer driver — direct port of iOS
 * `Geo Watch App/GeoWatchAppDelegate.swift` barometer logic.
 *
 * Maintains a rolling 20-sample altitude history matching the iOS
 * ContentView graph. Per-sample updates fire at the platform's
 * `SENSOR_DELAY_NORMAL` rate (~5 Hz on most watches); the iOS app
 * runs CMAltimeter at ~1 Hz, so the watch graph here is updated
 * slightly faster but capped to the same 20-sample buffer.
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

        // h = ln(P0/Ph) / 0.00012, where P0 = 101.325 kPa.
        val altitude = ln(101.325 / pressureKpa) / 0.00012
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
