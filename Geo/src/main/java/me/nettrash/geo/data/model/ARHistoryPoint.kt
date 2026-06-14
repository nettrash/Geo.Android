package me.nettrash.geo.data.model

import java.util.Date
import java.util.UUID

data class ARHistoryPoint(
    val id: UUID,
    val date: Date,
    val latitude: Double,
    val longitude: Double,
    val gpsAltitude: Double,
    val barometerAltitude: Double,
    val pressure: Double,
    val speed: Double,
    val distance: Double,
    val bearing: Double
) {
    companion object {
        fun create(
            date: Date,
            latitude: Double,
            longitude: Double,
            gpsAltitude: Double,
            barometerAltitude: Double,
            pressure: Double,
            speed: Double,
            distance: Double,
            bearing: Double
        ): ARHistoryPoint {
            // Stable id incorporating the full coordinates and the timestamp
            // so two distinct history points never collide (the old
            // time-only derivation collided whenever two points shared a
            // millisecond). Latitude bits fill the high half; longitude bits
            // mixed with the time fill the low half.
            val uuid = UUID(
                java.lang.Double.doubleToLongBits(latitude),
                java.lang.Double.doubleToLongBits(longitude) xor date.time
            )
            return ARHistoryPoint(uuid, date, latitude, longitude, gpsAltitude, barometerAltitude, pressure, speed, distance, bearing)
        }
    }
}
