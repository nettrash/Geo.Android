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
            val bits = date.time
            val uuid = UUID(bits, bits.inv())
            return ARHistoryPoint(uuid, date, latitude, longitude, gpsAltitude, barometerAltitude, pressure, speed, distance, bearing)
        }
    }
}
