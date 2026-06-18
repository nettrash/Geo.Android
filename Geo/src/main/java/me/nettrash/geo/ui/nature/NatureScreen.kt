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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import me.nettrash.geo.ar.ArOcclusionManager
import me.nettrash.geo.ar.ArProjection
import me.nettrash.geo.ar.ArSceneController
import me.nettrash.geo.ar.HorizonOverlay
import me.nettrash.geo.ar.PEAK_LABEL_LEADER_DP
import me.nettrash.geo.ar.PEAK_LABEL_MIN_SPACING_DP
import me.nettrash.geo.ar.PanoramaCapture
import me.nettrash.geo.ar.SkylineSample
import me.nettrash.geo.ar.cameraHeadingDeg
import me.nettrash.geo.ar.peakOnSilhouette
import me.nettrash.geo.ar.weldedPeakLabels
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.util.GeoCalculations
import me.nettrash.geo.ui.GeoViewModel
import java.util.Locale
import java.util.UUID
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

    // Periodic peak/history refresh — only while the AR session is
    // actually live. Mirrors iOS active-when-visible loop. Also
    // kicks the terrain skyline calculator off the same tick;
    // SkylineCalculator throttles itself to 500 m of observer
    // movement so the recompute is cheap most of the time.
    // Motion runs only while the AR session is actually active (foreground +
    // on-tab + camera/ARCore ready). Keyed on isARActive so it stops the moment
    // the app is backgrounded (appActive→false), not just on tab-leave —
    // otherwise the rotation-vector cluster would stay powered in the
    // background. AR welds labels to the live camera, so opt into the faster
    // rate for tighter tracking (the Info compass uses the cheaper default).
    DisposableEffect(isARActive) {
        if (isARActive) viewModel.motionManager.start(SensorManager.SENSOR_DELAY_GAME)
        onDispose { viewModel.motionManager.stop() }
    }

    LaunchedEffect(isARActive) {
        if (!isARActive) return@LaunchedEffect
        while (true) {
            viewModel.searchForPeaks()
            viewModel.locationManager.location.value?.let { loc ->
                viewModel.skylineCalculator.computeIfNeeded(
                    observer = loc,
                    barometerAltitude = viewModel.barometerManager.height.value.takeIf { it > 0 }
                )
            }
            delay(5_000)
        }
    }

    // Faster occlusion loop — runs every 500 ms while AR is active,
    // mirroring iOS `occlusionTimer`. Outdoor heuristic recomputed
    // each tick from GPS accuracy + distant-peak count.
    LaunchedEffect(isARActive) {
        if (!isARActive) return@LaunchedEffect
        // Reset the scene-ready gate for this fresh session and arm
        // the warm-up fallback. Mirrors iOS `sessionDidStart`. The
        // occlusion manager is a singleton, so without this the flag
        // would stay stuck at its previous value across sessions.
        viewModel.occlusionManager.sessionStarted()
        while (true) {
            val loc = viewModel.locationManager.location.value
            val currentPeaks = viewModel.peaks.value
            if (loc != null) {
                val distantPeakCount = currentPeaks.count { it.distance > 500 }
                val outdoorByAccuracy = loc.accuracy > 25f
                val outdoorByPeaks = distantPeakCount >= 3
                viewModel.occlusionManager.setOutdoor(outdoorByAccuracy || outdoorByPeaks)

                val targets = buildList {
                    for (peak in currentPeaks) {
                        val enu = GeoCalculations.gpsToENU(
                            loc.latitude, loc.longitude, loc.altitude,
                            peak.latitude, peak.longitude, peak.altitude
                        )
                        controller.cameraPosition.value?.let { cam ->
                            add(
                                ArOcclusionManager.OcclusionTarget(
                                    id = peak.id,
                                    worldPosition = floatArrayOf(
                                        enu.east.toFloat() + cam[0],
                                        enu.up.toFloat() + cam[1],
                                        (-enu.north).toFloat() + cam[2]
                                    )
                                )
                            )
                        }
                    }
                    // History points are not shown in the AR scene, so they need
                    // no occlusion targets.
                }
                if (targets.isNotEmpty()) {
                    viewModel.occlusionManager.check(targets, controller)
                }
            }
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // motionManager is stopped by the isARActive DisposableEffect above.
            controller.markUntracked()
            // Stop any in-flight skyline computation so Open-Elevation
            // batches don't keep running after the AR view tears down.
            viewModel.skylineCalculator.cancel()
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
    val wallDistance by controller.wallDistance.collectAsState()
    val distanceSource by controller.distanceSource.collectAsState()
    val skyline by viewModel.skylineCalculator.samples.collectAsState()
    val isSkylineComputing by viewModel.skylineCalculator.isComputing.collectAsState()
    val barometerHeight by viewModel.barometerManager.height.collectAsState()
    val occludedIds by viewModel.occlusionManager.occludedIds.collectAsState()
    val isDepthSupported by controller.isDepthSupported.collectAsState()
    val isSceneReady by viewModel.occlusionManager.isSceneReady.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }

    // Observer eye altitude — barometer (already pre-filtered to > 0) preferred,
    // else GPS. No clamp to >= 0 (below-sea-level observers are real). Shared by
    // the welded-peak filter and the tap hit-test so both agree with the overlay.
    val observerAlt = if (barometerHeight > 0) barometerHeight else (location?.altitude ?: 0.0)

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

    // Tap-to-identify + freeze-frame share.
    var selectedMarker by remember { mutableStateOf<ArMarkerSelection?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var arView by remember { mutableStateOf<ARSceneView?>(null) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val captureScope = rememberCoroutineScope()
    // Records the skyline+marker overlay so the shutter can read it back; the
    // Filament camera surface is captured separately via PixelCopy.
    val overlayLayer = rememberGraphicsLayer()
    val hitRadiusPx = with(density) { 56.dp.toPx() }
    // Welded-pill leader length in px (same dp as the overlay), so the hit-test
    // targets the pill centre the overlay drew.
    val leaderPx = with(density) { PEAK_LABEL_LEADER_DP.dp.toPx() }
    // De-collision spacing in px (same dp the overlay uses), so the hit-test
    // re-derives the SAME drawn label set across densities.
    val minSpacingPx = with(density) { PEAK_LABEL_MIN_SPACING_DP.dp.toPx() }
    // Measured marker sizes (id → px). Markers are TOP-LEFT-anchored on their
    // projected point, so the hit-test offsets by half the measured size to
    // compare against the visual CENTRE (matching iOS's center anchor).
    val markerSizes = remember { mutableStateMapOf<UUID, IntSize>() }

    // Peaks welded to the skyline ridge — their AR markers are suppressed so a
    // silhouette peak shows EITHER a ridge pill OR nothing, never a flat AR
    // marker. Camera-INDEPENDENT (peakOnSilhouette), so it's a stable superset of
    // what the horizon overlay actually welds: every drawn pill is suppressed
    // here, and a peak dropped from the welded labels (de-collision / heading
    // window / cap) is hidden rather than falling back to a flat, unrotated,
    // leaderless marker that wouldn't match the welded pills.
    val weldedPeakIds = remember(peaks, skyline, location, barometerHeight) {
        if (location == null || skyline.isEmpty()) {
            emptySet()
        } else {
            peaks.filter { peakOnSilhouette(it, skyline, observerAlt) }.map { it.id }.toSet()
        }
    }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { /* viewport handled via AndroidView */ }) {
        AndroidView(
            factory = { ctx ->
                ARSceneView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Enable vertical+horizontal planes for occlusion
                    // (vertical is "walls"); also try to enable depth
                    // mode and tell the controller whether it's
                    // supported so the depth-image path actually runs.
                    sessionConfiguration = { session, config ->
                        config.planeFindingMode =
                            Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        // Depth mode is OPT-IN because some devices
                        // (e.g. the test device we hit on 2026-05-16,
                        // and reportedly the Pixel 4a 5G / certain
                        // Samsung models) report
                        // `isDepthModeSupported(AUTOMATIC) == true`
                        // but then crash `Session.update` with a
                        // `FatalException` once depth-enabled frames
                        // start flowing — see the native error
                        //   spherical_rectifier.cc:159 ...
                        //   Only kUnrectifiedOriginal is supported
                        //   for ComputeDisparity.
                        // The crash happens inside sceneview-android's
                        // frame callback before any of our code runs,
                        // so it isn't catchable. Until ARCore /
                        // sceneview-android grow a recovery hook,
                        // plane occlusion alone is the safe default.
                        if (ENABLE_DEPTH_MODE) {
                            val claimsDepth = session.isDepthModeSupported(
                                Config.DepthMode.AUTOMATIC
                            )
                            if (claimsDepth) {
                                config.depthMode = Config.DepthMode.AUTOMATIC
                            }
                            controller.setDepthConfigEnabled(claimsDepth)
                        } else {
                            controller.setDepthConfigEnabled(false)
                        }
                    }
                    // Per-frame callback — push ARCore camera matrices,
                    // planes, and depth into the controller so the
                    // occlusion + projection paths stay in lockstep
                    // with the camera feed.
                    onSessionUpdated = { session, frame ->
                        controller.update(session, frame, width, height)
                    }
                }.also { arView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Captured overlay: skyline + markers recorded into a GraphicsLayer so
        // the shutter can read back exactly what's drawn here (the Filament
        // camera surface is captured separately via PixelCopy). The crosshair,
        // top bar and shutter live OUTSIDE this layer so they don't end up in
        // the shared image.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    overlayLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(overlayLayer)
                }
        ) {
            // Terrain-aware skyline (or geometric fallback) rendered
            // first so peak markers sit on top of the line.
            if (location != null && isTracking) {
                HorizonOverlay(
                    controller = controller,
                    userLocation = location,
                    barometerAltitude = barometerHeight.takeIf { it > 0 },
                    skyline = skyline,
                    peaks = peaks
                )
            }

            // Peak markers — only once the camera is actually tracking and the
            // matrices are available, so a marker can't be drawn at the wrong
            // place before the AR session warms up. Matches iOS, whose overlays
            // simply render nothing until `isTracking` (camera feed + crosshair
            // only for the first ~second) rather than showing an approximate
            // pre-tracking placement.
            if (location != null && isTracking && viewportSize != null) {
                // Suppress near markers (< NEARBY_THRESHOLD_M) until the
                // scene is ready, so they don't flash in before they can
                // be occluded by detected geometry. Far markers always
                // show. Mirrors iOS `PeakOverlayView` gating on
                // `distance >= nearbyThreshold || isSceneReady`.
                ProjectedOverlay(
                    controller = controller,
                    userLocation = location,
                    peaks = peaks.filterNot {
                        it.id in occludedIds ||
                            it.id in weldedPeakIds ||   // labelled on the ridge instead
                            (!isSceneReady && it.distance < NEARBY_THRESHOLD_M)
                    },
                    onMarkerSized = { id, size -> markerSizes[id] = size }
                )
            }
        }

        // Tap-to-identify: a tap runs a screen-space nearest-marker hit-test
        // (same projection + occlusion/near filters as ProjectedOverlay) and
        // opens the detail sheet. Only active once tracking — never over the
        // pre-tracking BearingWindow fallback. Below the top bar so its
        // long-press-for-diagnostics keeps working.
        if (location != null && isTracking) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(
                        peaks, occludedIds, isSceneReady, viewportSize,
                        skyline, weldedPeakIds, location, observerAlt, leaderPx, minSpacingPx
                    ) {
                        detectTapGestures { tap ->
                            nearestMarker(
                                tap, controller, location, peaks,
                                occludedIds, isSceneReady, hitRadiusPx, markerSizes,
                                skyline, weldedPeakIds, observerAlt, leaderPx, minSpacingPx
                            )?.let { selectedMarker = it }
                        }
                    }
            )
        }

        // Center crosshair + wall-distance label.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "+",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Thin
                )
                val label = wallDistance?.let { formatDistance(it) }
                    ?: stringResource(R.string.ar_distance_unknown)
                Text(
                    label,
                    color = if (wallDistance != null) Color.White
                            else Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Top info bar. Long-press anywhere on it to reveal the AR
        // diagnostics overlay — hidden by default so the bar stays
        // uncluttered for normal users.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { showDiagnostics = true }
                    )
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Counters reflect what's *actually drawn* in
            // ProjectedOverlay, i.e. peaks/history minus anything
            // ArOcclusionManager says is behind real geometry AND
            // minus near markers suppressed during scene warm-up.
            // Showing the raw collection sizes here would lie about
            // what the user can see on screen. Mirrors iOS
            // `visibleHistoryPoints`.
            val visiblePeaks = peaks.count {
                it.id !in occludedIds &&
                    (isSceneReady || it.distance >= NEARBY_THRESHOLD_M)
            }

            Icon(Icons.Default.Terrain, null, tint = Color(0xFFFF9800), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("$visiblePeaks", color = Color.White, fontSize = 14.sp)
            // History points are no longer shown in the AR scene.

            // "Scanning" while the AR camera isn't tracking yet OR the
            // scene-ready warm-up gate (Improvement #20) hasn't lifted.
            // The latter is the window during which near markers are
            // suppressed, so surfacing it explains the missing markers.
            // Mirrors iOS showing "Scanning" whenever `!isSceneReady`.
            if (!isTracking || !isSceneReady) {
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.ar_status_scanning),
                    color = Color(0xFFFFEB3B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (isSkylineComputing) {
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.ar_status_skyline),
                    color = Color(0xFF66BB6A),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (isDepthSupported) {
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.ar_status_depth),
                    color = Color(0xFF66BB6A),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            distanceSource?.let { src ->
                Spacer(Modifier.width(12.dp))
                Text(
                    when (src) {
                        ArSceneController.DistanceSource.DEPTH ->
                            stringResource(R.string.ar_distance_source_depth)
                        ArSceneController.DistanceSource.PLANE ->
                            stringResource(R.string.ar_distance_source_plane)
                        ArSceneController.DistanceSource.RAYCAST ->
                            stringResource(R.string.ar_distance_source_raycast)
                    },
                    color = Color(0xFF80DEEA),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

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
                fontFamily = FontFamily.Monospace
            )
        }

        // Diagnostic overlay — only when explicitly revealed via a
        // long-press on the top bar. Sits on top of everything else
        // in the outer Box.
        if (showDiagnostics) {
            ArDiagnosticsOverlay(
                controller = controller,
                occlusion = viewModel.occlusionManager,
                skyline = viewModel.skylineCalculator,
                peakCount = peaks.size,
                locationAccuracy = location?.accuracy,
                onDismiss = { showDiagnostics = false }
            )
        }

        // Shutter — capture a frozen, annotated panorama to share. Outside the
        // recorded overlay layer so the button isn't baked into the image.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                modifier = Modifier
                    .padding(bottom = 28.dp)
                    .size(68.dp)
                    .clip(CircleShape)
                    .clickable(enabled = isTracking && !isCapturing) {
                        val view = arView ?: return@clickable
                        isCapturing = true
                        captureScope.launch {
                            try {
                                // GraphicsLayer readback must happen on the main thread.
                                val overlay = overlayLayer.toImageBitmap().asAndroidBitmap()
                                val markers = peaks.count {
                                    it.id !in occludedIds && (isSceneReady || it.distance >= NEARBY_THRESHOLD_M)
                                }
                                PanoramaCapture.captureAndShare(context, view, overlay, markers)
                            } finally {
                                isCapturing = false
                            }
                        }
                    }
                    .border(4.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(if (isTracking) Color.White else Color.White.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = stringResource(R.string.ar_capture),
                        tint = Color.Black,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // Tap-to-identify detail sheet.
        selectedMarker?.let { sel ->
            MarkerDetailSheet(selection = sel, onDismiss = { selectedMarker = null })
        }
    }
}

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
    occludedIds: Set<UUID>,
    isSceneReady: Boolean,
    hitRadiusPx: Float,
    markerSizes: Map<UUID, IntSize>,
    skyline: List<SkylineSample>,
    weldedPeakIds: Set<UUID>,
    observerAlt: Double,
    leaderPx: Float,
    minSpacingPx: Float
): ArMarkerSelection? {
    var best: ArMarkerSelection? = null
    var bestDist = hitRadiusPx

    fun visible(id: UUID, distance: Double): Boolean =
        id !in occludedIds && (distance >= NEARBY_THRESHOLD_M || isSceneReady)

    // Markers are TOP-LEFT-anchored on the projected point, so compare the tap
    // against the marker's visual CENTRE (offset by half its measured size).
    fun centerDist(id: UUID, off: Offset): Float {
        val size = markerSizes[id]
        val cx = if (size != null) off.x + size.width / 2f else off.x
        val cy = if (size != null) off.y + size.height / 2f else off.y
        return hypot((cx - tap.x).toDouble(), (cy - tap.y).toDouble()).toFloat()
    }

    // Welded peaks are drawn as floating ridge pills, not AR markers. Test ONLY
    // the labels actually drawn (the same dedup'd/capped selection the overlay
    // renders) so a tap can't hit a suppressed-pill peak or resolve to a
    // nearer/farther mix-up; target the pill centre (anchor lifted by leaderPx).
    val view = controller.viewMatrix.value
    val viewport = controller.viewportSize.value
    if (view != null && viewport != null) {
        val drawn = weldedPeakLabels(
            controller, peaks, skyline, observerAlt,
            cameraHeadingDeg(view) + controller.frameYawOffsetDeg, viewport, minSpacingPx
        )
        for (label in drawn) {
            val target = Offset(label.pos.x, label.pos.y - leaderPx)
            val d = hypot((target.x - tap.x).toDouble(), (target.y - tap.y).toDouble()).toFloat()
            if (d <= bestDist) {
                val peak = peaks.firstOrNull { it.id == label.id } ?: continue
                bestDist = d; best = ArMarkerSelection.Peak(peak)
            }
        }
    }
    // Non-welded peaks keep their AR marker (welded ones are suppressed there).
    for (peak in peaks) {
        if (peak.id in weldedPeakIds) continue
        if (!visible(peak.id, peak.distance)) continue
        val off = ArProjection.projectGps(
            controller, userLocation, peak.latitude, peak.longitude, peak.altitude
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
    peaks: List<NearbyPeak>,
    onMarkerSized: (UUID, IntSize) -> Unit = { _, _ -> }
) {
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
                val off = ArProjection.projectGps(
                    controller = controller,
                    userLocation = userLocation,
                    targetLat = peak.latitude,
                    targetLon = peak.longitude,
                    targetAlt = peak.altitude
                )
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

@Composable
private fun PeakMarker(peak: NearbyPeak, opacity: Float = 1f, scale: Float = 1f) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(
                Color.Black.copy(alpha = 0.7f * opacity),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = (6 * scale).dp, vertical = (3 * scale).dp)
    ) {
        Text(
            peak.name,
            color = Color(0xFFFF9800).copy(alpha = opacity),
            fontSize = (13 * scale).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "${String.format(Locale.US, "%.1f", peak.distance / 1000)} km · ${peak.altitude.toInt()} m",
            color = Color.White.copy(alpha = opacity),
            fontSize = (10 * scale).sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "▼",
            color = Color(0xFFFF9800).copy(alpha = opacity),
            fontSize = (8 * scale).sp
        )
    }
}

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

/**
 * Markers closer than this (metres) are hidden until the AR scene is
 * ready (see `ArOcclusionManager.isSceneReady`), so near markers don't
 * flash in before they can be occluded by detected geometry. Markers
 * farther than this always show. Mirrors iOS `nearbyThreshold = 100`.
 */
private const val NEARBY_THRESHOLD_M = 100.0

/**
 * Feature flag for ARCore Depth API. Off by default — see the
 * long-form rationale in the `sessionConfiguration` block above.
 * Flip to `true` after verifying on a sufficiently large device set
 * that the depth-enabled session doesn't crash.
 */
private const val ENABLE_DEPTH_MODE = false

/**
 * Pretty-print a wall distance in centimetres / metres according to
 * magnitude. Mirrors iOS `formatDistance(_:Float)`.
 */
private fun formatDistance(meters: Float): String {
    return when {
        meters < 1.0f -> String.format(Locale.US, "%.0f cm", meters * 100)
        meters < 10.0f -> String.format(Locale.US, "%.2f m", meters)
        else -> String.format(Locale.US, "%.1f m", meters)
    }
}

private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(intent)
}
