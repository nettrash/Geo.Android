package me.nettrash.geo.util

/*
 * ── The honesty contract ────────────────────────────────────────────────
 * Committed atop BOTH cores (iOS `Geo/Core/Geomagnetic.swift` and this
 * file). Everything here is arithmetic over a number fetched from SWPC and
 * the user's coordinates. Nothing here reads a sensor, and the UI built on
 * it must NEVER claim:
 *
 *  1. That Geo finds a geomagnetic storm using this device's own hardware.
 *     A phone magnetometer's noise floor is ±190–220 nT and does not
 *     average down; the whole G1–G3 mid-latitude signal is 70–330 nT;
 *     thermal drift is ±200 nT/K and baseline wander ±1500–2000 nT. The
 *     signal is an order of magnitude under the instrument.
 *  2. Any Kp / ap / K / Dst / SYM-H figure derived from this device.
 *  3. That a field change observed on the device is attributable to space
 *     weather. Moving 20 cm changes it more than a G5.
 *  4. An absolute field-strength reading.
 *  5. That Geo has observed a solar flare or CME, or anything at all about
 *     the Sun.
 *  6. ANY health, wellness, sleep or mood claim — not hedged, not "some
 *     people report", not in an FAQ. The largest study (63 M posts across
 *     a solar maximum) is a well-powered NULL result.
 *  7. A headache line on the card. One sourced sentence lives in the
 *     explainer sheet only and is never promoted to a readout.
 *  8. That the aurora verdict forecasts what you will see. No brightness
 *     adjective anywhere — the model has no brightness term.
 *  9. That the compass figure is a measurement, or a correction to apply.
 * 10. GPS degradation quantified in metres on the card.
 * 11. That a local declination anomaly is space weather — crustal
 *     anomalies of 3–4° are common and can exceed 10°.
 * 12. An aurora verdict from the raw dipole when the grid failed to
 *     decode. See [MagneticConditions.magneticLatitudeDeg].
 * 13. A decimal Kp threshold. Kp lives on thirds; see [kpThirdsLabel].
 * 14. "Official", NOAA branding, or any implied endorsement.
 * 15. That an R-scale or S-scale event affects a hiker's VHF/UHF handheld,
 *     PLB or satellite messenger. Geo shows only the G scale.
 * 16. "No network required" for this card. It is the one card that needs
 *     a fetch.
 *
 * Structural guarantee: there is no function anywhere in this core that
 * could emit a device-derived index, and no file in this feature imports a
 * magnetometer sensor API. The vocabulary of on-device discovery is absent
 * from the whole feature by construction — copy, identifiers and log
 * messages included.
 * ────────────────────────────────────────────────────────────────────────
 */

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a Kp figure came from. SWPC spells these in its `observed`
 *  field; `PREDICTED` is surfaced to the user as the word "forecast". */
enum class KpProvenance { OBSERVED, ESTIMATED, PREDICTED }

/** One 3-hour Kp bin. [timeMs] is the START of the bin, UTC. */
data class KpPoint(
    val timeMs: Long,
    val kp: Double,
    val provenance: KpProvenance
)

/**
 * A fetched Kp series. [points] is ALWAYS sorted ascending by time —
 * SWPC's ordering is not consistent across its products, so the parser
 * sorts and nothing downstream may index by array position.
 */
data class KpSeries(
    val fetchedAtMs: Long,
    val points: List<KpPoint>
)

/**
 * NOAA G scale. The ordinal IS the raw value (G0 = 0 … G5 = 5), and
 * Kotlin enums are Comparable by ordinal, so `gScale >= GScale.G3` reads
 * exactly like the Swift `Comparable` conformance.
 */
enum class GScale(val rawValue: Int) { G0(0), G1(1), G2(2), G3(3), G4(4), G5(5) }

/** What the auroral oval is doing relative to the observer. `UNKNOWN`
 *  means we are missing an input, never "probably nothing". */
enum class AuroraVisibility { UNKNOWN, NOT_VISIBLE, HORIZON_GLOW, OVAL_REACHES, OVERHEAD }

/** Order-of-magnitude band for the storm-time swing of the horizontal
 *  field, expressed as an angle. An explainer figure, never a correction
 *  to dial into a compass (honesty contract 9). */
enum class CompassBand { NORMAL, UNDER_ONE, ONE_TO_TWO, TWO_TO_FIVE, OVER_FIVE }

/** Ionospheric-scintillation advisory for GNSS. Latitude-gated, not
 *  G-only — see [Geomagnetic.GNSS_HIGH_MLAT_DEG]. */
enum class GnssAdvisory { NOMINAL, MAY_DEGRADE, DEGRADED }

/** How old our copy of the Kp product is — and, below the live window,
 *  whether it still covers now: [CACHED] means one of the payload's own bins
 *  contains this moment. [EXPIRED] still yields magnetic latitude and the Kp
 *  threshold for this spot (neither depends on Kp), but never a stale "now"
 *  value. */
enum class Freshness { LIVE, CACHED, EXPIRED, NONE }

private const val MS_PER_HOUR = 3_600_000.0

/**
 * The AACGM-v2 correction grid: 65 × 72 nodes of `Int8` tenths of a
 * degree, added to the centred-dipole magnetic latitude to land on the
 * real corrected geomagnetic latitude.
 *
 * WHY IT EXISTS: the centred dipole is three lines of trigonometry and it
 * is badly wrong exactly where our users are — London dipole 53.34
 * against AACGM-v2 47.71, i.e. +5.63°, which is 2.5 whole Kp steps.
 * Dipole-only, Geo would promise a hiker in Britain an aurora at about
 * Kp 2.5 when the truth is Kp 5+. If the asset ever fails to decode the
 * app shows NO aurora verdict at all; it must never quietly fall back to
 * the raw dipole.
 *
 * The grid carries a LOW-LATITUDE TAPER — a smoothstep weight that is 0
 * below |dipole mlat| 35° and 1 above 50° — because AACGM-v2 is undefined
 * near the magnetic equator (150 of the 4680 nodes) and returns wild
 * values just outside it (−20.6° at 25 °N 0 °E), which would overflow the
 * Int8-tenths encoding. Inside the taper floor the apps therefore show
 * plain centred-dipole magnetic latitude. That is correct and harmless:
 * the auroral oval never reaches |mlat| 35 at any Kp, so every tapered
 * location is `NOT_VISIBLE` regardless.
 */
class MLatDeltaGrid private constructor(
    /** Row-major signed tenths of a degree, `raw[row * cols + col]`, row =
     *  latitude index from −80°, col = longitude index from −180°.
     *  Internal rather than private so the node-lookup test can address
     *  the same node the iOS test does. */
    internal val raw: ByteArray
) {

    companion object {
        /** Decodes the shipped asset. Returns null unless the input is
         *  EXACTLY [Geomagnetic.MLAT_GRID_BYTE_COUNT] bytes — a truncated
         *  asset must suppress the aurora rows outright, never silently
         *  degrade to the raw dipole (honesty contract 12). */
        fun decode(bytes: ByteArray): MLatDeltaGrid? {
            if (bytes.size != Geomagnetic.MLAT_GRID_BYTE_COUNT) return null
            return MLatDeltaGrid(bytes.copyOf())
        }
    }

    /**
     * Bilinear correction (degrees) at a geographic position. Longitude
     * WRAPS at the date line — `delta(lat, −180)` and `delta(lat, +180)`
     * are the same node, bit for bit — and latitude CLAMPS to the edge
     * row, so a position beyond ±80° reuses the polar row rather than
     * reading off the end of the array.
     */
    fun delta(latitude: Double, longitude: Double): Double {
        // Guard non-finite input before any Int conversion: `Int(NaN)`
        // traps in Swift, and Kotlin would quietly cast it to 0 and
        // return a node value for a position that does not exist.
        if (!latitude.isFinite() || !longitude.isFinite()) return 0.0
        val rows = Geomagnetic.MLAT_GRID_ROWS
        val cols = Geomagnetic.MLAT_GRID_COLS

        val lat = latitude.coerceIn(-90.0, 90.0)
        val fi = (lat - Geomagnetic.MLAT_GRID_LAT_MIN_DEG) / Geomagnetic.MLAT_GRID_LAT_STEP_DEG
        val shifted = (longitude - Geomagnetic.MLAT_GRID_LON_MIN_DEG) % 360.0
        val fj = (if (shifted < 0.0) shifted + 360.0 else shifted) / Geomagnetic.MLAT_GRID_LON_STEP_DEG

        val i0 = floor(fi).toInt().coerceIn(0, rows - 1)
        val i1 = (i0 + 1).coerceIn(0, rows - 1)
        val ti = (fi - i0).coerceIn(0.0, 1.0)
        val jFloor = floor(fj).toInt()
        val j0 = ((jFloor % cols) + cols) % cols
        val j1 = (j0 + 1) % cols
        val tj = fj - floor(fj)

        return node(i0, j0) * (1 - ti) * (1 - tj) +
                node(i1, j0) * ti * (1 - tj) +
                node(i0, j1) * (1 - ti) * tj +
                node(i1, j1) * ti * tj
    }

    /** Node value in degrees. `ByteArray` elements are already signed, so
     *  this is the Int8 the generator wrote, times the tenth-degree
     *  scale. */
    private fun node(i: Int, j: Int): Double =
        raw[i * Geomagnetic.MLAT_GRID_COLS + j].toInt() * Geomagnetic.MLAT_GRID_SCALE_DEG

    /** Structural equality over the raw bytes, so two decodes of the same
     *  asset compare equal exactly as the Swift `Equatable` struct does.
     *  A `data class` cannot do this: its generated `equals` compares the
     *  `ByteArray` by identity, so every decode would differ from every
     *  other one. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MLatDeltaGrid) return false
        return raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int = raw.contentHashCode()
}

/** Where the auroral oval sits relative to the observer tonight, plus the
 *  darkness the observer would need for any of it to matter. */
data class AuroraOutlook(
    val visibility: AuroraVisibility,
    val marginDeg: Double?,
    val rangeKm: Double?,
    val kpNeededForHorizonGlow: Double?,
    val poleBearingDeg: Double?,
    val windowStartMs: Long?,
    val windowEndMs: Long?,
    val hasDarkness: Boolean,
    val nextDarknessMs: Long?,
    val isNorthernHemisphere: Boolean
)

/**
 * Everything the magnetic-conditions card renders, resolved in one pure
 * pass. Every field is derived from a fetched Kp figure and the user's
 * coordinates — nothing here is read from the device.
 *
 * [magneticLatitudeDeg] is null ONLY when the correction grid failed to
 * decode. That case suppresses the Aurora and Magnetic-latitude rows
 * entirely; it must NEVER fall back to the raw dipole, which puts London
 * 5.63° too far poleward and flips its verdict at Kp 7.
 */
data class MagneticConditions(
    val kpNow: Double?,
    val kpProvenance: KpProvenance?,
    val binStartMs: Long?,
    val gScale: GScale,
    val magneticLatitudeDeg: Double?,
    val horizontalIntensityNt: Double?,
    val compass: CompassBand,
    val gnss: GnssAdvisory,
    val aurora: AuroraOutlook,
    val freshness: Freshness,
    val dataAgeMs: Long?
)

/**
 * Pure, dependency-free geomagnetic math for the magnetic-conditions
 * card. Kept line-for-line identical to iOS
 * `Geo/Geo/Core/Geomagnetic.swift` — same constant names, same
 * thresholds, same branch ORDER — so the two files diff cleanly and a
 * divergence on either platform shows up as a failing test on that side.
 * This is the [me.nettrash.geo.sensor.StormWarning] precedent applied to
 * a second shared core.
 *
 * Instants are epoch millis throughout, matching the `InformationToken`
 * wire convention.
 */
object Geomagnetic {

    // ── Shared constants — MUST match iOS Geomagnetic ────────────────

    /** Centred-dipole north pole for epoch 2026.0, computed from the
     *  IGRF-14 coefficients shipped inside `aacgmv2` 2.7.1. NCEI's
     *  published WMM2025 pole is 80.79 °N / 72.76 °W; the two agree to
     *  about 0.04° of tilt, which is all that is claimed here. */
    const val DIPOLE_POLE_LAT_DEG = 80.8302
    const val DIPOLE_POLE_LON_DEG = -72.8013
    /** Dipole moment expressed as the equatorial surface field, nT. */
    const val DIPOLE_MOMENT_NT = 29717.1744

    /** Geometry of the shipped AACGM-v2 correction grid. 65 rows of
     *  2.5° from −80°, 72 columns of 5° from −180°, one signed byte of
     *  tenths of a degree per node = 4680 bytes exactly. */
    const val MLAT_GRID_LAT_MIN_DEG = -80.0
    const val MLAT_GRID_LAT_STEP_DEG = 2.5
    const val MLAT_GRID_LON_MIN_DEG = -180.0
    const val MLAT_GRID_LON_STEP_DEG = 5.0
    const val MLAT_GRID_ROWS = 65
    const val MLAT_GRID_COLS = 72
    const val MLAT_GRID_SCALE_DEG = 0.1
    const val MLAT_GRID_BYTE_COUNT = 4680

    /** NOAA SWPC Aurora Tutorial: the oval's equatorward edge sits at
     *  66.5° magnetic at Kp 0 and moves ~2° equatorward per Kp step,
     *  reaching 48.1° at Kp 9. */
    const val OVAL_BASE_MLAT_DEG = 66.5
    const val OVAL_MLAT_PER_KP = 2.0444
    /** How far equatorward of the oval a glow is still visible ON the
     *  horizon. NOAA: "a person can see aurora even when it is 1000 km
     *  further north" — 1000 km is 8.99° of latitude, and 8.0° is used so
     *  the 100 km emission base stands ~3° above the horizon rather than
     *  exactly grazing it. It also makes Kp 9 land on NOAA's own G5
     *  observation of 40° magnetic. */
    const val HORIZON_REACH_DEG = 8.0
    /** Poleward of the edge by this much and the oval is overhead rather
     *  than merely reaching you. */
    const val OVERHEAD_MARGIN_DEG = 2.0
    /** Kp is defined on 0…9; anything needing more than this is
     *  unreachable, and any figure above it is a corrupt one. */
    const val MAX_KP = 9.0
    /** 1° of latitude on a sphere of mean radius 6371 km (2πR/360 =
     *  111.195). Only ever used to turn the oval margin into an
     *  approximate distance. */
    const val KM_PER_DEGREE_LATITUDE = 111.19
    /** Planetary Kp is published in 3-hour bins. */
    const val KP_BIN_HOURS = 3.0

    /** Sun elevation for an aurora-usable sky, and for a fully dark one.
     *  The values live in [Solar]; mirrored here so this block is the one
     *  place to diff against Swift. */
    const val DARK_ELEVATION_DEG = -12.0
    const val IDEAL_DARK_ELEVATION_DEG = -18.0

    /** G is a FLOOR on the INTEGER Kp step, never a rounding of decimal
     *  Kp: `9-` (= 8.667) is G4, not G5. `round()` here is the bug, and
     *  it has its own named test on both platforms. */
    const val KP_G1 = 5.0
    const val KP_G2 = 6.0
    const val KP_G3 = 7.0
    const val KP_G4 = 8.0
    const val KP_G5 = 9.0

    /** Order-of-magnitude horizontal-component perturbation by G level,
     *  nT, indexed G0…G5. Linearly interpolated on decimal Kp and NEVER
     *  rendered to a decimal in the UI. */
    val STORM_PERTURBATION_NT = doubleArrayOf(0.0, 100.0, 200.0, 400.0, 800.0, 1500.0)

    /** Band edges (degrees) for the compass explainer figure. */
    const val COMPASS_BAND_LOW_DEG = 1.0
    const val COMPASS_BAND_MID_DEG = 2.0
    const val COMPASS_BAND_HIGH_DEG = 5.0

    /** WMM2025 quiet-time declination error model,
     *  σ_D = sqrt(0.26² + (5417/H)²) degrees with H in nT. Explainer
     *  only — it is the model's own uncertainty, not a storm effect. The
     *  FORMULA is authoritative; the prose roundings (~0.36° / ~0.60°)
     *  are not, so do not "fix" the constants to match the prose. */
    const val DECL_SIGMA_BASE_DEG = 0.26
    const val DECL_SIGMA_H_COEFF = 5417.0

    /** Measured dual-frequency PPP 3D RMS stayed under 0.32 m at mid and
     *  low latitude from quiet conditions through a super-storm; only
     *  high latitude climbed (0.163 → 1.051 m). The GNSS advisory is
     *  therefore latitude-gated, not G-only. */
    const val GNSS_HIGH_MLAT_DEG = 55.0

    /** ap for each of the 28 Kp thirds, 0 … 9. There is no `9+` step. */
    val AP_TABLE = intArrayOf(
        0, 2, 3, 4, 5, 6, 7, 9, 12, 15, 18, 22, 27, 32, 39, 48,
        56, 67, 80, 94, 111, 132, 154, 179, 207, 236, 300, 400
    )

    /** Below G3 the compass band is reported as `NORMAL` outright — the
     *  swing is smaller than the crustal anomalies a hiker already walks
     *  through. */
    const val COMPASS_ADVISORY_MIN_G = 3

    /** Network policy. SWPC rewrites its tree every minute, so we throttle
     *  ourselves rather than send conditional GETs that never hit. */
    const val FETCH_MIN_INTERVAL_HOURS = 3.0
    const val LIVE_MAX_AGE_HOURS = 6.0

    /**
     * A BACKSTOP, not the expiry rule. What decides whether a cached copy
     * can still answer "what is Kp right now?" is the payload's OWN forward
     * coverage: one fetch is 81 bins spanning −177 h to +62.5 h (59
     * observed, 5 estimated, 17 predicted, measured against the live
     * endpoint), so a fresh copy keeps a bin over now for about 65 hours.
     * [coveringPoint] is therefore the real gate, and this cap only catches
     * a corrupt or far-future payload that claims coverage it should not
     * have. 72 sits just beyond the measured 65 deliberately: in normal
     * operation coverage always runs out first, so the cache expires when
     * it genuinely stops being able to answer rather than on an arbitrary
     * clock. The old 48 threw away ~17 h of NOAA's own forecast.
     */
    const val CACHED_MAX_AGE_HOURS = 72.0

    /** Opt-in aurora alert gates. There is no age ceiling of its own: the
     *  alert rides the freshness tier, so anything the card is willing to
     *  present as "now" is good enough to alert on, and staleness beyond
     *  that is bounded by [CACHED_MAX_AGE_HOURS]. */
    const val AURORA_ALERT_MIN_KP = 5.0
    const val AURORA_ALERT_COOLDOWN_HOURS = 20.0
    const val AURORA_ALERT_DARK_LOOKAHEAD_HOURS = 6.0

    // ── End of the shared constant block ─────────────────────────────

    /** Kp at which each entry of [STORM_PERTURBATION_NT] applies: quiet,
     *  then the five G boundaries. Derived so the two tables cannot drift
     *  apart. */
    private val PERTURBATION_KNOT_KP = doubleArrayOf(0.0, KP_G1, KP_G2, KP_G3, KP_G4, KP_G5)

    /** How far ahead [nextDarkness] will look. The dark-free stretch is
     *  longest at the pole — late January to mid-November, about 285 days
     *  — so a year of lookahead is the smallest bound that always
     *  answers. */
    const val NEXT_DARKNESS_SEARCH_DAYS = 366

    private const val DEG = 180.0 / Math.PI
    private const val RAD = Math.PI / 180.0

    // ── Magnetic coordinates ────────────────────────────────────────

    /** Centred-dipole magnetic latitude (degrees) of a geographic
     *  position. This is the RAW dipole — on its own it is 5.63° too far
     *  poleward at London. Only [magneticLatitudeDeg] may reach the UI. */
    fun dipoleMagneticLatitudeDeg(latitude: Double, longitude: Double): Double {
        val poleLat = DIPOLE_POLE_LAT_DEG * RAD
        val poleLon = DIPOLE_POLE_LON_DEG * RAD
        val lat = latitude * RAD
        val lon = longitude * RAD
        // AT the dipole pole the sum is sin²+cos², which rounds to
        // 1.0000000000000002 as easily as to 1.0 — and asin() of that is
        // NaN, not 90°. Clamp before the arcsine on both platforms.
        val sinMLat = (sin(poleLat) * sin(lat) + cos(poleLat) * cos(lat) * cos(lon - poleLon))
            .coerceIn(-1.0, 1.0)
        return asin(sinMLat) * DEG
    }

    /** Centred-dipole magnetic longitude (degrees). Not shown on the card;
     *  carried because the pair is the definition of the coordinate and
     *  the Swift side carries it too. */
    fun dipoleMagneticLongitudeDeg(latitude: Double, longitude: Double): Double {
        val poleLat = DIPOLE_POLE_LAT_DEG * RAD
        val poleLon = DIPOLE_POLE_LON_DEG * RAD
        val lat = latitude * RAD
        val lon = longitude * RAD
        return atan2(
            -cos(lat) * sin(lon - poleLon),
            sin(lat) * cos(poleLat) - cos(lat) * sin(poleLat) * cos(lon - poleLon)
        ) * DEG
    }

    /** Corrected geomagnetic latitude (degrees): the dipole plus the
     *  shipped AACGM-v2 delta. [grid] is deliberately NON-optional — a
     *  caller without a grid has no magnetic latitude to show, not a
     *  worse one. */
    fun magneticLatitudeDeg(latitude: Double, longitude: Double, grid: MLatDeltaGrid): Double =
        dipoleMagneticLatitudeDeg(latitude, longitude) + grid.delta(latitude, longitude)

    /** Whether the observer is in the northern MAGNETIC hemisphere, which
     *  is what decides which pole to face — not the geographic sign. */
    fun isNorthernMagnetic(latitude: Double, longitude: Double): Boolean =
        dipoleMagneticLatitudeDeg(latitude, longitude) >= 0.0

    /**
     * True-north bearing (0…360°) toward the magnetic pole of the
     * observer's own hemisphere — the direction to look. In the southern
     * MAGNETIC hemisphere it is the northern bearing turned by exactly
     * 180°, which is exact on a sphere: both poles and the observer share
     * one great circle. The hemisphere that counts is the magnetic one —
     * the oval you could see is the one on your side of the magnetic
     * equator, and the geographic sign disagrees with it over a band
     * several degrees wide.
     */
    fun poleBearingDeg(latitude: Double, longitude: Double): Double {
        val poleLat = DIPOLE_POLE_LAT_DEG * RAD
        val poleLon = DIPOLE_POLE_LON_DEG * RAD
        val lat = latitude * RAD
        val lon = longitude * RAD
        val y = sin(poleLon - lon) * cos(poleLat)
        val x = cos(lat) * sin(poleLat) - sin(lat) * cos(poleLat) * cos(poleLon - lon)
        var bearing = (atan2(y, x) * DEG + 360.0) % 360.0
        if (!isNorthernMagnetic(latitude, longitude)) {
            bearing = (bearing + 180.0) % 360.0
        }
        return bearing
    }

    /** Horizontal field intensity (nT) of the centred dipole at a
     *  magnetic latitude: 19101.8 nT at 50°, 11611.4 nT at 67°. It is the
     *  denominator of the compass figure, which is why a high-latitude
     *  storm bites harder for the same perturbation. */
    fun horizontalIntensityNt(magneticLatitudeDeg: Double): Double =
        DIPOLE_MOMENT_NT * cos(magneticLatitudeDeg * RAD)

    // ── The oval ────────────────────────────────────────────────────

    /** Equatorward edge of the auroral oval (magnetic latitude) at a
     *  given Kp. */
    fun ovalEdgeMLatDeg(kp: Double): Double = OVAL_BASE_MLAT_DEG - OVAL_MLAT_PER_KP * kp

    /** Signed degrees the observer is poleward of the oval's edge. The
     *  `abs()` on magnetic latitude is what makes the southern hemisphere
     *  need no special case at all. */
    fun marginDeg(magneticLatitudeDeg: Double, kp: Double): Double =
        abs(magneticLatitudeDeg) - ovalEdgeMLatDeg(kp)

    /** Ground distance (km) to the oval's edge, either side. */
    fun rangeKm(marginDeg: Double): Double = abs(marginDeg) * KM_PER_DEGREE_LATITUDE

    /** Verdict for a margin. Branch order is part of the contract. */
    fun visibilityForMargin(marginDeg: Double): AuroraVisibility = when {
        marginDeg >= OVERHEAD_MARGIN_DEG -> AuroraVisibility.OVERHEAD
        marginDeg >= 0.0 -> AuroraVisibility.OVAL_REACHES
        marginDeg >= -HORIZON_REACH_DEG -> AuroraVisibility.HORIZON_GLOW
        else -> AuroraVisibility.NOT_VISIBLE
    }

    /** Verdict at a magnetic latitude and Kp. */
    fun visibility(magneticLatitudeDeg: Double, kp: Double): AuroraVisibility =
        visibilityForMargin(marginDeg(magneticLatitudeDeg, kp))

    /**
     * Smallest Kp that would put a glow on this observer's horizon,
     * ROUNDED UP to the next third — Kp lives on thirds and the UI says
     * "about Kp 5+", never a decimal (honesty contract 13). Null when the
     * answer is above Kp 9, i.e. it will not happen. Rounding DOWN is the
     * tempting bug: London's raw 5.2778 must become 5.333, not 5.0.
     */
    fun kpNeededForHorizonGlow(magneticLatitudeDeg: Double): Double? {
        val raw = (OVAL_BASE_MLAT_DEG - HORIZON_REACH_DEG - abs(magneticLatitudeDeg)) / OVAL_MLAT_PER_KP
        val clamped = if (raw < 0.0) 0.0 else raw
        val thirds = ceil(clamped * 3.0) / 3.0
        return if (thirds > MAX_KP) null else thirds
    }

    /** Card copy for that threshold. Never a decimal — honesty contract
     *  13. Null means no Kp is high enough, which is a different sentence
     *  again and belongs to the caller. */
    fun requiredKpLabel(magneticLatitudeDeg: Double): String? {
        val needed = kpNeededForHorizonGlow(magneticLatitudeDeg) ?: return null
        return if (needed <= 0.0) "any night" else "about Kp " + kpThirdsLabel(needed)
    }

    // ── Kp, ap and the G scale ──────────────────────────────────────

    /** Kp as its integer third, 0 … 27. A non-finite Kp is not a Kp: it
     *  would throw out of [roundToInt] rather than index the table. */
    fun kpStep(kp: Double): Int {
        if (!kp.isFinite()) return 0
        return (kp * 3.0).roundToInt().coerceIn(0, AP_TABLE.size - 1)
    }

    /** ap (the linear equivalent of Kp) for a decimal Kp. */
    fun ap(kp: Double): Int = AP_TABLE[kpStep(kp)]

    /** Kp rendered the way the observatories write it: `"0"`, `"0+"`,
     *  `"1-"`, `"5+"`, `"9-"`. The minus form belongs to the NEXT whole
     *  number — 4.667 is "5-", not "4-". */
    fun kpThirdsLabel(kp: Double): String {
        val step = kpStep(kp)
        val whole = step / 3
        return when (step % 3) {
            0 -> "$whole"
            1 -> "$whole+"
            else -> "${whole + 1}-"
        }
    }

    /**
     * NOAA G scale for a decimal Kp — a FLOOR on the integer step, tested
     * downward so the branch order is the rule. `round(8.667) == 9` is
     * THE bug this shape exists to prevent: `9-` is a G4 storm.
     */
    fun gScale(kp: Double): GScale = when {
        kp >= KP_G5 - 1e-6 -> GScale.G5
        kp >= KP_G4 -> GScale.G4
        kp >= KP_G3 -> GScale.G3
        kp >= KP_G2 -> GScale.G2
        kp >= KP_G1 -> GScale.G1
        else -> GScale.G0
    }

    /** Order-of-magnitude horizontal-field perturbation (nT) at a decimal
     *  Kp, linearly interpolated between the G-level anchors and clamped
     *  at both ends. G0 is anchored at Kp 0, G1…G5 at [KP_G1]…[KP_G5]. */
    fun disturbanceNt(kp: Double): Double {
        if (kp <= PERTURBATION_KNOT_KP.first()) return STORM_PERTURBATION_NT.first()
        if (kp >= PERTURBATION_KNOT_KP.last()) return STORM_PERTURBATION_NT.last()
        for (i in 1 until PERTURBATION_KNOT_KP.size) {
            if (kp > PERTURBATION_KNOT_KP[i]) continue
            val lower = PERTURBATION_KNOT_KP[i - 1]
            val t = (kp - lower) / (PERTURBATION_KNOT_KP[i] - lower)
            return STORM_PERTURBATION_NT[i - 1] + t * (STORM_PERTURBATION_NT[i] - STORM_PERTURBATION_NT[i - 1])
        }
        return STORM_PERTURBATION_NT.last()
    }

    /** Angular offset a storm of this size implies for a compass needle
     *  at this H, degrees: atan(dB / H). An ILLUSTRATION of the size of
     *  the effect, not a measurement and not a correction to dial in. */
    fun compassOffsetDeg(kp: Double, horizontalIntensityNt: Double): Double {
        // NaN has to be named here, exactly as [declinationSigmaDeg] names
        // it: `NaN <= 0.0` is FALSE, so a NaN H sails past a bare `<= 0`
        // guard, atan() carries it through, and [compassBand] then fails
        // every `<` comparison in turn and lands on OVER_FIVE — the
        // loudest band in the set, from an input that is not a field
        // strength at all. Swift's `guard h > 0` rejects NaN for free and
        // returns 0 (which reads as UNDER_ONE); Kotlin must ask.
        if (!horizontalIntensityNt.isFinite() || horizontalIntensityNt <= 0.0) return 0.0
        return atan(disturbanceNt(kp) / horizontalIntensityNt) * DEG
    }

    /**
     * Band for the storm-time compass swing. Below G3 the answer is
     * `NORMAL` by fiat, whatever the arithmetic says — the effect is
     * smaller than a phone compass's own error and claiming otherwise
     * would be honesty contract item 9.
     */
    fun compassBand(kp: Double, horizontalIntensityNt: Double): CompassBand {
        if (gScale(kp).rawValue < COMPASS_ADVISORY_MIN_G) return CompassBand.NORMAL
        val theta = compassOffsetDeg(kp, horizontalIntensityNt)
        return when {
            theta < COMPASS_BAND_LOW_DEG -> CompassBand.UNDER_ONE
            theta < COMPASS_BAND_MID_DEG -> CompassBand.ONE_TO_TWO
            theta < COMPASS_BAND_HIGH_DEG -> CompassBand.TWO_TO_FIVE
            else -> CompassBand.OVER_FIVE
        }
    }

    /** WMM2025 quiet-time declination uncertainty (degrees) at a given
     *  horizontal intensity. The FORMULA is authoritative; the prose
     *  roundings (~0.36° at H 20000, ~0.60° at H 10000) are not — do not
     *  "fix" the constants to reproduce them. */
    fun declinationSigmaDeg(horizontalIntensityNt: Double): Double {
        if (!horizontalIntensityNt.isFinite() || horizontalIntensityNt <= 0.0) return DECL_SIGMA_BASE_DEG
        val ratio = DECL_SIGMA_H_COEFF / horizontalIntensityNt
        return sqrt(DECL_SIGMA_BASE_DEG * DECL_SIGMA_BASE_DEG + ratio * ratio)
    }

    /** GNSS advisory, in the spec's own branch order: below G3 nothing is
     *  said at all, then G3–G4 and G5 each apply the latitude gate, and
     *  the missing-latitude case comes LAST. Without a magnetic latitude
     *  we cannot apply the gate, and an ungated advisory would over-warn
     *  every user in the world, so we say nominal rather than guess. */
    fun gnssAdvisory(gScale: GScale, magneticLatitudeDeg: Double?): GnssAdvisory {
        if (gScale <= GScale.G2) return GnssAdvisory.NOMINAL
        if (magneticLatitudeDeg != null) {
            val high = abs(magneticLatitudeDeg) >= GNSS_HIGH_MLAT_DEG
            if (gScale <= GScale.G4) return if (high) GnssAdvisory.MAY_DEGRADE else GnssAdvisory.NOMINAL
            return if (high) GnssAdvisory.DEGRADED else GnssAdvisory.MAY_DEGRADE
        }
        return GnssAdvisory.NOMINAL
    }

    // ── Freshness and the current bin ───────────────────────────────

    /**
     * How much to trust a payload of a given age. COVERAGE is the real
     * gate below the live window: a copy stays CACHED for exactly as long
     * as it still holds a bin over this moment — see [coveringPoint] —
     * and [CACHED_MAX_AGE_HOURS] is only a backstop against a corrupt or
     * far-future payload. A LIVE copy is young enough that [currentPoint]'s
     * fallbacks are still an honest "now", so it is not asked for coverage.
     */
    fun freshness(dataAgeMs: Long?, hasCoveringBin: Boolean): Freshness {
        val age = dataAgeMs ?: return Freshness.NONE
        return when {
            age <= (LIVE_MAX_AGE_HOURS * MS_PER_HOUR).toLong() -> Freshness.LIVE
            hasCoveringBin && age <= (CACHED_MAX_AGE_HOURS * MS_PER_HOUR).toLong() -> Freshness.CACHED
            else -> Freshness.EXPIRED
        }
    }

    /**
     * The bin that strictly CONTAINS now: `now >= start` and
     * `now < start + KP_BIN_HOURS`. Tier 1 of [currentPoint], lifted out so
     * [freshness] and [conditions] can both ask the one question that
     * decides whether an offline copy is still describing this moment.
     * Null when no bin does. Never by array position: SWPC's ordering is
     * not something to rely on.
     */
    fun coveringPoint(points: List<KpPoint>, nowMs: Long): KpPoint? {
        val binMs = (KP_BIN_HOURS * MS_PER_HOUR).toLong()
        return points.sortedBy { it.timeMs }
            .lastOrNull { nowMs >= it.timeMs && nowMs < it.timeMs + binMs }
    }

    /**
     * The Kp bin that describes "now", in this order: the newest bin that
     * CONTAINS now; else the newest observed/estimated bin at or before
     * now; else — for a cache older than its own observed tail — the
     * newest predicted bin covering now. Never by array position: SWPC's
     * ordering is not something to rely on.
     */
    fun currentPoint(points: List<KpPoint>, nowMs: Long): KpPoint? {
        coveringPoint(points, nowMs)?.let { return it }
        val sorted = points.sortedBy { it.timeMs }
        sorted.lastOrNull { it.timeMs <= nowMs && it.provenance != KpProvenance.PREDICTED }
            ?.let { return it }
        return sorted.lastOrNull { it.provenance == KpProvenance.PREDICTED && it.timeMs <= nowMs }
    }

    // ── Darkness ────────────────────────────────────────────────────

    /**
     * The dark window that matters right now: the one already under way
     * if there is one, otherwise tonight's. Asking only for "today's"
     * window is the classic bug — at 02:00 the window under way began
     * yesterday evening, and a naive lookup would report darkness as ~17
     * hours off and suppress the alert while the sky is at its darkest.
     */
    fun darkWindow(
        latitude: Double,
        longitude: Double,
        nowMs: Long,
        timeZone: TimeZone
    ): Solar.NightWindow? {
        val yesterday = Calendar.getInstance(timeZone)
        yesterday.timeInMillis = nowMs
        yesterday.add(Calendar.DAY_OF_YEAR, -1)   // Calendar.add, so a DST day is still one day
        val previous = Solar.nightWindow(yesterday.timeInMillis, latitude, longitude, timeZone)
        if (previous != null && previous.end > nowMs) return previous
        return Solar.nightWindow(nowMs, latitude, longitude, timeZone)
    }

    /** When darkness next returns, for the polar-summer case. Null if it
     *  does not inside the search horizon. */
    fun nextDarkness(
        latitude: Double,
        longitude: Double,
        nowMs: Long,
        timeZone: TimeZone
    ): Long? {
        val probe = Calendar.getInstance(timeZone)
        for (day in 1..NEXT_DARKNESS_SEARCH_DAYS) {
            probe.timeInMillis = nowMs
            probe.add(Calendar.DAY_OF_YEAR, day)
            val window = Solar.nightWindow(probe.timeInMillis, latitude, longitude, timeZone)
            if (window != null) return window.start
        }
        return null
    }

    // ── Aggregation ─────────────────────────────────────────────────

    /**
     * The aurora half of the card. [magneticLatitudeDeg] null (grid
     * failed to decode, or no fix) suppresses every oval field; [kp] null
     * (expired cache) suppresses only the ones that depend on the current
     * storm, so "you need about Kp 5+ here" survives going offline.
     */
    fun auroraOutlook(
        magneticLatitudeDeg: Double?,
        kp: Double?,
        latitude: Double?,
        longitude: Double?,
        nowMs: Long,
        timeZone: TimeZone
    ): AuroraOutlook {
        var northern = true
        var bearing: Double? = null
        var windowStartMs: Long? = null
        var windowEndMs: Long? = null
        var hasDarkness = false
        var nextDarknessMs: Long? = null

        if (latitude != null && longitude != null) {
            northern = isNorthernMagnetic(latitude, longitude)
            bearing = poleBearingDeg(latitude, longitude)
            val window = darkWindow(latitude, longitude, nowMs, timeZone)
            if (window != null) {
                windowStartMs = window.start
                windowEndMs = window.end
                hasDarkness = true
            } else {
                // Only search forward through a polar summer when there
                // is no darkness tonight — the scan is a day-by-day loop.
                nextDarknessMs = nextDarkness(latitude, longitude, nowMs, timeZone)
            }
        }

        val margin = if (magneticLatitudeDeg != null && kp != null) {
            marginDeg(magneticLatitudeDeg, kp)
        } else {
            null
        }
        return AuroraOutlook(
            visibility = if (margin != null) visibilityForMargin(margin) else AuroraVisibility.UNKNOWN,
            marginDeg = margin,
            rangeKm = margin?.let { rangeKm(it) },
            kpNeededForHorizonGlow = magneticLatitudeDeg?.let { kpNeededForHorizonGlow(it) },
            poleBearingDeg = bearing,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            hasDarkness = hasDarkness,
            nextDarknessMs = nextDarknessMs,
            isNorthernHemisphere = northern
        )
    }

    /**
     * Resolve the whole card in one pure pass. Every input is optional
     * because every input really can be missing: no fetch yet, no
     * location fix, a grid asset that failed to decode.
     *
     * An EXPIRED payload drops [MagneticConditions.kpNow] to null — a copy
     * with no bin left over this moment has no "now" to give — but keeps
     * magnetic latitude and the Kp threshold, which are properties of the
     * place and stay true offline. [timeZone] is injected, never read from
     * the device, so the darkness window is deterministic under test.
     */
    fun conditions(
        series: KpSeries?,
        latitude: Double?,
        longitude: Double?,
        grid: MLatDeltaGrid?,
        nowMs: Long,
        timeZone: TimeZone
    ): MagneticConditions {
        val points = series?.points ?: emptyList()
        val dataAgeMs = if (series == null || points.isEmpty()) null else nowMs - series.fetchedAtMs
        val covering = coveringPoint(points, nowMs)
        val dataFreshness = freshness(dataAgeMs, covering != null)
        // LIVE gets the full three-tier answer: inside six hours the newest
        // observed bin is still an honest "now". CACHED is STRICT — the only
        // reason a day-old copy may speak for this moment is that one of its
        // own bins covers this moment, so a non-covering bin is never
        // presented as now.
        val point = when (dataFreshness) {
            Freshness.LIVE -> currentPoint(points, nowMs)
            Freshness.CACHED -> covering
            else -> null
        }
        // Kp is defined on 0…9. A corrupt cache or a malformed row must
        // not run off the ap table or invent a compass band, so the
        // figure is clamped once, here, before anything derives from it.
        val kpNow = point?.kp?.coerceIn(0.0, MAX_KP)
        val scale = if (kpNow != null) gScale(kpNow) else GScale.G0

        val mlat = if (latitude != null && longitude != null && grid != null) {
            magneticLatitudeDeg(latitude, longitude, grid)
        } else {
            null
        }
        val hNt = mlat?.let { horizontalIntensityNt(it) }
        val compass = if (kpNow != null && hNt != null) compassBand(kpNow, hNt) else CompassBand.NORMAL

        return MagneticConditions(
            kpNow = kpNow,
            kpProvenance = point?.provenance,
            binStartMs = point?.timeMs,
            gScale = scale,
            magneticLatitudeDeg = mlat,
            horizontalIntensityNt = hNt,
            compass = compass,
            gnss = gnssAdvisory(scale, mlat),
            aurora = auroraOutlook(mlat, kpNow, latitude, longitude, nowMs, timeZone),
            freshness = dataFreshness,
            dataAgeMs = dataAgeMs
        )
    }
}

/**
 * The opt-in aurora alert gate. Pure — no I/O, no scheduling, no
 * notification: the worker calls this and posts only if it says yes.
 * Kept identical to iOS `AuroraAlert`.
 */
object AuroraAlert {

    /**
     * True only when EVERY gate passes: the feature is on, the payload is
     * [Freshness.LIVE] or [Freshness.CACHED], Kp is at least
     * [Geomagnetic.AURORA_ALERT_MIN_KP], the oval actually reaches the
     * observer, there IS darkness, that darkness is still RUNNING and
     * starts within [Geomagnetic.AURORA_ALERT_DARK_LOOKAHEAD_HOURS] (or is
     * already under way), and we have not notified inside the cooldown.
     *
     * The gates are deliberately conservative: an aurora alert is a
     * middle-of-the-night notification that asks someone to put their
     * boots on, so a false positive costs more than a missed one.
     */
    fun shouldNotify(
        enabled: Boolean,
        conditions: MagneticConditions,
        lastNotifiedAtMs: Long?,
        nowMs: Long
    ): Boolean {
        if (!enabled) return false

        // Freshness IS the age gate. CACHED now means "one of NOAA's own
        // bins covers this moment", which is exactly what makes a multi-day
        // offline trip work: the copy fetched at the trailhead can still
        // raise the storm on the third night out, which is what a 3-day
        // forecast is for. The notification copy says "forecast", so this
        // stays honest.
        if (conditions.freshness != Freshness.LIVE && conditions.freshness != Freshness.CACHED) return false

        val kp = conditions.kpNow ?: return false
        if (kp < Geomagnetic.AURORA_ALERT_MIN_KP) return false

        val visibility = conditions.aurora.visibility
        if (visibility == AuroraVisibility.UNKNOWN || visibility == AuroraVisibility.NOT_VISIBLE) return false

        if (!conditions.aurora.hasDarkness) return false
        val darkStartMs = conditions.aurora.windowStartMs ?: return false
        // The window has to still be RUNNING: one that ended this morning
        // starts "within the lookahead" by arithmetic and is over in fact.
        val darkEndMs = conditions.aurora.windowEndMs ?: return false
        if (darkEndMs <= nowMs) return false
        val lookaheadMs = (Geomagnetic.AURORA_ALERT_DARK_LOOKAHEAD_HOURS * MS_PER_HOUR).toLong()
        if (darkStartMs - nowMs > lookaheadMs) return false

        val last = lastNotifiedAtMs ?: return true
        return nowMs - last >= (Geomagnetic.AURORA_ALERT_COOLDOWN_HOURS * MS_PER_HOUR).toLong()
    }
}
