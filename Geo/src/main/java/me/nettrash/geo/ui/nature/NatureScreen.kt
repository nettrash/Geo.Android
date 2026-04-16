package me.nettrash.geo.ui.nature

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.delay
import me.nettrash.geo.data.model.NearbyPeak
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
    }

    val location by viewModel.locationManager.location.collectAsState()
    val peaks by viewModel.peaks.collectAsState()
    val historyPoints by viewModel.arHistoryPoints.collectAsState()
    val heading by viewModel.motionManager.heading.collectAsState()

    // Check ARCore availability
    var arAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        arAvailable = try {
            ArCoreApk.getInstance().checkAvailability(context) == ArCoreApk.Availability.SUPPORTED_INSTALLED
        } catch (e: Exception) {
            false
        }
    }

    // Periodic peak/history refresh
    LaunchedEffect(cameraPermissionGranted, arAvailable) {
        if (cameraPermissionGranted && arAvailable) {
            viewModel.motionManager.start()
            while (true) {
                viewModel.searchForPeaks()
                viewModel.loadARHistoryPoints()
                delay(5000)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.motionManager.stop()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (cameraPermissionGranted && arAvailable) {
            // AR Camera view
            AndroidView(
                factory = { ctx ->
                    ARSceneView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Peak overlay (simplified 2D overlay using bearing/heading)
            PeakOverlay(
                peaks = peaks,
                historyPoints = historyPoints,
                heading = heading,
                userAltitude = location?.altitude ?: 0.0
            )

            // Center crosshair
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Thin
                )
            }

            // Top info bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Terrain, "Peaks", tint = Color(0xFFFF9800), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("${peaks.size}", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))

                Icon(Icons.Default.LocationOn, "History", tint = Color.Cyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("${historyPoints.size}", color = Color.White, fontSize = 14.sp)

                Spacer(Modifier.weight(1f))

                Icon(
                    Icons.Default.Explore,
                    "Heading",
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(16.dp).rotate(heading)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    String.format(Locale.US, "%.0f\u00B0", heading),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else if (!cameraPermissionGranted) {
            // Camera permission needed
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    "Camera",
                    tint = Color.Gray,
                    modifier = Modifier.size(60.dp)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "Camera Access Required",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "The Nature AR view needs camera access\nto show peaks around you.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Allow Camera Access", color = Color.White)
                }
            }
        } else {
            // AR not available
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Terrain, "AR", tint = Color.Gray, modifier = Modifier.size(60.dp))
                Spacer(Modifier.height(20.dp))
                Text("ARCore Not Available", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("This device does not support ARCore.", color = Color.Gray, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun PeakOverlay(
    peaks: List<NearbyPeak>,
    historyPoints: List<me.nettrash.geo.data.model.ARHistoryPoint>,
    heading: Float,
    userAltitude: Double
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Position peaks based on bearing relative to heading
        peaks.forEach { peak ->
            val relativeBearing = ((peak.bearing - heading + 360) % 360).toFloat()
            // Only show peaks roughly in front (within ~60 degrees of center)
            if (relativeBearing in 0f..60f || relativeBearing in 300f..360f) {
                val normalizedX = if (relativeBearing <= 180) {
                    0.5f + (relativeBearing / 120f)
                } else {
                    0.5f - ((360f - relativeBearing) / 120f)
                }

                // Vertical position based on altitude difference
                val altDiff = peak.altitude - userAltitude
                val normalizedY = (0.5f - (altDiff / 5000.0).toFloat()).coerceIn(0.1f, 0.9f)

                val opacity = (1.0 - (peak.distance / 50000.0) * 0.5).coerceIn(0.5, 1.0).toFloat()
                val scale = (1.0 - (peak.distance / 50000.0) * 0.4).coerceIn(0.6, 1.0).toFloat()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = (normalizedX * 300).dp,
                            top = (normalizedY * 500).dp
                        )
                ) {
                    PeakMarkerView(peak = peak, opacity = opacity, scale = scale)
                }
            }
        }

        // History points
        historyPoints.forEach { point ->
            val relativeBearing = ((point.bearing - heading + 360) % 360).toFloat()
            if (relativeBearing in 0f..60f || relativeBearing in 300f..360f) {
                val normalizedX = if (relativeBearing <= 180) {
                    0.5f + (relativeBearing / 120f)
                } else {
                    0.5f - ((360f - relativeBearing) / 120f)
                }
                val altDiff = point.gpsAltitude - userAltitude
                val normalizedY = (0.5f - (altDiff / 1000.0).toFloat()).coerceIn(0.1f, 0.9f)
                val opacity = (1.0 - (point.distance / 50000.0) * 0.5).coerceIn(0.5, 1.0).toFloat() * 0.85f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = (normalizedX * 300).dp,
                            top = (normalizedY * 500).dp
                        )
                ) {
                    HistoryMarkerView(point = point, opacity = opacity)
                }
            }
        }
    }
}

@Composable
fun PeakMarkerView(peak: NearbyPeak, opacity: Float = 1f, scale: Float = 1f) {
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
            "\u25BC",
            color = Color(0xFFFF9800).copy(alpha = opacity),
            fontSize = (8 * scale).sp
        )
    }
}

@Composable
fun HistoryMarkerView(point: me.nettrash.geo.data.model.ARHistoryPoint, opacity: Float = 1f) {
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
