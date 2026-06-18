package me.nettrash.geo.ar

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nettrash.geo.util.AppLog
import java.io.File

/**
 * Durable on-disk backing for [TerrainElevationService]'s in-memory
 * elevation cache.
 *
 * Why this exists
 * ---------------
 * [TerrainElevationService] kept resolved elevations purely in memory,
 * so every cold start (and every offline revisit of a place the user
 * already looked at) re-fetched the same thousands of points from the
 * public Open-Elevation API before the AR skyline could draw. Terrain
 * elevation is **static** — a value resolved once is correct forever —
 * so persisting it lets the skyline restore instantly and work offline.
 *
 * Format
 * ------
 * A single JSON file (`elevation_cache.json`) under `context.filesDir`,
 * holding a flat list of `[latMilli, lonMilli, elevation]` rows. The
 * grid keys are the same integer-milli-degree quantisation the service
 * uses in memory, so no precision is lost. Rows are persisted in
 * least-recently-used → most-recently-used order, so a bounded reload
 * preserves the service's LRU ordering across process death.
 *
 * Privacy
 * -------
 * Only the already-quantised (~110 m grid) coordinates the service
 * sends to the third-party API are stored — never the device's exact
 * position. The file lives in app-private storage.
 *
 * Failure tolerance
 * -----------------
 * All I/O is best-effort: a missing, truncated or corrupt file yields
 * an empty cache, and write failures are logged and swallowed. A stale
 * or absent cache only costs a network round-trip, never correctness.
 */
class ElevationCacheStore(private val context: Context) {

    /** One persisted grid cell. [lat]/[lon] are integer milli-degrees
     *  (the service's [GridKey] form); [elev] is metres above MSL. */
    @Serializable
    data class Entry(val lat: Int, val lon: Int, val elev: Double)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    /**
     * Load the persisted entries, oldest (least-recently-used) first.
     * Returns an empty list on a cold cache or any read/parse failure.
     */
    suspend fun load(): List<Entry> = withContext(Dispatchers.IO) {
        val f = file
        if (!f.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<Entry>>(f.readText())
        } catch (t: Throwable) {
            AppLog.ar.warn("Elevation cache load failed", t)
            emptyList()
        }
    }

    /**
     * Persist [entries] (oldest first), replacing any prior file.
     * Written via a temp file + atomic rename so a crash mid-write can
     * never leave a half-written cache behind. Best-effort.
     */
    suspend fun save(entries: List<Entry>) = withContext(Dispatchers.IO) {
        try {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(entries))
            if (!tmp.renameTo(file)) {
                // Fall back to a direct overwrite if rename is refused.
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            AppLog.ar.warn("Elevation cache save failed", t)
        }
    }

    private companion object {
        const val FILE_NAME = "elevation_cache.json"
    }
}
