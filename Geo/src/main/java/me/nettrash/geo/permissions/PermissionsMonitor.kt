package me.nettrash.geo.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralised observation of the Android permissions Geo depends on
 * so the UI can surface a single, consistent "we can't do X because
 * you denied Y" message instead of silently failing in each tab.
 *
 * Direct port of iOS `Core/PermissionsMonitor.swift` — the iOS app
 * tracks Location (CLAuthorizationStatus) and Motion (CMAltimeter
 * authorizationStatus). Android maps:
 *
 *  * Location → ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION grant
 *    state. We poll instead of registering a listener because Android
 *    only delivers permission callbacks to the requesting Activity.
 *  * Motion → does the device expose `Sensor.TYPE_PRESSURE` at all?
 *    Android doesn't gate barometer access behind a runtime
 *    permission — it's covered by the install-time
 *    HIGH_SAMPLING_RATE_SENSORS permission and by the hardware being
 *    physically present. We report "denied" only when the sensor is
 *    completely missing, which matches the iOS behaviour on devices
 *    without a barometer.
 *
 * Call [refresh] from the host activity / composable on `ON_RESUME`
 * so a user who toggles a switch in Settings sees the banner clear
 * immediately on their next foreground.
 */
@Singleton
class PermissionsMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val _locationDenied = MutableStateFlow(false)
    val locationDenied: StateFlow<Boolean> = _locationDenied.asStateFlow()

    private val _motionDenied = MutableStateFlow(false)
    val motionDenied: StateFlow<Boolean> = _motionDenied.asStateFlow()

    private val _hasAnyDenial = MutableStateFlow(false)
    val hasAnyDenial: StateFlow<Boolean> = _hasAnyDenial.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-read the system permission state. Cheap to call — both
     * `ContextCompat.checkSelfPermission` and `getDefaultSensor` are
     * synchronous in-process lookups.
     */
    fun refresh() {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // `.notDetermined` on iOS doesn't count as a denial; we mirror
        // that by only flagging denial when neither permission has
        // been granted. The first-launch flow runs through the
        // ActivityResultContract in MainActivity, which surfaces the
        // system prompt before this flag is read.
        _locationDenied.value = !fine && !coarse && hasBeenRequested()

        // Barometer: Android only exposes a permission for *raw* high
        // sampling (HIGH_SAMPLING_RATE_SENSORS). Practical denial is
        // "the device has no barometer at all", which the iOS app
        // also surfaces as "motion access required" on devices
        // missing CMAltimeter.
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        _motionDenied.value = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE) == null

        _hasAnyDenial.value = _locationDenied.value || _motionDenied.value
    }

    /**
     * Tracks whether the user has interacted with the location prompt
     * at least once. Prevents the banner from flashing on a fresh
     * install before the user has even seen the system dialog.
     */
    private fun hasBeenRequested(): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LOCATION_REQUESTED, false)
    }

    /**
     * Called by [MainActivity] after launching the permission request
     * so subsequent denials are visible in the banner.
     */
    fun markLocationPromptShown() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_LOCATION_REQUESTED, true).apply()
        refresh()
    }

    private companion object {
        const val PREFS = "me.nettrash.geo.permissions"
        const val KEY_LOCATION_REQUESTED = "location_requested"
    }
}
