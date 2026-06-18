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
}
