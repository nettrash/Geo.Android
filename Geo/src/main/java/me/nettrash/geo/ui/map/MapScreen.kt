package me.nettrash.geo.ui.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import me.nettrash.geo.R
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import me.nettrash.geo.data.model.MountainInfo
import me.nettrash.geo.ui.GeoViewModel
import me.nettrash.geo.ui.components.AssetImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(modifier: Modifier = Modifier, viewModel: GeoViewModel) {
    val location by viewModel.locationManager.location.collectAsState()
    val mountainsData by viewModel.mountainsData.collectAsState()
    val historyItems by viewModel.historyItems.collectAsState()
    val context = LocalContext.current

    var selectedMountain by remember { mutableStateOf<MountainInfo?>(null) }
    var selectedHistoryDate by remember { mutableStateOf<Long?>(null) }
    var showMountainSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val userLatLng = location?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0)
    val cameraPositionState = rememberCameraPositionState {
        // Initialise at street-level zoom so the user lands on
        // "where am I" rather than country-level "Earth in
        // general". 16f ≈ buildings clearly resolvable.
        position = CameraPosition.fromLatLngZoom(userLatLng, STREET_ZOOM)
    }
    // The initializer above only runs once — if `location` was null
    // on first composition, the camera sat at (0,0). Animate to the
    // user's real position the first time we get a fix. Only fires
    // once so subsequent location updates don't yank the camera back
    // mid-pan.
    var hasCenteredOnUser by remember { mutableStateOf(false) }
    LaunchedEffect(location) {
        val loc = location
        if (loc != null && !hasCenteredOnUser) {
            hasCenteredOnUser = true
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(
                    LatLng(loc.latitude, loc.longitude),
                    STREET_ZOOM
                ),
                durationMs = 600
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = location != null,
                mapType = MapType.TERRAIN
            ),
            uiSettings = MapUiSettings(
                compassEnabled = true,
                myLocationButtonEnabled = true,
                zoomControlsEnabled = true
            )
        ) {
            // Seven Peaks markers (orange)
            mountainsData?.sevenPeaks?.mountains?.forEach { mountain ->
                val lat = mountain.coordinates?.latitude ?: return@forEach
                val lon = mountain.coordinates.longitude ?: return@forEach
                Marker(
                    state = MarkerState(position = LatLng(lat, lon)),
                    title = mountain.name ?: "Unknown",
                    snippet = "${mountain.height ?: 0} m",
                    icon = BitmapDescriptorFactory.fromResource(R.drawable.pin_top_seven),
                    onClick = {
                        selectedMountain = mountain
                        showMountainSheet = true
                        true
                    }
                )
            }

            // Highest mountains
            val sevenPeakNames = mountainsData?.sevenPeaks?.mountains?.map { it.name }?.toSet() ?: emptySet()
            mountainsData?.highest?.mountains?.filter { it.name !in sevenPeakNames }?.forEach { mountain ->
                val lat = mountain.coordinates?.latitude ?: return@forEach
                val lon = mountain.coordinates.longitude ?: return@forEach
                Marker(
                    state = MarkerState(position = LatLng(lat, lon)),
                    title = mountain.name ?: "Unknown",
                    snippet = "${mountain.height ?: 0} m",
                    icon = BitmapDescriptorFactory.fromResource(R.drawable.pin_top_peaks),
                    onClick = {
                        selectedMountain = mountain
                        showMountainSheet = true
                        true
                    }
                )
            }

            // Snow Leopard peaks
            val allOtherNames = sevenPeakNames + (mountainsData?.highest?.mountains?.map { it.name }?.toSet() ?: emptySet())
            mountainsData?.snowLeopardOfRussia?.mountains?.filter { it.name !in allOtherNames }?.forEach { mountain ->
                val lat = mountain.coordinates?.latitude ?: return@forEach
                val lon = mountain.coordinates.longitude ?: return@forEach
                Marker(
                    state = MarkerState(position = LatLng(lat, lon)),
                    title = mountain.name ?: "Unknown",
                    snippet = "${mountain.height ?: 0} m",
                    icon = BitmapDescriptorFactory.fromResource(R.drawable.pin_snow_leopard),
                    onClick = {
                        selectedMountain = mountain
                        showMountainSheet = true
                        true
                    }
                )
            }

            // History points
            historyItems.takeLast(25).forEach { item ->
                Marker(
                    state = MarkerState(position = LatLng(item.gpsLatitude, item.gpsLongitude)),
                    title = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.recordDate)),
                    snippet = "Alt: ${String.format(Locale.US, "%.0f", item.gpsAltitude)} m",
                    icon = BitmapDescriptorFactory.fromResource(R.drawable.pin_history_point),
                    onClick = {
                        selectedHistoryDate = item.recordDate
                        showHistorySheet = true
                        true
                    }
                )
            }
        }

        // Mountain detail sheet
        if (showMountainSheet && selectedMountain != null) {
            ModalBottomSheet(
                onDismissRequest = { showMountainSheet = false },
                sheetState = sheetState,
                containerColor = Color(0xFF1A1A1A)
            ) {
                val mountain = selectedMountain!!
                val unknownText = stringResource(R.string.fallback_unknown)
                Column(modifier = Modifier.padding(16.dp)) {
                    // Photo. Bundled in assets/mountains/<list>/<file>;
                    // path computed at load time so each peak knows
                    // which sub-folder it lives in. Renders nothing
                    // when the mountain has no image or the asset is
                    // missing.
                    mountain.imageAssetPath?.let { path ->
                        AssetImage(
                            path = path,
                            contentDescription = mountain.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(
                        mountain.name ?: unknownText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    DetailRow(stringResource(R.string.field_height), "${mountain.height ?: 0} m")
                    DetailRow(stringResource(R.string.field_region), mountain.partOfTheWorld ?: "-")
                    DetailRow(stringResource(R.string.field_country), mountain.country ?: "-")
                    DetailRow(stringResource(R.string.field_location), mountain.location ?: "-")
                    DetailRow(
                        stringResource(R.string.field_coordinates),
                        "${String.format(Locale.US, "%.6f", mountain.coordinates?.latitude ?: 0.0)}, " +
                        "${String.format(Locale.US, "%.6f", mountain.coordinates?.longitude ?: 0.0)}"
                    )
                    DetailRow(stringResource(R.string.field_first_ascent), mountain.firstAscent ?: "-")

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val lat = mountain.coordinates?.latitude ?: return@Button
                            val lon = mountain.coordinates.longitude ?: return@Button
                            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${mountain.name})")
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.action_show_map), color = Color.White)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // History detail sheet
        if (showHistorySheet && selectedHistoryDate != null) {
            val item = historyItems.find { it.recordDate == selectedHistoryDate }
            if (item != null) {
                ModalBottomSheet(
                    onDismissRequest = { showHistorySheet = false },
                    sheetState = sheetState,
                    containerColor = Color(0xFF1A1A1A)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val dateStr = SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault()).format(Date(item.recordDate))
                        Text(dateStr, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        val pressureLabel = stringResource(R.string.field_pressure)
                        val altitudeLabel = stringResource(R.string.field_altitude)
                        val velocityLabel = stringResource(R.string.field_velocity)
                        DetailRow(pressureLabel, "${String.format(Locale.US, "%.4f", item.barometerPressure)} kPa")
                        DetailRow(pressureLabel, "${String.format(Locale.US, "%.4f", item.barometerPressure * 7.50062)} mm Hg")
                        DetailRow(pressureLabel, "${String.format(Locale.US, "%.4f", item.barometerPressure / 101.325)} atm")
                        DetailRow("$altitudeLabel (bar)", "${String.format(Locale.US, "%.0f", item.barometerAltitude)} m")
                        DetailRow("GPS lat", "${String.format(Locale.US, "%.6f", item.gpsLatitude)}")
                        DetailRow("GPS lon", "${String.format(Locale.US, "%.6f", item.gpsLongitude)}")
                        DetailRow("$altitudeLabel (GPS)", "${String.format(Locale.US, "%.0f", item.gpsAltitude)} m")
                        DetailRow(velocityLabel, "${String.format(Locale.US, "%.1f", item.gpsVelocity)} m/s")
                        DetailRow(velocityLabel, "${String.format(Locale.US, "%.1f", item.gpsVelocity * 3.6)} km/h")
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * Street-level zoom: Google Maps' zoom scale is logarithmic; 16
 * lands at roughly "individual buildings visible" which matches the
 * "open map → see where I am" intent. 17 is too zoomed for users
 * who want to spot nearby peaks.
 */
private const val STREET_ZOOM = 16f

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.width(120.dp))
        Text(value, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}
