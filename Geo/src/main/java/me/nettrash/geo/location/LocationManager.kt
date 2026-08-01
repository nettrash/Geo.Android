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
        set(value) {
            field = value
            locatedMountains = value?.let { data ->
                val all = mutableListOf<MountainInfo>()
                data.highest?.mountains?.let { all.addAll(it) }
                data.sevenPeaks?.mountains?.let { all.addAll(it) }
                data.snowLeopardOfRussia?.mountains?.let { all.addAll(it) }
                // Drop coordinate-less mountains before the nearest-search: a null
                // latitude/longitude would otherwise fall through to (0,0) and make
                // the peak a phantom off the coast of Africa.
                all.mapNotNull { m ->
                    val lat = m.coordinates?.latitude
                    val lon = m.coordinates?.longitude
                    if (lat == null || lon == null) null
                    else m to Location("").apply { latitude = lat; longitude = lon }
                }
            }
        }

    /**
     * The flattened, coordinate-filtered peak list with each peak's [Location]
     * pre-built — derived once when [mountainsData] is assigned rather than
     * rebuilt on every GPS fix.
     *
     * [refreshClosestMountain] runs on every accepted fix (every 2-5 s, all
     * session, on every tab). It used to re-flatten and re-filter the 136-entry
     * dataset and allocate a fresh `Location` per peak inside `minByOrNull`'s
     * selector — ~136 short-lived objects per fix for a dataset that never
     * changes after load. The distances themselves are still computed per fix
     * with the same `Location.distanceTo`, so the selected peak and the
     * displayed distance are bit-identical to before.
     */
    private var locatedMountains: List<Pair<MountainInfo, Location>>? = null
    var onLocationUpdated: ((Location) -> Unit)? = null

    private var stepLocation: Location? = null

    /** True once the first real-time tracking sample has been recorded. Lets
     *  [trackingRefresh] emit the first sample immediately, then throttle the
     *  rest to [trackingStep]. Replaces the old `trackingStepLocation` seed
     *  marker, which conflated "not started yet" with "no current GPS fix" and
     *  so blocked barometer-only tracking when GPS was unavailable. */
    private var trackingSeeded = false

    private val horizontalStep = 1000f // meters
    private val verticalStep = 50f // meters
    private var lastInfoTime = System.currentTimeMillis()
    private val trackingStep = 15_000L // 15 seconds

    /** A GPS fix older than this is treated as "GPS unavailable" for the
     *  real-time tracking chart: the barometer path fires every [trackingStep],
     *  and if the last fix hasn't refreshed within this window the GPS series
     *  records a gap rather than freezing at a stale altitude while the
     *  barometer keeps moving. Well above the ~1 Hz cadence a live fix
     *  delivers, so a working GPS is never mistaken for stale.
     *  Mirrors iOS `Location.trackingGPSStaleness`. */
    private val trackingGpsStaleness = 60_000L // 60 seconds

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

    /** Interval the current subscription was registered with, so
     *  [startLocationUpdates] can tell "already subscribed" from
     *  "subscribed, but at the wrong cadence". */
    private var currentIntervalMs = 0L

    /** Whether a live-readout consumer (the Info tab) is on screen. */
    private var wantsLiveCadence = false

    /** Cadence wanted right now. See [setLiveCadence]. */
    private val wantedIntervalMs: Long
        get() = if (wantsLiveCadence) LIVE_INTERVAL_MS else DEFAULT_INTERVAL_MS

    /**
     * Raise/lower the GPS cadence as the Info tab comes and goes.
     *
     * Deliberately CANNOT create a subscription: it only re-registers one that
     * already exists, so it is safe to call from `onDispose` even when the
     * process-lifecycle observer has already torn GPS down. If nothing is
     * subscribed the preference is just recorded, and the next
     * [startLocationUpdates] picks it up. Mirrors iOS `Location.setPrecision`.
     */
    fun setLiveCadence(live: Boolean) {
        if (wantsLiveCadence == live) return
        wantsLiveCadence = live
        if (isSubscribed) startLocationUpdates(wantedIntervalMs)
    }

    private var lastRecordedPressure = 0.0
    private val pressureStep = 0.1 // kPa

    var onRecordHistory: ((Location) -> Unit)? = null

    /** Emits one real-time tracking sample. The [Location] is NULLABLE: `null`
     *  means "GPS is unavailable right now" (no fix / no altitude / stale), and
     *  the consumer records a gap for the GPS series while still plotting the
     *  barometer. See [trackingRefresh]. */
    var onTrackingUpdate: ((Location?) -> Unit)? = null

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

                // `_location.value` is already the fresh fix (set above), so let
                // the self-throttling recorder read it directly. It emits the
                // first sample immediately, then honours `trackingStep`.
                trackingRefresh()
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
    fun startLocationUpdates(intervalMs: Long = wantedIntervalMs) {
        if (!hasLocationPermission()) return
        // Already subscribed at this exact cadence — nothing to do. A DIFFERENT
        // cadence falls through and re-registers, the same way
        // DeviceMotionManager.start() re-registers to change its sensor rate.
        if (isSubscribed && intervalMs == currentIntervalMs) return
        if (isSubscribed) fusedLocationClient.removeLocationUpdates(locationCallback)

        val request = LocationRequest.Builder(
            // PRIORITY_HIGH_ACCURACY is load-bearing, NOT a tuning knob:
            // usableGpsFix() rejects any fix without hasAltitude(), and
            // network/cell fixes carry neither altitude nor speed. Dropping to
            // BALANCED would permanently gap the Stat tab's GPS-altitude series,
            // kill the verticalStep history trigger and pin velocity at 0 m/s.
            // The interval is the safe lever.
            Priority.PRIORITY_HIGH_ACCURACY, intervalMs
        )
            // Keep the 2 s floor: fixes another app has already paid for are
            // consumed for free rather than discarded.
            .setMinUpdateIntervalMillis(2000L)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper()
            )
            isSubscribed = true
            currentIntervalMs = intervalMs
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
        currentIntervalMs = 0L
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

        // Update tracking on every barometer reading (subject to the throttle),
        // regardless of pressure step AND regardless of whether a GPS fix
        // exists. This is what keeps the tracking chart's barometer series live
        // when GPS is unavailable (indoors / denied / no fix yet) — with no
        // usable fix the GPS series records a gap and only the barometer line
        // is drawn. Previously this was inside a `_location.value?.let {}`, so
        // with no fix the barometer never reached the chart at all.
        trackingRefresh()
    }

    /**
     * Emit one real-time tracking sample, subject to the [trackingStep]
     * throttle. Driven by BOTH the GPS callback and [onBarometerUpdated], so
     * the barometer series keeps updating even when GPS is unavailable.
     *
     * The GPS fix is passed only when it's currently usable — see [usableGpsFix];
     * otherwise `null` records a gap so the GPS line breaks instead of freezing
     * at a stale altitude while the barometer keeps moving.
     *
     * The first sample is emitted immediately; later ones honour the throttle.
     * The shared [lastInfoTime] means the two paths cooperate — whichever fires
     * in a given window emits one sample that reads both sensors at that
     * instant. Mirrors iOS `Location.trackingRefresh()`.
     */
    private fun trackingRefresh() {
        if (!allowTracking) return
        val now = System.currentTimeMillis()
        if (trackingSeeded && lastInfoTime + trackingStep > now) return
        trackingSeeded = true
        lastInfoTime = now
        onTrackingUpdate?.invoke(usableGpsFix())
    }

    /**
     * The current fix, or `null` when GPS is effectively unavailable for the
     * tracking chart: no fix at all, a fix carrying no altitude, or a fix that
     * has gone stale (see [trackingGpsStaleness]).
     */
    private fun usableGpsFix(): Location? {
        val loc = _location.value ?: return null
        if (!loc.hasAltitude()) return null
        if (System.currentTimeMillis() - loc.time > trackingGpsStaleness) return null
        return loc
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

        // Flattened, coordinate-filtered, with each peak's Location pre-built —
        // see `locatedMountains`. Same order and same `distanceTo` metric as
        // before, so `minByOrNull` still resolves ties to the same peak.
        val candidates = locatedMountains ?: return

        val closest = candidates.minByOrNull { (_, mloc) -> loc.distanceTo(mloc) }

        if (closest != null) {
            val (closestMountain, closestLoc) = closest
            _closestMountain.value = closestMountain
            _closestMountainDistance.value = loc.distanceTo(closestLoc).toDouble()

            // Left exactly as it was: one allocation per fix, not 136, and
            // changing its null-coordinate fallback would be a correctness
            // change rather than an energy one.
            _highestMountain.value?.let { highest ->
                val hLoc = Location("").apply {
                    latitude = highest.coordinates?.latitude ?: 0.0
                    longitude = highest.coordinates?.longitude ?: 0.0
                }
                _highestMountainDistance.value = loc.distanceTo(hLoc).toDouble()
            }
        }
    }

    companion object {
        /**
         * Baseline cadence. Matches [trackingStep] — the fastest consumer that
         * isn't the Info tab's live readout. The history steps trigger on
         * distance/altitude deltas, not on a clock, and [trackingGpsStaleness]
         * is 60 s, so this leaves 4x headroom before the Stat tab's GPS series
         * would record a gap.
         */
        const val DEFAULT_INTERVAL_MS = 15_000L

        /**
         * Cadence while the Info tab is on screen — the only surface that
         * renders coordinates and velocity live. Same value the whole app used
         * to run at, so that readout is unchanged.
         */
        const val LIVE_INTERVAL_MS = 5_000L
    }
}
