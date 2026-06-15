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

@Singleton
class DeviceMotionManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _heading = MutableStateFlow(0f)
    val heading: StateFlow<Float> = _heading.asStateFlow()

    private val _pitch = MutableStateFlow(0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _roll = MutableStateFlow(0f)
    val roll: StateFlow<Float> = _roll.asStateFlow()

    /** Rotation-vector accuracy as a `SensorManager.SENSOR_STATUS_ACCURACY_*`
     *  value. Starts optimistic (HIGH) so the "calibrate" hint isn't shown
     *  before the first accuracy callback; drops to LOW/UNRELIABLE when the
     *  magnetometer drifts. Drives the compass-calibration hint. */
    private val _headingAccuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    val headingAccuracy: StateFlow<Int> = _headingAccuracy.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    private var isStarted = false

    /**
     * Begin delivering heading/pitch/roll.
     *
     * [samplingPeriodUs] defaults to [SensorManager.SENSOR_DELAY_UI] (~16 Hz),
     * which is smooth for the Info-card peak-bearing arrow while keeping the
     * fused rotation-vector sensor (accelerometer + gyroscope + magnetometer)
     * far cheaper than the previous `SENSOR_DELAY_GAME` (~50 Hz). The AR Nature
     * view, which welds labels to the live camera and wants tighter tracking,
     * opts into the faster rate explicitly.
     */
    fun start(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_UI) {
        if (isStarted) return
        rotationSensor?.let {
            // Track the actual registration result: if it fails, leave
            // isStarted false so a later start() can retry.
            isStarted = sensorManager.registerListener(this, it, samplingPeriodUs)
        }
    }

    fun stop() {
        if (!isStarted) return
        sensorManager.unregisterListener(this)
        isStarted = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationValues)

            // Convert to degrees
            var azimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360f

            // Publish heading quantised to whole degrees: StateFlow drops
            // no-op updates, so sub-degree sensor jitter no longer recomposes
            // the bearing rows every sample. Mirrors iOS `headingFilter = 1°`.
            _heading.value = (Math.round(azimuth) % 360).toFloat()
            _pitch.value = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
            _roll.value = Math.toDegrees(orientationValues[2].toDouble()).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            _headingAccuracy.value = accuracy
        }
    }
}
