package me.nettrash.geo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Solar-event math tests for the golden-hour / sunrise-sunset feature.
 * Mirrors iOS `SolarTests` — same golden cases and tolerances, so a
 * divergence in either platform's NOAA implementation fails its tests.
 * All asserted values are minutes past 00:00 UTC of the given date.
 */
class SolarTest {

    // London, ~(51.4778, 0), midsummer 2023-06-21: sunrise ≈ 03:43 UTC,
    // sunset ≈ 20:21 UTC (well-known published values).
    @Test
    fun londonMidsummerAnchor() {
        val sunrise = Solar.eventUtcMinutes(2023, 6, 21, 51.4778, 0.0, Solar.SUNRISE_ELEVATION, true)
        val sunset = Solar.eventUtcMinutes(2023, 6, 21, 51.4778, 0.0, Solar.SUNRISE_ELEVATION, false)
        assertNotNull(sunrise); assertNotNull(sunset)
        assertEquals(223.0, sunrise!!, 15.0)   // 03:43 UTC
        assertEquals(1221.0, sunset!!, 15.0)   // 20:21 UTC
    }

    // Equator, equinox-ish: day ≈ 12 h, solar noon ≈ 12:00 UTC at lon 0.
    @Test
    fun equatorEquinoxDayLength() {
        val sunrise = Solar.eventUtcMinutes(2023, 3, 21, 0.0, 0.0, Solar.SUNRISE_ELEVATION, true)!!
        val sunset = Solar.eventUtcMinutes(2023, 3, 21, 0.0, 0.0, Solar.SUNRISE_ELEVATION, false)!!
        val noon = Solar.solarNoonUtcMinutes(2023, 3, 21, 0.0)
        assertEquals(727.0, sunset - sunrise, 20.0)  // ~12 h 07 m
        assertEquals(727.0, noon, 15.0)              // ~12:07 UTC
    }

    // Sunrise / sunset are symmetric about solar noon.
    @Test
    fun symmetryAboutNoon() {
        val noon = Solar.solarNoonUtcMinutes(2023, 6, 21, 8.0)
        val sunrise = Solar.eventUtcMinutes(2023, 6, 21, 46.0, 8.0, Solar.SUNRISE_ELEVATION, true)!!
        val sunset = Solar.eventUtcMinutes(2023, 6, 21, 46.0, 8.0, Solar.SUNRISE_ELEVATION, false)!!
        assertEquals(noon - sunrise, sunset - noon, 0.1)
    }

    // Altitude horizon-dip: from a summit the sun rises earlier and sets
    // later than at sea level.
    @Test
    fun altitudeMakesSunriseEarlierSunsetLater() {
        fun sunrise(alt: Double) = Solar.eventUtcMinutes(
            2023, 6, 21, 46.0, 8.0,
            Solar.SUNRISE_ELEVATION - Solar.horizonDipDegrees(alt), true
        )!!
        fun sunset(alt: Double) = Solar.eventUtcMinutes(
            2023, 6, 21, 46.0, 8.0,
            Solar.SUNRISE_ELEVATION - Solar.horizonDipDegrees(alt), false
        )!!
        assertTrue(sunrise(3000.0) < sunrise(0.0))
        assertTrue(sunset(3000.0) > sunset(0.0))
        assertEquals(0.0, Solar.horizonDipDegrees(0.0), 1e-9)
    }

    // Event ordering across the day.
    @Test
    fun eventOrdering() {
        fun ev(elev: Double, rising: Boolean) =
            Solar.eventUtcMinutes(2023, 6, 21, 46.0, 8.0, elev, rising)!!
        val dawn = ev(Solar.CIVIL_ELEVATION, true)
        val sunrise = ev(Solar.SUNRISE_ELEVATION, true)
        val noon = Solar.solarNoonUtcMinutes(2023, 6, 21, 8.0)
        val sunset = ev(Solar.SUNRISE_ELEVATION, false)
        val dusk = ev(Solar.CIVIL_ELEVATION, false)
        assertTrue(dawn < sunrise && sunrise < noon && noon < sunset && sunset < dusk)
    }

    // High Arctic midsummer: the sun never sets.
    @Test
    fun polarDay() {
        val sunrise = Solar.eventUtcMinutes(2023, 6, 21, 80.0, 0.0, Solar.SUNRISE_ELEVATION, true)
        assertNull(sunrise)
        assertTrue(Solar.noonElevationDeg(2023, 6, 21, 80.0, 0.0) > 0.0)
    }

    // Date-line clock zone (UTC+13, e.g. Samoa, lon −171.8): the day's solar
    // events must fall on the queried LOCAL day, not be shifted +1 day — and
    // the countdown to today's sunrise must be minutes, not ~24 h.
    @Test
    fun farEastClockZoneAnchorsToLocalDay() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-13"))  // fixed UTC+13
            val q = Calendar.getInstance()
            q.set(2026, Calendar.JUNE, 21, 6, 30, 0)
            q.set(Calendar.MILLISECOND, 0)
            val query = q.timeInMillis
            val times = Solar.times(query, -13.76, -171.8, 0.0)
            assertNotNull(times.sunrise)
            val sr = Calendar.getInstance()
            sr.timeInMillis = times.sunrise!!
            assertEquals(q.get(Calendar.YEAR), sr.get(Calendar.YEAR))
            assertEquals(q.get(Calendar.DAY_OF_YEAR), sr.get(Calendar.DAY_OF_YEAR))
            assertTrue(times.sunrise!! - query < 3 * 3600 * 1000L)  // ~19 min, not 24 h
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
