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
        // Atomic check-then-set so a double-tap (or a caller on a multi-threaded
        // dispatcher) can't slip two concurrent downloads past a plain read+write.
        if (!_isDownloading.compareAndSet(expect = false, update = true)) return
        _progress.value = 0f
        _statusText.value = "Finding peaks…"
        try {
            // 1. Peaks across the whole radius (one throttled Overpass query).
            val osm = peakFinder.fetchPeaksForArea(centerLat, centerLon, radiusKm * 1000.0)
            // Keep the CLOSEST peaks when capping — Overpass returns nodes in
            // arrival order, not by distance, so a naive take could drop nearby
            // peaks while keeping far ones. Mirrors the live PeakFinder path.
            val peaks = osm.sortedBy { it.distance }.take(maxPackPeaks)

            // 2. DEM in three layers, fetched in chunks so we can show
            //    progress; each chunk goes through the elevation service's own
            //    throttle + retry. Area grids (not a single-observer fan) are
            //    what let the offline skyline resolve from *any* point in the
            //    area, not only when standing at the pack centre:
            //     • full-resolution ~110 m core (centre ± radius, budget-capped),
            //     • ~550 m ring to 50 km and ~2.2 km ring to 200 km — the
            //       skyline's full range, so distant mountain ranges stay in the
            //       offline silhouette instead of silently vanishing past the core.
            _statusText.value = "Caching terrain…"
            val fineCoords = SkylineCalculator.offlinePrefetchCoordinates(centerLat, centerLon, radiusKm)
            val mediumCoords = SkylineCalculator.offlineMediumPrefetchCoordinates(centerLat, centerLon)
            val coarseCoords = SkylineCalculator.offlineCoarsePrefetchCoordinates(centerLat, centerLon)
            val totalCount = fineCoords.size + mediumCoords.size + coarseCoords.size
            var processed = 0
            val onChunkDone: (Int) -> Unit = { n ->
                processed += n
                _progress.value = processed.toFloat() / totalCount
            }

            val cells = fetchLayer(fineCoords, { TerrainElevationService.milliDeg(it) }, onChunkDone)
            _statusText.value = "Caching far terrain…"
            val mediumCells = fetchLayer(mediumCoords, { TerrainElevationService.mediumMilliDeg(it) }, onChunkDone)
            val coarseCells = fetchLayer(coarseCoords, { TerrainElevationService.coarseMilliDeg(it) }, onChunkDone)

            // 3. Persist + register. Bail (without recording a metadata entry)
            //    if the data file didn't actually persist — a failed write would
            //    otherwise leave a phantom pack that can never be re-seeded.
            val id = UUID.randomUUID().toString()
            val saved = store.saveData(
                id,
                OfflinePackData(
                    peaks = peaks.map { PackPeak(it.name, it.latitude, it.longitude, it.altitude) },
                    cells = cells,
                    mediumCells = mediumCells,
                    coarseCells = coarseCells
                )
            )
            if (!saved) return
            val packName = name.trim().ifEmpty { defaultName(centerLat, centerLon) }
            val meta = OfflinePack(
                id = id, name = packName,
                centerLat = centerLat, centerLon = centerLon,
                radiusKm = radiusKm, createdAt = System.currentTimeMillis(),
                peakCount = peaks.size, cellCount = cells.size,
                ringCellCount = mediumCells.size + coarseCells.size
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

    /** Rename a saved pack. The name lives only in the index (not the DEM/peak
     *  payload), so this just rewrites the index — no cache reseed needed. A
     *  blank name is ignored (keeps the current name). */
    fun rename(pack: OfflinePack, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val updated = _packs.value
                .map { if (it.id == pack.id) it.copy(name = trimmed) else it }
                .sortedByDescending { it.createdAt }
            store.saveIndex(updated)
            _packs.value = updated
        }
    }

    /** Fetch one layer's grid chunk-by-chunk, keying results with the
     *  layer's own quantiser ([keyMilliDeg]) so they line up with the live
     *  fallback lookups. [onChunkDone] reports each chunk's size so the
     *  caller can publish combined progress across all three layers.
     *  Mirrors iOS `createPack`'s `fetchLayer`. */
    private suspend fun fetchLayer(
        coords: List<Pair<Double, Double>>,
        keyMilliDeg: (Double) -> Int,
        onChunkDone: (Int) -> Unit
    ): List<ElevationCacheStore.Entry> {
        val cellMap = HashMap<Pair<Int, Int>, Double>()
        val chunk = 300
        var i = 0
        while (i < coords.size) {
            val end = minOf(i + chunk, coords.size)
            val slice = coords.subList(i, end)
            val elevs = terrain.elevations(slice)
            for ((c, e) in slice.zip(elevs)) {
                if (e != null) {
                    cellMap[keyMilliDeg(c.first) to keyMilliDeg(c.second)] = e
                }
            }
            onChunkDone(slice.size)
            i = end
        }
        return cellMap.map { (k, v) -> ElevationCacheStore.Entry(k.first, k.second, v) }
    }

    /** Rebuild [combinedPeaks] and the elevation service's pinned cells from
     *  every saved pack. Called at launch and after any pack change. */
    private suspend fun reseed() {
        val metas = store.loadIndex().sortedByDescending { it.createdAt }
        val datas = metas.mapNotNull { store.loadData(it.id) }
        val seed = assembleSeed(datas)
        terrain.setPinned(seed.cells, seed.mediumCells, seed.coarseCells)
        _packs.value = metas
        _combinedPeaks.value = seed.peaks
    }

    private fun defaultName(lat: Double, lon: Double): String =
        String.format(Locale.US, "Area %.3f, %.3f", lat, lon)

    companion object {
        /** Assembled live-cache seed: the fine DEM cells, the two
         *  far-terrain ring layers and the deduped peaks. */
        data class Seed(
            val cells: List<ElevationCacheStore.Entry>,
            val mediumCells: List<ElevationCacheStore.Entry>,
            val coarseCells: List<ElevationCacheStore.Entry>,
            val peaks: List<NearbyPeak>
        )

        /**
         * Pure assembly of the live-cache seed from loaded pack payloads: union
         * each DEM layer (later packs win on a key collision — pre-ring packs
         * simply contribute nothing to the ring layers) and dedupe peaks by
         * their coordinate-derived id. Pure + side-effect-free so it's unit-
         * testable without files or the Android context. Mirrors iOS
         * `OfflinePackManager.assembleSeed`.
         */
        fun assembleSeed(datas: List<OfflinePackData>): Seed {
            val cellMap = LinkedHashMap<Pair<Int, Int>, Double>()
            val mediumMap = LinkedHashMap<Pair<Int, Int>, Double>()
            val coarseMap = LinkedHashMap<Pair<Int, Int>, Double>()
            val peaks = ArrayList<NearbyPeak>()
            val seen = HashSet<UUID>()
            for (data in datas) {
                for (c in data.cells) cellMap[c.lat to c.lon] = c.elev
                for (c in data.mediumCells.orEmpty()) mediumMap[c.lat to c.lon] = c.elev
                for (c in data.coarseCells.orEmpty()) coarseMap[c.lat to c.lon] = c.elev
                for (p in data.peaks) {
                    val np = NearbyPeak.create(p.name, p.lat, p.lon, p.altitude, 0.0, 0.0)
                    if (seen.add(np.id)) peaks.add(np)
                }
            }
            fun entries(m: Map<Pair<Int, Int>, Double>) =
                m.map { (k, v) -> ElevationCacheStore.Entry(k.first, k.second, v) }
            return Seed(entries(cellMap), entries(mediumMap), entries(coarseMap), peaks)
        }
    }
}
