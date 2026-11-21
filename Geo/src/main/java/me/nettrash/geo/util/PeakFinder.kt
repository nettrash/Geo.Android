package me.nettrash.geo.util

import android.content.Context
import android.location.Location
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlin.math.floor

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
 *   2. **TTL eviction**: peaks not re-confirmed in [peakTtlMs] ms drop
 *      out so the list doesn't grow forever when the user sits still
 *      for hours — but only when a confirmed answer arrives, so an
 *      outage never ages the horizon away.
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
     *
     * Both describe the last real outcome, which [PeakFinder] keeps: a reuse or
     * in-flight result confirms nothing, so it reports them unchanged instead
     * of resetting them on every refresh tick.
     */
    data class PeakSearchResult(
        val peaks: List<NearbyPeak>,
        val stale: Boolean = false,
        val error: String? = null
    )

    /// "The server answered and there are no peaks here" vs "we never got an
    /// answer". Collapsing those two is what turned a loaded Overpass mirror
    /// into a silently empty Nature tab that then refused to retry. Public
    /// because the offline-pack download ([fetchPeaksForArea]) must tell them
    /// apart too: a pack saved from a failure reads as "no peaks in this area".
    sealed class PeakOutcome {
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

    /// When the last outcome ARRIVED. `@Volatile`, like the outcome state below:
    /// written where a search's outcome lands, read by the next search, which
    /// may run on another thread.
    @Volatile private var lastSuccessAtMs: Long? = null
    @Volatile private var lastFailureAtMs: Long? = null

    /// What the last outcome said — the flag behind [PeakSearchResult.stale] and
    /// the reason behind [PeakSearchResult.error]. Kept here rather than only on
    /// each result because the reuse and in-flight paths confirm nothing and
    /// must hand back what the last search found; they used to clear both on
    /// the very next refresh tick.
    @Volatile private var peaksAreStale = false
    @Volatile private var lastSearchError: String? = null

    /// The peaks last handed out: returned, or published early. The caller keeps
    /// the set it shows, but GeoViewModel lives as long as the activity and this
    /// class as long as the process. After Back finishes the activity (Android
    /// 9–11), a cached process's next GeoViewModel asks with an empty set while
    /// the reuse state above still says "nothing to ask", and it got that empty
    /// set back for up to 15 min. A caller that shows nothing although peaks
    /// were handed out has lost them, so the search continues from these. (iOS
    /// has no such split: its PeakFinder owns `peaks`.)
    @Volatile private var lastHandedOut: List<NearbyPeak> = emptyList()

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

    /** Latitude band of [DuplicateIndex]: 0.002° is ~222 m everywhere, more
     *  than the 200 m of the duplicate rule ([isDuplicate]). */
    private val duplicateBandDegrees = 0.002

    private var lastSearchLocation: Location? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** Time source for every freshness, TTL, backoff and deadline decision, and
     *  for the `lastSeenAt` of every peak this class creates (the TTL compares
     *  the two). A seam, so a test can stand in for the hours an outage lasts. */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /** The Overpass client. A seam, so a test can answer per mirror with no
     *  network at all. */
    @VisibleForTesting
    internal var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(endpointTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(endpointTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor(minIntervalMs = 1000))
        .build()

    /**
     * Search for peaks near [location] and merge with [currentPeaks].
     * Safe to call from a Compose coroutine — the network work runs
     * on `Dispatchers.IO`. An empty [currentPeaks] after peaks were handed out
     * means the caller lost them, and the search continues from those (see
     * [lastHandedOut]).
     *
     * When the search goes to the network, [onLocalPeaks] is called once, on
     * the caller's context and before the network is awaited, with what needs
     * no network: [currentPeaks] (or what was last handed out, or the snapshot
     * restored from disk) merged with the bundled mountains and [offlinePeaks].
     * A failover across every mirror can take ~25 s, and those peaks must not
     * wait for it — nor vanish when it fails.
     */
    suspend fun searchPeaks(
        location: Location,
        mountainsData: MountainData?,
        currentPeaks: List<NearbyPeak>,
        offlinePeaks: List<NearbyPeak> = emptyList(),
        observerAltitude: Double? = null,
        onLocalPeaks: (PeakSearchResult) -> Unit = {}
    ): PeakSearchResult {
        // Observer altitude for the horizon-visibility cut. Prefer the caller's
        // (barometer-preferred) value — GPS altitude can be tens of metres off,
        // or garbage-low, which would wrongly hide visible peaks.
        val obsAlt = observerAltitude ?: location.altitude

        // A caller with nothing on screen may only have lost what it was handed
        // (see [lastHandedOut]): continue from that, as the caller before it
        // would have.
        var workingPeaks = currentPeaks.ifEmpty { lastHandedOut }
        // First search since the process started: the snapshot from the last
        // successful search is the baseline until the network says otherwise,
        // so an Overpass outage shows the last known peaks instead of nothing.
        if (!didLoadPersisted) {
            didLoadPersisted = true
            if (workingPeaks.isEmpty()) {
                val restored = readSnapshot(location)
                if (restored != null) {
                    workingPeaks = restored
                    // Not a confirmed answer, so "possibly outdated" — unless a
                    // downloaded pack covers here (see [packCoversHere]).
                    peaksAreStale = restored.isNotEmpty() && !packCoversHere(location, offlinePeaks)
                }
            }
        }

        val now = clock()
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
            // so visual fade-by-distance stays accurate. This confirms nothing,
            // so the stale flag and the error stay as the last outcome left them.
            return resultOf(refreshGeometry(workingPeaks, location, obsAlt))
        }

        if (!searching.compareAndSet(false, true)) {
            // A failover search can outlive the view's refresh tick; fold the
            // duplicate request into the one in flight rather than stack
            // requests onto an already-loaded endpoint. Nothing is confirmed
            // here either, so it reports the current flag and error — not
            // "never succeeded", which blinked the label off during each retry.
            return resultOf(refreshGeometry(workingPeaks, location, obsAlt))
        }
        // Stamped BEFORE the request, so a tick landing inside the retry backoff
        // takes the reuse path above instead of firing another request at a
        // loaded mirror. Only [lastSuccessAtMs] — the freshness stamp — waits
        // for a confirmed answer.
        lastSearchLocation = location
        try {
            return executeSearch(location, mountainsData, workingPeaks, offlinePeaks, obsAlt, onLocalPeaks)
        } finally {
            searching.set(false)
        }
    }

    /** [peaks] with the stale flag and the error as the last outcome left them,
     *  remembered as [lastHandedOut]. Every result is made here, the early
     *  publish included. */
    private fun resultOf(peaks: List<NearbyPeak>): PeakSearchResult {
        lastHandedOut = peaks
        return PeakSearchResult(peaks, stale = peaksAreStale, error = lastSearchError)
    }

    /**
     * Whether a downloaded offline pack covers here: any of its peaks within
     * the render distance. A pack is the explicit offline answer, so its area
     * is never labelled "possibly outdated" — calling it that on a summit with
     * no signal, the very case packs exist for, would be wrong.
     */
    private fun packCoversHere(location: Location, offlinePeaks: List<NearbyPeak>): Boolean =
        offlinePeaks.any { peak ->
            val pl = Location("").apply {
                latitude = peak.latitude
                longitude = peak.longitude
            }
            location.distanceTo(pl) <= maxPeakRenderDistanceM
        }

    /**
     * The network half of [searchPeaks], run once (guarded by [searching]).
     * The local sources are merged and handed to [onLocalPeaks] first. Only
     * [PeakOutcome.Success] then stamps the cache, clears the stale flag and
     * writes the on-disk snapshot; a failure keeps that local merge and marks
     * it stale, so an outage can never be mistaken for "no peaks here" — nor
     * hide the peaks the phone already has.
     */
    private suspend fun executeSearch(
        location: Location,
        mountainsData: MountainData?,
        previousPeaks: List<NearbyPeak>,
        offlinePeaks: List<NearbyPeak>,
        obsAlt: Double,
        onLocalPeaks: (PeakSearchResult) -> Unit
    ): PeakSearchResult = coroutineScope {
        val now = clock()
        val osmDeferred = async {
            fetchPeakOutcome(location.latitude, location.longitude, searchRadius)
        }

        // The local sources need no network, so they join what we already
        // have (live peaks or the last ones handed out, or the snapshot
        // restored from disk) BEFORE the network is awaited, and are published
        // at once. Same 200 m de-duplication as the confirmed merge below (a
        // peak already shown wins), checked through a [DuplicateIndex] because
        // this runs on every network search, and pack peaks are stamped fresh,
        // as they are below.
        val (knownPeaks, localPeaks) = withContext(Dispatchers.IO) {
            val known = findKnownMountains(location, mountainsData, now)
            val working = previousPeaks.toMutableList()
            val merged = DuplicateIndex(working)
            for (peak in known) {
                if (!merged.isDuplicate(peak)) {
                    working.add(peak)
                    merged.add(peak)
                }
            }
            for (peak in offlinePeaks) {
                if (!merged.isDuplicate(peak)) {
                    val stamped = peak.copy(lastSeenAt = now)
                    working.add(stamped)
                    merged.add(stamped)
                }
            }
            known to refreshGeometry(working, location, obsAlt)
        }
        onLocalPeaks(resultOf(localPeaks))

        val outcome = osmDeferred.await()
        // Stamp when the outcome ARRIVED, not when the search began: a failover
        // can take ~30 s, and stamped up front that shrank the 60 s retry
        // backoff to ~30 s.
        val answeredAt = clock()

        val osmPeaks = when (outcome) {
            is PeakOutcome.Failure -> {
                // A failed search is NOT a result. Keep showing what we have —
                // the local merge above, so offline-pack and bundled peaks
                // included — and mark it stale. This used to merge an empty OSM
                // set in and stamp `lastSearchLocation`, so `peakTtlMs` then
                // dropped everything the failure could not re-confirm, and
                // nothing retried until the user walked 500 m; then it returned
                // before the local sources were merged at all, so a downloaded
                // pack showed 0 peaks with no signal.
                lastFailureAtMs = answeredAt
                lastSearchError = outcome.reason
                peaksAreStale = localPeaks.isNotEmpty() && !packCoversHere(location, offlinePeaks)
                AppLog.ar.error("Nearby peaks: search failed (${outcome.reason})")
                return@coroutineScope resultOf(localPeaks)
            }
            is PeakOutcome.Success -> outcome.peaks
        }

        // Only a confirmed answer counts as fresh. (The search *location* was
        // stamped before the request, to pace retries; the *result* only ages
        // from a success, so a failure never becomes the thing we serve.)
        lastSuccessAtMs = answeredAt
        lastFailureAtMs = null
        lastSearchError = null
        peaksAreStale = false

        withContext(Dispatchers.IO) {
            val freshPeaks = osmPeaks.toMutableList()

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
                    freshPeaks.add(peak.copy(lastSeenAt = answeredAt))
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
            // then age out anything not re-confirmed within the TTL — here
            // only, right after a confirmed answer; every other path keeps what
            // it has, so an outage never ages the horizon away — and drop peaks
            // hidden below the Earth's bulge (only keep what's really visible
            // from here).
            val dropRadius = maxPeakRenderDistanceM
            val merged = byId.values
                .filter { peak ->
                    val pl = Location("").apply {
                        latitude = peak.latitude
                        longitude = peak.longitude
                    }
                    val d = location.distanceTo(pl).toDouble()
                    d <= dropRadius && (answeredAt - peak.lastSeenAt) <= peakTtlMs &&
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
            merged
        }.let(::resultOf)
    }

    private fun refreshGeometry(
        peaks: List<NearbyPeak>,
        location: Location,
        obsAlt: Double
    ): List<NearbyPeak> {
        // Apply the same drop-radius + horizon-visibility eviction and
        // nearest-first cap the confirmed merge does, so a stationary user (who
        // only ever takes the reuse path) still drops peaks that have drifted
        // out of view. NOT the TTL: every caller here — reuse, in flight, the
        // local merge, a failed search, a restored snapshot — confirms nothing,
        // and aging peaks out on these paths emptied the horizon an hour into
        // an outage. The TTL cuts only right after a confirmed answer.
        val dropRadius = maxPeakRenderDistanceM
        return peaks
            .filter { peak ->
                val pl = Location("").apply {
                    latitude = peak.latitude
                    longitude = peak.longitude
                }
                val d = location.distanceTo(pl).toDouble()
                d <= dropRadius && GeoCalculations.isAboveHorizon(obsAlt, peak.altitude, d)
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
     * [isDuplicate] for a set that grows one peak at a time, without scanning
     * the whole set for every peak. The local merge in [executeSearch] checks
     * every pack peak (4000 a pack, more with several) on every network search,
     * the retries of an outage included, and the whole-set scan made that
     * quadratic: about 3 s per search with three full packs on a desktop JVM.
     *
     * Peaks are kept in latitude bands of [duplicateBandDegrees]. A
     * great-circle distance is never shorter than its north–south part, so two
     * peaks less than 200 m apart are less than 0.0018° of latitude apart, and
     * only a peak's own band and the two beside it can hold its duplicate.
     * Those get the exact [isDuplicate] test, so the answer is the same as the
     * whole-set scan's.
     */
    private inner class DuplicateIndex(peaks: List<NearbyPeak>) {
        private val bands = HashMap<Long, MutableList<NearbyPeak>>()

        init {
            peaks.forEach(::add)
        }

        fun add(peak: NearbyPeak) {
            bands.getOrPut(bandOf(peak.latitude)) { mutableListOf() }.add(peak)
        }

        fun isDuplicate(peak: NearbyPeak): Boolean {
            val band = bandOf(peak.latitude)
            for (neighbour in band - 1..band + 1) {
                val inBand = bands[neighbour] ?: continue
                if (this@PeakFinder.isDuplicate(peak, inBand)) return true
            }
            return false
        }

        private fun bandOf(latitude: Double): Long = floor(latitude / duplicateBandDegrees).toLong()
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
     *  search; runs on `Dispatchers.IO`. Returns the [PeakOutcome] itself, not
     *  a bare list: [PeakOutcome.Failure] means no mirror answered, and a pack
     *  saved from it would read as "this area has no peaks" (an empty list
     *  used to stand for both). */
    suspend fun fetchPeaksForArea(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): PeakOutcome = fetchPeakOutcome(centerLat, centerLon, radiusMeters)

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
        val deadline = clock() + fetchDeadlineMs
        for (endpoint in rotatedEndpoints(endpointCursor.getAndIncrement())) {
            val host = endpoint.substringAfter("://").substringBefore('/')
            if (clock() >= deadline) {
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
        val decoded: OverpassResponse = try {
            val request = Request.Builder()
                .url("$endpoint?data=$encodedQuery")
                .header("User-Agent", TerrainElevationService.USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.ar.warn("Overpass $host returned HTTP ${response.code}")
                    return OverpassAttempt.Rejected("HTTP ${response.code}")
                }
                json.decodeFromString<OverpassResponse>(response.body.string())
            }
        } catch (e: Exception) {
            AppLog.ar.warn("Overpass $host failed", e)
            return OverpassAttempt.Rejected(e.message ?: e.javaClass.simpleName)
        }

        // A query that ran out of memory or timed out on a loaded server still
        // comes back HTTP 200, with empty `elements` and the reason in `remark`
        // ("runtime error: Query timed out in "query" at line 2 after 10
        // seconds."). That is the server failing to answer, not "no peaks
        // here" — taken as an answer it was reused for 15 min, cleared the
        // peaks on screen and overwrote the snapshot. Any other remark, or
        // none, is an answer as before.
        val remark = decoded.remark
        if (remark != null && remark.contains("runtime error", ignoreCase = true)) {
            AppLog.ar.warn("Overpass $host answered with a runtime error: $remark")
            return OverpassAttempt.Rejected(remark.trim().take(120))
        }
        val elements = decoded.elements

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
        val seenAt = clock()
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
                bearing = c.bearing,
                lastSeenAt = seenAt
            )
        })
    }

    private fun findKnownMountains(location: Location, data: MountainData?, now: Long): List<NearbyPeak> {
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
                // standing on it, which is where you cannot see it. The merges
                // in executeSearch still apply the horizon cut (and, after a
                // confirmed answer, the TTL).
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
                        bearing = bearing,
                        lastSeenAt = now
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
    // restored — marked stale unless a pack covers here — on the next start.

    /** On-disk form of the last successful search. `distance` / `bearing` are
     *  derived against the live fix, so only identity and altitude are stored.
     *  The centre is rounded to 2 decimal places (~1 km): it only answers "is
     *  this snapshot for here?" against [persistedReuseDistanceM], and the
     *  exact fix would keep more of where the user was than that check needs.
     *  The peak entries keep their exact coordinates. */
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

    /** In the cache dir, not the files dir: this is derived, re-downloadable
     *  data — and Auto Backup excludes the cache dir, so the rounded point a
     *  search was made from never leaves the device in a backup. */
    private fun snapshotFile(): File = File(context.cacheDir, "nearby_peaks_cache.json")

    /**
     * Restore the persisted snapshot if it is recent enough and near enough to
     * be about *here*. `lastSeenAt` is re-stamped on load — those peaks were not
     * "failing to be re-confirmed", they were on disk — and the `stale` flag on
     * the result is what tells the user they may be out of date. `distance` /
     * `bearing` are placeholders: every path recomputes them against the live
     * fix ([refreshGeometry]) before anything is shown.
     */
    private fun readSnapshot(location: Location): List<NearbyPeak>? {
        return try {
            val file = snapshotFile()
            if (!file.exists()) return null
            val snapshot = json.decodeFromString<PeakSnapshot>(file.readText())
            val ageMs = clock() - snapshot.savedAtMs
            if (ageMs > persistedMaxAgeMs) return null
            val cachedCenter = Location("").apply {
                latitude = snapshot.centerLat
                longitude = snapshot.centerLon
            }
            if (location.distanceTo(cachedCenter) > persistedReuseDistanceM) return null
            val now = clock()
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
                // ~1 km (2 decimal places) — see [PeakSnapshot].
                centerLat = Math.round(center.latitude * 100.0) / 100.0,
                centerLon = Math.round(center.longitude * 100.0) / 100.0,
                savedAtMs = clock(),
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
         *
         * Only instances with GLOBAL data may be listed. A regional instance
         * answers HTTP 200 with zero peaks everywhere outside its region, and
         * that empty answer is indistinguishable from "no peaks here": the
         * Switzerland-only `overpass.osm.ch` returned 0 peaks around Mont Blanc
         * and Fuji, where a global instance returns 83 and 11.
         */
        val overpassEndpoints = listOf(
            // FOSSGIS e.V., the main instance.
            "https://overpass-api.de/api/interpreter",
            // Private.coffee (formerly overpass.kumi.systems).
            "https://overpass.private.coffee/api/interpreter",
            // OpenStreetMap France.
            "https://overpass.openstreetmap.fr/api/interpreter"
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
    val elements: List<OverpassElement> = emptyList(),
    /** Where Overpass reports a runtime error (out of memory, timed out) while
     *  still answering HTTP 200; see [PeakFinder] `attemptOverpass`. */
    val remark: String? = null
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
