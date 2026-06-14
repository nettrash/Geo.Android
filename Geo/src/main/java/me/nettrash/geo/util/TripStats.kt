package me.nettrash.geo.util

/**
 * One sample fed to the trip-summary math. Neutral value type so the stat
 * functions stay pure + testable, and byte-identical to iOS `TripSample`.
 */
data class TripSample(
    val timeMs: Long,
    val altitude: Double,   // barometric altitude (m) — precise relative change
    val latitude: Double,
    val longitude: Double,
    val speed: Double        // m/s
)

/** Computed summary of an outing. Distances in metres, times in seconds. */
data class TripSummary(
    val totalAscent: Double = 0.0,
    val totalDescent: Double = 0.0,
    val maxAltitude: Double = 0.0,
    val minAltitude: Double = 0.0,
    val distance: Double = 0.0,
    val movingTime: Double = 0.0
)

/**
 * Pure trip-summary math. Byte-identical to iOS `TripStats`.
 *
 * Ascent/descent use a moving-reference hysteresis so slow real climbs
 * still count while sub-3 m noise is dropped; distance is the haversine
 * path over valid GPS points; moving time sums only segments above the
 * speed floor (each capped so a background gap can't inflate it).
 */
object TripStats {
    /** Altitude deltas below this are treated as sensor noise and don't
     *  count toward ascent/descent — without it jitter inflates gain wildly. */
    const val ASCENT_SMOOTHING_METERS = 3.0
    /** Below this speed the user is considered stopped (excluded from moving time). */
    const val MOVING_SPEED_MPS = 0.5
    /** Cap a single inter-sample gap so a long pause / background gap can't
     *  inflate moving time. */
    const val MAX_SEGMENT_SECONDS = 60.0

    fun summary(samples: List<TripSample>): TripSummary {
        val first = samples.firstOrNull() ?: return TripSummary()

        var maxAlt = first.altitude
        var minAlt = first.altitude
        for (s in samples) {
            if (s.altitude > maxAlt) maxAlt = s.altitude
            if (s.altitude < minAlt) minAlt = s.altitude
        }

        var ref = first.altitude
        var ascent = 0.0
        var descent = 0.0
        var distance = 0.0
        var movingTime = 0.0

        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val cur = samples[i]

            val delta = cur.altitude - ref
            if (delta >= ASCENT_SMOOTHING_METERS) {
                ascent += delta; ref = cur.altitude
            } else if (delta <= -ASCENT_SMOOTHING_METERS) {
                descent += -delta; ref = cur.altitude
            }

            if (!(prev.latitude == 0.0 && prev.longitude == 0.0) &&
                !(cur.latitude == 0.0 && cur.longitude == 0.0)
            ) {
                distance += GeoCalculations.distanceBetween(
                    prev.latitude, prev.longitude, cur.latitude, cur.longitude
                )
            }

            val dtSec = (cur.timeMs - prev.timeMs) / 1000.0
            if (dtSec > 0 && cur.speed >= MOVING_SPEED_MPS) {
                movingTime += minOf(dtSec, MAX_SEGMENT_SECONDS)
            }
        }

        return TripSummary(ascent, descent, maxAlt, minAlt, distance, movingTime)
    }
}
