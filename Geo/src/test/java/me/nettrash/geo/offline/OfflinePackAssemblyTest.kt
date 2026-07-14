package me.nettrash.geo.offline

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nettrash.geo.ar.ElevationCacheStore
import me.nettrash.geo.ar.SkylineCalculator
import me.nettrash.geo.ar.TerrainElevationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * Tests for the offline-pack on-disk format, the seed-assembly
 * dedupe/union, and the far-terrain ring layers (prefetch lattices +
 * the pinned-ring fallback lookup). Sibling of iOS `OfflinePackTests`.
 * Robolectric only for the fallback test's `Context`; everything else
 * is pure.
 */
@RunWith(RobolectricTestRunner::class)
class OfflinePackAssemblyTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun packDataRoundTrips() {
        val data = OfflinePackData(
            peaks = listOf(PackPeak("Rainier", 46.8523, -121.7603, 4392.0)),
            cells = listOf(ElevationCacheStore.Entry(46852, -121760, 4392.0)),
            mediumCells = listOf(ElevationCacheStore.Entry(46850, -121760, 4200.0)),
            coarseCells = listOf(ElevationCacheStore.Entry(46860, -121760, 3900.0))
        )
        val decoded = json.decodeFromString<OfflinePackData>(json.encodeToString(data))

        assertEquals(1, decoded.peaks.size)
        assertEquals("Rainier", decoded.peaks[0].name)
        assertEquals(1, decoded.cells.size)
        assertEquals(4392.0, decoded.cells[0].elev, 0.0)
        assertEquals(4200.0, decoded.mediumCells!![0].elev, 0.0)
        assertEquals(3900.0, decoded.coarseCells!![0].elev, 0.0)
    }

    /** A pack file saved before the far-terrain rings existed decodes fine —
     *  the ring layers are simply absent, and seeding treats them as empty. */
    @Test
    fun preRingPackDataStillDecodes() {
        val legacy = """{"peaks":[],"cells":[{"lat":45000,"lon":7000,"elev":3000.0}]}"""
        val decoded = json.decodeFromString<OfflinePackData>(legacy)
        assertEquals(1, decoded.cells.size)
        assertNull(decoded.mediumCells)
        assertNull(decoded.coarseCells)
        val seed = OfflinePackRepository.assembleSeed(listOf(decoded))
        assertEquals(0, seed.mediumCells.size)
        assertEquals(0, seed.coarseCells.size)
    }

    @Test
    fun assembleSeedDedupesPeaksAndUnionsCells() {
        val shared = PackPeak("Shared", 45.0, 7.0, 3000.0)
        val packA = OfflinePackData(
            peaks = listOf(shared, PackPeak("OnlyA", 45.1, 7.1, 2500.0)),
            cells = listOf(
                ElevationCacheStore.Entry(45000, 7000, 3000.0),
                ElevationCacheStore.Entry(45100, 7100, 2500.0)
            ),
            mediumCells = listOf(ElevationCacheStore.Entry(45000, 7000, 2900.0)),
            coarseCells = null
        )
        val packB = OfflinePackData(
            peaks = listOf(shared, PackPeak("OnlyB", 46.0, 8.0, 4000.0)),
            cells = listOf(
                ElevationCacheStore.Entry(46000, 8000, 4000.0),
                ElevationCacheStore.Entry(45000, 7000, 3100.0) // collides with packA
            ),
            mediumCells = listOf(ElevationCacheStore.Entry(45000, 7000, 3050.0)), // collides with packA's ring
            coarseCells = listOf(ElevationCacheStore.Entry(44980, 7020, 2800.0))
        )

        val seed = OfflinePackRepository.assembleSeed(listOf(packA, packB))

        // Peaks: the shared coordinate collapses to one → 3 unique.
        assertEquals(3, seed.peaks.size)
        assertEquals(1, seed.peaks.count { it.name == "Shared" })

        // Cells: 3 distinct keys; the colliding key takes the later pack's value.
        assertEquals(3, seed.cells.size)
        val collided = seed.cells.first { it.lat == 45000 && it.lon == 7000 }
        assertEquals(3100.0, collided.elev, 0.0)

        // Ring layers union the same way, later pack winning; a null layer
        // contributes nothing.
        assertEquals(1, seed.mediumCells.size)
        assertEquals(3050.0, seed.mediumCells.first { it.lat == 45000 && it.lon == 7000 }.elev, 0.0)
        assertEquals(1, seed.coarseCells.size)
    }

    @Test
    fun assembleSeedEmptyIsEmpty() {
        val seed = OfflinePackRepository.assembleSeed(emptyList())
        assertEquals(0, seed.cells.size)
        assertEquals(0, seed.mediumCells.size)
        assertEquals(0, seed.coarseCells.size)
        assertEquals(0, seed.peaks.size)
    }

    // ─── Far-terrain ring layers ───────────────────────────────────

    @Test
    fun ringGridsSnapToTheirOwnLattices() {
        val medium = SkylineCalculator.offlineMediumPrefetchCoordinates(47.1234, 8.5678)
        val coarse = SkylineCalculator.offlineCoarsePrefetchCoordinates(47.1234, 8.5678)
        assertTrue(medium.isNotEmpty())
        assertTrue(coarse.isNotEmpty())
        assertTrue(medium.size <= SkylineCalculator.OFFLINE_MAX_RING_CELLS)
        assertTrue(coarse.size <= SkylineCalculator.OFFLINE_MAX_RING_CELLS)
        // Every node keys to a distinct cell of its own layer — the exact
        // property the live fallback lookup depends on (no phase drift,
        // no duplicate keys, no gaps).
        val mediumKeys = medium.map {
            TerrainElevationService.mediumMilliDeg(it.first) to
                TerrainElevationService.mediumMilliDeg(it.second)
        }.toSet()
        assertEquals(medium.size, mediumKeys.size)
        val coarseKeys = coarse.map {
            TerrainElevationService.coarseMilliDeg(it.first) to
                TerrainElevationService.coarseMilliDeg(it.second)
        }.toSet()
        assertEquals(coarse.size, coarseKeys.size)
    }

    @Test
    fun coarseRingReachesTheSkylineRange() {
        // The coarse ring must reach (nearly) the 200 km skyline range —
        // that's the whole point of the layer: distant mountain ranges
        // staying in the offline silhouette.
        val coarse = SkylineCalculator.offlineCoarsePrefetchCoordinates(47.0, 8.0)
        val maxLatSpanMeters = coarse.maxOf { abs(it.first - 47.0) * 111_320.0 }
        assertTrue(maxLatSpanMeters > 150_000.0)
    }

    @Test
    fun pinnedRingFallbackResolvesWithoutNetwork() = runBlocking {
        val service = TerrainElevationService(RuntimeEnvironment.getApplication())
        service.clearCache()
        // Pin ONLY a coarse cell; query a nearby point that misses the fine
        // and medium layers. It must resolve through the coarse fallback —
        // and because everything resolves from pinned data, no network
        // request is ever attempted (the whole offline promise).
        val lat = 45.003
        val lon = 7.006
        service.setPinned(
            fine = emptyList(),
            medium = emptyList(),
            coarse = listOf(
                ElevationCacheStore.Entry(
                    TerrainElevationService.coarseMilliDeg(lat),
                    TerrainElevationService.coarseMilliDeg(lon),
                    1234.0
                )
            )
        )
        val result = service.elevations(listOf(lat to lon))
        assertEquals(listOf(1234.0), result)
    }
}
