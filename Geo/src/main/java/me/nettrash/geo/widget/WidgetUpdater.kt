package me.nettrash.geo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.nettrash.geo.connectivity.WearMessageBridge
import me.nettrash.geo.data.model.InformationToken
import me.nettrash.geo.location.LocationManager
import me.nettrash.geo.sensor.BarometerManager
import me.nettrash.geo.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralised widget refresh path. Owned by the application scope so
 * the process-lifecycle observer in [me.nettrash.geo.GeoApplication]
 * can push a final snapshot when the app backgrounds, even after the
 * UI ViewModel has been torn down.
 *
 * Mirrors iOS `GeoAppDelegate.pushDataToWidget()` + `reloadWidgetIfNeeded()`.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val barometerManager: BarometerManager,
    private val locationManager: LocationManager,
    private val wearBridge: WearMessageBridge
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Reload the widget at most every [throttleMs] milliseconds.
     * Matches the iOS 30 s budget. Call this from the ViewModel's
     * regular tick.
     */
    private var lastReloadMs = 0L
    private val throttleMs = 30_000L

    /** Push the latest in-memory snapshot to prefs + reload, honoring the throttle. */
    fun pushThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastReloadMs < throttleMs) return
        lastReloadMs = now
        writeAndReload()
    }

    /**
     * Push immediately, bypassing the throttle. Used from the
     * process-lifecycle ON_STOP hook so the widget always sees the
     * freshest sample before the app backgrounds — even if a
     * throttled call just fired.
     */
    fun pushImmediate() {
        lastReloadMs = System.currentTimeMillis()
        writeAndReload()
    }

    private fun writeAndReload() {
        val loc = locationManager.location.value
        val token = InformationToken(
            recordDate = System.currentTimeMillis(),
            gpsAltitude = loc?.altitude ?: 0.0,
            gpsSpeed = maxOf(loc?.speed?.toDouble() ?: 0.0, 0.0),
            barPreassure =barometerManager.pressure.value,
            barAltitude = barometerManager.height.value,
            gpsLatitude = loc?.latitude ?: 0.0,
            gpsLongitude = loc?.longitude ?: 0.0
        )
        WidgetDataStore.write(
            context     = context,
            pressureKpa = token.barPreassure,
            barAltitude = token.barAltitude,
            gpsAltitude = token.gpsAltitude,
            gpsSpeed    = token.gpsSpeed,
            gpsLat      = token.gpsLatitude,
            gpsLon      = token.gpsLongitude
        )
        // Best-effort push to the paired watch. Mirrors iOS
        // PhoneConnectivityManager.sendCurrentSnapshot — no-op if no
        // watch is paired.
        wearBridge.send(token)

        scope.launch {
            try {
                // updateAll is a no-op if no widget instance is pinned,
                // so it's safe to call unconditionally on every push.
                val manager = GlanceAppWidgetManager(context)
                if (manager.getGlanceIds(GeoWidget::class.java).isNotEmpty()) {
                    GeoWidget().updateAll(context)
                }
            } catch (t: Throwable) {
                AppLog.widget.warn("Widget updateAll failed", t)
            }
        }
    }
}
