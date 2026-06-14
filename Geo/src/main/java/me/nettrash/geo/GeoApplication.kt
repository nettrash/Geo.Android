package me.nettrash.geo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import me.nettrash.geo.location.LocationManager
import me.nettrash.geo.notification.StormNotifier
import me.nettrash.geo.sensor.BarometerManager
import me.nettrash.geo.util.AppLog
import me.nettrash.geo.widget.WidgetUpdater
import me.nettrash.geo.worker.BarometerRefreshWorker
import javax.inject.Inject

@HiltAndroidApp
class GeoApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var widgetUpdater: WidgetUpdater
    @Inject lateinit var locationManager: LocationManager
    @Inject lateinit var barometerManager: BarometerManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Create the weather-alerts notification channel up-front so the
        // storm warning (M5a) has somewhere to post on Android 8+.
        StormNotifier.ensureChannel(this)

        // Mirrors iOS GeoAppDelegate.registerBackgroundTasks() —
        // schedule the periodic barometer sample at process start so
        // the widget keeps refreshing even when the user hasn't
        // recently opened the foreground app.
        BarometerRefreshWorker.schedule(this)

        // Mirrors iOS applicationWillResignActive: push the latest
        // snapshot + force a widget reload as soon as the process
        // moves into background. The ViewModel only does this on
        // refresh ticks while it's alive, which doesn't help when
        // Android has paused the ViewModel after a config change.
        //
        // Also gate the high-rate sensors on the app lifecycle: stop
        // GPS + barometer streaming when the UI is no longer visible
        // (ON_STOP) and resume on ON_START, instead of leaving them
        // armed for the whole session and only stopping in
        // GeoViewModel.onCleared(). Saves battery while backgrounded.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    AppLog.app.debug("Process foregrounded — resuming sensors")
                    locationManager.startLocationUpdates()
                    barometerManager.start()
                }

                override fun onStop(owner: LifecycleOwner) {
                    AppLog.app.debug("Process backgrounded — pushing widget snapshot, pausing sensors")
                    widgetUpdater.pushImmediate()
                    locationManager.stopLocationUpdates()
                    barometerManager.stop()
                }
            }
        )
    }
}
