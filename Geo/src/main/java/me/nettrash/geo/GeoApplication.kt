package me.nettrash.geo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import me.nettrash.geo.util.AppLog
import me.nettrash.geo.widget.WidgetUpdater
import me.nettrash.geo.worker.BarometerRefreshWorker
import javax.inject.Inject

@HiltAndroidApp
class GeoApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var widgetUpdater: WidgetUpdater

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

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
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    AppLog.app.debug("Process backgrounded — pushing widget snapshot")
                    widgetUpdater.pushImmediate()
                }
            }
        )
    }
}
