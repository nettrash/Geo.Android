package me.nettrash.geo.ar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nettrash.geo.util.AppLog
import me.nettrash.geo.util.RetryInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight wrapper around the public Open-Meteo elevation REST API.
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

    /** Largest number of grid cells kept in memory and on disk; the
     *  least-recently-used cells are evicted first. Each entry is a
     *  ~110 m grid cell at ~24 bytes on disk, so even 50 000 entries is
     *  ~1.2 MB. The cap must comfortably hold several full skyline
     *  passes: one pass is now ~8 500 distinct cells (dense schedule +
     *  refinement round), and a cap smaller than a few passes would
     *  make the LRU evict its own working set — every recompute would
     *  re-fetch everything. Mirrors iOS `cacheCap`. */
    private val maxCacheEntries = 50_000

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

    // NOTE: this service used to also hold *pinned* DEM layers (a fine ~110 m
    // core plus ~550 m / ~2.2 km far-terrain rings) seeded from offline
    // expedition packs, so the terrain skyline could resolve a full 200 km
    // panorama with no signal. The skyline was removed from the Nature tab, and
    // with it the pinned layers, `setPinned(...)` and the pack DEM prefetch.
    //
    // What remains is what [PeakFinder] and [QnhRepository] actually need:
    // resolve the elevation of a handful of specific points on demand (e.g. an
    // OSM peak node carrying no `ele` tag). That's served by the LRU cache below.

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

    /** How many elevation batches to fetch concurrently. A cold skyline pass is
     *  ~36 batches; running a few lanes in parallel turns a ~7–15 s serial fetch
     *  into ~1–2 s while staying well within Open-Meteo's fair-use limits. */
    private val maxConcurrentBatches = 6

    // minIntervalMs = 0 disables the per-request spacing (politeness now comes
    // from the bounded lane count above, so batches can actually run in
    // parallel); the retry-on-429/5xx behaviour is retained.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor(minIntervalMs = 0))
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
                val cached = cache[key]
                if (cached != null) results[i] = cached else pending.add(i to key)
            }
        }
        if (pending.isEmpty()) return@withContext results.toList()

        // Slice into ≤batchSize HTTP batches and fetch them CONCURRENTLY, bounded
        // to maxConcurrentBatches lanes by the semaphore. A cold skyline is ~36
        // batches; serial they took ~7–15 s, in parallel ~1–2 s. Each batch
        // stitches its own results into the cache under cacheLock as it returns.
        var resolvedAny = false
        val gate = Semaphore(maxConcurrentBatches)
        coroutineScope {
            pending.chunked(batchSize).map { chunk ->
                async {
                    val response = gate.withPermit { fetchBatch(chunk.map { it.second.toCoord() }) }
                    cacheLock.withLock {
                        for ((row, elevation) in chunk.zip(response)) {
                            // Reject implausible values: below the lowest dry land
                            // on Earth (Dead Sea shore, ~-430 m) is impossible, and
                            // an exact 0.0 is the ocean / outside-DEM value —
                            // accepting it would plant a peak at sea level. Treat
                            // both as null so the caller falls back to the
                            // geometric horizon.
                            if (elevation != null && elevation > -430.0 && elevation != 0.0) {
                                cache[row.second] = elevation
                                results[row.first] = elevation
                                resolvedAny = true
                            }
                        }
                    }
                }
            }.awaitAll()
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

        // Open-Meteo elevation API: GET with comma-separated lat/lon lists, up to
        // 100 points per request (matches batchSize). Reliable + free + no key,
        // and already used for weather/QNH. Replaces the public Open-Elevation
        // endpoint, which frequently 504s / times out and left the AR terrain
        // skyline (and its welded peak labels) empty. Coords are pre-quantised
        // to the privacy grid before this call.
        val lats = coords.joinToString(",") { it.lat.toString() }
        val lons = coords.joinToString(",") { it.lon.toString() }
        val url = "https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons"
        val request = Request.Builder()
            .url(url)
            .get()
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
                val decoded = json.decodeFromString<OpenMeteoElevationResponse>(text)
                val out: List<Double?> = decoded.elevation
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
        GridKey(lat = milliDeg(lat), lon = milliDeg(lon))

    private fun GridKey.toCoord(): Coord =
        Coord(lat / 1000.0, lon / 1000.0)

    private data class Coord(val lat: Double, val lon: Double)

    companion object {
        /** App-identifying User-Agent for the public third-party APIs
         *  (Open-Meteo, Overpass). Matches iOS so operators see one
         *  consistent client across both ports. */
        const val USER_AGENT = "me.nettrash.Geo/1.0 (+https://nettrash.me)"

        /**
         * Round a coordinate component to the shared ~110 m privacy grid
         * (3 decimals, round-half). Reused by [TerrainElevationService]
         * (cache + Open-Meteo request) and [me.nettrash.geo.util.PeakFinder]
         * (Overpass request) so every outbound coordinate uses identical
         * grid math, matching iOS.
         */
        fun quantise(value: Double): Double = Math.round(value * 1000.0) / 1000.0

        /** Integer milli-degree grid index for a coordinate component —
         *  the in-memory `GridKey` form. Exposed so `OfflinePackRepository`
         *  builds pinned-cell keys that line up with live lookups. */
        fun milliDeg(value: Double): Int = Math.round(value * 1000.0).toInt()
    }
}

// ─── Wire format ───────────────────────────────────────────────────

/** Open-Meteo elevation response: `{ "elevation": [e0, e1, …] }`, one entry per
 *  requested coordinate, in order. */
@Serializable
private data class OpenMeteoElevationResponse(val elevation: List<Double> = emptyList())
