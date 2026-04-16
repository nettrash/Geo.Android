package me.nettrash.geo.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoCalculations {

    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val EARTH_RADIUS = 6_371_000.0

    /**
     * Calculate bearing (degrees) from one coordinate to another
     */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        var b = Math.toDegrees(atan2(y, x))
        if (b < 0) b += 360.0
        return b
    }

    /**
     * Convert GPS to local ENU (East-North-Up) offset in meters.
     * Includes Earth curvature compensation for points >5km away.
     */
    data class ENUOffset(val east: Double, val north: Double, val up: Double)

    fun gpsToENU(
        fromLat: Double, fromLon: Double, fromAlt: Double,
        toLat: Double, toLon: Double, toAlt: Double
    ): ENUOffset {
        val latRef = Math.toRadians(fromLat)
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(latRef)

        val dLat = toLat - fromLat
        val dLon = toLon - fromLon

        val north = dLat * METERS_PER_DEGREE_LAT
        val east = dLon * metersPerDegreeLon

        val horizontalDist = sqrt(north * north + east * east)
        val curvatureDrop = (horizontalDist * horizontalDist) / (2.0 * EARTH_RADIUS)

        val up = (toAlt - fromAlt) - if (horizontalDist > 5000) curvatureDrop else 0.0

        return ENUOffset(east, north, up)
    }

    /**
     * Distance between two coordinates in meters (haversine)
     */
    fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS * c
    }
}
