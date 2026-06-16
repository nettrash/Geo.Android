package me.nettrash.geo.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.nettrash.geo.ar.ElevationCacheStore
import me.nettrash.geo.ar.SkylineCalculator
import me.nettrash.geo.ar.TerrainElevationService
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.util.PeakFinder
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the offline-expedition-pack state and the seeding of prefetched
 * data back into the two live caches so the AR view works with no signal:
 *
 *  • [PeakFinder] merges [combinedPeaks] (so named peaks / markers / ridge
 *    labels appear area-wide offline), and
 *  • [TerrainElevationService] pins the packs' DEM cells (so the terrain
 *    skyline resolves from cache instead of going empty offline).
 *
 * Download reuses the app's existing throttled, retry-backed public-API
 * paths ([PeakFinder.fetchPeaksForArea] / [TerrainElevationService]), so
 * the bounding-box prefetch is automatically polite to Overpass and
 * Open-Elevation. Sibling of iOS `Core/OfflinePackManager.swift`.
 */
@Singleton
class OfflinePackRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val terrain: TerrainElevationService,
    private val peakFinder: PeakFinder
) {

    private val store = OfflinePackStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Hard cap on stored peaks per pack so a huge radius in a dense range
     *  can't bloat the file (the live view only shows the nearest ~200). */
    private val maxPackPeaks = 4000

    private val _packs = MutableStateFlow<List<OfflinePack>>(emptyList())
    val packs: StateFlow<List<OfflinePack>> = _packs.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    /** 0…1 across the DEM prefetch (the long phase). */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    /** Union of every pack's peaks, deduped — handed to [PeakFinder] so the
     *  area's peaks show offline. Distance/bearing are placeholders;
     *  PeakFinder recomputes them against the live location on merge. */
    private val _combinedPeaks = MutableStateFlow<List<NearbyPeak>>(emptyList())
    val combinedPeaks: StateFlow<List<NearbyPeak>> = _combinedPeaks.asStateFlow()

    init {
        scope.launch { reseed() }
    }

    /**
     * Prefetch and persist a pack centred on (lat, lon) out to [radiusKm].
     * No-ops if a download is already running; publishes progress as it goes.
     */
    suspend fun createPack(name: String, centerLat: Double, centerLon: Double, radiusKm: Double) {
        if (_isDownloading.value) return
        _isDownloading.value = true
        _progress.value = 0f
        _statusText.value = "Finding peaks…"
        try {
            // 1. Peaks across the whole radius (one throttled Overpass query).
            val osm = peakFinder.fetchPeaksForArea(centerLat, centerLon, radiusKm * 1000.0)
            val peaks = osm.take(maxPackPeaks)

            // 2. DEM: the centre's full skyline panorama (180×20 polar grid),
            //    fetched in chunks for progress; each chunk goes through the
            //    elevation service's own 200 ms throttle + retry.
            _statusText.value = "Caching terrain…"
            val coords = SkylineCalculator.skylineGridCoordinates(centerLat, centerLon)
            val cellMap = HashMap<Pair<Int, Int>, Double>()
            val chunk = 300
            var processed = 0
            var i = 0
            while (i < coords.size) {
                val end = minOf(i + chunk, coords.size)
                val slice = coords.subList(i, end)
                val elevs = terrain.elevations(slice)
                for ((c, e) in slice.zip(elevs)) {
                    if (e != null) {
                        cellMap[TerrainElevationService.milliDeg(c.first) to
                            TerrainElevationService.milliDeg(c.second)] = e
                    }
                }
                processed += slice.size
                _progress.value = processed.toFloat() / coords.size
                i = end
            }
            val cells = cellMap.map { (k, v) -> ElevationCacheStore.Entry(k.first, k.second, v) }

            // 3. Persist + register.
            val id = UUID.randomUUID().toString()
            store.saveData(
                id,
                OfflinePackData(
                    peaks = peaks.map { PackPeak(it.name, it.latitude, it.longitude, it.altitude) },
                    cells = cells
                )
            )
            val packName = name.trim().ifEmpty { defaultName(centerLat, centerLon) }
            val meta = OfflinePack(
                id = id, name = packName,
                centerLat = centerLat, centerLon = centerLon,
                radiusKm = radiusKm, createdAt = System.currentTimeMillis(),
                peakCount = peaks.size, cellCount = cells.size
            )
            store.saveIndex((listOf(meta) + _packs.value).sortedByDescending { it.createdAt })
            reseed()
        } finally {
            _isDownloading.value = false
            _statusText.value = ""
            _progress.value = 0f
        }
    }

    /** Delete a saved pack and re-seed the live caches without it. */
    fun delete(pack: OfflinePack) {
        scope.launch {
            store.deleteData(pack.id)
            store.saveIndex(_packs.value.filterNot { it.id == pack.id })
            reseed()
        }
    }

    /** Rebuild [combinedPeaks] and the elevation service's pinned cells from
     *  every saved pack. Called at launch and after any pack change. */
    private suspend fun reseed() {
        val metas = store.loadIndex().sortedByDescending { it.createdAt }
        val cells = ArrayList<ElevationCacheStore.Entry>()
        val peaks = ArrayList<NearbyPeak>()
        val seen = HashSet<UUID>()
        for (m in metas) {
            val data = store.loadData(m.id) ?: continue
            cells.addAll(data.cells)
            for (p in data.peaks) {
                val np = NearbyPeak.create(p.name, p.lat, p.lon, p.altitude, 0.0, 0.0)
                if (seen.add(np.id)) peaks.add(np)
            }
        }
        terrain.setPinned(cells)
        _packs.value = metas
        _combinedPeaks.value = peaks
    }

    private fun defaultName(lat: Double, lon: Double): String =
        String.format(Locale.US, "Area %.3f, %.3f", lat, lon)
}
