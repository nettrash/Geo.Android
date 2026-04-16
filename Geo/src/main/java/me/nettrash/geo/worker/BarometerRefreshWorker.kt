package me.nettrash.geo.worker

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nettrash.geo.data.model.InformationToken
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.ln

@HiltWorker
class BarometerRefreshWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return Result.success()

        // Get a single barometer reading
        val pressureHpa = withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine<Float> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event?.sensor?.type == Sensor.TYPE_PRESSURE) {
                            sensorManager.unregisterListener(this)
                            cont.resume(event.values[0])
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        } ?: return Result.success()

        val pressureKpa = pressureHpa / 10.0
        val altitude = ln(101.325 / pressureKpa) / 0.00012

        // Read previous data to preserve GPS values
        val prefs = context.getSharedPreferences("geo_widget", Context.MODE_PRIVATE)
        val previousJson = prefs.getString("actual_information", null)
        val previous = previousJson?.let {
            try { Json.decodeFromString<InformationToken>(it) } catch (e: Exception) { null }
        }

        val token = InformationToken(
            recordDate = System.currentTimeMillis(),
            gpsAltitude = previous?.gpsAltitude ?: 0.0,
            gpsSpeed = previous?.gpsSpeed ?: 0.0,
            barPressure = pressureKpa,
            barAltitude = altitude
        )

        prefs.edit()
            .putString("actual_information", Json.encodeToString(token))
            .apply()

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "barometer_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BarometerRefreshWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
