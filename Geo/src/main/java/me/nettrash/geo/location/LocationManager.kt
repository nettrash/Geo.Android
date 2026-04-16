package me.nettrash.geo.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

@Singleton
class LocationManager @Inject constructor(
    @param:ApplicationContext private val context: Context
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

    private var lastRecordedPressure = 0.0
    private val pressureStep = 0.1 // kPa

    var onRecordHistory: ((Location) -> Unit)? = null
    var onTrackingUpdate: ((Location) -> Unit)? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                _location.value = loc
                refreshClosestMountain(loc)
                onLocationUpdated?.invoke(loc)

                val now = System.currentTimeMillis()
                val step = stepLocation
                if (step != null) {
                    val distance = step.distanceTo(loc)
                    val altDiff = abs(step.altitude - loc.altitude)
                    if (distance > horizontalStep || altDiff > verticalStep) {
                        stepLocation = Location(loc)
                        onRecordHistory?.invoke(loc)
                    }
                } else {
                    stepLocation = Location(loc)
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

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateIntervalMillis(2000L).build()

        fusedLocationClient.requestLocationUpdates(
            request, locationCallback, Looper.getMainLooper()
        )

        mountainsData?.sevenPeaks?.mountains?.firstOrNull()?.let {
            _highestMountain.value = it
        }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
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
        val allMountains = mutableListOf<MountainInfo>()
        data.highest?.mountains?.let { allMountains.addAll(it) }
        data.sevenPeaks?.mountains?.let { allMountains.addAll(it) }
        data.snowLeopardOfRussia?.mountains?.let { allMountains.addAll(it) }

        val closest = allMountains.minByOrNull { m ->
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
