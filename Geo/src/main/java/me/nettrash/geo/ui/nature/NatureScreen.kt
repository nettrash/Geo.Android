package me.nettrash.geo.ui.nature

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import me.nettrash.geo.R
import me.nettrash.geo.ar.ArOcclusionManager
import me.nettrash.geo.ar.ArProjection
import me.nettrash.geo.ar.ArSceneController
import me.nettrash.geo.ar.HorizonOverlay
import me.nettrash.geo.data.model.ARHistoryPoint
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.util.GeoCalculations
import me.nettrash.geo.ui.GeoViewModel
import java.util.Locale

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
    val historyPoints by viewModel.arHistoryPoints.collectAsState()
    val heading by viewModel.motionManager.heading.collectAsState()

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
    LaunchedEffect(isARActive) {
        if (!isARActive) return@LaunchedEffect
        viewModel.motionManager.start()
        while (true) {
            viewModel.searchForPeaks()
            viewModel.loadARHistoryPoints()
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
        while (true) {
            val loc = viewModel.locationManager.location.value
            val currentPeaks = viewModel.peaks.value
            val currentHistory = viewModel.arHistoryPoints.value
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
                    for (point in currentHistory) {
                        val enu = GeoCalculations.gpsToENU(
                            loc.latitude, loc.longitude, loc.altitude,
                            point.latitude, point.longitude, point.gpsAltitude
                        )
                        controller.cameraPosition.value?.let { cam ->
                            add(
                                ArOcclusionManager.OcclusionTarget(
                                    id = point.id,
                                    worldPosition = floatArrayOf(
                                        enu.east.toFloat() + cam[0],
                                        enu.up.toFloat() + cam[1],
                                        (-enu.north).toFloat() + cam[2]
                                    )
                                )
                            )
                        }
                    }
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
            viewModel.motionManager.stop()
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
            else -> ArScene(
                controller = controller,
                location = location,
                peaks = peaks,
                historyPoints = historyPoints,
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
    historyPoints: List<ARHistoryPoint>,
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
                        val supportsDepth = session.isDepthModeSupported(
                            Config.DepthMode.AUTOMATIC
                        )
                        if (supportsDepth) {
                            config.depthMode = Config.DepthMode.AUTOMATIC
                        }
                        controller.setDepthSupported(supportsDepth)
                    }
                    // Per-frame callback — push ARCore camera matrices,
                    // planes, and depth into the controller so the
                    // occlusion + projection paths stay in lockstep
                    // with the camera feed.
                    onSessionUpdated = { session, frame ->
                        controller.update(session, frame, width, height)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Terrain-aware skyline (or geometric fallback) rendered
        // first so peak markers sit on top of the line.
        if (location != null && isTracking) {
            HorizonOverlay(
                controller = controller,
                userLocation = location,
                barometerAltitude = barometerHeight.takeIf { it > 0 },
                skyline = skyline
            )
        }

        // Peak / history markers — only render when matrices are
        // available AND the user has a location, otherwise fall back
        // to the simpler bearing-window overlay used previously so
        // we still show *something* before the AR session warms up.
        if (location != null && isTracking && viewportSize != null) {
            ProjectedOverlay(
                controller = controller,
                userLocation = location,
                peaks = peaks.filterNot { it.id in occludedIds },
                historyPoints = historyPoints.filterNot { it.id in occludedIds }
            )
        } else {
            BearingWindowOverlay(
                peaks = peaks,
                historyPoints = historyPoints,
                heading = heading,
                userAltitude = location?.altitude ?: 0.0
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

        // Top info bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Terrain, null, tint = Color(0xFFFF9800), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("${peaks.size}", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))

            Icon(Icons.Default.LocationOn, null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("${historyPoints.size}", color = Color.White, fontSize = 14.sp)

            if (!isTracking) {
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
    }
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
    historyPoints: List<ARHistoryPoint>
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // History points first so peaks render on top.
        historyPoints.forEach { point ->
            val off = ArProjection.projectGps(
                controller = controller,
                userLocation = userLocation,
                targetLat = point.latitude,
                targetLon = point.longitude,
                targetAlt = point.gpsAltitude
            ) ?: return@forEach

            val opacity = (1.0 - (point.distance / 50_000.0) * 0.5).coerceIn(0.5, 1.0).toFloat() * 0.85f
            // Use Modifier.offset (not padding) — projection can yield
            // negative offsets when the marker sits just outside the
            // viewport but within the 50 px margin that `projectGps`
            // accepts. `padding()` would throw IllegalArgumentException
            // on negative values; `offset()` accepts any sign.
            Box(
                modifier = Modifier.absoluteOffset(
                    x = off.x.toInt().dp,
                    y = off.y.toInt().dp
                )
            ) {
                HistoryMarker(point = point, opacity = opacity)
            }
        }

        peaks.forEach { peak ->
            val off = ArProjection.projectGps(
                controller = controller,
                userLocation = userLocation,
                targetLat = peak.latitude,
                targetLon = peak.longitude,
                targetAlt = peak.altitude
            ) ?: return@forEach

            val opacity = (1.0 - (peak.distance / 50_000.0) * 0.5).coerceIn(0.5, 1.0).toFloat()
            val scale = (1.0 - (peak.distance / 50_000.0) * 0.4).coerceIn(0.6, 1.0).toFloat()
            Box(
                modifier = Modifier.absoluteOffset(
                    x = off.x.toInt().dp,
                    y = off.y.toInt().dp
                )
            ) {
                PeakMarker(peak = peak, opacity = opacity, scale = scale)
            }
        }
    }
}

/**
 * Fallback bearing-window overlay used while the AR session warms
 * up. Inferior to ProjectedOverlay (no altitude awareness, no
 * camera matrices) but still gives the user *something* to look at
 * in the first second after granting the camera permission.
 */
@Composable
private fun BearingWindowOverlay(
    peaks: List<NearbyPeak>,
    historyPoints: List<ARHistoryPoint>,
    heading: Float,
    userAltitude: Double
) {
    Box(modifier = Modifier.fillMaxSize()) {
        peaks.forEach { peak ->
            val rel = ((peak.bearing - heading + 360) % 360).toFloat()
            if (rel !in 0f..60f && rel !in 300f..360f) return@forEach
            val nx = if (rel <= 180) 0.5f + (rel / 120f) else 0.5f - ((360f - rel) / 120f)
            val ny = (0.5f - ((peak.altitude - userAltitude) / 5000.0).toFloat()).coerceIn(0.1f, 0.9f)
            val opacity = (1.0 - (peak.distance / 50000.0) * 0.5).coerceIn(0.5, 1.0).toFloat()
            val scale = (1.0 - (peak.distance / 50000.0) * 0.4).coerceIn(0.6, 1.0).toFloat()
            Box(modifier = Modifier.padding(start = (nx * 300).dp, top = (ny * 500).dp)) {
                PeakMarker(peak = peak, opacity = opacity, scale = scale)
            }
        }
        historyPoints.forEach { point ->
            val rel = ((point.bearing - heading + 360) % 360).toFloat()
            if (rel !in 0f..60f && rel !in 300f..360f) return@forEach
            val nx = if (rel <= 180) 0.5f + (rel / 120f) else 0.5f - ((360f - rel) / 120f)
            val ny = (0.5f - ((point.gpsAltitude - userAltitude) / 1000.0).toFloat()).coerceIn(0.1f, 0.9f)
            val opacity = (1.0 - (point.distance / 50000.0) * 0.5).coerceIn(0.5, 1.0).toFloat() * 0.85f
            Box(modifier = Modifier.padding(start = (nx * 300).dp, top = (ny * 500).dp)) {
                HistoryMarker(point = point, opacity = opacity)
            }
        }
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

@Composable
private fun HistoryMarker(point: ARHistoryPoint, opacity: Float = 1f) {
    val dateFormatter = remember { java.text.SimpleDateFormat("MMM d HH:mm", Locale.getDefault()) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(
                Color.Black.copy(alpha = 0.7f * opacity),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            dateFormatter.format(point.date),
            color = Color.Cyan.copy(alpha = opacity),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "GPS: ${point.gpsAltitude.toInt()}m  Bar: ${point.barometerAltitude.toInt()}m",
            color = Color.White.copy(alpha = opacity),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "${String.format(Locale.US, "%.0f", point.distance)} m",
            color = Color.Cyan.copy(alpha = opacity),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
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
