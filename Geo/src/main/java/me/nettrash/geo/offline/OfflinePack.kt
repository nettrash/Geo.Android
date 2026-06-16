package me.nettrash.geo.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nettrash.geo.ar.ElevationCacheStore
import me.nettrash.geo.util.AppLog
import java.io.File

/**
 * "Offline expedition pack" — a pre-cached area so the AR peaks + terrain
 * skyline keep working on a summit with no signal. Direct sibling of iOS
 * `Core/OfflinePack.swift`.
 *
 * Each pack bundles the area's OSM peaks and the skyline DEM (digital
 * elevation model) sampled from the pack centre, both pulled through the
 * same throttled public-API paths the live app uses.
 *
 * Storage is plain JSON files under `filesDir/offline_packs` (NOT Room):
 * the DEM blob is large and purely local, so there's no reason to put it
 * through a migration-bearing schema. A light `index.json` holds the
 * metadata the management UI lists; each pack's peaks + cells live in its
 * own `<id>.json` so the list never loads megabytes of terrain.
 *
 * Map tiles are intentionally out of scope (Maps/MapKit licensing forbids
 * caching tiles) — this is "offline data & AR", not "offline map".
 */
@Serializable
data class OfflinePack(
    val id: String,
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    /** Radius (km) of the peak search box this pack was built with. */
    val radiusKm: Double,
    val createdAt: Long,
    /** Named peaks cached for the area. */
    val peakCount: Int,
    /** ~110 m DEM grid cells cached for the centre's skyline panorama. */
    val cellCount: Int
)

/** The heavy payload for one pack, stored in `<id>.json`. */
@Serializable
data class OfflinePackData(
    val peaks: List<PackPeak> = emptyList(),
    /** Quantised grid cells (integer milli-degrees + elevation), shaped
     *  exactly like the live elevation cache so they seed straight in as
     *  pinned cells. */
    val cells: List<ElevationCacheStore.Entry> = emptyList()
)

@Serializable
data class PackPeak(
    val name: String,
    val lat: Double,
    val lon: Double,
    val altitude: Double
)

/**
 * File-backed persistence for offline packs. Pure I/O; the
 * `OfflinePackRepository` owns the in-memory state and seeding. All writes
 * go through a temp-file + atomic rename so a crash can't leave a
 * half-written file. Best-effort: failures are logged and swallowed.
 */
class OfflinePackStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val dir: File
        get() = File(context.filesDir, "offline_packs").apply { if (!exists()) mkdirs() }

    private val indexFile: File get() = File(dir, "index.json")
    private fun dataFile(id: String): File = File(dir, "$id.json")

    suspend fun loadIndex(): List<OfflinePack> = withContext(Dispatchers.IO) {
        val f = indexFile
        if (!f.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<OfflinePack>>(f.readText())
        } catch (t: Throwable) {
            AppLog.app.warn("Offline pack index load failed", t)
            emptyList()
        }
    }

    suspend fun saveIndex(packs: List<OfflinePack>) = withContext(Dispatchers.IO) {
        try {
            writeAtomic(indexFile, json.encodeToString(packs))
        } catch (t: Throwable) {
            AppLog.app.warn("Offline pack index save failed", t)
        }
    }

    suspend fun loadData(id: String): OfflinePackData? = withContext(Dispatchers.IO) {
        val f = dataFile(id)
        if (!f.exists()) return@withContext null
        try {
            json.decodeFromString<OfflinePackData>(f.readText())
        } catch (t: Throwable) {
            AppLog.app.warn("Offline pack data load failed", t)
            null
        }
    }

    suspend fun saveData(id: String, data: OfflinePackData) = withContext(Dispatchers.IO) {
        try {
            writeAtomic(dataFile(id), json.encodeToString(data))
        } catch (t: Throwable) {
            AppLog.app.warn("Offline pack data save failed", t)
        }
    }

    suspend fun deleteData(id: String) = withContext(Dispatchers.IO) {
        try { dataFile(id).delete() } catch (_: Throwable) {}
        Unit
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
