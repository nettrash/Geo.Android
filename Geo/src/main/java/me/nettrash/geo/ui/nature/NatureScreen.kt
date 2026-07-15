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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import me.nettrash.geo.ar.PanoramaCapture
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.ui.GeoViewModel
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

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
    val context = LocalContext.current
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

    // Min-altitude view filter (metres, 0…Everest) set by the on-screen slider.
    // A live filter — the marker overlay, the tap hit-test and the top-bar count
    // all read `filteredPeaks`, so they always agree. `< 1` = off. Persisted to
    // SharedPreferences so the chosen floor is remembered across launches.
    val naturePrefs = remember(context) {
        context.getSharedPreferences("me.nettrash.geo.nature", android.content.Context.MODE_PRIVATE)
    }
    var minPeakAltitude by remember { mutableStateOf(naturePrefs.getFloat(KEY_MIN_PEAK_ALT, 0f)) }
    val filteredPeaks = remember(peaks, minPeakAltitude) {
        if (minPeakAltitude < 1f) peaks else peaks.filter { it.altitude >= minPeakAltitude }
    }

    // Freeze-frame capture: the Filament camera surface renders via Metal/GL and
    // reads back BLACK through a normal view capture, so the shutter grabs it
    // with PixelCopy and composites it with the recorded overlay (see
    // `overlayLayer` below). `arView` is captured from the AndroidView factory.
    var isCapturing by remember { mutableStateOf(false) }
    var arView by remember { mutableStateOf<ARSceneView?>(null) }
    val captureScope = rememberCoroutineScope()
    // Records the horizon + peak-banner overlay so the shutter can read back
    // exactly what's drawn; the chrome (top bar, slider, shutter) lives outside
    // this layer so it isn't baked into the shared image.
    val overlayLayer = rememberGraphicsLayer()

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
    // Leader length in px — the hit-test targets the floating BANNER (summit
    // lifted by this), where the label is drawn, not the bare summit dot.
    val leaderPx = with(density) { PEAK_LEADER_DP.dp.toPx() }

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
                }.also { arView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Recorded overlay layer: the horizon + peak banners recorded into a
        // GraphicsLayer so the shutter can read back exactly what's drawn (the
        // Filament camera surface is captured separately via PixelCopy).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    overlayLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(overlayLayer)
                }
        ) {
            // Geometric horizon line + cardinal markers, drawn first so peak
            // markers sit on top of it.
            if (location != null && isTracking) {
                HorizonOverlay(
                    controller = controller,
                    userLocation = location,
                    barometerAltitude = barometerHeight.takeIf { it > 0 }
                )
            }

            // Peak markers — the point of the tab. Only once the camera is actually
            // tracking and the matrices are available, so a marker can't be drawn at
            // the wrong place before the AR session warms up. EVERY visible peak
            // gets a marker: no occlusion culling (peaks are kilometres away).
            if (location != null && isTracking && viewportSize != null) {
                ProjectedOverlay(
                    controller = controller,
                    userLocation = location,
                    observerAltitude = observerAlt,
                    peaks = filteredPeaks
                )
            }
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
                    .pointerInput(filteredPeaks, viewportSize, location, observerAlt) {
                        detectTapGestures { tap ->
                            nearestMarker(
                                tap, controller, location, filteredPeaks,
                                hitRadiusPx, leaderPx, observerAlt
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
            Text("${filteredPeaks.size}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)

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

        // Min-altitude filter — a vertical bar on the right edge. Drag the handle
        // up to raise the floor and hide the smaller peaks; the orange band above
        // the handle is the altitude range being shown. Mirrors iOS.
        AltitudeFilterSlider(
            minAltitude = minPeakAltitude,
            onChange = {
                minPeakAltitude = it
                naturePrefs.edit().putFloat(KEY_MIN_PEAK_ALT, it).apply()
            },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)
        )

        // Shutter — capture a frozen photo of the camera + peak overlay to share.
        // Outside the recorded overlay layer so the button isn't baked into the
        // image.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
                            PanoramaCapture.captureAndShare(context, view, overlay, filteredPeaks.size)
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
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.ar_capture),
                    tint = Color.Black,
                    modifier = Modifier.size(26.dp)
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

/** Everest — the top of the min-altitude filter's range. */
private const val MAX_FILTER_ALT = 8848f

/**
 * Vertical min-altitude filter. The handle's height on the track maps to a
 * minimum peak altitude (0 at the bottom → Everest at the top); the orange band
 * above the handle is the altitude range still shown. Tap or drag anywhere on the
 * track to set it; the value rounds to a tidy 10 m. Session-only, unobtrusive,
 * matching the view's pill styling. Mirrors iOS `AltitudeFilterSlider`.
 */
@Composable
private fun AltitudeFilterSlider(
    minAltitude: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val orange = Color(0xFFFF9800)
    val setFromY: (Float, Float) -> Unit = { y, h ->
        val clamped = y.coerceIn(0f, h)
        val raw = (1f - clamped / h) * MAX_FILTER_ALT
        onChange((raw / 10f).roundToInt() * 10f)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            if (minAltitude < 1f) "All" else "≥ ${minAltitude.toInt()} m",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(200.dp)
                .pointerInput(Unit) { detectTapGestures { setFromY(it.y, size.height.toFloat()) } }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        setFromY(change.position.y, size.height.toFloat())
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val frac = (minAltitude / MAX_FILTER_ALT).coerceIn(0f, 1f)
                val cx = size.width / 2f
                val trackW = 5.dp.toPx()
                val knobR = 9.dp.toPx()
                val handleY = (size.height - 2 * knobR) * (1f - frac) + knobR
                // Track casing.
                drawLine(Color.White.copy(alpha = 0.18f), Offset(cx, 0f), Offset(cx, size.height),
                         strokeWidth = trackW, cap = StrokeCap.Round)
                // Shown band: from the handle up to the top (higher peaks).
                drawLine(orange.copy(alpha = 0.55f), Offset(cx, 0f), Offset(cx, handleY),
                         strokeWidth = trackW, cap = StrokeCap.Round)
                // Handle: orange disc with a white ring.
                drawCircle(Color.White, radius = knobR, center = Offset(cx, handleY))
                drawCircle(orange, radius = knobR - 2.dp.toPx(), center = Offset(cx, handleY))
            }
        }
    }
}

/**
 * Screen-space nearest-marker hit-test for tap-to-identify. Recomputes each
 * peak's projected summit via [ArProjection] (the same source the overlay uses)
 * and returns the closest whose BANNER is within [hitRadiusPx] of the tap. The
 * banner sits at the leader top ([leaderPx] above the summit), so that — not the
 * bare summit dot — is the tap target, matching iOS.
 */
private fun nearestMarker(
    tap: Offset,
    controller: ArSceneController,
    userLocation: android.location.Location,
    peaks: List<NearbyPeak>,
    hitRadiusPx: Float,
    leaderPx: Float,
    observerAlt: Double
): ArMarkerSelection? {
    var best: ArMarkerSelection? = null
    var bestDist = hitRadiusPx

    for (peak in peaks) {
        // Same unified observer altitude as ProjectedOverlay's projection, so the
        // hit-test agrees with what's drawn.
        val off = ArProjection.projectGps(
            controller, userLocation, peak.latitude, peak.longitude, peak.altitude,
            observerAltitude = observerAlt
        ) ?: continue
        val target = Offset(off.x, off.y - leaderPx)   // banner anchor = leader top
        val d = hypot((target.x - tap.x).toDouble(), (target.y - tap.y).toDouble()).toFloat()
        if (d <= bestDist) { bestDist = d; best = ArMarkerSelection.Peak(peak) }
    }
    return best
}

/** Leader length (dp) from a peak's projected summit up to its floating banner.
 *  Mirrors iOS `peakBannerLeaderLength`. */
private const val PEAK_LEADER_DP = 46f

/** Counter-clockwise banner tilt (right edge lifted), so it stands up from the
 *  leader like a signpost. At −75° it's near-vertical. Mirrors iOS
 *  `bannerTiltDegrees`. */
private const val PEAK_BANNER_TILT_DEG = -75f

/**
 * Annotates each visible peak at its real summit: a dot at the projected summit,
 * a thin leader rising from it, and a slightly tilted name/altitude banner at the
 * leader top. The dot marks the exact peak; the banner floats clear so it never
 * hides the summit you're identifying. Mirrors iOS `PeakOverlayView`.
 */
@Composable
private fun ProjectedOverlay(
    controller: ArSceneController,
    userLocation: android.location.Location,
    /** Unified observer altitude (baro-preferred, else GPS) — see
     *  `ArScene.observerAlt`. */
    observerAltitude: Double,
    peaks: List<NearbyPeak>
) {
    // Subscribe to the per-frame tick so every annotation re-projects each ARCore
    // frame. The camera matrices are read through `.value` inside ArProjection,
    // which registers no Compose subscription — so without this read the markers
    // only recompose when some other observed state happens to change, and they
    // visibly jump and blink instead of tracking the camera. Mirrors iOS
    // `PeakOverlayView`'s `_ = sessionManager.frameTick`.
    val frameTick by controller.frameTick.collectAsState()
    val leaderPx = with(LocalDensity.current) { PEAK_LEADER_DP.dp.toPx() }
    val orange = Color(0xFFFF9800)

    // Project every peak once per frame. `summit` is the peak top; `anchor` (the
    // leader top) is the banner's bottom-centre.
    data class Projected(val peak: NearbyPeak, val summit: Offset, val opacity: Float, val scale: Float) {
        val anchor get() = Offset(summit.x, summit.y - leaderPx)
    }
    val projected = remember(frameTick, peaks, userLocation, observerAltitude) {
        peaks.mapNotNull { peak ->
            val off = ArProjection.projectGps(
                controller, userLocation, peak.latitude, peak.longitude, peak.altitude,
                observerAltitude = observerAltitude
            ) ?: return@mapNotNull null
            val opacity = (1.0 - (peak.distance / 50_000.0) * 0.5).coerceIn(0.5, 1.0).toFloat()
            val scale = (1.0 - (peak.distance / 50_000.0) * 0.4).coerceIn(0.6, 1.0).toFloat()
            Projected(peak, off, opacity, scale)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Leaders + summit dots — one Canvas for all of them.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lineW = 1.5.dp.toPx()
            val dotR = 2.5.dp.toPx()
            for (p in projected) {
                drawLine(
                    color = orange.copy(alpha = 0.9f * p.opacity),
                    start = p.summit, end = p.anchor,
                    strokeWidth = lineW, cap = StrokeCap.Round
                )
                drawCircle(orange.copy(alpha = p.opacity), radius = dotR, center = p.summit)
            }
        }
        // Tilted name/altitude banners at the leader tops.
        for (p in projected) {
            key(p.peak.id) {
                PeakBanner(peak = p.peak, anchor = p.anchor, tiltDeg = PEAK_BANNER_TILT_DEG,
                           scale = p.scale, opacity = p.opacity)
            }
        }
    }
}

/**
 * A peak's floating name/altitude banner. Single-line so it reads cleanly as one
 * strip when stood up near-vertical. Measures itself and pins its bottom-LEADING
 * corner to [anchor] (the leader top) via a `graphicsLayer`, rotating and scaling
 * about that corner so the banner rises from the leader tip like a signpost
 * regardless of tilt or distance scaling. Parked invisible until measured.
 * Mirrors iOS `PeakSummitBanner`.
 */
@Composable
private fun PeakBanner(peak: NearbyPeak, anchor: Offset, tiltDeg: Float, scale: Float, opacity: Float) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val orange = Color(0xFFFF9800)
    Box(
        modifier = Modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 1f)   // bottom-leading corner
                rotationZ = tiltDeg
                scaleX = scale
                scaleY = scale
                translationX = anchor.x
                translationY = anchor.y - size.height
                alpha = if (size == IntSize.Zero) 0f else opacity
            }
            .onSizeChanged { size = it }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .border(1.dp, orange.copy(alpha = 0.8f), RoundedCornerShape(7.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(peak.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (peak.altitude > 0) {
                Spacer(Modifier.width(5.dp))
                // Summit altitude in metres — the mountaineering convention
                // ("Mont Blanc 4808 m"), not kilometres.
                Text("${peak.altitude.toInt()} m", color = Color.White.copy(alpha = 0.8f),
                     fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
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
private const val KEY_MIN_PEAK_ALT = "min_peak_altitude"

private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(intent)
}
