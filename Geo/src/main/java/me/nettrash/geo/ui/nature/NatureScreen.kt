package me.nettrash.geo.ui.nature

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.nettrash.geo.R
import me.nettrash.geo.ar.ArProjection
import me.nettrash.geo.ar.ArSceneController
import me.nettrash.geo.ar.HorizonOverlay
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.ui.GeoViewModel
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun NatureScreen(modifier: Modifier = Modifier, viewModel: GeoViewModel) {
    val context = LocalContext.current

    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    // Persist across cold launches so a user who once tapped Deny
    // sees "Open Settings" the next time they open the app rather
    // than a "Continue" that no-ops silently.
    val cameraPrefs = remember(context) {
        context.getSharedPreferences("me.nettrash.geo.camera", android.content.Context.MODE_PRIVATE)
    }
    var hasRequestedCamera by remember {
        mutableStateOf(cameraPrefs.getBoolean(KEY_CAMERA_REQUESTED, false))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        hasRequestedCamera = true
        cameraPrefs.edit().putBoolean(KEY_CAMERA_REQUESTED, true).apply()
    }

    // Track whether this view is currently the on-screen tab AND
    // the app is foregrounded. Mirrors iOS `isARActive` which is
    // `cameraPermissionGranted && isOnScreen && scenePhase == .active`.
    val lifecycleOwner = LocalLifecycleOwner.current
    var appActive by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> appActive = true
                Lifecycle.Event.ON_PAUSE  -> appActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Per-screen AR matrices. A fresh controller every recomposition
    // would defeat StateFlow continuity, so it's `remember`-ed.
    val controller = remember { ArSceneController() }

    val location by viewModel.locationManager.location.collectAsState()
    val peaks by viewModel.peaks.collectAsState()
    // AR holds the phone UPRIGHT (camera at the horizon), so use the camera-axis
    // azimuth, not the flat top-edge `heading` (that one gimbal-locks when the
    // phone is vertical, which mis-aligned the skyline and the N/E/S/W markers).
    // The Info compass, held flat, still uses `heading`. See DeviceMotionManager.
    val heading by viewModel.motionManager.cameraHeading.collectAsState()

    // Overlay anchor with 5 m hysteresis: the marker/horizon
    // projection is fed THIS rather than the raw `location` so GPS
    // jitter doesn't make markers twitch. Only updated when a new fix
    // is at least 5 m from the current anchor. Mirrors iOS
    // `refreshOverlayLocationIfNeeded`.
    var overlayLocation by remember { mutableStateOf(location) }
    LaunchedEffect(location) {
        val newLoc = location ?: return@LaunchedEffect
        val current = overlayLocation
        if (current == null || newLoc.distanceTo(current) >= 5f) {
            overlayLocation = newLoc
        }
    }

    var arAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        arAvailable = try {
            ArCoreApk.getInstance().checkAvailability(context) == ArCoreApk.Availability.SUPPORTED_INSTALLED
        } catch (e: Exception) {
            false
        }
    }

    val isARActive = cameraPermissionGranted && arAvailable && appActive

    // Motion runs only while the AR session is actually active (foreground +
    // on-tab + camera/ARCore ready). Keyed on isARActive so it stops the moment
    // the app is backgrounded (appActive→false), not just on tab-leave —
    // otherwise the rotation-vector cluster would stay powered in the
    // background. AR welds markers to the live camera, so opt into the faster
    // rate for tighter tracking (the Info compass uses the cheaper default).
    DisposableEffect(isARActive) {
        if (isARActive) viewModel.motionManager.start(SensorManager.SENSOR_DELAY_GAME)
        onDispose { viewModel.motionManager.stop() }
    }

    LaunchedEffect(isARActive) {
        if (!isARActive) return@LaunchedEffect
        while (true) {
            viewModel.searchForPeaks()
            delay(5_000)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // motionManager is stopped by the isARActive DisposableEffect above.
            controller.markUntracked()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when {
            !cameraPermissionGranted -> {
                // Mirror iOS: friendly "Continue" wording until the
                // system prompt has been shown once; switch to
                // "Open Settings" afterwards (the system prompt
                // typically won't reappear on subsequent taps once
                // denied).
                CameraPermissionGate(
                    hasBeenAsked = hasRequestedCamera,
                    onContinue = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { openAppSettings(context) }
                )
            }
            !arAvailable -> ArUnavailableScreen()
            // History points are intentionally not shown in the AR scene; they
            // remain on the Map and Stat tabs.
            else -> ArScene(
                controller = controller,
                location = overlayLocation,
                peaks = peaks,
                heading = heading,
                isARActive = isARActive,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun ArScene(
    controller: ArSceneController,
    location: android.location.Location?,
    peaks: List<NearbyPeak>,
    heading: Float,
    isARActive: Boolean,
    viewModel: GeoViewModel
) {
    val viewportSize = controller.viewportSize.collectAsState().value
    val isTracking by controller.isTracking.collectAsState()
    // Manual compass-alignment offset (session-only, lives on the controller so
    // the projection choke point and every consumer shift coherently).
    val userAlignmentDeg by controller.userAlignmentDeg.collectAsState()
    val barometerHeight by viewModel.barometerManager.height.collectAsState()

    // ONE observer altitude for every AR consumer (the horizon line, the marker
    // projection and the tap hit-test), so none can vertically detach from the
    // others. The barometer is preferred — far more accurate vertically than GPS
    // — but reads exactly 0 until its first sample lands (and permanently on
    // barometer-less devices), so a non-positive value is treated as absent. No
    // clamp to >= 0 (below-sea-level observers are real).
    val observerAlt =
        if (barometerHeight > 0) barometerHeight else (location?.altitude ?: 0.0)

    // True-north alignment: ARCore's world frame isn't north-aligned, so feed the
    // controller the device's TRUE compass heading (magnetic azimuth corrected by
    // the local declination, exactly as the Info compass does). The controller
    // diff's it against the ARCore pose heading each frame and rotates the overlay
    // into true-north alignment.
    val declination = remember(location?.latitude, location?.longitude, location?.altitude) {
        val loc = location
        if (loc != null && !(loc.latitude == 0.0 && loc.longitude == 0.0)) {
            GeomagneticField(
                loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(),
                System.currentTimeMillis()
            ).declination
        } else 0f
    }
    val trueHeading = (((heading + declination) % 360f) + 360f) % 360f
    // setCompassTrueHeading is a trivial synchronous @Volatile write, so push it
    // via SideEffect (runs after each successful composition) rather than a
    // LaunchedEffect that would cancel + relaunch a coroutine on every ~50 Hz
    // heading change just to assign one field.
    SideEffect { controller.setCompassTrueHeading(trueHeading) }

    // Tap-to-identify.
    var selectedMarker by remember { mutableStateOf<ArMarkerSelection?>(null) }
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 56.dp.toPx() }
    // Measured marker sizes (id → px). Markers are TOP-LEFT-anchored on their
    // projected point, so the hit-test offsets by half the measured size to
    // compare against the visual CENTRE (matching iOS's center anchor).
    val markerSizes = remember { mutableStateMapOf<UUID, IntSize>() }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { /* viewport handled via AndroidView */ }) {
        AndroidView(
            factory = { ctx ->
                ARSceneView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Deliberately minimal: no plane finding, no depth. The tab
                    // identifies mountains kilometres away, so scanning the room
                    // around you bought nothing but battery drain (and the depth
                    // path crashed `Session.update` outright on some devices).
                    // All we need from ARCore is a tracked, gravity-aligned
                    // camera pose to project peaks and the horizon through.
                    sessionConfiguration = { _, config ->
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    }
                    // Per-frame callback — push ARCore camera matrices into the
                    // controller so the projection stays in lockstep with the feed.
                    onSessionUpdated = { session, frame ->
                        controller.update(session, frame, width, height)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Geometric horizon line + cardinal markers, drawn first so peak markers
        // sit on top of it.
        if (location != null && isTracking) {
            HorizonOverlay(
                controller = controller,
                userLocation = location,
                barometerAltitude = barometerHeight.takeIf { it > 0 }
            )
        }

        // Peak markers — the point of the tab. Only once the camera is actually
        // tracking and the matrices are available, so a marker can't be drawn at
        // the wrong place before the AR session warms up. EVERY peak the finder
        // returns gets a marker: no occlusion culling (peaks are kilometres away,
        // nothing indoors can meaningfully occlude them) and no ridge-weld
        // suppression.
        if (location != null && isTracking && viewportSize != null) {
            ProjectedOverlay(
                controller = controller,
                userLocation = location,
                observerAltitude = observerAlt,
                peaks = peaks,
                onMarkerSized = { id, size -> markerSizes[id] = size }
            )
        }

        // Tap/pan-catch layer: a tap runs a screen-space nearest-marker
        // hit-test (same projection + occlusion/near filters as
        // ProjectedOverlay) and opens the detail sheet; a horizontal PAN
        // adjusts the manual compass alignment live. Only active once
        // tracking — never over the pre-tracking BearingWindow fallback.
        // Below the top bar so its long-press-for-diagnostics keeps working.
        //
        // Gesture composition (mirrors iOS `onTapGesture` + a
        // `DragGesture(minimumDistance: 12)`): the two detectors live in
        // separate pointerInput modifiers. The drag detector only activates
        // after horizontal touch slop, so a tap never reaches it and
        // tap-to-identify keeps working unchanged; once the slop is crossed
        // the drag consumes its position changes, which cancels the tap
        // detector for that gesture.
        if (location != null && isTracking) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(peaks, viewportSize, location, observerAlt) {
                        detectTapGestures { tap ->
                            nearestMarker(
                                tap, controller, location, peaks,
                                hitRadiusPx, markerSizes, observerAlt
                            )?.let { selectedMarker = it }
                        }
                    }
                    .pointerInput(Unit) {
                        val pxPerDp = this.density
                        var baseDeg = 0.0
                        var totalPx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                // Latch the offset at pan start; every event
                                // recomputes from base + TOTAL translation
                                // (pure, clamped ±30°) — no compounding.
                                baseDeg = controller.userAlignmentDeg.value.toDouble()
                                totalPx = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                totalPx += dragAmount
                                // px → dp so the 8-per-degree feel matches iOS
                                // points physically at every screen density.
                                // Drag right → offset up → overlay moves right,
                                // following the finger (see
                                // ArSceneController.userAlignmentDeg).
                                controller.setUserAlignment(
                                    alignmentOffsetDegrees(
                                        base = baseDeg,
                                        panTranslationDp = (totalPx / pxPerDp).toDouble()
                                    ).toFloat()
                                )
                            }
                        )
                    }
            )
        }

        // Minimal top bar: how many peaks we found, and where we're pointing.
        // Nothing else — the diagnostic chips (depth, distance source, scan and
        // skyline progress) were noise on a tab whose job is to name mountains.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // iOS uses medium-weight rounded text here (not monospace); the
            // default system font is the closest Android-native match.
            Icon(Icons.Default.Terrain, null, tint = Color(0xFFFF9800), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("${peaks.size}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)

            Spacer(Modifier.weight(1f))

            Icon(
                Icons.Default.Explore, null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(16.dp).rotate(heading)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                String.format(Locale.US, "%.0f°", heading),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Manual compass-alignment chip — visible while an alignment offset
        // is applied (≥0.5°, i.e. would display as ≥1°). Unobtrusive, matches
        // the screen's pill styling; tapping it (✕) resets the offset.
        // Session-only state — deliberately never persisted, compass error
        // differs every session. Mirrors iOS.
        if (abs(userAlignmentDeg) >= 0.5f) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { controller.setUserAlignment(0f) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⇄",
                    color = Color(0xFFFF9800),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    String.format(Locale.US, "Alignment %+.0f°", userAlignmentDeg),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(5.dp))
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Reset compass alignment",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Tap-to-identify detail sheet.
        selectedMarker?.let { sel ->
            MarkerDetailSheet(selection = sel, onDismiss = { selectedMarker = null })
        }
    }
}

/** Pan sensitivity of the manual compass alignment: horizontal drag distance
 *  (dp — the same physical unit as iOS points) per degree of offset. 8 dp/°
 *  turns a typical 5–15° compass error into a comfortable 40–120 dp swipe,
 *  precise enough to line a distant summit up with its drawn silhouette
 *  without overshooting. Mirrors iOS `alignmentPanPointsPerDegree`. */
const val ALIGNMENT_PAN_DP_PER_DEGREE = 8.0

/** Hard clamp on the total manual alignment. Compass error is realistically
 *  5–15°; ±30° is generous headroom while preventing an accidental swipe
 *  from spinning the panorama into nonsense. Mirrors iOS
 *  `alignmentMaxOffsetDeg`. */
const val ALIGNMENT_MAX_OFFSET_DEG = 30.0

/**
 * Pure pan→alignment conversion: the new alignment offset (degrees) produced
 * by a pan whose TOTAL horizontal translation is [panTranslationDp] dp,
 * starting from [base] degrees.
 *
 * SIGN: a drag RIGHT (positive translation) increases the offset, which the
 * projection choke point turns into a clockwise bearing rotation of all
 * drawn content — i.e. the overlay moves RIGHT, following the finger (see
 * `ArSceneController.userAlignmentDeg` for the full derivation). The result
 * is clamped to ±[ALIGNMENT_MAX_OFFSET_DEG]. Mirrors iOS
 * `alignmentOffsetDegrees`.
 */
fun alignmentOffsetDegrees(base: Double, panTranslationDp: Double): Double =
    (base + panTranslationDp / ALIGNMENT_PAN_DP_PER_DEGREE)
        .coerceIn(-ALIGNMENT_MAX_OFFSET_DEG, ALIGNMENT_MAX_OFFSET_DEG)

/**
 * Screen-space nearest-marker hit-test for tap-to-identify. Recomputes each
 * visible marker's projected position via [ArProjection] (the same source the
 * overlay uses) and returns the closest within [hitRadiusPx]. Applies the SAME
 * occluded / near-warm-up filter as [ProjectedOverlay] so an off-screen or
 * hidden marker can never be selected.
 */
private fun nearestMarker(
    tap: Offset,
    controller: ArSceneController,
    userLocation: android.location.Location,
    peaks: List<NearbyPeak>,
    hitRadiusPx: Float,
    markerSizes: Map<UUID, IntSize>,
    observerAlt: Double
): ArMarkerSelection? {
    var best: ArMarkerSelection? = null
    var bestDist = hitRadiusPx

    // Markers are TOP-LEFT-anchored on the projected point, so compare the tap
    // against the marker's visual CENTRE (offset by half its measured size).
    fun centerDist(id: UUID, off: Offset): Float {
        val size = markerSizes[id]
        val cx = if (size != null) off.x + size.width / 2f else off.x
        val cy = if (size != null) off.y + size.height / 2f else off.y
        return hypot((cx - tap.x).toDouble(), (cy - tap.y).toDouble()).toFloat()
    }

    for (peak in peaks) {
        // Same unified observer altitude as ProjectedOverlay's projection, so the
        // hit-test agrees with what's drawn.
        val off = ArProjection.projectGps(
            controller, userLocation, peak.latitude, peak.longitude, peak.altitude,
            observerAltitude = observerAlt
        ) ?: continue
        val d = centerDist(peak.id, off)
        if (d <= bestDist) { bestDist = d; best = ArMarkerSelection.Peak(peak) }
    }
    return best
}

/**
 * Pixel-accurate overlay: each marker is positioned by projecting
 * its GPS coordinate through the AR camera matrices.
 */
@Composable
private fun ProjectedOverlay(
    controller: ArSceneController,
    userLocation: android.location.Location,
    /** Unified observer altitude (DEM-anchored skyline value, else baro,
     *  else GPS) — see `ArScene.observerAlt`. */
    observerAltitude: Double,
    peaks: List<NearbyPeak>,
    onMarkerSized: (UUID, IntSize) -> Unit = { _, _ -> }
) {
    // Subscribe to the per-frame tick so every marker re-projects each ARCore
    // frame. The camera matrices are read through `.value` inside ArProjection,
    // which registers no Compose subscription — so without this read the markers
    // only recompose when some other observed state happens to change, and they
    // visibly jump and blink instead of tracking the camera. Mirrors iOS
    // `PeakOverlayView`'s `_ = sessionManager.frameTick`.
    val frameTick by controller.frameTick.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // `key(id)` makes each marker's animation state survive list
        // reorderings (the merge step in PeakFinder can shuffle
        // order without changing identities).
        //
        // We DON'T use `return@key` to skip off-screen markers
        // because `key` is `@Composable inline fun` and a non-local
        // return out of its body through the outer (non-inline)
        // `forEach` lambda generates a `$$$$$NON_LOCAL_RETURN$$$$$`
        // helper class that R8 can't represent in dex format.
        // A plain `if (off != null)` does the same thing and dexes.
        peaks.forEach { peak ->
            key(peak.id) {
                // Keyed on frameTick so it re-projects once per frame, and only
                // once per frame (rather than on every unrelated recomposition).
                val off = remember(frameTick, peak.id, userLocation, observerAltitude) {
                    ArProjection.projectGps(
                        controller = controller,
                        userLocation = userLocation,
                        targetLat = peak.latitude,
                        targetLon = peak.longitude,
                        targetAlt = peak.altitude,
                        observerAltitude = observerAltitude
                    )
                }
                if (off != null) {
                    val opacity = (1.0 - (peak.distance / 50_000.0) * 0.5).coerceIn(0.5, 1.0).toFloat()
                    val scale = (1.0 - (peak.distance / 50_000.0) * 0.4).coerceIn(0.6, 1.0).toFloat()
                    AnimatedMarker(target = off, onMeasured = { onMarkerSized(peak.id, it) }) {
                        PeakMarker(peak = peak, opacity = opacity, scale = scale)
                    }
                }
            }
        }
    }
}

/**
 * Position [content] at [target] with a short linear glide between
 * updates so markers don't snap when a fresh AR projection arrives.
 * Mirrors iOS's `.animation(.linear(duration: 1/30), value: screenPos)`
 * — 33 ms is fast enough to feel real-time but smooths the
 * ~half-pixel jitter from each frame's projection refresh.
 *
 * `Modifier.absoluteOffset` (taking an `IntOffset` lambda) accepts
 * negative values, which matters because the projection can yield
 * positions in the [-50, viewportSize + 50] margin so markers don't
 * pop out abruptly at the edges.
 */
@Composable
private fun AnimatedMarker(
    target: androidx.compose.ui.geometry.Offset,
    onMeasured: (IntSize) -> Unit = {},
    content: @Composable () -> Unit
) {
    val animated by animateIntOffsetAsState(
        targetValue = IntOffset(target.x.toInt(), target.y.toInt()),
        animationSpec = tween(durationMillis = 33, easing = LinearEasing),
        label = "marker_offset"
    )
    Box(
        modifier = Modifier
            .absoluteOffset { animated }
            .onSizeChanged(onMeasured)
    ) {
        content()
    }
}

/**
 * Single peak marker. Kept visually in step with iOS `PeakMarkerView`: WHITE
 * name (not orange), a dimmer "distance · altitude" detail line, a small orange
 * down-triangle pointing at the summit, and a translucent-black card with a thin
 * orange border. Uses the default (rounded-ish) system font rather than the old
 * Monospace, closer to iOS's `.rounded` while staying Android-native. Distance
 * and altitude use iOS's own m-below-1km / km-above formatting.
 */
@Composable
private fun PeakMarker(peak: NearbyPeak, opacity: Float = 1f, scale: Float = 1f) {
    val orange = Color(0xFFFF9800)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f * opacity))
            .border(1.dp, orange.copy(alpha = 0.8f * opacity), RoundedCornerShape(8.dp))
            .padding(horizontal = (8 * scale).dp, vertical = (5 * scale).dp)
    ) {
        Text(
            peak.name,
            color = Color.White.copy(alpha = opacity),
            fontSize = (13 * scale).sp,
            fontWeight = FontWeight.Bold
        )
        val detail = buildString {
            append(formatMeters(peak.distance))
            if (peak.altitude > 0) append(" · ").append(formatMeters(peak.altitude))
        }
        Text(
            detail,
            color = Color.White.copy(alpha = 0.85f * opacity),
            fontSize = (11 * scale).sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            "▼",
            color = orange.copy(alpha = opacity),
            fontSize = (8 * scale).sp
        )
    }
}

/** iOS `PeakMarkerView.formatDistance`/`formatAltitude`: whole metres below
 *  1 km, one decimal of km above. */
private fun formatMeters(meters: Double): String =
    if (meters >= 1000) String.format(Locale.US, "%.1f km", meters / 1000)
    else String.format(Locale.US, "%.0f m", meters)

/**
 * Pre-AR splash. iOS wording: title is "About the Nature view"; the
 * action button is "Continue" before the prompt has been shown, and
 * "Open Settings" once it has been permanently denied. We mirror that
 * here so a user who once tapped Deny doesn't see a useless
 * "Continue" that no longer triggers the system dialog.
 */
@Composable
private fun CameraPermissionGate(
    hasBeenAsked: Boolean,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(60.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.ar_about_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(
                if (hasBeenAsked) R.string.ar_about_denied else R.string.ar_about_undetermined
            ),
            color = Color.Gray,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 40.dp)
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = if (hasBeenAsked) onOpenSettings else onContinue,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                stringResource(
                    if (hasBeenAsked) R.string.action_open_settings else R.string.action_continue
                ),
                color = Color.White
            )
        }
    }
}

@Composable
private fun ArUnavailableScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Terrain, null, tint = Color.Gray, modifier = Modifier.size(60.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.ar_unavailable_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.ar_unavailable_detail),
            color = Color.Gray,
            fontSize = 14.sp
        )
    }
}

private const val KEY_CAMERA_REQUESTED = "camera_requested"

private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(intent)
}
