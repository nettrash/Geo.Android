package me.nettrash.geo.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.nettrash.geo.data.snapshot.SharedSnapshotStore
import me.nettrash.geo.notification.AuroraNotifier
import me.nettrash.geo.util.AppLog
import me.nettrash.geo.util.AuroraAlert
import me.nettrash.geo.util.AuroraAlertStore
import me.nettrash.geo.util.MagneticConditions
import me.nettrash.geo.util.SpaceWeatherRepository
import java.util.concurrent.TimeUnit

/**
 * Periodic planetary-K-index refresh behind the opt-in aurora alert,
 * mirroring iOS's `BGAppRefreshTask` path. Runs every 3 hours — the length
 * of a Kp bin, so a shorter period could not return a new number — and
 * only ever exists while the user has alerts switched on.
 *
 * A SIBLING of [BarometerRefreshWorker], deliberately not a passenger on
 * it. This one needs the network; the barometer tick must keep working
 * without it, because capturing pressure with no signal is the whole point
 * of that worker. Adding `NetworkType.CONNECTED` to `barometer_refresh`
 * to save a worker would silently stop the offline barometer capture —
 * the same reasoning that keeps iOS's fetch off its processing task,
 * which sets `requiresNetworkConnectivity = false` for exactly this
 * reason. Two workers, two constraint sets, two unique names.
 *
 * The alert decision itself is the pure
 * [me.nettrash.geo.util.AuroraAlert.shouldNotify]; nothing is decided
 * here.
 */
@HiltWorker
class SpaceWeatherWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val spaceWeatherRepository: SpaceWeatherRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppLog.spaceWeather.debug("SpaceWeatherWorker start")

        val store = AuroraAlertStore(context)
        val enabled = store.isEnabled()
        if (!enabled) {
            // Belt and braces: the toggle cancels this work as it is turned
            // off, so a tick arriving here means we lost that race. Do
            // nothing at all — no fetch, no notification.
            AppLog.spaceWeather.info("Aurora alerts are off; worker is a no-op")
            return Result.success()
        }

        // The fix comes from the snapshot the foreground app and the
        // barometer worker already maintain. This feature asks for no
        // background location of its own, and the sensors are stopped while
        // the process is backgrounded anyway.
        val fix = SharedSnapshotStore.readCurrent(context)
        val latitude = fix?.gpsLatitude
        val longitude = fix?.gpsLongitude
        if (latitude == null || longitude == null || (latitude == 0.0 && longitude == 0.0)) {
            // Exactly (0, 0) is the token's own "never had a fix" default,
            // not a place anybody is standing. Without a position there is
            // no magnetic latitude, so there is no local answer to give.
            AppLog.spaceWeather.info("No stored fix; aurora alert skipped")
            return Result.success()
        }

        val nowMs = System.currentTimeMillis()
        val conditions = spaceWeatherRepository.refreshAndResolve(latitude, longitude, nowMs)

        // Failure-tolerant — never fails the tick. A dropped alert costs
        // one night; a failing periodic worker costs the schedule.
        runCatching { evaluateAuroraAlert(conditions, store, nowMs) }
            .onFailure { AppLog.spaceWeather.warn("Aurora alert evaluation failed", it) }

        AppLog.spaceWeather.debug("SpaceWeatherWorker done")
        return Result.success()
    }

    /**
     * Ask the pure gate, and post only if it says yes. The cooldown stamp
     * is written straight after delivery and synchronously — see
     * [AuroraAlertStore] — because the OS may reclaim this process the
     * moment the tick returns, and a lost stamp re-fires the alert.
     */
    private fun evaluateAuroraAlert(
        conditions: MagneticConditions,
        store: AuroraAlertStore,
        nowMs: Long
    ) {
        if (!AuroraAlert.shouldNotify(true, conditions, store.lastNotifiedAtMs(), nowMs)) return
        AuroraNotifier.postAuroraAlert(context, conditions)
        store.setLastNotifiedAtMs(nowMs)
    }

    companion object {
        // NOT "barometer_refresh". A separate unique name is what keeps the
        // two schedules independent, so cancelling this one can never
        // silence the barometer sample.
        private const val WORK_NAME = "space_weather_refresh"

        /** Match the schedule to the opt-in flag. The single place that
         *  decides, called from process start and from the toggle alike, so
         *  the two can never drift into "flag off, worker still ticking". */
        fun applyOptIn(context: Context, enabled: Boolean) {
            if (enabled) schedule(context) else cancel(context)
        }

        private fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                // The one worker in the app that genuinely needs a network:
                // there is nothing to compute without a fresh Kp figure.
                // This constraint belongs HERE and must never migrate onto
                // BarometerRefreshWorker.
                .setRequiredNetworkType(NetworkType.CONNECTED)
                // A 3-hourly fetch for an optional alert is not worth
                // deepening a low battery.
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<SpaceWeatherWorker>(
                3, TimeUnit.HOURS
            ).setConstraints(constraints).build()

            // UPDATE (not KEEP) so an existing install picks up a changed
            // period or constraint instead of clinging to the old schedule.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        private fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
