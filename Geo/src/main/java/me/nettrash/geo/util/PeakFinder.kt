package me.nettrash.geo.util

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nettrash.geo.ar.TerrainElevationService
import me.nettrash.geo.data.model.MountainData
import me.nettrash.geo.data.model.NearbyPeak
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.UUID
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
    private val terrain: TerrainElevationService
) {

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

    /** Max peaks kept in the merged set (nearest-first eviction). Larger than
     *  before so a dense range doesn't evict the distant flagship summits (the
     *  whole point of pointing the camera at the horizon) in favour of nearer
     *  foothills. Only in-view peaks are drawn, so the on-screen count stays
     *  small regardless. Mirrors iOS. */
    private val maxRetainedPeaks = 300

    private var lastSearchLocation: Location? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
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
    ): List<NearbyPeak> {
        // Observer altitude for the horizon-visibility cut. Prefer the caller's
        // (barometer-preferred) value — GPS altitude can be tens of metres off,
        // or garbage-low, which would wrongly hide visible peaks.
        val obsAlt = observerAltitude ?: location.altitude
        val last = lastSearchLocation
        if (last != null &&
            last.distanceTo(location) < minimumSearchDistance &&
            currentPeaks.isNotEmpty()
        ) {
            // No movement — just refresh distance/bearing of the
            // existing set against the latest location so visual
            // fade-by-distance stays accurate.
            return refreshGeometry(currentPeaks, location, obsAlt)
        }
        lastSearchLocation = location

        val now = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            val osmDeferred = async {
                searchOpenStreetMapArea(location.latitude, location.longitude, searchRadius)
            }
            val knownPeaks = findKnownMountains(location, mountainsData)

            val freshPeaks = mutableListOf<NearbyPeak>()

            val osmResults = osmDeferred.await()
            freshPeaks.addAll(osmResults)

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
            for (p in currentPeaks) byId[p.id] = p
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

            merged
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
    ): List<NearbyPeak> {
        // Quantise lat/lon to ~110 m grid before sending to the
        // public API. Uses the shared round-half helper so Overpass,
        // Open-Elevation and the elevation cache all bucket identically,
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
        val url = "https://overpass-api.de/api/interpreter?data=$encodedQuery"

        val elements: List<OverpassElement> = try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", TerrainElevationService.USER_AGENT)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body.string()
            json.decodeFromString<OverpassResponse>(body).elements
        } catch (e: Exception) {
            AppLog.ar.warn("OpenStreetMap peak search failed", e)
            return emptyList()
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
        return candidates.mapNotNull { c ->
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
        }
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
