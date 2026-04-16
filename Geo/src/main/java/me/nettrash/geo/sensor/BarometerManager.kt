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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

@Singleton
class BarometerManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val _pressure = MutableStateFlow(0.0) // kPa
    val pressure: StateFlow<Double> = _pressure.asStateFlow()

    private val _height = MutableStateFlow(0.0) // meters
    val height: StateFlow<Double> = _height.asStateFlow()

    private val _everest = MutableStateFlow(0.0) // ratio
    val everest: StateFlow<Double> = _everest.asStateFlow()

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
        if (event?.sensor?.type == Sensor.TYPE_PRESSURE) {
            // event.values[0] is pressure in hPa (mbar), convert to kPa
            val pressureHpa = event.values[0].toDouble()
            val pressureKpa = pressureHpa / 10.0

            _pressure.value = pressureKpa

            // h = ln(P0 / Ph) / 0.00012
            // P0 = 101.325 kPa (standard sea level)
            val p0 = 101.325
            val h = ln(p0 / pressureKpa) / 0.00012
            _height.value = h
            _everest.value = h / 8848.0

            onDataUpdated?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
