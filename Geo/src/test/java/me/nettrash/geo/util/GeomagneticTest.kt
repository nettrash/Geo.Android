package me.nettrash.geo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Golden-value tests for the shared geomagnetic core. Mirrors iOS
 * `GeomagneticTests` — the SAME fixture tables, the same tolerances, the
 * same scenario names — so a divergence in either platform's math fails
 * its own tests rather than shipping two different aurora verdicts.
 *
 * Every corrected magnetic latitude below was checked against `aacgmv2`
 * 2.7.1 (IGRF-14, 110 km, epoch 2026.0) while the grid was generated;
 * worst observed grid error was 0.066°.
 */
class GeomagneticTest {

    private val nowMs = 1_700_000_000_000L
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun hours(h: Double): Long = (h * 3_600_000.0).toLong()

    // ── Fixture positions, shared by every table below ───────────────

    private data class Place(val name: String, val lat: Double, val lon: Double)

    private val tromso = Place("Tromso", 69.6492, 18.9553)
    private val reykjavik = Place("Reykjavik", 64.1466, -21.9426)
    private val fairbanks = Place("Fairbanks", 64.8378, -147.7164)
    private val edmonton = Place("Edmonton", 53.5461, -113.4938)
    private val moscow = Place("Moscow", 55.7558, 37.6173)
    private val london = Place("London", 51.5074, -0.1278)
    private val berlin = Place("Berlin", 52.5200, 13.4050)
    private val seattle = Place("Seattle", 47.6062, -122.3321)
    private val chicago = Place("Chicago", 41.8781, -87.6298)
    private val hobart = Place("Hobart", -42.8821, 147.3272)
    private val lancaster = Place("Lancaster", 54.0466, -2.8007)
    private val edinburgh = Place("Edinburgh", 55.9533, -3.1883)
    private val oslo = Place("Oslo", 59.9139, 10.7522)
    private val yekaterinburg = Place("Yekaterinburg", 56.8389, 60.6057)
    private val helsinki = Place("Helsinki", 60.1699, 24.9384)
    private val vladivostok = Place("Vladivostok", 43.1198, 131.8869)

    /** Raw bytes of the shipped correction grid.
     *
     *  Read straight off disk with [File]: this suite runs on the JVM, and
     *  `AssetManager` only exists on a device. Gradle runs unit tests with
     *  the MODULE directory as the working directory; some IDE runners use
     *  the repository root instead, so both are tried. */
    private fun gridBytes(): ByteArray {
        val candidates = listOf(
            File("src/main/assets/spaceweather/mlat_delta_2026.bin"),
            File("Geo/src/main/assets/spaceweather/mlat_delta_2026.bin")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "mlat_delta_2026.bin not found from ${File(".").absolutePath}",
            file
        )
        return file!!.readBytes()
    }

    private val grid: MLatDeltaGrid
        get() = MLatDeltaGrid.decode(gridBytes())!!

    // ── Dipole ──────────────────────────────────────────────────────

    // The three cases where the dipole answer is known without any model:
    // the pole itself, the geographic pole (= the dipole tilt subtracted
    // from 90°), and the point on the pole's own meridian at the equator
    // (= the tilt). The computed tilt is 9.1698°; NCEI's published
    // WMM2025 pole implies ~9.21°, so the two agree to about 0.04° — that
    // is the whole claim, and it is not a three-decimal agreement.
    @Test
    fun dipoleDegenerateCases() {
        assertEquals(
            90.0,
            Geomagnetic.dipoleMagneticLatitudeDeg(
                Geomagnetic.DIPOLE_POLE_LAT_DEG,
                Geomagnetic.DIPOLE_POLE_LON_DEG
            ),
            1e-3
        )
        assertEquals(80.8302, Geomagnetic.dipoleMagneticLatitudeDeg(90.0, 0.0), 1e-3)
        assertEquals(
            9.1698,
            Geomagnetic.dipoleMagneticLatitudeDeg(0.0, Geomagnetic.DIPOLE_POLE_LON_DEG),
            1e-3
        )
    }

    @Test
    fun rawDipoleMagneticLatitudes() {
        val cases = listOf(
            tromso to 67.50, reykjavik to 68.79,
            fairbanks to 65.67, edmonton to 59.98,
            moscow to 51.70, london to 53.34,
            berlin to 52.17, seattle to 53.02,
            chicago to 50.69, hobart to -49.56
        )
        for ((place, expected) in cases) {
            assertEquals(
                place.name,
                expected,
                Geomagnetic.dipoleMagneticLatitudeDeg(place.lat, place.lon),
                1e-2
            )
        }
    }

    // The pole's own meridian is magnetic longitude 180 at the equator and
    // the antimeridian of it is 0 — the only two values the formula can be
    // pinned to without a second model to compare against.
    @Test
    fun dipoleMagneticLongitudeOnThePoleMeridian() {
        assertEquals(
            180.0,
            abs(Geomagnetic.dipoleMagneticLongitudeDeg(0.0, Geomagnetic.DIPOLE_POLE_LON_DEG)),
            1e-6
        )
        assertEquals(
            0.0,
            Geomagnetic.dipoleMagneticLongitudeDeg(0.0, Geomagnetic.DIPOLE_POLE_LON_DEG + 180.0),
            1e-6
        )
    }

    @Test
    fun correctedMagneticLatitudesReachAacgm() {
        val g = grid
        val cases = listOf(
            london to 47.71, lancaster to 50.86, edinburgh to 53.14,
            reykjavik to 64.21, berlin to 48.83, oslo to 57.15,
            yekaterinburg to 54.01, moscow to 52.70, helsinki to 57.23,
            tromso to 67.25, fairbanks to 65.19, edmonton to 60.20,
            seattle to 52.88, chicago to 51.36, hobart to -53.72
        )
        for ((place, expected) in cases) {
            assertEquals(
                place.name,
                expected,
                Geomagnetic.magneticLatitudeDeg(place.lat, place.lon, g),
                0.5
            )
        }
    }

    // The most important test in the feature. At Kp 7 the raw dipole puts
    // London INSIDE the oval and the corrected latitude puts it 4.5°
    // outside — two different verdicts on the same night. Both halves are
    // asserted, so deleting the grid, zeroing it, or "simplifying" the
    // correction away fails here loudly instead of shipping the wrong
    // answer.
    @Test
    fun londonRegression() {
        val kp = 7.0
        assertEquals(52.1892, Geomagnetic.ovalEdgeMLatDeg(kp), 1e-4)

        val corrected = Geomagnetic.magneticLatitudeDeg(london.lat, london.lon, grid)
        assertEquals(47.69, corrected, 0.05)
        assertEquals(-4.50, Geomagnetic.marginDeg(corrected, kp), 0.05)
        assertEquals(AuroraVisibility.HORIZON_GLOW, Geomagnetic.visibility(corrected, kp))

        val raw = Geomagnetic.dipoleMagneticLatitudeDeg(london.lat, london.lon)
        assertEquals(53.34, raw, 0.01)
        assertEquals(1.15, Geomagnetic.marginDeg(raw, kp), 0.01)
        assertEquals(AuroraVisibility.OVAL_REACHES, Geomagnetic.visibility(raw, kp))
        assertNotEquals(
            Geomagnetic.visibility(raw, kp),
            Geomagnetic.visibility(corrected, kp)
        )
    }

    // The southern mirror of londonRegression: at Hobart the correction
    // pushes the observer POLEWARD instead, so the two verdicts differ in
    // the opposite direction. abs() on magnetic latitude is what makes
    // this work with no southern special case.
    @Test
    fun hobartSouthernRegression() {
        val kp = 7.0
        val corrected = Geomagnetic.magneticLatitudeDeg(hobart.lat, hobart.lon, grid)
        assertEquals(-53.72, corrected, 0.5)
        assertEquals(AuroraVisibility.OVAL_REACHES, Geomagnetic.visibility(corrected, kp))

        val raw = Geomagnetic.dipoleMagneticLatitudeDeg(hobart.lat, hobart.lon)
        assertEquals(-49.56, raw, 0.01)
        assertEquals(AuroraVisibility.HORIZON_GLOW, Geomagnetic.visibility(raw, kp))
        assertNotEquals(
            Geomagnetic.visibility(raw, kp),
            Geomagnetic.visibility(corrected, kp)
        )
    }

    // ── Grid integrity ──────────────────────────────────────────────

    // Pins the shipped asset itself. Regenerating the grid with different
    // inputs changes this digest, which is the point: the corrected
    // latitudes above are only golden against THESE bytes.
    @Test
    fun gridSha256MatchesShippedAsset() {
        val digest = MessageDigest.getInstance("SHA-256").digest(gridBytes())
        val hex = digest.joinToString("") { "%02x".format(it) }
        assertEquals("270ce131ed654c14fc686f2ed072f9291889f5874a5f4199ab5829e7a73a85ed", hex)
        assertEquals(Geomagnetic.MLAT_GRID_BYTE_COUNT, gridBytes().size)
    }

    @Test
    fun gridDecodeRejectsWrongByteCount() {
        assertNull(MLatDeltaGrid.decode(ByteArray(Geomagnetic.MLAT_GRID_BYTE_COUNT - 1)))
        assertNull(MLatDeltaGrid.decode(ByteArray(Geomagnetic.MLAT_GRID_BYTE_COUNT + 1)))
        assertNotNull(MLatDeltaGrid.decode(ByteArray(Geomagnetic.MLAT_GRID_BYTE_COUNT)))
    }

    // −180 and +180 are the SAME column, so the two lookups must agree
    // exactly — not within a tolerance. A modulo that can go negative
    // shows up here as a jump across the date line.
    @Test
    fun gridWrapsAtTheDateLine() {
        val g = grid
        for (lat in listOf(-70.0, -40.0, 0.0, 40.0, 55.0, 70.0)) {
            assertEquals("$lat", g.delta(lat, -180.0), g.delta(lat, 180.0), 0.0)
        }
    }

    // Row 54 / column 40 is 55 °N 20 °E. Landing exactly on a node must
    // return that node, which pins the row-major index order.
    @Test
    fun gridReturnsNodeValueAtNode() {
        val g = grid
        val stored = g.raw[54 * Geomagnetic.MLAT_GRID_COLS + 40].toInt() *
                Geomagnetic.MLAT_GRID_SCALE_DEG
        assertEquals(stored, g.delta(55.0, 20.0), 1e-9)
    }

    // Bilinear, so the halfway point between two nodes is their mean in
    // both axes. A nearest-neighbour or transposed lookup fails here.
    @Test
    fun gridMidpointIsTheMeanOfItsNeighbours() {
        val g = grid
        val south = g.delta(55.0, 20.0)
        val north = g.delta(57.5, 20.0)
        val east = g.delta(55.0, 25.0)
        assertEquals((south + north) / 2.0, g.delta(56.25, 20.0), 1e-9)
        assertEquals((south + east) / 2.0, g.delta(55.0, 22.5), 1e-9)
    }

    // Equality is over the BYTES, not the array reference: two decodes of
    // the same asset are the same grid. A `data class` here would compare
    // the ByteArray by identity, so every decode would differ from every
    // other one and anything that ever caches a resolved card by its inputs
    // would recompute for ever — or, worse, keep a stale grid because the
    // new one "differs".
    @Test
    fun gridEqualityIsOverTheRawBytes() {
        val first = MLatDeltaGrid.decode(gridBytes())
        val second = MLatDeltaGrid.decode(gridBytes())
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first, second)
        assertEquals(first!!.hashCode(), second!!.hashCode())

        // And a grid that differs by a single tenth of a degree at one node
        // is a different grid.
        val altered = gridBytes()
        val node = 54 * Geomagnetic.MLAT_GRID_COLS + 40
        altered[node] = (altered[node] + 1).toByte()
        val other = MLatDeltaGrid.decode(altered)
        assertNotNull(other)
        assertNotEquals(first, other)
    }

    // A non-finite coordinate is not a position. `Int(NaN)` traps in Swift,
    // and Kotlin would quietly cast it to 0 and interpolate from the −80°
    // polar row — a correction for Antarctica handed back for a fix we
    // could not place. Both platforms answer a flat zero instead; without
    // the guard the weights themselves go NaN and the correction with them.
    @Test
    fun gridDeltaIsZeroForNonFiniteCoordinates() {
        val g = grid
        assertEquals(0.0, g.delta(Double.NaN, 20.0), 0.0)
        assertEquals(0.0, g.delta(55.0, Double.NaN), 0.0)
        assertEquals(0.0, g.delta(Double.POSITIVE_INFINITY, 20.0), 0.0)
        assertEquals(0.0, g.delta(55.0, Double.NEGATIVE_INFINITY), 0.0)
        // The same node with real coordinates is NOT zero, so the four
        // above are the guard answering and not the grid being empty here.
        assertNotEquals(0.0, g.delta(55.0, 20.0), 1e-9)
    }

    // AACGM-v2 is undefined near the magnetic equator, so the generator
    // tapers the correction to exactly zero below |dipole mlat| 35°. Those
    // locations therefore show plain centred-dipole latitude — harmless,
    // because the oval never reaches |mlat| 35 at any Kp.
    @Test
    fun gridIsZeroInsideTheTaperedEquatorialBand() {
        val g = grid
        assertEquals(0.0, g.delta(0.0, 0.0), 0.0)
        assertEquals(0.0, g.delta(10.0, 60.0), 0.0)
        assertEquals(0.0, g.delta(-20.0, -150.0), 0.0)
    }

    // ── The oval ────────────────────────────────────────────────────

    @Test
    fun ovalEdgeByKp() {
        val cases = listOf(
            0.0 to 66.5000, 1.0 to 64.4556, 3.0 to 60.3668,
            5.0 to 56.2780, 7.0 to 52.1892, 9.0 to 48.1004
        )
        for ((kp, expected) in cases) {
            assertEquals("Kp $kp", expected, Geomagnetic.ovalEdgeMLatDeg(kp), 0.01)
        }
    }

    // Cross-source bracket: our Kp 9 edge minus the horizon reach lands on
    // 40.1° magnetic, and NOAA's own G5 write-up says aurora has been seen
    // down to 40° geomagnetic. Two independent statements, 0.1° apart.
    @Test
    fun ovalEdgeAtKp9BracketsNoaaG5Statement() {
        val seenTo = Geomagnetic.ovalEdgeMLatDeg(9.0) - Geomagnetic.HORIZON_REACH_DEG
        assertEquals(40.0, seenTo, 0.2)
    }

    // Both band edges are INCLUSIVE on the poleward side, so +2.0 is
    // already overhead and −8.0 is still a horizon glow. Off-by-one here
    // moves a verdict by a whole category.
    @Test
    fun visibilityBands() {
        assertEquals(AuroraVisibility.OVERHEAD, Geomagnetic.visibilityForMargin(5.0))
        assertEquals(AuroraVisibility.OVERHEAD, Geomagnetic.visibilityForMargin(2.0))
        assertEquals(AuroraVisibility.OVAL_REACHES, Geomagnetic.visibilityForMargin(1.99))
        assertEquals(AuroraVisibility.OVAL_REACHES, Geomagnetic.visibilityForMargin(0.0))
        assertEquals(AuroraVisibility.HORIZON_GLOW, Geomagnetic.visibilityForMargin(-0.01))
        assertEquals(AuroraVisibility.HORIZON_GLOW, Geomagnetic.visibilityForMargin(-8.0))
        assertEquals(AuroraVisibility.NOT_VISIBLE, Geomagnetic.visibilityForMargin(-8.01))
    }

    @Test
    fun rangeKmFromMargin() {
        assertEquals(500.4, Geomagnetic.rangeKm(-4.5), 0.5)
        assertEquals(500.4, Geomagnetic.rangeKm(4.5), 0.5)   // sign-free
    }

    @Test
    fun requiredKpRoundsUpToTheNextThird() {
        // Tromso's 0.0 is rendered by the UI as "any night", not "Kp 0".
        val cases = listOf(
            Triple("London", 47.71, 5.333),
            Triple("Berlin", 48.83, 5.000),
            Triple("Chicago", 51.36, 3.667),
            Triple("Moscow", 52.70, 3.000),
            Triple("Tromso", 67.25, 0.000)
        )
        for ((name, mlat, expected) in cases) {
            val needed = Geomagnetic.kpNeededForHorizonGlow(mlat)
            assertNotNull(name, needed)
            assertEquals(name, expected, needed!!, 1e-3)
        }
        assertEquals("5+", Geomagnetic.kpThirdsLabel(Geomagnetic.kpNeededForHorizonGlow(47.71)!!))
        assertEquals("5", Geomagnetic.kpThirdsLabel(Geomagnetic.kpNeededForHorizonGlow(48.83)!!))
        assertEquals("4-", Geomagnetic.kpThirdsLabel(Geomagnetic.kpNeededForHorizonGlow(51.36)!!))
        assertEquals("3", Geomagnetic.kpThirdsLabel(Geomagnetic.kpNeededForHorizonGlow(52.70)!!))
    }

    // The card copy for that threshold, assembled in the CORE so both
    // platforms say the same words. Thirds, never a decimal — honesty
    // contract 13.
    @Test
    fun requiredKpLabelsAreThirdsNeverDecimals() {
        assertEquals("about Kp 5+", Geomagnetic.requiredKpLabel(47.71))
        assertEquals("about Kp 5", Geomagnetic.requiredKpLabel(48.83))
        assertEquals("about Kp 4-", Geomagnetic.requiredKpLabel(51.36))
        assertEquals("about Kp 3", Geomagnetic.requiredKpLabel(52.70))
        assertEquals("any night", Geomagnetic.requiredKpLabel(67.25))
        assertNull(Geomagnetic.requiredKpLabel(35.0))
    }

    // London's exact requirement is Kp 5.2778. Rounding to the NEAREST
    // third gives 5.333 too, but rounding DOWN gives 5.0 — a threshold the
    // oval would not actually reach. It must always round up.
    @Test
    fun requiredKpForLondonRoundsUpNotDown() {
        val needed = Geomagnetic.kpNeededForHorizonGlow(47.71)!!
        assertEquals(5.333, needed, 1e-3)
        assertTrue(needed > 5.2778)
        assertNotEquals(5.0, needed, 1e-9)
    }

    @Test
    fun requiredKpIsNullBelowTheOvalReach() {
        // 35° magnetic would need Kp 11.4948 — it does not happen.
        assertNull(Geomagnetic.kpNeededForHorizonGlow(35.0))
    }

    // The threshold has to BE the boundary: at it the oval reaches, a
    // third below it it does not. Tromso is excluded — its threshold is
    // 0.0 and "a third below Kp 0" is not a Kp.
    @Test
    fun requiredKpThresholdIsTheVisibilityBoundary() {
        val mlats = listOf("London" to 47.71, "Berlin" to 48.83, "Chicago" to 51.36, "Moscow" to 52.70)
        for ((name, mlat) in mlats) {
            val needed = Geomagnetic.kpNeededForHorizonGlow(mlat)!!
            assertNotEquals(name, AuroraVisibility.NOT_VISIBLE, Geomagnetic.visibility(mlat, needed))
            assertEquals(name, AuroraVisibility.NOT_VISIBLE, Geomagnetic.visibility(mlat, needed - 0.34))
        }
    }

    // ── Kp arithmetic ───────────────────────────────────────────────

    @Test
    fun gScaleIsAFloorOnTheIntegerStep() {
        val cases = listOf(
            4.667 to GScale.G0, 5.0 to GScale.G1, 5.667 to GScale.G1,
            6.0 to GScale.G2, 7.0 to GScale.G3, 7.667 to GScale.G3,
            8.0 to GScale.G4, 9.0 to GScale.G5
        )
        for ((kp, expected) in cases) {
            assertEquals("Kp $kp", expected, Geomagnetic.gScale(kp))
        }
    }

    // Kp 8.667 is written "9-" and IS a G4 storm. round(8.667) == 9 is the
    // bug this test exists to catch: G is a floor on the integer step, so
    // a decimal Kp must never be rounded to get it.
    @Test
    fun gScaleKp8667IsG4NotG5() {
        assertEquals(GScale.G4, Geomagnetic.gScale(8.667))
        assertEquals("9-", Geomagnetic.kpThirdsLabel(8.667))
    }

    @Test
    fun kpThirdsLabels() {
        val cases = listOf(
            0.0 to "0", 0.333 to "0+", 0.667 to "1-", 1.0 to "1",
            4.667 to "5-", 5.333 to "5+", 8.667 to "9-", 9.0 to "9"
        )
        for ((kp, expected) in cases) {
            assertEquals("Kp $kp", expected, Geomagnetic.kpThirdsLabel(kp))
        }
    }

    @Test
    fun apTableCoversEveryStep() {
        assertEquals(28, Geomagnetic.AP_TABLE.size)
        for (step in 0..27) {
            assertEquals("step $step", Geomagnetic.AP_TABLE[step], Geomagnetic.ap(step / 3.0))
        }
    }

    @Test
    fun apAtNamedKpValues() {
        assertEquals(48, Geomagnetic.ap(5.0))
        assertEquals(80, Geomagnetic.ap(6.0))
        assertEquals(132, Geomagnetic.ap(7.0))
        assertEquals(300, Geomagnetic.ap(8.667))
        assertEquals(400, Geomagnetic.ap(9.0))
    }

    @Test
    fun apTableIsStrictlyMonotonic() {
        for (i in 1 until Geomagnetic.AP_TABLE.size) {
            assertTrue("step $i", Geomagnetic.AP_TABLE[i] > Geomagnetic.AP_TABLE[i - 1])
        }
    }

    // The scale ends at "9". There is no 9+ step, so anything above Kp 9
    // clamps onto the last one rather than running off the table. A
    // non-finite Kp is not a Kp at all and clamps onto the first.
    @Test
    fun thereIsNoKpNinePlusStep() {
        assertEquals(27, Geomagnetic.kpStep(9.0))
        assertEquals(27, Geomagnetic.kpStep(9.5))
        assertEquals(400, Geomagnetic.ap(9.5))
        assertEquals(0, Geomagnetic.kpStep(-1.0))
        assertEquals(0, Geomagnetic.kpStep(Double.NaN))
        assertEquals(0, Geomagnetic.kpStep(Double.POSITIVE_INFINITY))
    }

    // ── Field strength ──────────────────────────────────────────────

    @Test
    fun horizontalIntensityByMagneticLatitude() {
        assertEquals(29717.17, Geomagnetic.horizontalIntensityNt(0.0), 1.0)
        assertEquals(19101.8, Geomagnetic.horizontalIntensityNt(50.0), 1.0)
        assertEquals(11611.4, Geomagnetic.horizontalIntensityNt(67.0), 1.0)
        assertEquals(0.0, Geomagnetic.horizontalIntensityNt(90.0), 1.0)
    }

    @Test
    fun horizontalIntensityStaysInBandBelow70Degrees() {
        for (mlat in -70..70) {
            val h = Geomagnetic.horizontalIntensityNt(mlat.toDouble())
            assertTrue("mlat $mlat", h in 10_000.0..30_000.0)
        }
    }

    // The FORMULA is authoritative, not the prose rounding: WMM2025's
    // write-up says "about 0.36°" and "about 0.60°", and these are the
    // values sqrt(0.26² + (5417/H)²) actually produces. Do not "correct"
    // the constants to match the prose.
    @Test
    fun declinationSigma() {
        assertEquals(0.37545, Geomagnetic.declinationSigmaDeg(20_000.0), 0.005)
        assertEquals(0.60087, Geomagnetic.declinationSigmaDeg(10_000.0), 0.005)
        // At the magnetic pole H goes to zero and the model divides by it.
        // Fall back to the floor rather than return an infinity the UI
        // would render.
        assertEquals(Geomagnetic.DECL_SIGMA_BASE_DEG, Geomagnetic.declinationSigmaDeg(0.0), 0.0)
        assertEquals(Geomagnetic.DECL_SIGMA_BASE_DEG, Geomagnetic.declinationSigmaDeg(Double.NaN), 0.0)
    }

    // ── Advisories ──────────────────────────────────────────────────

    @Test
    fun compassBands() {
        assertEquals(CompassBand.NORMAL, Geomagnetic.compassBand(4.0, 19102.0))
        assertEquals(CompassBand.ONE_TO_TWO, Geomagnetic.compassBand(7.0, 19102.0))
        assertEquals(CompassBand.ONE_TO_TWO, Geomagnetic.compassBand(7.0, 11611.0))
        assertEquals(CompassBand.TWO_TO_FIVE, Geomagnetic.compassBand(9.0, 19102.0))
        assertEquals(CompassBand.OVER_FIVE, Geomagnetic.compassBand(9.0, 11611.0))

        assertEquals(1.199, Geomagnetic.compassOffsetDeg(7.0, 19102.0), 0.01)
        assertEquals(1.973, Geomagnetic.compassOffsetDeg(7.0, 11611.0), 0.01)
        assertEquals(4.489, Geomagnetic.compassOffsetDeg(9.0, 19102.0), 0.01)
        assertEquals(7.359, Geomagnetic.compassOffsetDeg(9.0, 11611.0), 0.01)
    }

    @Test
    fun compassOffsetIsMonotonicInKp() {
        var previous = -1.0
        for (step in 0..18) {
            val kp = step / 2.0
            val theta = Geomagnetic.compassOffsetDeg(kp, 19102.0)
            assertTrue("Kp $kp", theta >= previous)
            previous = theta
        }
    }

    // At Kp 6.99 the arithmetic gives ~1.19°, but that is a G2 storm and
    // the offset is smaller than a phone compass's own error. The card
    // says nothing rather than implying a correction to dial in.
    @Test
    fun compassIsSilentBelowG3() {
        assertTrue(Geomagnetic.compassOffsetDeg(6.99, 19102.0) > 1.0)
        assertEquals(CompassBand.NORMAL, Geomagnetic.compassBand(6.99, 19102.0))
        assertEquals(CompassBand.ONE_TO_TWO, Geomagnetic.compassBand(7.0, 19102.0))
    }

    // A NaN H is not a small H. `NaN <= 0.0` is FALSE, so an unguarded
    // offset carries the NaN into atan and on into [Geomagnetic.compassBand],
    // where every `<` comparison is false in turn and the answer falls
    // through to OVER_FIVE — the loudest band there is, produced by an
    // input that is not a field strength at all. Swift's `guard h > 0`
    // rejects NaN for free and lands on UNDER_ONE; this pins the two
    // platforms to the same answer. Zero and negative H are the same rule.
    @Test
    fun nonFiniteHorizontalIntensityReadsAsNoOffset() {
        for (h in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertEquals("H $h", 0.0, Geomagnetic.compassOffsetDeg(9.0, h), 0.0)
            assertEquals("H $h", CompassBand.UNDER_ONE, Geomagnetic.compassBand(9.0, h))
        }
    }

    @Test
    fun compassBandIsMonotonicInKp() {
        for (h in listOf(19102.0, 11611.0)) {
            var previous = CompassBand.NORMAL
            for (step in 0..90) {
                val kp = step / 10.0
                val band = Geomagnetic.compassBand(kp, h)
                assertTrue("H $h Kp $kp", band.ordinal >= previous.ordinal)
                previous = band
            }
        }
    }

    // Measured PPP error only climbed at high latitude, so a G3 in Berlin
    // is nominal while the same storm at Tromso is not.
    @Test
    fun gnssAdvisoryMatrix() {
        assertEquals(GnssAdvisory.NOMINAL, Geomagnetic.gnssAdvisory(GScale.G2, 60.0))
        assertEquals(GnssAdvisory.NOMINAL, Geomagnetic.gnssAdvisory(GScale.G3, 40.0))
        assertEquals(GnssAdvisory.MAY_DEGRADE, Geomagnetic.gnssAdvisory(GScale.G3, 60.0))
        assertEquals(GnssAdvisory.MAY_DEGRADE, Geomagnetic.gnssAdvisory(GScale.G5, 40.0))
        assertEquals(GnssAdvisory.DEGRADED, Geomagnetic.gnssAdvisory(GScale.G5, 60.0))
        assertEquals(GnssAdvisory.NOMINAL, Geomagnetic.gnssAdvisory(GScale.G3, null))
        // The gate is on |mlat|, so the southern hemisphere reads the same.
        assertEquals(GnssAdvisory.DEGRADED, Geomagnetic.gnssAdvisory(GScale.G5, -60.0))
    }

    // ── Where to look ───────────────────────────────────────────────

    @Test
    fun poleBearings() {
        val cases = listOf(
            moscow to 346.056, vladivostok to 4.643, fairbanks to 21.929,
            london to 345.237, chicago to 3.691, hobart to 189.111
        )
        for ((place, expected) in cases) {
            assertEquals(place.name, expected, Geomagnetic.poleBearingDeg(place.lat, place.lon), 0.01)
        }
    }

    // In the southern hemisphere you face the other way, and on a sphere
    // that is exactly 180° — not "about". The northern branch is the plain
    // great-circle bearing to the dipole pole, which
    // [GeoCalculations.bearing] already computes, so the two can be
    // compared without restating the formula.
    @Test
    fun poleBearingSouthIsNorthPlus180() {
        for (place in listOf(hobart, Place("Ushuaia", -54.8019, -68.3030))) {
            assertFalse(place.name, Geomagnetic.isNorthernMagnetic(place.lat, place.lon))
            val northern = GeoCalculations.bearing(
                place.lat, place.lon,
                Geomagnetic.DIPOLE_POLE_LAT_DEG, Geomagnetic.DIPOLE_POLE_LON_DEG
            )
            assertEquals(
                place.name,
                (northern + 180.0) % 360.0,
                Geomagnetic.poleBearingDeg(place.lat, place.lon),
                1e-9
            )
        }
    }

    // The magnetic equator is tilted ~9° off the geographic one, so there
    // is a band several degrees wide where the two hemispheres disagree —
    // and the pole worth facing is the MAGNETIC one, because the oval you
    // could see is the one on your side of the magnetic equator. Branching
    // on the sign of geographic latitude turns every one of these bearings
    // by 180°: at (−5.0, −72.8013) it answers 180.000 where the truth is
    // 0.000, due north along the pole's own meridian.
    @Test
    fun poleBearingFollowsTheMagneticHemisphereNotTheGeographic() {
        val disagreeing = listOf(
            Place("On the pole meridian", -5.0, -72.8013),
            Place("Amazonia", -4.0, -60.0),
            Place("Malay peninsula", 1.0, 100.0),
            Place("Borneo", 3.0, 110.0)
        )
        for (place in disagreeing) {
            val magneticallyNorthern = Geomagnetic.isNorthernMagnetic(place.lat, place.lon)
            assertTrue(
                "${place.name} is not in the disagreement band",
                (place.lat >= 0.0) != magneticallyNorthern
            )
            val northern = GeoCalculations.bearing(
                place.lat, place.lon,
                Geomagnetic.DIPOLE_POLE_LAT_DEG, Geomagnetic.DIPOLE_POLE_LON_DEG
            )
            assertEquals(
                place.name,
                if (magneticallyNorthern) northern else (northern + 180.0) % 360.0,
                Geomagnetic.poleBearingDeg(place.lat, place.lon),
                1e-9
            )
        }
        assertEquals(0.000, Geomagnetic.poleBearingDeg(-5.0, -72.8013), 1e-9)
    }

    // ── Darkness ────────────────────────────────────────────────────

    /** Local midnight-based instant builder — the calendar is explicit so
     *  no test depends on the machine's zone. */
    private fun instantOf(year: Int, month: Int, day: Int, hour: Int, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.clear()
        c.set(year, month - 1, day, hour, 0, 0)
        return c.timeInMillis
    }

    // Two files hold these numbers so the parity block stays diffable
    // against Swift. This is the pin that keeps them equal.
    @Test
    fun darkElevationConstantsMatchSolar() {
        assertEquals(Solar.ASTRO_DARK_ELEVATION_DEG, Geomagnetic.DARK_ELEVATION_DEG, 0.0)
        assertEquals(Solar.IDEAL_DARK_ELEVATION_DEG, Geomagnetic.IDEAL_DARK_ELEVATION_DEG, 0.0)
    }

    // Midnight sun: no astronomical darkness at all, but the card still
    // has something true to say — the date the nights come back.
    @Test
    fun tromsoMidsummerHasNoDarknessButANextDate() {
        val now = instantOf(2023, 6, 21, 12, utc)
        assertNull(Solar.nightWindow(now, tromso.lat, tromso.lon, utc))

        val conditions = Geomagnetic.conditions(
            series = null,
            latitude = tromso.lat,
            longitude = tromso.lon,
            grid = grid,
            nowMs = now,
            timeZone = utc
        )
        assertFalse(conditions.aurora.hasDarkness)
        assertNotNull(conditions.aurora.nextDarknessMs)
        assertTrue(conditions.aurora.nextDarknessMs!! > now)
    }

    // The window starts on day D and ends on day D+1. Building it inside a
    // single calendar day is the classic bug here and would produce an
    // empty or inverted window every night of the year.
    @Test
    fun londonMidwinterNightSpansMidnight() {
        val now = instantOf(2023, 12, 21, 12, utc)
        val night = Solar.nightWindow(now, london.lat, london.lon, utc)
        assertNotNull(night)
        val start = night!!.start
        val end = night.end

        val startCal = Calendar.getInstance(utc).apply { timeInMillis = start }
        val endCal = Calendar.getInstance(utc).apply { timeInMillis = end }
        assertEquals(21, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(22, endCal.get(Calendar.DAY_OF_MONTH))
        assertTrue(start < end)
        assertTrue(end - start > hours(8.0))

        assertNotNull(night.idealStart)
        assertNotNull(night.idealEnd)
        val idealStart = night.idealStart!!
        val idealEnd = night.idealEnd!!
        assertTrue(start < idealStart)
        assertTrue(idealStart < idealEnd)
        assertTrue(idealEnd < end)
    }

    // At 02:00 the window that matters started YESTERDAY evening. Asking
    // only for "today's" window would report darkness as ~17 hours away
    // and quietly suppress the alert while the sky is at its darkest.
    @Test
    fun darkWindowAlreadyUnderWayIsPreferredOverTonights() {
        val twoAm = instantOf(2023, 12, 22, 2, utc)
        val window = Geomagnetic.darkWindow(london.lat, london.lon, twoAm, utc)
        assertNotNull(window)
        assertTrue(window!!.start <= twoAm)
        assertTrue(window.end > twoAm)
        val startCal = Calendar.getInstance(utc).apply { timeInMillis = window.start }
        assertEquals(21, startCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun equatorEquinoxNightIsAboutTenHours() {
        val now = instantOf(2023, 3, 21, 12, utc)
        val night = Solar.nightWindow(now, 0.0, 0.0, utc)
        assertNotNull(night)
        val start = night!!.start
        val spanHours = (night.end - start) / 3_600_000.0
        assertEquals(10.0, spanHours, 1.0)
        assertTrue(start > now)   // midday query, so the window is the COMING night
    }

    // The dark window has to nest inside civil twilight: it cannot start
    // before dusk or end after the NEXT morning's dawn.
    @Test
    fun nightWindowSitsInsideCivilTwilight() {
        val original = TimeZone.getDefault()
        try {
            // Solar.times reads the device zone; pin it so both sides of
            // the comparison are anchored to the same day.
            TimeZone.setDefault(utc)
            val now = instantOf(2023, 12, 21, 12, utc)
            val night = Solar.nightWindow(now, london.lat, london.lon, utc)!!
            val times = Solar.times(now, london.lat, london.lon, 0.0)

            val civilDusk = times.civilDusk
            assertNotNull(civilDusk)
            assertTrue(civilDusk!! <= night.start)

            val dawnMinutes = Solar.eventUtcMinutes(
                2023, 12, 22, london.lat, london.lon, Solar.CIVIL_ELEVATION, true
            )
            assertNotNull(dawnMinutes)
            val nextCivilDawn = instantOf(2023, 12, 22, 0, utc) + (dawnMinutes!! * 60_000.0).toLong()
            assertTrue(night.end <= nextCivilDawn)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // ── Aggregation and freshness ───────────────────────────────────

    /** A two-bin series [ageHours] old whose newest bin starts
     *  [binOffsetHours] from now (0 = the bin containing now). */
    private fun series(ageHours: Double, kp: Double, binOffsetHours: Double = 0.0): KpSeries =
        KpSeries(
            fetchedAtMs = nowMs - hours(ageHours),
            points = listOf(
                KpPoint(nowMs + hours(binOffsetHours - 3.0), kp - 1.0, KpProvenance.OBSERVED),
                KpPoint(nowMs + hours(binOffsetHours), kp, KpProvenance.OBSERVED)
            )
        )

    /**
     * A payload shaped like the real product: 3-hour bins running from
     * [spanStartHours] to [spanEndHours] RELATIVE TO THE FETCH, observed up
     * to the fetch stamp and forecast after it, fetched [ageHours] ago.
     *
     * The live endpoint returns 81 bins spanning −177 h to +62.5 h (59
     * observed, 5 estimated, 17 predicted), which is why a cached copy can
     * still answer for "now" about 65 hours after it was fetched — the
     * measurement the coverage rule is built on.
     */
    private fun forecastSeries(
        ageHours: Double,
        kp: Double,
        spanStartHours: Double = -177.0,
        spanEndHours: Double = 62.5
    ): KpSeries {
        val fetchedAtMs = nowMs - hours(ageHours)
        val points = mutableListOf<KpPoint>()
        var offset = spanStartHours
        while (offset <= spanEndHours) {
            points += KpPoint(
                timeMs = fetchedAtMs + hours(offset),
                kp = kp,
                provenance = if (offset <= 0.0) KpProvenance.OBSERVED else KpProvenance.PREDICTED
            )
            offset += Geomagnetic.KP_BIN_HOURS
        }
        return KpSeries(fetchedAtMs, points)
    }

    private fun londonConditions(series: KpSeries?): MagneticConditions =
        Geomagnetic.conditions(series, london.lat, london.lon, grid, nowMs, utc)

    @Test
    fun freshnessByAge() {
        // Inside the live window coverage is not asked for at all: the
        // three-tier fallback is still an honest "now" that young.
        assertEquals(Freshness.LIVE, Geomagnetic.freshness(hours(2.0), true))
        assertEquals(Freshness.LIVE, Geomagnetic.freshness(hours(2.0), false))
        // Past it, coverage is the gate — the same age reads either way.
        assertEquals(Freshness.CACHED, Geomagnetic.freshness(hours(9.0), true))
        assertEquals(Freshness.EXPIRED, Geomagnetic.freshness(hours(9.0), false))
        assertEquals(Freshness.CACHED, Geomagnetic.freshness(hours(60.0), true))
        assertEquals(Freshness.EXPIRED, Geomagnetic.freshness(hours(60.0), false))
        // And the backstop, which only a corrupt or far-future payload
        // should ever reach.
        assertEquals(Freshness.EXPIRED, Geomagnetic.freshness(hours(80.0), true))
        assertEquals(Freshness.NONE, Geomagnetic.freshness(null, true))
    }

    @Test
    fun freshnessBands() {
        assertEquals(Freshness.LIVE, londonConditions(series(2.0, 6.0)).freshness)
        assertEquals(Freshness.CACHED, londonConditions(series(9.0, 6.0)).freshness)
        assertEquals(Freshness.EXPIRED, londonConditions(series(60.0, 6.0, -60.0)).freshness)
        assertEquals(Freshness.NONE, londonConditions(null).freshness)
    }

    // Five hours old: no bin contains now, so the current value falls back
    // to the newest observed bin at or before now — not to array position,
    // which SWPC does not order for us. Only the LIVE tier takes that
    // fallback; CACHED is strict about coverage.
    @Test
    fun liveSeriesStillResolvesItsNewestObservedBin() {
        val conditions = londonConditions(series(5.0, 6.0, -5.0))
        assertEquals(Freshness.LIVE, conditions.freshness)
        assertNotNull(conditions.kpNow)
        assertEquals(6.0, conditions.kpNow!!, 1e-9)
        assertEquals(KpProvenance.OBSERVED, conditions.kpProvenance)
        assertNotNull(conditions.binStartMs)
        assertEquals(nowMs - hours(5.0), conditions.binStartMs!!)
    }

    // ── Coverage, not the clock ─────────────────────────────────────

    // Both edges of the 3-hour window. The bin owns its own start instant
    // and every millisecond up to the next one, and nothing outside that.
    @Test
    fun coveringPointOwnsItsBinAtBothEdgesAndNothingOutside() {
        val bin = KpPoint(nowMs, 5.0, KpProvenance.PREDICTED)
        val points = listOf(bin)
        val binMs = hours(Geomagnetic.KP_BIN_HOURS)

        assertEquals(bin, Geomagnetic.coveringPoint(points, nowMs))
        assertEquals(bin, Geomagnetic.coveringPoint(points, nowMs + binMs - 1))

        assertNull(Geomagnetic.coveringPoint(points, nowMs - 1))
        assertNull(Geomagnetic.coveringPoint(points, nowMs + binMs))
        // An empty payload covers nothing at all — there is no bin to be
        // "the last one", so the lookup must answer null and not crash.
        assertNull(Geomagnetic.coveringPoint(emptyList(), nowMs))
    }

    // THE REGRESSION TEST FOR THE OLD 48-HOUR CAP. A copy fetched 60 hours
    // ago still carries a bin over this moment — the payload runs 62.5 h
    // past its own fetch — so it can still answer "what is Kp right now?"
    // with NOAA's own forecast. The old cap threw that away on nothing but
    // a clock.
    @Test
    fun sixtyHourCacheStillCoveringNowIsCachedNotExpired() {
        val series = forecastSeries(ageHours = 60.0, kp = 6.0)
        assertNotNull(Geomagnetic.coveringPoint(series.points, nowMs))

        val conditions = londonConditions(series)
        assertEquals(Freshness.CACHED, conditions.freshness)
        assertNotNull(conditions.kpNow)
        assertEquals(6.0, conditions.kpNow!!, 1e-9)
        // It is a forecast bin, and the Field row's caption says so.
        assertEquals(KpProvenance.PREDICTED, conditions.kpProvenance)
        assertNotNull(conditions.binStartMs)
        assertEquals(nowMs, conditions.binStartMs!!)
        // And the G scale is derived from that forecast bin like any other.
        assertEquals(GScale.G2, conditions.gScale)
    }

    // The same 60 hours, but a payload whose forward run stopped 48 h after
    // the fetch and so has nothing to say about now. Coverage, not the
    // clock, is what expires it — and the rows that never needed the
    // network survive, which is the whole offline tier of this card.
    @Test
    fun sixtyHourCacheThatNoLongerCoversNowExpiresButKeepsTheOnDeviceRows() {
        val series = forecastSeries(ageHours = 60.0, kp = 6.0, spanEndHours = 48.0)
        assertNull(Geomagnetic.coveringPoint(series.points, nowMs))

        val conditions = londonConditions(series)
        assertEquals(Freshness.EXPIRED, conditions.freshness)
        assertNull(conditions.kpNow)
        assertNull(conditions.kpProvenance)
        assertNull(conditions.binStartMs)
        assertEquals(GScale.G0, conditions.gScale)

        val mlat = conditions.magneticLatitudeDeg
        assertNotNull(mlat)
        assertEquals(47.71, mlat!!, 0.5)
        assertNotNull(conditions.aurora.kpNeededForHorizonGlow)
        assertEquals(5.333, conditions.aurora.kpNeededForHorizonGlow!!, 1e-3)
        assertEquals("about Kp 5+", Geomagnetic.requiredKpLabel(mlat))
    }

    // The backstop. This payload reaches 90 h past its own fetch — further
    // forward than the real product has ever run — so coverage alone would
    // let it speak for now at 80 hours old. CACHED_MAX_AGE_HOURS is what
    // stops a corrupt or far-future cache doing that.
    @Test
    fun eightyHourCacheExpiresOnTheBackstopEvenWhileItCoversNow() {
        val series = forecastSeries(ageHours = 80.0, kp = 6.0, spanEndHours = 90.0)
        assertNotNull(Geomagnetic.coveringPoint(series.points, nowMs))

        val conditions = londonConditions(series)
        assertEquals(Freshness.EXPIRED, conditions.freshness)
        assertNull(conditions.kpNow)
        assertNotNull(conditions.magneticLatitudeDeg)
    }

    // Exactly LIVE_MAX_AGE_HOURS old, and with no bin over now: the live
    // window is an age test and nothing else, so this is still LIVE and
    // still answers from the three-tier fallback. A hair past it the same
    // payload is expired, because there the coverage gate takes over.
    @Test
    fun sixHourBoundaryIsStillLive() {
        assertEquals(Freshness.LIVE, Geomagnetic.freshness(hours(6.0), false))

        val series = forecastSeries(ageHours = 6.0, kp = 6.0, spanEndHours = 0.0)
        assertNull(Geomagnetic.coveringPoint(series.points, nowMs))

        val conditions = londonConditions(series)
        assertEquals(Freshness.LIVE, conditions.freshness)
        assertNotNull(conditions.kpNow)
        assertEquals(6.0, conditions.kpNow!!, 1e-9)
        assertEquals(nowMs - hours(6.0), conditions.binStartMs!!)

        val justPastTheBoundary = forecastSeries(ageHours = 6.01, kp = 6.0, spanEndHours = 0.0)
        assertNull(Geomagnetic.coveringPoint(justPastTheBoundary.points, nowMs))
        assertEquals(Freshness.EXPIRED, londonConditions(justPastTheBoundary).freshness)
    }

    // SWPC publishes Kp on 0…9. A malformed row — or a cache written by a
    // build that got the parse wrong — must not carry 9.33 into the ap
    // lookup or the compass band, where it would index off the end of a
    // 28-entry table. It is pinned to 9.0 at the door.
    @Test
    fun kpAboveTheScaleIsClampedToNine() {
        val conditions = londonConditions(series(1.0, 9.33))
        assertNotNull(conditions.kpNow)
        val kpNow = conditions.kpNow!!
        assertEquals(9.0, kpNow, 1e-9)
        assertEquals(GScale.G5, conditions.gScale)
        assertEquals(400, Geomagnetic.ap(kpNow))
        assertEquals("9", Geomagnetic.kpThirdsLabel(kpNow))
    }

    // The other end of the same clamp. A negative Kp is not a quiet sky, it
    // is a broken payload; the card shows the bottom of the scale rather
    // than a G value derived from a number that cannot exist.
    @Test
    fun negativeKpFromACorruptCacheIsClampedToZero() {
        val conditions = londonConditions(series(1.0, -1.0))
        assertNotNull(conditions.kpNow)
        val kpNow = conditions.kpNow!!
        assertEquals(0.0, kpNow, 1e-9)
        assertEquals(GScale.G0, conditions.gScale)
        assertEquals(0, Geomagnetic.ap(kpNow))
        assertEquals(CompassBand.NORMAL, conditions.compass)
    }

    // A two-day-old Kp is not "now" and must not be shown as one — but
    // magnetic latitude and the Kp this place needs are properties of the
    // PLACE and stay true offline, so they survive.
    @Test
    fun expiredSeriesDropsKpButKeepsMagneticLatitude() {
        val fetchedAt = nowMs - hours(60.0)
        val series = KpSeries(
            fetchedAtMs = fetchedAt,
            points = listOf(KpPoint(fetchedAt, 7.0, KpProvenance.OBSERVED))
        )
        val conditions = Geomagnetic.conditions(
            series = series,
            latitude = london.lat,
            longitude = london.lon,
            grid = grid,
            nowMs = nowMs,
            timeZone = utc
        )
        assertEquals(Freshness.EXPIRED, conditions.freshness)
        assertNull(conditions.kpNow)
        assertEquals(GScale.G0, conditions.gScale)
        assertNotNull(conditions.magneticLatitudeDeg)
        assertEquals(47.71, conditions.magneticLatitudeDeg!!, 0.5)
        assertNotNull(conditions.aurora.kpNeededForHorizonGlow)
        assertEquals(5.333, conditions.aurora.kpNeededForHorizonGlow!!, 1e-3)
        assertEquals(AuroraVisibility.UNKNOWN, conditions.aurora.visibility)
        assertNull(conditions.aurora.marginDeg)
    }

    @Test
    fun emptySeriesIsNoDataAndDoesNotCrash() {
        val conditions = londonConditions(KpSeries(nowMs, emptyList()))
        assertEquals(Freshness.NONE, conditions.freshness)
        assertNull(conditions.kpNow)
        assertNull(conditions.dataAgeMs)
        assertEquals(AuroraVisibility.UNKNOWN, conditions.aurora.visibility)
    }

    // Honesty contract item 12: without the correction there is no aurora
    // verdict at all. Falling back to the raw dipole here is the +5.63°
    // London bug wearing a different hat.
    @Test
    fun missingGridSuppressesTheAuroraRowEntirely() {
        val conditions = Geomagnetic.conditions(
            series = series(1.0, 7.0),
            latitude = london.lat,
            longitude = london.lon,
            grid = null,
            nowMs = nowMs,
            timeZone = utc
        )
        assertNull(conditions.magneticLatitudeDeg)
        assertNull(conditions.horizontalIntensityNt)
        assertNull(conditions.aurora.kpNeededForHorizonGlow)
        assertEquals(AuroraVisibility.UNKNOWN, conditions.aurora.visibility)
        assertEquals(GnssAdvisory.NOMINAL, conditions.gnss)
        assertEquals(CompassBand.NORMAL, conditions.compass)
        // The Kp half of the card still works — it needs no position at all.
        assertNotNull(conditions.kpNow)
        assertEquals(7.0, conditions.kpNow!!, 1e-9)
        assertEquals(GScale.G3, conditions.gScale)
    }

    @Test
    fun conditionsWithoutAFixHasNoOvalAndNoBearing() {
        val conditions = Geomagnetic.conditions(
            series = series(1.0, 7.0),
            latitude = null,
            longitude = null,
            grid = null,
            nowMs = nowMs,
            timeZone = utc
        )
        assertNull(conditions.aurora.poleBearingDeg)
        assertFalse(conditions.aurora.hasDarkness)
        assertEquals(AuroraVisibility.UNKNOWN, conditions.aurora.visibility)
    }

    // ── The alert gate ──────────────────────────────────────────────

    /** A synthetic card state for the alert gate. Built directly rather
     *  than through [Geomagnetic.conditions] so each scenario can pin one
     *  variable — a margin of exactly +0.5° is not something you can ask a
     *  real location for. */
    private fun alertConditions(
        kp: Double?,
        marginDeg: Double?,
        freshness: Freshness = Freshness.LIVE,
        dataAgeHours: Double = 1.0,
        hasDarkness: Boolean = true,
        darkStartInHours: Double = 0.0
    ): MagneticConditions {
        val darkStart = if (hasDarkness) nowMs + hours(darkStartInHours) else null
        val aurora = AuroraOutlook(
            visibility = if (marginDeg != null) {
                Geomagnetic.visibilityForMargin(marginDeg)
            } else {
                AuroraVisibility.UNKNOWN
            },
            marginDeg = marginDeg,
            rangeKm = marginDeg?.let { Geomagnetic.rangeKm(it) },
            kpNeededForHorizonGlow = 5.333,
            poleBearingDeg = 345.237,
            windowStartMs = darkStart,
            windowEndMs = darkStart?.plus(hours(8.0)),
            hasDarkness = hasDarkness,
            nextDarknessMs = null,
            isNorthernHemisphere = true
        )
        return MagneticConditions(
            kpNow = kp,
            kpProvenance = KpProvenance.OBSERVED,
            binStartMs = nowMs,
            gScale = kp?.let { Geomagnetic.gScale(it) } ?: GScale.G0,
            magneticLatitudeDeg = if (marginDeg != null) 47.69 else null,
            horizontalIntensityNt = if (marginDeg != null) Geomagnetic.horizontalIntensityNt(47.69) else null,
            compass = CompassBand.NORMAL,
            gnss = GnssAdvisory.NOMINAL,
            aurora = aurora,
            freshness = freshness,
            dataAgeMs = hours(dataAgeHours)
        )
    }

    @Test
    fun alertDoesNotFireWhenDisabled() {
        assertFalse(AuroraAlert.shouldNotify(false, alertConditions(6.0, 3.0), null, nowMs))
    }

    @Test
    fun alertDoesNotFireOnExpiredData() {
        val conditions = alertConditions(6.0, 3.0, freshness = Freshness.EXPIRED, dataAgeHours = 60.0)
        assertFalse(AuroraAlert.shouldNotify(true, conditions, null, nowMs))
    }

    // The offline-trek case, and the reason a 3-day forecast is worth
    // fetching at all: the payload was pulled at the trailhead nearly two
    // days ago, one of NOAA's own bins covers tonight, and London is dark
    // with the oval within horizon reach. Resolved through the real
    // [Geomagnetic.conditions], so it is the coverage rule that decides and
    // not a hand-set freshness — and the notification says "forecast".
    @Test
    fun alertFiresFromAFortyHourCacheThatStillCoversNow() {
        val conditions = londonConditions(forecastSeries(ageHours = 40.0, kp = 6.0))
        assertEquals(Freshness.CACHED, conditions.freshness)
        assertNotNull(conditions.kpNow)
        assertEquals(AuroraVisibility.HORIZON_GLOW, conditions.aurora.visibility)
        // Every other gate is genuinely open, so coverage is what is being
        // tested here and not, say, a missing darkness window.
        assertTrue(conditions.aurora.hasDarkness)
        assertTrue(AuroraAlert.shouldNotify(true, conditions, null, nowMs))
    }

    // The other half of the same rule. Once no bin covers now there is no
    // Kp to alert on, however recently the copy was fetched: a cache only
    // just too old to be LIVE is in exactly the same position as a
    // three-day-old one.
    @Test
    fun alertDoesNotFireWhenNoBinCoversNow() {
        for (ageHours in listOf(7.0, 24.0, 40.0, 60.0, 71.0)) {
            val conditions = londonConditions(
                forecastSeries(ageHours = ageHours, kp = 6.0, spanEndHours = 0.0)
            )
            assertEquals("age $ageHours", Freshness.EXPIRED, conditions.freshness)
            assertNull("age $ageHours", conditions.kpNow)
            assertFalse("age $ageHours", AuroraAlert.shouldNotify(true, conditions, null, nowMs))
        }
        // And however young the copy is: a payload with nothing at or before
        // now has no bin to read at all, so there is no Kp to alert on. (A
        // live copy that does have a past bin still uses the fallback — six
        // hours old is data about now whichever bin it lands on.)
        val futureOnly = KpSeries(
            fetchedAtMs = nowMs - hours(1.0),
            points = listOf(
                KpPoint(nowMs + hours(3.0), 7.0, KpProvenance.PREDICTED),
                KpPoint(nowMs + hours(6.0), 7.0, KpProvenance.PREDICTED)
            )
        )
        val young = londonConditions(futureOnly)
        assertEquals(Freshness.LIVE, young.freshness)
        assertNull(young.kpNow)
        assertFalse(AuroraAlert.shouldNotify(true, young, null, nowMs))
    }

    @Test
    fun alertDoesNotFireBelowKpFive() {
        assertFalse(AuroraAlert.shouldNotify(true, alertConditions(4.9, 3.0), null, nowMs))
    }

    // A card with no Kp figure at all — an expired payload, or a series
    // whose bins do not cover now. Every other gate is wide open here (the
    // oval reaches, the data is fresh, it is dark), so `kpNow ?: return
    // false` is the branch that decides: G0 by default is not the same
    // statement as "quiet", and neither is a reason to wake anybody.
    @Test
    fun alertDoesNotFireWithoutAKpFigure() {
        assertFalse(AuroraAlert.shouldNotify(true, alertConditions(null, 3.0), null, nowMs))
    }

    @Test
    fun alertFiresWhenTheOvalReachesAtKpFive() {
        assertTrue(AuroraAlert.shouldNotify(true, alertConditions(5.0, 0.5), null, nowMs))
    }

    @Test
    fun alertFiresOnHorizonGlow() {
        assertTrue(AuroraAlert.shouldNotify(true, alertConditions(6.0, -4.0), null, nowMs))
    }

    @Test
    fun alertDoesNotFireWhenTheOvalIsOutOfReach() {
        assertFalse(AuroraAlert.shouldNotify(true, alertConditions(6.0, -12.0), null, nowMs))
    }

    @Test
    fun alertDoesNotFireWithoutDarkness() {
        val conditions = alertConditions(6.0, 3.0, hasDarkness = false)
        assertFalse(AuroraAlert.shouldNotify(true, conditions, null, nowMs))
    }

    // A window that started nine hours ago and ended an hour ago still
    // passes the lookahead arithmetic — it is "in the past", not "too far
    // ahead". Only the end stamp catches it.
    @Test
    fun alertDoesNotFireOnceTonightsWindowHasEnded() {
        val conditions = alertConditions(6.0, 3.0, darkStartInHours = -9.0)
        assertFalse(AuroraAlert.shouldNotify(true, conditions, null, nowMs))
    }

    @Test
    fun alertDoesNotFireWhenDarknessIsSevenHoursAway() {
        val conditions = alertConditions(6.0, 3.0, darkStartInHours = 7.0)
        assertFalse(AuroraAlert.shouldNotify(true, conditions, null, nowMs))
    }

    @Test
    fun alertFiresWhenDarknessIsFourHoursAway() {
        val conditions = alertConditions(6.0, 3.0, darkStartInHours = 4.0)
        assertTrue(AuroraAlert.shouldNotify(true, conditions, null, nowMs))
    }

    @Test
    fun alertDoesNotFireInsideTheCooldown() {
        val conditions = alertConditions(6.0, 3.0)
        assertFalse(AuroraAlert.shouldNotify(true, conditions, nowMs - hours(19.0), nowMs))
    }

    @Test
    fun alertFiresAfterTheCooldown() {
        val conditions = alertConditions(6.0, 3.0)
        assertTrue(AuroraAlert.shouldNotify(true, conditions, nowMs - hours(21.0), nowMs))
    }

    // No fix means no magnetic latitude, which means UNKNOWN — and an
    // UNKNOWN verdict never wakes anybody up.
    @Test
    fun alertDoesNotFireWithoutALocationFix() {
        assertFalse(AuroraAlert.shouldNotify(true, alertConditions(6.0, null), null, nowMs))
    }
}
