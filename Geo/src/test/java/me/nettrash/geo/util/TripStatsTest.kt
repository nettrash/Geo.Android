package me.nettrash.geo.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Trip-summary stats tests (M5c). Mirrors iOS `TripStatsTests` — same
 * golden inputs and expected values, so a divergence fails on the
 * affected platform.
 */
class TripStatsTest {

    private fun sample(t: Long, alt: Double, lat: Double = 45.0, lon: Double = 8.0, speed: Double = 1.0) =
        TripSample(t, alt, lat, lon, speed)

    @Test
    fun emptyAndSingle() {
        assertThat(TripStats.summary(emptyList())).isEqualTo(TripSummary())
        val s = TripStats.summary(listOf(sample(0, 123.0)))
        assertThat(s.totalAscent).isEqualTo(0.0)
        assertThat(s.totalDescent).isEqualTo(0.0)
        assertThat(s.maxAltitude).isEqualTo(123.0)
        assertThat(s.minAltitude).isEqualTo(123.0)
    }

    @Test
    fun flatWithJitterIsFiltered() {
        val alts = listOf(100.0, 101.0, 100.0, 102.0, 99.0, 101.0, 100.0)
        val s = TripStats.summary(alts.mapIndexed { i, a -> sample(i * 1000L, a) })
        assertThat(s.totalAscent).isWithin(1e-9).of(0.0)
        assertThat(s.totalDescent).isWithin(1e-9).of(0.0)
        assertThat(s.maxAltitude).isEqualTo(102.0)
        assertThat(s.minAltitude).isEqualTo(99.0)
    }

    @Test
    fun slowClimbCapturedDespiteSmallSteps() {
        val alts = listOf(100.0, 102.0, 104.0, 106.0, 108.0, 110.0)   // +2 m steps, +10 m total
        val s = TripStats.summary(alts.mapIndexed { i, a -> sample(i * 1000L, a) })
        assertThat(s.totalAscent).isWithin(1e-9).of(8.0)              // two confirmed 4 m steps; 2 m residual dropped
        assertThat(s.totalDescent).isWithin(1e-9).of(0.0)
    }

    @Test
    fun ascentDescent() {
        val s = TripStats.summary(listOf(sample(0, 100.0), sample(1000, 200.0), sample(2000, 150.0)))
        assertThat(s.totalAscent).isWithin(1e-9).of(100.0)
        assertThat(s.totalDescent).isWithin(1e-9).of(50.0)
        assertThat(s.maxAltitude).isEqualTo(200.0)
        assertThat(s.minAltitude).isEqualTo(100.0)
    }

    @Test
    fun distanceHaversine() {
        val s = TripStats.summary(
            listOf(
                sample(0, 100.0, 45.0, 8.0, 2.0),
                sample(1000, 100.0, 45.001, 8.0, 2.0)
            )
        )
        assertThat(s.distance).isWithin(1.5).of(111.2)   // ~0.001° lat ≈ 111 m
        assertThat(s.movingTime).isWithin(1e-9).of(1.0)
    }

    @Test
    fun movingTimeExcludesStops() {
        val s = TripStats.summary(
            listOf(
                sample(0, 100.0, speed = 2.0),
                sample(10_000, 100.0, speed = 2.0),    // moving 10 s
                sample(20_000, 100.0, speed = 0.1),    // stopped 10 s
                sample(30_000, 100.0, speed = 1.0)     // moving 10 s
            )
        )
        assertThat(s.movingTime).isWithin(1e-9).of(20.0)
    }

    @Test
    fun movingTimeSegmentCap() {
        val s = TripStats.summary(listOf(sample(0, 100.0, speed = 2.0), sample(120_000, 100.0, speed = 2.0)))
        assertThat(s.movingTime).isWithin(1e-9).of(60.0)   // 120 s gap capped to 60 s
    }

    @Test
    fun invalidGpsSkippedInDistance() {
        val s = TripStats.summary(
            listOf(
                sample(0, 100.0, 0.0, 0.0, 2.0),       // (0,0) invalid
                sample(1000, 100.0, 45.001, 8.0, 2.0)
            )
        )
        assertThat(s.distance).isWithin(1e-9).of(0.0)
    }
}
