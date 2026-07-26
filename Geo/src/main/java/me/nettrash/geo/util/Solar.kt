package me.nettrash.geo.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure, on-device solar-event math — sunrise / sunset / golden & blue
 * hour / civil twilight / solar noon — for an exact position and
 * altitude. Implements the NOAA solar-position algorithm (after Meeus);
 * kept byte-identical to iOS `Solar` in `Core/Geometry.swift`.
 *
 * The core `…UtcMinutes` functions are pure (Int date + Double position →
 * Double minutes past 00:00 UTC) so they unit-test deterministically and
 * match across platforms; [times] is the thin wrapper that turns them
 * into epoch-millis instants for the UI.
 */
object Solar {

    /** Sun-centre geometric elevation (deg) at apparent sunrise/sunset.
     *  −0.833° folds in mean refraction (~34′) and the solar semidiameter
     *  (~16′); the altitude horizon-dip is added on top per observer. */
    const val SUNRISE_ELEVATION = -0.833
    /** Civil twilight / blue-hour outer edge: sun centre 6° below horizon. */
    const val CIVIL_ELEVATION = -6.0
    /** Golden-hour band: sun between −4° and +6°. */
    const val GOLDEN_LOWER_ELEVATION = -4.0
    const val GOLDEN_UPPER_ELEVATION = 6.0

    /** Sun centre 12° below the horizon — the nautical/astronomical
     *  boundary, and the outer edge of the aurora-watching window used by
     *  [Geomagnetic]. Above this the residual twilight washes out a low
     *  arc. Mirrored as `Geomagnetic.DARK_ELEVATION_DEG` so the shared
     *  constant block reads as one block on both platforms. */
    const val ASTRO_DARK_ELEVATION_DEG = -12.0
    /** Sun centre 18° below the horizon — no residual twilight anywhere in
     *  the sky. Reported as the "ideal" sub-window, and nullable on
     *  purpose: above ~48.6° of latitude the midsummer sun never gets that
     *  low, so there are nights with a −12° window and no −18° one. */
    const val IDEAL_DARK_ELEVATION_DEG = -18.0

    private const val DEG = 180.0 / Math.PI
    private const val RAD = Math.PI / 180.0

    /** Geographic horizon dip (degrees) for an observer [altitude] m above
     *  MSL, reusing [GeoCalculations.horizonDistance]: `dip = atan(d / R)`.
     *  Makes the sun rise earlier / set later from a summit. Zero at/below
     *  sea level. */
    fun horizonDipDegrees(altitude: Double): Double {
        if (altitude <= 0.0) return 0.0
        return atan(GeoCalculations.horizonDistance(altitude) / GeoCalculations.EARTH_RADIUS) * DEG
    }

    /** Julian Day at 00:00 UTC of the given Gregorian calendar date. */
    fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private data class SunPosition(val declination: Double, val eqTimeMinutes: Double)

    /** Sun declination (radians) and equation of time (minutes) for Julian
     *  century [t]. NOAA formulae. */
    private fun sunPosition(t: Double): SunPosition {
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mRad = m * RAD
        val c = sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                sin(2 * mRad) * (0.019993 - 0.000101 * t) +
                sin(3 * mRad) * 0.000289
        val trueLong = l0 + c
        val omega = (125.04 - 1934.136 * t) * RAD
        val lambda = (trueLong - 0.00569 - 0.00478 * sin(omega)) * RAD
        val epsilon0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val epsilon = (epsilon0 + 0.00256 * cos(omega)) * RAD
        val declination = asin(sin(epsilon) * sin(lambda))
        val l0Rad = l0 * RAD
        val y = tan(epsilon / 2) * tan(epsilon / 2)
        val eqTimeRad = y * sin(2 * l0Rad) -
                2 * e * sin(mRad) +
                4 * e * y * sin(mRad) * cos(2 * l0Rad) -
                0.5 * y * y * sin(4 * l0Rad) -
                1.25 * e * e * sin(2 * mRad)
        return SunPosition(declination, 4 * eqTimeRad * DEG)
    }

    private fun julianCentury(year: Int, month: Int, day: Int, longitude: Double): Double {
        val jdNoon = julianDay(year, month, day) + 0.5 - longitude / 360.0
        return (jdNoon - 2451545.0) / 36525.0
    }

    /** Minutes past 00:00 UTC of solar noon at [longitude] on the date. */
    fun solarNoonUtcMinutes(year: Int, month: Int, day: Int, longitude: Double): Double {
        val t = julianCentury(year, month, day, longitude)
        return 720 - 4 * longitude - sunPosition(t).eqTimeMinutes
    }

    /** Minutes past 00:00 UTC when the sun centre is at [elevationDeg]
     *  (geometric) for the date and position. [rising] selects the morning
     *  (true) or evening (false) crossing. `null` when the sun never
     *  reaches that elevation that day (polar day / night). */
    fun eventUtcMinutes(
        year: Int, month: Int, day: Int,
        latitude: Double, longitude: Double,
        elevationDeg: Double, rising: Boolean
    ): Double? {
        val t = julianCentury(year, month, day, longitude)
        val pos = sunPosition(t)
        val latRad = latitude * RAD
        val elevRad = elevationDeg * RAD
        val cosH = (sin(elevRad) - sin(latRad) * sin(pos.declination)) /
                (cos(latRad) * cos(pos.declination))
        if (cosH > 1 || cosH < -1) return null
        val haMinutes = 4 * acos(cosH) * DEG
        val noon = 720 - 4 * longitude - pos.eqTimeMinutes
        return if (rising) noon - haMinutes else noon + haMinutes
    }

    /** Sun elevation (deg) at solar noon — disambiguates polar day (sun
     *  always up) from polar night (sun always down). */
    fun noonElevationDeg(year: Int, month: Int, day: Int, latitude: Double, longitude: Double): Double {
        val decl = sunPosition(julianCentury(year, month, day, longitude)).declination
        val latRad = latitude * RAD
        return asin(sin(latRad) * sin(decl) + cos(latRad) * cos(decl)) * DEG
    }

    /**
     * Resolved solar windows for one day at one position. Times are epoch
     * millis (absolute instants) — format them in any time zone. `null`
     * fields mean that crossing doesn't occur that day.
     */
    data class Times(
        val civilDawn: Long?,          // sun −6° rising
        val goldenDawn: Long?,         // sun −4° rising  (morning golden start / blue end)
        val sunrise: Long?,            // sun −0.833°−dip rising
        val goldenMorningEnd: Long?,   // sun +6° rising  (morning golden end)
        val solarNoon: Long?,
        val goldenEveningStart: Long?, // sun +6° falling (evening golden start)
        val sunset: Long?,             // sun −0.833°−dip falling
        val goldenDusk: Long?,         // sun −4° falling (evening golden end / blue start)
        val civilDusk: Long?,          // sun −6° falling
        val dayLengthMs: Long?,
        val noonElevationDeg: Double,
        val isPolarDay: Boolean,
        val isPolarNight: Boolean
    )

    /**
     * Build the day's solar windows for the calendar day containing
     * [nowMs] (device time zone) at the observer's position and
     * [altitude] (used for the horizon dip on sunrise/sunset).
     */
    fun times(nowMs: Long, latitude: Double, longitude: Double, altitude: Double): Times {
        // Anchor the solar day to LOCAL NOON, then take that instant's UTC
        // calendar date. Anchoring directly to UTC-midnight of the local
        // date would shift every event by a day in far-east clock zones
        // (UTC+13/+14, west of the date line: Samoa / Kiritimati / Chatham),
        // where the local day's solar events fall on the previous UTC date.
        val local = Calendar.getInstance()
        local.timeInMillis = nowMs
        local.set(Calendar.HOUR_OF_DAY, 12)
        local.set(Calendar.MINUTE, 0)
        local.set(Calendar.SECOND, 0)
        local.set(Calendar.MILLISECOND, 0)
        val localNoonMs = local.timeInMillis

        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = localNoonMs
        val year = utc.get(Calendar.YEAR)
        val month = utc.get(Calendar.MONTH) + 1
        val day = utc.get(Calendar.DAY_OF_MONTH)
        utc.clear()
        utc.set(year, month - 1, day, 0, 0, 0)
        val utcMidnight = utc.timeInMillis

        fun toMs(minutes: Double?): Long? = minutes?.let { utcMidnight + (it * 60_000.0).toLong() }
        fun event(elevation: Double, rising: Boolean): Double? =
            eventUtcMinutes(year, month, day, latitude, longitude, elevation, rising)

        val riseSetElevation = SUNRISE_ELEVATION - horizonDipDegrees(altitude)
        val sunrise = toMs(event(riseSetElevation, true))
        val sunset = toMs(event(riseSetElevation, false))
        val noonElev = noonElevationDeg(year, month, day, latitude, longitude)
        val polarDay = sunrise == null && sunset == null && noonElev > riseSetElevation
        val polarNight = sunrise == null && sunset == null && noonElev <= riseSetElevation

        return Times(
            civilDawn = toMs(event(CIVIL_ELEVATION, true)),
            goldenDawn = toMs(event(GOLDEN_LOWER_ELEVATION, true)),
            sunrise = sunrise,
            goldenMorningEnd = toMs(event(GOLDEN_UPPER_ELEVATION, true)),
            solarNoon = toMs(solarNoonUtcMinutes(year, month, day, longitude)),
            goldenEveningStart = toMs(event(GOLDEN_UPPER_ELEVATION, false)),
            sunset = sunset,
            goldenDusk = toMs(event(GOLDEN_LOWER_ELEVATION, false)),
            civilDusk = toMs(event(CIVIL_ELEVATION, false)),
            dayLengthMs = if (sunrise != null && sunset != null) sunset - sunrise else null,
            noonElevationDeg = noonElev,
            isPolarDay = polarDay,
            isPolarNight = polarNight
        )
    }

    /**
     * The dark part of ONE night, as absolute instants. [start] falls on
     * the evening of day D and [end] on the morning of day D+1 — the
     * window ALWAYS spans local midnight. Treating it as a single-day
     * interval is the classic bug in this kind of code (you get an empty
     * or inverted window every night of the year), so it has its own named
     * test on both platforms.
     *
     * [idealStart]/[idealEnd] bound the −18° sub-window and are null
     * TOGETHER whenever the sun never gets that low.
     */
    data class NightWindow(
        val start: Long,
        val end: Long,
        val idealStart: Long?,
        val idealEnd: Long?
    )

    /** UTC calendar date the solar-event formulae are evaluated for, plus
     *  that date's 00:00 UTC instant — the base the `…UtcMinutes` results
     *  are offsets from. */
    private class SolarDate(val year: Int, val month: Int, val day: Int, val utcMidnightMs: Long)

    /** UTC date (and its midnight) containing [instantMs]. Callers pass
     *  LOCAL noon, exactly as [times] does, so a far-east clock zone
     *  (UTC+13/+14) doesn't shift the whole night onto the wrong date. */
    private fun solarDate(instantMs: Long): SolarDate {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = instantMs
        val year = utc.get(Calendar.YEAR)
        val month = utc.get(Calendar.MONTH) + 1
        val day = utc.get(Calendar.DAY_OF_MONTH)
        utc.clear()
        utc.set(year, month - 1, day, 0, 0, 0)
        return SolarDate(year, month, day, utc.timeInMillis)
    }

    /** Local 12:00 of the day containing [instantMs] in [timeZone]. */
    private fun localNoon(instantMs: Long, timeZone: TimeZone): Calendar {
        val c = Calendar.getInstance(timeZone)
        c.timeInMillis = instantMs
        c.set(Calendar.HOUR_OF_DAY, 12)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }

    /** Absolute instant of a `…UtcMinutes` result on [date], or null.
     *  Non-finite minutes are rejected here: within a hair of the
     *  geographic poles `cos(latitude)` underflows and [eventUtcMinutes]
     *  divides by ~0, producing a NaN that slips past its own ±1 domain
     *  check (NaN compares false against everything). Better no window
     *  than an instant in the year 292 million. */
    private fun instant(date: SolarDate, minutes: Double?): Long? {
        if (minutes == null || !minutes.isFinite()) return null
        return date.utcMidnightMs + (minutes * 60_000.0).toLong()
    }

    /**
     * The dark window that follows the local noon of [dayMs]'s day, or
     * null when the sun never drops to [ASTRO_DARK_ELEVATION_DEG] between
     * the two noons (an arctic summer night, which is not a dark one).
     *
     * [timeZone] is INJECTED rather than read from the device so the
     * result is deterministic under test — the aurora window is one of
     * the few places where an off-by-one day is invisible in the UI but
     * silently wrong.
     *
     * This answers for ONE night. Which night matters right now, and how
     * far to search when none of them is dark, belong to the aurora card
     * and live in [Geomagnetic.darkWindow] / [Geomagnetic.nextDarkness] —
     * exactly where iOS keeps them.
     */
    fun nightWindow(
        dayMs: Long,
        latitude: Double,
        longitude: Double,
        timeZone: TimeZone
    ): NightWindow? {
        val noon = localNoon(dayMs, timeZone)
        val eveningNoonMs = noon.timeInMillis
        noon.add(Calendar.DAY_OF_YEAR, 1)   // Calendar.add, so a DST day is still one day
        val morningNoonMs = noon.timeInMillis

        val evening = solarDate(eveningNoonMs)
        val morning = solarDate(morningNoonMs)

        fun dusk(elevationDeg: Double): Long? = instant(
            evening,
            eventUtcMinutes(evening.year, evening.month, evening.day, latitude, longitude, elevationDeg, false)
        )
        fun dawn(elevationDeg: Double): Long? = instant(
            morning,
            eventUtcMinutes(morning.year, morning.month, morning.day, latitude, longitude, elevationDeg, true)
        )
        // A missing crossing means one of two OPPOSITE things: polar day
        // (the sun never dips that low) or continuous darkness (it never
        // climbs that high, e.g. 80 °N in December, where noon peaks at
        // −13.4°). Solar noon elevation is what tells them apart — the
        // same discriminator [times] uses for isPolarDay/isPolarNight.
        // Without this branch Ny-Ålesund reports "no darkness" all winter.
        fun darkAllDay(date: SolarDate, elevationDeg: Double): Boolean =
            noonElevationDeg(date.year, date.month, date.day, latitude, longitude) <= elevationDeg

        val start = dusk(ASTRO_DARK_ELEVATION_DEG)
            ?: if (darkAllDay(evening, ASTRO_DARK_ELEVATION_DEG)) eveningNoonMs else return null
        val end = dawn(ASTRO_DARK_ELEVATION_DEG)
            ?: if (darkAllDay(morning, ASTRO_DARK_ELEVATION_DEG)) morningNoonMs else return null
        if (end <= start) return null

        val idealStart = dusk(IDEAL_DARK_ELEVATION_DEG)
            ?: if (darkAllDay(evening, IDEAL_DARK_ELEVATION_DEG)) eveningNoonMs else null
        val idealEnd = dawn(IDEAL_DARK_ELEVATION_DEG)
            ?: if (darkAllDay(morning, IDEAL_DARK_ELEVATION_DEG)) morningNoonMs else null
        val hasIdeal = idealStart != null && idealEnd != null

        return NightWindow(
            start = start,
            end = end,
            idealStart = if (hasIdeal) idealStart else null,
            idealEnd = if (hasIdeal) idealEnd else null
        )
    }
}
