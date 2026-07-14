package me.nettrash.geo.ar

import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    /** The observer altitude the current [samples] were computed with —
     *  the DEM-anchored value from [GeoCalculations.effectiveObserverAltitude].
     *  Published together with [samples] so every consumer that projects
     *  against the skyline (horizon overlay, welded pills, AR markers,
     *  occlusion targets, tap hit-tests) uses the SAME altitude the
     *  silhouette was picked with; a baro/GPS-vs-skyline mismatch
     *  vertically detaches those layers from each other. `null` until the
     *  first successful pass — consumers then fall back to their existing
     *  baro-preferred / GPS expression. Mirrors iOS `observerAltitudeUsed`. */
    private val _observerAltitudeUsed = MutableStateFlow<Double?>(null)
    val observerAltitudeUsed: StateFlow<Double?> = _observerAltitudeUsed.asStateFlow()

    private val _isComputing = MutableStateFlow(false)
    val isComputing: StateFlow<Boolean> = _isComputing.asStateFlow()

    /** Distance the observer must move before we re-run. */
    private val recomputeDistanceM = 500.0

    // Instance aliases for the live-compute schedule (see the companion).
    private val bearingStepDeg = BEARING_STEP_DEG
    private val maxRangeMeters = MAX_RANGE_METERS
    private val distancesMeters: List<Double> = DISTANCES_METERS

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
                // `observerAltitudeUsed` is adopted together with
                // `samples` so the two can never describe different
                // passes.
                if (new.first.isNotEmpty()) {
                    _samples.value = new.first
                    _observerAltitudeUsed.value = new.second
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

    /**
     * Pipeline (query budget per full recompute, before caching):
     *   1. observer DEM anchor — 1 query (B);
     *   2. base fan: 180 bearings × ~44 distances ≈ 8 000 queries;
     *   3. distance refinement × [DISTANCE_REFINEMENT_ROUNDS] — ≤2 per
     *      bearing per round ≈ 720 (F);
     *   4. adaptive bearings: ≤[ADAPTIVE_MAX_EXTRA_BEARINGS] midpoint
     *      bearings, each a full distance scan + its own refinement —
     *      ≤120 × (44 + 4) ≈ 5 800 worst case, usually far fewer (A).
     *
     * Returns the bearing-sorted samples plus the observer altitude the
     * pass was computed with, so the calculator can publish the two
     * together. NOTE: with the adaptive pass the samples are no longer a
     * uniform [bearingStepDeg] lattice — renderers must walk the actual
     * samples, not reconstruct the grid. Mirrors iOS `computeSkyline`.
     */
    private suspend fun computeSkyline(
        observer: Location,
        observerAltitudeOverride: Double?,
        bearingStepDeg: Double,
        distances: List<Double>,
        maxRange: Double
    ): Pair<List<SkylineSample>, Double> = withContext(Dispatchers.Default) {
        val ds = distances.filter { it <= maxRange }

        // B. DEM-anchored observer altitude. Defence in depth on the
        // sensor side first: a barometer override is an absolute altitude
        // that is exactly 0 before its first sample (and on barometer-less
        // devices), so a non-positive override falls back to GPS altitude.
        // Then reconcile that sensor value with the DEM elevation of the
        // observer's own cell (one query, cached): the silhouette is drawn
        // FROM this DEM, so anchoring the eye to it keeps the whole near
        // silhouette level even when GPS/baro drift by 10–30 m.
        val sensorAlt = observerAltitudeOverride?.takeIf { it > 0 } ?: observer.altitude
        val demGround: Double? =
            terrain.elevations(listOf(observer.latitude to observer.longitude)).firstOrNull()
        val observerAlt = GeoCalculations.effectiveObserverAltitude(sensorAlt, demGround)

        fun angle(distance: Double, elevation: Double): Double =
            GeoCalculations.apparentAltitudeAngle(
                observerAltitude = observerAlt,
                targetAltitude = elevation,
                distance = distance,
                radius = GeoCalculations.EFFECTIVE_EARTH_RADIUS
            )

        /** Winner along one bearing, with the distance bracket around it
         *  (`loD`/`hiD` = the nearest already-sampled distances below/above
         *  the winner) so refinement rounds know where to bisect next. */
        data class Winner(val sample: SkylineSample, val angle: Double, val loD: Double, val hiD: Double)

        /** Full distance scan along each given bearing: pick the sample
         *  with the maximum apparent-altitude angle — with refraction, so
         *  distant ranges that ARE visible in reality don't lose the pick
         *  to an un-refracted curvature drop. That's the skyline. */
        suspend fun scanBearings(bearings: List<Double>): HashMap<Double, Winner> {
            val best = HashMap<Double, Winner>()
            if (bearings.isEmpty() || ds.isEmpty()) return best
            data class GridPoint(val bearing: Double, val dIndex: Int, val lat: Double, val lon: Double)
            val grid = ArrayList<GridPoint>(bearings.size * ds.size)
            for (bearing in bearings) {
                for ((i, d) in ds.withIndex()) {
                    val (lat, lon) = GeoCalculations.project(
                        observer.latitude, observer.longitude,
                        bearingDeg = bearing,
                        distance = d
                    )
                    grid.add(GridPoint(bearing, i, lat, lon))
                }
            }
            val elevations = terrain.elevations(grid.map { it.lat to it.lon })
            for ((i, gp) in grid.withIndex()) {
                val elev = elevations[i] ?: continue
                val d = ds[gp.dIndex]
                val a = angle(d, elev)
                val prev = best[gp.bearing]
                if (prev != null && prev.angle >= a) continue
                best[gp.bearing] = Winner(
                    sample = SkylineSample(gp.bearing, d, elev),
                    angle = a,
                    loD = if (gp.dIndex > 0) ds[gp.dIndex - 1] else d,
                    hiD = if (gp.dIndex + 1 < ds.size) ds[gp.dIndex + 1] else d
                )
            }
            return best
        }

        /** F. Distance refinement, [rounds] bracket-tightening passes.
         *  Even a dense schedule can straddle a summit — the coarse winner
         *  is then a flank sample and the silhouette renders low and
         *  lumpy. Each round samples the midpoints between the winner and
         *  its bracket edges, re-picks, and tightens the bracket around
         *  the (possibly moved) winner, so round 2 refines around the
         *  UPDATED winner rather than re-testing the same midpoints. */
        suspend fun refineDistances(best: HashMap<Double, Winner>, rounds: Int) {
            repeat(rounds) {
                data class RefinePoint(
                    val bearing: Double,
                    val distance: Double,
                    val isLowSide: Boolean,
                    val lat: Double,
                    val lon: Double
                )
                val refine = ArrayList<RefinePoint>(best.size * 2)
                for ((bearing, w) in best) {
                    val win = w.sample.distance
                    val mids = listOf((w.loD + win) / 2 to true, (win + w.hiD) / 2 to false)
                    for ((m, low) in mids) {
                        if (m > 0 && kotlin.math.abs(m - win) >= 5) {
                            val (lat, lon) = GeoCalculations.project(
                                observer.latitude, observer.longitude,
                                bearingDeg = bearing,
                                distance = m
                            )
                            refine.add(RefinePoint(bearing, m, low, lat, lon))
                        }
                    }
                }
                if (refine.isEmpty()) return
                val refined = terrain.elevations(refine.map { it.lat to it.lon })
                // Per-bearing low-/high-side probes: (distance, elevation, angle).
                val lows = HashMap<Double, Triple<Double, Double, Double>>()
                val highs = HashMap<Double, Triple<Double, Double, Double>>()
                for ((i, rp) in refine.withIndex()) {
                    val elev = refined[i] ?: continue
                    val a = angle(rp.distance, elev)
                    if (rp.isLowSide) lows[rp.bearing] = Triple(rp.distance, elev, a)
                    else highs[rp.bearing] = Triple(rp.distance, elev, a)
                }
                for (bearing in best.keys.toList()) {
                    var w = best[bearing] ?: continue
                    val lo = lows[bearing]
                    val hi = highs[bearing]
                    val oldWin = w.sample.distance
                    if (hi != null && hi.third > w.angle &&
                        hi.third >= (lo?.third ?: Double.NEGATIVE_INFINITY)
                    ) {
                        w = Winner(
                            sample = SkylineSample(bearing, hi.first, hi.second),
                            angle = hi.third, loD = oldWin, hiD = w.hiD
                        )
                    } else if (lo != null && lo.third > w.angle) {
                        w = Winner(
                            sample = SkylineSample(bearing, lo.first, lo.second),
                            angle = lo.third, loD = w.loD, hiD = oldWin
                        )
                    } else {
                        w = w.copy(loD = lo?.first ?: w.loD, hiD = hi?.first ?: w.hiD)
                    }
                    best[bearing] = w
                }
            }
        }

        // 1–3. Base fan: uniform `bearingStepDeg` lattice around the circle.
        val bearings = generateSequence(0.0) { it + bearingStepDeg }
            .takeWhile { it < 360.0 }
            .toList()
        val best = scanBearings(bearings)
        if (best.isEmpty()) return@withContext emptyList<SkylineSample>() to observerAlt

        // 4/F. Two bracket-tightening distance-refinement rounds.
        refineDistances(best, DISTANCE_REFINEMENT_ROUNDS)

        // 5/A. Adaptive bearing refinement: subdivide silhouette
        // discontinuities (cliff edges, near/far transitions, narrow
        // summits) with ONE midpoint bearing each — full distance scan
        // plus its own refinement — so the drawn line hugs the real
        // silhouette where it changes fastest. The result is deliberately
        // NOT a uniform lattice any more.
        val sorted = best.values.sortedBy { it.sample.bearing }
        val extraBearings = adaptiveRefinementBearings(
            samples = sorted.map {
                AdaptiveSample(it.sample.bearing, Math.toDegrees(it.angle), it.sample.distance)
            }
        )
        if (extraBearings.isNotEmpty()) {
            val extraBest = scanBearings(extraBearings)
            refineDistances(extraBest, DISTANCE_REFINEMENT_ROUNDS)
            for ((bearing, w) in extraBest) best[bearing] = w
        }

        best.values.map { it.sample }.sortedBy { it.bearing } to observerAlt
    }

    /** One per-bearing winner as [adaptiveRefinementBearings] sees it:
     *  [angleDeg] is the winning apparent-altitude angle in DEGREES.
     *  The Kotlin shape of iOS's `(bearing, angleDeg, distance)` tuple. */
    data class AdaptiveSample(val bearing: Double, val angleDeg: Double, val distance: Double)

    companion object {
        const val BEARING_STEP_DEG = 2.0
        const val MAX_RANGE_METERS = 200_000.0

        /** Adjacent-pair thresholds for the adaptive bearing pass: a jump
         *  in winning apparent angle of more than ~0.8° between
         *  neighbouring bearings, OR winning distances differing by more
         *  than 1.5×, marks a silhouette discontinuity (cliff edge,
         *  near/far transition, narrow summit straddled by the lattice)
         *  worth one midpoint bearing. */
        const val ADAPTIVE_ANGLE_JUMP_DEG = 0.8
        const val ADAPTIVE_DISTANCE_RATIO = 1.5

        /** Budget cap on inserted midpoint bearings per pass. 120 extra
         *  bearings × ~44 schedule distances ≈ 5 300 worst-case elevation
         *  queries on top of the base ~8 000 — still one amortised pass
         *  against the persistent cache, and in practice a real skyline
         *  has far fewer discontinuities than the cap. Worst pairs win
         *  the cap. */
        const val ADAPTIVE_MAX_EXTRA_BEARINGS = 120

        /** Bearing gaps wider than this carry no adjacency information
         *  (e.g. the wrap pair of a sparse test set, or a fan with
         *  missing sectors) and are never subdivided. */
        const val ADAPTIVE_MAX_PAIR_GAP_DEG = 45.0

        /** F. Number of distance-refinement (bracket-tightening) rounds
         *  run per bearing. Each round costs ≤2 elevation queries per
         *  bearing (≤2×360 ≈ 720 extra for both rounds over the full
         *  fan), almost all cache hits on recomputes, and quarters the
         *  distance uncertainty around the winner. */
        const val DISTANCE_REFINEMENT_ROUNDS = 2

        /**
         * A. Pure pair-selection for the adaptive bearing refinement:
         * given the per-bearing winners (sorted ascending by bearing),
         * return the midpoint bearings to insert — one between each
         * adjacent pair whose angles differ by more than [angleJumpDeg]
         * or whose distances differ by more than [distanceRatio]×.
         * Wrap-aware: the last↔first pair (e.g. 358°↔0°) is examined too,
         * and a midpoint of ≥360° wraps back into [0, 360). Capped at
         * [maxExtra], keeping the most severe discontinuities (largest
         * threshold overshoot) first. Mirrors iOS
         * `adaptiveRefinementBearings`.
         */
        fun adaptiveRefinementBearings(
            samples: List<AdaptiveSample>,
            angleJumpDeg: Double = ADAPTIVE_ANGLE_JUMP_DEG,
            distanceRatio: Double = ADAPTIVE_DISTANCE_RATIO,
            maxExtra: Int = ADAPTIVE_MAX_EXTRA_BEARINGS
        ): List<Double> {
            if (samples.size < 2 || maxExtra <= 0) return emptyList()
            data class Split(val mid: Double, val severity: Double)
            val splits = ArrayList<Split>()
            for (i in samples.indices) {
                val a = samples[i]
                val b = samples[(i + 1) % samples.size]   // last pairs with first
                var gap = b.bearing - a.bearing
                if (gap < 0) gap += 360.0                 // the 358°↔0° wrap pair
                if (gap <= 0.01 || gap >= ADAPTIVE_MAX_PAIR_GAP_DEG) continue
                val angleJump = kotlin.math.abs(a.angleDeg - b.angleDeg)
                val dLo = minOf(a.distance, b.distance)
                val dHi = maxOf(a.distance, b.distance)
                val ratio = if (dLo > 0) dHi / dLo else Double.POSITIVE_INFINITY
                if (angleJump <= angleJumpDeg && ratio <= distanceRatio) continue
                var mid = a.bearing + gap / 2
                if (mid >= 360.0) mid -= 360.0
                // Severity = how far past its threshold the worse criterion
                // is, so the cap keeps the most visible discontinuities.
                val severity = maxOf(angleJump / angleJumpDeg, ratio / distanceRatio)
                splits.add(Split(mid, severity))
            }
            splits.sortWith(compareByDescending<Split> { it.severity }.thenBy { it.mid })
            return splits.take(maxExtra).map { it.mid }
        }

        /**
         * Distance samples per bearing.
         *
         * Spacing matters: along a single bearing the silhouette is
         * whichever sample has the largest apparent-altitude angle, so
         * any peak that sits between two sample distances is invisible
         * to the picker. The previous hand-written schedule still had
         * 22–60 km holes past 48 km — wide enough to swallow entire
         * mountain ranges, which is exactly how the drawn skyline drifts
         * away from the real one.
         *
         * This schedule keeps the close-in metric step (~200 m), then
         * grows geometrically with a ≤1.15× ratio out to
         * [MAX_RANGE_METERS], so the miss window is never worse than
         * ±7 % of the distance at any range (the refinement round in
         * `computeSkyline` then tightens the winner further). ~44
         * samples × 180 bearings ≈ 8 000 elevation queries per full
         * recompute; the persistent cache in [TerrainElevationService]
         * amortises that to nearly zero on subsequent recomputes since
         * the user has to move ≥500 m before we re-run. Mirrors iOS
         * `skylineDistancesMeters`.
         */
        val DISTANCES_METERS: List<Double> = buildList {
            addAll(listOf(100.0, 200.0, 400.0, 600.0, 800.0, 1_000.0))
            val ratio = 1.15
            var d = 1_000.0
            while (d < MAX_RANGE_METERS) {
                d = minOf(d * ratio, MAX_RANGE_METERS)
                add(Math.round(d / 10.0) * 10.0)   // tidy to 10 m
            }
            set(size - 1, MAX_RANGE_METERS)
        }

        /** Grid cell step in degrees — must equal the elevation cache's
         *  ~110 m quantisation (3 decimals, see [TerrainElevationService.milliDeg])
         *  so every prefetched node is a distinct cache cell that a live
         *  skyline lookup from ANY observer in the area can hit. */
        const val OFFLINE_CELL_STEP_DEG = 0.001

        /** Max DEM cells cached per offline pack. A full-resolution
         *  (~110 m) area grid over a large radius would be millions of
         *  cells (a 100 km pack ≈ 3.3 M), so this bounds the download, the
         *  on-disk pack and the pinned-cell memory. When the radius would
         *  exceed it, the cached square shrinks (keeping full resolution
         *  within it) so any observer in the cached core gets a hole-free
         *  skyline and the far edges of a very large pack degrade
         *  gracefully. Mirrors iOS `offlineMaxDEMCells`. */
        const val OFFLINE_MAX_DEM_CELLS = 40_000

        /** Far-terrain ring layers: the skyline looks out to 200 km, but
         *  the full-resolution core above only reaches ~10 km before the
         *  cell budget bites. Rather than spending the whole budget on
         *  110 m resolution nobody can see at range (the fan's own lateral
         *  resolution is 2° of bearing ≈ 3.5 % of distance), the pack adds
         *  two coarser rings around its centre — matching how the skyline
         *  actually consumes data, so distant ranges resolve offline too.
         *  Mirrors iOS `offlineMediumRangeKm` / `offlineCoarseRangeKm` /
         *  `offlineMaxRingCells`. */
        const val OFFLINE_MEDIUM_RANGE_KM = 50.0
        const val OFFLINE_COARSE_RANGE_KM = 200.0
        const val OFFLINE_MAX_RING_CELLS = 50_000

        /**
         * All DEM cells to prefetch for an offline pack's full-resolution
         * core: a regular ~110 m lat/lon grid over the pack's bounding box
         * (centre ± [radiusKm]), snapped to the cache's milli-degree
         * lattice and capped to [OFFLINE_MAX_DEM_CELLS].
         *
         * Replaces the old single-observer fan, which only lined up with the
         * live lookups when the observer stood exactly at the pack centre —
         * off-centre observers' fans hit different cells, so every offline
         * lookup missed and the skyline went empty. An area grid covers the
         * cells any observer in the area will look up. Mirrors iOS
         * `SkylineCalculator.offlinePrefetchCoordinates`.
         */
        fun offlinePrefetchCoordinates(
            centerLat: Double,
            centerLon: Double,
            radiusKm: Double
        ): List<Pair<Double, Double>> = areaGrid(
            centerLat, centerLon, radiusKm,
            stepDeg = OFFLINE_CELL_STEP_DEG, maxCells = OFFLINE_MAX_DEM_CELLS
        )

        /** The ~550 m ring out to [OFFLINE_MEDIUM_RANGE_KM] — mid-range
         *  terrain for the offline skyline. Keyed by
         *  [TerrainElevationService.mediumMilliDeg]. Mirrors iOS
         *  `offlineMediumPrefetchCoordinates`. */
        fun offlineMediumPrefetchCoordinates(
            centerLat: Double,
            centerLon: Double
        ): List<Pair<Double, Double>> = areaGrid(
            centerLat, centerLon, OFFLINE_MEDIUM_RANGE_KM,
            stepDeg = TerrainElevationService.MEDIUM_STEP_DEG, maxCells = OFFLINE_MAX_RING_CELLS
        )

        /** The ~2.2 km ring out to [OFFLINE_COARSE_RANGE_KM] (the skyline's
         *  full range) — distant ranges for the offline skyline. Keyed by
         *  [TerrainElevationService.coarseMilliDeg]. Mirrors iOS
         *  `offlineCoarsePrefetchCoordinates`. */
        fun offlineCoarsePrefetchCoordinates(
            centerLat: Double,
            centerLon: Double
        ): List<Pair<Double, Double>> = areaGrid(
            centerLat, centerLon, OFFLINE_COARSE_RANGE_KM,
            stepDeg = TerrainElevationService.COARSE_STEP_DEG, maxCells = OFFLINE_MAX_RING_CELLS
        )

        /**
         * Shared area-grid builder: a regular [stepDeg] lat/lon grid over
         * centre ± [radiusKm], snapped to that step's lattice and capped to
         * [maxCells] by shrinking the covered square (never the resolution),
         * so a live lookup inside the covered core never lands in a gap.
         * Mirrors iOS `areaGrid(center:radiusKm:stepDeg:maxCells:)`.
         */
        private fun areaGrid(
            centerLat: Double,
            centerLon: Double,
            radiusKm: Double,
            stepDeg: Double,
            maxCells: Int
        ): List<Pair<Double, Double>> {
            val step = stepDeg
            val metersPerDegLat = 111_320.0
            val cosLat = maxOf(0.01, kotlin.math.cos(Math.toRadians(centerLat)))
            var halfLatDeg = (radiusKm * 1000.0) / metersPerDegLat
            var halfLonDeg = (radiusKm * 1000.0) / (metersPerDegLat * cosLat)

            // Shrink the covered square (NOT the resolution) if a full-res
            // grid would blow the cell budget, so a lookup never lands in a gap.
            val latNodes = ((2 * halfLatDeg) / step).toLong() + 1
            val lonNodes = ((2 * halfLonDeg) / step).toLong() + 1
            val total = latNodes * lonNodes
            if (total > maxCells) {
                val scale = kotlin.math.sqrt(maxCells.toDouble() / total.toDouble())
                halfLatDeg *= scale
                halfLonDeg *= scale
            }

            // Snap the centre to the layer's lattice and step exactly one
            // cell at a time so every node maps to its own distinct cache
            // cell (no phase drift, no gaps, no duplicates vs the live
            // lookups).
            val cLat = Math.round(centerLat / step) * step
            val cLon = Math.round(centerLon / step) * step
            val latSteps = (halfLatDeg / step).toInt()
            val lonSteps = (halfLonDeg / step).toInt()

            val out = ArrayList<Pair<Double, Double>>((2 * latSteps + 1) * (2 * lonSteps + 1))
            var i = -latSteps
            while (i <= latSteps) {
                val lat = cLat + i * step
                var j = -lonSteps
                while (j <= lonSteps) {
                    out.add(lat to (cLon + j * step))
                    j++
                }
                i++
            }
            return out
        }
    }
}
