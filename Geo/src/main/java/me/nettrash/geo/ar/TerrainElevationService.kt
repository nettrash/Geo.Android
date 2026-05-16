package me.nettrash.geo.ar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nettrash.geo.util.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight wrapper around the public Open-Elevation REST API.
 * Direct port of iOS `Core/TerrainElevationService.swift`.
 *
 *  * **Batched lookups** — up to 100 points per HTTP POST.
 *  * **In-memory cache** — keyed on a ~110 m grid (3-decimal rounding)
 *    so successive skyline passes share results. Each skyline pass
 *    samples thousands of points; caching matters a lot.
 *  * **Graceful failure** — any unresolved point returns `null` so
 *    callers fall back to geometric (sea-level) estimates.
 *  * **Privacy** — request coordinates are quantised to the cache
 *    grid before being sent to the third-party API.
 */
@Singleton
class TerrainElevationService @Inject constructor() {

    private data class GridKey(val lat: Int, val lon: Int)

    /** Cache. Guarded by [cacheLock] because skyline computation
     *  fans out async tasks that share the same instance. */
    private val cache = HashMap<GridKey, Double>()
    private val cacheLock = Mutex()

    private val batchSize = 100
    private val timeoutSeconds = 8L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Resolve elevations (metres above MSL) for each `(lat, lon)`
     * pair in [points], preserving order. `null` means we couldn't
     * resolve that point.
     */
    suspend fun elevations(points: List<Pair<Double, Double>>): List<Double?> = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyList()

        val results = arrayOfNulls<Double>(points.size)
        val pending = mutableListOf<Pair<Int, GridKey>>()

        cacheLock.withLock {
            for ((i, p) in points.withIndex()) {
                val key = gridKey(p.first, p.second)
                cache[key]?.let { results[i] = it } ?: pending.add(i to key)
            }
        }
        if (pending.isEmpty()) return@withContext results.toList()

        // Slice into HTTP batches. Each batch is one POST.
        pending.chunked(batchSize).forEach { chunk ->
            val coords = chunk.map { it.second.toCoord() }
            val response = fetchBatch(coords)
            cacheLock.withLock {
                for ((row, elevation) in chunk.zip(response)) {
                    if (elevation != null) {
                        cache[row.second] = elevation
                        results[row.first] = elevation
                    }
                }
            }
        }
        results.toList()
    }

    /** Drop everything from the cache. Primarily useful for tests. */
    suspend fun clearCache() = cacheLock.withLock { cache.clear() }

    private fun fetchBatch(coords: List<Coord>): List<Double?> {
        if (coords.isEmpty()) return emptyList()

        val url = "https://api.open-elevation.com/api/v1/lookup"
        val payload = OpenElevationRequest(
            coords.map { OpenElevationRequest.Location(it.lat, it.lon) }
        )
        val body = try {
            json.encodeToString(payload).toRequestBody(JSON_MEDIA)
        } catch (t: Throwable) {
            AppLog.ar.warn("Elevation request encode failed", t)
            return List(coords.size) { null }
        }
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.ar.warn("Elevation HTTP ${resp.code}")
                    return List(coords.size) { null }
                }
                val text = resp.body.string()
                val decoded = json.decodeFromString<OpenElevationResponse>(text)
                val out: List<Double?> = decoded.results.map { it.elevation }
                if (out.size < coords.size) {
                    out + List<Double?>(coords.size - out.size) { null }
                } else {
                    out.take(coords.size)
                }
            }
        } catch (t: Throwable) {
            AppLog.ar.warn("Elevation request failed", t)
            List(coords.size) { null }
        }
    }

    // ─── Quantisation ─────────────────────────────────────────────

    private fun gridKey(lat: Double, lon: Double): GridKey =
        GridKey(
            lat = Math.round(lat * 1000.0).toInt(),
            lon = Math.round(lon * 1000.0).toInt()
        )

    private fun GridKey.toCoord(): Coord =
        Coord(lat / 1000.0, lon / 1000.0)

    private data class Coord(val lat: Double, val lon: Double)

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}

// ─── Wire format ───────────────────────────────────────────────────

@Serializable
private data class OpenElevationRequest(val locations: List<Location>) {
    @Serializable
    data class Location(val latitude: Double, val longitude: Double)
}

@Serializable
private data class OpenElevationResponse(val results: List<Row> = emptyList()) {
    @Serializable
    data class Row(val latitude: Double, val longitude: Double, val elevation: Double)
}
