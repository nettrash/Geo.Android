package me.nettrash.geo.ar

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.util.GeoCalculations
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Draws the geometric horizon visible from the observer's location on top of
 * the AR camera feed, with cardinal-direction markers welded to true compass
 * bearings. Direct port of iOS `Nature/HorizonOverlayView.swift`.
 *
 * The line assumes a smooth sea-level Earth: every point sits at altitude 0 at
 * the geometric horizon distance `sqrt(2·R·h + h²)`, dropped by the
 * Earth-curvature term so it lands *h* metres below eye level. That makes it a
 * function of the observer's altitude and nothing else — no digital-elevation
 * model, no network, nothing that can drift out of agreement with the real
 * world.
 *
 * (An earlier version rendered a DEM-derived terrain silhouette with peak names
 * welded onto the ridge; it was removed because the modelled ridge rarely lined
 * up with the real one on camera. Peaks are now identified by their own AR
 * markers — see `ProjectedOverlay` in NatureScreen.)
 *
 * Every point is projected through [ArSceneController.projectToScreen], so the
 * line and its labels stay welded to the world as the device moves — and pick up
 * both the automatic true-north correction and the user's manual compass
 * alignment for free.
 */
@Composable
fun HorizonOverlay(
    controller: ArSceneController,
    userLocation: Location?,
    /** Barometer altitude when available (already pre-filtered to > 0 at the
     *  call site); far more accurate vertically than GPS. */
    barometerAltitude: Double?,
    modifier: Modifier = Modifier
) {
    val viewportState by controller.viewportSize.collectAsState()
    val cameraPosState by controller.cameraPosition.collectAsState()
    val isTracking by controller.isTracking.collectAsState()
    val viewMatrixState by controller.viewMatrix.collectAsState()
    // Per-frame refresh signal — the same one the peak markers use, so the
    // horizon line, the cardinal markers and the markers all move on one clock.
    val frameTick by controller.frameTick.collectAsState()

    if (userLocation == null || !isTracking) return
    // Bind to local non-null values. `by collectAsState()` produces a delegated
    // `val`; Kotlin's smart-cast doesn't fire across the null check above because
    // the getter could in principle return a different value on the next read.
    // Pinning them here also gives the `remember(...)` blocks below stable keys.
    val view: FloatArray = viewMatrixState ?: return
    val cam: FloatArray = cameraPosState ?: return
    val viewport: ArSceneController.IntSize = viewportState ?: return

    // CONTENT heading (degrees, 0 = N): the ARCore pose heading plus the COMPOSED
    // yaw correction (automatic true-north fix MINUS the manual alignment knob).
    // projectToScreen rotates drawn content by that same composed value, so the
    // bearing whose content sits at the screen centre is exactly this — windowing
    // by it keeps the drawn line centred while the user drags into alignment.
    val headingDeg = cameraHeadingDeg(view) + controller.appliedYawOffsetDeg

    // No clamp to >= 0: a below-sea-level observer (Dead Sea, Death Valley) has a
    // real negative eye height.
    val observerAlt = barometerAltitude ?: userLocation.altitude
    val h = max(observerAlt, 1.5) // floor so very-low altitudes still draw something
    val horizonDist = GeoCalculations.horizonDistance(h, GeoCalculations.EFFECTIVE_EARTH_RADIUS)

    val sampleStepDeg = 1.0
    val headingHalfWindowDeg = 110.0

    // Build screen-space segments across the `headingDeg ± 110°` window.
    val segments = remember(frameTick, viewport, observerAlt) {
        val list = ArrayList<MutableList<Offset>>()
        var current = mutableListOf<Offset>()
        val maxSegmentGap = 600f

        // Every horizon point sits at sea level (altitude 0) at `horizonDist`. Its
        // apparent height relative to the observer is therefore (0 − observerAlt),
        // further dropped by the Earth-curvature term over that distance. Together
        // these produce the standard horizon dip angle √(2h/R). Both terms are
        // bearing-independent, so hoist them out of the loop.
        val curvatureDrop = (horizonDist * horizonDist) /
            (2.0 * GeoCalculations.EFFECTIVE_EARTH_RADIUS)
        val up = -observerAlt - curvatureDrop

        var bearing = headingDeg - headingHalfWindowDeg
        val upper = headingDeg + headingHalfWindowDeg
        while (bearing <= upper) {
            val theta = Math.toRadians(bearing)
            val east = horizonDist * kotlin.math.sin(theta)
            val north = horizonDist * kotlin.math.cos(theta)

            val world = floatArrayOf(
                east.toFloat() + cam[0],
                up.toFloat() + cam[1],
                (-north).toFloat() + cam[2]
            )

            val screen = controller.projectToScreen(world)
            if (screen != null &&
                screen.x.isFinite() && screen.y.isFinite() &&
                screen.x > -200 && screen.x < viewport.width + 200 &&
                screen.y > -200 && screen.y < viewport.height + 200
            ) {
                val last = current.lastOrNull()
                if (last != null && hypot(screen.x - last.x, screen.y - last.y) > maxSegmentGap) {
                    if (current.size >= 2) list.add(current)
                    current = mutableListOf()
                }
                current.add(screen)
            } else if (current.isNotEmpty()) {
                if (current.size >= 2) list.add(current)
                current = mutableListOf()
            }
            bearing += sampleStepDeg
        }
        if (current.size >= 2) list.add(current)
        list
    }

    // Cardinal + intercardinal markers, anchored to true compass bearings. They
    // sit at `-h` (rather than the line's deeper `up`), which floats them a
    // fraction of a degree above the line so they don't collide with it.
    val labels = remember(frameTick, viewport, observerAlt) {
        buildList {
            val cardinals = listOf(
                "N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0,
                "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0
            )
            for ((label, deg) in cardinals) {
                if (abs(angleDelta(deg, headingDeg)) > headingHalfWindowDeg) continue
                val theta = Math.toRadians(deg)
                val east = horizonDist * kotlin.math.sin(theta)
                val north = horizonDist * kotlin.math.cos(theta)
                val world = floatArrayOf(
                    east.toFloat() + cam[0],
                    (-h).toFloat() + cam[1],
                    (-north).toFloat() + cam[2]
                )
                val screen = controller.projectToScreen(world) ?: continue
                if (!screen.x.isFinite() || !screen.y.isFinite()) continue
                add(label to screen)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Widths in dp, NOT raw px: iOS strokes these in points (4 pt casing,
            // 1.5 pt core), and 1 dp ≡ 1 point physically. As raw px they were
            // ~1/3 the weight on a 3x screen, so the line read as a faint thread.
            val casingWidth = HORIZON_CASING_DP.dp.toPx()
            val coreWidth = HORIZON_CORE_DP.dp.toPx()
            for (segment in segments) {
                if (segment.size < 2) continue
                val path = Path().apply {
                    moveTo(segment.first().x, segment.first().y)
                    for (p in segment.drop(1)) lineTo(p.x, p.y)
                }
                // Soft white casing under a thin cyan core, so the line stays
                // legible against both bright sky and dark ground.
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.25f),
                    style = Stroke(width = casingWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    // iOS `.cyan` (0xFF00FFFF) at 0.85 — a more saturated line than
                    // the old light-cyan 0xFF80DEEA.
                    color = Color.Cyan.copy(alpha = 0.85f),
                    style = Stroke(width = coreWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        for ((label, pos) in labels) {
            CardinalLabel(label, pos)
        }
    }
}

/** Horizon-line stroke weights, in dp (≡ iOS points). */
private const val HORIZON_CASING_DP = 4f
private const val HORIZON_CORE_DP = 1.5f

/**
 * One cardinal marker, centred on [position]. Mirrors iOS: white heavy letter on
 * a translucent-black capsule (the capsule is what keeps N/E/S/W readable against
 * a bright sky — the Android version was previously bare white text).
 *
 * `.position` on iOS centres the view on the point; here we measure the pill and
 * offset by half its size to do the same, rather than the old fixed −12/−10 guess.
 */
@Composable
private fun CardinalLabel(text: String, position: Offset) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .offset { IntOffset(position.x.toInt() - size.width / 2, position.y.toInt() - size.height / 2) }
            .onSizeChanged { size = it }
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black
        )
    }
}

/**
 * Camera heading (degrees, 0 = N, 90 = E) from an ARCore view matrix.
 *
 * The camera looks down its local −Z axis. In a view matrix (world → camera),
 * the third ROW is the camera's world-space backward vector, so the forward
 * vector is its negation. Column-major FloatArray(16): row 2 is indices 2, 6, 10.
 */
fun cameraHeadingDeg(view: FloatArray): Double {
    val east = -view[2]
    val north = view[10]
    var deg = Math.toDegrees(atan2(east.toDouble(), north.toDouble()))
    if (deg < 0) deg += 360.0
    return deg
}

/** Smallest signed difference between two angles on a 360° circle, in (-180, 180]. */
private fun angleDelta(a: Double, b: Double): Double {
    var d = (a - b) % 360.0
    if (d > 180) d -= 360.0
    if (d <= -180) d += 360.0
    return d
}
