package me.nettrash.geo.ar

import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.geo.util.GeoCalculations
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One point on the visible terrain skyline.
 *
 *  @property bearing  Compass bearing from the observer, 0..360, 0=N.
 *  @property distance Horizontal distance from the observer (m).
 *  @property altitude Terrain elevation at that point (m MSL).
 */
data class SkylineSample(
    val bearing: Double,
    val distance: Double,
    val altitude: Double
)

/**
 * Computes the terrain-aware skyline visible from the observer. Direct
 * port of iOS `Nature/SkylineCalculator.swift`.
 *
 * Algorithm: fan out a bearing grid (every [bearingStepDeg]) and at
 * each bearing sample terrain elevations from
 * [TerrainElevationService] along a log-spaced distance grid. The
 * silhouette sample at that bearing is the one with the maximum
 * apparent altitude angle (accounting for Earth curvature). Result
 * is a list sorted by bearing.
 */
@Singleton
class SkylineCalculator @Inject constructor(
    private val terrain: TerrainElevationService
) {

    private val _samples = MutableStateFlow<List<SkylineSample>>(emptyList())
    val samples: StateFlow<List<SkylineSample>> = _samples.asStateFlow()

    private val _isComputing = MutableStateFlow(false)
    val isComputing: StateFlow<Boolean> = _isComputing.asStateFlow()

    /** Distance the observer must move before we re-run. */
    private val recomputeDistanceM = 500.0

    private val bearingStepDeg = 2.0
    private val maxRangeMeters = 200_000.0

    /**
     * Distance grid sampled along each bearing ray. Consecutive
     * ratio ≤ 1.5× so no real ridge falls into a sampling gap —
     * earlier doubling steps (8 → 16 → 32 km) missed prominent
     * peaks that happened to sit in the middle of a gap. 20 entries
     * out to 200 km matches the brief's prescription byte-for-byte.
     */
    private val distancesMeters: List<Double> = listOf(
        100.0, 200.0, 400.0, 600.0, 800.0,
        1_000.0, 1_500.0, 2_000.0, 3_000.0, 5_000.0,
        7_000.0, 10_000.0, 15_000.0, 22_000.0, 32_000.0,
        48_000.0, 70_000.0, 100_000.0, 140_000.0, 200_000.0
    )

    private var lastObserver: Location? = null
    private var currentJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Recompute if the observer has moved further than the threshold
     * since the last successful pass. Cheap to call from a Timer or
     * onReceive.
     */
    fun computeIfNeeded(observer: Location, barometerAltitude: Double? = null) {
        lastObserver?.let { last ->
            if (last.distanceTo(observer) < recomputeDistanceM && _samples.value.isNotEmpty()) {
                return
            }
        }
        compute(observer, barometerAltitude)
    }

    /** Force a recompute regardless of distance moved. */
    fun compute(observer: Location, barometerAltitude: Double? = null) {
        currentJob?.cancel()
        lastObserver = observer
        currentJob = scope.launch {
            _isComputing.value = true
            try {
                val new = computeSkyline(
                    observer = observer,
                    observerAltitudeOverride = barometerAltitude,
                    bearingStepDeg = bearingStepDeg,
                    distances = distancesMeters,
                    maxRange = maxRangeMeters
                )
                // Keep the previous skyline visible if the new pass
                // came back empty (network failure, etc.). The
                // geometric fallback in the overlay already covers
                // the empty case so wiping is not necessary.
                if (new.isNotEmpty()) {
                    _samples.value = new
                }
            } finally {
                // Only clear the spinner if WE are still the current
                // job. When a faster-moving observer triggers a new
                // compute() before this pass finishes, this job is
                // cancelled and `currentJob` already points at the
                // replacement; clearing here would race the new job's
                // `_isComputing.value = true` on Dispatchers.Default
                // (no happens-before) and could latch the flow to
                // false while the replacement is still computing.
                if (coroutineContext[Job] == currentJob) {
                    _isComputing.value = false
                }
            }
        }
    }

    /** Cancel any in-flight compute so Open-Elevation batches stop.
     *  Unlike [shutdown] this keeps [scope] alive, because the
     *  calculator is a [Singleton] reused across AR teardown/rebuild —
     *  a later [compute] must still be able to launch. Called from the
     *  Nature screen's onDispose. */
    fun cancel() {
        currentJob?.cancel()
        _isComputing.value = false
    }

    /** Stop and discard any in-flight computation. Call when the
     *  AR view tears down so the scope doesn't outlive the host. */
    fun shutdown() {
        currentJob?.cancel()
        scope.cancel()
    }

    private suspend fun computeSkyline(
        observer: Location,
        observerAltitudeOverride: Double?,
        bearingStepDeg: Double,
        distances: List<Double>,
        maxRange: Double
    ): List<SkylineSample> = withContext(Dispatchers.Default) {

        // 1. Build the (bearing, distance, lat, lon) sample grid.
        val bearings = generateSequence(0.0) { it + bearingStepDeg }
            .takeWhile { it < 360.0 }
            .toList()

        data class GridPoint(val bearing: Double, val distance: Double, val lat: Double, val lon: Double)
        val grid = ArrayList<GridPoint>(bearings.size * distances.size)
        for (bearing in bearings) {
            for (d in distances) {
                if (d > maxRange) continue
                val (lat, lon) = GeoCalculations.project(
                    observer.latitude, observer.longitude,
                    bearingDeg = bearing,
                    distance = d
                )
                grid.add(GridPoint(bearing, d, lat, lon))
            }
        }

        // 2. Resolve elevations in batched HTTP calls.
        val elevations = terrain.elevations(grid.map { it.lat to it.lon })

        // 3. For each bearing, pick the sample with the maximum
        //    apparent-altitude angle.
        val observerAlt = observerAltitudeOverride ?: observer.altitude
        val bestPerBearing = HashMap<Double, Pair<SkylineSample, Double>>()
        for ((i, gp) in grid.withIndex()) {
            val elev = elevations[i] ?: continue
            val angle = GeoCalculations.apparentAltitudeAngle(
                observerAltitude = observerAlt,
                targetAltitude = elev,
                distance = gp.distance
            )
            val prev = bestPerBearing[gp.bearing]
            if (prev != null && prev.second >= angle) continue
            bestPerBearing[gp.bearing] = SkylineSample(gp.bearing, gp.distance, elev) to angle
        }

        bestPerBearing.values
            .map { it.first }
            .sortedBy { it.bearing }
    }
}
