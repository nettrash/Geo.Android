package me.nettrash.geo.ar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nettrash.geo.util.AppLog
import me.nettrash.geo.util.RetryInterceptor
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
 *  * **Persistent LRU cache** — keyed on a ~110 m grid (3-decimal
 *    rounding) so successive skyline passes share results. Each skyline
 *    pass samples thousands of points; caching matters a lot. Terrain
 *    elevation is *static*, so the cache is also persisted to disk
 *    ([ElevationCacheStore]) and restored on init — the skyline draws
 *    instantly on a cold start and works offline for revisited places.
 *    Bounded to [maxCacheEntries] cells, evicting least-recently-used.
 *  * **Graceful failure** — any unresolved point returns `null` so
 *    callers fall back to geometric (sea-level) estimates.
 *  * **Privacy** — request coordinates are quantised to the cache
 *    grid before being sent to the third-party API (and before being
 *    persisted).
 */
@Singleton
class TerrainElevationService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private data class GridKey(val lat: Int, val lon: Int)

    /** Largest number of grid cells kept in memory and on disk.
     *  At ~110 m spacing this covers a wide area while bounding the
     *  cache file; the least-recently-used cells are evicted first. */
    private val maxCacheEntries = 8000

    /** Cache. Guarded by [cacheLock] because skyline computation
     *  fans out async tasks that share the same instance.
     *
     *  `accessOrder = true` + [removeEldestEntry] makes this an LRU:
     *  reads/writes promote a key to most-recently-used, and inserts
     *  past [maxCacheEntries] drop the least-recently-used cell. */
    private val cache = object : LinkedHashMap<GridKey, Double>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GridKey, Double>): Boolean =
            size > maxCacheEntries
    }
    private val cacheLock = Mutex()

    /** Durable backing for [cache] so terrain elevations survive process
     *  death and offline sessions. */
    private val store = ElevationCacheStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Restore the persisted cells (oldest first, preserving LRU
        // order) so the first skyline pass after a cold start can be
        // served from disk. Best-effort; failures leave an empty cache.
        scope.launch {
            val persisted = store.load()
            if (persisted.isEmpty()) return@launch
            cacheLock.withLock {
                for (e in persisted) {
                    cache[GridKey(e.lat, e.lon)] = e.elev
                }
            }
        }
    }

    private val batchSize = 100
    private val timeoutSeconds = 8L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor(minIntervalMs = 200))
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
        var resolvedAny = false
        pending.chunked(batchSize).forEach { chunk ->
            val coords = chunk.map { it.second.toCoord() }
            val response = fetchBatch(coords)
            cacheLock.withLock {
                for ((row, elevation) in chunk.zip(response)) {
                    // Reject implausible values: below the lowest dry land
                    // on Earth (Dead Sea shore, ~-430 m) is impossible, and
                    // an exact 0.0 is Open-Elevation's "unresolved" / ocean
                    // sentinel — accepting it would plant a peak at sea
                    // level. Treat both as null so the caller falls back.
                    if (elevation != null && elevation > -430.0 && elevation != 0.0) {
                        cache[row.second] = elevation
                        results[row.first] = elevation
                        resolvedAny = true
                    }
                }
            }
        }
        // Write through so the freshly resolved (static) elevations
        // survive a cold start / offline revisit. Off the request path.
        if (resolvedAny) persistCache()
        results.toList()
    }

    /** Drop everything from the cache, in memory and on disk.
     *  Primarily useful for tests. */
    suspend fun clearCache() {
        cacheLock.withLock { cache.clear() }
        store.save(emptyList())
    }

    /** Snapshot the cache (under the lock) in LRU order and persist it.
     *  Launched on [scope] so disk I/O never blocks the skyline. */
    private fun persistCache() {
        scope.launch {
            val snapshot = cacheLock.withLock {
                cache.map { (key, elev) -> ElevationCacheStore.Entry(key.lat, key.lon, elev) }
            }
            store.save(snapshot)
        }
    }

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
            .header("User-Agent", USER_AGENT)
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

        /** App-identifying User-Agent for the public third-party APIs
         *  (Open-Elevation, Overpass). Matches iOS so operators see one
         *  consistent client across both ports. */
        const val USER_AGENT = "me.nettrash.Geo/1.0 (+https://nettrash.me)"

        /**
         * Round a coordinate component to the shared ~110 m privacy grid
         * (3 decimals, round-half). Reused by [TerrainElevationService]
         * (cache + Open-Elevation request) and [me.nettrash.geo.util.PeakFinder]
         * (Overpass request) so every outbound coordinate uses identical
         * grid math, matching iOS.
         */
        fun quantise(value: Double): Double = Math.round(value * 1000.0) / 1000.0
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
