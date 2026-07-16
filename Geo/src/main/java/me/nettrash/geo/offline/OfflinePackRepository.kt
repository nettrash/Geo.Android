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
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.util.PeakFinder
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the offline-expedition-pack state and seeds the prefetched peaks back
 * into [PeakFinder] (via [combinedPeaks]), so the AR view can still name the
 * mountains around you on a summit with no signal.
 *
 * Packs are PEAKS ONLY. They used to also prefetch an area DEM grid (a ~110 m
 * core plus far-terrain rings out to 200 km) to feed the terrain skyline; that
 * skyline was removed — the modelled ridge rarely matched the real one on camera
 * — and the grid prefetch went with it. Peak altitudes are still DEM-resolved at
 * download time inside [PeakFinder.fetchPeaksForArea], one bounded lookup per
 * peak.
 *
 * Download reuses the app's existing throttled, retry-backed public-API path
 * ([PeakFinder.fetchPeaksForArea]), so the bounding-box prefetch is automatically
 * polite to Overpass. Sibling of iOS `Core/OfflinePackManager.swift`.
 */
@Singleton
class OfflinePackRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
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

    /** Id of the pack currently being re-downloaded by [updatePack], or null.
     *  Lets the management list show a per-row spinner on exactly that pack. */
    private val _updatingPackId = MutableStateFlow<String?>(null)
    val updatingPackId: StateFlow<String?> = _updatingPackId.asStateFlow()

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

            // Note: `fetchPeaksForArea` already resolves the altitude of every
            // peak whose OSM node carries no `ele` tag, via one batched
            // TerrainElevationService lookup — a bounded number of DEM points
            // (one per peak) that MUST stay, because a peak with no altitude is
            // dropped. What used to sit on top of that was an *area DEM grid*
            // prefetch (a ~110 m core plus far-terrain rings out to 200 km, i.e.
            // tens of thousands of Open-Elevation points) that existed solely to
            // feed the terrain skyline. The skyline is gone, so the grid prefetch
            // is too: packs are now just peaks, which makes them small and quick.

            // 2. Persist + register. Bail (without recording a metadata entry)
            //    if the data file didn't actually persist — a failed write would
            //    otherwise leave a phantom pack that can never be re-seeded.
            val id = UUID.randomUUID().toString()
            val saved = store.saveData(
                id,
                OfflinePackData(
                    peaks = peaks.map { PackPeak(it.name, it.latitude, it.longitude, it.altitude) }
                )
            )
            if (!saved) return
            val packName = name.trim().ifEmpty { defaultName(centerLat, centerLon) }
            val meta = OfflinePack(
                id = id, name = packName,
                centerLat = centerLat, centerLon = centerLon,
                radiusKm = radiusKm, createdAt = System.currentTimeMillis(),
                peakCount = peaks.size
            )
            store.saveIndex((listOf(meta) + _packs.value).sortedByDescending { it.createdAt })
            reseed()
        } finally {
            _isDownloading.value = false
            _statusText.value = ""
            _progress.value = 0f
        }
    }

    /**
     * Re-fetch a saved pack's peaks for its ORIGINAL centre + radius and replace
     * its stored data in place (same id / name / created date). Use it to pick up
     * new OpenStreetMap peaks, or to complete a download that was partial.
     *
     * A failed or empty fetch (offline, Overpass down) is IGNORED — an empty
     * result almost always means the request didn't get through, not that a
     * once-populated area is suddenly peakless, so an update attempt can never
     * wipe a good pack. Mirrors iOS `OfflinePackManager.updatePack`.
     */
    suspend fun updatePack(pack: OfflinePack) {
        if (!_isDownloading.compareAndSet(expect = false, update = true)) return
        _updatingPackId.value = pack.id
        _statusText.value = "Updating…"
        try {
            val osm = peakFinder.fetchPeaksForArea(pack.centerLat, pack.centerLon, pack.radiusKm * 1000.0)
            val peaks = osm.sortedBy { it.distance }.take(maxPackPeaks)
            if (peaks.isEmpty()) return   // never overwrite a good pack with nothing

            val saved = store.saveData(
                pack.id,
                OfflinePackData(peaks = peaks.map { PackPeak(it.name, it.latitude, it.longitude, it.altitude) })
            )
            if (!saved) return
            val updated = _packs.value.map {
                if (it.id == pack.id) it.copy(peakCount = peaks.size) else it
            }
            store.saveIndex(updated)
            reseed()
        } finally {
            _isDownloading.value = false
            _updatingPackId.value = null
            _statusText.value = ""
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

    /** Rebuild [combinedPeaks] from every saved pack. Called at launch and after
     *  any pack change. */
    private suspend fun reseed() {
        val metas = store.loadIndex().sortedByDescending { it.createdAt }
        val datas = metas.mapNotNull { store.loadData(it.id) }
        _packs.value = metas
        _combinedPeaks.value = assembleSeed(datas)
    }

    private fun defaultName(lat: Double, lon: Double): String =
        String.format(Locale.US, "Area %.3f, %.3f", lat, lon)

    companion object {
        /**
         * Pure assembly of the live-cache seed from loaded pack payloads: dedupe
         * peaks by their coordinate-derived id. Pure + side-effect-free so it's
         * unit-testable without files or the Android context. Mirrors iOS
         * `OfflinePackManager.assembleSeed`.
         */
        fun assembleSeed(datas: List<OfflinePackData>): List<NearbyPeak> {
            val peaks = ArrayList<NearbyPeak>()
            val seen = HashSet<UUID>()
            for (data in datas) {
                for (p in data.peaks) {
                    val np = NearbyPeak.create(p.name, p.lat, p.lon, p.altitude, 0.0, 0.0)
                    if (seen.add(np.id)) peaks.add(np)
                }
            }
            return peaks
        }
    }
}
