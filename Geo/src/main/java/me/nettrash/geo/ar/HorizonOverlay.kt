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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.util.GeoCalculations
import java.util.UUID
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
    /** The DEM-anchored observer altitude the skyline was computed with
     *  ([SkylineCalculator.observerAltitudeUsed]). When present it wins
     *  over the baro/GPS expression so the drawn line, the welded pills
     *  and the picker all share ONE altitude; `null` (before the first
     *  skyline pass) falls back to the baro-preferred sensor value. */
    observerAltitudeUsed: Double?,
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

    // CONTENT heading (degrees, 0 = N): the ARCore pose heading plus the
    // COMPOSED yaw correction (automatic true-north fix MINUS the manual
    // alignment knob). projectToScreen rotates drawn content by that same
    // composed value, so the bearing whose content sits at the screen centre
    // is exactly this — windowing by it keeps the drawn line centred while
    // the user drags the panorama into alignment. Mirrors iOS
    // `contentHeadingDeg = headingDeg − headingAlignmentDeg`.
    val headingDeg = cameraHeadingDeg(view) + controller.appliedYawOffsetDeg

    // No clamp to >= 0: a below-sea-level observer (Dead Sea, Death Valley) has a
    // real negative eye height, and the silhouette/labels must use it (matches
    // iOS, and the SkylineCalculator that picked the silhouette). The DEM-anchored
    // altitude the skyline was computed with wins when available (one source of
    // truth across skyline/pills/markers); the barometer fallback is already
    // pre-filtered to > 0 at the call site.
    val observerAlt = observerAltitudeUsed ?: barometerAltitude ?: userLocation.altitude
    val h = max(observerAlt, 1.5) // floor so very-low altitudes still draw something
    // Geometric horizon distance with the same refraction-corrected radius as
    // the skyline picker (`GeoCalculations.EFFECTIVE_EARTH_RADIUS`) — every
    // curvature term along the AR sightline must use the SAME radius or the
    // drawn line detaches from the computed silhouette.
    val geometricHorizon = GeoCalculations.horizonDistance(h, GeoCalculations.EFFECTIVE_EARTH_RADIUS)

    val sampleStepDeg = 1.0
    val headingHalfWindowDeg = 110.0

    // Build screen-space segments across the `headingDeg ± 110°` window.
    val segments = remember(skyline, view, cam, viewport, observerAlt) {
        val list = ArrayList<MutableList<Offset>>()
        var current = mutableListOf<Offset>()
        val maxSegmentGap = 600f

        // Shared projection + segment assembly for one silhouette vertex.
        // `up` is the apparent rise relative to the observer including the
        // Earth-curvature drop; for the geometric path it works out to
        // exactly `-h` — the skyline lies *h* metres below eye level.
        // **No** clamp to the geometric horizon: distant tall peaks
        // (Everest from 200 km, etc.) are visible past the sea-level
        // horizon precisely because their elevation lifts them above eye
        // level — clamping would project them at the wrong distance.
        // Off-screen culling is the projection bounds check, not distance.
        fun appendSilhouettePoint(bearingDeg: Double, distance: Double, altitude: Double) {
            val theta = Math.toRadians(bearingDeg)
            val east = distance * kotlin.math.sin(theta)
            val north = distance * kotlin.math.cos(theta)
            val curvatureDrop = (distance * distance) / (2.0 * GeoCalculations.EFFECTIVE_EARTH_RADIUS)
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
        }

        if (skyline.isNotEmpty()) {
            // Terrain mode walks the calculator's ACTUAL samples so every
            // rendered vertex IS a real skyline sample. Two reasons this
            // must not be a fixed-step lattice: (1) re-interpolating between
            // samples blends (distance, altitude) pairs linearly, but the
            // apparent angle is non-linear in that pair, so wherever a near
            // hill met a distant ridge the blend cut a V-notch *below* both
            // real samples; (2) the adaptive bearing refinement inserts
            // midpoint bearings at silhouette discontinuities, so the sample
            // set is intentionally non-uniform — a lattice walk would skip
            // exactly the extra detail it adds. Samples are selected by
            // wrap-aware heading delta and sorted by that (unwrapped) delta
            // so segments connect in screen order across the 0°/360° seam.
            skyline
                .map { it to angleDelta(it.bearing, headingDeg) }
                .filter { abs(it.second) <= headingHalfWindowDeg }
                .sortedBy { it.second }
                .forEach { (s, _) ->
                    appendSilhouettePoint(s.bearing, s.distance, s.altitude)
                }
        } else {
            // Geometric fallback keeps the fine fixed-step 1° grid (its
            // line is smooth by construction): every horizon point is at
            // sea level (alt 0) at the geometric distance.
            var bearing = headingDeg - headingHalfWindowDeg
            val upper = headingDeg + headingHalfWindowDeg
            while (bearing <= upper) {
                appendSilhouettePoint(bearing, geometricHorizon, 0.0)
                bearing += sampleStepDeg
            }
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

    // De-collision spacing in px, density-scaled from the shared dp so pills pack
    // to the same PHYSICAL tightness on every screen density (matches the dp the
    // pills themselves are sized in).
    val minSpacingPx = with(LocalDensity.current) { PEAK_LABEL_MIN_SPACING_DP.dp.toPx() }
    // Peak labels welded to the silhouette — the SAME selection the tap hit-test
    // uses (shared [weldedPeakLabels]), so only drawn pills are ever tappable.
    val peakLabels = remember(skyline, view, cam, viewport, observerAlt, headingDeg, peaks, minSpacingPx) {
        weldedPeakLabels(controller, peaks, skyline, observerAlt, headingDeg, viewport, minSpacingPx)
    }
    // Leader length / pill-float in px, density-scaled from the shared dp so it
    // matches iOS's points physically (not a raw 40px ≈ 13dp sliver).
    val leaderPx = with(LocalDensity.current) { PEAK_LABEL_LEADER_DP.dp.toPx() }

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
            // Leader line from each ridge anchor up to its floating pill, with a
            // dot marking the exact silhouette point the label identifies.
            for (label in peakLabels) {
                drawLine(
                    color = Color(0xFFFF9800).copy(alpha = 0.85f),
                    start = label.pos,
                    end = Offset(label.pos.x, label.pos.y - leaderPx),
                    strokeWidth = 1.5f,
                    cap = StrokeCap.Round
                )
                drawCircle(color = Color(0xFFFF9800), radius = 2.5f, center = label.pos)
            }
        }

        for ((label, pos) in labels) {
            CardinalLabel(label, pos)
        }
        for (label in peakLabels) {
            PeakLabel(label.name, label.altitude, label.pos, leaderPx)
        }
    }
}

/** A named peak floated above its ridge silhouette position, joined to it by a
 *  leader line drawn on the Canvas. The pill's CENTRE sits at `pos − leaderPx`,
 *  coinciding with the tap hit-test's target (matching iOS). */
@Composable
private fun PeakLabel(name: String, altitude: Double, position: Offset, leaderPx: Float) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .offset {
                // Until measured (size 0) park it off-screen so it doesn't flash
                // top-left for one frame.
                if (size == IntSize.Zero) {
                    IntOffset(-10_000, -10_000)
                } else {
                    // Pin the pill's LEADING-edge centre (its lower tip, the
                    // rotation pivot below) onto the leader-line top — the anchor
                    // lifted by leaderPx. That edge sits at the layout left edge,
                    // vertical middle, so the top-left goes to x = anchor.x,
                    // y = (anchor.y − leaderPx) − height/2. The pill then leans up
                    // off that pinned tip, and the leader line meets it there.
                    IntOffset(
                        position.x.toInt(),
                        (position.y - leaderPx - size.height / 2f).toInt()
                    )
                }
            }
            .onSizeChanged { size = it }
            // Rotate near-vertical about the LEADING-edge centre (0, 0.5) — the
            // pill's lower tip — so it claims little HORIZONTAL room on a crowded
            // ridge (the point — see [PEAK_LABEL_ROTATION_DEG]) while that tip stays
            // pinned to the leader top (placed above). Draw-time transform: the
            // measured size is unchanged, so the leader join and tap target hold.
            .graphicsLayer {
                rotationZ = PEAK_LABEL_ROTATION_DEG
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
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
    // Same refraction-corrected radius as the calculator and the overlay.
    fun angle(d: Double, alt: Double): Double =
        atan2((alt - observerAlt) - (d * d) / (2.0 * GeoCalculations.EFFECTIVE_EARTH_RADIUS), max(d, 1.0))
    val (skyDist, skyAlt) = interpolateSkyline(((peak.bearing % 360) + 360) % 360, skyline)
    val tol = Math.toRadians(1.5)
    return angle(peak.distance, peak.altitude) >= angle(skyDist, skyAlt) - tol
}

/**
 * Vertical gap a welded peak pill's CENTRE floats above its ridge anchor, in
 * **dp** (converted to px at each use site so the leader is the same physical
 * length regardless of display density — and the same ~42 as iOS's points). A
 * leader line bridges the gap so the name reads clearly off the silhouette.
 * Shared by the renderer and the tap hit-test so the visible pill and its
 * tappable target stay locked together. Mirrors iOS `peakHorizonLabelLift`.
 */
const val PEAK_LABEL_LEADER_DP = 42

/**
 * Degrees the welded peak pill is rotated (negative = counter-clockwise, so the
 * name reads bottom-to-top, "growing" up off the ridge). Near-vertical (−75°)
 * shrinks each pill's HORIZONTAL footprint to roughly half of the old flat pill,
 * so a ridge crowded with summits keeps its neighbouring labels instead of the
 * de-collision ([weldedPeakLabels] `minSpacing`) dropping them. The slight tilt
 * off pure −90° reads a touch easier than dead vertical. Flip the sign to lean
 * the other way. Android-only divergence from iOS (which keeps pills horizontal).
 */
const val PEAK_LABEL_ROTATION_DEG = -75f

/**
 * Minimum horizontal gap between welded pill anchors before the nearer one wins
 * the de-collision, in **dp** (converted to px at each call site, like
 * [PEAK_LABEL_LEADER_DP]). Kept in dp — not raw px — so de-collision stays the
 * same PHYSICAL tightness across screen densities; a px literal would pack pills
 * too tight on xxhdpi and too loose on mdpi since the pills themselves are
 * dp-sized. iOS uses `minSpacing = 54` density-independent POINTS; an Android dp
 * is the same physical unit as an iOS point (~1/160 inch), so the faithful port
 * is 54dp — matching iOS's de-collision tightness exactly. (The earlier 20dp
 * came from mistaking iOS's 54 *points* for 54 *px*, which packed labels ~2.7×
 * tighter than iOS on the same ridge.)
 */
const val PEAK_LABEL_MIN_SPACING_DP = 54

/**
 * Camera heading (degrees, 0 = N) from the AR view matrix — the same derivation
 * [HorizonOverlay] uses, exposed so the tap hit-test welds against the same
 * labels the overlay drew.
 */
fun cameraHeadingDeg(view: FloatArray): Double {
    val fwdX = -view[2]
    val fwdZ = -view[10]
    var deg = Math.toDegrees(kotlin.math.atan2(fwdX.toDouble(), (-fwdZ).toDouble()))
    if (deg < 0) deg += 360.0
    return deg
}

/**
 * Pure screen-space interpolation for the weld anchor: given the two
 * skyline samples bracketing a peak's bearing (each already projected to
 * its OWN screen point) blend the two screen points by the wrap-aware
 * bearing fraction. Because the drawn polyline connects exactly those
 * projected sample points with straight screen segments, the result lands
 * exactly ON the drawn segment — unlike the old world-space blend of
 * (distance, altitude), whose non-linear projection could put the pill's
 * dot visibly off the line. Wrap-aware: lo 354° / hi 0° with a query at
 * 358° interpolates 2/3 of the way. Extracted pure for unit testing.
 * Mirrors iOS `weldedAnchorScreenPoint`.
 */
fun weldedAnchorScreenPoint(
    bearing: Double,
    loBearing: Double,
    hiBearing: Double,
    loScreen: Offset,
    hiScreen: Offset
): Offset {
    val span = wrap(hiBearing - loBearing)
    val pos = wrap(bearing - loBearing)
    val t = if (span == 0.0) 0.0 else (pos / span).coerceIn(0.0, 1.0)
    return Offset(
        (loScreen.x + (hiScreen.x - loScreen.x) * t).toFloat(),
        (loScreen.y + (hiScreen.y - loScreen.y) * t).toFloat()
    )
}

/**
 * Screen position of the ridge silhouette point at [peak]'s bearing — the anchor
 * the welded label floats above (by [PEAK_LABEL_LEADER_DP]). Shared by the
 * renderer ([HorizonOverlay]) and the tap hit-test (NatureScreen) so the pill the
 * user sees and the point the user taps are computed identically. Projects the
 * two skyline samples around the peak's bearing with the SAME world-point math
 * as the silhouette renderer, then interpolates between those two SCREEN points
 * ([weldedAnchorScreenPoint]) so the anchor sits exactly on the drawn segment.
 * Returns null if the camera isn't tracking or the point is behind the camera.
 * Mirrors iOS `weldedLabelAnchor`.
 */
fun weldedLabelAnchor(
    controller: ArSceneController,
    peak: NearbyPeak,
    skyline: List<SkylineSample>,
    observerAlt: Double
): Offset? {
    if (skyline.isEmpty()) return null
    val cam = controller.cameraPosition.value ?: return null

    // Same up/curvature math as the silhouette renderer
    // (`appendSilhouettePoint`), applied to a sample's OWN
    // (bearing, distance, altitude).
    fun project(s: SkylineSample): Offset? {
        val theta = Math.toRadians(s.bearing)
        val east = s.distance * kotlin.math.sin(theta)
        val north = s.distance * kotlin.math.cos(theta)
        val up = (s.altitude - observerAlt) -
            (s.distance * s.distance) / (2.0 * GeoCalculations.EFFECTIVE_EARTH_RADIUS)
        val world = floatArrayOf(
            east.toFloat() + cam[0], up.toFloat() + cam[1], (-north).toFloat() + cam[2]
        )
        val screen = controller.projectToScreen(world) ?: return null
        if (!screen.x.isFinite() || !screen.y.isFinite()) return null
        return screen
    }

    if (skyline.size == 1) return project(skyline[0])

    // Bracketing samples around the peak's bearing, wrap-aware (a peak at
    // 358° brackets between the last and first samples).
    val b = wrap(peak.bearing)
    var hiIdx = firstBearingAbove(b, skyline)
    val loIdx: Int
    if (hiIdx == skyline.size || hiIdx == 0) {
        loIdx = skyline.size - 1
        hiIdx = 0
    } else {
        loIdx = hiIdx - 1
    }
    val lo = skyline[loIdx]
    val hi = skyline[hiIdx]
    val loScreen = project(lo) ?: return null
    val hiScreen = project(hi) ?: return null
    return weldedAnchorScreenPoint(b, lo.bearing, hi.bearing, loScreen, hiScreen)
}

/** A named-peak label welded to the silhouette: its peak [id] (for tap-back),
 *  display [name] + [altitude], and the ridge-anchor screen point [pos]. */
data class PeakLabelInfo(
    val id: UUID,
    val name: String,
    val altitude: Double,
    val pos: Offset
)

/**
 * Distance penalty of the peak-label importance score, metres of altitude
 * per kilometre of distance. Calibrated so that between two similar-height
 * summits the NEARER one wins its de-collision slot (any positive penalty
 * does that), but a genuinely big summit survives against small near bumps:
 * at 8 m/km a 4 000 m summit 30 km away (score 3 760) still crushes a
 * 400 m hill 3 km away (score 376) — the old nearest-first sort labelled
 * the hill and dropped the famous peak. Mirrors iOS
 * `peakScoreDistancePenaltyMetersPerKm`.
 */
const val PEAK_SCORE_DISTANCE_PENALTY_M_PER_KM = 8.0

/** Importance score used to hand out the limited welded-label slots:
 *  `altitude_msl − 8 × distance_km`. Higher is more label-worthy. Pure so
 *  the ordering is pinned by unit tests. Mirrors iOS `peakLabelScore`. */
fun peakLabelScore(altitude: Double, distance: Double): Double =
    altitude - PEAK_SCORE_DISTANCE_PENALTY_M_PER_KM * (distance / 1_000.0)

/** Pure de-collision + cap selection over scored label candidates: highest
 *  [peakLabelScore] first, keep a candidate only if its screen x stays
 *  ≥ [minSpacing] from every already-kept label, stop at [maxCount].
 *  Extracted from [weldedPeakLabels] so the slot-assignment policy is unit
 *  testable without an AR session. Mirrors iOS `selectWeldedPeakLabels`. */
fun selectWeldedPeakLabels(
    candidates: List<Pair<PeakLabelInfo, Double>>,
    minSpacing: Float,
    maxCount: Int
): List<PeakLabelInfo> {
    val ordered = candidates.sortedByDescending { it.second }
    val kept = ArrayList<PeakLabelInfo>()
    for ((label, _) in ordered) {
        if (kept.all { abs(it.pos.x - label.pos.x) >= minSpacing }) {
            kept.add(label)
            if (kept.size >= maxCount) break
        }
    }
    return kept
}

/**
 * The named-peak labels actually welded to the silhouette this frame — the
 * SINGLE source of truth for which pills are drawn, shared by the renderer
 * ([HorizonOverlay]) and the tap hit-test (NatureScreen) so a tap can only ever
 * resolve to a pill the user can actually see (no phantom targets, no
 * importance mix-up). Selection: peaks on/above the silhouette
 * ([peakOnSilhouette]), within the heading window (pass the
 * alignment-compensated content heading, not the raw camera heading), whose
 * ridge anchor projects on-screen; more IMPORTANT peaks ([peakLabelScore]:
 * altitude − 8 m/km of distance) win when pills would overlap (kept
 * >= minSpacing apart) and the count is capped. Mirrors iOS
 * `weldedPeakLabels`.
 */
fun weldedPeakLabels(
    controller: ArSceneController,
    peaks: List<NearbyPeak>,
    skyline: List<SkylineSample>,
    observerAlt: Double,
    headingDeg: Double,
    viewport: ArSceneController.IntSize,
    minSpacingPx: Float
): List<PeakLabelInfo> {
    if (skyline.isEmpty()) return emptyList()
    val headingHalfWindowDeg = 110.0
    // Pills are rotated near-vertical ([PEAK_LABEL_ROTATION_DEG]), so their
    // horizontal footprint is ~halved vs the old flat pills — tighter de-collision
    // (from the dp-based [PEAK_LABEL_MIN_SPACING_DP], passed in as px so it's the
    // same physical gap at any density) plus a higher cap keeps a crowded ridge's
    // neighbouring summits rather than dropping them.
    val minSpacing = minSpacingPx
    val maxLabels = 16
    val boundsMargin = 200f

    val candidates = ArrayList<Pair<PeakLabelInfo, Double>>()
    for (peak in peaks) {
        if (!peakOnSilhouette(peak, skyline, observerAlt)) continue
        if (abs(angleDelta(peak.bearing, headingDeg)) > headingHalfWindowDeg) continue
        val screen = weldedLabelAnchor(controller, peak, skyline, observerAlt) ?: continue
        if (screen.x < -boundsMargin || screen.x > viewport.width + boundsMargin ||
            screen.y < -boundsMargin || screen.y > viewport.height + boundsMargin
        ) continue
        candidates.add(
            PeakLabelInfo(peak.id, peak.name, peak.altitude, screen) to
                peakLabelScore(altitude = peak.altitude, distance = peak.distance)
        )
    }
    return selectWeldedPeakLabels(candidates, minSpacing, maxLabels)
}

/** Binary search over a bearing-sorted skyline: first index whose bearing is
 *  strictly greater than [query], or `samples.size` if none. O(log n) vs the
 *  old O(n) `indexOfFirst`; the per-frame horizon layout does hundreds of these
 *  lookups (every rendered bearing + every peak). [samples] MUST be sorted
 *  ascending by bearing. */
private fun firstBearingAbove(query: Double, samples: List<SkylineSample>): Int {
    var lo = 0
    var hi = samples.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (samples[mid].bearing > query) hi = mid else lo = mid + 1
    }
    return lo
}

/** Linear-interpolate the skyline `(distance, altitude)` at an arbitrary
 *  bearing in [0, 360), with circular wrap-around. The terrain renderer
 *  now walks the actual samples directly, so this remains only for
 *  arbitrary-bearing skyline queries (the peak-weld / marker-suppression
 *  paths). */
private fun interpolateSkyline(
    bearing: Double,
    samples: List<SkylineSample>
): Pair<Double, Double> {
    if (samples.isEmpty()) return 0.0 to 0.0
    if (samples.size == 1) return samples[0].distance to samples[0].altitude
    // First index whose bearing > query (O(log n) binary search), or size if
    // none. Both "before the first sample" and "after the last sample" wrap
    // around to interpolate between the last and first samples.
    var hiIdx = firstBearingAbove(bearing, samples)
    val loIdx: Int
    if (hiIdx == samples.size || hiIdx == 0) {
        loIdx = samples.size - 1
        hiIdx = 0
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
