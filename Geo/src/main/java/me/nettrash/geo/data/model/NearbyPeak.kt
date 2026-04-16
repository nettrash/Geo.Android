package me.nettrash.geo.data.model

import java.util.UUID

data class NearbyPeak(
    val id: UUID,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val distance: Double,
    val bearing: Double
) {
    companion object {
        fun create(
            name: String,
            latitude: Double,
            longitude: Double,
            altitude: Double,
            distance: Double,
            bearing: Double
        ): NearbyPeak {
            // Stable UUID derived from coordinates to prevent marker flash
            val bits = java.lang.Double.doubleToLongBits(latitude) xor
                    (java.lang.Double.doubleToLongBits(longitude) shl 32)
            val uuid = UUID(bits, bits.inv())
            return NearbyPeak(uuid, name, latitude, longitude, altitude, distance, bearing)
        }
    }
}
