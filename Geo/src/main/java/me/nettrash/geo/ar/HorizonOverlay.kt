package me.nettrash.geo.ar

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.util.GeoCalculations
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Draws the horizon visible from the observer's location on top of
 * the AR camera feed. Direct port of iOS `Nature/HorizonOverlayView.swift`.
 *
 * Two render modes, both projected through [ArSceneController]:
 *
 *  1. **Terrain-aware skyline** — when [skyline] is non-empty, every
 *     bearing samples its real `(distance, altitude)` from the DEM.
 *  2. **Geometric horizon** — fallback when [skyline] is empty.
 *     Assumes a sea-level Earth; every point sits at altitude 0 at
 *     the geometric horizon distance `sqrt(2·R·h + h²)`.
 *
 * Cardinal labels are always anchored to true compass bearings so the
 * user sees N/E/S/W in the right place regardless of the active mode.
 */
@Composable
fun HorizonOverlay(
    controller: ArSceneController,
    userLocation: Location?,
    barometerAltitude: Double?,
    skyline: List<SkylineSample>,
    peaks: List<NearbyPeak>,
    modifier: Modifier = Modifier
) {
    val viewportState by controller.viewportSize.collectAsState()
    val cameraPosState by controller.cameraPosition.collectAsState()
    val isTracking by controller.isTracking.collectAsState()
    val viewMatrixState by controller.viewMatrix.collectAsState()

    if (userLocation == null || !isTracking) return
    // Bind to local non-null values. `by collectAsState()` produces a
    // delegated `val`; Kotlin's smart-cast doesn't fire across the
    // null check above because the getter could in principle return
    // a different value on the next read. Pinning them here also
    // gives the `remember(...)` blocks below stable keys.
    val view: FloatArray = viewMatrixState ?: return
    val cam: FloatArray = cameraPosState ?: return
    val viewport: ArSceneController.IntSize = viewportState ?: return

    // Compute the camera heading (degrees, 0 = N) from the inverse
    // view matrix. ARCore uses a right-handed camera frame with the
    // camera looking down −Z; the world-space forward vector is the
    // negated Z column of the camera transform. The view matrix is
    // the inverse of the camera transform, and for a rotation the
    // inverse equals the transpose, so the forward vector is the
    // negated third row of the view matrix.
    val fwdX = -view[2]   // row 0, col 2 (camera-to-world east component)
    val fwdZ = -view[10]  // row 2, col 2 (camera-to-world south component)
    // In ARCore's east-up-(-)north frame, +X is east, −Z is north,
    // so heading = atan2(east, north) → atan2(fwdX, -fwdZ).
    var headingDeg = Math.toDegrees(kotlin.math.atan2(fwdX.toDouble(), (-fwdZ).toDouble()))
    if (headingDeg < 0) headingDeg += 360.0

    // No clamp to >= 0: a below-sea-level observer (Dead Sea, Death Valley) has a
    // real negative eye height, and the silhouette/labels must use it (matches
    // iOS, and the SkylineCalculator that picked the silhouette). The barometer
    // is already pre-filtered to > 0 at the call site.
    val observerAlt = barometerAltitude ?: userLocation.altitude
    val h = max(observerAlt, 1.5) // floor so very-low altitudes still draw something
    val geometricHorizon = GeoCalculations.horizonDistance(h)

    val sampleStepDeg = 1.0
    val headingHalfWindowDeg = 110.0

    // Build screen-space segments by walking from `headingDeg − 110°`
    // to `headingDeg + 110°` in 1° steps.
    val segments = remember(skyline, view, cam, viewport, observerAlt) {
        val list = ArrayList<MutableList<Offset>>()
        var current = mutableListOf<Offset>()
        val maxSegmentGap = 600f

        var bearing = headingDeg - headingHalfWindowDeg
        val upper = headingDeg + headingHalfWindowDeg
        while (bearing <= upper) {
            val (distance, altitude) = resolveBearing(bearing, skyline, geometricHorizon)
            // **No** clamp to the geometric horizon. Distant tall
            // peaks (Everest from 200 km, etc.) are visible past
            // the sea-level horizon precisely because their
            // elevation lifts them above eye level — clamping
            // would project them at the wrong distance and the
            // line would draw at the horizon instead of along the
            // real silhouette. Off-screen culling is handled by
            // the projection bounds check below, not by distance.
            val theta = Math.toRadians(bearing)
            val east = distance * kotlin.math.sin(theta)
            val north = distance * kotlin.math.cos(theta)
            val curvatureDrop = (distance * distance) / (2.0 * GeoCalculations.EARTH_RADIUS)
            val up = (altitude - observerAlt) - curvatureDrop

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

    val labels = remember(view, cam, viewport, observerAlt, headingDeg) {
        buildList {
            val cardinals = listOf(
                "N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0,
                "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0
            )
            for ((label, deg) in cardinals) {
                val delta = abs(angleDelta(deg, headingDeg))
                if (delta > headingHalfWindowDeg) continue
                val theta = Math.toRadians(deg)
                val east = geometricHorizon * kotlin.math.sin(theta)
                val north = geometricHorizon * kotlin.math.cos(theta)
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

    // Peak labels welded to the silhouette — only when a real skyline exists.
    val peakLabels = remember(skyline, view, cam, viewport, observerAlt, headingDeg, peaks) {
        if (skyline.isEmpty()) {
            emptyList()
        } else {
            val minSpacing = 104f   // wide enough that adjacent long-name pills don't overlap
            val maxLabels = 10

            data class Cand(val name: String, val altitude: Double, val pos: Offset, val distance: Double)
            val cands = ArrayList<Cand>()
            for (peak in peaks) {
                // `peakOnSilhouette` (camera-independent) decides which peaks are
                // on the ridge; the same predicate suppresses their duplicate AR
                // markers in NatureScreen.
                if (!peakOnSilhouette(peak, skyline, observerAlt)) continue
                if (abs(angleDelta(peak.bearing, headingDeg)) > headingHalfWindowDeg) continue

                val (skyDist, skyAlt) = interpolateSkyline(((peak.bearing % 360) + 360) % 360, skyline)
                val theta = Math.toRadians(peak.bearing)
                val east = skyDist * kotlin.math.sin(theta)
                val north = skyDist * kotlin.math.cos(theta)
                val up = (skyAlt - observerAlt) - (skyDist * skyDist) / (2.0 * GeoCalculations.EARTH_RADIUS)
                val world = floatArrayOf(
                    east.toFloat() + cam[0], up.toFloat() + cam[1], (-north).toFloat() + cam[2]
                )
                val screen = controller.projectToScreen(world) ?: continue
                if (!screen.x.isFinite() || !screen.y.isFinite()) continue
                if (screen.x < -200 || screen.x > viewport.width + 200 ||
                    screen.y < -200 || screen.y > viewport.height + 200
                ) continue
                cands.add(Cand(peak.name, peak.altitude, screen, peak.distance))
            }
            // Nearer (more prominent) peaks first; keep those at least
            // `minSpacing` apart horizontally, up to `maxLabels`.
            cands.sortBy { it.distance }
            val kept = ArrayList<Cand>()
            for (c in cands) {
                if (kept.all { abs(it.pos.x - c.pos.x) >= minSpacing }) {
                    kept.add(c)
                    if (kept.size >= maxLabels) break
                }
            }
            kept
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (segment in segments) {
                if (segment.size < 2) continue
                val path = Path().apply {
                    moveTo(segment.first().x, segment.first().y)
                    for (p in segment.drop(1)) lineTo(p.x, p.y)
                }
                // Soft wide stroke under a coloured stroke — matches
                // the SwiftUI version's drop-shadowy look.
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.25f),
                    style = Stroke(
                        width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round
                    )
                )
                drawPath(
                    path = path,
                    color = if (skyline.isNotEmpty()) Color(0xFF66BB6A) else Color(0xFF80DEEA),
                    style = Stroke(
                        width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round
                    )
                )
            }
        }

        for ((label, pos) in labels) {
            CardinalLabel(label, pos)
        }
        for (label in peakLabels) {
            PeakLabel(label.name, label.altitude, label.pos)
        }
    }
}

/** A named peak floated just above its ridge silhouette position. */
@Composable
private fun PeakLabel(name: String, altitude: Double, position: Offset) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .offset {
                // Centre horizontally on the ridge point and float just above it.
                // Until measured (size 0) park it off-screen so it doesn't flash
                // top-left for one frame.
                if (size == IntSize.Zero) {
                    IntOffset(-10_000, -10_000)
                } else {
                    IntOffset(
                        (position.x - size.width / 2f).toInt(),
                        (position.y - size.height - 8).toInt()
                    )
                }
            }
            .onSizeChanged { size = it }
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(0.75.dp, Color(0xFFFF9800).copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▲", color = Color(0xFFFF9800), fontSize = 8.sp)
            Spacer(Modifier.width(3.dp))
            Text(name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (altitude > 0) {
                Spacer(Modifier.width(3.dp))
                Text("${altitude.toInt()} m", color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun CardinalLabel(text: String, position: Offset) {
    Box(
        modifier = Modifier
            .offset { IntOffset(position.x.toInt() - 12, position.y.toInt() - 10) }
            .clip(RoundedCornerShape(8.dp))
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

/**
 * Camera-INDEPENDENT test: does this named peak form the visible skyline
 * silhouette (tip on/above the ridge, not occluded behind nearer, higher
 * terrain)? Drives both the welded ridge label and the suppression of the
 * peak's duplicate AR marker, so a peak shows EITHER a ridge label OR a marker.
 * Mirrors iOS `peakOnSilhouette`.
 */
fun peakOnSilhouette(peak: NearbyPeak, skyline: List<SkylineSample>, observerAlt: Double): Boolean {
    if (peak.name.isEmpty() || peak.distance < 1_000.0 || skyline.isEmpty()) return false
    fun angle(d: Double, alt: Double): Double =
        atan2((alt - observerAlt) - (d * d) / (2.0 * GeoCalculations.EARTH_RADIUS), max(d, 1.0))
    val (skyDist, skyAlt) = interpolateSkyline(((peak.bearing % 360) + 360) % 360, skyline)
    val tol = Math.toRadians(1.5)
    return angle(peak.distance, peak.altitude) >= angle(skyDist, skyAlt) - tol
}

private fun resolveBearing(
    bearing: Double,
    samples: List<SkylineSample>,
    geometricHorizon: Double
): Pair<Double, Double> {
    if (samples.isEmpty()) return geometricHorizon to 0.0
    val normalised = ((bearing % 360) + 360) % 360
    return interpolateSkyline(normalised, samples)
}

private fun interpolateSkyline(
    bearing: Double,
    samples: List<SkylineSample>
): Pair<Double, Double> {
    if (samples.isEmpty()) return 0.0 to 0.0
    if (samples.size == 1) return samples[0].distance to samples[0].altitude
    var hiIdx = samples.indexOfFirst { it.bearing > bearing }
    val loIdx: Int
    if (hiIdx == -1) {
        loIdx = samples.size - 1
        hiIdx = 0
    } else if (hiIdx == 0) {
        loIdx = samples.size - 1
    } else {
        loIdx = hiIdx - 1
    }
    val lo = samples[loIdx]
    val hi = samples[hiIdx]
    val span = wrap(hi.bearing - lo.bearing)
    val pos = wrap(bearing - lo.bearing)
    val t = if (span == 0.0) 0.0 else (pos / span).coerceIn(0.0, 1.0)
    return (lo.distance + (hi.distance - lo.distance) * t) to
        (lo.altitude + (hi.altitude - lo.altitude) * t)
}

private fun wrap(deg: Double): Double {
    val d = deg % 360
    return if (d < 0) d + 360 else d
}

private fun angleDelta(a: Double, b: Double): Double {
    var d = (a - b) % 360
    if (d > 180) d -= 360
    if (d <= -180) d += 360
    return d
}
