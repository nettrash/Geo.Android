package me.nettrash.geo.util

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 *   • Live OpenStreetMap Overpass results (`natural=peak` nodes /
 *     ways / relations within [searchRadius]).
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
class PeakFinder @Inject constructor() {

    private val searchRadius = 5_000.0   // 5 km
    private val minimumSearchDistance = 500.0 // re-search after 500 m of movement
    private val peakTtlMs = 60L * 60L * 1000L  // 1 hour
    private val maxRetainedPeaks = 200

    private var lastSearchLocation: Location? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
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
        return peaks.map { peak ->
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
     * Query OpenStreetMap Overpass API.
     *
     * Widening note vs the previous version: we now accept
     * `way["natural"="peak"]` and `relation["natural"="peak"]` too,
     * not just nodes — Overpass has a non-trivial number of named
     * peaks expressed as ways (e.g. summit ridges).
     */
    private fun searchOpenStreetMap(location: Location): List<NearbyPeak> {
        // Quantise lat/lon to ~110 m grid before sending to the
        // public API. Mirrors iOS privacy note.
        val qLat = (location.latitude * 1000).toInt() / 1000.0
        val qLon = (location.longitude * 1000).toInt() / 1000.0
        val radiusMeters = searchRadius.toInt()

        val query = """
            [out:json][timeout:10];
            (
              node["natural"="peak"](around:$radiusMeters,$qLat,$qLon);
              way["natural"="peak"](around:$radiusMeters,$qLat,$qLon);
              relation["natural"="peak"](around:$radiusMeters,$qLat,$qLon);
            );
            out body center;
        """.trimIndent()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://overpass-api.de/api/interpreter?data=$encodedQuery"

        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body.string()
            val result = json.decodeFromString<OverpassResponse>(body)

            result.elements.mapNotNull { element ->
                val name = element.tags?.name ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null

                // Nodes carry lat/lon; ways/relations carry center.
                val lat = element.lat ?: element.center?.lat ?: return@mapNotNull null
                val lon = element.lon ?: element.center?.lon ?: return@mapNotNull null

                val distance = GeoCalculations.distanceBetween(
                    location.latitude, location.longitude, lat, lon
                )
                if (distance > searchRadius) return@mapNotNull null

                val bearing = GeoCalculations.bearing(
                    location.latitude, location.longitude, lat, lon
                )
                val altitude = element.tags.ele?.toDoubleOrNull() ?: (location.altitude + 100)

                NearbyPeak.create(
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    altitude = altitude,
                    distance = distance,
                    bearing = bearing
                )
            }
        } catch (e: Exception) {
            AppLog.ar.warn("OpenStreetMap peak search failed", e)
            emptyList()
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

@Serializable
private data class OverpassElement(
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: OverpassTags? = null
)

@Serializable
private data class OverpassCenter(
    val lat: Double,
    val lon: Double
)

@Serializable
private data class OverpassTags(
    val name: String? = null,
    val ele: String? = null
)
