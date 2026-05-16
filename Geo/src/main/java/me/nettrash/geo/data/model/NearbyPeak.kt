package me.nettrash.geo.data.model

import java.util.UUID

/**
 * Represents a peak/mountain point of interest to display in AR.
 *
 * `distance`, `bearing`, and `lastSeenAt` are deliberately `var` so
 * [me.nettrash.geo.util.PeakFinder] can refresh them in place during
 * the merge step without destroying the marker's stable identity.
 */
data class NearbyPeak(
    val id: UUID,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    var distance: Double,
    var bearing: Double,
    var lastSeenAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun create(
            name: String,
            latitude: Double,
            longitude: Double,
            altitude: Double,
            distance: Double,
            bearing: Double,
            lastSeenAt: Long = System.currentTimeMillis()
        ): NearbyPeak {
            // Stable UUID derived from coordinates to prevent marker flash
            val bits = java.lang.Double.doubleToLongBits(latitude) xor
                    (java.lang.Double.doubleToLongBits(longitude) shl 32)
            val uuid = UUID(bits, bits.inv())
            return NearbyPeak(uuid, name, latitude, longitude, altitude, distance, bearing, lastSeenAt)
        }
    }
}
