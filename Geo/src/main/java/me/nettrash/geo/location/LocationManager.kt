package me.nettrash.geo.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.nettrash.geo.data.model.MountainData
import me.nettrash.geo.data.model.MountainInfo
import me.nettrash.geo.util.QnhRepository
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

@Singleton
class LocationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val qnhRepository: QnhRepository
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val _closestMountain = MutableStateFlow<MountainInfo?>(null)
    val closestMountain: StateFlow<MountainInfo?> = _closestMountain.asStateFlow()

    private val _closestMountainDistance = MutableStateFlow<Double?>(null)
    val closestMountainDistance: StateFlow<Double?> = _closestMountainDistance.asStateFlow()

    private val _highestMountain = MutableStateFlow<MountainInfo?>(null)
    val highestMountain: StateFlow<MountainInfo?> = _highestMountain.asStateFlow()

    private val _highestMountainDistance = MutableStateFlow<Double?>(null)
    val highestMountainDistance: StateFlow<Double?> = _highestMountainDistance.asStateFlow()

    var mountainsData: MountainData? = null
    var onLocationUpdated: ((Location) -> Unit)? = null

    private var stepLocation: Location? = null
    private var trackingStepLocation: Location? = null
    private val horizontalStep = 1000f // meters
    private val verticalStep = 50f // meters
    private var lastInfoTime = System.currentTimeMillis()
    private val trackingStep = 15_000L // 15 seconds
    var allowTracking = true

    // Dedicated calendar-day tracker for the daily-rollover history
    // record. Kept separate from lastInfoTime (the tracking throttle)
    // on purpose — mirrors iOS but avoids the lastInfoDate-overloading
    // bug, so a motionless device still records one point per day.
    private var lastRecordDay: Int = -1

    // Whether the location callback is currently subscribed. Guards
    // startLocationUpdates() against double-registration when it is
    // called both on init and again after a permission grant (A1).
    private var isSubscribed = false

    private var lastRecordedPressure = 0.0
    private val pressureStep = 0.1 // kPa

    var onRecordHistory: ((Location) -> Unit)? = null
    var onTrackingUpdate: ((Location) -> Unit)? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                // Ignore stale/low-quality fixes. The first delivered fix
                // is often a stale cached location that would seed a
                // misleading first point: drop anything older than ~10s or
                // with a horizontal accuracy worse than ~100m (mirrors iOS
                // Location.swift didUpdateLocations).
                if (System.currentTimeMillis() - loc.time > 10_000L ||
                    (loc.hasAccuracy() && loc.accuracy > 100f)
                ) {
                    return
                }
                // A fix without horizontal accuracy, or a (0,0) coordinate,
                // is treated as invalid; don't seed/record from it (avoids
                // writing a phantom (0,0) into history/closest-mountain/
                // tracking). hasAltitude() guards the altitude-step trigger.
                val positionValid = loc.hasAccuracy() &&
                    !(loc.latitude == 0.0 && loc.longitude == 0.0)
                val altitudeValid = loc.hasAltitude()
                if (!positionValid) return

                _location.value = loc
                refreshClosestMountain(loc)
                // Give QnhRepository a chance to refresh on each
                // fix; it self-throttles by distance and time, so
                // calling it every tick is cheap.
                qnhRepository.maybeRefresh(loc)
                onLocationUpdated?.invoke(loc)

                val now = System.currentTimeMillis()
                val today = currentDayKey()
                val step = stepLocation
                if (step != null) {
                    val distance = step.distanceTo(loc)
                    // Only let the altitude delta trigger a record when this
                    // fix's altitude is valid (hasAltitude()); an absent
                    // altitude is unreliable (mirrors iOS verticalAccuracy).
                    val altitudeStepped = altitudeValid &&
                        abs(step.altitude - loc.altitude) > verticalStep
                    // OR-in the calendar-day rollover so a motionless
                    // device still records one point per day (mirrors
                    // iOS Location.swift day-component clause).
                    if (distance > horizontalStep || altitudeStepped || lastRecordDay != today) {
                        stepLocation = Location(loc)
                        lastRecordDay = today
                        onRecordHistory?.invoke(loc)
                    }
                } else {
                    stepLocation = Location(loc)
                    lastRecordDay = today
                    onRecordHistory?.invoke(loc)
                }

                if (allowTracking) {
                    if (trackingStepLocation == null || lastInfoTime + trackingStep <= now) {
                        trackingStepLocation = Location(loc)
                        lastInfoTime = now
                        onTrackingUpdate?.invoke(loc)
                    }
                }
            }
        }
    }

    /**
     * True when at least one of the location permissions is granted.
     * Mirrors iOS checking the CLLocationManager authorization status
     * before starting the monitor.
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Subscribe to location updates. Safe to call repeatedly — used both
     * on init and after a runtime-permission grant (mirrors iOS
     * didChangeAuthorization -> startLocationMonitor). No-ops while no
     * location permission is granted, and swallows the SecurityException
     * that Play Services may still throw, so a permission-less call on
     * first launch cannot crash or silently register a dead callback.
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        if (isSubscribed) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateIntervalMillis(2000L).build()

        try {
            fusedLocationClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper()
            )
            isSubscribed = true
        } catch (e: SecurityException) {
            // Permission was revoked between the check above and the
            // request, or Play Services rejected it. Leave it unsubscribed
            // so a later grant can re-subscribe.
            isSubscribed = false
        }

        mountainsData?.sevenPeaks?.mountains?.firstOrNull()?.let {
            _highestMountain.value = it
        }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isSubscribed = false
    }

    private fun currentDayKey(): Int {
        // Year-unique day key. `DAY_OF_YEAR` alone repeats every year (Jan 1 is
        // day 1 each year), which would suppress the daily-rollover record on
        // the first day of a new year.
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }

    fun onBarometerUpdated(pressure: Double, height: Double) {
        val pressureDelta = abs(pressure - lastRecordedPressure)
        if (lastRecordedPressure == 0.0 || pressureDelta >= pressureStep) {
            lastRecordedPressure = pressure
            _location.value?.let { loc ->
                stepLocation = Location(loc)
                onRecordHistory?.invoke(loc)
            }
        }

        if (allowTracking) {
            _location.value?.let { loc ->
                val now = System.currentTimeMillis()
                if (lastInfoTime + trackingStep <= now) {
                    trackingStepLocation = Location(loc)
                    lastInfoTime = now
                    onTrackingUpdate?.invoke(loc)
                }
            }
        }
    }

    private fun refreshClosestMountain(loc: Location) {
        val data = mountainsData ?: return

        // Highest = first of the pre-sorted seven-summits list (mirrors iOS).
        // Initialise it here (lazily, on the first fix that has data loaded) as a
        // fallback to the assignment in startLocationUpdates(): that one sits behind
        // `if (isSubscribed) return` and needs mountainsData ready at subscribe
        // time, so if the process-lifecycle observer subscribes before the ViewModel
        // loads the data, _highestMountain would stay null forever while
        // _closestMountain (set below) populates fine — leaving the highest card
        // with no bearing arrow. The null-guard makes this a one-time init, not a
        // per-fix write.
        if (_highestMountain.value == null) {
            _highestMountain.value = data.sevenPeaks?.mountains?.firstOrNull()
        }

        val allMountains = mutableListOf<MountainInfo>()
        data.highest?.mountains?.let { allMountains.addAll(it) }
        data.sevenPeaks?.mountains?.let { allMountains.addAll(it) }
        data.snowLeopardOfRussia?.mountains?.let { allMountains.addAll(it) }

        // Drop coordinate-less mountains before the nearest-search: a null
        // latitude/longitude would otherwise fall through to (0,0) and make
        // the peak a phantom off the coast of Africa.
        val locatedMountains = allMountains.filter {
            it.coordinates?.latitude != null && it.coordinates?.longitude != null
        }

        val closest = locatedMountains.minByOrNull { m ->
            val mloc = Location("").apply {
                latitude = m.coordinates?.latitude ?: 0.0
                longitude = m.coordinates?.longitude ?: 0.0
            }
            loc.distanceTo(mloc).toDouble()
        }

        if (closest != null) {
            _closestMountain.value = closest
            val cLoc = Location("").apply {
                latitude = closest.coordinates?.latitude ?: 0.0
                longitude = closest.coordinates?.longitude ?: 0.0
            }
            _closestMountainDistance.value = loc.distanceTo(cLoc).toDouble()

            _highestMountain.value?.let { highest ->
                val hLoc = Location("").apply {
                    latitude = highest.coordinates?.latitude ?: 0.0
                    longitude = highest.coordinates?.longitude ?: 0.0
                }
                _highestMountainDistance.value = loc.distanceTo(hLoc).toDouble()
            }
        }
    }
}
