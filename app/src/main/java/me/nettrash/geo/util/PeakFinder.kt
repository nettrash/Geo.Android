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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeakFinder @Inject constructor() {

    private val searchRadius = 5000.0 // 5 km
    private val minimumSearchDistance = 500.0 // re-search after 500m
    private var lastSearchLocation: Location? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun searchPeaks(
        location: Location,
        mountainsData: MountainData?,
        currentPeaks: List<NearbyPeak>
    ): List<NearbyPeak> {
        val last = lastSearchLocation
        if (last != null && last.distanceTo(location) < minimumSearchDistance && currentPeaks.isNotEmpty()) {
            return currentPeaks
        }
        lastSearchLocation = location

        return withContext(Dispatchers.IO) {
            val osmDeferred = async { searchOpenStreetMap(location) }
            val knownPeaks = findKnownMountains(location, mountainsData)

            val foundPeaks = mutableListOf<NearbyPeak>()

            val osmResults = osmDeferred.await()
            foundPeaks.addAll(osmResults)

            for (peak in knownPeaks) {
                if (!isDuplicate(peak, foundPeaks)) {
                    foundPeaks.add(peak)
                }
            }

            foundPeaks.sortBy { it.distance }
            foundPeaks
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

    private fun searchOpenStreetMap(location: Location): List<NearbyPeak> {
        val lat = location.latitude
        val lon = location.longitude
        val radiusMeters = searchRadius.toInt()

        val query = """
            [out:json][timeout:10];
            node["natural"="peak"](around:$radiusMeters,$lat,$lon);
            out body;
        """.trimIndent()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://overpass-api.de/api/interpreter?data=$encodedQuery"

        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val result = json.decodeFromString<OverpassResponse>(body)

            result.elements.mapNotNull { element ->
                val name = element.tags?.name ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null

                val distance = GeoCalculations.distanceBetween(lat, lon, element.lat, element.lon)
                if (distance > searchRadius) return@mapNotNull null

                val bearing = GeoCalculations.bearing(lat, lon, element.lat, element.lon)
                val altitude = element.tags.ele?.toDoubleOrNull() ?: (location.altitude + 100)

                NearbyPeak.create(
                    name = name,
                    latitude = element.lat,
                    longitude = element.lon,
                    altitude = altitude,
                    distance = distance,
                    bearing = bearing
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
    val lat: Double,
    val lon: Double,
    val tags: OverpassTags? = null
)

@Serializable
private data class OverpassTags(
    val name: String? = null,
    val ele: String? = null
)
