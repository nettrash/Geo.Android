package me.nettrash.geo.offline

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the offline-pack on-disk format and the seed-assembly dedupe.
 * Sibling of iOS `OfflinePackTests`.
 *
 * Packs are PEAKS ONLY. The DEM-grid half (a ~110 m core plus far-terrain rings
 * out to 200 km) existed to feed the terrain skyline; that skyline was removed
 * from the Nature tab, so the grid prefetch, the pinned-cell seeding and their
 * tests went with it. Peak altitudes are still DEM-resolved at download time
 * inside `PeakFinder.fetchPeaksForArea` (one bounded lookup per peak).
 */
class OfflinePackAssemblyTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun packDataRoundTrips() {
        val data = OfflinePackData(
            peaks = listOf(PackPeak("Rainier", 46.8523, -121.7603, 4392.0))
        )
        val decoded = json.decodeFromString<OfflinePackData>(json.encodeToString(data))

        assertEquals(1, decoded.peaks.size)
        assertEquals("Rainier", decoded.peaks[0].name)
        assertEquals(4392.0, decoded.peaks[0].altitude, 1e-9)
    }

    /**
     * A pack file written by an OLDER build still carries the skyline DEM blobs
     * (`cells` / `mediumCells` / `coarseCells`). Those keys are no longer part of
     * the model — `ignoreUnknownKeys` must drop them rather than fail — so an
     * existing pack on a user's device keeps working as a peak-only pack without
     * any migration.
     */
    @Test
    fun legacyPackWithDemBlobsStillDecodes() {
        val legacy = """
            {"peaks":[{"name":"Mont Blanc","lat":45.8326,"lon":6.8652,"altitude":4808.0}],
             "cells":[{"lat":45000,"lon":7000,"elev":3000.0}],
             "mediumCells":[{"lat":45000,"lon":7000,"elev":2900.0}],
             "coarseCells":[{"lat":44980,"lon":7020,"elev":2800.0}]}
        """.trimIndent()
        val decoded = json.decodeFromString<OfflinePackData>(legacy)

        assertEquals(1, decoded.peaks.size)
        assertEquals("Mont Blanc", decoded.peaks[0].name)

        // And it still seeds PeakFinder.
        val seed = OfflinePackRepository.assembleSeed(listOf(decoded))
        assertEquals(1, seed.size)
        assertEquals("Mont Blanc", seed[0].name)
    }

    /**
     * Likewise for the index: an entry written with the old `cellCount` /
     * `ringCellCount` counters decodes into the slimmed metadata.
     */
    @Test
    fun legacyIndexEntryWithCellCountsStillDecodes() {
        val legacy = """
            [{"id":"abc","name":"Rainier area","centerLat":46.85,"centerLon":-121.76,
              "radiusKm":25.0,"createdAt":700000000,"peakCount":3,
              "cellCount":3600,"ringCellCount":12000}]
        """.trimIndent()
        val decoded = json.decodeFromString<List<OfflinePack>>(legacy)

        assertEquals(1, decoded.size)
        assertEquals("Rainier area", decoded[0].name)
        assertEquals(3, decoded[0].peakCount)
    }

    @Test
    fun indexRoundTrips() {
        val meta = OfflinePack(
            id = "abc", name = "Rainier area",
            centerLat = 46.85, centerLon = -121.76,
            radiusKm = 25.0, createdAt = 700_000_000L, peakCount = 3
        )
        val decoded = json.decodeFromString<List<OfflinePack>>(json.encodeToString(listOf(meta)))
        assertEquals(listOf(meta), decoded)
    }

    /**
     * Two packs that share a peak coordinate collapse to a single peak — the id
     * is derived from the coordinate, so the union across packs is deduped.
     */
    @Test
    fun assembleSeedDedupesPeaks() {
        val shared = PackPeak("Shared", 45.0, 7.0, 3000.0)
        val packA = OfflinePackData(peaks = listOf(shared, PackPeak("OnlyA", 45.1, 7.1, 2500.0)))
        val packB = OfflinePackData(peaks = listOf(shared, PackPeak("OnlyB", 46.0, 8.0, 4000.0)))

        val seed = OfflinePackRepository.assembleSeed(listOf(packA, packB))

        assertEquals(3, seed.size)                                   // shared appears once
        assertEquals(1, seed.count { it.name == "Shared" })
        assertTrue(seed.any { it.name == "OnlyA" })
        assertTrue(seed.any { it.name == "OnlyB" })
    }

    @Test
    fun assembleSeedEmptyIsEmpty() {
        assertTrue(OfflinePackRepository.assembleSeed(emptyList()).isEmpty())
    }
}
