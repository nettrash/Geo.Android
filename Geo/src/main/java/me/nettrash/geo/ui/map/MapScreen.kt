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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import com.google.maps.android.compose.Circle
import me.nettrash.geo.offline.OfflinePack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(modifier: Modifier = Modifier, viewModel: GeoViewModel) {
    val location by viewModel.locationManager.location.collectAsState()
    val mountainsData by viewModel.mountainsData.collectAsState()
    val historyItems by viewModel.historyItems.collectAsState()
    val context = LocalContext.current

    // Offline expedition pack — region circles + "choose an area on the map" flow.
    val offlinePacks by viewModel.offlinePacks.collectAsState()
    val offlineDownloading by viewModel.offlinePackDownloading.collectAsState()
    val offlineStatus by viewModel.offlinePackStatus.collectAsState()
    var chooseAreaMode by remember { mutableStateOf(false) }
    var downloadRadiusKm by remember { mutableStateOf(10.0) }
    var selectedPack by remember { mutableStateOf<OfflinePack?>(null) }
    var nameAction by remember { mutableStateOf<MapNameAction?>(null) }
    var nameText by remember { mutableStateOf("") }
    // Leave "choose area" mode automatically once a download we started finishes.
    var wasDownloading by remember { mutableStateOf(false) }
    LaunchedEffect(offlineDownloading) {
        if (wasDownloading && !offlineDownloading) chooseAreaMode = false
        wasDownloading = offlineDownloading
    }

    var selectedMountain by remember { mutableStateOf<MountainInfo?>(null) }
    var selectedHistoryDate by remember { mutableStateOf<Long?>(null) }
    var showMountainSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    // Reuse one formatter for the history detail sheet instead of
    // building a new SimpleDateFormat on every recomposition.
    val historyDateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault()) }

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
            ),
            // Lift the map's zoom controls + Google logo above the offline
            // control bar at the bottom so the bar doesn't cover/intercept them.
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Hoist the per-pin BitmapDescriptors and the marker date
            // formatter so dozens of markers reuse one instance each
            // instead of reallocating on every recomposition. These
            // remembers live inside the GoogleMap content scope so they
            // run after the Maps SDK is initialised, which
            // BitmapDescriptorFactory requires. (iOS hoists these too.)
            val pinTopSeven = remember { BitmapDescriptorFactory.fromResource(R.drawable.pin_top_seven) }
            val pinTopPeaks = remember { BitmapDescriptorFactory.fromResource(R.drawable.pin_top_peaks) }
            val pinSnowLeopard = remember { BitmapDescriptorFactory.fromResource(R.drawable.pin_snow_leopard) }
            val pinHistoryPoint = remember { BitmapDescriptorFactory.fromResource(R.drawable.pin_history_point) }
            val markerDateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

            // Seven Peaks markers (orange)
            mountainsData?.sevenPeaks?.mountains?.forEach { mountain ->
                val lat = mountain.coordinates?.latitude ?: return@forEach
                val lon = mountain.coordinates.longitude ?: return@forEach
                Marker(
                    state = MarkerState(position = LatLng(lat, lon)),
                    title = mountain.name ?: "Unknown",
                    snippet = "${mountain.height ?: 0} m",
                    icon = pinTopSeven,
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
                    icon = pinTopPeaks,
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
                    icon = pinSnowLeopard,
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
                    title = markerDateFormat.format(Date(item.recordDate)),
                    snippet = "Alt: ${String.format(Locale.US, "%.0f", item.gpsAltitude)} m",
                    icon = pinHistoryPoint,
                    onClick = {
                        selectedHistoryDate = item.recordDate
                        showHistorySheet = true
                        true
                    }
                )
            }

            // Offline expedition pack regions — one orange circle per saved pack
            // (tap to delete), plus a live blue preview while choosing an area.
            for (pack in offlinePacks) {
                Circle(
                    center = LatLng(pack.centerLat, pack.centerLon),
                    radius = pack.radiusKm * 1000.0,
                    fillColor = OFFLINE_ACCENT.copy(alpha = 0.12f),
                    strokeColor = OFFLINE_ACCENT,
                    strokeWidth = 4f,
                    clickable = true,
                    onClick = { selectedPack = pack }
                )
            }
            if (chooseAreaMode) {
                Circle(
                    center = cameraPositionState.position.target,
                    radius = downloadRadiusKm * 1000.0,
                    fillColor = PREVIEW_BLUE.copy(alpha = 0.12f),
                    strokeColor = PREVIEW_BLUE,
                    strokeWidth = 4f
                )
            }
        }

        // Crosshair marking the area centre while choosing an area to download.
        if (chooseAreaMode) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = PREVIEW_BLUE,
                modifier = Modifier.align(Alignment.Center).size(40.dp)
            )
        }

        // Bottom control bar: "Download a region" → radius chips + Download here,
        // with live progress while a download runs.
        Surface(
            color = Color(0xFF222222),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                when {
                    !chooseAreaMode -> {
                        Button(
                            onClick = { chooseAreaMode = true },
                            colors = ButtonDefaults.buttonColors(containerColor = OFFLINE_ACCENT),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.offline_download_region), color = Color.White) }
                    }
                    offlineDownloading -> {
                        Text(
                            if (offlineStatus.isEmpty()) stringResource(R.string.offline_downloading) else offlineStatus,
                            color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        // Indeterminate: a peaks-only pack is a single quick query
                        // with no chartable phases (was a 0%-stuck determinate bar).
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = OFFLINE_ACCENT
                        )
                    }
                    else -> {
                        Text(stringResource(R.string.offline_radius), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (r in OFFLINE_RADII) {
                                val selected = r == downloadRadiusKm
                                Button(
                                    onClick = { downloadRadiusKm = r },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) OFFLINE_ACCENT else Color.Gray.copy(alpha = 0.4f)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) { Text("${r.toInt()} km", color = Color.White, fontSize = 12.sp) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { chooseAreaMode = false }) {
                                Text(stringResource(R.string.action_cancel), color = Color.White)
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = {
                                    val c = cameraPositionState.position.target
                                    nameText = ""
                                    nameAction = MapNameAction.Download(c.latitude, c.longitude, downloadRadiusKm)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = OFFLINE_ACCENT),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text(stringResource(R.string.offline_download_here), color = Color.White) }
                        }
                    }
                }
            }
        }

        // Tap a saved region's circle → rename or delete it.
        selectedPack?.let { pack ->
            AlertDialog(
                onDismissRequest = { selectedPack = null },
                containerColor = Color(0xFF222222),
                title = { Text(pack.name, color = Color.White) },
                text = {
                    Text(
                        "${pack.peakCount} peaks · ${pack.radiusKm.toInt()} km",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { viewModel.updateOfflinePack(pack); selectedPack = null }) {
                            Text(stringResource(R.string.action_update), color = OFFLINE_ACCENT)
                        }
                        TextButton(onClick = {
                            nameText = pack.name
                            nameAction = MapNameAction.Rename(pack)
                            selectedPack = null
                        }) {
                            Text(stringResource(R.string.action_rename), color = OFFLINE_ACCENT)
                        }
                        TextButton(onClick = { viewModel.deleteOfflinePack(pack); selectedPack = null }) {
                            Text(stringResource(R.string.action_delete), color = Color(0xFFE57373))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedPack = null }) {
                        Text(stringResource(R.string.action_cancel), color = Color.White)
                    }
                }
            )
        }

        // Name a new area (download) or rename an existing one.
        nameAction?.let { action ->
            val titleRes = if (action is MapNameAction.Rename) R.string.offline_rename_title else R.string.offline_name_area
            AlertDialog(
                onDismissRequest = { nameAction = null },
                containerColor = Color(0xFF222222),
                title = { Text(stringResource(titleRes), color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = OFFLINE_ACCENT,
                            focusedBorderColor = OFFLINE_ACCENT,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        when (action) {
                            is MapNameAction.Download ->
                                viewModel.downloadOfflinePackAt(nameText, action.lat, action.lon, action.radiusKm)
                            is MapNameAction.Rename ->
                                viewModel.renameOfflinePack(action.pack, nameText)
                        }
                        nameAction = null
                    }) {
                        Text(
                            stringResource(
                                if (action is MapNameAction.Rename) R.string.action_save else R.string.offline_download_here
                            ),
                            color = OFFLINE_ACCENT
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { nameAction = null }) {
                        Text(stringResource(R.string.action_cancel), color = Color.White)
                    }
                }
            )
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
                        val dateStr = historyDateFormat.format(Date(item.recordDate))
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

private val OFFLINE_ACCENT = Color(0xFFFF9800)
private val PREVIEW_BLUE = Color(0xFF2196F3)
private val OFFLINE_RADII = listOf(5.0, 10.0, 50.0, 100.0)

/** What the Map tab's name dialog is committing — a new download or a rename. */
private sealed interface MapNameAction {
    data class Download(val lat: Double, val lon: Double, val radiusKm: Double) : MapNameAction
    data class Rename(val pack: OfflinePack) : MapNameAction
}

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
