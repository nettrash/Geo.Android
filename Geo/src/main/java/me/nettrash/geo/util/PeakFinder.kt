package me.nettrash.geo.util

import android.content.Context
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import me.nettrash.geo.ar.TerrainElevationService
import me.nettrash.geo.data.model.MountainData
import me.nettrash.geo.data.model.NearbyPeak
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers nearby peaks by merging:
 *
 *   • Live OpenStreetMap Overpass results — `natural=peak` **nodes
 *     only** within [searchRadius]. Ways and relations are
 *     deliberately excluded because they're mostly noise (summit
 *     ridges and large areas whose centre coordinate isn't a peak
 *     location). Peaks missing an `ele` tag get their altitude
 *     resolved via [TerrainElevationService] in one batched call,
 *     and are dropped entirely if the DEM also has nothing for
 *     them — no placeholder altitudes that float fake peaks above
 *     the horizon.
 *   • Known mountains from the app's bundled mountain data
 *     (`MountainData.highest`, `sevenPeaks`, `snowLeopardOfRussia`),
 *     out to [maxPeakRenderDistanceM] rather than [searchRadius] —
 *     they need no network, and a famous summit is something you
 *     name from a long way off.
 *
 * Direct port of iOS `Nature/PeakFinder.swift`. Two behaviours that
 * weren't in the previous Android version but matter a lot:
 *
 *   1. **Merge-by-ID with hysteresis**: a fresh search result merges
 *      into the existing list rather than replacing it. Peaks survive
 *      until they fall outside `searchRadius × 2`; this stops the AR
 *      overlay from flickering when Overpass returns nothing for a
 *      tick.
 *   2. **TTL eviction**: peaks not re-confirmed in [peakTTL] ms drop
 *      out so the list doesn't grow forever when the user sits still
 *      for hours.
 */
@Singleton
class PeakFinder @Inject constructor(
    private val terrain: TerrainElevationService,
    @param:ApplicationContext private val context: Context
) {

    /**
     * Outcome of a nearby-peak search. `stale` means "these are the peaks we
     * have, but they are not a confirmed-current answer" — restored from disk
     * after a restart, or kept alive through a failed search — and `error` is
     * the reason the network let us down. The Nature view says "possibly
     * outdated" instead of letting an old horizon look like a live one, and
     * never shows a bare `0` that could be read either way.
     */
    data class PeakSearchResult(
        val peaks: List<NearbyPeak>,
        val stale: Boolean = false,
        val error: String? = null
    )

    /// "The server answered and there are no peaks here" vs "we never got an
    /// answer". Collapsing those two is what turned a loaded Overpass mirror
    /// into a silently empty Nature tab that then refused to retry.
    private sealed class PeakOutcome {
        data class Success(val peaks: List<NearbyPeak>) : PeakOutcome()
        data class Failure(val reason: String) : PeakOutcome()
    }

    /// Result of trying ONE mirror: a rejected attempt is a reason to try the
    /// next one, never an answer.
    private sealed class OverpassAttempt {
        data class Peaks(val peaks: List<NearbyPeak>) : OverpassAttempt()
        data class Rejected(val reason: String) : OverpassAttempt()
    }

    private val searchRadius = 5_000.0   // 5 km

    /** How far a peak may be from the observer and still be kept for the AR
     *  overlay. Deliberately MUCH larger than [searchRadius]: the live Overpass
     *  query only reaches 5 km (to stay polite on the shared endpoint), but a
     *  downloaded offline pack holds peaks out to 100 km — and in a camera
     *  peak-identifier the summits you point at are the distant ones on the
     *  horizon. Capping at `searchRadius × 2` threw ~90 % of a big pack away.
     *  The AR projection already culls peaks below the horizon / off screen, so
     *  this is a coverage bound, not a visibility one. Mirrors iOS
     *  `PeakFinder.maxPeakRenderDistance`. */
    private val maxPeakRenderDistanceM = 80_000.0   // 80 km

    private val minimumSearchDistance = 500.0 // re-search after 500 m of movement
    private val peakTtlMs = 60L * 60L * 1000L  // 1 hour

    /**
     * A previous result may be reused for at most this long. Distance alone is
     * not a validity rule: standing still pinned `lastSearchLocation` forever,
     * which made the "no movement" fast path hit indefinitely and a failed
     * search never retry. Age makes a stationary user ask again. Mirrors iOS
     * `PeakFinder.maxCacheAge`.
     */
    private val maxCacheAgeMs = 15L * 60L * 1000L

    /// After a failed search, wait this long before asking Overpass again — the
    /// AR view asks every few seconds, and a loaded shared endpoint must not be
    /// hammered for the whole outage.
    private val retryBackoffMs = 60L * 1000L

    /// How long a result loaded from disk may still be shown. Peaks are static
    /// geography, so a clearly-marked week-old set beats an empty horizon while
    /// Overpass is unhealthy.
    private val persistedMaxAgeMs = 7L * 24L * 60L * 60L * 1000L

    /// Only reuse the persisted set when it was cached within this distance of
    /// the current position: peaks 60 km away in another valley are not
    /// "nearby peaks".
    private val persistedReuseDistanceM = 50_000.0

    /// Per-mirror transport wait; `fetchDeadlineMs` bounds the whole search.
    private val endpointTimeoutSeconds = 8
    private val fetchDeadlineMs = 30_000L

    private var lastSuccessAtMs: Long? = null
    private var lastFailureAtMs: Long? = null

    /// Guards against overlapping searches: the AR tab asks every few seconds
    /// while a failover search can take tens of seconds, and concurrent
    /// searches would multiply the load on a shared public endpoint.
    private val searching = AtomicBoolean(false)

    /// The persisted snapshot is read once, lazily, on the first search — only
    /// then do we have a location to judge "is this snapshot even for here?".
    private var didLoadPersisted = false

    /// Rotating index into [overpassEndpoints]; atomic because searches run on
    /// `Dispatchers.IO` threads.
    private val endpointCursor = AtomicInteger(0)

    /** Max peaks kept in the merged set (nearest-first eviction). Larger than
     *  before so a dense range doesn't evict the distant flagship summits (the
     *  whole point of pointing the camera at the horizon) in favour of nearer
     *  foothills. Only in-view peaks are drawn, so the on-screen count stays
     *  small regardless. Mirrors iOS. */
    private val maxRetainedPeaks = 300

    private var lastSearchLocation: Location? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(endpointTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(endpointTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor(minIntervalMs = 1000))
        .build()

    /**
     * Search for peaks near [location] and merge with [currentPeaks].
     * Safe to call from a Compose coroutine — the network work runs
     * on `Dispatchers.IO`.
     */
    suspend fun searchPeaks(
        location: Location,
        mountainsData: MountainData?,
        currentPeaks: List<NearbyPeak>,
        offlinePeaks: List<NearbyPeak> = emptyList(),
        observerAltitude: Double? = null
    ): PeakSearchResult {
        // Observer altitude for the horizon-visibility cut. Prefer the caller's
        // (barometer-preferred) value — GPS altitude can be tens of metres off,
        // or garbage-low, which would wrongly hide visible peaks.
        val obsAlt = observerAltitude ?: location.altitude

        // First search since the process started: the snapshot from the last
        // successful search is the baseline until the network says otherwise,
        // so an Overpass outage shows the last known peaks instead of nothing.
        var workingPeaks = currentPeaks
        if (!didLoadPersisted) {
            didLoadPersisted = true
            if (workingPeaks.isEmpty()) {
                val restored = readSnapshot(location)
                if (restored != null) {
                    workingPeaks = restored
                }
            }
        }

        val now = System.currentTimeMillis()
        val last = lastSearchLocation
        // Reuse the previous result while it is still trustworthy: no movement
        // AND an answer young enough to keep. Deliberately no
        // `workingPeaks.isNotEmpty()` — during an outage the set can
        // legitimately be empty, and "we asked a few seconds ago and it
        // refused" is exactly when NOT to ask again.
        if (last != null &&
            last.distanceTo(location) < minimumSearchDistance &&
            canReuseResult(now, lastSuccessAtMs, lastFailureAtMs, maxCacheAgeMs, retryBackoffMs)
        ) {
            // No movement AND a result young enough to trust — just refresh
            // distance/bearing of the existing set against the latest location
            // so visual fade-by-distance stays accurate.
            return PeakSearchResult(refreshGeometry(workingPeaks, location, obsAlt))
        }

        if (!searching.compareAndSet(false, true)) {
            // A failover search can outlive the view's refresh tick; fold the
            // duplicate request into the one in flight rather than stack
            // requests onto an already-loaded endpoint.
            return PeakSearchResult(
                peaks = refreshGeometry(workingPeaks, location, obsAlt),
                stale = lastSuccessAtMs == null
            )
        }
        // Stamped BEFORE the request, so a tick landing inside the retry backoff
        // takes the reuse path above instead of firing another request at a
        // loaded mirror. Only [lastSuccessAtMs] — the freshness stamp — waits
        // for a confirmed answer.
        lastSearchLocation = location
        try {
            return executeSearch(location, mountainsData, workingPeaks, offlinePeaks, obsAlt)
        } finally {
            searching.set(false)
        }
    }

    /**
     * The network half of [searchPeaks], run once (guarded by [searching]).
     * Stamps the cache — and writes the on-disk snapshot — only from
     * [PeakOutcome.Success]; a failure keeps the previous peaks and marks them
     * stale so an outage can never be mistaken for "no peaks here".
     */
    private suspend fun executeSearch(
        location: Location,
        mountainsData: MountainData?,
        previousPeaks: List<NearbyPeak>,
        offlinePeaks: List<NearbyPeak>,
        obsAlt: Double
    ): PeakSearchResult {
        val now = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            val osmDeferred = async {
                fetchPeakOutcome(location.latitude, location.longitude, searchRadius)
            }
            val knownPeaks = findKnownMountains(location, mountainsData)

            val freshPeaks = mutableListOf<NearbyPeak>()

            when (val outcome = osmDeferred.await()) {
                is PeakOutcome.Failure -> {
                    // A failed search is NOT a result. Keep showing what we
                    // already have (live peaks, or the snapshot restored from
                    // disk), age their geometry against the current fix, and
                    // mark them stale. This used to merge an empty OSM set in
                    // and stamp `lastSearchLocation`, so `peakTtlMs` then
                    // dropped everything the failure could not re-confirm —
                    // downloaded offline-pack peaks included — and nothing
                    // retried until the user walked 500 m.
                    lastFailureAtMs = now
                    AppLog.ar.error("Nearby peaks: search failed (${outcome.reason})")
                    return@withContext PeakSearchResult(
                        peaks = refreshGeometry(previousPeaks, location, obsAlt),
                        stale = true,
                        error = outcome.reason
                    )
                }
                is PeakOutcome.Success -> freshPeaks.addAll(outcome.peaks)
            }

            // Only a confirmed answer counts as fresh. (The search *location* was
            // stamped before the request, to pace retries; the *result* only ages
            // from a success, so a failure never becomes the thing we serve.)
            lastSuccessAtMs = now
            lastFailureAtMs = null

            // Peaks neither OSM nor the DEM can give an altitude for are
            // dropped inside [fetchPeakOutcome] — no placeholder heights.
            for (peak in knownPeaks) {
                if (!isDuplicate(peak, freshPeaks)) {
                    freshPeaks.add(peak)
                }
            }

            // Offline-pack peaks: make the area's named peaks appear with NO
            // signal (and complement the live 5 km query when online). Stamp
            // them fresh so the TTL prune below doesn't drop them on merge;
            // the drop-radius filter still removes any too far to see.
            for (peak in offlinePeaks) {
                if (!isDuplicate(peak, freshPeaks)) {
                    freshPeaks.add(peak.copy(lastSeenAt = now))
                }
            }

            // Merge: previous list provides identity continuity,
            // fresh results win on duplicate IDs (so distance / bearing
            // / lastSeenAt get updated).
            val byId = HashMap<UUID, NearbyPeak>()
            for (p in previousPeaks) byId[p.id] = p
            for (p in freshPeaks) byId[p.id] = p

            // Keep peaks out to the render distance (not just the 5 km search
            // radius) so downloaded offline packs show distant horizon summits,
            // then age out anything not re-confirmed within the TTL, and drop
            // peaks hidden below the Earth's bulge (only keep what's really
            // visible from here).
            val dropRadius = maxPeakRenderDistanceM
            val merged = byId.values
                .filter { peak ->
                    val pl = Location("").apply {
                        latitude = peak.latitude
                        longitude = peak.longitude
                    }
                    val d = location.distanceTo(pl).toDouble()
                    d <= dropRadius && (now - peak.lastSeenAt) <= peakTtlMs &&
                        GeoCalculations.isAboveHorizon(obsAlt, peak.altitude, d)
                }
                .map { peak ->
                    val d = GeoCalculations.distanceBetween(
                        location.latitude, location.longitude,
                        peak.latitude, peak.longitude
                    )
                    val b = GeoCalculations.bearing(
                        location.latitude, location.longitude,
                        peak.latitude, peak.longitude
                    )
                    peak.copy(distance = d, bearing = b)
                }
                .sortedBy { it.distance }
                .take(maxRetainedPeaks)

            // Persisted on success only, so a failed search can never
            // overwrite a usable snapshot with an empty one.
            writeSnapshot(merged, location)
            PeakSearchResult(merged)
        }
    }

    private fun refreshGeometry(
        peaks: List<NearbyPeak>,
        location: Location,
        obsAlt: Double
    ): List<NearbyPeak> {
        // Apply the same TTL + drop-radius + horizon-visibility eviction the
        // full-search branch does, so a stationary user (who only ever takes this
        // path) still ages out peaks that have drifted out of view.
        val now = System.currentTimeMillis()
        val dropRadius = maxPeakRenderDistanceM
        return peaks
            .filter { peak ->
                val pl = Location("").apply {
                    latitude = peak.latitude
                    longitude = peak.longitude
                }
                val d = location.distanceTo(pl).toDouble()
                d <= dropRadius && (now - peak.lastSeenAt) <= peakTtlMs &&
                    GeoCalculations.isAboveHorizon(obsAlt, peak.altitude, d)
            }
            .map { peak ->
                val d = GeoCalculations.distanceBetween(
                    location.latitude, location.longitude,
                    peak.latitude, peak.longitude
                )
                val b = GeoCalculations.bearing(
                    location.latitude, location.longitude,
                    peak.latitude, peak.longitude
                )
                peak.copy(distance = d, bearing = b)
            }
    }

    private fun isDuplicate(peak: NearbyPeak, existing: List<NearbyPeak>): Boolean {
        return existing.any { other ->
            GeoCalculations.distanceBetween(
                peak.latitude, peak.longitude,
                other.latitude, other.longitude
            ) < 200
        }
    }

    /**
     * Query OpenStreetMap Overpass API for `natural=peak` nodes
     * only.
     *
     * **Why nodes only**: `way["natural"="peak"]` and
     * `relation["natural"="peak"]` bring in summit ridges and large
     * areas whose `center` coordinate is meaningless as a peak
     * location, plus they massively widen the result set with low-
     * quality entries. The brief explicitly calls this out as a
     * pitfall to avoid — keeping it tight matches iOS after the
     * `MKLocalSearch("mountain peak")` removal.
     *
     * **Altitudes**: a peak's `ele` tag is preferred. If absent, we
     * batch all such peaks into one Open-Elevation lookup; if that
     * still fails, the peak is dropped rather than rendered at a
     * placeholder altitude like `userAltitude + 100` (also a brief-
     * called-out pitfall — those placeholders put fake peaks on the
     * horizon).
     */
    /** Fetch OSM `natural=peak` nodes for an arbitrary area. Reused by the
     *  offline-pack prefetch with a much larger radius than the live 5 km
     *  search; runs on `Dispatchers.IO`. */
    suspend fun fetchPeaksForArea(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): List<NearbyPeak> = withContext(Dispatchers.IO) {
        searchOpenStreetMapArea(centerLat, centerLon, radiusMeters)
    }

    private suspend fun searchOpenStreetMapArea(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): List<NearbyPeak> = when (val outcome = fetchPeakOutcome(centerLat, centerLon, radiusMeters)) {
        is PeakOutcome.Success -> outcome.peaks
        // The offline-pack prefetch already ignores an empty fetch and rebuilds
        // the pack later, so it does not need the failure reason.
        is PeakOutcome.Failure -> emptyList()
    }

    /**
     * Ask the mirrors in turn until one answers, from a rotating start and
     * under one total deadline.
     *
     * `.Failure` means *no mirror answered*; `.Success(emptyList())` means the
     * query genuinely found nothing. The caller must treat those differently —
     * keep the previous peaks vs. replace them — and the old
     * `if (!response.isSuccessful) return emptyList()` collapsed exactly that
     * distinction, which is how a loaded `overpass-api.de` came to look like
     * "there are no peaks near Belgrade".
     */
    private suspend fun fetchPeakOutcome(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): PeakOutcome = withContext(Dispatchers.IO) {
        // Quantise lat/lon to ~110 m grid before sending to the
        // public API. Uses the shared round-half helper so Overpass,
        // Open-Meteo and the elevation cache all bucket identically,
        // matching iOS.
        val qLat = TerrainElevationService.quantise(centerLat)
        val qLon = TerrainElevationService.quantise(centerLon)
        val radiusInt = radiusMeters.toInt()

        val query = """
            [out:json][timeout:10];
            node["natural"="peak"](around:$radiusInt,$qLat,$qLon);
            out body;
        """.trimIndent()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val reasons = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + fetchDeadlineMs
        for (endpoint in rotatedEndpoints(endpointCursor.getAndIncrement())) {
            val host = endpoint.substringAfter("://").substringBefore('/')
            if (System.currentTimeMillis() >= deadline) {
                reasons += "$host: skipped (search deadline)"
                break
            }
            when (val attempt = attemptOverpass(endpoint, encodedQuery, centerLat, centerLon, radiusMeters)) {
                is OverpassAttempt.Peaks -> return@withContext PeakOutcome.Success(attempt.peaks)
                is OverpassAttempt.Rejected -> reasons += "$host: ${attempt.reason}"
            }
        }
        val summary = if (reasons.isEmpty()) "no Overpass endpoint reachable"
                      else reasons.joinToString("; ")
        AppLog.ar.error("Overpass peak search failed on all ${overpassEndpoints.size} endpoints — $summary")
        PeakOutcome.Failure(summary)
    }

    /**
     * Try ONE mirror. Everything that used to be a silent `return emptyList()`
     * is now a logged [OverpassAttempt.Rejected], so the failover loop can move
     * on and the failure summary names the instance that let us down. The
     * `RetryInterceptor` on [client] still owns the single 429/5xx retry and the
     * inter-request spacing.
     */
    private suspend fun attemptOverpass(
        endpoint: String,
        encodedQuery: String,
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): OverpassAttempt {
        val host = endpoint.substringAfter("://").substringBefore('/')
        val elements: List<OverpassElement> = try {
            val request = Request.Builder()
                .url("$endpoint?data=$encodedQuery")
                .header("User-Agent", TerrainElevationService.USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.ar.warn("Overpass $host returned HTTP ${response.code}")
                    return OverpassAttempt.Rejected("HTTP ${response.code}")
                }
                json.decodeFromString<OverpassResponse>(response.body.string()).elements
            }
        } catch (e: Exception) {
            AppLog.ar.warn("Overpass $host failed", e)
            return OverpassAttempt.Rejected(e.message ?: e.javaClass.simpleName)
        }

        // Stage 1: candidates that have an `ele` tag — keep them
        // directly. Candidates without `ele` go to a second stage
        // for a batched DEM lookup.
        data class Candidate(
            val name: String,
            val lat: Double,
            val lon: Double,
            val distance: Double,
            val bearing: Double,
            val knownAltitude: Double?  // null → needs DEM resolution
        )

        val candidates = elements.mapNotNull { element ->
            val name = element.tags?.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lat = element.lat ?: return@mapNotNull null
            val lon = element.lon ?: return@mapNotNull null

            val d = GeoCalculations.distanceBetween(
                centerLat, centerLon, lat, lon
            )
            if (d > radiusMeters) return@mapNotNull null

            val b = GeoCalculations.bearing(
                centerLat, centerLon, lat, lon
            )
            val ele = element.tags.ele?.toDoubleOrNull()
            Candidate(name, lat, lon, d, b, ele)
        }

        // Stage 2: batch-resolve any candidate without an `ele` via
        // the same Open-Elevation client the skyline uses. One HTTP
        // round-trip for the whole set.
        val needingDem = candidates.filter { it.knownAltitude == null }
        val resolvedAltitudes: List<Double?> = if (needingDem.isEmpty()) {
            emptyList()
        } else {
            terrain.elevations(needingDem.map { it.lat to it.lon })
        }

        // Recombine, dropping any candidate whose altitude is still
        // unknown (neither `ele` nor DEM produced a value).
        var demIdx = 0
        return OverpassAttempt.Peaks(candidates.mapNotNull { c ->
            val altitude = c.knownAltitude
                ?: resolvedAltitudes.getOrNull(demIdx++)
                ?: return@mapNotNull null
            NearbyPeak.create(
                name = c.name,
                latitude = c.lat,
                longitude = c.lon,
                altitude = altitude,
                distance = c.distance,
                bearing = c.bearing
            )
        })
    }

    private fun findKnownMountains(location: Location, data: MountainData?): List<NearbyPeak> {
        if (data == null) return emptyList()

        val results = mutableListOf<NearbyPeak>()
        val allLists = listOfNotNull(data.highest, data.sevenPeaks, data.snowLeopardOfRussia)

        for (list in allLists) {
            for (mountain in list.mountains.orEmpty()) {
                val lat = mountain.coordinates?.latitude ?: continue
                val lon = mountain.coordinates.longitude ?: continue
                val name = mountain.name ?: continue

                val distance = GeoCalculations.distanceBetween(
                    location.latitude, location.longitude, lat, lon
                )
                // Bound by the render distance, NOT the 5 km Overpass radius.
                // These summits are bundled, so they cost no network at all —
                // and they're exactly the ones you look at from far away. At
                // 5 km a curated peak only appeared when you were already
                // standing on it, which is where you cannot see it. The merge
                // in searchPeaks still applies the horizon and TTL cuts.
                if (distance > maxPeakRenderDistanceM) continue

                val bearing = GeoCalculations.bearing(
                    location.latitude, location.longitude, lat, lon
                )

                results.add(
                    NearbyPeak.create(
                        name = name,
                        latitude = lat,
                        longitude = lon,
                        altitude = mountain.height?.toDouble() ?: 0.0,
                        distance = distance,
                        bearing = bearing
                    )
                )
            }
        }
        return results
    }

    // ─── Result freshness & persistence ───────────────────────────────
    //
    // Three pieces make "the network failed" stop looking like "there are no
    // peaks here": the pure reuse decision the no-move path consults (see
    // companion), a snapshot written on success only, and that snapshot
    // restored — marked stale — on the next start.

    /** On-disk form of the last successful search. `distance` / `bearing` are
     *  derived against the live fix, so only identity and altitude are stored. */
    @Serializable
    private data class PeakSnapshot(
        val centerLat: Double,
        val centerLon: Double,
        val savedAtMs: Long,
        val entries: List<Entry>
    ) {
        @Serializable
        data class Entry(
            val name: String,
            val latitude: Double,
            val longitude: Double,
            val altitude: Double
        )
    }

    private fun snapshotFile(): File = File(context.filesDir, "nearby_peaks_cache.json")

    /**
     * Restore the persisted snapshot if it is recent enough and near enough to
     * be about *here*. `lastSeenAt` is re-stamped on load — those peaks were not
     * "failing to be re-confirmed", they were on disk — and the `stale` flag on
     * the result is what tells the user they may be out of date.
     */
    private fun readSnapshot(location: Location): List<NearbyPeak>? {
        return try {
            val file = snapshotFile()
            if (!file.exists()) return null
            val snapshot = json.decodeFromString<PeakSnapshot>(file.readText())
            val ageMs = System.currentTimeMillis() - snapshot.savedAtMs
            if (ageMs > persistedMaxAgeMs) return null
            val cachedCenter = Location("").apply {
                latitude = snapshot.centerLat
                longitude = snapshot.centerLon
            }
            if (location.distanceTo(cachedCenter) > persistedReuseDistanceM) return null
            val now = System.currentTimeMillis()
            snapshot.entries.map {
                NearbyPeak.create(
                    name = it.name,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    altitude = it.altitude,
                    distance = 0.0,
                    bearing = 0.0,
                    lastSeenAt = now
                )
            }.also {
                AppLog.ar.info("Restored ${it.size} nearby peaks from disk (${ageMs / 60000} min old)")
            }
        } catch (e: Exception) {
            AppLog.ar.warn("Could not restore nearby-peak snapshot", e)
            null
        }
    }

    /** Write the last good result. Called from the success path only. */
    private fun writeSnapshot(peaks: List<NearbyPeak>, center: Location) {
        try {
            val snapshot = PeakSnapshot(
                centerLat = center.latitude,
                centerLon = center.longitude,
                savedAtMs = System.currentTimeMillis(),
                entries = peaks.map {
                    PeakSnapshot.Entry(
                        name = it.name,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        altitude = it.altitude
                    )
                }
            )
            snapshotFile().writeText(json.encodeToString(PeakSnapshot.serializer(), snapshot))
        } catch (e: Exception) {
            AppLog.ar.warn("Could not persist nearby-peak snapshot", e)
        }
    }

    companion object {
        /**
         * The public Overpass mirrors, in preference order.
         *
         * `overpass-api.de` is the canonical instance and the one this class
         * used to hardcode — it is also the first to answer `502 "This Overpass
         * API server is currently at capacity"`, `429`, or nothing at all when a
         * busy shared IP exhausts its quota, while the mirrors routinely serve
         * the identical query. One unhealthy instance no longer means an empty
         * Nature tab. Keep in step with iOS `PeakFinder.overpassEndpoints`.
         */
        val overpassEndpoints = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.openstreetmap.fr/api/interpreter",
            "https://overpass.osm.ch/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        )

        /**
         * Rotate the mirror list so a fleet of phones (or the second half of a
         * retry) does not keep leading with the same overloaded instance.
         */
        fun rotatedEndpoints(start: Int): List<String> {
            val count = overpassEndpoints.size
            if (count == 0) return emptyList()
            val offset = ((start % count) + count) % count
            return overpassEndpoints.drop(offset) + overpassEndpoints.take(offset)
        }

        /**
         * Pure reuse decision, extracted so the cache rules are unit-testable
         * without a network. A result is reusable while it is young; once it is
         * stale it is still reused only while the retry backoff from a failed
         * attempt runs — so the peaks on screen are never dropped for nothing
         * and a loaded mirror is not hammered on every refresh tick.
         */
        fun canReuseResult(nowMs: Long, lastSuccessAtMs: Long?, lastFailureAtMs: Long?,
                           maxCacheAgeMs: Long, retryBackoffMs: Long): Boolean {
            // A confirmed result is reusable while it is young.
            if (lastSuccessAtMs != null && nowMs - lastSuccessAtMs <= maxCacheAgeMs) return true
            // Otherwise — stale, or never succeeded at all — keep showing what we
            // have only while the retry backoff from a failed attempt is running.
            val failure = lastFailureAtMs ?: return false
            return nowMs - failure < retryBackoffMs
        }
    }
}

@Serializable
private data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList()
)

// Nodes-only query → no `center` field; everything is a top-level
// (lat, lon) pair.
@Serializable
private data class OverpassElement(
    val lat: Double? = null,
    val lon: Double? = null,
    val tags: OverpassTags? = null
)

@Serializable
private data class OverpassTags(
    val name: String? = null,
    val ele: String? = null
)
