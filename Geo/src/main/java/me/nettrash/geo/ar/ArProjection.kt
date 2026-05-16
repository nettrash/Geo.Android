package me.nettrash.geo.ar

import android.location.Location
import androidx.compose.ui.geometry.Offset
import me.nettrash.geo.util.GeoCalculations

/**
 * Pure projection helper: turns a GPS `(lat, lon, alt)` into a
 * viewport `Offset` using an [ArSceneController] for the AR camera
 * matrices.
 *
 * The function is split out from [ArSceneController] so it can be
 * unit-tested with a hand-rolled controller stub and so the marker
 * compose code doesn't need to know about ENU / camera anchors.
 *
 * Pipeline (mirrors iOS PeakOverlayView.projectGPSPoint):
 *   1. (user lat/lon/alt) + (target lat/lon/alt) →
 *      local ENU offset in metres (via [GeoCalculations.gpsToENU]).
 *   2. ENU → ARCore world space, **anchored to the camera's current
 *      position** so the direction to the peak doesn't drift as the
 *      session origin moves. ARCore uses (+X = East, +Y = Up, −Z =
 *      North) when started with the default orientation that aligns
 *      with gravity & heading.
 *   3. World → screen via [ArSceneController.projectToScreen].
 */
object ArProjection {

    /**
     * Project a target GPS coordinate to a viewport offset.
     *
     * Returns `null` if the camera isn't tracking, the point is
     * behind the camera, or it falls more than [margin] pixels
     * outside the viewport.
     */
    fun projectGps(
        controller: ArSceneController,
        userLocation: Location,
        targetLat: Double,
        targetLon: Double,
        targetAlt: Double,
        margin: Float = 50f
    ): Offset? {
        val camPos = controller.cameraPosition.value ?: return null
        val viewport = controller.viewportSize.value ?: return null

        val enu = GeoCalculations.gpsToENU(
            userLocation.latitude, userLocation.longitude, userLocation.altitude,
            targetLat, targetLon, targetAlt
        )

        // ARCore gravity-aligned world space: +X east, +Y up, −Z north.
        val world = floatArrayOf(
            enu.east.toFloat() + camPos[0],
            enu.up.toFloat() + camPos[1],
            (-enu.north).toFloat() + camPos[2]
        )

        val screen = controller.projectToScreen(world) ?: return null
        if (screen.x < -margin || screen.x > viewport.width + margin) return null
        if (screen.y < -margin || screen.y > viewport.height + margin) return null
        return screen
    }
}
