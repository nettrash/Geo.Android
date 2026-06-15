package me.nettrash.geo.worker

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.nettrash.geo.data.model.InformationToken
import me.nettrash.geo.data.repository.HistoryRepository
import me.nettrash.geo.data.snapshot.SharedSnapshotStore
import me.nettrash.geo.notification.StormNotifier
import me.nettrash.geo.sensor.Atmosphere
import me.nettrash.geo.sensor.PressureSample
import me.nettrash.geo.sensor.PressureTrendClass
import me.nettrash.geo.sensor.StormWarning
import me.nettrash.geo.util.AppLog
import me.nettrash.geo.util.QnhRepository
import me.nettrash.geo.util.StormWarningStore
import me.nettrash.geo.widget.GeoWidget
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Periodic barometer-only sample, mirroring iOS
 * `GeoAppDelegate.captureBarometerSampleAndPersist`. Runs every 15 min
 * via WorkManager and writes the result through [SharedSnapshotStore]
 * so the widget can read it AND the main app can backfill the buffer
 * into Room on next launch.
 */
@HiltWorker
class BarometerRefreshWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val qnhRepository: QnhRepository,
    private val historyRepository: HistoryRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppLog.background.debug("BarometerRefreshWorker start")
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
            ?: run {
                AppLog.background.info("No barometer sensor; worker is a no-op")
                return Result.success()
            }

        // Wait up to 5 s for a single sample.
        val pressureHpa = withTimeoutOrNull(5_000L) {
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
                sensorManager.registerListener(
                    listener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL
                )
                cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        } ?: run {
            AppLog.background.warn("Barometer did not deliver a sample within 5 s")
            return Result.success()
        }

        // Clamp the sample to a plausible barometric range (#11) so a
        // spurious reading can't poison the persisted altitude/history.
        val pressureKpa = (pressureHpa / 10.0).coerceIn(30.0, 110.0)
        // Mirror the foreground BarometerManager: prefer a calibrated
        // altitude against a real (persisted/last-known) QNH and only
        // fall back to the standard-atmosphere reference when none is
        // available. This keeps background/widget/history altitude in
        // agreement with the app and with iOS's calibrated path.
        // Calibration-aware QNH (manual "I am at X m" pin if set, else the
        // last-known network QNH) so the widget/history altitude matches the
        // calibrated foreground readout.
        val qnhHpa = qnhRepository.effectiveQnhHpaNow(System.currentTimeMillis())
        val altitude: Double = if (qnhHpa != null) {
            SensorManager.getAltitude(qnhHpa.toFloat(), (pressureKpa * 10.0).toFloat()).toDouble()
        } else {
            Atmosphere.altitude(pressureKpa)
        }

        // Preserve last-known GPS state from the buffered current
        // snapshot so the widget shows complete data after this
        // background tick. Mirrors iOS's "merge previous GPS context"
        // logic in `captureBarometerSampleAndPersist`.
        val previous = SharedSnapshotStore.readCurrent(context)
        val token = InformationToken(
            recordDate = System.currentTimeMillis(),
            gpsAltitude  = previous?.gpsAltitude ?: 0.0,
            gpsSpeed     = previous?.gpsSpeed ?: 0.0,
            barPreassure = pressureKpa,
            barAltitude  = altitude,
            gpsLatitude  = previous?.gpsLatitude ?: 0.0,
            gpsLongitude = previous?.gpsLongitude ?: 0.0
        )
        SharedSnapshotStore.write(context, token)

        // Pressure-trend storm warning (M5a): classify the de-trended
        // 3-hour tendency and, if it crosses the storm threshold, post a
        // local notification. Failure-tolerant — never fails the tick.
        runCatching { evaluateStormWarning(token) }
            .onFailure { AppLog.background.warn("Storm-warning evaluation failed", it) }

        // Nudge the widget so the next render cycle picks up the
        // freshest sample. Skipping if no widget is pinned avoids
        // gratuitous IO on devices that don't use the widget.
        try {
            val manager = GlanceAppWidgetManager(context)
            if (manager.getGlanceIds(GeoWidget::class.java).isNotEmpty()) {
                GeoWidget().updateAll(context)
            }
        } catch (t: Throwable) {
            AppLog.widget.warn("Worker widget update failed", t)
        }

        AppLog.background.debug("BarometerRefreshWorker done")
        return Result.success()
    }

    /**
     * Run the de-trended 3-hour pressure-tendency fit and deliver a
     * cooldown-gated storm notification on a fast fall. Feeds the fit the
     * not-yet-drained buffered snapshots plus the freshest sample so the
     * window is complete even when the foreground app hasn't run recently
     * to backfill Room. Recovery to steady/rising resets the cooldown so
     * a genuinely new storm can alert promptly.
     */
    private suspend fun evaluateStormWarning(latest: InformationToken) {
        val nowMs = System.currentTimeMillis()
        val windowStart = nowMs - (StormWarning.WINDOW_HOURS * 3_600_000.0).toLong()
        val extra = (SharedSnapshotStore.readBuffer(context) + latest)
            .filter { it.recordDate in windowStart..nowMs && it.barPreassure > 0 }
            .map { PressureSample(it.recordDate, it.barPreassure, it.gpsAltitude) }

        val trend = historyRepository.pressureTendency(extra, nowMs)
        val store = StormWarningStore(context)

        when (trend.classification) {
            PressureTrendClass.STEADY, PressureTrendClass.RISING -> store.clear()
            PressureTrendClass.UNKNOWN, PressureTrendClass.FALLING -> Unit // no alert
            PressureTrendClass.FALLING_FAST -> {
                val cooldownMs = (StormWarning.NOTIFICATION_COOLDOWN_HOURS * 3_600_000.0).toLong()
                if (nowMs - store.lastNotifiedAtMs() >= cooldownMs) {
                    StormNotifier.postStormWarning(context, abs(trend.changeHpaOver3h))
                    store.setLastNotifiedAtMs(nowMs)
                }
            }
        }
    }

    companion object {
        private const val WORK_NAME = "barometer_refresh"

        fun schedule(context: Context) {
            // Don't run the periodic widget/storm sample when the battery is
            // low — a 15-minute background sensor+widget tick isn't worth
            // deepening a low battery, and the widget can show a staleness cue
            // until the next opportunity.
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BarometerRefreshWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(constraints).build()

            // UPDATE (not KEEP) so an existing install picks up the new
            // constraint instead of clinging to the unconstrained schedule.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
