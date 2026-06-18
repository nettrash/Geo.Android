package me.nettrash.geo.offline

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nettrash.geo.ar.ElevationCacheStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure (no Android context) tests for the offline-pack on-disk format and the
 * seed-assembly dedupe/union. Sibling of iOS `OfflinePackTests`.
 */
class OfflinePackAssemblyTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun packDataRoundTrips() {
        val data = OfflinePackData(
            peaks = listOf(PackPeak("Rainier", 46.8523, -121.7603, 4392.0)),
            cells = listOf(ElevationCacheStore.Entry(46852, -121760, 4392.0))
        )
        val decoded = json.decodeFromString<OfflinePackData>(json.encodeToString(data))

        assertEquals(1, decoded.peaks.size)
        assertEquals("Rainier", decoded.peaks[0].name)
        assertEquals(1, decoded.cells.size)
        assertEquals(4392.0, decoded.cells[0].elev, 0.0)
    }

    @Test
    fun assembleSeedDedupesPeaksAndUnionsCells() {
        val shared = PackPeak("Shared", 45.0, 7.0, 3000.0)
        val packA = OfflinePackData(
            peaks = listOf(shared, PackPeak("OnlyA", 45.1, 7.1, 2500.0)),
            cells = listOf(
                ElevationCacheStore.Entry(45000, 7000, 3000.0),
                ElevationCacheStore.Entry(45100, 7100, 2500.0)
            )
        )
        val packB = OfflinePackData(
            peaks = listOf(shared, PackPeak("OnlyB", 46.0, 8.0, 4000.0)),
            cells = listOf(
                ElevationCacheStore.Entry(46000, 8000, 4000.0),
                ElevationCacheStore.Entry(45000, 7000, 3100.0) // collides with packA
            )
        )

        val (cells, peaks) = OfflinePackRepository.assembleSeed(listOf(packA, packB))

        // Peaks: the shared coordinate collapses to one → 3 unique.
        assertEquals(3, peaks.size)
        assertEquals(1, peaks.count { it.name == "Shared" })

        // Cells: 3 distinct keys; the colliding key takes the later pack's value.
        assertEquals(3, cells.size)
        val collided = cells.first { it.lat == 45000 && it.lon == 7000 }
        assertEquals(3100.0, collided.elev, 0.0)
    }

    @Test
    fun assembleSeedEmptyIsEmpty() {
        val (cells, peaks) = OfflinePackRepository.assembleSeed(emptyList())
        assertEquals(0, cells.size)
        assertEquals(0, peaks.size)
    }
}
