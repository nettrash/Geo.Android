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
 *     (`MountainData.highest`, `sevenPeaks`, `snowLeopardOfRussia`).
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
    private val minimumSearchDistance = 500.0 // re-search after 500 m of movement
    private val peakTtlMs = 60L * 60L * 1000L  // 1 hour
    private val maxRetainedPeaks = 200

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
        currentPeaks: List<NearbyPeak>
    ): List<NearbyPeak> {
        val last = lastSearchLocation
        if (last != null &&
            last.distanceTo(location) < minimumSearchDistance &&
            currentPeaks.isNotEmpty()
        ) {
            // No movement — just refresh distance/bearing of the
            // existing set against the latest location so visual
            // fade-by-distance stays accurate.
            return refreshGeometry(currentPeaks, location)
        }
        lastSearchLocation = location

        val now = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            val osmDeferred = async { searchOpenStreetMap(location) }
            val knownPeaks = findKnownMountains(location, mountainsData)

            val freshPeaks = mutableListOf<NearbyPeak>()

            val osmResults = osmDeferred.await()
            freshPeaks.addAll(osmResults)

            for (peak in knownPeaks) {
                if (!isDuplicate(peak, freshPeaks)) {
                    freshPeaks.add(peak)
                }
            }

            // Merge: previous list provides identity continuity,
            // fresh results win on duplicate IDs (so distance / bearing
            // / lastSeenAt get updated).
            val byId = HashMap<UUID, NearbyPeak>()
            for (p in currentPeaks) byId[p.id] = p
            for (p in freshPeaks) byId[p.id] = p

            // Hysteresis + TTL: drop entries far outside the search
            // radius OR not seen in a while.
            val dropRadius = searchRadius * 2
            val merged = byId.values
                .filter { peak ->
                    val pl = Location("").apply {
                        latitude = peak.latitude
                        longitude = peak.longitude
                    }
                    val d = location.distanceTo(pl).toDouble()
                    d <= dropRadius && (now - peak.lastSeenAt) <= peakTtlMs
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
        location: Location
    ): List<NearbyPeak> {
        // Apply the same TTL + drop-radius eviction the full-search branch
        // does, so a stationary user (who only ever takes this path) still
        // ages out peaks that haven't been re-confirmed within peakTtlMs or
        // have drifted outside the drop radius.
        val now = System.currentTimeMillis()
        val dropRadius = searchRadius * 2
        return peaks
            .filter { peak ->
                val pl = Location("").apply {
                    latitude = peak.latitude
                    longitude = peak.longitude
                }
                val d = location.distanceTo(pl).toDouble()
                d <= dropRadius && (now - peak.lastSeenAt) <= peakTtlMs
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
    private suspend fun searchOpenStreetMap(location: Location): List<NearbyPeak> {
        // Quantise lat/lon to ~110 m grid before sending to the
        // public API. Uses the shared round-half helper so Overpass,
        // Open-Elevation and the elevation cache all bucket identically,
        // matching iOS.
        val qLat = TerrainElevationService.quantise(location.latitude)
        val qLon = TerrainElevationService.quantise(location.longitude)
        val radiusMeters = searchRadius.toInt()

        val query = """
            [out:json][timeout:10];
            node["natural"="peak"](around:$radiusMeters,$qLat,$qLon);
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
                location.latitude, location.longitude, lat, lon
            )
            if (d > searchRadius) return@mapNotNull null

            val b = GeoCalculations.bearing(
                location.latitude, location.longitude, lat, lon
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
                if (distance > searchRadius) continue

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
